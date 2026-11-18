package app.ink.music

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** DIALED IN's pins and the ones said "not here" ([DialedIn]), kept on the computer (dialed-in.json), as its menu sets them. */
object DialedPins {
    private val file = java.io.File(Home.dir, "dialed-in.json")
    private val kept = DialedIn.read(runCatching { file.readText() }.getOrNull())
    val pins = MutableStateFlow(kept.first)
    val hidden = MutableStateFlow(kept.second)
    private fun keep() = runCatching { Home.write(file, DialedIn.json(pins.value, hidden.value)) }

    /** This one kept at its number, whatever the hour (one already there moves out). */
    fun pin(s: DialedIn.Slot) {
        pins.value = pins.value.filter { it.n != s.n && it.key != s.key } + DialedIn.Pin(s.n, s.key, s.kind, s.song, s.artist, s.record)
        keep()
    }
    fun unpin(n: Int) { pins.value = pins.value.filter { it.n != n }; keep() }
    /** Never here again (pinned, it's let go too). */
    fun hide(s: DialedIn.Slot) { hidden.value = hidden.value + s.key; pins.value = pins.value.filter { it.key != s.key }; keep() }

    /** The keys 1 to 9 on listen, set while DIALED IN is shown: plays that one; false if there's none. */
    @Volatile var play: ((Int) -> Boolean)? = null
}

/**
 * DIALED IN on listen (2026-11-17; the phone's, with room): the nine as a contact sheet, touching but for a hairline of
 * black, a row of nine where the window is wide and three by three where it isn't; each picture in black and white with
 * grain ([Mono]), its number big at its top left and its name along its foot, the one playing in its colour, and the
 * one under the pointer (Bronson, 2026-11-17: "on hover can the dials light up with their color?"). A click plays it, as
 * do the keys 1 to 9; its menu (a right click) pins it, opens it, or says not here. Shown once
 * there are six.
 */
@Composable
fun DialedInSheet(open: (Page) -> Unit) {
    val pins by DialedPins.pins.collectAsState()
    val hidden by DialedPins.hidden.collectAsState()
    val heard by Listened.now.collectAsState()
    val liked by TubeAccount.liked.collectAsState()
    val scope = rememberCoroutineScope()
    val known = remember(liked) { liked.associateBy { it.id } }
    fun songOf(id: String) = Library.song(id) ?: known[id] ?: YouTube.known(id)
    val now = remember(heard) { System.currentTimeMillis() }
    val minute = remember(now) { Listening.minuteOfWeek(now) }
    // what's been heard, read off the window's thread each time it grows
    val all by androidx.compose.runtime.produceState(emptyList<History.Heard>(), heard) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { History.read() }
    }
    // only what can be played again here (the library's, or a YouTube song this run knows)
    val slots = remember(all, pins, hidden, known) {
        val listens = all.filter { songOf(it.id) != null }.map { DialedIn.Listen(it.at, it.id, it.artist, it.record, Listening.minuteOfWeek(it.at)) }
        DialedIn.pick(listens, minute, now, pins.filter { songOf(it.song) != null }, hidden)
    }
    // the keys only while it's shown: hidden (too few listens), 1 to 9 played what wasn't there
    if (slots.size < 6) return
    DisposableEffect(slots) {
        DialedPins.play = { n -> slots.firstOrNull { it.n == n }?.let { s -> songOf(s.song)?.let { play(s, it, scope); true } } ?: false }
        onDispose { DialedPins.play = null }
    }
    val playing by Player.now.collectAsState()
    Column {
        Section("DIALED IN", DialedIn.said(minute) + ", 1 to 9 play them")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // a row of nine where each can be 130 dp and more; else three by three, at most 600 wide
            val nine = maxWidth >= 130.dp * 9
            val cols = if (nine) 9 else 3
            val side = if (nine) (maxWidth - 8.dp) / 9 else min((maxWidth - 2.dp) / 3, 200.dp)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                slots.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        row.forEach { s -> androidx.compose.runtime.key(s.key) {
                            val song = songOf(s.song)
                            val lit = song != null && playing?.let { playingIs(s, it) } == true
                            Box(Modifier.width(side)) { Slot(s, song, lit, side.value >= 170, scope, open) }
                        }}
                    }
                }
            }
        }
    }
}

/** Whether what's playing is this slot's: the song, a song off the record, a song of theirs. */
private fun playingIs(s: DialedIn.Slot, n: Player.Now): Boolean = when (s.kind) {
    DialedIn.Kind.SONG -> n.song.id == s.song
    DialedIn.Kind.RECORD -> n.song.albumId == s.record
    DialedIn.Kind.ARTIST -> n.song.artist.lowercase().startsWith(s.artist.lowercase())
}

private fun nameOf(s: DialedIn.Slot, song: Song?): String = when (s.kind) {
    DialedIn.Kind.SONG -> song?.title ?: s.song
    DialedIn.Kind.RECORD -> song?.album?.ifEmpty { null } ?: song?.title.orEmpty()
    DialedIn.Kind.ARTIST -> s.artist
}

/** A YouTube record by its id ("ya" and its browse id, or its own playlist's). */
fun tubeRecord(record: String, song: Song?): TubeAlbum {
    val id = record.removePrefix("ya")
    val url = if (id.startsWith("OLAK5uy_")) "${InnerTube.BASE}/playlist?list=$id" else "${InnerTube.BASE}/browse/$id"
    return TubeAlbum(url, song?.album.orEmpty(), song?.albumArtist?.ifEmpty { null } ?: song?.artist.orEmpty(), song?.art)
}

/**
 * A slot played: the song (radio after it), the record from its start; an artist, your own songs by them shuffled (this
 * computer's files, your server's), else YouTube Music's mix from their most heard: someone with only their own files
 * gets them (2026-11-17, Bronson asked; the mix alone played one song and stopped, YouTube having none for a file).
 */
private fun play(s: DialedIn.Slot, song: Song, scope: kotlinx.coroutines.CoroutineScope) {
    when (s.kind) {
        DialedIn.Kind.SONG -> Player.play(listOf(song))
        DialedIn.Kind.RECORD -> {
            val album = Library.albums.value.firstOrNull { it.id == s.record }
            if (album != null) Player.play(album.songs)
            else if (s.record.startsWith("ya")) playTube(tubeRecord(s.record, song))
            else Player.play(listOf(song))
        }
        DialedIn.Kind.ARTIST -> {
            val theirs = byArtist(s.artist)
            if (theirs.size >= 3 || !song.tube) Player.play(theirs.ifEmpty { listOf(song) }.shuffled())
            else scope.launch { Mixes.play(Mixes.Mix(s.artist, song, song)) }
        }
    }
}

/** Your songs by them (as the album artist, or the first named): the library's, files and server alike. */
private fun byArtist(name: String): List<Song> = Library.songs.value.filter { s ->
    s.albumArtist.equals(name, true) || s.artist.substringBefore(", ").equals(name, true)
}

private fun openOf(s: DialedIn.Slot, song: Song?, open: (Page) -> Unit) = when (s.kind) {
    DialedIn.Kind.ARTIST -> open(Page.Artist(s.artist))
    DialedIn.Kind.RECORD -> if (Library.albums.value.any { it.id == s.record }) open(Page.Record(s.record)) else open(Page.Tube(tubeRecord(s.record, song)))
    DialedIn.Kind.SONG -> Unit
}

/** One of the nine: its picture (an artist's photo, else the cover), its number, pinned or not, its name along its foot. */
@Composable
private fun Slot(s: DialedIn.Slot, song: Song?, lit: Boolean, big: Boolean, scope: kotlinx.coroutines.CoroutineScope, open: (Page) -> Unit) {
    // an artist's photo from their page (kept), else the cover of their most heard song; a song or record whose cover
    // doesn't come (a server's song with none, missed by the catalogs too), their artist's photo in its place, not a
    // black square (2026-11-17, Bronson: three of the nine without a picture, on the phone)
    val cover = if (s.kind != DialedIn.Kind.ARTIST && song != null) rememberCover(song, 400) else null
    val gaveUp by androidx.compose.runtime.produceState(false, song?.albumId) { kotlinx.coroutines.delay(3_000); value = true }
    // or one so dark it would show as black
    val dark by androidx.compose.runtime.produceState(false, cover) { value = cover?.let { c -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { lightness(c.image) < .22f } } ?: false }
    val wantFace = s.kind == DialedIn.Kind.ARTIST || (cover == null && gaveUp) || dark
    // the flow made once per artist: made in the drawing, each redraw had begun a new read of their page (one artist
    // read every second or so while listen was open, 2026-11-18)
    val info by remember(wantFace, s.artist) { if (wantFace) Artists.of(s.artist) else kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val face = (if (wantFace) info?.photo?.let { faceSong(s.artist, it, photo = true) } else null) ?: song
    WithMenu({
        listOf(listOfNotNull(
            "play" to { song?.let { play(s, it, scope) }; Unit },
            (if (s.kind != DialedIn.Kind.SONG) "open" to { openOf(s, song, open); Unit } else null),
        ), listOf(
            (if (s.pinned) "unpin" else "pin to ${s.n}") to { if (s.pinned) DialedPins.unpin(s.n) else DialedPins.pin(s); Unit },
            "not here" to { DialedPins.hide(s); Unit },
        ))
    }) {
        val src = remember { MutableInteractionSource() }
        val over by src.collectIsHoveredAsState()
        // its colour comes up over the black and white while it plays or the pointer's on it (ink's curve, in and out)
        val colour by animateFloatAsState(if (lit || over) 1f else 0f,
            tween(if (lit || over) Motion.ENTER else Motion.EXIT, easing = Motion.expo), label = "dial colour")
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(Ink.black).hoverable(src)
            .clickable(src, indication = null, onClick = felt { song?.let { play(s, it, scope) } })) {
            if (face != null) {
                Mono(face, Modifier.fillMaxSize())
                // in its colour, dimmed evenly for the words (as a name over a cover is)
                if (colour > 0f) rememberCover(face, 400)?.let { c ->
                    val k = remember(c) { nameDim(c.image) }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = colour }) {
                        Image(c.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Ink.black.copy(alpha = 1f - k)))
                    }
                }
            }
            BasicText("${s.n}", style = headline(if (big) 52.sp else 40.sp), modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            if (s.pinned) BasicText("pinned", style = pixel(12.sp), modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp))
            BasicText(nameOf(s, song).uppercase(), style = headline(if (big) 15.sp else 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
        }
    }
}

/**
 * How light a picture is, 0 to 1, from a sample of its pixels: one nearly black (a dark video's frame, 2026-11-17: No One
 * Noticed's slot on the S26 stayed black with its cover read) says nothing once it's in black and white.
 */
private fun lightness(i: androidx.compose.ui.graphics.ImageBitmap): Float = runCatching {
    val m = i.toPixelMap()
    var sum = 0f; var n = 0
    for (y in 0 until m.height step 8) for (x in 0 until m.width step 8) { val c = m[x, y]; sum += .3f * c.red + .59f * c.green + .11f * c.blue; n++ }
    if (n == 0) 1f else sum / n
}.getOrDefault(1f)
