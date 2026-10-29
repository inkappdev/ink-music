package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The places, as the rail says them (the phone's tabs, with explore). */
val PLACES = listOf("listen", "ink", "albums", "artists", "songs", "radio", "lists")

/** How the window's laid out by its width: a rail, the page and the now column; a rail and the page; the phone's. */
enum class Frame { WIDE, MIDDLE, NARROW }

fun frameOf(width: Dp) = when {
    width >= 1300.dp -> Frame.WIDE
    width >= 960.dp -> Frame.MIDDLE
    else -> Frame.NARROW
}

/** The rail: the places as words down the window's left (lit is where you are), find and settings under them. */
@Composable
fun Rail(place: String, onPlace: (String) -> Unit, onFind: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.width(RAIL).fillMaxHeight().padding(start = SIDE, top = TOP + 40.dp, bottom = 20.dp)) {
        PLACES.forEach { p ->
            Word(p.uppercase(), headline(22.sp, if (p == place) Ink.ink else Ink.dim), Modifier.padding(vertical = 2.dp), touch = 6.dp) { onPlace(p) }
        }
        Spacer(Modifier.height(28.dp))
        // find, the lens (⌘F)
        Pixel(SEARCH, "find", Ink.mid, Modifier.offset(x = (-8).dp), cell = 3.dp, onClick = onFind)
        Word("your year", pixel(13.sp, Ink.mid), touch = 6.dp) { Nav.open(Page.Year) }
        Word("settings", pixel(13.sp, Ink.mid), touch = 6.dp, onClick = onSettings)
        Spacer(Modifier.weight(1f))
        BasicText("made by ink", style = pixel(12.sp, Ink.dim))
    }
}

val RAIL = 210.dp
val NOW = 360.dp
/** How narrow and how wide the now column can be dragged. */
val NOW_LEAST = 300.dp
val NOW_MOST = 620.dp

/**
 * The now column: what's playing kept beside whatever's browsed (the phone's player, small). The cover with the
 * line along its top and the times over it, the song, its artist and record, the controls; then up next, the
 * lyrics or where it plays, chosen by a word. The cover opens the player, the whole window.
 */
@Composable
fun NowColumn(onTuck: () -> Unit, open: (Page) -> Unit) {
    val now by Player.now.collectAsState()
    val trouble by Player.trouble.collectAsState()
    var shows by remember { mutableStateOf("up next") }
    val wide by Tuck.width.collectAsState()
    Row(Modifier.fillMaxHeight()) {
    // its left edge, dragged: the column wider or narrower (kept); a hairline under the mouse says it can be
    val edge = remember { MutableInteractionSource() }
    val over by edge.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        Modifier.width(12.dp).fillMaxHeight().hoverable(edge)
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true }, onDragEnd = { dragging = false; Tuck.keepWidth() }, onDragCancel = { dragging = false },
                ) { c, dx -> c.consume(); Tuck.width.value = (Tuck.width.value - with(density) { dx.toDp() }).coerceIn(NOW_LEAST, NOW_MOST) }
            },
        contentAlignment = Alignment.Center,
    ) { if (over || dragging) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = TOP + 40.dp).background(if (dragging) Ink.mid else Ink.line)) }
    Column(Modifier.width(wide).fillMaxHeight().padding(end = SIDE, top = TOP + 4.dp, bottom = 16.dp)) {
        // tucked away to the right (the bar along the foot shows it instead)
        Box(Modifier.fillMaxWidth().height(36.dp)) {
            Pixel(TUCK, "tuck away", Ink.dim, Modifier.align(Alignment.CenterEnd).offset(x = 8.dp), onClick = onTuck)
        }
        val n = now
        if (n == null) {
            BasicText("NOTHING PLAYING", style = headline(22.sp, Ink.dim))
            Spacer(Modifier.height(6.dp))
            BasicText(trouble ?: "a record, a song or a station: what you play shows here", style = pixel(12.sp, Ink.mid))
            return@Column
        }
        val cover = rememberCover(n.song, 720)
        val pos by Player.position.collectAsState()
        val heard by Player.heard.collectAsState()
        val on by Casting.on.collectAsState()
        // the times, what's heard between them (a click opens the player), the line along the cover's top
        Scrubber(n, cover?.accent ?: Ink.ink) {
            heard?.takeIf { it.id == n.song.id && on == null }?.let { h ->
                Word(h.word, pixel(12.sp, if (h.lossless) Ink.ink else Ink.mid)) { open(Page.Playing) }
            }
        }
        // the song small, so up next and the lyrics have the column (Bronson, 2026-10-28: "far too small"): the cover
        // under the line, its words beside it; the cover opens the player, the big one
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(112.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(Page.Playing) })) {
                CoverOf(n.song, n.song.album.ifEmpty { n.song.title }, Modifier.fillMaxSize(), px = 720, moving = n.playing)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                val (title, artist) = said(n)
                Row(verticalAlignment = Alignment.Top) {
                    BasicText(title.uppercase(), style = headline(19.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Heart(n.song, 2.dp, Modifier.offset(x = 8.dp, y = (-6).dp))
                }
                Spacer(Modifier.height(4.dp))
                Word(artist.lowercase(), pixel(13.sp)) { if (!n.song.station) open(Page.Artist(n.song.albumArtist.ifBlank { n.song.artist.substringBefore(", ") })) }
                if (n.song.album.isNotEmpty()) Word(n.song.album.lowercase(), pixel(12.sp, Ink.mid)) { open(Page.Record(n.song.albumId)) }
                on?.let { BasicText("on ${it.name.lowercase()}", style = pixel(12.sp, Ink.mid)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Controls(n, Modifier.fillMaxWidth())
        Volume(Modifier.offset(x = (-8).dp), line = 200.dp)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            // the lyrics are the player's (Bronson, 2026-10-28: "only lyrics when you go full screen")
            for (w in listOf("up next", "play on")) Word(w, pixel(13.sp, if (w == shows) Ink.ink else Ink.dim), touch = 6.dp) { shows = w }
            Spacer(Modifier.weight(1f))
            // the player, the whole screen
            // the mini player, over every window; the player, the whole screen
            Row(Modifier.offset(x = 8.dp)) {
                Pixel(SHRINK, "mini player", Ink.mid) { Mini.open() }
                Pixel(FULL, "full screen", Ink.mid) { Win.full(true); open(Page.Playing) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (shows) {
                "up next" -> {
                    val list = rememberLazyListState()
                    // the song playing near the top as the song changes
                    LaunchedEffect(n.index) { if (n.index > 1) list.animateScrollToItem(n.index - 1) }
                    Column {
                        RadioWord()
                        QueueList(open, list, PaddingValues(bottom = 16.dp), Modifier.fillMaxSize())
                    }
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState())) { PlayOnList(small = true) }
            }
        }
    }
}
}

/** The now column tucked away (kept): the window's foot bar shows what's playing instead. */
object Tuck {
    private val file = java.io.File(Home.dir, "now-tucked")
    private val wideFile = java.io.File(Home.dir, "now-width")
    /** The now column's width, as dragged (kept). */
    val width = kotlinx.coroutines.flow.MutableStateFlow(runCatching { wideFile.readText().trim().toFloat().dp }.getOrDefault(NOW).coerceIn(NOW_LEAST, NOW_MOST))
    fun keepWidth() { runCatching { wideFile.writeText(width.value.value.toString()) } }
    val on = kotlinx.coroutines.flow.MutableStateFlow(file.exists())
    fun set(t: Boolean) { on.value = t; runCatching { if (t) file.writeText("") else file.delete() } }
}
