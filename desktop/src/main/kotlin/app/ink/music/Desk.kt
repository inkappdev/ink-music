package app.ink.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * The window's size and place, kept from one run to the next, and whether it was zoomed big (2026-10-29: it
 * always opened tiny: a zoomed window kept nothing, so the size before came back). Never smaller than [LEAST], nor
 * bigger than the screen.
 */
object Placed {
    private val file = File(Home.dir, "window.json")
    /**
     * The smallest the window is let be, and kept: the phone's layout (the places across the top, the bar along the
     * foot). It was 960 x 640 while the window itself could be dragged to 720 x 520, so a narrower window (an upright
     * screen) was never kept and came back wider than its screen's half (2026-11-09).
     */
    val LEAST = DpSize(720.dp, 520.dp)
    private val FIRST = DpSize(1440.dp, 900.dp)

    @Composable
    fun remember(): WindowState {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()
        // the screen it was left on, where that's still plugged in (2026-11-09: it only knew the main one, so a window
        // left on a second screen came back on the first); else the main one
        val screen = o?.takeIf { it.has("x") }?.let { screenAt(it.optDouble("x").toInt(), it.optDouble("y").toInt()) }
            ?: runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
        // the first time at most 90% of the screen (a laptop's 1280 x 800 is under FIRST), then as it was left
        val w = o?.optDouble("w", FIRST.width.value.toDouble())?.toFloat() ?: screen?.let { minOf(FIRST.width.value, it.width * .9f) } ?: FIRST.width.value
        val h = o?.optDouble("h", FIRST.height.value.toDouble())?.toFloat() ?: screen?.let { minOf(FIRST.height.value, it.height * .9f) } ?: FIRST.height.value
        val size = DpSize(
            w.coerceAtLeast(LEAST.width.value).let { v -> screen?.let { minOf(v, it.width.toFloat()) } ?: v }.dp,
            h.coerceAtLeast(LEAST.height.value).let { v -> screen?.let { minOf(v, it.height.toFloat()) } ?: v }.dp,
        )
        // where it was, kept on the screen (one since unplugged, a smaller one); the first time in the middle: the
        // system's own place (Windows' cascade) can push a big window off the screen's edge
        val at = o?.takeIf { it.has("x") }?.let {
            var x = it.optDouble("x").toFloat(); var y = it.optDouble("y").toFloat()
            screen?.let { b ->
                x = x.coerceIn(b.x.toFloat(), maxOf(b.x.toFloat(), b.x + b.width - size.width.value))
                y = y.coerceIn(b.y.toFloat(), maxOf(b.y.toFloat(), b.y + b.height - size.height.value))
            }
            WindowPosition(x.dp, y.dp)
        } ?: WindowPosition(Alignment.Center)
        // the first time as big as the screen lets it (a tester's ask, 2026-10-30), then as it was left
        val zoomed = o?.optBoolean("zoomed") ?: true
        return rememberWindowState(placement = if (zoomed) WindowPlacement.Maximized else WindowPlacement.Floating, size = size, position = at)
    }

    /**
     * The room on the screen a place is on (or nearest it: a window's corner can sit just off a screen's edge), less
     * the taskbar; null when no screen is near (one since unplugged).
     */
    fun screenAt(x: Int, y: Int): java.awt.Rectangle? = runCatching {
        val env = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        env.screenDevices.map { it.defaultConfiguration }.map { c ->
            val b = c.bounds
            val i = toolkit.getScreenInsets(c)
            java.awt.Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
        }.firstOrNull { java.awt.Rectangle(it.x - 40, it.y - 40, it.width + 80, it.height + 80).contains(x + 40, y + 40) }
    }.getOrNull()

    /** Written down as it is now: its plain size (as it was before, while full screen), and whether it's zoomed. */
    fun keep(s: WindowState) {
        if (s.isMinimized || Win.full) return
        val floating = s.placement == WindowPlacement.Floating
        val size = if (floating) s.size else Win.before ?: s.size
        val p = if (floating) s.position else Win.beforeAt ?: s.position
        if (!size.width.value.isFinite() || size.width < LEAST.width || size.height < LEAST.height) return
        runCatching {
            Home.write(file, JSONObject().put("w", size.width.value.toDouble()).put("h", size.height.value.toDouble())
                .put("zoomed", s.placement == WindowPlacement.Maximized)
                .apply { if (p.isSpecified) put("x", p.x.value.toDouble()).put("y", p.y.value.toDouble()) }.toString())
        }
    }
}

/**
 * How big everything's drawn, over the desktop's own scale (2026-10-29, GNOME on a 4K at 125%: "far too
 * small for the window"). "fit", the first choice, trusts the desktop's own scale (the person's own answer to how far
 * they sit from how big a screen) and steps in only where a screen's set small: one taller than 1440 points, made
 * 1440 tall (2026-11-10: by its width, made 1920 across, a 32" 4K at Windows' 150% came out 135%, too big; height, as
 * an ultrawide at 100% is wide without its words being small). A 4K at 150%: as it is; at 125%: 120%; unscaled: 150%.
 * Or a size chosen in settings, or by ⌘/Ctrl = and -, or ⌘/Ctrl and the wheel. Kept in a file.
 */
object Size {
    private val file = File(Home.dir, "size")
    /** null is fit; else the size chosen, 1 the desktop's own. */
    val chosen = MutableStateFlow(runCatching { file.readText().trim().toFloat() }.getOrNull())
    // smaller than the desktop's own too (2026-11-10: "the interface and fonts are way too large"), and finer steps just
    // above it (2026-11-10, Bronson: 100% on a 4K "could be just a tad bit bigger")
    val CHOICES = listOf(.8f, .9f, 1f, 1.1f, 1.2f, 1.35f, 1.5f, 1.75f, 2f)

    /** The size fit gives this screen. */
    val fit: Float by lazy {
        val tall = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds.height }.getOrNull() ?: 0
        (Math.round(tall / 1440f * 20) / 20f).coerceIn(1f, 2f)
    }

    fun of(chosen: Float?) = chosen ?: fit

    fun set(to: Float?) {
        chosen.value = to?.let { Math.round(it.coerceIn(.75f, 2.5f) * 20) / 20f }
        runCatching { if (to == null) file.delete() else Home.write(file, chosen.value.toString()) }
    }

    /** Counted up at each step from the keys, the wheel or the menu, for [SizeShown]. */
    val stepped = MutableStateFlow(0)

    fun bigger() { set(of(chosen.value) + .1f); stepped.value++ }
    fun smaller() { set(of(chosen.value) - .1f); stepped.value++ }
}

/** The window's content at [Size]'s size. */
@Composable
fun Sized(content: @Composable () -> Unit) {
    val chosen by Size.chosen.collectAsState()
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * Size.of(chosen), d.fontScale),
        content = content,
    )
}

/**
 * Closing the window on Windows and Linux: the app quits, as it always has; or, chosen in settings (about), the music
 * plays on with ink music in the tray (by the clock), its icon there bringing the window back and its menu playing,
 * pausing, going on and quitting (2026-11-09). A Mac's apps go on in the Dock by themselves.
 */
object Tucked {
    private val file = File(Home.dir, "close-to-tray")
    /** Whether closing the window leaves the music playing, ink music in the tray. */
    val keep = MutableStateFlow(!Home.mac && file.exists())
    /** The window closed into the tray. */
    val away = MutableStateFlow(false)

    fun choose(on: Boolean) {
        keep.value = on
        runCatching { if (on) file.writeText("") else file.delete() }
        if (!on) away.value = false
    }

    /** The window's close: into the tray, or the whole app quit. */
    fun close() { if (keep.value) away.value = true else quit() }

    /** Back from the tray, to the front. */
    fun back() {
        away.value = false
        // a Mac's window is never minimised by ink (Mini.open), and asking it to come back from that waits as long
        if (!Home.mac) Win.state?.isMinimized = false
        java.awt.EventQueue.invokeLater { java.awt.Frame.getFrames().firstOrNull { it.title == "ink music" && it.isDisplayable }?.let { it.toFront(); it.requestFocus() } }
    }
}

/** ink music in the tray while its window's closed into it: its icon brings the window back, its menu plays. */
@Composable
fun androidx.compose.ui.window.ApplicationScope.InkTray() {
    val keep by Tucked.keep.collectAsState()
    if (!keep) return
    val now by Player.now.collectAsState()
    val n = now
    Tray(
        icon = inkIcon,
        tooltip = n?.let { "${it.song.title} · ${it.song.artist}" } ?: "ink music",
        onAction = { Tucked.back() },
        menu = {
            if (n != null) {
                Item(if (n.playing) "Pause" else "Play") { Player.toggle() }
                Item("Next") { Player.next() }
                Item("Back") { Player.previous() }
                Separator()
            }
            Item("Show ink music") { Tucked.back() }
            Item("Quit") { quit() }
        },
    )
}

/**
 * The mini player: what's playing in a small window kept over every other, to be dragged anywhere; the player made
 * small, not the bar cut out of the window (2026-11-09: "more unique and ink"). The cover the window's whole height at
 * its left edge (the handle it's dragged by); the song and its artist; back / play / next and the heart; the beat's
 * four squares and the tempo across from them, in a place of their own (they sat on the line, the tempo cut off);
 * the artist's name big and faint behind it all, drifting while it plays; the song's line along the window's foot in
 * the cover's colour. A hairline round it, so it reads as a thing over a dark desktop. The main window goes to the
 * dock meanwhile, back with ↗.
 */
object Mini {
    val on = MutableStateFlow(false)
    private val file = File(Home.dir, "mini.json")

    /** Where it was left (kept), if that's still on a screen; else the top right of the main one, as it always was. */
    fun at(): WindowPosition = runCatching { JSONObject(file.readText()) }.getOrNull()?.let { o ->
        val x = o.optDouble("x").toFloat(); val y = o.optDouble("y").toFloat()
        Placed.screenAt(x.toInt(), y.toInt())?.let { b ->
            WindowPosition(x.coerceIn(b.x.toFloat(), (b.x + b.width - 440).toFloat().coerceAtLeast(b.x.toFloat())).dp,
                y.coerceIn(b.y.toFloat(), (b.y + b.height - 128).toFloat().coerceAtLeast(b.y.toFloat())).dp)
        }
    } ?: WindowPosition(Alignment.TopEnd)

    fun keep(p: WindowPosition) {
        if (!p.isSpecified) return
        runCatching { Home.write(file, JSONObject().put("x", p.x.value.toDouble()).put("y", p.y.value.toDouble()).toString()) }
    }

    /** Only with something to show (with nothing playing it was a small black window, the main one gone to the Dock). */
    // the main window out of the way once the mini player's up (MiniWindow), not here. On a Mac it's hidden, never
    // minimised: Java's minimise there waits on the event thread for the window to reach the Dock, while the Dock's
    // animation waits on the same thread to move it, and the app froze for good (2026-11-09, both thread dumps). A
    // click on ink music in the Dock brings it back (dockReopens)
    fun open() { if (Player.now.value == null) return; on.value = true }
    fun close() { on.value = false; if (!Home.mac) Win.state?.isMinimized = false; if (Tucked.away.value) Tucked.back() }

    /** The main window out of the way while the mini player's up: hidden on a Mac (see [open]), to the taskbar elsewhere. */
    fun away() { if (Home.mac) Tucked.away.value = true else Win.state?.isMinimized = true }

    /** A Mac: ink music clicked in the Dock while its window's hidden (the mini player up): the window back. */
    fun dockReopens() {
        if (!Home.mac) return
        runCatching {
            java.awt.Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener {
                if (on.value) close() else if (Tucked.away.value) Tucked.back()
            })
        }
    }
}

@Composable
fun MiniWindow() {
    val on by Mini.on.collectAsState()
    if (!on) return
    // made at ink's size from the start: a resize as it first shows was half of the Mac's freeze (Mini.open)
    val state = rememberWindowState(size = DpSize(440.dp, 128.dp) * Size.of(Size.chosen.value), position = Mini.at())
    Window(
        onCloseRequest = { Mini.close() }, state = state, title = "ink music", icon = inkIcon, undecorated = true,
        alwaysOnTop = true, resizable = false,
    ) {
        window.background = java.awt.Color.BLACK
        // ink's size (settings, Ctrl = and -): the window and all in it, as the main one's content is (it stayed small)
        val chosen by Size.chosen.collectAsState()
        val k = Size.of(chosen)
        androidx.compose.runtime.LaunchedEffect(k) { if (state.size != DpSize(440.dp * k, 128.dp * k)) state.size = DpSize(440.dp * k, 128.dp * k) }
        // the main window out of the way, once this one has shown and settled
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(350); Mini.away() }
        // where it's dragged to, kept for next time (a moment after it stops)
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { state.position }.collect { kotlinx.coroutines.delay(600); Mini.keep(it) }
        }
        val now by Player.now.collectAsState()
        val n = now
        WindowDraggableArea { Sized {
            Box(Modifier.fillMaxSize().background(Ink.black).border(1.dp, Ink.line)) {
                if (n == null) {
                    // stopped while it was up: said, and the way back
                    BasicText("NOTHING PLAYING", style = headline(18.sp, Ink.dim), modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
                    Pixel(OPEN, "back to the window", Ink.mid, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { Mini.close() }
                } else MiniSleeve(n)
            }
        } }
    }
}

@Composable
private fun MiniSleeve(n: Player.Now) {
    val cover = rememberCover(n.song, 320)
    val accent by animateColorAsState(cover?.accent ?: Ink.ink, tween(Motion.ENTER), label = "accent")
    val names by Marquees.on.collectAsState()
    val (title, artist) = said(n)
    // a right click on it, the song's options (2026-11-11, a tester: none in the mini player)
    var menuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    Row(Modifier.fillMaxSize().rightClick { menuAt = it }) {
        // inside what was clicked, so the menu opens from the click
        menuAt?.let { SongMenu(n.song, null, null, it, playing = true) { menuAt = null } }
        // nothing a click does: the cover is what the window's taken hold of by, to drag it
        CoverOf(n.song, n.song.album.ifEmpty { n.song.title }, Modifier.fillMaxHeight().aspectRatio(1f), px = 320, moving = n.playing)
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            // the player's marquee, small and faint: under the words, never over them
            Marquee(artist, n.playing && names, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 10.dp), size = 64.sp, color = Ink.ink.copy(alpha = .09f))
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            BasicText(title.uppercase(), style = headline(20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            BasicText(artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Pixel(OPEN, "back to the window", Ink.mid) { Mini.close() }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Controls(n, small = true)
                        Heart(n.song, 2.dp, Modifier.padding(start = 10.dp))
                        Spacer(Modifier.weight(1f))
                        if (!n.song.station && n.duration > 0) BeatCounter(n.song.id, n.playing, Modifier.padding(end = 6.dp), key = false)
                    }
                }
                // from the cover's edge to the window's
                SeekLine(n, accent, beat = false)
            }
        }
    }
}

/** Out of the mini player, back to the window (an arrow up and out); and into it (the window made small). */
val OPEN = listOf("..####", "....##", "...#.#", "..#..#", ".#....", "#.....")
val SHRINK = listOf("#.....", ".#....", "..#..#", "...#.#", "....##", "..####")

/**
 * Files and folders dropped on the window play: a song, a record's folder, a stack of them, in the order dropped
 * (a folder's songs by disc and track). Whatever lies outside the music folder plays as well, read from its tags.
 */
object Dropped {
    private val scope = CoroutineScope(Dispatchers.IO)
    val over = MutableStateFlow(false)
    /** A list of yours open (HereLists): what's dropped goes into it, not played (2026-11-12, a tester). */
    @Volatile var into: String? = null

    /** Files and folders as songs, a record's in its order. */
    fun songsOf(paths: List<File>): List<Song> = paths.flatMap { f ->
        if (f.isDirectory) Library.read(f.walkTopDown().filter { it.isFile }.toList())
            .sortedWith(compareBy({ it.albumId }, { it.disc }, { it.track }, { it.path }))
        else Library.read(listOf(f))
    }

    fun play(paths: List<File>) = scope.launch {
        // an .inkmusic file dropped: its list made yours and opened
        paths.filter { it.name.endsWith(".inkmusic") }.mapNotNull { ListOut.open(it) }.lastOrNull()?.let { m ->
            javax.swing.SwingUtilities.invokeLater { Nav.open(Page.List(m.id, m.name)) }
        }
        val songs = songsOf(paths.filterNot { it.name.endsWith(".inkmusic") })
        val list = into
        if (songs.isEmpty()) return@launch
        if (list != null) HereLists.add(list, songs)
        else javax.swing.SwingUtilities.invokeLater { Player.play(songs) }
    }
}

/** The window as a place to drop songs on: lit while they're over it. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.takesDrops(): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
    target = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) { Dropped.over.value = true }
        override fun onExited(event: DragAndDropEvent) { Dropped.over.value = false }
        override fun onEnded(event: DragAndDropEvent) { Dropped.over.value = false }
        override fun onDrop(event: DragAndDropEvent): Boolean {
            Dropped.over.value = false
            val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                .mapNotNull { runCatching { File(java.net.URI(it)) }.getOrNull() }
            if (files.isEmpty()) return false
            Dropped.play(files)
            return true
        }
    },
)

/** Over the window while songs are dragged across it: what letting go does. */
@Composable
fun DropHint() {
    val over by Dropped.over.collectAsState()
    AnimatedVisibility(over, enter = fadeIn(tween(Motion.ENTER, easing = Motion.expo)), exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
        Box(Modifier.fillMaxSize().background(Ink.black.copy(alpha = .85f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(if (Dropped.into != null) "LET GO TO ADD" else "LET GO TO PLAY", style = headline(64.sp))
                Spacer(Modifier.height(10.dp))
                BasicText("songs, a record's folder, a stack of them", style = pixel(14.sp, Ink.mid))
            }
        }
    }
}

/**
 * The size as it's stepped (ctrl or ⌘ with the wheel, = and -, the menu): its percentage, big, in the middle of the
 * window for a moment, so it's known where it's got to (2026-11-10, Bronson). Drawn at the new size, as the rest is.
 */
@Composable
fun SizeShown() {
    val stepped by Size.stepped.collectAsState()
    val chosen by Size.chosen.collectAsState()
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(stepped) {
        if (stepped == 0) return@LaunchedEffect
        shown = true
        kotlinx.coroutines.delay(1200)
        shown = false
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(shown, enter = fadeIn(tween(Motion.ENTER, easing = Motion.expo)), exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
            Column(
                Modifier.background(Ink.black).border(1.dp, Ink.line).padding(horizontal = 40.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText("${Math.round(Size.of(chosen) * 100)}%", style = headline(64.sp))
                Spacer(Modifier.height(6.dp))
                BasicText(if (Home.mac) "size · ⌘ = and -" else "size · ctrl = and -", style = pixel(12.sp, Ink.mid))
            }
        }
    }
}

/**
 * Windows, on JetBrains' Java (the app's own since 2026-11-09): no title bar of Windows' own over ink. Ink's black runs
 * to the window's top, the strip along the top [TOP] tall and empty (it's what the window's dragged by, double-clicked
 * to fill the screen, snapped with), and Windows' own minimise, maximise and close drawn over its right end, light on
 * dark, the maximise button's snap layouts and all. On another Java, or with -Dink.titlebar=windows, Windows' bar as
 * before ([darkTitleBar]). Ink puts nothing to press in the strip: a press there is the window's.
 */
object InkTitle {
    private val file = File(Home.dir, "title-bar")
    /**
     * The top chosen in settings ("ink" or "windows"); none chosen, ink's, but Windows' own on Windows 10, where ink's
     * didn't minimise from the taskbar (2026-11-10, a Reddit report; Windows 11's was fine).
     */
    val wanted: String = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it == "ink" || it == "windows" }
        ?: if (System.getProperty("os.name") == "Windows 10") "windows" else "ink"
    fun choose(to: String) { runCatching { file.writeText(to) } }

    val on: Boolean = Home.windows && System.getProperty("ink.titlebar") != "windows" && wanted == "ink" &&
        runCatching { com.jetbrains.JBR.isWindowDecorationsSupported() }.getOrDefault(false)
    /** The strip's height, in ink's own size (dp at ink's size 1). */
    const val HEIGHT = 34f
    /** The bar as it's set now: the strip tells it, at each move of the mouse, that it's the bar's ([TitleStrip]). */
    @Volatile var bar: com.jetbrains.WindowDecorations.CustomTitleBar? = null

    /** The size it was last made at, for putting it back after full screen ([full]). */
    @Volatile private var k = 1f

    /** The main window's bar made ink's, `k` its size (settings): the strip as tall as the pages' [TOP] is drawn. */
    fun apply(window: java.awt.Window, k: Float) {
        if (!on) return
        this.k = k
        if (Win.full) return   // full screen: no bar till it's left, and then at this size
        val frame = window as? java.awt.Frame ?: return
        runCatching {
            val d = com.jetbrains.JBR.getWindowDecorations()
            val bar = d.createCustomTitleBar()
            bar.height = HEIGHT * k
            bar.putProperty("controls.dark", true)
            d.setCustomTitleBar(frame, bar)
            this.bar = bar
        }.onFailure { Log.d("title bar: $it") }
    }

    /**
     * Full screen: no minimise, maximise and close over ink's top right (JetBrains' Java draws them itself, so the
     * window's frame taken off left them: 2026-11-12, Bronson), and no strip to drag; left, the bar as it was.
     */
    fun full(window: java.awt.Window, on: Boolean) {
        if (!this.on) return
        val frame = window as? java.awt.Frame ?: return
        if (!on) return apply(window, k)
        runCatching {
            val d = com.jetbrains.JBR.getWindowDecorations()
            val bar = d.createCustomTitleBar()
            // the least height there is: 0 is refused (the call failed, quietly, and the buttons stayed: 2026-11-12)
            bar.height = 1f
            bar.putProperty("controls.visible", false)
            d.setCustomTitleBar(frame, bar)
            this.bar = null
        }.onFailure { Log.d("title bar: $it") }
    }
}

/**
 * The strip along the window's top, over everything and drawing nothing: every mouse event on it told to JetBrains'
 * Java as the title bar's, so it drags the window, double-clicks it full and snaps it. Without it the strip was
 * ink's (Compose listens to the mouse across the whole window) and the window couldn't be moved (2026-11-09).
 */
@Composable
fun TitleStrip(k: Float) {
    if (!InkTitle.on || Win.full) return
    Box(Modifier.fillMaxWidth().height((InkTitle.HEIGHT * k).dp).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                InkTitle.bar?.forceHitTest(false)
            }
        }
    })
}

/**
 * The Mac's top strip, as tall as the pages' [TOP] (under the traffic lights): a double-click there does what the Mac
 * says a title bar's double-click does (Desktop & Dock: zoom, which fills the screen, by default; or minimise; or
 * nothing). ink's page runs under the bar (fullWindowContent), so the click was Compose's and the Mac never had it
 * (2026-11-09). It only watches: nothing is taken from the page, the window still dragged as before.
 */
@Composable
fun MacTitleStrip(k: Float) {
    if (!Home.mac) return
    Box(Modifier.fillMaxWidth().height((28f * k).dp).pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        var last = 0L
        var lastAt = androidx.compose.ui.geometry.Offset.Zero
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (e.type != androidx.compose.ui.input.pointer.PointerEventType.Press || !e.buttons.isPrimaryPressed) continue
                val at = e.changes.firstOrNull()?.position ?: continue
                val now = e.changes.first().uptimeMillis
                if (now - last <= viewConfiguration.doubleTapTimeoutMillis && (at - lastAt).getDistance() <= slop * 2) {
                    last = 0L; MacTitle.doubleClicked()
                } else { last = now; lastAt = at }
            }
        }
    })
}

object MacTitle {
    /** What the Mac's set to do (AppleActionOnDoubleClick: Maximize or Fill, Minimize, None; zoom where it's never been set). */
    private fun action(): String = runCatching {
        val p = ProcessBuilder("defaults", "read", "-g", "AppleActionOnDoubleClick").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0) out else "Maximize"
    }.getOrDefault("Maximize")

    // the window's place before it was zoomed, for the next double-click to put it back
    private var before: java.awt.Rectangle? = null

    /**
     * Zoomed by its bounds: the screen's room (less the menu bar and the Dock), and back. Not Java's own zoom or
     * minimise (window states): on a Mac those wait on the event thread for the window to get there, while the Mac
     * waits on the same thread to move it, and the app froze (the mini player's minimise, 2026-11-09). "Minimize" set
     * on the Mac does nothing here for that reason; the yellow button still does it.
     */
    fun doubleClicked() {
        val s = Win.state ?: return
        if (s.placement == WindowPlacement.Fullscreen) return
        Thread {
            val a = action()
            javax.swing.SwingUtilities.invokeLater {
                if (a == "None" || a == "Minimize") return@invokeLater
                val w = java.awt.Frame.getFrames().firstOrNull { it.title == "ink music" && it.isDisplayable && it.isVisible && it.width >= 600 } ?: return@invokeLater
                val gc = w.graphicsConfiguration
                val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
                val b = gc.bounds
                val room = java.awt.Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
                val was = before
                if (was != null && w.bounds == room) { w.bounds = was; before = null }
                else { before = w.bounds; w.bounds = room }
            }
        }.start()
    }
}

/** Windows' window manager (DWM), the one call ink needs: a window's title bar dark, and its colours. */
private interface Dwm : com.sun.jna.Library {
    fun DwmSetWindowAttribute(hwnd: com.sun.jna.Pointer, attribute: Int, value: com.sun.jna.ptr.IntByReference, size: Int): Int
}

/**
 * On Windows, the window's title bar black with ink's white words (it was Windows' white): dark mode for it, and on
 * Windows 11 its caption and text colours too. Elsewhere nothing (a Mac's is gone; Linux's follows its theme).
 */
fun darkTitleBar(window: java.awt.Window) {
    if (!Home.windows) return
    runCatching {
        val dwm = com.sun.jna.Native.load("dwmapi", Dwm::class.java)
        val hwnd = com.sun.jna.Native.getWindowPointer(window)
        fun set(attribute: Int, v: Int) = dwm.DwmSetWindowAttribute(hwnd, attribute, com.sun.jna.ptr.IntByReference(v), 4)
        set(20, 1)              // DWMWA_USE_IMMERSIVE_DARK_MODE
        set(35, 0x00000000)     // DWMWA_CAPTION_COLOR: black (COLORREF, 0x00BBGGRR)
        set(36, 0x00ECF0F1)     // DWMWA_TEXT_COLOR: ink's white, #F1F0EC
        set(34, 0x00000000)     // DWMWA_BORDER_COLOR: black
    }
}

/**
 * Linux, before the first window: what a JVM gets wrong there on its own (tried on Fedora with niri, 2026-10-29).
 * Its size: Java takes the screen's scale only from GDK_SCALE, which most desktops don't set, so on a 2x screen
 * everything was half size; the desktop's own Xft.dpi says it (192 is 2x). A tiling desktop that doesn't frame its
 * windows (niri, sway, Hyprland): AWT believes every window is framed, and the page stayed the size it was first
 * drawn in a corner of a bigger window; _JAVA_AWT_WM_NONREPARENTING tells it, set here on its field (the variable
 * can't be set from inside). And its name, the .desktop file's, so the dock and launcher show ink music's icon for it.
 */
fun linuxWindow() {
    if (Home.mac || Home.windows) return
    if (System.getenv("GDK_SCALE") == null && System.getenv("J2D_UISCALE") == null && System.getProperty("sun.java2d.uiScale") == null) {
        val dpi = runCatching {
            ProcessBuilder("xrdb", "-query").redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
                .firstOrNull { it.startsWith("Xft.dpi:") }?.substringAfter(':')?.trim()?.toFloat()
        }.getOrNull()
        if (dpi != null && dpi >= 144) System.setProperty("sun.java2d.uiScale", Math.round(dpi / 96).toString())
    }
    // the desktops whose window managers frame their windows; anything else is taken as one that doesn't
    val framed = listOf("GNOME", "KDE", "XFCE", "CINNAMON", "MATE", "UNITY", "BUDGIE", "LXQT", "LXDE", "PANTHEON", "DEEPIN")
    val desktop = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").uppercase()
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(null, "ink-music-ink_music")
        if (System.getenv("_JAVA_AWT_WM_NONREPARENTING") == null && desktop.isNotEmpty() && framed.none { it in desktop })
            Class.forName("sun.awt.X11.XWM").getDeclaredField("awtWMNonReparenting").apply { isAccessible = true }.setInt(null, 1)
    }
}
