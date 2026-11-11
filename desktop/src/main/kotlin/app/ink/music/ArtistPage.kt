package app.ink.music

import androidx.compose.ui.draw.clipToBounds
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One page for every artist: their photo beside what's said of them, their name across under both; how many listen,
 * their records, how many of theirs you have; play, shuffle, radio; then the songs of theirs you have (they're why you
 * came), their best-known songs, their records (yours first), singles and EPs, videos, playlists, and the artists fans
 * also like. YouTube Music's part kept from last time at once, then read again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistPage(name: String, id: String, open: (Page) -> Unit, onClose: () -> Unit) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val records = remember(albums, name) { albums.filter { it.artist.equals(name, ignoreCase = true) }.sortedBy { it.year } }
    val theirs = remember(songs, name) { songs.filter { s -> s.artist.split(", ").any { it.equals(name, ignoreCase = true) } || s.albumArtist.equals(name, ignoreCase = true) } }
    val info by produceState<ArtistInfo?>(null, name, id) { Artists.of(name, id).collect { if (it != null || value == null) value = it } }
    val i = info
    // who fans also like, read ahead, so the next click opens at once
    LaunchedEffect(i?.id) { i?.like?.take(5)?.forEach { Artists.warm(it.name, it.id) } }
    // their best-known songs, your own copy where there is one (it plays from here or your server)
    // six, three a column (YouTube's five and the next from its list: InnerTube.top)
    val top = remember(i, theirs) { i?.top.orEmpty().take(6).map { t -> theirs.firstOrNull { it.title.equals(t.title, ignoreCase = true) } ?: t } }
    val playable = remember(top, theirs) { (top + theirs).distinctBy { it.id } }
    // their records: yours first, then YouTube's that you don't have
    val tubeAlbums = remember(i, records) { i?.albums.orEmpty().filter { t -> records.none { it.title.equals(t.title, ignoreCase = true) } } }
    // the whole of what's said of them, read over the page (2026-11-09: opened in place it pushed the page about)
    var reading by remember(name) { mutableStateOf(false) }
    // opened the first time (nothing kept): the photo's room held and the rest kept back till YouTube Music answers,
    // a second and a half at most, so it comes in once
    var patient by remember(name, id) { mutableStateOf(true) }
    LaunchedEffect(name, id) { delay(1_500); patient = false }
    val waiting = info == null && patient
    val list = pageList()
    val shown = i?.name?.ifBlank { null } ?: name
    // their photo, dithered or as it is (settings, the look), their moving one over it where Apple Music has one
    val picture = @Composable { side: androidx.compose.ui.unit.Dp ->
        val photo = i?.photo
        if (photo != null) Box(Modifier.size(side)) {
            val dithered by Moving.artistDither.collectAsState()
            val still = remember(photo) { faceSong(name, photo, photo = true) }
            if (dithered) Dithered(still, Modifier.fillMaxSize()) else AsItIs(still, Modifier.fillMaxSize())
            val c = rememberCover(still, 1080)
            ArtistVideo(shown, c?.accent ?: Ink.ink, dithered, Modifier.fillMaxSize())
            // as it is, a light photo held down so the name over its foot reads (nameDim)
            if (!dithered && c != null) {
                val dim = remember(c) { nameDim(c.image) }
                if (dim < 1f) Box(Modifier.fillMaxSize().background(Ink.black.copy(alpha = 1f - dim)))
            }
        } else Box(Modifier.size(side))
    }
    // what's said of them: its first sentence pulled out big, as a lyric is; the rest small under it, a click for the rest
    val about = @Composable { quote: androidx.compose.ui.unit.TextUnit, lines: Int ->
        // its source line ("From Wikipedia ... under Creative Commons") off, a quiet credit under it instead
        // with no bio anywhere, a line ink writes from what it knows (Bio.facts): never an empty space
        val picked by Picks.said.collectAsState()
        val bio = i?.about?.let { Bio.of(it) }?.takeIf { it.first.isNotEmpty() } ?: i?.let { info ->
            remember(info, picked, records.size, tubeAlbums.size) {
                val by = picked.filter { Curation.folded(it.artist.substringBefore(", ")) == Curation.folded(shown) }.map { it.from }.distinct()
                Bio.facts(records.size + tubeAlbums.size, info.singles.size, by, info.like.map { it.name }, info.audience)
            }?.let { it to null }
        }
        bio?.let { (about, from) ->
            val (said, all) = remember(about) { Artists.pullQuote(about) }
            // its first paragraph only: the rest is read over the page (more), and the name has the room under it
            val rest = remember(all) { all.substringBefore('\n').trim() }
            Column(Modifier.widthIn(max = 720.dp)) {
                Label("about")
                said?.let {
                    BasicText(it.uppercase(), style = headline(quote).copy(lineHeight = quote * 1.14f))
                    Spacer(Modifier.height(14.dp))
                }
                if (rest.isNotEmpty()) BasicText(
                    rest, style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 22.sp),
                    maxLines = lines, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { reading = true }),
                )
                if (all.isNotEmpty()) Word("more", pixel(12.sp, Ink.mid), Modifier.padding(top = 6.dp), touch = 8.dp) { reading = true }
                // where it's from: not on the page (2026-11-09, Bronson: cut it); the licence's credit closes the whole bio (Reading)
            }
        }
    }
    // how many listen, their records, how many of theirs you have; then play, shuffle, radio, follow
    val counts = @Composable {
        val count = records.size + tubeAlbums.size
        BasicText(
            listOfNotNull(
                i?.audience?.takeIf { it.isNotEmpty() }?.lowercase()?.replace("monthly audience", "a month"),
                count.takeIf { it > 0 }?.let { "$it ${if (it == 1) "record" else "records"}" },
                theirs.size.takeIf { it > 0 }?.let { "$it of yours" },
            ).joinToString("   ").ifEmpty { if (info == null) "reading it from youtube music" else "" },
            style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(end = 56.dp, top = 8.dp, bottom = 8.dp),
        )
    }
    val actions = @Composable {
        if (playable.isNotEmpty()) Row {
            Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Player.play(playable, 0) }
            Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Player.play(playable.shuffled(), 0) }
            // their songs as a DJ's arc, each into the next on the beat (the phone's artist page has it)
            MixWord("artist $shown", playable, Modifier.padding(end = 26.dp))
            // radio: their best-known song, then more like it (the queue's end brings them)
            Word("radio", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Player.radio(true); Player.play(listOf(playable.first())) }
            // follow: lit while you do (on YouTube Music too, signed in); their mix comes first among yours
            val following by Follows.all.collectAsState()
            val on = following.any { it.name.equals(shown, true) }
            Word(if (on) "following" else "follow", pixel(14.sp, if (on) Ink.ink else Ink.mid), touch = 8.dp) { Follows.set(shown, i?.id ?: id, !on) }
        }
        MixProgress("artist $shown")
    }
    val rule = @Composable { Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Ink.line)) }
    // a chart: the place big beside each
    val chart = @Composable { from: Int, songs: List<Song> ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            songs.forEachIndexed { n, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("${from + n + 1}", style = headline(40.sp), modifier = Modifier.width(64.dp))
                    // no times in the chart: YouTube's five come without one, the rest of the six with (2026-11-09: only the sixth said)
                    Box(Modifier.weight(1f)) { SongRow(s.copy(duration = 0), said = s.album.lowercase().ifEmpty { null }, open = open) { Player.play(top, from + n) } }
                }
            }
        }
    }
    // a shelf under its label, gone over a page at a time
    fun androidx.compose.foundation.lazy.LazyListScope.shelf(key: String, label: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) =
        item(key = key) { Column { Label(label); Shelf(content = content); Spacer(Modifier.height(22.dp)) } }

    // A magazine spread, scrolled as one (2026-11-09, Bronson's pick of four drawn: "a"): the photo and what's said of
    // them side by side, their name across under both, then each part its own shape: the chart in two columns, the
    // records and the rest as shelves gone through with < and >
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val inner = maxWidth - SIDE * 2
            val wide = inner >= 700.dp
            LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, bottom = 40.dp)) { cascading {
                // the spread: side by side while there's room, else the photo over what's said
                val side = minOf(inner * .4f, 440.dp)
                item(key = "spread") {
                    // what's said of them from the photo's top, so the space under it is the name's
                    if (wide) Row(verticalAlignment = Alignment.Top) {
                        picture(side)
                        Spacer(Modifier.width(40.dp))
                        Box(Modifier.weight(1f).padding(top = 6.dp)) { if (!waiting) about(if (inner >= 1000.dp) 30.sp else 24.sp, if (inner >= 1000.dp) 4 else 3) }
                    } else Column {
                        picture(minOf(inner, 440.dp))
                        Spacer(Modifier.height(24.dp))
                        if (!waiting) about(22.sp, 4)
                    }
                }
                item(key = "name") {
                    // wide, the name over the photo's foot, 15% of it, and the room it leaves taken up
                    val over = if (wide) side * .15f else 0.dp
                    Column(Modifier.padding(top = if (wide) 0.dp else 10.dp).layout { m, c ->
                        val pull = over.roundToPx()
                        val p = m.measure(c)
                        layout(p.width, (p.height - pull).coerceAtLeast(0)) { p.place(0, -pull) }
                    }) {
                        BigName(shown, inner)
                        Spacer(Modifier.height(18.dp))
                        FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp)) { counts(); actions() }
                    }
                }
                if (waiting) return@cascading
                // the songs of theirs you have first, above all YouTube has: they're why you came
                if (theirs.isNotEmpty()) item(key = "yours") {
                    Column {
                        rule()
                        Label("yours")
                        val half = (theirs.size + 1) / 2
                        if (wide && theirs.size > 3) Row(horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                            listOf(theirs.take(half), theirs.drop(half)).forEachIndexed { c, part ->
                                Column(Modifier.weight(1f)) { part.forEachIndexed { n, s -> SongRow(s, said = s.album.lowercase().ifEmpty { null }, open = open) { Player.play(theirs, c * half + n) } } }
                            }
                        } else Column(Modifier.widthIn(max = SONGS)) { theirs.forEachIndexed { n, s -> SongRow(s, said = s.album.lowercase().ifEmpty { null }, open = open) { Player.play(theirs, n) } } }
                    }
                }
                if (top.isNotEmpty()) item(key = "top") {
                    Column {
                        rule()
                        Label("top songs")
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                            Box(Modifier.weight(1f)) { chart(0, top.take(3)) }
                            Box(Modifier.weight(1f)) { chart(3, top.drop(3)) }
                        } else Box(Modifier.widthIn(max = SONGS)) { chart(0, top) }
                    }
                }
                item(key = "rule") { rule() }
                // their records, yours first: a shelf as the rest are (2026-11-09: not big, gone through)
                if (records.isNotEmpty() || tubeAlbums.isNotEmpty()) shelf("records", "records") {
                    items(records, key = { it.id }) { a -> RecordTile(a, Modifier.width(176.dp)) { open(Page.Record(a.id)) } }
                    items(tubeAlbums, key = { it.url }) { r -> TubeTile(r, Modifier.width(176.dp)) { open(Page.Tube(r)) } }
                }
                i?.singles?.takeIf { it.isNotEmpty() }?.let { singles ->
                    shelf("singles", "singles and eps") { items(singles, key = { it.url }) { r -> TubeTile(r, Modifier.width(150.dp)) { open(Page.Tube(r)) } } }
                }
                // their music videos (MusicVideos), newest first
                if (i != null && i.id.startsWith("UC")) item(key = "videos") { ArtistVideos(name, i.id, open) }
                // the playlists they're on (YouTube Music's "featured on", and their own)
                i?.featured?.takeIf { it.isNotEmpty() }?.let { lists ->
                    shelf("featured", "on playlists") { items(lists, key = { it.url }) { r -> TubeTile(r, Modifier.width(150.dp)) { open(Page.Tube(r)) } } }
                }
                i?.like?.takeIf { it.isNotEmpty() }?.let { like ->
                    shelf("like", "fans also like") {
                        items(like, key = { it.id.ifEmpty { it.name } }) { a ->
                            Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                                onClick = felt { open(Page.Artist(a.name, a.id)) })) {
                                CoverOf(remember(a.face) { a.face?.let { faceSong(a.name, it) } }, a.name, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                                Spacer(Modifier.height(8.dp))
                                UnderCover(a.name, "artist")
                            }
                        }
                    }
                }
            }}
        }
    }
    i?.about?.let { Bio.of(it) }?.takeIf { it.first.isNotEmpty() }?.let { (about, from) ->
        Reading(reading, shown, about, from) { reading = false }
    }
    }
}

/** What closes the bio being read over a page, for the window's escape (it goes back a page otherwise). */
object Reading {
    @Volatile var close: (() -> Unit)? = null
}

/**
 * The whole of what's said of an artist, over their page: the page darkened behind, and on it a column of its own
 * (hairline-edged, no more than 680 wide) to read down: their name, the first sentence big, the rest in its paragraphs,
 * where it's from. A click outside it, close, or escape puts it away.
 */
@Composable
private fun Reading(open: Boolean, name: String, about: String, from: String?, onClose: () -> Unit) {
    androidx.compose.runtime.DisposableEffect(open) {
        Reading.close = if (open) onClose else null
        onDispose { if (Reading.close === onClose) Reading.close = null }
    }
    androidx.compose.animation.AnimatedVisibility(open, enter = androidx.compose.animation.fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
        exit = androidx.compose.animation.fadeOut(tween(Motion.EXIT))) {
        Box(Modifier.fillMaxSize().background(Ink.black.copy(alpha = .86f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClose)),
            contentAlignment = Alignment.Center) {
            val (said, rest) = remember(about) { Artists.pullQuote(about) }
            val paragraphs = remember(rest) { rest.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() } }
            Column(Modifier.padding(vertical = 40.dp, horizontal = SIDE).widthIn(max = 680.dp).fillMaxHeight()
                .background(Ink.black).border(1.dp, Ink.line)
                // a click on the column itself stays: only outside it closes
                .clickable(remember { MutableInteractionSource() }, indication = null) {}) {
                Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 24.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("about", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                    Word("close", pixel(14.sp), touch = 8.dp, onClick = onClose)
                }
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 40.dp)) {
                    BasicText(name.uppercase(), style = headline(56.sp).copy(lineHeight = 52.sp))
                    Spacer(Modifier.height(22.dp))
                    said?.let {
                        BasicText(it.uppercase(), style = headline(26.sp).copy(lineHeight = 30.sp))
                        Spacer(Modifier.height(26.dp))
                    }
                    paragraphs.forEach {
                        BasicText(it, style = pixel(13.sp, Ink.ink.copy(alpha = .8f)).copy(lineHeight = 24.sp))
                        Spacer(Modifier.height(18.dp))
                    }
                    from?.let { BasicText("from $it", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}

/**
 * Their name across the page under the spread: as big as fits the width (230 sp at most), and where even 72 sp won't
 * fit, running across as the player's does.
 */
@Composable
private fun BigName(name: String, room: androidx.compose.ui.unit.Dp) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val size = remember(name, room) {
        val at = measurer.measure(name.uppercase(), headline(100.sp), maxLines = 1, softWrap = false).size.width
        val fits = with(density) { room.toPx() } / at * 100f
        fits.coerceAtMost(230f)
    }
    if (size < 72f) Marquee(name, Marquees.on.collectAsState().value, Modifier.fillMaxWidth(), size = 120.sp)
    // its box trimmed to the capitals (Archivo Black's are .688 of its size): the room above them kept for accents
    // and below for descents, which a name in capitals hasn't, held the photo and the counts away from it
    else BasicText(name.uppercase(), style = headline(size.sp), maxLines = 1, softWrap = false, modifier = Modifier.layout { m, c ->
        val p = m.measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        val base = p[androidx.compose.ui.layout.FirstBaseline]
        val cap = (size.sp.toPx() * .688f).toInt()
        layout(p.width, cap) { p.place(0, cap - base) }
    })
}

/**
 * A picture in the dither (the player's), in its own colour, developing from black (the brightest dots first) once
 * it's read: an artist's photo.
 */
@Composable
fun Dithered(photo: Song, modifier: Modifier, px: Int = 1080) {
    val cover = rememberCover(photo, px)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(Ink.black)) {
        val side = with(density) { maxOf(maxWidth, maxHeight).toPx() }
        // whole pixels to a dot at this zoom, and as many dots as cover the tile (Dither.cells)
        val (cell, dots) = remember(side, density.density) { Dither.cells(side, density.density) }
        val born = remember { System.nanoTime() }
        // a picture's frames kept for the session: a tile seen before (scrolled back to, listen opened again) is there at
        // once, not developed all over (2026-11-09: "the dithered ... loading in slow"; each was 25 ms in coming and 7 ms
        // to make, then 900 ms of developing, every time it came into sight)
        val key = remember(cover, dots) { cover?.let { "${photo.id}|${photo.albumId}|$dots|${it.accent.value}" } }
        val ready = remember(key) { key?.let { Dither.kept(it) } }
        // made anew for each size (the zoom, the window): keyed on the key, not the cover alone (2026-11-11: zoomed, the
        // old size's frames were kept as made, the new ones never were, and the develop stopped at its first frame, one
        // dot in sixteen: "the effect loses its nice look")
        val frames by produceState(ready, key) {
            val c = cover ?: return@produceState
            if (ready != null) { value = ready; return@produceState }
            val t = System.nanoTime()
            value = withContext(Dispatchers.Default) { Dither.develop(c.image, c.accent, dots) }?.also { f -> key?.let { Dither.keep(it, f) } }
            if (Log.on) Log.d("dither: ${photo.album.take(20)} cover ${(t - born) / 1_000_000} ms after shown, frames ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        // developed in the first time it's seen (450 ms); a size changed after, there at once (the picture's, not the size's)
        val shown = remember(photo.id, photo.albumId) { Animatable(if (ready != null) 1f else 0f) }
        LaunchedEffect(frames) { if (frames != null && shown.value < 1f) shown.animateTo(1f, tween(450, easing = Motion.expo)) }
        frames?.let { f ->
            // the dots drawn as dots: unfiltered, as the player draws them
            // each dot exactly `cell` pixels, the grid centered on whole pixels and cut by the tile's edges (never
            // stretched to fit: a stretch of 1.1 made dots of two sizes)
            Dots(f[((shown.value * f.size).toInt()).coerceIn(0, f.size - 1)], cell, Modifier.fillMaxSize())
        }
    }
}

/** A picture as it is, filling its space (an artist's photo with the dither off). */
@Composable
private fun AsItIs(photo: Song, modifier: Modifier, px: Int = 1080) {
    val cover = rememberCover(photo, px)
    Box(modifier.background(Ink.black)) { cover?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

private val SONGS = 720.dp

/**
 * How far to dim an artist's photo shown as it is so their white name reads over its foot (2026-11-09: Yard Act's light
 * photo took the name): the part the name covers (the foot of the square cut from the middle, as it's drawn) judged by
 * its light parts, the brightest tenth (by the average, Yard Act's black shirts hid the cream wall the letters cross),
 * brought down to .4 at most, evenly, never below .4 of itself; a dark photo stays as it is (1). As the canvas behind
 * the player is held down: evenly, no gradient.
 */
fun nameDim(image: androidx.compose.ui.graphics.ImageBitmap): Float {
    val w = image.width; val h = image.height
    val side = minOf(w, h); val left = (w - side) / 2; val top = (h - side) / 2
    val from = top + side * 7 / 10
    val rows = top + side - from
    val px = IntArray(side * rows)
    runCatching { image.readPixels(px, left, from, side, rows) }.onFailure { return 1f }
    val step = maxOf(1, side / 96)
    val lights = ArrayList<Float>()
    for (y in 0 until rows step step) for (x in 0 until side step step) {
        val c = px[y * side + x]
        lights += (.299f * (c shr 16 and 255) + .587f * (c shr 8 and 255) + .114f * (c and 255)) / 255f
    }
    if (lights.isEmpty()) return 1f
    lights.sort()
    val light = lights[lights.size * 9 / 10]
    return if (light <= .4f) 1f else (.4f / light).coerceAtLeast(.4f)
}
