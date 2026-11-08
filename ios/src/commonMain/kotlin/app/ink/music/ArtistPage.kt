package app.ink.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * YouTube Music's page for an artist (photo, about, audience, top songs, records, singles, fans also like), read once
 * a run: found by name when there's no id ([InnerTube.searchItems], the one whose name folds the same, else the first).
 */
object Artists {
    private val kept = HashMap<String, InnerTube.Artist>()
    private val ids = HashMap<String, String>()

    fun kept(name: String, id: String): InnerTube.Artist? = kept[id.ifEmpty { ids[Search.fold(name)] ?: "" }]

    /** Blocking: off the main thread. Null if YouTube Music can't say. */
    fun of(name: String, id: String): InnerTube.Artist? = runCatching {
        val folded = Search.fold(name)
        val browse = id.ifEmpty {
            ids[folded] ?: Tube.api.searchItems(name, InnerTube.Kind.ARTIST, 5).let { found ->
                (found.firstOrNull { Search.fold(it.title) == folded } ?: found.firstOrNull())?.id
            } ?: return null
        }
        ids[folded] = browse
        kept[browse] ?: Tube.api.artist(browse).takeIf { it.name.isNotEmpty() }?.also { kept[browse] = it }
    }.getOrNull()
}

/** What's said of an artist, its first sentence pulled out (as the phone's): (the sentence, the rest). */
fun pullQuote(about: String): Pair<String?, String> {
    val end = Regex("""[.!?](?=\s+[A-Z“"(])""").find(about)?.range?.last ?: return null to about
    val first = about.substring(0, end + 1).trim()
    return if (first.length in 20..180) first to about.substring(end + 1).trim() else null to about
}

/**
 * An artist's page, as the phone's: their photo across in the dither, their name on it; how many a month, records,
 * songs of yours; play, shuffle, radio; yours (your server's), their top songs as a chart, records (yours first),
 * singles and eps, what's said of them (the first sentence big), fans also like.
 */
@Composable
fun ArtistPage(name: String, tubeId: String, onClose: () -> Unit) {
    val opened = remember(name, tubeId) { nowMs() }
    val info by produceState(Artists.kept(name, tubeId), name, tubeId) { if (value == null) value = Here.off { Artists.of(name, tubeId) } }
    val i = info
    // your server's songs and records by them
    val server by produceState<Pair<List<Album>, List<Song>>>(emptyList<Album>() to emptyList(), name) {
        val (albums, songs) = runCatching { Server.search(name, 40, 100) }.getOrDefault(emptyList<Album>() to emptyList())
        val f = Search.fold(name)
        value = albums.filter { Search.fold(it.artist) == f } to songs.filter { s -> s.artist.split(", ").any { Search.fold(it) == f } }
    }
    val (records, theirs) = server
    val top = remember(i, theirs) {
        i?.top.orEmpty().take(5).map { t -> theirs.firstOrNull { it.title.equals(t.title, ignoreCase = true) } ?: Tube.song(t) }
    }
    val playable = remember(top, theirs) { (top + theirs).distinctBy { it.id } }
    val tubeAlbums = remember(i, records) { i?.albums.orEmpty().filter { t -> records.none { it.title.equals(t.title, ignoreCase = true) } } }
    var aboutOpen by remember(name) { mutableStateOf(false) }
    // opened the first time: the photo's room held and the rest kept back till YouTube Music answers, a second and a half
    // at most, so it comes in once
    var patient by remember(name, tubeId) { mutableStateOf(true) }
    LaunchedEffect(name, tubeId) { delay(1_500); patient = false }
    val waiting = info == null && patient
    val now by Sound.now.collectAsState()
    PageOver(onClose) { top0 ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top0, bottom = (if (now != null) 120.dp else 30.dp) + 24.dp)) {
            item {
                Column(Modifier.cascade(0, opened)) {
                    val photo = i?.photo
                    if (photo != null || waiting) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                        val side = minOf(maxWidth, 560.dp)
                        if (photo != null) Dithered(photo, Modifier.size(side).align(Alignment.Center))
                        else Box(Modifier.size(side).align(Alignment.Center))
                        NameOn(i?.name ?: name, Modifier.fillMaxWidth().padding(bottom = 6.dp))
                    } else Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        BasicText((i?.name ?: name).uppercase(), style = headline(52.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(14.dp))
                        val count = records.size + tubeAlbums.size
                        BasicText(
                            listOfNotNull(
                                i?.audience?.takeIf { it.isNotEmpty() }?.lowercase()?.replace("monthly audience", "a month"),
                                count.takeIf { it > 0 }?.let { "$it ${if (it == 1) "record" else "records"}" },
                                theirs.size.takeIf { it > 0 }?.let { "$it of yours" },
                            ).joinToString("   ").ifEmpty { if (info == null) "reading it from youtube music" else "" },
                            style = pixel(12.sp, Ink.mid),
                        )
                        Spacer(Modifier.height(16.dp))
                        if (playable.isNotEmpty()) Row {
                            Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Sound.play(playable, 0) }
                            Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Sound.play(playable.shuffled(), 0) }
                            // radio: the first, then more like it as it plays
                            Word("radio", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) {
                                Sound.radio(true); Sound.play(listOf(playable.first()), 0)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            if (waiting) return@LazyColumn
            // the songs of theirs you have first, above all YouTube has: they're why you came
            if (theirs.isNotEmpty()) {
                item { Label("yours", Modifier.padding(horizontal = 20.dp)) }
                itemsIndexed(theirs, key = { _, s -> "y" + s.id }) { n, s ->
                    Box(Modifier.cascade(n + 1, opened).padding(horizontal = 20.dp)) { SongRow(s) { Sound.play(theirs, n) } }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
            if (top.isNotEmpty()) {
                item { Label("top songs", Modifier.padding(horizontal = 20.dp)) }
                // a chart: the place big beside each
                itemsIndexed(top, key = { _, s -> "t" + s.id }) { n, s ->
                    Row(Modifier.cascade(n + 1, opened).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("${n + 1}", style = headline(34.sp), modifier = Modifier.width(48.dp))
                        Box(Modifier.weight(1f)) { SongRow(s, yours = s.kind != "youtube" || theirs.isEmpty()) { Sound.play(top, n) } }
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
            if (records.isNotEmpty() || tubeAlbums.isNotEmpty()) item {
                Label("records", Modifier.padding(horizontal = 20.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(records, key = { "r" + it.id }) { a -> RecordTile(a, Modifier.width(150.dp)) }
                    items(tubeAlbums, key = { "t" + it.id }) { r -> RecordTile(Tube.record(r), Modifier.width(150.dp)) }
                }
                Spacer(Modifier.height(18.dp))
            }
            i?.singles?.takeIf { it.isNotEmpty() }?.let { singles ->
                item {
                    Label("singles and eps", Modifier.padding(horizontal = 20.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(singles, key = { "s" + it.id }) { r -> RecordTile(Tube.record(r), Modifier.width(130.dp)) }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
            // what's said of them: its first sentence pulled out big, as a lyric is; the rest small, four lines, a tap for more
            i?.about?.takeIf { it.isNotEmpty() }?.let { about ->
                item {
                    val (said, rest) = remember(about) { pullQuote(about) }
                    Label("about", Modifier.padding(horizontal = 20.dp))
                    said?.let {
                        BasicText(it.uppercase(), style = headline(24.sp).copy(lineHeight = 28.sp), modifier = Modifier.padding(horizontal = 20.dp))
                        Spacer(Modifier.height(14.dp))
                    }
                    if (rest.isNotEmpty()) BasicText(
                        rest, style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 22.sp),
                        maxLines = if (aboutOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { aboutOpen = !aboutOpen }),
                    )
                    if (!aboutOpen && rest.isNotEmpty()) Word("more", pixel(12.sp, Ink.mid), Modifier.padding(start = 20.dp, top = 6.dp), touch = 8.dp) { aboutOpen = true }
                    Spacer(Modifier.height(22.dp))
                }
            }
            i?.like?.takeIf { it.isNotEmpty() }?.let { like ->
                item {
                    Label("fans also like", Modifier.padding(horizontal = 20.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(like, key = { "a" + it.id.ifEmpty { it.title } }) { a ->
                            Column(Modifier.width(130.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                                onClick = felt { Pages.open(Page.Artist(a.title, a.id)) })) {
                                Cover(a.art, 400, a.title, Modifier.fillMaxWidth().aspectRatio(1f))
                                Spacer(Modifier.height(8.dp))
                                BasicText(a.title.uppercase(), style = headline(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                BasicText("artist", style = pixel(12.sp, Ink.mid), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A picture in the dither (the player's), in its own colour, developing from black once it's read: an artist's photo.
 * The dots drawn as dots: unfiltered.
 */
@Composable
fun Dithered(url: String, modifier: Modifier, px: Int = 1080) {
    val cover = rememberCover(url, px)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(Ink.black)) {
        val side = with(density) { maxWidth.toPx() }
        val img by produceState<ImageBitmap?>(null, cover, side) {
            val c = cover ?: return@produceState
            value = Here.off { Looks.draw(square(c.image), Look.DITHER, c.accent, side, density.density) }
        }
        val shown = remember(img) { Animatable(0f) }
        LaunchedEffect(img) { if (img != null) shown.animateTo(1f, tween(900, easing = Motion.expo)) }
        img?.let {
            Image(it, null, Modifier.fillMaxSize().alpha(shown.value), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
        }
    }
}

/** A wide photo cut to its middle square (the dither reads a square). */
private fun square(img: ImageBitmap): ImageBitmap {
    if (img.width == img.height) return img
    val n = minOf(img.width, img.height)
    val px = IntArray(img.width * img.height)
    img.readPixels(px)
    val x0 = (img.width - n) / 2; val y0 = (img.height - n) / 2
    val out = IntArray(n * n) { k -> px[(y0 + k / n) * img.width + x0 + k % n] }
    return imageOf(out, n)
}

/** An artist's name on their photo: big and still when it fits the width, else running across it as the player's does. */
@Composable
private fun NameOn(name: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = headline(64.sp)
    BoxWithConstraints(modifier) {
        val room = with(LocalDensity.current) { (maxWidth - 40.dp).toPx() }
        val wide = remember(name, room) { measurer.measure(name.uppercase(), style, maxLines = 1, softWrap = false).size.width > room }
        if (wide) Marquee(name, true, Modifier.fillMaxWidth(), size = 96.sp)
        else BasicText(name.uppercase(), style = style, maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp))
    }
}
