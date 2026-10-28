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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One page for every artist, as the phone's: their photo across the top in the dither, their name on it; how many
 * listen, their records, how many of theirs you have; play, shuffle, radio; then the songs of theirs you have (they're
 * why you came), their best-known songs, their records (yours first), singles and EPs, what's said of them, and the
 * artists fans also like. YouTube Music's part kept from last time at once, then read again.
 */
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
    val top = remember(i, theirs) { i?.top.orEmpty().take(5).map { t -> theirs.firstOrNull { it.title.equals(t.title, ignoreCase = true) } ?: t } }
    val playable = remember(top, theirs) { (top + theirs).distinctBy { it.id } }
    // their records: yours first, then YouTube's that you don't have
    val tubeAlbums = remember(i, records) { i?.albums.orEmpty().filter { t -> records.none { it.title.equals(t.title, ignoreCase = true) } } }
    var aboutOpen by remember(name) { mutableStateOf(false) }
    // opened the first time (nothing kept): the photo's room held and the rest kept back till YouTube Music answers,
    // a second and a half at most, so it comes in once
    var patient by remember(name, id) { mutableStateOf(true) }
    LaunchedEffect(name, id) { delay(1_500); patient = false }
    val waiting = info == null && patient
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, bottom = 32.dp)) {
            item {
                Column {
                    // their photo in the dither (the player's), developing as it opens, their name on it
                    val photo = i?.photo
                    if (photo != null || waiting) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                        // square, as the phone's (a cover's cut square), the name across the page over its foot
                        val side = minOf(maxWidth, 440.dp)
                        if (photo != null) Dithered(remember(photo) { faceSong(name, photo, photo = true) }, Modifier.size(side))
                        else Box(Modifier.size(side))
                        NameOn(i?.name ?: name, Modifier.fillMaxWidth().padding(bottom = 6.dp))
                    } else BasicText((i?.name ?: name).uppercase(), style = headline(64.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp))
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
                        Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Player.play(playable, 0) }
                        Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Player.play(playable.shuffled(), 0) }
                        // radio: their best-known song, then more like it (the queue's end brings them)
                        Word("radio", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Player.radio(true); Player.play(listOf(playable.first())) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (waiting) return@LazyColumn
            // the songs of theirs you have first, above all YouTube has: they're why you came
            if (theirs.isNotEmpty()) {
                item { Label("yours") }
                itemsIndexed(theirs, key = { _, s -> s.id }) { n, s -> SongRow(s, said = s.album.lowercase().ifEmpty { null }, open = open) { Player.play(theirs, n) } }
                item { Spacer(Modifier.height(18.dp)) }
            }
            if (top.isNotEmpty()) {
                item { Label("top songs") }
                // a chart: the place big beside each
                itemsIndexed(top, key = { _, s -> "t" + s.id }) { n, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("${n + 1}", style = headline(34.sp), modifier = Modifier.width(56.dp))
                        Box(Modifier.weight(1f)) { SongRow(s, said = s.album.lowercase().ifEmpty { null }, open = open) { Player.play(top, n) } }
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
            if (records.isNotEmpty() || tubeAlbums.isNotEmpty()) item {
                Label("records")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(records, key = { it.id }) { a -> RecordTile(a, Modifier.width(176.dp)) { open(Page.Record(a.id)) } }
                    items(tubeAlbums, key = { it.url }) { r -> TubeTile(r, Modifier.width(176.dp)) { open(Page.Tube(r)) } }
                }
                Spacer(Modifier.height(18.dp))
            }
            i?.singles?.takeIf { it.isNotEmpty() }?.let { singles ->
                item {
                    Label("singles and eps")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(singles, key = { it.url }) { r -> TubeTile(r, Modifier.width(150.dp)) { open(Page.Tube(r)) } }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
            // what's said of them: its first sentence pulled out big, as a lyric is; the rest small under it, four lines, a click for the rest
            i?.about?.takeIf { it.isNotEmpty() }?.let { about ->
                item {
                    val (said, rest) = remember(about) { Artists.pullQuote(about) }
                    Column(Modifier.width(720.dp)) {
                        Label("about")
                        said?.let {
                            BasicText(it.uppercase(), style = headline(26.sp).copy(lineHeight = 30.sp))
                            Spacer(Modifier.height(14.dp))
                        }
                        if (rest.isNotEmpty()) BasicText(
                            rest, style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 22.sp),
                            maxLines = if (aboutOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { aboutOpen = !aboutOpen }),
                        )
                        if (!aboutOpen && rest.isNotEmpty()) Word("more", pixel(12.sp, Ink.mid), Modifier.padding(top = 6.dp), touch = 8.dp) { aboutOpen = true }
                        Spacer(Modifier.height(22.dp))
                    }
                }
            }
            i?.like?.takeIf { it.isNotEmpty() }?.let { like ->
                item {
                    Label("fans also like")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
            }
        }
    }
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
        val side = with(density) { maxWidth.toPx() }
        val frames by produceState<List<ImageBitmap>?>(null, cover) {
            val c = cover ?: return@produceState
            value = withContext(Dispatchers.Default) { Dither.develop(c.image, c.accent, side, density.density) }
        }
        val shown = remember(frames) { Animatable(0f) }
        LaunchedEffect(frames) { if (frames != null) shown.animateTo(1f, tween(900, easing = Motion.expo)) }
        frames?.let { f ->
            // the dots drawn as dots: unfiltered, as the player draws them
            Image(f[((shown.value * f.size).toInt()).coerceIn(0, f.size - 1)], null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
        }
    }
}

/** An artist's name on their photo: big and still when it fits the width, else running across it as the player's does. */
@Composable
private fun NameOn(name: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = headline(72.sp)
    BoxWithConstraints(modifier) {
        val room = with(LocalDensity.current) { (maxWidth - 40.dp).toPx() }
        val wide = remember(name, room) { measurer.measure(name.uppercase(), style, maxLines = 1, softWrap = false).size.width > room }
        if (wide) Marquee(name, true, Modifier.fillMaxWidth(), size = 96.sp)
        else BasicText(name.uppercase(), style = style, maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp))
    }
}
