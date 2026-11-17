package app.ink.music

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * DIALED IN's pins and the ones said "not here" ([DialedIn]), kept on the phone (files/dialed-in.json), as a hold sets them.
 */
object DialedPins {
    val pins = MutableStateFlow<List<DialedIn.Pin>>(emptyList())
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    private fun file(c: Context) = java.io.File(c.filesDir, "dialed-in.json")

    fun start(c: Context) {
        val (p, h) = DialedIn.read(runCatching { file(c).readText() }.getOrNull())
        pins.value = p; hidden.value = h
    }
    private fun keep(c: Context) = runCatching { file(c).writeText(DialedIn.json(pins.value, hidden.value)) }

    /** This one kept at its number, whatever the hour (one already there moves out). */
    fun pin(c: Context, s: DialedIn.Slot) {
        pins.value = pins.value.filter { it.n != s.n && it.key != s.key } + DialedIn.Pin(s.n, s.key, s.kind, s.song, s.artist, s.record)
        keep(c)
    }
    fun unpin(c: Context, n: Int) { pins.value = pins.value.filter { it.n != n }; keep(c) }
    /** Never here again (pinned, it's let go too). */
    fun hide(c: Context, s: DialedIn.Slot) { hidden.value = hidden.value + s.key; pins.value = pins.value.filter { it.key != s.key }; keep(c) }
}

/**
 * DIALED IN on listen (2026-11-17): the nine as a contact sheet, three by three, touching but for a hairline of black;
 * each picture in black and white with grain ([Mono]), its number big at its top left and its name along its foot, the
 * one playing the only one in its colour. A tap plays it; held, the others dim and its words come under the sheet:
 * PIN TO (or UNPIN), open, not here. Shown once there are six to show.
 */
@Composable
fun DialedInSheet(heard: List<History.Heard>, songOf: (String) -> Song?, pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DialedPins.start(ctx) }
    val pins by DialedPins.pins.collectAsState()
    val hidden by DialedPins.hidden.collectAsState()
    val now = remember(heard) { System.currentTimeMillis() }
    val minute = remember(now) { Listening.minuteOfWeek(now) }
    // only what can be played again here (the library's, or a YouTube song this run knows)
    val slots = remember(heard, pins, hidden) {
        val listens = heard.filter { songOf(it.id) != null }.map { DialedIn.Listen(it.at, it.id, it.artist, it.record, Listening.minuteOfWeek(it.at)) }
        DialedIn.pick(listens, minute, now, pins.filter { songOf(it.song) != null }, hidden)
    }
    if (slots.size < 6) return
    var held by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(held) { if (held != null) { kotlinx.coroutines.delay(6_000); held = null } }
    val playing by Playback.now.collectAsState()
    Column {
        Heading("dialed in", DialedIn.said(minute))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            slots.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.forEach { s ->
                        val song = songOf(s.song)
                        val lit = song != null && playing?.let { n -> playingIs(s, song, n) } == true
                        Box(Modifier.weight(1f).alpha(if (held == null || held == s.n) 1f else .35f)) {
                            Slot(s, song, lit, onClick = { held = null; play(s, song, scope) }, onHold = { held = s.n })
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        held?.let { n -> slots.firstOrNull { it.n == n } }?.let { s ->
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                BasicText("${nameOf(s, songOf(s.song)).lowercase()}, ${s.kind.word}" + if (s.pinned) ", pinned" else ", here at this hour",
                    style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Word(if (s.pinned) "UNPIN" else "PIN TO ${s.n}", headline(24.sp), touch = 8.dp) {
                        if (s.pinned) DialedPins.unpin(ctx, s.n) else DialedPins.pin(ctx, s); held = null
                    }
                    if (s.kind != DialedIn.Kind.SONG) Word("open", pixel(14.sp), touch = 8.dp) { held = null; open(s, songOf(s.song), pages) }
                    Word("not here", pixel(14.sp, Ink.mid), touch = 8.dp) { DialedPins.hide(ctx, s); held = null }
                }
            }
        }
    }
}

/**
 * Whether what's playing is this slot's: the song, a song off the record, a song of theirs (by id where the player knows
 * the song, else by its words: a YouTube song's Now carries none).
 */
private fun playingIs(s: DialedIn.Slot, song: Song, n: Now): Boolean = when (s.kind) {
    DialedIn.Kind.SONG -> n.song?.id?.let { it == s.song } ?: (n.title.equals(song.title, true) && n.artist.equals(song.artist, true))
    DialedIn.Kind.RECORD -> n.song?.albumId?.let { it == s.record } ?: (song.album.isNotEmpty() && n.album.equals(song.album, true))
    DialedIn.Kind.ARTIST -> n.artist.lowercase().startsWith(s.artist.lowercase())
}

private fun nameOf(s: DialedIn.Slot, song: Song?): String = when (s.kind) {
    DialedIn.Kind.SONG -> song?.title ?: s.song
    DialedIn.Kind.RECORD -> song?.album?.ifEmpty { null } ?: song?.title.orEmpty()
    DialedIn.Kind.ARTIST -> s.artist
}

/** A YouTube record by its id ("ya" and its browse id, or its own playlist's). */
private fun tubeRecord(record: String, song: Song?): TubeAlbum {
    val id = record.removePrefix("ya")
    val url = if (id.startsWith("OLAK5uy_")) "https://music.youtube.com/playlist?list=$id" else "https://music.youtube.com/browse/$id"
    return TubeAlbum(url, song?.album.orEmpty(), song?.albumArtist?.ifEmpty { null } ?: song?.artist.orEmpty(), song?.art)
}

/**
 * A slot played: the song (radio after it), the record from its start; an artist, your own songs by them shuffled (the
 * phone's files, your server's), else YouTube Music's mix from their most heard: someone with only their own files gets
 * them (2026-11-17, Bronson asked; the mix alone played one song and stopped, YouTube having none for a file).
 */
private fun play(s: DialedIn.Slot, song: Song?, scope: kotlinx.coroutines.CoroutineScope) {
    song ?: return
    when (s.kind) {
        DialedIn.Kind.SONG -> Playback.play(listOf(song), 0)
        DialedIn.Kind.RECORD -> {
            val album = Library.albums.value.firstOrNull { it.id == s.record }
            if (album != null) Playback.play(album.songs, 0)
            else if (s.record.startsWith("ya")) scope.launch {
                val all = YouTubeMusic.album(tubeRecord(s.record, song))
                if (all.isNotEmpty()) Playback.play(all, 0) else Playback.play(listOf(song), 0)
            } else Playback.play(listOf(song), 0)
        }
        DialedIn.Kind.ARTIST -> {
            val theirs = byArtist(s.artist)
            if (theirs.size >= 3 || !song.tube) Playback.play(theirs.ifEmpty { listOf(song) }.shuffled(), 0)
            else scope.launch { Listen.mix(song) }
        }
    }
}

/** Your songs by them (as the album artist, or the first named): the library's, files and server alike. */
private fun byArtist(name: String): List<Song> = Library.songs.value.filter { s ->
    s.albumArtist.equals(name, true) || s.artist.substringBefore(", ").equals(name, true)
}

private fun open(s: DialedIn.Slot, song: Song?, pages: Pages) = when (s.kind) {
    DialedIn.Kind.ARTIST -> pages.open(Page.Artist(s.artist))
    DialedIn.Kind.RECORD -> if (Library.albums.value.any { it.id == s.record }) pages.open(Page.Record(s.record))
        else pages.open(Page.Tube(tubeRecord(s.record, song)))
    DialedIn.Kind.SONG -> Unit
}

/** One of the nine: its picture (an artist's photo, else the cover), its number, pinned or not, its name along its foot. */
@Composable
private fun Slot(s: DialedIn.Slot, song: Song?, lit: Boolean, onClick: () -> Unit, onHold: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    // an artist's photo from their page (kept), else the cover of their most heard song
    val info by (if (s.kind == DialedIn.Kind.ARTIST) YouTubeAccount.artist(s.artist) else kotlinx.coroutines.flow.flowOf(null)).collectAsState(null)
    val face = info?.photo?.let { photoSong(s.artist, it) } ?: song
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(Color.Black)
        .combinedClickable(remember { MutableInteractionSource() }, indication = null,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onHold() }, onClick = felt(then = onClick))) {
        if (face != null) {
            // the one playing in its colour, dimmed evenly for the words (as a name over a cover is)
            if (lit) {
                val cover = rememberCover(face, 400)
                cover?.let { c ->
                    val k = remember(c) { nameDim(c.image) }
                    Image(c.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - k)))
                }
            } else Mono(face, Modifier.fillMaxSize())
        }
        BasicText("${s.n}", style = headline(38.sp), modifier = Modifier.padding(start = 7.dp, top = 5.dp))
        if (s.pinned) BasicText("pinned", style = pixel(12.sp), modifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp))
        BasicText(nameOf(s, song).uppercase(), style = headline(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 7.dp, end = 7.dp, bottom = 7.dp))
    }
}
