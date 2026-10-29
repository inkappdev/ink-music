package app.ink.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** The window's size and place, kept from one run to the next. */
object Placed {
    private val file = File(Home.dir, "window.json")

    @Composable
    fun remember(): WindowState {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()
        val size = o?.let { DpSize(it.optDouble("w", 1440.0).toFloat().dp, it.optDouble("h", 900.0).toFloat().dp) } ?: DpSize(1440.dp, 900.dp)
        val at = o?.takeIf { it.has("x") }?.let { WindowPosition(it.optDouble("x").toFloat().dp, it.optDouble("y").toFloat().dp) }
            ?: WindowPosition.PlatformDefault
        return rememberWindowState(size = size, position = at)
    }

    /** Written down as it is now (not while it's the whole screen: then as it was before). */
    fun keep(s: WindowState) {
        if (s.placement != WindowPlacement.Floating || s.isMinimized) return
        val p = s.position
        runCatching {
            Home.write(file, JSONObject().put("w", s.size.width.value.toDouble()).put("h", s.size.height.value.toDouble())
                .apply { if (p.isSpecified) put("x", p.x.value.toDouble()).put("y", p.y.value.toDouble()) }.toString())
        }
    }
}

/**
 * The mini player: what's playing in a small window kept over every other (the line along its cover's top, the cover,
 * the song, back / play / next), to be dragged anywhere. The main window goes to the dock meanwhile, back with ↗.
 */
object Mini {
    val on = MutableStateFlow(false)

    fun open() { on.value = true; Win.state?.isMinimized = true }
    fun close() { on.value = false; Win.state?.isMinimized = false }
}

@Composable
fun MiniWindow() {
    val on by Mini.on.collectAsState()
    if (!on) return
    val state = rememberWindowState(size = DpSize(420.dp, 112.dp), position = WindowPosition(Alignment.TopEnd))
    Window(
        onCloseRequest = { Mini.close() }, state = state, title = "ink music", undecorated = true,
        alwaysOnTop = true, resizable = false,
    ) {
        window.background = java.awt.Color.BLACK
        val now by Player.now.collectAsState()
        val n = now
        WindowDraggableArea {
            Box(Modifier.fillMaxSize().background(Ink.black)) {
                if (n == null) {
                    BasicText("NOTHING PLAYING", style = headline(18.sp, Ink.dim), modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
                } else Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(88.dp)) {
                        CoverOf(n.song, n.song.album.ifEmpty { n.song.title }, Modifier.size(88.dp), px = 240, moving = n.playing)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        val (title, artist) = said(n)
                        BasicText(title.uppercase(), style = headline(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Controls(n, Modifier.padding(top = 2.dp), small = true)
                        }
                        // the song's line, filling in its cover's colour
                        Line(n, rememberCover(n.song, 240)?.accent ?: Ink.ink, Modifier.padding(end = 4.dp))
                    }
                    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Pixel(OPEN, "back to the window", Ink.mid) { Mini.close() }
                    }
                }
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

    fun play(paths: List<File>) = scope.launch {
        val songs = paths.flatMap { f ->
            if (f.isDirectory) Library.read(f.walkTopDown().filter { it.isFile }.toList())
                .sortedWith(compareBy({ it.albumId }, { it.disc }, { it.track }, { it.path }))
            else Library.read(listOf(f))
        }
        if (songs.isNotEmpty()) javax.swing.SwingUtilities.invokeLater { Player.play(songs) }
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
                BasicText("LET GO TO PLAY", style = headline(64.sp))
                Spacer(Modifier.height(10.dp))
                BasicText("songs, a record's folder, a stack of them", style = pixel(14.sp, Ink.mid))
            }
        }
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
