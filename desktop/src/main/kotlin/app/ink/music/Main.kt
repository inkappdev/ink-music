package app.ink.music

import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrl
import androidx.compose.ui.input.pointer.isMetaPressed as pointerMeta
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    // Windows: drawn through ANGLE (bundled beside skiko), not Direct3D's own, whose resizing shook (2026-11-09). Where
    // ANGLE won't load skiko falls back by itself; -Dskiko.renderApi still chooses
    if (Home.windows && System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "ANGLE")
    // Apple's catalog (moving covers) wants an Origin header, which the JVM drops unless allowed before any request
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    linuxWindow()
    // whatever stops it, kept in crash.log (Crash)
    Crash.start()
    // ⌘Q (the app menu's Quit, the Dock's): the same whole quit as closing the window
    runCatching { java.awt.Desktop.getDesktop().setQuitHandler { _, response -> runCatching { quit() }; response.performQuit() } }
    // what Windows' refused renames left stranded beside each kept file, put back first (Keep)
    runCatching { Keep.recover(Home.dir); Keep.recover(java.io.File(Home.dir, "podcasts")) }
    Library.start()
    Server.start()
    Covers.warm()
    // the next song's cover (and the last's) ready before it plays
    Covers.ahead()
    // the genres you open most, their editors' songs read ahead (their pages open with their block there)
    Picks.warmGenres(Moods.counts.value.entries.sortedByDescending { it.value }.map { it.key }.take(6))
    Sorts.start()
    Lyrics.start()
    Keys.start()
    LookFile.start()
    ListenBrainz.start(Home.dir)
    LastFm.start(Home.dir)
    Listened.read()
    Loved.start()
    TubeAccount.start()
    // a paired phone kept in step, over the wifi (Sync)
    // podcasts (Pods): the shows followed and where each episode was left, kept here and carried by sync with the phone
    Podcasts.dir = java.io.File(Home.dir, "podcasts")
    SyncHub.start()
    // what plays, on your Discord profile (Discord)
    Discord.start()
    Follows.start()
    Colours.start()
    Player.start()
    // ink's icon on every window Java makes, not Java's own (Duke): Windows' restart screen showed Duke beside ink music
    // (a PC, 2026-10-29); Swing's hidden owner of a window with none (the folder chooser's) and any other
    runCatching {
        val icon = javax.imageio.ImageIO.read(Ink::class.java.getResource("/ink-music.png"))
        javax.swing.JOptionPane.getRootFrame().iconImage = icon
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            (e.source as? java.awt.Window)?.let { if (it.iconImages.isEmpty()) it.iconImages = listOf(icon) }
        }, java.awt.AWTEvent.WINDOW_EVENT_MASK)
    }
    application {
        // an error while a window draws: kept in crash.log before its box is shown (Crash)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides Crash) {
        // closed into the tray (settings, about): hidden, not gone, the music playing on (Tucked)
        val away by Tucked.away.collectAsState()
        Window(
            onCloseRequest = { Tucked.close() },
            visible = !away,
            title = "ink music",
            icon = inkIcon,
            state = Placed.remember().also { Win.state = it },
        ) {
            // never dragged smaller than the rail, a page and the now column
            window.minimumSize = java.awt.Dimension(Placed.LEAST.width.value.toInt(), Placed.LEAST.height.value.toInt())
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(720, 520)
                window.background = java.awt.Color.BLACK
                // a black window: the title bar goes, the content runs under the traffic lights
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                // Windows: its title bar black, not white (and its border, where the bar's ink's own)
                darkTitleBar(window)
            }
            // Windows on JetBrains' Java: no bar of Windows' own, ink's black to the top (InkTitle), the strip as tall
            // as the pages' TOP at ink's size
            val chosen by Size.chosen.collectAsState()
            LaunchedEffect(Size.of(chosen)) { InkTitle.apply(window, Size.of(chosen)) }
            LaunchedEffect(Unit) {
                // back to the front: the music folder read again (a song added meanwhile shows)
                window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = Library.freshen()
                })
            }
            // where the window is, written down as it's moved or sized (and on closing)
            LaunchedEffect(Unit) {
                androidx.compose.runtime.snapshotFlow { Win.state?.let { it.size to it.position } }
                    .collect { kotlinx.coroutines.delay(800); Win.state?.let(Placed::keep) }
            }
            // a click on ink music in the Dock brings the window back while the mini player has it hidden
            LaunchedEffect(Unit) { Mini.dockReopens() }
            // the menus in the Mac's own bar along the top of the screen (elsewhere, the keys alone)
            if (Home.mac) Menus()
            Box(Modifier.fillMaxSize()) {
                Sized { App() }
                // Windows: the strip along the top the window's to drag (InkTitle), over the pages
                TitleStrip(Size.of(chosen))
                // the Mac: a double-click along the top zooms, as a title bar's does (MacTitleStrip)
                MacTitleStrip(Size.of(chosen))
            }
        }
        MiniWindow()
        InkTray()
        }
    }
    // the window's closed: gone, wholly (2026-10-29: after quitting, the Dock still had it "running in the
    // background": a thread the Mac's media buttons had started kept the Java process alive)
    quit()
}

/** Everything let go (the player, Now Playing, the wifi announcements) and the process ended, however it was quit. */
/** ink's icon, for its windows' title bars and the taskbar. */
val inkIcon by lazy {
    androidx.compose.ui.graphics.painter.BitmapPainter(
        org.jetbrains.skia.Image.makeFromEncoded(Ink::class.java.getResourceAsStream("/ink-music.png")!!.readBytes()).toComposeImageBitmap(),
    )
}

fun quit(): Nothing {
    runCatching { Win.state?.let(Placed::keep) }
    runCatching { Player.close() }
    runCatching { NowPlaying.stop() }
    runCatching { SyncHub.close() }
    runCatching { TubeAccount.stop() }
    runCatching { Casting.stopFinding() }
    kotlin.system.exitProcess(0)
}

/** What's over the library: a record, an artist, the player. */
sealed interface Page {
    data class Record(val id: String) : Page
    /** An artist's page: by name (found on YouTube Music), or by their page's id there too. */
    data class Artist(val name: String, val id: String = "") : Page
    data object Find : Page
    data object Playing : Page
    data object Settings : Page
    data object Year : Page
    data class Festival(val name: String) : Page
    data class Mood(val mood: InnerTube.Mood) : Page
    data object Queue : Page
    data object PlayOn : Page
    data class Tube(val album: TubeAlbum) : Page
    data class List(val id: String, val name: String) : Page
    /** A music video, watched. */
    data class Watch(val clip: MusicVideos.Clip, val list: kotlin.collections.List<MusicVideos.Clip> = emptyList()) : Page
    /** A song into one of your playlists. */
    data class AddTo(val song: Song) : Page
    /** A podcast: by its feed (the show as found, shown at once till its feed's read). One page a feed. */
    class Show(val feed: String, val first: Podcasts.Show? = null) : Page {
        override fun equals(other: Any?) = other is Show && other.feed == feed
        override fun hashCode() = feed.hashCode()
    }
}

/** Where you are: the place on the rail, and the pages over it (the menus and keys move them too). */
object Nav {
    val pages = androidx.compose.runtime.mutableStateListOf<Page>()
    var place by mutableStateOf("listen")

    /** A page on top (not twice over itself). */
    fun open(p: Page) { if (pages.lastOrNull() != p) pages += p }
    fun go(to: String) { place = to; pages.clear() }
    fun back() {
        if (pages.isEmpty()) return
        if (pages.last() == Page.Playing) Win.full(false)
        // find closed: what was typed kept among the recent, the field empty for next time
        if (pages.last() == Page.Find) { Finding.keep(Finding.query); Finding.query = "" }
        pages.removeLast()
    }
    fun find() { open(Page.Find); Finding.asked.value++ }

    /**
     * The down arrow: the whole browsing trail closed at once, back to the place it began from (the phone's: an artist,
     * then fans also like, then another, was a close each). Esc and ⌘[ still go back one at a time ([back]).
     */
    fun close() {
        if (Page.Find in pages) { Finding.keep(Finding.query); Finding.query = "" }
        // the player stays if it's what's open under the trail
        val keep = pages.takeWhile { it == Page.Playing }
        pages.clear(); pages.addAll(keep)
    }
    /** The player over the window; `full`, the whole screen. */
    fun player(full: Boolean = false) { PlayerSide.right = false; if (full) Win.full(true); open(Page.Playing) }
}

/**
 * The player opened out of the now column, on the window's right (2026-11-10): it opens out across the page from there
 * and goes back into it, its way back → at the top right and an edge along its right side where the column comes back.
 * Opened anywhere else, it rises from the foot and goes back down. An arrow points where its thing goes.
 */
object PlayerSide { var right by mutableStateOf(false) }

/**
 * The Mac's menu bar: play (the song's controls, volume, lyrics, up next, play on), go (the places, ⌘1 to ⌘8, find,
 * settings, back) and view (the player, the whole screen, the now column tucked). The space bar stays the window's
 * own (a menu's would take it from find's typing).
 */
@Composable
private fun androidx.compose.ui.window.FrameWindowScope.Menus() {
    val now by Player.now.collectAsState()
    val shuffle by Player.shuffle.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val lyrics by Lyrics.on.collectAsState()
    val muted by Player.muted.collectAsState()
    val tucked by Tuck.on.collectAsState()
    fun cmd(k: Key, shift: Boolean = false, alt: Boolean = false) = androidx.compose.ui.input.key.KeyShortcut(k, meta = Home.mac, ctrl = !Home.mac, shift = shift, alt = alt)
    MenuBar {
        Menu("Play") {
            Item(if (now?.playing == true) "Pause" else "Play", enabled = now != null) { Player.toggle() }
            Item("Next", shortcut = cmd(Key.DirectionRight), enabled = now != null) { Player.next() }
            Item("Back", shortcut = cmd(Key.DirectionLeft), enabled = now != null) { Player.previous() }
            Separator()
            CheckboxItem("Shuffle", shuffle, shortcut = cmd(Key.S)) { Player.shuffle() }
            Item(when (repeat) { 1 -> "Repeat: All"; 2 -> "Repeat: One"; else -> "Repeat: Off" }, shortcut = cmd(Key.R)) { Player.repeat() }
            Separator()
            Item("Louder", shortcut = cmd(Key.DirectionUp)) { Player.volume(Player.volume.value + 5) }
            Item("Quieter", shortcut = cmd(Key.DirectionDown)) { Player.volume(Player.volume.value - 5) }
            CheckboxItem("Mute", muted, shortcut = cmd(Key.DirectionDown, alt = true)) { Player.mute() }
            Separator()
            // the lyrics are the player's: on, it opens to them; off, they go
            CheckboxItem("Lyrics", lyrics, shortcut = cmd(Key.L)) { Lyrics.toggle(); if (Lyrics.on.value && now != null) Nav.player() }
            Menu("Sleep") {
                val sleep by Sleep.at.collectAsState()
                CheckboxItem("Off", sleep == 0L) { Sleep.set(null) }
                Sleep.CHOICES.forEach { m -> CheckboxItem("In $m Minutes", false) { Sleep.set(m) } }
                CheckboxItem("At the Song's End", sleep == Sleep.END_OF_SONG) { Sleep.set(0) }
            }
            Item("Up Next", shortcut = cmd(Key.U)) { Nav.open(Page.Queue) }
            Item("Play On", shortcut = cmd(Key.P)) { Nav.open(Page.PlayOn) }
        }
        Menu("Go") {
            PLACES.forEachIndexed { i, p ->
                Item(p.replaceFirstChar { it.uppercase() }, shortcut = cmd(listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)[i])) { Nav.go(p) }
            }
            Separator()
            Item("Find", shortcut = cmd(Key.F)) { Nav.find() }
            Item("Your Year", shortcut = cmd(Key.Y)) { Nav.open(Page.Year) }
            Item("Settings", shortcut = cmd(Key.Comma)) { Nav.open(Page.Settings) }
            Item("Back", shortcut = cmd(Key.LeftBracket)) { Nav.back() }
        }
        Menu("View") {
            Item("Player", shortcut = cmd(Key.Zero), enabled = now != null) { Nav.player() }
            Item("Player, Full Screen", shortcut = cmd(Key.F, shift = true), enabled = now != null) { Nav.player(full = true) }
            Item("Mini Player", shortcut = cmd(Key.M, shift = true)) { Mini.open() }
            CheckboxItem("Now Playing Beside", !tucked, shortcut = cmd(Key.Backslash)) { Tuck.set(!Tuck.on.value) }
            CheckboxItem("Places Beside", !RailFold.on.collectAsState().value, shortcut = cmd(Key.Backslash, shift = true)) { RailFold.set(!RailFold.on.value) }
            Separator()
            Item("Bigger", shortcut = cmd(Key.Equals)) { Size.bigger() }
            Item("Smaller", shortcut = cmd(Key.Minus)) { Size.smaller() }
        }
    }
}

/** Clear of the Mac's traffic lights, and of Windows' buttons (InkTitle): the title bar is gone, its buttons stay. */
val TOP = if (Home.mac) 28.dp else if (InkTitle.on) InkTitle.HEIGHT.dp else 0.dp
val SIDE = 32.dp

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun App() {
    val pages = Nav.pages
    var place by Nav::place
    val focus = remember { FocusRequester() }
    // the keys the window's: given back to it as pages change (find's field had them, and closing it left them nowhere)
    LaunchedEffect(pages.lastOrNull()) { if (pages.lastOrNull() != Page.Find) runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Ink.black).takesDrops().focusRequester(focus).focusable()
            // ctrl (⌘) and the wheel: bigger or smaller, before anything under the mouse scrolls (2026-11-10, after Limusic's)
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.input.pointer.PointerEventPass.Initial) { e: androidx.compose.ui.input.pointer.PointerEvent ->
                val held = if (Home.mac) e.keyboardModifiers.pointerMeta else e.keyboardModifiers.pointerCtrl
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (held && dy != 0f) { if (dy < 0) Size.bigger() else Size.smaller(); e.changes.forEach { it.consume() } }
            }
            .onKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onKeyEvent false
                // a text field has the keys (find, a list's name, a search): space and the arrows are its, not play and
                // seek. A field takes the letter typed, but a key's press comes on up to here: the space between two
                // words of a search paused the music (2026-11-10, a Reddit report: "songs stop playing whenever ... song
                // searches are performed")
                if (Typing.on && !k.isCtrlPressed && !k.isMetaPressed) return@onKeyEvent false
                // ↑ ↑ ↓ ↓ ← → ← → B A: 1-bit mode (the arrows still do what they do)
                val name = when (k.key) { Key.DirectionUp -> "up"; Key.DirectionDown -> "down"; Key.DirectionLeft -> "left"; Key.DirectionRight -> "right"; Key.B -> "b"; Key.A -> "a"; else -> "" }
                if (name.isNotEmpty() && OneBit.key(name)) return@onKeyEvent true
                // Windows and Linux: the Mac menu bar's keys with Ctrl (there's no menu bar there)
                if (!Home.mac && k.isCtrlPressed) {
                    val digits = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)
                    val done = when {
                        k.key in digits -> { PLACES.getOrNull(digits.indexOf(k.key))?.let { Nav.go(it) }; true }
                        k.key == Key.F && k.isShiftPressed -> { if (Player.now.value != null) Nav.player(full = true); true }
                        k.key == Key.F -> { Nav.find(); true }
                        k.key == Key.M && k.isShiftPressed -> { Mini.open(); true }
                        k.key == Key.L -> { Lyrics.toggle(); if (Lyrics.on.value && Player.now.value != null) Nav.player(); true }
                        k.key == Key.U -> { Nav.open(Page.Queue); true }
                        k.key == Key.P -> { Nav.open(Page.PlayOn); true }
                        k.key == Key.S -> { Player.shuffle(); true }
                        k.key == Key.R -> { Player.repeat(); true }
                        k.key == Key.Zero -> { if (Player.now.value != null) Nav.player(); true }
                        k.key == Key.Y -> { Nav.open(Page.Year); true }
                        k.key == Key.Comma -> { Nav.open(Page.Settings); true }
                        k.key == Key.LeftBracket -> { Nav.back(); true }
                        k.key == Key.Backslash && k.isShiftPressed -> { RailFold.set(!RailFold.on.value); true }
                        k.key == Key.Backslash -> { Tuck.set(!Tuck.on.value); true }
                        k.key == Key.Equals || k.key == Key.Plus || k.key == Key.NumPadAdd -> { Size.bigger(); true }
                        k.key == Key.Minus || k.key == Key.NumPadSubtract -> { Size.smaller(); true }
                        k.key == Key.DirectionRight -> { Player.next(); true }
                        k.key == Key.DirectionLeft -> { Player.previous(); true }
                        else -> false
                    }
                    if (done) return@onKeyEvent true
                }
                when {
                    k.key == Key.Spacebar -> { Watching.toggle?.invoke() ?: Player.toggle(); true }
                    // a video open: the arrows move through it, f is full screen, esc leaves it (not the music under it)
                    k.key == Key.DirectionRight && Watching.seekBy != null -> { Watching.seekBy?.invoke(10_000); true }
                    k.key == Key.DirectionLeft && Watching.seekBy != null -> { Watching.seekBy?.invoke(-10_000); true }
                    k.key == Key.F && Watching.setFull != null -> { Watching.setFull?.invoke(!Watching.full.value); true }
                    k.key == Key.DirectionRight -> { Player.seek(Player.position.value + 10_000); true }
                    k.key == Key.DirectionLeft -> { Player.seek((Player.position.value - 10_000).coerceAtLeast(0)); true }
                    k.key == Key.DirectionUp -> { Player.volume(Player.volume.value + 5); true }
                    k.key == Key.DirectionDown -> { Player.volume(Player.volume.value - 5); true }
                    k.key == Key.Escape && Reading.close != null -> { Reading.close?.invoke(); true }
                    k.key == Key.Escape && pages.isNotEmpty() -> { Nav.back(); true }
                    else -> false
                }
            },
    ) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val frame = frameOf(maxWidth)
        val tucked by Tuck.on.collectAsState()
        // nothing playing: no now column, the page has its room (it came with the first thing played)
        val playing by Player.now.collectAsState()
        val trouble by Player.trouble.collectAsState()
        // find takes the now column's room too (its results in two columns), the bar along the foot meanwhile
        val finding = pages.lastOrNull() == Page.Find
        Row(Modifier.fillMaxSize()) {
        val railFolded by RailFold.on.collectAsState()
        // folded in: only the INK wordmark and an arrow by it to bring the rail back out; out, the rail slides open and
        // its words rise in one after another (2026-11-10)
        androidx.compose.animation.AnimatedVisibility(frame != Frame.NARROW && railFolded,
            enter = fadeIn(tween(Motion.ENTER, easing = Motion.expo)), exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
            FoldedRail { place = "ink"; pages.clear() }
        }
        androidx.compose.animation.AnimatedVisibility(frame != Frame.NARROW && !railFolded,
            enter = expandHorizontally(tween(Motion.ENTER, easing = Motion.expo), expandFrom = Alignment.Start) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = shrinkHorizontally(tween(Motion.EXIT, easing = Motion.expo), shrinkTowards = Alignment.Start) + fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
        Rail(place, { place = it; pages.clear() }, { if (pages.lastOrNull() != Page.Find) pages += Page.Find; Finding.asked.value++ },
            { if (pages.lastOrNull() != Page.Settings) pages += Page.Settings })
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // the player's over the whole window: under it, the page it was opened from
                val top = pages.lastOrNull().let { if (it == Page.Playing) pages.dropLast(1).lastOrNull() else it }
                LaunchedEffect(pages.toList()) { PageScroll.stack(pages.toList()) }
                // going forward (a page opened over another, a place chosen) or back, for the rows' cascade (LocalOpened)
                val depth = pages.count { it != Page.Playing }
                val deepest = remember { intArrayOf(depth) }
                val forward = remember(depth, place) { (depth >= deepest[0]).also { deepest[0] = depth } }
                AnimatedContent(
                    top,
                    transitionSpec = {
                        (slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 12 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))) togetherWith
                            fadeOut(tween(Motion.EXIT, easing = Motion.expo))
                    },
                    label = "page",
                ) { page ->
                    // each page's place kept while it's under another, and each of the library's places while another's shown
                    // (the phone's, 2026-11-08: back from a genre's page went to INK's top)
                    val at = if (page == null) -1 else pages.indexOf(page)
                    val opened = remember(page, if (page == null) place else null) { if (forward) System.currentTimeMillis() else 0L }
                    androidx.compose.runtime.CompositionLocalProvider(LocalPageKey provides (if (page == null) "place:$place" else if (at >= 0) "$at:${page.hashCode()}" else null),
                        LocalOpened provides opened) {
                    when (page) {
                        null -> LibraryPage(place, frame == Frame.NARROW, { place = it }) { pages += it }
                        is Page.Record -> RecordPage(page.id, { pages += it }) { Nav.close() }
                        is Page.Artist -> ArtistPage(page.name, page.id, { pages += it }) { Nav.close() }
                        is Page.Watch -> WatchPage(page.clip, page.list, { pages += it }) { Nav.close() }
                        Page.Find -> Column(Modifier.fillMaxSize()) { Close { Nav.close() }; FindTab { pages += it } }
                        Page.Playing -> {}
                        Page.Settings -> SettingsPage { Nav.close() }
                        Page.Year -> YearPage({ pages += it }) { Nav.close() }
                        is Page.Festival -> FestivalPage(page.name, { pages += it }) { Nav.close() }
                        is Page.Mood -> MoodPage(page.mood, { pages += it }) { Nav.close() }
                        Page.Queue -> QueuePage({ pages += it }) { Nav.close() }
                        Page.PlayOn -> PlayOnPage { Nav.close() }
                        is Page.Tube -> TubeRecordPage(page.album, { pages += it }) { Nav.close() }
                        is Page.List -> ListPage(page.id, page.name, { pages += it }) { Nav.close() }
                        is Page.Show -> ShowPage(page.feed, page.first, { pages += it }) { Nav.close() }
                        // done, back where it was added from; closed, only itself (not the trail under it)
                        is Page.AddTo -> AddToPage(page.song, { Nav.back() }) { Nav.back() }
                    }
                    }
                }
            }
            // the bar along the foot where there's no room for the now column: rising a third of itself and in, sinking
            // away out, as the phone's mini player (2026-11-10; it was there or not)
            val remote = remoteNow()
            AnimatedVisibility(
                (frame != Frame.WIDE || tucked || finding || remote != null) && pages.lastOrNull() != Page.Playing,
                enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 3 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
                exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { it / 3 } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
            ) {
                MiniPlayer(
                    if (frame == Frame.WIDE && !finding) ({ Tuck.set(false) }) else null,
                    { if (pages.lastOrNull() != Page.Queue) pages += Page.Queue },
                    { if (pages.lastOrNull() != Page.PlayOn) pages += Page.PlayOn },
                    { name -> pages += Page.Artist(name) },
                ) { if (pages.lastOrNull() != Page.Playing) { PlayerSide.right = false; pages += Page.Playing } }
            }
        }
        // the now column, tucked away to the right (its arrow, or ⌘\): the bar along the foot instead
        AnimatedVisibility(
            frame == Frame.WIDE && !tucked && !finding && pages.lastOrNull() != Page.Playing && (playing != null || trouble != null),
            enter = expandHorizontally(tween(Motion.ENTER, easing = Motion.expo)) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = shrinkHorizontally(tween(Motion.ENTER, easing = Motion.expo)) + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { NowColumn({ Tuck.set(true) }) { if (it == Page.Playing) PlayerSide.right = true; pages += it } }
        }
        // the player: out of the now column, opening out from the right and going back there; else rising from the foot
        // and back down (PlayerSide)
        val right = PlayerSide.right
        AnimatedVisibility(
            pages.lastOrNull() == Page.Playing,
            enter = (if (right) androidx.compose.animation.slideInHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { it / 5 }
                else slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 12 }) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = (if (right) androidx.compose.animation.slideOutHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { it / 5 }
                else slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { it / 16 }) + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) {
            // nothing playing here and the phone is: its playing, the whole window (steered from here, to take over)
            val remote = remoteNow()
            if (remote != null) RemotePlayerPage(remote, { pages += it }) { Win.full(false); pages.remove(Page.Playing) }
            else PlayerPage({ pages += it }) { Win.full(false); pages.remove(Page.Playing) }
        }
        DropHint()
        SizeShown()
      }
    }
}

@Composable
private fun LibraryPage(tab: String, words: Boolean, onPlace: (String) -> Unit, open: (Page) -> Unit) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val read by Library.read.collectAsState()
    // the places' menu, dropped from the place's word (a narrow window's), folded by a place chosen
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(tab, words) { menu = false }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = TOP + if (words) 16.dp else 40.dp)) {
        // the phone's layout (a narrow window, an upright screen): the place you're on as one big word, the rest dropped
        // down from it, as the phone's; find beside it. 2026-11-09: every place as words across the top took two or three
        // lines of an upright window before any music
        if (words) PlaceHead(tab, menu, { menu = !menu }) { open(Page.Find) }
        // beside the rail no name over the page: the rail's lit word says where you are (2026-10-29)
        if (words) Spacer(Modifier.height(14.dp))
        if (tab in setOf("albums", "artists", "songs")) NetworkShut(Modifier.padding(horizontal = SIDE).padding(bottom = 18.dp))
        // the songs page's songs: the library's and every YouTube song you have (liked there, loved here, kept on the
        // computer), each from where it comes (SongsFrom); and what's kept offline, from anywhere
        val liked by TubeAccount.liked.collectAsState()
        val loved by Loved.tube.collectAsState()
        val kept by Offline.kept.collectAsState()
        val keptSongs = remember(kept, songs) { Offline.songs() }
        val pool = remember(songs, liked, loved, keptSongs) {
            val have = songs.mapTo(HashSet()) { it.id }
            (songs + (liked + loved + keptSongs.filter { it.tube }).filter { it.id !in have }.distinctBy { it.id }).sortedBy { Library.sortName(it.title) }
        }
        val counts = remember(pool, kept) {
            pool.groupingBy(SongsFrom::of).eachCount() + (pool.count { it.id in kept }.takeIf { it > 0 }?.let { mapOf("kept" to it) } ?: emptyMap())
        }
        // the genres your songs are in (each artist's Last.fm tags, Genres), most first; tagged as the page is opened
        val tagged by GenrePicks.tagged.collectAsState()
        LaunchedEffect(tab, pool.size) { if (tab == "songs" && pool.isNotEmpty()) GenrePicks.tag(pool.map { it.artist }) }
        val genres = remember(pool, tagged, tab) {
            if (tab != "songs") emptyList()
            else Genres.names.map { g -> g to pool.count { Genres.fits(it.artist, g) } }.filter { it.second > 0 }.sortedByDescending { it.second }
        }
        if (songs.isNotEmpty() && tab in setOf("albums", "artists", "songs")) Row(Modifier.padding(horizontal = SIDE)) {
            SortRow(tab, when (tab) { "albums" -> Sorts.RECORDS; "artists" -> Sorts.ARTISTS; else -> Sorts.SONGS }, Modifier.weight(1f))
            // the songs as a list of words or a grid of covers (2026-11-10, Bronson: "a grid view, album blocks")
            if (tab == "songs") {
                val grid by SongsFrom.grid.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Word("list", pixel(13.sp, if (!grid) Ink.ink else Ink.dim), touch = 8.dp) { SongsFrom.view(false) }
                    Word("grid", pixel(13.sp, if (grid) Ink.ink else Ink.dim), touch = 8.dp) { SongsFrom.view(true) }
                }
            }
        }
        if (tab == "songs" && (counts.size > 1 || genres.isNotEmpty())) FilterRow(counts, genres, Modifier.padding(horizontal = SIDE))
        Spacer(Modifier.height(4.dp))
        if (read && songs.isEmpty() && tab in setOf("albums", "artists", "songs")) {
            Column(Modifier.padding(horizontal = SIDE)) {
                val syncing by Server.syncing.collectAsState()
                BasicText(if (syncing) "READING YOUR SERVER" else "NO MUSIC YET", style = headline(28.sp))
                Spacer(Modifier.height(8.dp))
                if (!syncing) {
                    BasicText("put songs in ${Library.said}, or sign in to your server", style = pixel(13.sp, Ink.mid))
                    Spacer(Modifier.height(16.dp))
                    Word("SIGN IN", headline(28.sp), touch = 8.dp) { open(Page.Settings) }
                }
            }
            return@Column
        }
        // each tab in its own order (Sorting.kt): a to z, by year, lately, most, forgotten, this hour, by color, shuffled
        val chosen by Sorts.chosen.collectAsState()
        val seed by Order.seed.collectAsState()
        val heard by Listened.now.collectAsState()
        val colours by Colours.known.collectAsState()
        val ac = chosen["artists"] ?: Sorts.Chosen()
        val rc = chosen["albums"] ?: Sorts.Chosen()
        val sc = chosen["songs"] ?: Sorts.Chosen()
        val now = remember(chosen, heard) { System.currentTimeMillis() }
        val minute = remember(chosen, heard) { Listening.minuteOfWeek(now) }
        // the color order asks for the colors it doesn't know yet (worked out in the background, kept)
        LaunchedEffect(rc.sort, albums) { if (rc.sort == Sort.COLOUR) Colours.fill(albums) }
        // a new order starts at the top: each list made anew with it
        when (tab) {
            "albums" -> {
                val records = remember(albums, rc, seed, heard, colours) {
                    Sorts.order(albums, rc, seed, { it.id },
                        compareBy<Album>({ Library.sortName(it.artist) }, { it.year }, { Library.sortName(it.title) })) { a ->
                        when (rc.sort) {
                            // a timeline: the oldest first
                            Sort.YEAR -> a.year.takeIf { it > 0 }?.let { -it.toDouble() }
                            // newest in first: a file's own date, a server song's when the server took it in
                            Sort.ADDED -> a.songs.maxOf { it.modified }.takeIf { it > 0 }?.toDouble()
                            Sort.LATELY -> heard.lately("r:" + a.id)
                            Sort.COLOUR -> Colours.place(colours[a.id])
                            else -> null
                        }
                    }
                }
                key(rc, seed) { Records(records, open) }
            }
            "artists" -> {
                val artists = remember(albums, songs, ac, seed, heard) {
                    Sorts.order(artistsOf(albums, songs), ac, seed, { it.name }) { a ->
                        val k = "a:" + a.name.lowercase()
                        when (ac.sort) { Sort.MOST -> heard.most(k); Sort.LATELY -> heard.lately(k); Sort.FORGOTTEN -> heard.forgotten(k, now); else -> null }
                    }
                }
                key(ac, seed) { Artists(artists, open) }
            }
            "radio" -> RadioTab()
            "podcasts" -> PodcastsTab(open)
            "listen" -> ListenPage(open)
            // ink weekly, ink radio and everything people picked: one place (explore was its own till 2026-10-29)
            "ink", "explore" -> ExplorePage(open)
            "lists" -> ListsPage(open)
            else -> {
                val from by SongsFrom.chosen.collectAsState()
                val genre by SongsFrom.genre.collectAsState()
                val every = remember(pool, from, genre, kept, tagged, sc, seed, heard, minute) {
                    val shown = pool.filter { s ->
                        (from == "all" || from !in counts || (if (from == "kept") s.id in kept else SongsFrom.of(s) == from)) &&
                            (genre.isEmpty() || Genres.fits(s.artist, genre))
                    }
                    Sorts.order(shown, sc, seed, { it.id },
                        compareBy<Song>({ Library.sortName(it.albumArtist.ifBlank { it.artist }) }, { it.year }, { Library.sortName(it.album) }, { it.disc }, { it.track })) { s ->
                        when (sc.sort) {
                            Sort.HOUR -> heard.hour(s.id, minute); Sort.LATELY -> heard.lately(s.id); Sort.MOST -> heard.most(s.id)
                            Sort.FORGOTTEN -> heard.forgotten(s.id, now)
                            // a timeline: the oldest first
                            Sort.YEAR -> s.year.takeIf { it > 0 }?.let { -it.toDouble() }
                            // newest in first: a file's own date, a server song's when the server took it in
                            Sort.ADDED -> s.modified.takeIf { it > 0 }?.toDouble()
                            Sort.LENGTH -> s.duration.takeIf { it > 0 }?.toDouble()
                            else -> null
                        }
                    }
                }
                val grid by SongsFrom.grid.collectAsState()
                key(sc, seed, grid) { if (grid) SongGrid(every) else Songs(every, open) }
            }
        }
    }
    if (words) PlaceMenu(tab, menu, Modifier.padding(top = TOP + 16.dp + PLACE_HEAD), { menu = false }, { menu = false; onPlace(it) }) { menu = false; open(it) }
    }
}

/** A narrow window's head: the place's word big, a pixel arrow down after it (its menu), find at the right. */
private val PLACE_HEAD = 64.dp

@Composable
private fun PlaceHead(tab: String, menu: Boolean, onMenu: () -> Unit, onFind: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(PLACE_HEAD).padding(horizontal = SIDE), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onMenu)), verticalAlignment = Alignment.CenterVertically) {
            BasicText(tab.uppercase(), style = headline(44.sp))
            Spacer(Modifier.width(12.dp))
            // down while folded, up while it's dropped
            Pixel(if (menu) PLACES_UP else PLACES_DOWN, "places", Ink.mid, cell = 3.dp, onClick = onMenu)
        }
        Spacer(Modifier.weight(1f))
        Pixel(SEARCH, "find", Ink.mid, cell = 3.dp, onClick = onFind)
    }
}

private val PLACES_DOWN = listOf("#...#", ".#.#.", "..#..")
private val PLACES_UP = listOf("..#..", ".#.#.", "#...#")

/**
 * The places, dropped down under the word (the phone's): big, the one you're on lit, each arriving in turn; your year,
 * settings and made by ink after them. Black over the page, never a frosted panel; a click off the words, or esc, folds
 * it away.
 */
@Composable
private fun PlaceMenu(tab: String, shown: Boolean, modifier: Modifier, onFold: () -> Unit, onPlace: (String) -> Unit, open: (Page) -> Unit) {
    AnimatedVisibility(
        shown, modifier = modifier,
        enter = androidx.compose.animation.expandVertically(tween(Motion.ENTER, easing = Motion.expo), expandFrom = Alignment.Top) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
        exit = androidx.compose.animation.shrinkVertically(tween(Motion.EXIT, easing = Motion.expo), shrinkTowards = Alignment.Top) + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
    ) {
        // the keys to it while it's down: esc folds it
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier.fillMaxSize().background(Ink.black)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onFold)
                .focusRequester(focus).focusable()
                .onKeyEvent { k -> if (k.type == KeyEventType.KeyDown && k.key == Key.Escape) { onFold(); true } else false }
                .padding(start = SIDE, end = SIDE, top = 8.dp),
        ) {
            PLACES.forEachIndexed { i, t ->
                MenuPlace(i, shown) { Word(t.uppercase(), headline(40.sp, if (t == tab) Ink.ink else Ink.mid), touch = 4.dp) { onPlace(t) } }
            }
            Spacer(Modifier.height(24.dp))
            MenuPlace(PLACES.size, shown) { Word("your year", pixel(14.sp, Ink.mid), touch = 8.dp) { open(Page.Year) } }
            MenuPlace(PLACES.size + 1, shown) { Word("settings", pixel(14.sp, Ink.mid), touch = 8.dp) { open(Page.Settings) } }
            // made by ink, its heart (the rail's, which a narrow window hasn't): the quiet funnel
            MenuPlace(PLACES.size + 2, shown) {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("made by ", style = pixel(12.sp, Ink.dim))
                    Word("ink", pixel(12.sp, Ink.mid)) { browse("https://inkhome.app") }
                    Spacer(Modifier.width(6.dp))
                    InkHeart(Ink.dim)
                }
            }
        }
    }
}

/**
 * A word of the places' menu, arriving after the one before it, rising as it fades in: ink's own arrive, 50 ms apart
 * and 20 dp, as the phone's (2026-11-10; it was half that here).
 */
@Composable
private fun MenuPlace(place: Int, shown: Boolean, content: @Composable () -> Unit) {
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) { a.snapTo(0f); kotlinx.coroutines.delay(Motion.STAGGER.toLong() * place); a.animateTo(1f, tween(Motion.ENTER, easing = Motion.expo)) }
    }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * Motion.drift.toPx() }) { content() }
}

@Composable
private fun Records(albums: List<Album>, open: (Page) -> Unit, header: (@Composable () -> Unit)? = null) {
    val grid = pageGrid()
    LazyVerticalGrid(
        GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
        contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (header != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
        items(albums, key = { it.id }) { a -> RecordTile(a, Modifier) { open(Page.Record(a.id)) } }
    }
}

/** An artist, with how much of theirs is here. */
data class ArtistEntry(val name: String, val records: Int, val songs: Int)

fun artistsOf(albums: List<Album>, songs: List<Song>): List<ArtistEntry> {
    val records = albums.groupingBy { it.artist }.eachCount()
    return songs.groupBy { it.albumArtist }.map { (name, s) -> ArtistEntry(name, records[name] ?: 0, s.size) }
        .sortedBy { Library.sortName(it.name) }
}

@Composable
private fun Artists(artists: List<ArtistEntry>, open: (Page) -> Unit) {
    // a grid of faces, as the records are (2026-10-29: one thin list down a wide page)
    val albums by Library.albums.collectAsState()
    val covers = remember(albums) { albums.groupBy { it.artist }.mapValues { it.value.first().songs.first() } }
    val grid = pageGrid()
    LazyVerticalGrid(
        GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
        contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(artists, key = { it.name }) { a -> ArtistTile(a, covers[a.name]) { open(Page.Artist(a.name)) } }
    }
}

/**
 * An artist as a tile: their photo from YouTube Music (asked once it's been in sight a moment, so a fast scroll asks for
 * none; kept after), their record's cover till then; their name, how much of theirs is here.
 */
@Composable
private fun ArtistTile(a: ArtistEntry, record: Song?, onClick: () -> Unit) {
    val photo by androidx.compose.runtime.produceState<String?>(null, a.name) {
        kotlinx.coroutines.delay(500)
        Artists.of(a.name).collect { i -> i?.photo?.let { value = it } }
    }
    Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        val face = remember(photo, record) { photo?.let { faceSong(a.name, it, photo = true) } ?: record }
        CoverOf(face, a.name, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        Spacer(Modifier.height(8.dp))
        UnderCover(a.name, listOfNotNull(a.records.takeIf { it > 0 }?.let { "$it ${if (it == 1) "record" else "records"}" },
            "${a.songs} ${if (a.songs == 1) "song" else "songs"}").joinToString("  ·  "))
    }
}

@Composable
fun ArtistRow(a: ArtistEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp)) {
        BasicText(a.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(
            // none of theirs here: one YouTube Music has (find)
            if (a.records == 0 && a.songs == 0) "on youtube music"
            else listOfNotNull(a.records.takeIf { it > 0 }?.let { "$it ${if (it == 1) "record" else "records"}" }, "${a.songs} ${if (a.songs == 1) "song" else "songs"}").joinToString("   "),
            style = pixel(12.sp, Ink.mid),
        )
    }
}

/** The songs as a grid of their records' covers, the records' own sizes; the one playing lit under it; a click plays from there. */
@Composable
private fun SongGrid(songs: List<Song>) {
    val grid = pageGrid("songs")
    val now by Player.now.collectAsState()
    LazyVerticalGrid(
        GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
        contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(songs, i) })) {
                CoverOf(s, s.album.ifEmpty { s.title }, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
                Spacer(Modifier.height(8.dp))
                BasicText(s.title.uppercase(), style = headline(14.sp, if (now?.song?.id == s.id) Ink.ink else Ink.ink.copy(alpha = .9f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(s.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Songs(songs: List<Song>, open: (Page) -> Unit) {
    val list = pageList()
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp)) { cascading {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, said = "${s.artist}  ·  ${s.album}".lowercase(), open = open) { Player.play(songs, i) } }
    }}
}

/** A song as words: its title, and under it who and where; lit while it plays. A click plays it, in its list. */
@Composable
fun SongRow(
    s: Song, number: String? = null, said: String? = null, open: ((Page) -> Unit)? = null,
    lit: Boolean? = null, dim: Boolean = false, onRemove: (() -> Unit)? = null, cover: Boolean = false,
    glow: Color? = null, onClick: () -> Unit,
) {
    val now by Player.now.collectAsState()
    val on = lit ?: (now?.song?.id == s.id)
    var options by remember { mutableStateOf(false) }
    // where the right click was, in the row: the menu opens from there
    var clickAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    val color = (if (on || options) Ink.ink else if (dim) Ink.dim else Ink.mid).let { if (over) brighter(it) else it }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().hoverable(src).clickable(src, indication = null, onClick = felt { options = false; onClick() })
                // a right click (the phone's hold): its options in a menu where it was clicked
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                clickAt = e.changes.first().position
                                options = true; e.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // its record's cover beside it, small and square (a list's covers word)
            if (cover) { CoverOf(s, s.album.ifEmpty { s.title }, Modifier.size(44.dp), px = 160); Spacer(Modifier.width(14.dp)) }
            if (number != null) BasicText(number, style = pixel(12.sp, color), modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                // `glow` (the queue's lists): the song playing, its name the progress line, filling from the left in
                // its cover's colour as it plays (2026-11-09; a wash behind the row was tried first, too much of a box)
                if (on && glow != null && now?.song?.id == s.id && (now?.duration ?: 0) > 0) PlayingTitle(s.title.uppercase(), color, glow)
                else BasicText(s.title.uppercase(), style = headline(17.sp, color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (said != null) BasicText(said, style = pixel(12.sp, if (dim) Ink.dim else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // a length not known (YouTube's top songs come without one): none said
            if (s.duration > 0) BasicText(clock(s.duration), style = pixel(12.sp, if (dim) Ink.dim else Ink.mid), modifier = Modifier.padding(start = 16.dp))
        }
        // its options, in a menu where it was clicked (2026-11-09: they were a row of nine words under the song, too
        // much to read, wrapping in narrow places and pushing the list down)
        if (options) SongMenu(s, open, onRemove, clickAt, playing = on) { options = false }
    }
}

/**
 * A song's options, a menu at the right click: on black, a hairline round it, the words one under another in three
 * kinds (what plays, what's kept, where to go; remove last and dim). It rises a little out of the click as it fades in,
 * its words following one after another; away by a click anywhere else, esc, or a word chosen. Kept inside the window.
 */
@Composable
private fun SongMenu(s: Song, open: ((Page) -> Unit)?, onRemove: (() -> Unit)?, at: androidx.compose.ui.geometry.Offset, playing: Boolean, onClose: () -> Unit) {
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    // the close plays out (a fade) before the menu goes; what was chosen is done as it starts
    fun close(then: () -> Unit = {}) { then(); shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState) onClose() }
    val loved by Loved.ids.collectAsState()
    val kept by Offline.kept.collectAsState()
    // `playing`, the row lit as the one playing (a queue's own row, not every copy of the song in it): "play next" and
    // "add to queue" say nothing about it, so they're not there
    val go = open ?: Nav::open
    // copy link: its words say how it's going, and the menu stays till it's on the clipboard (a short link takes a
    // moment: Apple's and Deezer's ids found)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var copying by remember { mutableStateOf<String?>(null) }
    val file = remember(s.id) { SongLink.file(s) }
    val groups = listOf(
        listOfNotNull(
            ("play next" to { Player.playNext(s) }).takeIf { !playing },
            ("add to queue" to { Player.add(s) }).takeIf { !playing },
            // radio: this song, then more like it as the queue runs out
            ("radio" to { Player.startRadio(s) }).takeIf { !s.station && !s.episode },
        ),
        listOfNotNull(
            ((if (s.id in loved) "unlove" else "love") to { Loved.toggle(s) }).takeIf { !s.station && !s.episode },
            // into one of your playlists (the server's, YouTube Music's)
            ("add to a playlist" to { go(Page.AddTo(s)) }).takeIf { canAddToList(s) },
            // kept on the computer, to play with no signal (your server's, YouTube's)
            (if (s.id in kept) "not offline" to { Offline.remove(listOf(s.id)) } else "keep offline" to { Offline.keep(listOf(s)) }).takeIf { Offline.keepable(s) },
        ),
        listOfNotNull(
            // an episode's show where a song's record and artist are
            Pods.page(s)?.let { p -> "show" to { go(p) } }?.takeIf { open != null && s.episode },
            ("record" to { go(Page.Record(s.albumId)) }).takeIf { open != null && s.album.isNotEmpty() && !s.episode },
            ("artist" to { go(Page.Artist(s.albumArtist)) }).takeIf { open != null && !s.episode },
            // a file of yours: shown where it is (Explorer, the Finder)
            file?.let { f -> (if (Home.mac) "show in finder" else "show in folder") to { SongLink.reveal(f) } },
        ),
    ).filter { it.isNotEmpty() }
    // copy link, after the keeping kind (its own kind where that's empty): a song with a link (not a station's, an episode's)
    val share = (copying ?: "copy link").takeIf { SongLink.long(s) != null }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
                val x = anchorBounds.left + at.x.toInt() + gap
                val y = anchorBounds.top + at.y.toInt() + gap
                // kept inside the window: up from the click where it would go past the foot, left where past the side
                val fx = if (x + popupContentSize.width > windowSize.width - gap) (x - popupContentSize.width - gap * 2) else x
                val fy = if (y + popupContentSize.height > windowSize.height - gap) (y - popupContentSize.height - gap * 2) else y
                return androidx.compose.ui.unit.IntOffset(fx.coerceAtLeast(gap), fy.coerceAtLeast(gap))
            }
        },
        onDismissRequest = { close() },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        onKeyEvent = { k -> if (k.type == KeyEventType.KeyDown && k.key == Key.Escape) { close(); true } else false },
    ) {
        androidx.compose.animation.AnimatedVisibility(
            shown,
            enter = fadeIn(tween(Motion.ENTER, easing = Motion.expo)) + slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { -it / 14 } +
                androidx.compose.animation.scaleIn(tween(Motion.ENTER, easing = Motion.expo), initialScale = .96f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)),
            exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) {
            // as wide as its longest word (a popup's given the whole window to fill), the hairlines across that
            Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 180.dp)
                .background(Ink.black).border(1.dp, Ink.line).padding(horizontal = 18.dp, vertical = 12.dp)) {
                // the song's cover colour (its progress line's), what a word lights in under the mouse
                val accent = rememberCover(s, 300)?.accent ?: Ink.ink
                var n = 0
                groups.forEachIndexed { g, words ->
                    if (g > 0) Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Ink.line))
                    words.forEach { (word, act) -> MenuWord(word, Ink.ink, accent, n++) { close(act) } }
                    // copy link at the end of what's kept and sent (the second kind)
                    if (share != null && words.any { it.first in setOf("love", "unlove", "add to a playlist", "keep offline", "not offline") }) {
                        MenuWord(share, if (copying == null) Ink.ink else Ink.mid, accent, n++) {
                            if (copying == null) scope.launch {
                                copying = "finding the link"
                                copying = if (SongLink.copy(s)) "link copied" else "no link found"
                                kotlinx.coroutines.delay(700)
                                close()
                            }
                        }
                    }
                }
                if (onRemove != null) {
                    Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Ink.line))
                    MenuWord("remove", Ink.mid, accent, n) { close(onRemove) }
                }
            }
        }
    }
}

/**
 * The bar's "more": a menu as a song's right click is (black, a hairline round it, its words following one another),
 * rising out of the word, over it, the bar being at the window's foot. Away by a click anywhere else, esc, or a word
 * chosen. The last kind is dim (stop, as remove is in a song's).
 */
@Composable
private fun BarMenu(groups: List<List<Pair<String, () -> Unit>>>, accent: Color, onClose: () -> Unit) {
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    fun close(then: () -> Unit = {}) { then(); shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState) onClose() }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
                // over the word, its right edge at the word's (kept inside the window)
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
                val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(gap)
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        },
        onDismissRequest = { close() },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        onKeyEvent = { k -> if (k.type == KeyEventType.KeyDown && k.key == Key.Escape) { close(); true } else false },
    ) {
        androidx.compose.animation.AnimatedVisibility(
            shown,
            enter = fadeIn(tween(Motion.ENTER, easing = Motion.expo)) + slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 14 } +
                androidx.compose.animation.scaleIn(tween(Motion.ENTER, easing = Motion.expo), initialScale = .96f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
            exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) {
            Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 160.dp)
                .background(Ink.black).border(1.dp, Ink.line).padding(horizontal = 18.dp, vertical = 12.dp)) {
                var n = 0
                groups.forEachIndexed { g, words ->
                    if (g > 0) Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Ink.line))
                    val last = g == groups.lastIndex && g > 0
                    words.forEach { (word, act) -> MenuWord(word, if (last) Ink.mid else Ink.ink, accent, n++) { close(act) } }
                }
            }
        }
    }
}

/**
 * A word in a menu, coming in after the one before it (ink's stagger). Under the mouse it takes on the song's cover
 * colour, gently (2026-11-09), and dims a moment while pressed.
 */
@Composable
private fun MenuWord(word: String, color: Color, accent: Color, place: Int, onClick: () -> Unit) {
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(Motion.STAGGER / 2L * place); alpha.animateTo(1f, tween(Motion.ENTER, easing = Motion.expo)) }
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val c by androidx.compose.animation.animateColorAsState(if (pressed) Ink.mid else if (over) accent else color,
        tween(Motion.ENTER, easing = Motion.expo), label = "menu word")
    BasicText(
        word, style = pixel(13.sp, c), maxLines = 1,
        modifier = Modifier.graphicsLayer { this.alpha = alpha.value; translationY = (1f - alpha.value) * 6.dp.toPx() }.fillMaxWidth()
            .hoverable(src).clickable(src, indication = null, onClick = felt(then = onClick)).padding(vertical = 6.dp, horizontal = 3.dp),
    )
}

/**
 * The playing song's name as its progress: the letters in `base`, and over them the same letters in the cover's colour
 * cut off where the song's at, moving on smoothly between the player's fifths of a second.
 */
@Composable
private fun PlayingTitle(title: String, base: Color, accent: Color) {
    val now by Player.now.collectAsState()
    val pos by Player.position.collectAsState()
    val len = now?.duration ?: 0L
    val target = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f
    val k by androidx.compose.animation.core.animateFloatAsState(target, tween(450, easing = androidx.compose.animation.core.LinearEasing), label = "filled")
    val c by androidx.compose.animation.animateColorAsState(accent, tween(Motion.ENTER * 2, easing = Motion.expo), label = "fill")
    Box {
        BasicText(title, style = headline(17.sp, base), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(title, style = headline(17.sp, c), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.drawWithContent { clipRect(right = size.width * k) { this@drawWithContent.drawContent() } })
    }
}

/**
 * A tile's words under its cover: the title (two lines at most) and a word under it, given a two-line
 * title's room whatever this one's is, so a row of covers keeps one height.
 */
@Composable
fun UnderCover(title: String, said: String) {
    Box {
        Column(Modifier.alpha(0f)) {
            BasicText("I\nI", style = headline(15.sp))
            Spacer(Modifier.height(3.dp))
            BasicText("i", style = pixel(12.sp))
        }
        Column {
            BasicText(title.uppercase(), style = headline(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            BasicText(said.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun RecordTile(a: Album, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(a.songs.first(), a.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        Spacer(Modifier.height(8.dp))
        UnderCover(a.title, a.artist)
    }
}

@Composable
fun CoverOf(song: Song?, name: String, modifier: Modifier, px: Int = 640, moving: Boolean = false) {
    // a station: its own sleeve (its logo small and every shape; a word works)
    if (song?.station == true) return StationCover(song, modifier, moving)
    // 1-bit mode (the Konami code): every cover in the dither, its own colour
    if (OneBit.on.collectAsState().value && song != null && rememberCover(song, px) != null) return Dithered(song, modifier, px)
    val cover = rememberCover(song, px)
    // as the phone's (2026-11-10, Bronson: "albums and photos have a nice fade in animation when they load... match"): black
    // while it comes, then the cover develops in from black over 600 ms, as a print does; its name as a cover only after a
    // second and a half with nothing (a record that has none), so a cover that's merely on its way never flashes the name
    // card first. One already in memory (scrolled back to) is there at once. (300 ms here on 2026-11-09, back to the phone's.)
    val there = androidx.compose.runtime.remember(song?.albumId) { cover != null }
    val shown = androidx.compose.runtime.remember(song?.albumId) { androidx.compose.animation.core.Animatable(if (there) 1f else 0f) }
    val late by androidx.compose.runtime.produceState(false, song?.albumId) { kotlinx.coroutines.delay(1_500); value = true }
    androidx.compose.runtime.LaunchedEffect(cover != null) {
        if (cover != null && shown.value < 1f) shown.animateTo(1f, androidx.compose.animation.core.tween(DEVELOP, easing = Motion.expo))
    }
    BoxWithConstraints(modifier) {
        if (cover == null && (late || song == null)) NameCover(name, maxWidth)
        if (cover != null) Image(cover.image, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }, contentScale = ContentScale.Crop)
    }
}

/** How long a cover takes to develop in from black, the phone's own. */
private const val DEVELOP = 600

/** A song's words as shown: a station's, what it says it's playing (its own name and city with nothing said). */
@Composable
fun said(n: Player.Now): Pair<String, String> {
    val air by Player.onAir.collectAsState()
    val s = n.song
    val a = air
    return if (s.station && a != null) a.title to (a.artist ?: s.title) else s.title to s.artist
}

/**
 * Close: with a page under this one (an artist, then one their fans also like), back at the top left, a page at a time;
 * the down arrow, as the phone's, at the top right: the whole trail (2026-11-09: the two side by side looked and worked
 * oddly, two ways out in one place; 2026-11-07: only the arrow, and it went all the way out; esc and ⌘[ did, unseen).
 */
@Composable
fun Close(onClose: () -> Unit) {
    val under = Nav.pages.count { it != Page.Playing } > 1
    Row(Modifier.fillMaxWidth().padding(start = SIDE - 8.dp, end = SIDE - 8.dp, top = TOP).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        if (under) Pixel(BACK_ARROW, "back", Ink.mid, Modifier.wrapContentHeight(unbounded = true)) { Nav.back() }
        Spacer(Modifier.weight(1f))
        CloseArrow(onClick = onClose)
    }
}

/**
 * What critics said of a record, under its cover (2026-11-09: "reviews from critics? cool little editorial snippets in
 * nice font?"): each publication that picked it (Picks.said), its first sentence as the record of the day says one,
 * big and in quotes, who said it under it and "read it" to their page. Two at most: the column doesn't scroll. Nothing
 * where no critic picked it.
 */
@Composable
fun Critics(title: String, artist: String, most: Int = 3, across: Boolean = false) {
    val all by Picks.said.collectAsState()
    val said = remember(all, title, artist) { Curation.saidOf(all, title, artist).take(most) }
    if (said.isEmpty()) return
    @Composable
    fun Quote(p: Curation.Pick, modifier: Modifier) = Column(modifier) {
        BasicText("\u201c${p.said}\u201d".uppercase(), style = headline(if (across) 19.sp else 17.sp).copy(lineHeight = if (across) 24.sp else 22.sp), maxLines = 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = READ))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("\u2014 ${p.from.lowercase()}", style = pixel(12.sp, Ink.mid))
            if (p.link.isNotBlank()) Word("read it", pixel(12.sp, Ink.mid), Modifier.padding(start = 16.dp), touch = 8.dp) { browse(p.link) }
        }
    }
    // under a record's songs: a hairline, then the quotes side by side, each its share of the width
    if (across) Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) { said.forEach { Quote(it, Modifier.weight(1f)) } }
    } else Column(Modifier.padding(top = 10.dp)) {
        said.forEach { p -> Spacer(Modifier.height(20.dp)); Quote(p, Modifier) }
    }
}

@Composable
private fun RecordPage(id: String, open: (Page) -> Unit, onClose: () -> Unit) {
    val albums by Library.albums.collectAsState()
    val a = albums.firstOrNull { it.id == id } ?: return Close(onClose)
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 16.dp)) {
        // a narrow window (an upright screen): the record over its songs, all one list, not a 320 column beside a
        // squeezed one (2026-11-09)
        val narrow = maxWidth < 760.dp
        val cover = if (narrow) minOf(maxWidth, 360.dp) else 320.dp
        val head = @Composable {
            Column(Modifier.width(cover)) {
                CoverOf(a.songs.first(), a.title, Modifier.size(cover))
                Spacer(Modifier.height(16.dp))
                BasicText(a.title.uppercase(), style = headline(30.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Word(a.artist.lowercase(), pixel(14.sp)) { open(Page.Artist(a.artist)) }
                Spacer(Modifier.height(4.dp))
                BasicText(
                    listOfNotNull(a.year.takeIf { it > 0 }?.toString(), "${a.songs.size} ${if (a.songs.size == 1) "song" else "songs"}", clock(a.length),
                        a.songs.first().quality.takeIf { it.isNotEmpty() }).joinToString("  ·  "),
                    style = pixel(12.sp, Ink.mid),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Word("play", pixel(14.sp), touch = 8.dp) { Player.play(a.songs) }
                    KeepOffline(a.songs)
                }
            }
        }
        val list = pageList()
        val discs = a.songs.map { it.disc }.distinct().size > 1
        val songs: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            itemsIndexed(a.songs, key = { _, s -> s.id }) { i, s ->
                val n = if (s.track > 0) (if (discs) "${s.disc}.${s.track}" else "${s.track}") else "${i + 1}"
                SongRow(s, number = n, said = s.artist.takeIf { it != a.artist }?.lowercase(), open = open) { Player.play(a.songs, i) }
            }
            item { Around(a, albums, open) }
        }
        if (narrow) LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(bottom = 32.dp)) { cascading {
            item(key = "head") { Column { head(); Spacer(Modifier.height(24.dp)) } }
            songs()
            item(key = "said") { Critics(a.title, a.artist) }
        }} else Row(Modifier.fillMaxSize()) {
            head()
            Spacer(Modifier.width(40.dp))
            // the songs in their own box, never past the quotes under them (2026-11-09: beside the cover they were crowded)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(Modifier.weight(1f, fill = false).feather(list), list, contentPadding = PaddingValues(bottom = 16.dp)) { songs() }
                Critics(a.title, a.artist, across = true)
            }
        }
        }
    }
}

/**
 * Why nothing can play; on Linux with no libmpv, the line that installs it on this system, to copy into a
 * terminal, and try again once it's in (no restart).
 */
@Composable
internal fun NoSound(trouble: String, modifier: Modifier = Modifier) {
    val missing by Player.missing.collectAsState()
    var copied by remember { mutableStateOf(false) }
    Column(modifier) {
        BasicText(trouble, style = pixel(12.sp, Ink.mid))
        val line = missing ?: return@Column
        Spacer(Modifier.height(8.dp))
        BasicText(line, style = pixel(12.sp))
        Spacer(Modifier.height(8.dp))
        Row {
            if (line.startsWith("sudo ")) {
                Word(if (copied) "copied" else "copy", pixel(12.sp, if (copied) Ink.mid else Ink.ink)) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(line), null)
                    copied = true
                }
                Spacer(Modifier.width(24.dp))
            }
            Word("try again", pixel(12.sp)) { Player.retry() }
        }
    }
}

/**
 * What's playing, along the foot of the window: the line along the cover's top edge filling with the song in its
 * colour, its times at its two ends and the beat in the middle over it (as the player's and the phone's line); the
 * cover, the song, back / play / next and the heart. A click opens the player. The beat and the times were in the row
 * for an evening (2026-11-09), and it got crowded: they're the line's, so on the line.
 */
@Composable
private fun MiniPlayer(onUntuck: (() -> Unit)?, onQueue: () -> Unit, onPlayOn: () -> Unit, onArtist: (String) -> Unit, onOpen: () -> Unit) {
    val now by Player.now.collectAsState()
    val trouble by Player.trouble.collectAsState()
    trouble?.let {
        NoSound(it, Modifier.padding(horizontal = SIDE, vertical = 14.dp))
        return
    }
    // nothing playing here and the phone is: its playing, steered from here, to take over (Sync)
    remoteNow()?.let { return RemoteBar(it, onArtist, onOpen) }
    val n = now ?: return
    val cover = rememberCover(n.song)
    val side by Bar.side.collectAsState()
    // the beat over the line where the window's wide enough for its four squares and tempo between the times
    var beatOn by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().background(Ink.black).padding(start = SIDE, end = SIDE, bottom = 14.dp)) {
        // the bar's top edge over the line's reach: dragged up, the bar taller and its cover bigger (BarEdge)
        Box(Modifier.fillMaxWidth()) {
            SeekLine(n, cover?.accent ?: Ink.ink, beat = beatOn, ends = true)
            BarEdge(Modifier.align(Alignment.TopStart))
        }
        // a narrow window (an upright screen, or the rail beside the bar): the song keeps its room, and what the player
        // has too goes as the bar narrows: the beat first, then the volume, the times, the words; the cover, the song,
        // back / play / next and the heart always, as the phone's bar (2026-11-09: all of it was kept whatever the room,
        // and under 700 across the cover and the song had none)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        beatOn = maxWidth >= BAR_BEAT
        val volume = maxWidth >= BAR_VOLUME
        val words = maxWidth >= BAR_WORDS
        // nothing behind it: the artist's name big and faint, as the mini player has, made the small words and the
        // controls over it hard to read (tried and taken out the same day, 2026-11-09)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onOpen)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Opens(Modifier.size(side), onOpen) { CoverOf(n.song, n.song.album.ifEmpty { n.song.title }, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val (title, artist) = said(n)
                    // a station's song, as it says it: its title opens find with it typed, its artist their page (OnAirSong)
                    val aired = rememberAired(n)
                    // the song's name a little bigger with the cover: 20 at the bar's least, 28 at its most
                    val grown = (side - Bar.LEAST) / (Bar.MOST - Bar.LEAST)
                    if (aired != null) Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                        onClick = felt { OnAirSong.find(aired.title, aired.artist!!) })) { WholeWords(title.uppercase(), headline((20 + 8 * grown).sp), 2) }
                    else WholeWords(title.uppercase(), headline((20 + 8 * grown).sp), 2)
                    Spacer(Modifier.height(4.dp))
                    val on by Casting.on.collectAsState()
                    Row {
                        Word(artist.lowercase(), pixel(12.sp, Ink.mid)) {
                            // an episode's show; a song's artist; the artist a station says it's playing
                            if (n.song.episode) Pods.page(n.song)?.let { Nav.open(it) }
                            else if (aired != null) onArtist(aired.artist!!.substringBefore(", "))
                            else if (!n.song.station) onArtist(n.song.albumArtist.ifBlank { n.song.artist.substringBefore(", ") })
                        }
                        on?.let { BasicText("  on ${it.name.lowercase()}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            Controls(n, small = true)
            // a station's: the song it's playing, found, to love (the heart) or put in a list (add)
            val airedSong = rememberAiredSong(n)
            if (airedSong != null) {
                Heart(airedSong, 2.dp, Modifier.padding(start = 10.dp))
                Word("add", pixel(14.sp, Ink.mid), Modifier.padding(start = 10.dp), touch = 10.dp) { Nav.open(Page.AddTo(airedSong)) }
            } else Heart(n.song, 2.dp, Modifier.padding(start = 10.dp))
            if (volume) { Spacer(Modifier.width(12.dp)); Volume(line = 80.dp) }
            if (words) {
                Word("up next", pixel(14.sp, Ink.mid), Modifier.padding(start = 12.dp), touch = 10.dp, onClick = onQueue)
                // the rest in one word (2026-11-09: stop, play on and the mini player and full screen pixels made the bar
                // crowded): its menu over it, as a song's right click is. Lit while on a speaker, as play on was
                val on by Casting.on.collectAsState()
                var more by remember { mutableStateOf(false) }
                Box {
                    Word("more", pixel(14.sp, if (on != null || more) Ink.ink else Ink.mid), Modifier.padding(start = 12.dp), touch = 10.dp) { more = !more }
                    if (more) BarMenu(
                        listOf(
                            listOf(
                                // where it plays, the speaker's name while on one
                                (on?.let { "play on ${it.name.lowercase()}" } ?: "play on") to onPlayOn,
                                // the mini player, over every window
                                "mini player" to { Mini.open() },
                                "full screen" to { Win.full(true); onOpen() },
                            ),
                            // sleep: set, or how long's left and off
                            if (Sleep.at.value == 0L) Sleep.CHOICES.filter { it in setOf(15, 30, 60) }.map { m -> "sleep in $m min" to { Sleep.set(m) } } +
                                listOf("sleep at the song's end" to { Sleep.set(0) })
                            else listOf("${Sleep.said()} · off" to { Sleep.set(null) }),
                            // stop: the music over, the queue let go, this bar gone (the phone's, 2026-11-08)
                            listOf("stop" to { Player.stop() }),
                        ),
                        cover?.accent ?: Ink.ink,
                    ) { more = false }
                }
            }
            // the now column, brought back out
            onUntuck?.let { Pixel(UNTUCK, "up beside the page", Ink.mid, onClick = it) }
        }
        }
    }
}

/**
 * How tall the bar along the foot is, as its cover's side: dragged by the bar's top edge and kept, as the now column's
 * width is (2026-11-09: "drag it up and the album art gets bigger"). Never under the bar as it was, nor past [MOST]:
 * about a cover's size on a shelf, so the page above keeps the window.
 */
object Bar {
    val LEAST = 72.dp
    val MOST = 168.dp
    private val file = java.io.File(Home.dir, "bar-height")
    val side = kotlinx.coroutines.flow.MutableStateFlow(
        (runCatching { file.readText().trim().toFloat() }.getOrNull()?.takeIf { it.isFinite() }?.dp ?: LEAST).coerceIn(LEAST, MOST),
    )
    fun keep() { runCatching { file.writeText(side.value.value.toString()) } }
}

/**
 * The bar's top edge, to drag: a strip over the top of the line's reach (the line keeps the rest, nearer itself), the
 * up-and-down arrow over it and a hairline while the mouse is on it or it's held, as the now column's edge has. 14 of
 * the reach's 24: a press a little low for the edge takes the edge, never the song somewhere else (it was 8, the two
 * touching, 2026-11-09).
 */
@Composable
private fun BarEdge(modifier: Modifier = Modifier) {
    val edge = remember { MutableInteractionSource() }
    val over by edge.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        modifier.fillMaxWidth().height(14.dp).hoverable(edge)
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true }, onDragEnd = { dragging = false; Bar.keep() }, onDragCancel = { dragging = false },
                ) { c, dy -> c.consume(); Bar.side.value = (Bar.side.value - with(density) { dy.toDp() }).coerceIn(Bar.LEAST, Bar.MOST) }
            },
    ) { if (over || dragging) Box(Modifier.fillMaxWidth().height(1.dp).background(if (dragging) Ink.mid else Ink.line)) }
}

/**
 * How wide the bar along the foot is before it shows each of these too (under [BAR_WORDS]: the cover, the song, back /
 * play / next; the times always, on the line). The beat is on the line, between the times.
 */
private val BAR_WORDS = 580.dp
private val BAR_VOLUME = 840.dp
private val BAR_BEAT = 640.dp

/** The line filling with the song in the cover's colour, to go anywhere in it ([SeekLine]). */
@Composable
fun Line(n: Player.Now, accent: Color, modifier: Modifier = Modifier) = SeekLine(n, accent, modifier)

/** The queue, as words: played dim, the one playing lit, the rest as they come. A click plays from there. */
@Composable
private fun QueuePage(open: (Page) -> Unit, onClose: () -> Unit) {
    val queue by Player.queue.collectAsState()
    val now by Player.now.collectAsState()
    val at = now?.index ?: -1
    val list = rememberLazyListState()
    // opened at the song playing, a few before it showing
    LaunchedEffect(Unit) { if (at > 2) list.scrollToItem(at - 2) }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Column(Modifier.padding(start = SIDE, end = SIDE, top = 16.dp)) {
            BasicText("UP NEXT", style = headline(64.sp))
            Spacer(Modifier.height(6.dp))
            BasicText(if (queue.isEmpty()) "nothing queued" else "${queue.size} ${if (queue.size == 1) "song" else "songs"}   right click one for more",
                style = pixel(12.sp, Ink.mid))
            RadioWord(Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
        }
        QueueList(open, list, PaddingValues(start = SIDE, end = SIDE, bottom = 32.dp), Modifier.fillMaxSize())
    }
}

/** Radio: lit, the queue's end brings more like it. */
@Composable
fun RadioWord(modifier: Modifier = Modifier) {
    val radio by Player.radio.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Word("radio", pixel(13.sp, if (radio) Ink.ink else Ink.dim), touch = 8.dp) { Player.radio(!radio) }
        BasicText(if (radio) "  then more like these" else "  stops at the end", style = pixel(12.sp, Ink.mid))
    }
}

/** A row's grip, to drag it into another place: six pixels in two columns. */
private val GRIP = listOf("#.#", "...", "#.#", "...", "#.#")

/**
 * The queue's songs: played dim, the one playing lit, the rest as they come; a click plays from there. Each dragged by
 * its grip (at its right end, under the mouse) into another place (2026-11-10, Bronson: "we need a way of reordering the up
 * next list"): the row follows the mouse, trading places with each neighbour it passes half of, the queue moved as it goes.
 */
@Composable
fun QueueList(open: (Page) -> Unit, list: androidx.compose.foundation.lazy.LazyListState, pad: PaddingValues, modifier: Modifier, follow: Boolean = false) {
    val queue by Player.queue.collectAsState()
    val now by Player.now.collectAsState()
    val at = now?.index ?: -1
    // the row held, and how far it's been dragged from its place
    var dragging by remember { mutableStateOf(-1) }
    var dy by remember { mutableStateOf(0f) }
    // `follow` (the player's up next): the song playing at the list's top, there at once when it opens and slid to as
    // each next one starts, what's been played above it a scroll away (2026-11-09); not while a row's held
    if (follow) {
        val opened = remember { booleanArrayOf(false) }
        LaunchedEffect(at, queue.size) {
            if (at < 0 || at >= queue.size || dragging >= 0) return@LaunchedEffect
            if (!opened[0]) { opened[0] = true; list.scrollToItem(at) } else list.animateScrollToItem(at)
        }
    }
    // past half the neighbour it's over: the two trade places, the drag carried on from the new place
    fun step() {
        val seen = list.layoutInfo.visibleItemsInfo
        if (dy > 0) seen.firstOrNull { it.index == dragging + 1 }?.let { n ->
            if (dy > n.size / 2f) { Player.move(dragging, dragging + 1); dragging += 1; dy -= n.size }
        } else if (dy < 0) seen.firstOrNull { it.index == dragging - 1 }?.let { p ->
            if (-dy > p.size / 2f) { Player.move(dragging, dragging - 1); dragging -= 1; dy += p.size }
        }
    }
    // the playing song's cover colour, the progress line's, for its row's wash
    val accent = rememberCover(now?.song, 300)?.accent
    LazyColumn(modifier.feather(list), list, contentPadding = pad) {
        // no keys: one song can be queued twice (so a row's place is its index, and the held one is followed by index)
        itemsIndexed(queue) { i, s ->
            val src = remember { MutableInteractionSource() }
            val over by src.collectIsHoveredAsState()
            val held = i == dragging
            Row(
                Modifier.hoverable(src).zIndex(if (held) 1f else 0f)
                    .graphicsLayer { translationY = if (held) dy else 0f }.background(if (held) Ink.black else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    SongRow(s, said = s.artist.lowercase(), open = open, lit = i == at, dim = i < at, onRemove = { Player.remove(i) }, glow = accent) { Player.jump(i) }
                }
                // its grip, at its right end (where the titles line up with the headings above is left alone)
                Box(
                    Modifier.width(16.dp).height(36.dp)
                        .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(java.awt.Cursor.MOVE_CURSOR)))
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = i; dy = 0f },
                                onDragEnd = { dragging = -1; dy = 0f },
                                onDragCancel = { dragging = -1; dy = 0f },
                            ) { c, d -> c.consume(); if (dragging >= 0) { dy += d; step() } }
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) { if (over || held) Sprite(GRIP, if (held) Ink.ink else Ink.mid, 2.dp) }
            }
        }
    }
}

/**
 * A song's lines, read as it plays: null while they're being found (lrclib busy is asked again each minute) or lyrics
 * are off, empty when there are none (a station too). "None" and "still finding" were one null, so a song without
 * words said "finding the words" for ever (a Mac, 2026-10-29).
 */
@Composable
fun rememberLyrics(song: Song): List<Line>? {
    val on by Lyrics.on.collectAsState()
    val lines by androidx.compose.runtime.produceState<List<Line>?>(null, song.id, on) {
        value = null
        if (on && (song.station || song.episode)) { value = emptyList(); return@produceState }
        while (on) { Lyrics.of(song)?.let { value = it; return@produceState }; kotlinx.coroutines.delay(61_000) }
    }
    return lines?.takeIf { on }
}

/** What the lyrics' place says when there are no lines to show: off, still finding them, or none. */
fun lyricsSaid(lines: List<Line>?): String = when {
    !Lyrics.on.value -> "lyrics are off: " + (if (Home.mac) "⌘L" else "ctrl+l") + " turns them on"
    lines == null -> "finding the words"
    else -> "no words found for this one"
}

/**
 * The words: the line before dim, the sung one big (lit word by word), the next three dim; a new line rises in. A line
 * is never cut short (a long one a size smaller). The wheel or a drag shows them all, the sung one lit, a click on one
 * plays from there ([onSeek]); left a few seconds, they follow the song again (a listener couldn't read the rest of a
 * long song's words, 2026-11-01). Words with no times: all of them, scrolled.
 */
@Composable
fun LyricsBlock(lines: List<Line>, index: Int, modifier: Modifier, small: Boolean = false, big: Boolean = false, onSeek: ((Long) -> Unit)? = null, position: Long = -1L) {
    // big: the full-screen player's inner sleeve, the words the room's whole height
    val k = if (small) .78f else if (big) 1.35f else 1f
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drift = with(density) { Motion.drift.roundToPx() }
    val notch = with(density) { 64.dp.toPx() }
    val scroll = androidx.compose.foundation.rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // when the wheel or a drag last moved them (0: following the song)
    var touched by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val untimed = lines.firstOrNull()?.let { it.at < 0 } == true
    LaunchedEffect(touched) {
        if (touched == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(4_000)
        touched = 0L
    }
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { d -> touched = System.currentTimeMillis(); scroll.dispatchRawDelta(-d) }
    // the lines to come run out of room at the foot: feathered, never cut
    Box(
        modifier.clipToBounds().feather({ if (touched != 0L || (untimed && scroll.value > 0)) 1f else 0f }, { 1f })
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != androidx.compose.ui.input.pointer.PointerEventType.Scroll) continue
                        val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy == 0f) continue
                        touched = System.currentTimeMillis()
                        scroll.dispatchRawDelta(dy * notch)
                        e.changes.forEach { it.consume() }
                    }
                }
            }
            .draggable(drag, androidx.compose.foundation.gestures.Orientation.Vertical, onDragStopped = { v ->
                touched = System.currentTimeMillis()
                scope.launch { scroll.animateScrollBy(-v * .25f, tween(Motion.ENTER * 3, easing = Motion.expo)) }
            }),
    ) {
        androidx.compose.animation.Crossfade(untimed || touched != 0L, animationSpec = tween(Motion.ENTER, easing = Motion.expo), label = "all") { all ->
            if (all) {
                // every line, the sung one lit, first shown with it near the top
                val tops = remember { HashMap<Int, Int>() }
                val above = with(density) { 120.dp.roundToPx() }
                LaunchedEffect(Unit) {
                    if (untimed) return@LaunchedEffect
                    androidx.compose.runtime.withFrameNanos { }   // laid out first
                    tops[index]?.let { scroll.scrollTo((it - above).coerceAtLeast(0)) }
                }
                Column(Modifier.fillMaxWidth().verticalScroll(scroll, enabled = false).padding(bottom = 80.dp)) {
                    lines.forEachIndexed { i, line ->
                        // untimed: a verse's gap kept
                        if (line.words.isBlank()) { if (untimed) Spacer(Modifier.height(12.dp * k)); return@forEachIndexed }
                        // the whole row and the gap under it a click's, not just the words
                        BasicText(line.words.uppercase(), style = headline((if (untimed) 18.sp else 22.sp) * k, if (untimed || i == index) Ink.ink else Ink.dim),
                            modifier = Modifier.fillMaxWidth().onPlaced { tops[i] = it.positionInParent().y.toInt() }.then(
                                if (untimed || onSeek == null) Modifier else Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                                    onClick = felt { onSeek(line.at); touched = 0L })).padding(bottom = 12.dp * k))
                    }
                }
            } else AnimatedContent(
                targetState = index,
                transitionSpec = {
                    (slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { drift } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))) togetherWith
                        (slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { -drift } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)))
                },
                label = "line",
            ) { i ->
                val sung = lines.getOrNull(i)?.takeIf { it.words.isNotBlank() }
                // a line leaving is drawn with its own number over the next song's lyrics (a mix changes them under it): read
                // as far as they go (line 80 of 28 crashed the computer, 2026-11-08)
                val before = (i - 1 downTo 0).asSequence().mapNotNull { lines.getOrNull(it) }.firstOrNull { it.words.isNotBlank() }?.takeIf { sung != null }
                val after = (i + 1 until lines.size).asSequence().map { lines[it] }.filter { it.words.isNotBlank() }.take(if (big) 6 else 3).toList()
                Column(Modifier.fillMaxWidth()) {
                    before?.let {
                        BasicText(it.words.uppercase(), style = headline(20.sp * k, Ink.dim))
                        Spacer(Modifier.height(16.dp * k))
                    }
                    sung?.let {
                        // a long line a size smaller, so it's all there
                        val size = when { it.words.length > 90 -> 26.sp; it.words.length > 48 -> 32.sp; else -> 40.sp }
                        // timed word by word (Better Lyrics): each word lit as it's sung; else lit in turn, by a guess
                        if (it.parts.isNotEmpty() && position >= 0) BasicText(
                            androidx.compose.ui.text.buildAnnotatedString {
                                it.parts.forEach { (at, w) ->
                                    pushStyle(androidx.compose.ui.text.SpanStyle(color = if (at <= position + Lyrics.LEAD) Ink.ink else Ink.dim)); append(w.uppercase()); pop()
                                }
                            },
                            style = headline(size * k),
                        )
                        else FadeText(it.words.uppercase(), headline(size * k), from = Ink.dim)
                        Spacer(Modifier.height(18.dp * k))
                    }
                    after.forEach {
                        BasicText(it.words.uppercase(), style = headline(22.sp * k, Ink.dim))
                        Spacer(Modifier.height(12.dp * k))
                    }
                }
            }
        }
    }
}


/**
 * Under a record's songs: more by them (your records of theirs, then YouTube Music's you don't have), and records
 * like it (yours by the artists their fans also like, else those artists themselves). From their artist page, kept.
 */
@Composable
private fun Around(a: Album, albums: List<Album>, open: (Page) -> Unit) {
    val info by androidx.compose.runtime.produceState<ArtistInfo?>(null, a.artist) { Artists.of(a.artist, "").collect { if (it != null) value = it } }
    val mine = remember(albums, a) { albums.filter { it.id != a.id && it.artist.equals(a.artist, ignoreCase = true) }.sortedBy { it.year } }
    val i = info
    val tube = remember(i, albums) { (i?.albums.orEmpty() + i?.singles.orEmpty().take(6)).filter { t -> albums.none { it.title.equals(t.title, ignoreCase = true) } && !t.title.equals(a.title, ignoreCase = true) } }
    val like = i?.like.orEmpty()
    val near = remember(like, albums) {
        val names = like.map { it.name.lowercase() }.toSet()
        albums.filter { it.artist.lowercase() in names }.shuffled(java.util.Random(a.id.hashCode().toLong())).take(12)
    }
    Column(Modifier.padding(top = 28.dp)) {
        if (mine.isNotEmpty() || tube.isNotEmpty()) {
            Label("more by them")
            Shelf {
                items(mine, key = { it.id }) { r -> RecordTile(r, Modifier.width(150.dp)) { open(Page.Record(r.id)) } }
                items(tube, key = { it.url }) { r -> TubeTile(r, Modifier.width(150.dp)) { open(Page.Tube(r)) } }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (near.isNotEmpty()) {
            Label("records like it")
            Shelf {
                items(near, key = { it.id }) { r -> RecordTile(r, Modifier.width(150.dp)) { open(Page.Record(r.id)) } }
            }
        } else if (like.isNotEmpty()) {
            Label("fans also like")
            Shelf {
                items(like, key = { it.id.ifEmpty { it.name } }) { who ->
                    Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                        onClick = felt { open(Page.Artist(who.name, who.id)) })) {
                        CoverOf(remember(who.face) { who.face?.let { faceSong(who.name, it) } }, who.name, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                        Spacer(Modifier.height(8.dp))
                        UnderCover(who.name, "artist")
                    }
                }
            }
        }
    }
}

/** A list's word for covers beside its songs, lit when on (one choice for every list: [ListCovers]). */
@Composable
fun CoversWord(modifier: Modifier = Modifier) {
    val on by ListCovers.on.collectAsState()
    Word("covers", pixel(14.sp, if (on) Ink.ink else Ink.dim), modifier, touch = 8.dp) { ListCovers.set(!on) }
}


/** The page being drawn's place ("index:page", or "place:<name>" for the library's places), for what it keeps. */
val LocalPageKey = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** Each page's scroll (its item and how far into it), kept while it's under another; a closed page's let go. */
object PageScroll {
    val kept = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    /** The stack changed: what's no longer in it let go (the places kept). */
    fun stack(pages: kotlin.collections.List<Page>) {
        val now = pages.mapIndexed { i, p -> "$i:${p.hashCode()}" }.toSet()
        kept.keys.removeIf { !it.startsWith("place:") && it.substringBefore("#") !in now }
    }
}

/** Whether a text field has the keys: the window's own (space, the arrows) are left to it then. */
object Typing { @Volatile var on = false }

/** A text field's: while it has the keys, [Typing] says so (and stops saying so if it goes away holding them). */
fun Modifier.typing(): Modifier = composed {
    val mine = remember { booleanArrayOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (mine[0]) Typing.on = false } }
    onFocusChanged { mine[0] = it.isFocused; Typing.on = it.isFocused }
}

/** When the page on top was opened (ms), going forward (a page opened, a place chosen); 0 going back. */
val LocalOpened = androidx.compose.runtime.compositionLocalOf { 0L }

/**
 * A page's rows arriving in turn as it opens, the phone's (2026-11-10, Bronson: "i like all the phone animations"): ink's
 * [arrive], up 20 dp and in, 50 ms apart, the first eight, the rest with the eighth; rows that come later (scrolled to),
 * and pages gone back to, just there.
 */
@Composable
fun Modifier.cascade(i: Int): Modifier {
    val opened = LocalOpened.current
    val now = remember { System.currentTimeMillis() }
    if (opened == 0L || now - opened > 600) return this
    return this.arrive(i.coerceAtMost(8), Unit)
}

/**
 * A page's list whose rows [cascade] in as it opens, each in the order it's put in the list (one line where the list's
 * rows are made: `cascading { ... }`, rather than every row wrapped by hand).
 */
fun androidx.compose.foundation.lazy.LazyListScope.cascading(rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) = Cascading(this).rows()

private class Cascading(private val scope: androidx.compose.foundation.lazy.LazyListScope) : androidx.compose.foundation.lazy.LazyListScope by scope {
    private var n = 0
    // each row's pieces one under another, as a list's item lays them (a Box laid them over each other: the kept page's
    // name over its count and its words, 2026-11-10)
    override fun item(key: Any?, contentType: Any?, content: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit) {
        val i = n++
        scope.item(key, contentType) { val row = this; Column(Modifier.cascade(i)) { row.content() } }
    }
    override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?,
                       itemContent: @Composable androidx.compose.foundation.lazy.LazyItemScope.(index: Int) -> Unit) {
        val first = n; n += count
        scope.items(count, key, contentType) { at -> val row = this; Column(Modifier.cascade(first + at)) { row.itemContent(at) } }
    }
}

/** A page's list, begun where it was left when it's come back to (the phone's pageList). */
@Composable
fun pageList(part: String = ""): androidx.compose.foundation.lazy.LazyListState {
    val k = LocalPageKey.current?.let { "$it#list$part" }
    val state = remember(k) { k?.let { PageScroll.kept[it] }.let { androidx.compose.foundation.lazy.LazyListState(it?.first ?: 0, it?.second ?: 0) } }
    // the place kept, gone back to once the page is long enough to have it: an artist's page draws short first (while
    // YouTube Music's part is read back), and a place further down than that began at the top (2026-11-09)
    val back = remember(k) { k?.let { PageScroll.kept[it] } }
    if (back != null && back.first > 0) androidx.compose.runtime.LaunchedEffect(state) {
        kotlinx.coroutines.withTimeoutOrNull(4_000) {
            androidx.compose.runtime.snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > back.first }
        } ?: return@LaunchedEffect
        if (!state.isScrollInProgress && state.firstVisibleItemIndex < back.first) state.scrollToItem(back.first, back.second)
    }
    if (k != null) androidx.compose.runtime.DisposableEffect(k, state) { onDispose { PageScroll.kept[k] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset } }
    return state
}

/** A page's grid, the same. */
@Composable
fun pageGrid(part: String = ""): androidx.compose.foundation.lazy.grid.LazyGridState {
    val k = LocalPageKey.current?.let { "$it#grid$part" }
    val state = remember(k) { k?.let { PageScroll.kept[it] }.let { androidx.compose.foundation.lazy.grid.LazyGridState(it?.first ?: 0, it?.second ?: 0) } }
    if (k != null) androidx.compose.runtime.DisposableEffect(k, state) { onDispose { PageScroll.kept[k] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset } }
    return state
}

/**
 * What the songs page shows (2026-11-10, Bronson: "a way to separate the music sources", "genres in here too", "very
 * smart intuitive filtering", "a marked offline category, just youtube", "a grid view"): where they come from
 * (everything: the library and every YouTube song you have; or this computer's, your server's, YouTube's, or what's kept
 * offline from any of them) and a genre (by each artist's listeners' tags, [Genres]), the two together; as a list or a
 * grid of covers; all kept.
 */
object SongsFrom {
    private val file = java.io.File(Home.dir, "songs-from")
    private val genreFile = java.io.File(Home.dir, "songs-genre")
    val chosen = kotlinx.coroutines.flow.MutableStateFlow(runCatching { file.readText().trim() }.getOrDefault("").ifEmpty { "all" })
    /** The genre shown, "" for every one. */
    val genre = kotlinx.coroutines.flow.MutableStateFlow(runCatching { genreFile.readText().trim() }.getOrDefault(""))
    // "kept": kept offline, from wherever it came (2026-11-10, Bronson: "a marked offline category, just youtube")
    val WORDS = listOf("all" to "everything", "computer" to "this computer", "server" to "your server", "youtube" to "youtube", "kept" to "kept offline")

    fun choose(f: String) { chosen.value = f; runCatching { if (f == "all") file.delete() else file.writeText(f) } }

    private val viewFile = java.io.File(Home.dir, "songs-grid")
    /** The songs as a grid of covers, not a list (kept). */
    val grid = kotlinx.coroutines.flow.MutableStateFlow(viewFile.exists())
    fun view(asGrid: Boolean) { grid.value = asGrid; runCatching { if (asGrid) viewFile.writeText("") else viewFile.delete() } }
    /** A genre shown ("": every genre). */
    fun setGenre(g: String) {
        genre.value = g
        runCatching { if (g.isEmpty()) genreFile.delete() else genreFile.writeText(g) }
    }
    fun of(s: Song) = when { s.tube -> "youtube"; s.server -> "server"; else -> "computer" }
}

/**
 * The songs page's filters under its orders, one line (wrapping where it's narrow): the sources it has, then a hairline's
 * gap, then the genres your songs are in, most first (eight, and the one chosen), each with how many. Lit is what's shown;
 * a source and a genre together narrow it to both; the lit genre again, every genre.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun FilterRow(counts: Map<String, Int>, genres: List<Pair<String, Int>>, modifier: Modifier) {
    val from by SongsFrom.chosen.collectAsState()
    val genre by SongsFrom.genre.collectAsState()
    val now = if (from in counts) from else "all"
    @Composable
    fun Choice(word: String, count: Int?, lit: Boolean, onClick: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
        Word(word, pixel(13.sp, if (lit) Ink.ink else Ink.dim), touch = 8.dp, onClick = onClick)
        count?.let { BasicText(" $it", style = pixel(12.sp, if (lit) Ink.mid else Ink.dim)) }
    }
    androidx.compose.foundation.layout.FlowRow(modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (counts.size > 1) SongsFrom.WORDS.filter { (k, _) -> k == "all" || k in counts }.forEach { (k, word) ->
            Choice(word, counts[k].takeIf { k != "all" }, k == now) { SongsFrom.choose(k) }
        }
        if (counts.size > 1 && (genres.isNotEmpty() || genre.isNotEmpty())) Box(Modifier.width(1.dp).height(14.dp).background(Ink.line))
        // the genres dropped down from one word, as the places' menu is (2026-11-10, Bronson: "a cleaner way to display
        // genres"): every genre, or the one chosen, lit; ↓ opens it, ↑ while it's open
        if (genres.isNotEmpty() || genre.isNotEmpty()) Box {
            var down by remember { mutableStateOf(false) }
            // there while it's down or still folding away: it unfolds from under the word, its rows rising in one after
            // another as the places menu's do, and folds back up (2026-11-10, as the phone's)
            val open = remember { androidx.compose.animation.core.MutableTransitionState(false) }
            open.targetState = down
            val turn by androidx.compose.animation.core.animateFloatAsState(if (down) 180f else 0f, tween(Motion.ENTER, easing = Motion.expo), label = "arrow")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Word(genre.ifEmpty { "every genre" }, pixel(13.sp, if (genre.isEmpty() && !down) Ink.dim else Ink.ink), touch = 8.dp) { down = !down }
                Sprite(PLACES_DOWN, if (genre.isEmpty() && !down) Ink.dim else Ink.ink, 2.dp, Modifier.padding(start = 4.dp).graphicsLayer { rotationZ = turn })
            }
            if (open.currentState || open.targetState) androidx.compose.ui.window.Popup(
                offset = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.unit.IntOffset(0, 30.dp.roundToPx()) },
                onDismissRequest = { down = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                // the chosen one always there, even with none of its songs tagged yet
                val all = if (genre.isNotEmpty() && genres.none { it.first == genre }) genres + (genre to 0) else genres
                androidx.compose.animation.AnimatedVisibility(
                    open,
                    enter = androidx.compose.animation.expandVertically(tween(Motion.ENTER, easing = Motion.expo), expandFrom = Alignment.Top) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
                    exit = androidx.compose.animation.shrinkVertically(tween(Motion.EXIT, easing = Motion.expo), shrinkTowards = Alignment.Top) + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
                ) {
                    Column(Modifier.background(Ink.black).border(1.dp, Ink.line).heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
                        (listOf("every genre" to null) + all.map { it.first to it.second }).forEachIndexed { i, (g, n) ->
                            // each row rising in 30 ms after the one before (the first dozen; the rest with the last)
                            val shown = remember { androidx.compose.animation.core.Animatable(0f) }
                            LaunchedEffect(Unit) { kotlinx.coroutines.delay(minOf(i, 12) * 30L); shown.animateTo(1f, tween(Motion.ENTER, easing = Motion.expo)) }
                            Box(Modifier.graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 10.dp.toPx() }) {
                                if (i == 0) Choice(g, null, genre.isEmpty()) { SongsFrom.setGenre(""); down = false }
                                else Choice(g, n, g == genre) { SongsFrom.setGenre(g); down = false }
                            }
                        }
                    }
                }
            }
        }
    }
}
