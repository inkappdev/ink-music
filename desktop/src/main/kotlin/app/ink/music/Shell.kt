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
import androidx.compose.ui.graphics.graphicsLayer
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
val PLACES = listOf("listen", "ink", "albums", "artists", "songs", "radio", "podcasts", "lists")
    // local files only: the places that are all online (INK, radio, podcasts) not there (Local)
    .filter { !Local.on || it !in setOf("ink", "radio", "podcasts") }

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
        // each word rising in as the rail opens, 40 ms after the one before (the places menu's motion)
        PLACES.forEachIndexed { i, p ->
          if (i == 0) Row(Modifier.rising(0), verticalAlignment = Alignment.CenterVertically) {
            Word(p.uppercase(), headline(22.sp, if (p == place) Ink.ink else Ink.dim), Modifier.padding(vertical = 2.dp), touch = 6.dp) { onPlace(p) }
            Spacer(Modifier.weight(1f))
            // fold the rail in (⌘⇧\): only the INK wordmark left, an arrow by it to bring it out
            Pixel(FOLD_LEFT, "fold the places away", Ink.dim, Modifier.padding(end = 18.dp), cell = 2.dp) { RailFold.set(true) }
          } else
            Word(p.uppercase(), headline(22.sp, if (p == place) Ink.ink else Ink.dim), Modifier.rising(i).padding(vertical = 2.dp), touch = 6.dp) { onPlace(p) }
        }
        Spacer(Modifier.height(28.dp))
        // find, the lens (⌘F)
        Pixel(SEARCH, "find", Ink.mid, Modifier.rising(PLACES.size).offset(x = (-8).dp), cell = 3.dp, onClick = onFind)
        Word("your year", pixel(13.sp, Ink.mid), Modifier.rising(PLACES.size + 1), touch = 6.dp) { Nav.open(Page.Year) }
        Word("settings", pixel(13.sp, Ink.mid), Modifier.rising(PLACES.size + 2), touch = 6.dp, onClick = onSettings)
        Spacer(Modifier.weight(1f))
        // made by ink, a small pixel heart after it; ink its own website (the quiet funnel, never a nag)
        Row(Modifier.rising(PLACES.size + 3), verticalAlignment = Alignment.CenterVertically) {
            BasicText("made by ", style = pixel(12.sp, Ink.dim))
            Word("ink", pixel(12.sp, Ink.mid)) { browse("https://inkhome.app") }
            Spacer(Modifier.width(6.dp))
            // it beats when clicked; seven clicks and hearts float up (an easter egg)
            InkHeart(Ink.dim)
        }
    }
}

val RAIL = 210.dp
/** Rising in as it first shows, `order` steps of 40 ms after the first (the rail's words as it opens). */
@Composable
private fun Modifier.rising(order: Int): Modifier {
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(order * 40L); shown.animateTo(1f, androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo)) }
    return graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * Motion.drift.toPx() * .6f }
}

/** The rail folded in: INK (a click, the INK place) and the arrow by it that brings the rail back out. */
@Composable
fun FoldedRail(onInk: () -> Unit) {
    Column(Modifier.width(FOLDED).fillMaxHeight().padding(start = SIDE, top = TOP + 40.dp)) {
        // the arrow on the word's middle (it sat low, by the top of its box)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Word("INK", headline(22.sp), Modifier.padding(vertical = 2.dp), touch = 6.dp, onClick = onInk)
            Spacer(Modifier.width(10.dp))
            Pixel(FOLD_RIGHT, "the places beside", Ink.dim, cell = 2.dp) { RailFold.set(false) }
        }
    }
}

/** The rail folded in: as wide as INK and its arrow. */
val FOLDED = 112.dp
val FOLD_LEFT = listOf("..#", ".#.", "#..", ".#.", "..#")
val FOLD_RIGHT = listOf("#..", ".#.", "..#", ".#.", "#..")

/** The rail folded in (2026-11-10, kept): the page takes its room, the INK wordmark left with an arrow by it to bring it out. */
object RailFold {
    private val file = java.io.File(Home.dir, "rail-folded")
    val on = kotlinx.coroutines.flow.MutableStateFlow(file.exists())
    fun set(t: Boolean) { on.value = t; runCatching { if (t) file.writeText("") else file.delete() } }
}
val NOW = 360.dp
/**
 * The widest a sentence or a paragraph runs (a quote, a note, notes): about 45 to 60 letters a line, the same at every
 * size. Lists, grids and shelves take the room there is; words to read wrap here, so a smaller size leaves black
 * beside them rather than one line across the screen (2026-11-10: the record of the day's quote at 80%).
 */
val READ = 640.dp
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
        // tucked away, down into the bar along the foot (it shows what's playing instead; its ↑ brings this back)
        Box(Modifier.fillMaxWidth().height(36.dp)) {
            Pixel(TUCK, "down into the bar", Ink.mid, Modifier.align(Alignment.CenterEnd).offset(x = 8.dp), onClick = onTuck)
        }
        val n = now
        if (n == null) {
            BasicText("NOTHING PLAYING", style = headline(22.sp, Ink.dim))
            Spacer(Modifier.height(6.dp))
            trouble?.let { NoSound(it) } ?: BasicText("a record, a song or a station: what you play shows here", style = pixel(12.sp, Ink.mid))
            return@Column
        }
        val cover = rememberCover(n.song, 720)
        val pos by Player.position.collectAsState()
        val heard by Player.heard.collectAsState()
        val on by Casting.on.collectAsState()
        // the times, what's heard between them (a click drops the signal path down over the column, again takes it
        // away; it opened the whole player, the path a second click there), the line along the cover's top
        val hh = heard?.takeIf { it.id == n.song.id && on == null }
        var pathDown by remember { mutableStateOf(false) }
        Scrubber(n, cover?.accent ?: Ink.ink) {
            hh?.let { h -> Word(h.word, pixel(12.sp, if (h.lossless || pathDown) Ink.ink else Ink.mid)) { pathDown = !pathDown } }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
        // the song small, so up next and the lyrics have the column (2026-10-28): the cover
        // under the line, its words beside it; the cover opens the player, the big one; a right click its options
        var menuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        menuAt?.let { SongMenu(n.song, open, null, it, playing = true) { menuAt = null } }
        Row(Modifier.rightClick { menuAt = it }, verticalAlignment = Alignment.Top) {
            Opens(Modifier.size(112.dp), { open(Page.Playing) }, left = true) {
                CoverOf(n.song, n.song.album.ifEmpty { n.song.title }, Modifier.fillMaxSize(), px = 720, moving = n.playing)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                val (title, artist) = said(n)
                // a station's song, as it says it (OnAirSong): its title opens find with it typed, its artist their page; the
                // song found, a heart to love it and "add" to put it in a list
                val aired = rememberAired(n)
                val airedSong = rememberAiredSong(n)
                Row(verticalAlignment = Alignment.Top) {
                    WholeWords(title.uppercase(), headline(19.sp), 3, Modifier.weight(1f).then(
                        if (aired != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                            onClick = felt { OnAirSong.find(aired.title, aired.artist!!) }) else Modifier))
                    Heart(airedSong ?: n.song, 2.dp, Modifier.offset(x = 8.dp, y = (-6).dp))
                }
                Spacer(Modifier.height(4.dp))
                Word(artist.lowercase(), pixel(13.sp)) {
                    if (n.song.episode) Pods.page(n.song)?.let(open)
                    else if (aired != null) open(Page.Artist(aired.artist!!.substringBefore(", ")))
                    else if (!n.song.station) open(Page.Artist(n.song.albumArtist.ifBlank { n.song.artist.substringBefore(", ") }))
                }
                airedSong?.let { s -> Word("add to a list", pixel(12.sp, Ink.mid), touch = 6.dp) { open(Page.AddTo(s)) } }
                if (n.song.album.isNotEmpty()) Word(n.song.album.lowercase(), pixel(12.sp, Ink.mid)) { open(Page.Record(n.song.albumId)) }
                on?.let { BasicText("on ${it.name.lowercase()}", style = pixel(12.sp, Ink.mid)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Controls(n, Modifier.fillMaxWidth())
        // the volume, and at its end the mini player (over every window) and the player, the whole screen: here, as the
        // words below filled their row once sleep joined them and squeezed these two over each other (2026-11-10)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Volume(Modifier.weight(1f).offset(x = (-8).dp), line = 200.dp, shrinks = true)
            Row(Modifier.offset(x = 8.dp)) {
                Pixel(SHRINK, "mini player", Ink.mid) { Mini.open() }
                Pixel(FULL, "full screen", Ink.mid) { Win.full(true); open(Page.Playing) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
        Spacer(Modifier.height(10.dp))
        // across the column, as the controls are; sleep's "song's end" just "end" in the narrowest (it ran past the edge)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tight = maxWidth < 300.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // the lyrics are the player's (2026-10-28)
            for (w in listOf("up next", "play on")) Word(w, pixel(13.sp, if (w == shows) Ink.ink else Ink.dim), touch = 6.dp) { shows = w }
            // stop: the music over, the queue let go, this column gone (the phone's)
            Word("stop", pixel(13.sp, Ink.dim), touch = 6.dp) { Player.stop() }
            // sleep: each click the next (15, 30, 60 minutes, the song's end, off), lit while it's set, the minutes left
            val sleep by Sleep.at.collectAsState()
            val tick by androidx.compose.runtime.produceState(0L, sleep) { while (sleep > 0) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(20_000) } }
            Word(remember(sleep, tick, tight) {
                if (sleep == 0L) "sleep" else Sleep.said().removePrefix("sleep · ").let { if (tight && it == "song's end") "end" else it }
            }, pixel(13.sp, if (sleep != 0L) Ink.ink else Ink.dim), touch = 6.dp) { Sleep.next() }
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
        // the signal path, unrolling down from the line over the song and up next
        androidx.compose.animation.AnimatedVisibility(
            pathDown && hh != null,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(Motion.ENTER * 2, easing = Motion.expo), expandFrom = Alignment.Top) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo), shrinkTowards = Alignment.Top) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.EXIT, easing = Motion.expo)),
        ) { SignalColumn(hh, { pathDown = false }) }
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
