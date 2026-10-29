package app.ink.music

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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

fun main() {
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    linuxWindow()
    // ⌘Q (the app menu's Quit, the Dock's): the same whole quit as closing the window
    runCatching { java.awt.Desktop.getDesktop().setQuitHandler { _, response -> runCatching { quit() }; response.performQuit() } }
    Library.start()
    Server.start()
    Sorts.start()
    Lyrics.start()
    Keys.start()
    ListenBrainz.start(Home.dir)
    LastFm.start(Home.dir)
    Listened.read()
    Loved.start()
    TubeAccount.start()
    // a paired phone kept in step, over the wifi (Sync)
    SyncHub.start()
    Follows.start()
    Colours.start()
    Player.start()
    // ink's icon on every window Java makes, not Java's own (Duke): Windows' restart screen showed Duke beside ink music
    // (Bronson's PC, 2026-10-29); Swing's hidden owner of a window with none (the folder chooser's) and any other
    runCatching {
        val icon = javax.imageio.ImageIO.read(Ink::class.java.getResource("/ink-music.png"))
        javax.swing.JOptionPane.getRootFrame().iconImage = icon
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            (e.source as? java.awt.Window)?.let { if (it.iconImages.isEmpty()) it.iconImages = listOf(icon) }
        }, java.awt.AWTEvent.WINDOW_EVENT_MASK)
    }
    application {
        Window(
            onCloseRequest = { quit() },
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
                // Windows: its title bar black, not white
                darkTitleBar(window)
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
            // the menus in the Mac's own bar along the top of the screen (elsewhere, the keys alone)
            if (Home.mac) Menus()
            Sized { App() }
        }
        MiniWindow()
    }
    // the window's closed: gone, wholly (Bronson, 2026-10-29: after quitting, the Dock still had it "running in the
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
    fun player(full: Boolean = false) { if (full) Win.full(true); open(Page.Playing) }
}

/**
 * The Mac's menu bar: play (the song's controls, volume, lyrics, up next, play on), go (the places, ⌘1 to ⌘7, find,
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
            Separator()
            Item("Bigger", shortcut = cmd(Key.Equals)) { Size.bigger() }
            Item("Smaller", shortcut = cmd(Key.Minus)) { Size.smaller() }
        }
    }
}

/** Clear of the Mac's traffic lights (the title bar is gone, its buttons stay). */
val TOP = if (Home.mac) 28.dp else 0.dp
val SIDE = 32.dp

@Composable
private fun App() {
    val pages = Nav.pages
    var place by Nav::place
    val focus = remember { FocusRequester() }
    // the keys the window's: given back to it as pages change (find's field had them, and closing it left them nowhere)
    LaunchedEffect(pages.lastOrNull()) { if (pages.lastOrNull() != Page.Find) runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Ink.black).takesDrops().focusRequester(focus).focusable()
            .onKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onKeyEvent false
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
                    k.key == Key.Spacebar -> { Player.toggle(); true }
                    k.key == Key.DirectionRight -> { Player.seek(Player.position.value + 10_000); true }
                    k.key == Key.DirectionLeft -> { Player.seek((Player.position.value - 10_000).coerceAtLeast(0)); true }
                    k.key == Key.DirectionUp -> { Player.volume(Player.volume.value + 5); true }
                    k.key == Key.DirectionDown -> { Player.volume(Player.volume.value - 5); true }
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
        if (frame != Frame.NARROW) Rail(place, { place = it; pages.clear() }, { if (pages.lastOrNull() != Page.Find) pages += Page.Find; Finding.asked.value++ },
            { if (pages.lastOrNull() != Page.Settings) pages += Page.Settings })
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // the player's over the whole window: under it, the page it was opened from
                val top = pages.lastOrNull().let { if (it == Page.Playing) pages.dropLast(1).lastOrNull() else it }
                AnimatedContent(
                    top,
                    transitionSpec = {
                        (slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 12 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))) togetherWith
                            fadeOut(tween(Motion.EXIT, easing = Motion.expo))
                    },
                    label = "page",
                ) { page ->
                    when (page) {
                        null -> LibraryPage(place, frame == Frame.NARROW, { place = it }) { pages += it }
                        is Page.Record -> RecordPage(page.id, { pages += it }) { Nav.close() }
                        is Page.Artist -> ArtistPage(page.name, page.id, { pages += it }) { Nav.close() }
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
                    }
                }
            }
            // the bar along the foot where there's no room for the now column
            val remote = remoteNow()
            if ((frame != Frame.WIDE || tucked || finding || remote != null) && pages.lastOrNull() != Page.Playing) MiniPlayer(
                if (frame == Frame.WIDE && !finding) ({ Tuck.set(false) }) else null,
                { if (pages.lastOrNull() != Page.Queue) pages += Page.Queue },
                { if (pages.lastOrNull() != Page.PlayOn) pages += Page.PlayOn },
                { name -> pages += Page.Artist(name) },
            ) { if (pages.lastOrNull() != Page.Playing) pages += Page.Playing }
        }
        // the now column, tucked away to the right (its arrow, or ⌘\): the bar along the foot instead
        AnimatedVisibility(
            frame == Frame.WIDE && !tucked && !finding && pages.lastOrNull() != Page.Playing && (playing != null || trouble != null),
            enter = expandHorizontally(tween(Motion.ENTER, easing = Motion.expo)) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = shrinkHorizontally(tween(Motion.ENTER, easing = Motion.expo)) + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { NowColumn({ Tuck.set(true) }) { pages += it } }
        }
        AnimatedVisibility(
            pages.lastOrNull() == Page.Playing,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 12 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { it / 16 } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) {
            // nothing playing here and the phone is: its playing, the whole window (steered from here, to take over)
            val remote = remoteNow()
            if (remote != null) RemotePlayerPage(remote, { pages += it }) { Win.full(false); pages.remove(Page.Playing) }
            else PlayerPage({ pages += it }) { Win.full(false); pages.remove(Page.Playing) }
        }
        DropHint()
      }
    }
}

@Composable
private fun LibraryPage(tab: String, words: Boolean, onPlace: (String) -> Unit, open: (Page) -> Unit) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val read by Library.read.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = TOP + if (words) 16.dp else 40.dp)) {
        // the phone's layout (a narrow window): the places as words across the top
        if (words) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in PLACES) Word(t.uppercase(), headline(26.sp, if (t == tab) Ink.ink else Ink.dim)) { onPlace(t) }
            Pixel(SEARCH, "find", Ink.mid, Modifier.padding(top = 4.dp)) { open(Page.Find) }
            Word("your year", pixel(13.sp, Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) { open(Page.Year) }
            Word("settings", pixel(13.sp, Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) { open(Page.Settings) }
        }
        // beside the rail no name over the page: the rail's lit word says where you are (Bronson, 2026-10-29)
        if (words) Spacer(Modifier.height(14.dp))
        if (tab in setOf("albums", "artists", "songs")) NetworkShut(Modifier.padding(horizontal = SIDE).padding(bottom = 18.dp))
        if (songs.isNotEmpty() && tab in setOf("albums", "artists", "songs")) SortRow(tab, when (tab) { "albums" -> Sorts.RECORDS; "artists" -> Sorts.ARTISTS; else -> Sorts.SONGS },
            Modifier.padding(horizontal = SIDE))
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
            "listen" -> ListenPage(open)
            // ink weekly, ink radio and everything people picked: one place (explore was its own till 2026-10-29)
            "ink", "explore" -> ExplorePage(open)
            "lists" -> ListsPage(open)
            else -> {
                val every = remember(songs, sc, seed, heard, minute) {
                    Sorts.order(songs, sc, seed, { it.id }) { s ->
                        when (sc.sort) {
                            Sort.HOUR -> heard.hour(s.id, minute); Sort.LATELY -> heard.lately(s.id); Sort.MOST -> heard.most(s.id)
                            Sort.FORGOTTEN -> heard.forgotten(s.id, now); else -> null
                        }
                    }
                }
                key(sc, seed) { Songs(every, open) }
            }
        }
    }
}

@Composable
private fun Records(albums: List<Album>, open: (Page) -> Unit, header: (@Composable () -> Unit)? = null) {
    val grid = rememberLazyGridState()
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
    // a grid of faces, as the records are (Bronson, 2026-10-29: one thin list down a wide page)
    val albums by Library.albums.collectAsState()
    val covers = remember(albums) { albums.groupBy { it.artist }.mapValues { it.value.first().songs.first() } }
    val grid = rememberLazyGridState()
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
            listOfNotNull(a.records.takeIf { it > 0 }?.let { "$it ${if (it == 1) "record" else "records"}" }, "${a.songs} ${if (a.songs == 1) "song" else "songs"}").joinToString("   "),
            style = pixel(12.sp, Ink.mid),
        )
    }
}

@Composable
private fun Songs(songs: List<Song>, open: (Page) -> Unit) {
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp)) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, said = "${s.artist}  ·  ${s.album}".lowercase(), open = open) { Player.play(songs, i) } }
    }
}

/** A song as words: its title, and under it who and where; lit while it plays. A click plays it, in its list. */
@Composable
fun SongRow(
    s: Song, number: String? = null, said: String? = null, open: ((Page) -> Unit)? = null,
    lit: Boolean? = null, dim: Boolean = false, onRemove: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val now by Player.now.collectAsState()
    val on = lit ?: (now?.song?.id == s.id)
    var options by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    val color = (if (on) Ink.ink else if (dim) Ink.dim else Ink.mid).let { if (over) brighter(it) else it }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().hoverable(src).clickable(src, indication = null, onClick = felt { options = false; onClick() })
                // a right click (the phone's hold): its options open under it
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                options = !options; e.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) BasicText(number, style = pixel(12.sp, color), modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                BasicText(s.title.uppercase(), style = headline(17.sp, color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (said != null) BasicText(said, style = pixel(12.sp, if (dim) Ink.dim else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // a length not known (YouTube's top songs come without one): none said
            if (s.duration > 0) BasicText(clock(s.duration), style = pixel(12.sp, if (dim) Ink.dim else Ink.mid), modifier = Modifier.padding(start = 16.dp))
        }
        // its options, wrapping onto a second line where there's no room (the now column's up next)
        if (options) androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(start = if (number != null) 36.dp else 0.dp, bottom = 10.dp).fadeUp(),
            horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val o = pixel(13.sp)
            Word("play next", o, touch = 6.dp) { options = false; Player.playNext(s) }
            Word("add to queue", o, touch = 6.dp) { options = false; Player.add(s) }
            // radio: this song, then more like it as the queue runs out
            if (!s.station) Word("radio", o, touch = 6.dp) { options = false; Player.startRadio(s) }
            val loved by Loved.ids.collectAsState()
            if (!s.station) Word(if (s.id in loved) "unlove" else "love", o, touch = 6.dp) { options = false; Loved.toggle(s) }
            if (open != null && s.album.isNotEmpty()) Word("record", o, touch = 6.dp) { options = false; open(Page.Record(s.albumId)) }
            if (open != null) Word("artist", o, touch = 6.dp) { options = false; open(Page.Artist(s.albumArtist)) }
            // kept on the computer, to play with no signal (your server's, YouTube's)
            if (Offline.keepable(s)) {
                val kept by Offline.kept.collectAsState()
                if (s.id in kept) Word("not offline", o, touch = 6.dp) { options = false; Offline.remove(listOf(s.id)) }
                else Word("keep offline", o, touch = 6.dp) { options = false; Offline.keep(listOf(s)) }
            }
            if (onRemove != null) Word("remove", o, touch = 6.dp) { options = false; onRemove() }
        }
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
    BoxWithConstraints(modifier) {
        if (cover != null) Image(cover.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else NameCover(name, maxWidth)
    }
}

/** A song's words as shown: a station's, what it says it's playing (its own name and city with nothing said). */
@Composable
fun said(n: Player.Now): Pair<String, String> {
    val air by Player.onAir.collectAsState()
    val s = n.song
    val a = air
    return if (s.station && a != null) a.title to (a.artist ?: s.title) else s.title to s.artist
}

/** Close: the down arrow, as the phone's, top left (clear of the traffic lights). */
@Composable
fun Close(onClose: () -> Unit) = CloseArrow(Modifier.padding(start = SIDE - 8.dp, top = TOP), onClick = onClose)

@Composable
private fun RecordPage(id: String, open: (Page) -> Unit, onClose: () -> Unit) {
    val albums by Library.albums.collectAsState()
    val a = albums.firstOrNull { it.id == id } ?: return Close(onClose)
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 16.dp)) {
            Column(Modifier.width(320.dp)) {
                CoverOf(a.songs.first(), a.title, Modifier.size(320.dp))
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
            Spacer(Modifier.width(40.dp))
            val list = rememberLazyListState()
            val discs = a.songs.map { it.disc }.distinct().size > 1
            LazyColumn(Modifier.weight(1f).fillMaxHeight().feather(list), list, contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(a.songs, key = { _, s -> s.id }) { i, s ->
                    val n = if (s.track > 0) (if (discs) "${s.disc}.${s.track}" else "${s.track}") else "${i + 1}"
                    SongRow(s, number = n, said = s.artist.takeIf { it != a.artist }?.lowercase(), open = open) { Player.play(a.songs, i) }
                }
                item { Around(a, albums, open) }
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
 * What's playing, along the foot of the window: the line along the cover's top edge filling with the
 * song in its colour, the cover, the song, back / play / next and the times. A click opens the player.
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
    Column(Modifier.fillMaxWidth().background(Ink.black).padding(start = SIDE, end = SIDE, bottom = 14.dp)) {
        Line(n, cover?.accent ?: Ink.ink)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onOpen)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Opens(Modifier.size(72.dp), onOpen) { CoverOf(n.song, n.song.album.ifEmpty { n.song.title }, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val (title, artist) = said(n)
                    BasicText(title.uppercase(), style = headline(20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    val on by Casting.on.collectAsState()
                    Row {
                        Word(artist.lowercase(), pixel(12.sp, Ink.mid)) { if (!n.song.station) onArtist(n.song.albumArtist.ifBlank { n.song.artist.substringBefore(", ") }) }
                        on?.let { BasicText("  on ${it.name.lowercase()}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            Controls(n, small = true)
            Spacer(Modifier.width(24.dp))
            Times(n, Modifier.widthIn(min = 96.dp))
            Volume(line = 80.dp)
            Word("up next", pixel(14.sp, Ink.mid), Modifier.padding(start = 12.dp), touch = 10.dp, onClick = onQueue)
            // where it plays: lit while on a speaker
            val on by Casting.on.collectAsState()
            Word("play on", pixel(14.sp, if (on != null) Ink.ink else Ink.mid), Modifier.padding(start = 12.dp), touch = 10.dp, onClick = onPlayOn)
            Pixel(FULL, "full screen", Ink.mid, Modifier.padding(start = 4.dp)) { Win.full(true); onOpen() }
            // the now column, brought back out
            onUntuck?.let { Pixel(UNTUCK, "show what's playing beside", Ink.mid, onClick = it) }
        }
    }
}

@Composable
private fun Times(n: Player.Now, modifier: Modifier = Modifier) {
    val pos by Player.position.collectAsState()
    BasicText(if (n.song.station) "live" else "${clock(pos)} / ${clock(n.duration)}", style = pixel(12.sp, Ink.mid), modifier = modifier)
}

/** The 1 px line filling with the song in the cover's colour; a click along it goes there. */
@Composable
fun Line(n: Player.Now, accent: Color, modifier: Modifier = Modifier) {
    val pos by Player.position.collectAsState()
    // a station has no length (mpv's guess is the stream's buffer): the line stays a hairline
    val frac = if (n.duration > 0 && !n.song.station) (pos.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
    // a line too thin to aim at: its click reaches 8 dp either side of it
    Box(
        modifier.fillMaxWidth().height(17.dp).pointerInput(n.duration) {
            detectTapGestures { at -> if (n.duration > 0 && !n.song.station) Player.seek((at.x / size.width * n.duration).toLong()) }
        },
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line)) {
            Box(Modifier.fillMaxWidth(frac).height(1.dp).background(accent))
        }
    }
}

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

/** The queue's songs: played dim, the one playing lit, the rest as they come; a click plays from there. */
@Composable
fun QueueList(open: (Page) -> Unit, list: androidx.compose.foundation.lazy.LazyListState, pad: PaddingValues, modifier: Modifier) {
    val queue by Player.queue.collectAsState()
    val now by Player.now.collectAsState()
    val at = now?.index ?: -1
    LazyColumn(modifier.feather(list), list, contentPadding = pad) {
        // no keys: one song can be queued twice
        itemsIndexed(queue) { i, s ->
            SongRow(s, said = s.artist.lowercase(), open = open, lit = i == at, dim = i < at, onRemove = { Player.remove(i) }) { Player.jump(i) }
        }
    }
}

/** A song's lines, read as it plays (null while there are none to show; lrclib busy is asked again each minute). */
@Composable
fun rememberLyrics(song: Song): List<Line>? {
    val on by Lyrics.on.collectAsState()
    val lines by androidx.compose.runtime.produceState<List<Line>?>(null, song.id, on) {
        value = null
        while (on && !song.station) { Lyrics.of(song)?.let { value = it; return@produceState }; kotlinx.coroutines.delay(61_000) }
    }
    return lines?.takeIf { on && it.isNotEmpty() }
}

/** The words: the line before dim, the sung one big (lit word by word), the next three dim; a new line rises in. */
@Composable
fun LyricsBlock(lines: List<Line>, index: Int, modifier: Modifier, small: Boolean = false, big: Boolean = false) {
    // big: the full-screen player's inner sleeve, the words the room's whole height
    val k = if (small) .78f else if (big) 1.35f else 1f
    val drift = with(androidx.compose.ui.platform.LocalDensity.current) { Motion.drift.roundToPx() }
    // the lines to come run out of room at the foot: feathered, never cut
    Box(modifier.clipToBounds().feather({ 0f }, { 1f })) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { drift } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))) togetherWith
                    (slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { -drift } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)))
            },
            label = "line",
        ) { i ->
            val sung = lines.getOrNull(i)?.takeIf { it.words.isNotBlank() }
            val before = (i - 1 downTo 0).asSequence().map { lines[it] }.firstOrNull { it.words.isNotBlank() }?.takeIf { sung != null }
            val after = (i + 1 until lines.size).asSequence().map { lines[it] }.filter { it.words.isNotBlank() }.take(if (big) 6 else 3).toList()
            Column(Modifier.fillMaxWidth()) {
                before?.let {
                    BasicText(it.words.uppercase(), style = headline(20.sp * k, Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp * k))
                }
                sung?.let {
                    FadeText(it.words.uppercase(), headline(40.sp * k), maxLines = 4, overflow = TextOverflow.Ellipsis, from = Ink.dim)
                    Spacer(Modifier.height(18.dp * k))
                }
                after.forEach {
                    BasicText(it.words.uppercase(), style = headline(22.sp * k, Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp * k))
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(mine, key = { it.id }) { r -> RecordTile(r, Modifier.width(150.dp)) { open(Page.Record(r.id)) } }
                items(tube, key = { it.url }) { r -> TubeTile(r, Modifier.width(150.dp)) { open(Page.Tube(r)) } }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (near.isNotEmpty()) {
            Label("records like it")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(near, key = { it.id }) { r -> RecordTile(r, Modifier.width(150.dp)) { open(Page.Record(r.id)) } }
            }
        } else if (like.isNotEmpty()) {
            Label("fans also like")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
