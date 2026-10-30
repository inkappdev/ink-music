package app.ink.music

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.lifecycleScope
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

class Main : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(Sun(this))   // the dim words brighter in sunlight
        Moves.start(this)
        // opened again (the home screen, the notification) over ink music's own screens: those stay
        // as they were (the YouTube sign-in, mid-way), not a second library over them
        if (!isTaskRoot) { finish(); return }
        enableEdgeToEdge()
        Lyrics.start(this)
        Moments.start(this)
        Looks.start(this)
        Marquees.start(this)
        Loved.start(this)
        YouTubeMusic.attach(this)
        Server.start(this)
        YouTubeAccount.start(this)
        HiddenLists.load(this)
        Moving.start(this)
        ListenBrainz.start(this)
        LastFm.start(this)
        Offline.start(this)
        Order.start(this)
        Seeds.start(this)
        Stations.curated(this)
        setContent { App() }
        // casting: the volume buttons, while ink music is open, go to the player's session, which turns the speaker
        // (Android gives them to a session only while it plays, and the phone's own player is paused under a speaker)
        lifecycleScope.launch {
            Casting.volume.map { it != null }.distinctUntilChanged().collect { on ->
                mediaController = Playback.token?.takeIf { on }?.let { android.media.session.MediaController(this@Main, it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // your listening read again for the orders (lately, most, this hour: what was played since counts)
        Listened.read(this)
        Colours.start(this)
        Playback.connect(this)
        Order.opened()
        // a paired computer: in step now, and every minute while open
        SyncPhone.start(this)
        SyncPhone.open()
    }

    override fun onStop() {
        SyncPhone.closed()
        Playback.release()
        Order.left()
        super.onStop()
    }
}

/**
 * The library's order: a to z, or shuffled (a setting). Shuffled, it's new each time the app is
 * opened (fresh, or back after 10 minutes away; not back from a moment in another app), and holds
 * still until then: each record, artist and song has its place by the run's seed, so one added
 * meanwhile slots in without moving the rest.
 */
object Order {
    private val _seed = MutableStateFlow(java.util.Random().nextLong())
    val seed: StateFlow<Long> = _seed
    private var away = 0L

    fun start(c: android.content.Context) { Sorts.start(c) }

    /** Shuffle tapped again: a new order. */
    fun reshuffle() { _seed.value = java.util.Random().nextLong() }

    fun left() { away = android.os.SystemClock.elapsedRealtime() }
    fun opened() {
        if (away != 0L && android.os.SystemClock.elapsedRealtime() - away > 10 * 60_000L) _seed.value = java.util.Random().nextLong()
        away = 0L
    }

    /** `list` in the run's order: each by its own key's place (splitmix64 of the key and the seed). */
    fun <T> of(list: List<T>, seed: Long, key: (T) -> String): List<T> = list.sortedBy { item ->
        var z = key(item).hashCode().toLong() xor seed
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z xor (z ushr 31)
    }
}

/** Clear of the status bar and every cutout, upright or sideways (ink's rule 6). */
val bars @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout)

/** A page over the library. */
sealed interface Page {
    /** A record of yours; [tube], YouTube Music's own of it where it's known (the artist page), for the songs you don't have. */
    data class Record(val id: String, val tube: TubeAlbum? = null) : Page
    data class Artist(val name: String) : Page
    data object Find : Page
    data object Settings : Page
    data class Playlist(val id: String, val name: String) : Page
    data class Tube(val album: TubeAlbum) : Page
    data class AddTo(val song: Song) : Page
    data class Path(val song: Song) : Page
    data object Loved : Page
    data object Year : Page
    data object Pick : Page
    data object InkRadio : Page
    data class TubeList(val list: app.ink.music.TubeList) : Page
    data class TubeArtist(val artist: app.ink.music.TubeArtist) : Page
    data class Weekly(val week: Int) : Page
    data object Kept : Page
    data object Import : Page
    data object Equalizer : Page
    data object HandOver : Page
    data class Mood(val mood: TubeMood) : Page
    data class Festival(val name: String) : Page
    data class SettingsPart(val part: String) : Page
}

/** Where the library's pages go: pushed in from the right, back out to the right (the iPod's). */
class Pages {
    val stack = mutableStateListOf<Page>()
    var forward by mutableStateOf(true)
    /** What's typed in find: opened afresh it's empty; come back to it from a result and it's as you left it. */
    var query by mutableStateOf("")
    /** Opened: on top; a page of the browsing already in the stack is gone back to, not stacked again (going in circles). */
    fun open(p: Page) {
        val at = stack.indexOf(p)
        if (at >= 0 && p.browsing()) { forward = false; while (stack.size > at + 1) stack.removeAt(stack.lastIndex); return }
        forward = true; if (p == Page.Find) query = ""; stack.add(p)
    }
    /** Back (Android's): one page. */
    fun back() { forward = false; stack.removeLastOrNull() }
    /**
     * Close (the arrow, or pulled down): the page and the browsing that led to it, artist to record to artist, back to
     * where it began (the library, find, a list; a tester got lost pages deep, closing one at a time). Any other page
     * (settings, add to a playlist) just itself.
     */
    fun close() {
        forward = false
        if (stack.lastOrNull()?.browsing() != true) { stack.removeLastOrNull(); return }
        while (stack.lastOrNull()?.browsing() == true) stack.removeAt(stack.lastIndex)
    }
}

/** A page of browsing: one music leads to another from (an artist, a record, a playlist, YouTube's). */
private fun Page.browsing() = this is Page.Artist || this is Page.TubeArtist || this is Page.Record || this is Page.Tube ||
    this is Page.Playlist || this is Page.TubeList

@Composable
private fun App() {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(Library.allowed(ctx)) }
    val welcomed by Seeds.welcomed.collectAsState()
    LaunchedEffect(allowed) { if (allowed) Library.start(ctx) }
    // what plays found in the library once it's read (its cover, its record)
    val library by Library.songs.collectAsState()
    LaunchedEffect(library) { Playback.refresh() }

    val pages = remember { Pages() }
    var playerUp by rememberSaveable { mutableStateOf(false) }
    BackHandler(playerUp) { playerUp = false }
    BackHandler(pages.stack.isNotEmpty() && !playerUp) { pages.back() }

    Box(Modifier.fillMaxSize().background(Ink.black)) {
        // the first open: ink music before anything is asked
        if (!welcomed) {
            Welcome(onAllowed = { allowed = true }, onDone = {})
            return@Box
        }
        // everything scrolls on under the mini player, which shows it frosted
        val frost = rememberHazeState()
        var foot by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalFoot provides foot) {
            Box(Modifier.fillMaxSize().hazeSource(frost)) {
                // the library stays built under the pages (never thrown away to show one)
                LibraryPage(pages, backHome = pages.stack.isEmpty() && !playerUp)
                androidx.compose.animation.SharedTransitionLayout { val shared = this
                AnimatedContent(
                    targetState = pages.stack.lastOrNull(),
                    transitionSpec = {
                        val f = pages.forward
                        val pageToPage = initialState != null && targetState != null
                        when {
                            // moving less: a page just fades in, and out
                            Moves.less.value -> ContentTransform(fadeIn(tween(Motion.FADE)), fadeOut(tween(Motion.EXIT)))
                            // forward, page over page: the new one comes in on top (its rows then cascade), the old one
                            // stays whole under it till it's covered (both fading showed the library through them)
                            f && pageToPage -> ContentTransform(
                                // solid almost at once (a slower fade crossed its words with the old page's)
                                slideInHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { (it * .12f).toInt() } + fadeIn(tween(90)),
                                fadeOut(tween(1, delayMillis = Motion.ENTER)),
                                targetContentZIndex = 1f,
                            )
                            // back, page from page: the one under is simply there, the one leaving slides off over it
                            !f && pageToPage -> ContentTransform(
                                EnterTransition.None,
                                slideOutHorizontally(tween(Motion.EXIT, easing = Motion.expo)) { (it * .12f).toInt() } + fadeOut(tween(Motion.EXIT)),
                                targetContentZIndex = -1f,
                            )
                            // to or from the library: as before, pushed in from the right and back to the right
                            else -> ContentTransform(
                                slideInHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { if (f) (it * .2f).toInt() else -(it * .1f).toInt() } +
                                    fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
                                slideOutHorizontally(tween(Motion.EXIT, easing = Motion.expo)) { if (f) -(it * .1f).toInt() else (it * .1f).toInt() } +
                                    fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
                            )
                        }
                    },
                    label = "pages",
                ) { p ->
                    val opened = remember(p) { if (pages.forward) android.os.SystemClock.uptimeMillis() else 0L }
                    CompositionLocalProvider(LocalOpened provides opened, LocalShared provides shared, LocalPageScope provides this) {
                    when (p) {
                        null -> Box(Modifier.fillMaxSize())
                        is Page.Record -> RecordPage(p.id, pages, p.tube)
                        is Page.Artist -> ArtistPage(p.name, pages)
                        Page.Find -> FindPage(pages.query, { pages.query = it }, pages)
                        Page.Settings -> SettingsPage(pages)
                        is Page.Playlist -> PlaylistPage(p.id, p.name, pages)
                        is Page.Tube -> TubeAlbumPage(p.album, pages)
                        is Page.AddTo -> AddToPage(p.song, pages)
                        is Page.Path -> PathPage(p.song, pages)
                        Page.Loved -> LovedPage(pages)
                        Page.Year -> YearPage(pages)
                        Page.Pick -> ArtistPicker("YOUR ARTISTS", skip = false) { pages.back() }
                        Page.InkRadio -> InkRadioPage(pages)
                        is Page.TubeList -> TubeListPage(p.list, pages)
                        is Page.TubeArtist -> ArtistPage(p.artist.name, pages, p.artist.id)
                        is Page.Weekly -> WeeklyPage(p.week, pages)
                        Page.Kept -> KeptPage(pages)
                        Page.Import -> ImportPage(pages)
                        Page.Equalizer -> EqualizerPage(pages)
                        Page.HandOver -> HandOverPage(pages)
                        is Page.Mood -> MoodPage(p.mood, pages)
                        is Page.Festival -> FestivalPage(p.name, pages)
                        is Page.SettingsPart -> if (p.part == "look") LookPage(pages) else SettingsPage(pages, p.part)
                    }
                    }
                }
                }
            }
        }
        // stopped, the player sinks all the way off the screen as the music fades (a quarter second), the song and
        // the page going together; closed any other way, the quick fade. The mini player goes at once, under it
        // (left to go by itself it went a beat after the player had: two goings), and is back when something plays
        var stopping by remember { mutableStateOf(false) }
        // only for the sink itself: a second on, it's stopped and the mini player's own rule keeps it away (left till
        // playing was seen to start, a start not seen kept it hidden while a song played)
        LaunchedEffect(stopping) { if (stopping) { delay(1_000); stopping = false } }
        Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { foot = with(density) { it.height.toDp() } }) {
            if (!stopping && !sideways()) MiniPlayer(frost, onOpen = { playerUp = true })
        }
        androidx.compose.animation.AnimatedVisibility(
            playerUp,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = if (stopping) slideOutVertically(tween(STOP_MS, easing = Motion.expo)) { it } + fadeOut(tween(STOP_MS, delayMillis = STOP_MS / 2))
                else slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { PlayerPage(onClose = { playerUp = false }, onOpen = { playerUp = false; pages.open(it) }, onStop = { stopping = true; playerUp = false }) }
    }
}

/**
 * The library: listen first (something to play whatever the phone holds), then records as covers
 * (the only colour), artists or every song as words, and lists; "find" at the row's end. With
 * nothing on the phone or a server, only listen and lists. A swipe across the page goes to the tab
 * beside, the songs' too (there a song's play next and add to queue are held, not swiped); a row of
 * covers scrolling keeps its own.
 */
@Composable
private fun LibraryPage(pages: Pages, backHome: Boolean = false) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val read by Library.read.collectAsState()
    var tab by rememberSaveable { mutableStateOf("listen") }
    // nothing on the phone or a server: no records, artists or songs to show
    // INK: ink weekly, ink radio and everything people picked, one place (explore till 2026-10-29: Bronson)
    val tabs = if (songs.isEmpty()) listOf("listen", "ink", "radio", "lists") else listOf("listen", "ink", "albums", "artists", "songs", "radio", "lists")
    val pager = rememberPagerState(tabs.indexOf(tab).coerceAtLeast(0)) { tabs.size }
    // the places' menu, dropped down from the place's word
    var menu by remember { mutableStateOf(false) }
    // the tab row follows a swipe; a tab tapped goes there on ink's curve
    LaunchedEffect(pager, tabs) { snapshotFlow { pager.currentPage }.collect { tab = tabs.getOrElse(it) { "listen" } } }
    LaunchedEffect(tabs) { pager.scrollToPage(tabs.indexOf(tab).coerceAtLeast(0)) }
    val scope = rememberCoroutineScope()
    // back on another tab goes to listen, as a home; back on listen leaves (a tester's back left the app from albums)
    BackHandler(backHome && pager.currentPage != 0) {
        scope.launch { pager.animateScrollToPage(0, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) }
    }
    val listenList = rememberLazyListState()
    val exploreList = rememberLazyListState()
    val radioGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val grid = rememberLazyGridState()
    val artistList = rememberLazyListState()
    val listsList = rememberLazyListState()
    val songList = rememberLazyListState()
    val seed by Order.seed.collectAsState()
    // each tab in its own order (Sorting.kt): a to z, by year, lately, most, forgotten, this hour, by colour, shuffled
    val chosen by Sorts.chosen.collectAsState()
    val heard by Listened.now.collectAsState()
    val colours by Colours.known.collectAsState()
    val ac = chosen["artists"] ?: Sorts.Chosen()
    val rc = chosen["albums"] ?: Sorts.Chosen()
    val sc = chosen["songs"] ?: Sorts.Chosen()
    val now = remember(chosen, heard) { System.currentTimeMillis() }
    val minute = remember(chosen, heard) { Listening.minuteOfWeek(now) }
    val artists = remember(albums, songs, ac, seed, heard) {
        Sorts.order(artistsOf(albums, songs), ac, seed, { it.name }) { a ->
            val k = "a:" + a.name.lowercase()
            when (ac.sort) { Sort.MOST -> heard.most(k); Sort.LATELY -> heard.lately(k); Sort.FORGOTTEN -> heard.forgotten(k, now); else -> null }
        }
    }
    // the artists you have most songs by, read ahead (wifi, once a day), so a first visit opens at once
    val ctx = LocalContext.current
    LaunchedEffect(albums, songs) {
        if (songs.isNotEmpty()) YouTubeAccount.warmLibrary(ctx, artistsOf(albums, songs).sortedByDescending { it.songs }.take(40).map { it.name })
    }
    // the colour order asks for the colours it doesn't know yet (worked out in the background, kept)
    LaunchedEffect(rc.sort, albums) { if (rc.sort == Sort.COLOUR) Colours.fill(ctx, albums) }
    val hiddenAlbums by Library.hiddenAlbums.collectAsState()
    var showHiddenRecords by remember { mutableStateOf(false) }
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
    val everySong = remember(songs, sc, seed, heard, minute) {
        Sorts.order(songs, sc, seed, { it.id }) { s ->
            when (sc.sort) {
                Sort.HOUR -> heard.hour(s.id, minute); Sort.LATELY -> heard.lately(s.id); Sort.MOST -> heard.most(s.id)
                Sort.FORGOTTEN -> heard.forgotten(s.id, now); else -> null
            }
        }
    }
    // a new order starts at the top
    LaunchedEffect(ac, seed) { artistList.scrollToItem(0) }
    LaunchedEffect(rc, seed) { grid.scrollToItem(0) }
    LaunchedEffect(sc, seed) { songList.scrollToItem(0) }
    val tabsFrost = rememberHazeState()
    var head by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val foot = LocalFoot.current
    val sides = bars.only(WindowInsetsSides.Horizontal)
    // the lists start under the tab row (not under its band) and leave room to scroll under the mini player
    val pad = PaddingValues(start = 20.dp, end = 20.dp, top = (head - BAND).coerceAtLeast(0.dp) + 6.dp, bottom = foot + 24.dp)
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().hazeSource(tabsFrost).windowInsetsPadding(sides)) {
      HorizontalPager(
        pager, Modifier.fillMaxSize(), key = { tabs[it] },
        flingBehavior = stillOnItsPage(pager, PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(Motion.ENTER, easing = Motion.expo))),
      ) { page ->
       // what scrolls sideways inside a page (a row of covers) keeps its scroll: run to its end, it doesn't turn the page
       Box(Modifier.fillMaxSize().nestedScroll(keepSideways)) {
        when (tabs[page]) {
            "listen" -> ListenPage(pages, pad, listenList)
            "ink" -> ExplorePage(pages, pad, exploreList)
            // radio, a place of its own: the tower, what's on, every station
            "radio" -> RadioPlace(pages, pad, radioGrid)
            "albums" -> LazyVerticalGrid(
                GridCells.Adaptive(150.dp), state = grid, contentPadding = pad,
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "sort") { SortRow("albums", Sorts.RECORDS) }
                if (rc.sort == Sort.YEAR) {
                    // by year: the year big where each begins, a timeline down the page
                    var at = 0
                    while (at < records.size) {
                        val year = records[at].year
                        var end = at
                        while (end < records.size && records[end].year == year) end++
                        val run = records.subList(at, end)
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "year$year") {
                            BasicText(if (year > 0) "$year" else "no year", style = headline(28.sp), modifier = Modifier.padding(top = 6.dp))
                        }
                        items(run, key = { it.id }) { a -> RecordTile(a, Modifier.fillMaxWidth()) { pages.open(Page.Record(a.id)) } }
                        at = end
                    }
                } else items(records, key = { it.id }) { a -> RecordTile(a, Modifier.fillMaxWidth()) { pages.open(Page.Record(a.id)) } }
                // the records you've hidden: a dim word at the foot, then them, dim, once asked for (#1)
                val hiddenRecords = hiddenAlbums
                if (hiddenRecords.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "hidden") {
                    Word(if (showHiddenRecords) "hide them again" else "${hiddenRecords.size} hidden", pixel(12.sp, Ink.dim), Modifier.padding(vertical = 12.dp), touch = 8.dp) {
                        showHiddenRecords = !showHiddenRecords
                    }
                }
                if (showHiddenRecords) items(hiddenRecords, key = { "h" + it.id }) { a ->
                    Box(Modifier.alpha(0.5f)) { RecordTile(a, Modifier.fillMaxWidth()) { pages.open(Page.Record(a.id)) } }
                }
            }
            "artists" -> LazyColumn(state = artistList, contentPadding = pad) {
                item(key = "sort") { SortRow("artists", Sorts.ARTISTS) }
                items(artists, key = { it.name }) { a -> ArtistRow(a) { pages.open(Page.Artist(a.name)) } }
            }
            // lists: the songs you love, your year, then your playlists (and "new >" for one more)
            "lists" -> {
                val lists by Server.playlists.collectAsState()
                val tubeLists by YouTubeAccount.playlists.collectAsState()
                val hidden by HiddenLists.ids.collectAsState()
                var showHidden by remember { mutableStateOf(false) }
                val lovedIds by Loved.ids.collectAsState()
                val account by Server.account.collectAsState()
                val ctx = LocalContext.current
                val minutes by androidx.compose.runtime.produceState<Long?>(null) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Year.of(History.read(ctx)).minutes }
                }
                LazyColumn(state = listsList, contentPadding = pad) {
                    item {
                        if (lovedIds.isNotEmpty()) ListRow("loved", "${lovedIds.size} ${if (lovedIds.size == 1) "song" else "songs"}") { pages.open(Page.Loved) }
                        ListRow("your year", minutes?.let { if (it > 0) "%,d minutes so far".format(it) else "starts now" } ?: "") { pages.open(Page.Year) }
                        // what's on the phone to play with no signal (your server's, YouTube's)
                        val keptIds by Offline.kept.collectAsState()
                        if (keptIds.isNotEmpty()) ListRow("kept on this phone", "${keptIds.size} ${if (keptIds.size == 1) "song" else "songs"}, offline") { pages.open(Page.Kept) }
                        Spacer(Modifier.height(10.dp))
                        if (account != null) { NewPlaylist(null) {}; Spacer(Modifier.height(10.dp)) }
                    }
                    items(lists, key = { it.id }) { pl ->
                        Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                            onClick = felt { pages.open(Page.Playlist(pl.id, pl.name)) }).padding(vertical = 9.dp)) {
                            BasicText(pl.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            BasicText("${pl.count} ${if (pl.count == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
                        }
                    }
                    // your youtube music: its playlists, liked music first as YouTube lists them (read only); the ones
                    // you've hidden after the rest, dim, only once asked for (#1)
                    items(tubeLists.filter { it.id !in hidden }, key = { "y" + it.id }) { pl ->
                        ListRow(pl.name, listOf("youtube music", pl.said.lowercase()).filter { it.isNotEmpty() }.joinToString("   ")) {
                            pages.open(Page.TubeList(pl))
                        }
                    }
                    val hiddenLists = tubeLists.filter { it.id in hidden }
                    if (hiddenLists.isNotEmpty()) item {
                        Word(if (showHidden) "hide them again" else "${hiddenLists.size} hidden", pixel(12.sp, Ink.dim), Modifier.padding(vertical = 12.dp), touch = 8.dp) { showHidden = !showHidden }
                    }
                    if (showHidden) items(hiddenLists, key = { "yh" + it.id }) { pl ->
                        Box(Modifier.alpha(0.5f)) { ListRow(pl.name, "hidden   youtube music") { pages.open(Page.TubeList(pl)) } }
                    }
                }
            }
            else -> LazyColumn(state = songList, contentPadding = pad) {
                item(key = "sort") { SortRow("songs", Sorts.SONGS) }
                itemsIndexed(everySong, key = { _, s -> s.id }) { i, s -> SongRow(s, swipe = false, onClick = { Playback.play(everySong, i) }) }
            }
        }
       }
      }
    }
    // the tab row on black; what scrolls up toward it softens just under it
    Column(Modifier.fillMaxWidth().onSizeChanged { head = with(density) { it.height.toDp() } }) {
      Column(Modifier.fillMaxWidth().background(Ink.black).windowInsetsPadding(bars)) {
        // the place you're on, one big word (Bronson, 2026-10-28: the row of words got crowded at six); a tap drops
        // every place down as big words, ink's menu (no hamburger: a word where an icon would go). Find stays put.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { menu = !menu }),
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.animation.AnimatedContent(tab, label = "place",
                    transitionSpec = { androidx.compose.animation.fadeIn(tween(Motion.ENTER, easing = Motion.expo)) togetherWith androidx.compose.animation.fadeOut(tween(Motion.EXIT)) },
                ) { t -> BasicText(t.uppercase(), style = headline(26.sp)) }
                Spacer(Modifier.width(10.dp))
                // the menu's chevron: down while shut, up while open
                val turn by androidx.compose.animation.core.animateFloatAsState(if (menu) 180f else 0f, tween(Motion.ENTER, easing = Motion.expo), label = "chevron")
                Sprite(listOf("##...##", ".##.##.", "..###..", "...#..."), Ink.mid, 2.dp, Modifier.graphicsLayer { rotationZ = turn })
            }
            Word("find", pixel(14.sp, Ink.mid), touch = 8.dp) { menu = false; pages.open(Page.Find) }
        }
      }
      Box(Modifier.fillMaxWidth().height(BAND).softEdge(tabsFrost, towardBottom = false))
    }
    // the places, dropped down under the word: big, the one you're on lit, each arriving in turn; a tap goes there (on
    // ink's curve, as a swipe would), a tap anywhere else or back folds it away. Black, never a frosted panel.
    BackHandler(menu) { menu = false }
    androidx.compose.animation.AnimatedVisibility(
        menu, modifier = Modifier.padding(top = (head - BAND).coerceAtLeast(0.dp)),
        enter = androidx.compose.animation.expandVertically(tween(Motion.ENTER, easing = Motion.expo), expandFrom = Alignment.Top) +
            androidx.compose.animation.fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
        exit = androidx.compose.animation.shrinkVertically(tween(Motion.EXIT, easing = Motion.expo), shrinkTowards = Alignment.Top) +
            androidx.compose.animation.fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
    ) {
        Column(
            Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) { menu = false }
                .windowInsetsPadding(sides).padding(start = 20.dp, end = 20.dp, top = 10.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                Word(t.uppercase(), headline(48.sp, if (t == tab) Ink.ink else Ink.mid), Modifier.arrive(i, menu), touch = 4.dp) {
                    menu = false
                    scope.launch { pager.animateScrollToPage(i, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Word("settings", pixel(14.sp, Ink.mid), Modifier.arrive(tabs.size, menu), touch = 8.dp) { menu = false; pages.open(Page.Settings) }
            Word("your year", pixel(14.sp, Ink.mid), Modifier.arrive(tabs.size + 1, menu), touch = 8.dp) { menu = false; pages.open(Page.Year) }
            // made by ink, its heart (it beats; seven taps and hearts float up), ink its website: the quiet funnel
            Row(Modifier.arrive(tabs.size + 2, menu).padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                val ctx = LocalContext.current
                BasicText("made by ", style = pixel(12.sp, Ink.dim))
                Word("ink", pixel(12.sp, Ink.mid), touch = 8.dp) {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://inkhome.app")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                Spacer(Modifier.width(8.dp))
                InkHeart(Ink.dim)
            }
        }
    }
    // a swipe to another place folds the menu away
    LaunchedEffect(tab) { menu = false }
    // the records, with nothing open over them, follow the phone round (ink music is upright everywhere else, and
    // Android's rotation lock is kept): turned sideways they're the covers to flick through, the whole screen;
    // upright again, the grid as it was
    val activity = LocalContext.current as? android.app.Activity
    val onRecords = tab == "albums" && backHome
    androidx.compose.runtime.DisposableEffect(onRecords) {
        activity?.requestedOrientation = if (onRecords) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    if (onRecords && sideways()) RecordFlow(records) { pages.open(Page.Record(it.id)) }
    }
}

/** The phone turned sideways (only the records can be: [RecordFlow]). */
@Composable
fun sideways() = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

/**
 * The pager's fling, but none when it's still and on its page. A row of covers flung to its end hands the
 * pager what's left (nothing, `keepSideways` took it), and the pager still ran its snap, moving nowhere for
 * a quarter second: "scrolling", so the next thumb on a row caught the pager instead (seen on the S26,
 * 2026-10-26: quick flicks along a row slid the page toward the next tab).
 */
@Composable
private fun stillOnItsPage(pager: androidx.compose.foundation.pager.PagerState, snap: androidx.compose.foundation.gestures.TargetedFlingBehavior) =
    remember(pager, snap) {
        object : androidx.compose.foundation.gestures.TargetedFlingBehavior {
            override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(
                initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit,
            ): Float =
                if (initialVelocity == 0f && pager.currentPageOffsetFraction == 0f) 0f
                else with(snap) { performFling(initialVelocity, onRemainingDistanceUpdated) }
        }
    }

/** Takes what a page's sideways scrollers leave over, so running one to its end never turns the library's page. */
private val keepSideways = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(available.x, 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(available.x, 0f)
}

/** An artist as the library lists them: their name, how many records and songs. */
data class ArtistEntry(val name: String, val records: Int, val songs: Int)

/** Every artist: whose records they are, and who sings (each of "a, b" counts). */
fun artistsOf(albums: List<Album>, songs: List<Song>): List<ArtistEntry> {
    val records = albums.groupingBy { it.artist }.eachCount()
    // their songs: the ones they sing, and those on their records (a label filed as the album
    // artist sings none, and read "0 songs")
    val theirs = HashMap<String, MutableSet<String>>()
    songs.forEach { s -> (s.artist.split(", ") + s.albumArtist).forEach { theirs.getOrPut(it) { HashSet() } += s.id } }
    return (records.keys + theirs.keys).filter { it != "unknown" }.distinct()
        .map { ArtistEntry(it, records[it] ?: 0, theirs[it]?.size ?: 0) }
        .sortedBy { Library.sortName(it.name) }
}

@Composable
private fun ArtistRow(a: ArtistEntry, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val ctx = LocalContext.current
    // their page read from the finger landing, not from the page opening
    LaunchedEffect(src, a.name) {
        src.interactions.collect { if (it is androidx.compose.foundation.interaction.PressInteraction.Press) YouTubeAccount.warmNow(ctx, a.name) }
    }
    Column(Modifier.fillMaxWidth().clickable(src, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp)) {
        BasicText(a.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(
            // none of theirs here: one YouTube Music has (find)
            if (a.records == 0 && a.songs == 0) "on youtube music"
            else listOfNotNull(a.records.takeIf { it > 0 }?.let { "$it ${if (it == 1) "record" else "records"}" }, "${a.songs} ${if (a.songs == 1) "song" else "songs"}").joinToString("   "),
            style = pixel(12.sp, Ink.mid),
        )
    }
}

/**
 * A tile's words under its cover: the title (two lines at most) and a word under it, given a two-line
 * title's room whatever this one's is, so a row of covers keeps one height as it slides (a tile with a
 * one-line title beside one with two moved the rows under it up and down, 2026-10-26).
 */
@Composable
fun UnderCover(title: String, said: String, saidStyle: TextStyle, gap: Dp = 0.dp) {
    Box {
        Column(Modifier.alpha(0f)) {
            BasicText("I\nI", style = headline(15.sp))
            Spacer(Modifier.height(gap))
            BasicText("i", style = saidStyle)
        }
        Column {
            BasicText(title.uppercase(), style = headline(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(gap))
            BasicText(said.lowercase(), style = saidStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun RecordTile(a: Album, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(a.songs.first(), a.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        UnderCover(a.title, a.artist, pixel(12.sp, Ink.mid), gap = 3.dp)
    }
}

/**
 * A song as words. A tap plays (from there, in its list); held, its options open under it:
 * play next, add to queue, radio (it, then songs like it), love, add to a playlist (a song the server has), a path from it to
 * another (a server that finds them), its record, its artist;
 * in a playlist of yours, remove. Swiped right it plays next, left it goes to
 * the queue's end: the word shows under it as it slides, lit (and ticked) once far enough to count.
 * Not where a swipe turns the page (the library's songs): there, held is the way.
 */
@Composable
private fun SongRow(s: Song, number: String? = null, pages: Pages? = null, onRemove: (() -> Unit)? = null, swipe: Boolean = true, yours: Boolean = true, onClick: () -> Unit) {
    val now by Playback.now.collectAsState()
    val on = now?.song?.id == s.id
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val tap = felt(then = onClick)
    val scope = rememberCoroutineScope()
    val slid = remember { Animatable(0f) }
    var armed by remember { mutableIntStateOf(0) }    // 1: play next, -1: to the queue's end
    var done by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(done) { if (done != null) { delay(1_400); done = null } }
    val reach = with(LocalDensity.current) { SWIPE.toPx() }
    Box(Modifier.fillMaxWidth()) {
        if (slid.value != 0f) Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            if (slid.value > 0) BasicText("play next", style = pixel(13.sp, if (armed == 1) Ink.ink else Ink.dim))
            Spacer(Modifier.weight(1f))
            if (slid.value < 0) BasicText("add to queue", style = pixel(13.sp, if (armed == -1) Ink.ink else Ink.dim))
        }
    Column(
        Modifier.fillMaxWidth().offset { IntOffset(slid.value.roundToInt(), 0) }.background(Ink.black).pointerInput(s.id) {
            detectTapGestures(
                onTap = { if (open) open = false else tap() },
                onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open = !open },
            )
        }.then(if (!swipe) Modifier else Modifier.pointerInput(s.id) {
            var pulled = 0f
            fun letGo(act: Boolean) {
                if (act) when (armed) {
                    1 -> { Playback.playNext(s); done = "next" }
                    -1 -> { Playback.add(s); done = "queued" }
                }
                armed = 0; pulled = 0f
                scope.launch { slid.animateTo(0f, tween(Motion.EXIT, easing = Motion.expo)) }
            }
            detectHorizontalDragGestures(onDragEnd = { letGo(true) }, onDragCancel = { letGo(false) }) { change, dx ->
                change.consume()
                pulled += dx
                // past the reach it slows, as if held
                val past = (kotlin.math.abs(pulled) - reach).coerceAtLeast(0f)
                scope.launch { slid.snapTo(kotlin.math.sign(pulled) * (kotlin.math.abs(pulled) - past * .7f)) }
                val at = when { pulled > reach -> 1; pulled < -reach -> -1; else -> 0 }
                if (at != armed) { armed = at; if (at != 0) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
            }
        }).padding(vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            number?.let { BasicText(it, style = pixel(12.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.width(30.dp).padding(bottom = 3.dp)) }
            Column(Modifier.weight(1f)) {
                BasicText(s.title.uppercase(), style = headline(19.sp, when { on -> Ink.ink; !yours -> if (now == null) Ink.mid else Ink.dim; now == null -> Ink.ink; else -> Ink.mid }), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (number == null) BasicText(s.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // a swipe's word stays a moment where the length was: it's done
            done?.let { BasicText(it, style = pixel(12.sp), modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)) }
                ?: if (s.duration > 0) BasicText(clock(s.duration), style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)) else Unit
        }
        if (open) FlowRow(Modifier.padding(start = if (number != null) 30.dp else 0.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Word("play next", pixel(13.sp), touch = 8.dp) { open = false; Playback.playNext(s) }
            Word("add to queue", pixel(13.sp), touch = 8.dp) { open = false; Playback.add(s) }
            Word("radio", pixel(13.sp), touch = 8.dp) { open = false; Playback.startRadio(s) }
            val lovedIds by Loved.ids.collectAsState()
            Word(if (s.id in lovedIds) "unlove" else "love", pixel(13.sp), touch = 8.dp) { open = false; Loved.toggle(s) }
            val account by Server.account.collectAsState()
            val tubeWho by YouTubeAccount.who.collectAsState()
            if (pages != null && ((account != null && Server.idOf(s) != null) || (tubeWho != null && !s.station)))
                Word("add to playlist", pixel(13.sp), touch = 8.dp) { open = false; pages.open(Page.AddTo(s)) }
            val sonic by Server.sonic.collectAsState()
            if (pages != null && sonic && account != null && Server.idOf(s) != null)
                Word("path to", pixel(13.sp), touch = 8.dp) { open = false; pages.open(Page.Path(s)) }
            if (Offline.keepable(s)) {
                val kept by Offline.kept.collectAsState()
                val ctx2 = LocalContext.current
                if (s.id in kept) Word("not offline", pixel(13.sp, Ink.mid), touch = 8.dp) { open = false; Offline.remove(ctx2, listOf(s.id)) }
                else Word("keep offline", pixel(13.sp), touch = 8.dp) { open = false; Offline.keep(ctx2, listOf(s)) }
            }
            onRemove?.let { Word("remove", pixel(13.sp), touch = 8.dp) { open = false; it() } }
            if (pages != null && number == null && s.album.isNotEmpty() && !s.tube) Word("record", pixel(13.sp, Ink.mid), touch = 8.dp) { open = false; pages.open(Page.Record(s.albumId)) }
            if (pages != null) Word("artist", pixel(13.sp, Ink.mid), touch = 8.dp) { open = false; pages.open(Page.Artist(s.albumArtist)) }
        }
    }}
}

/** A record's page: its cover, its name, its songs as words; a tap plays from there. */
@Composable
private fun RecordPage(id: String, pages: Pages, tube: TubeAlbum? = null) {
    val albums by Library.albums.collectAsState()
    val hiddenAlbums by Library.hiddenAlbums.collectAsState()
    val off = albums.none { it.id == id }
    val a = albums.firstOrNull { it.id == id } ?: hiddenAlbums.firstOrNull { it.id == id } ?: return
    // the whole record, where YouTube Music has it: your songs in their places (they play from the phone or the
    // server), the ones you don't have from YouTube; a tester had one song of a record and saw only that
    val whole by androidx.compose.runtime.produceState(a.songs, a) {
        if (!YouTubeMusic.available || a.songs.any { it.tube }) return@produceState
        val record = tube ?: runCatching { YouTubeMusic.search("${a.artist} ${a.title}").albums }.getOrDefault(emptyList())
            .firstOrNull { plainName(it.title) == plainName(a.title) && plainName(it.artist).contains(plainName(a.artist).take(12)) }
            ?: return@produceState
        val tracks = runCatching { YouTubeMusic.album(record) }.getOrDefault(emptyList())
        if (tracks.size <= a.songs.size) return@produceState
        val mine = a.songs.toMutableList()
        val placed = tracks.map { t -> mine.firstOrNull { plainName(it.title) == plainName(t.title) }?.also { mine.remove(it) } ?: t }
        value = placed + mine   // any of yours YouTube's hasn't (a bonus track), after
    }
    val yours = remember(a) { a.songs.map { it.id }.toSet() }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            BoxWithConstraints(Modifier.bleed(20.dp), contentAlignment = Alignment.Center) {
                CoverOf(a.songs.first(), a.title, Modifier.size(minOf(maxWidth, 560.dp)))
            }
            Spacer(Modifier.height(18.dp))
            BasicText(a.title.uppercase(), style = headline(36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Word(a.artist.lowercase(), pixel(13.sp, Ink.mid), touch = 6.dp) { pages.open(Page.Artist(a.artist)) }
            Spacer(Modifier.height(4.dp))
            BasicText(
                listOfNotNull(a.year.takeIf { it > 0 }?.toString(), "${whole.size} ${if (whole.size == 1) "song" else "songs"}",
                    "${a.songs.size} yours".takeIf { whole.size > a.songs.size },
                    whole.sumOf { it.duration }.takeIf { it > 0 }?.let { "${(it / 60_000).coerceAtLeast(1)} min" }).joinToString("   "),
                style = pixel(12.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(whole, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(whole.shuffled(), 0) }
                KeepOffline(a.songs)
            }
            // out of ink music, the files untouched (#1); a hidden one's page says so and brings it back
            val ctx = LocalContext.current
            Word(if (off) "hidden: show it again" else "hide", pixel(12.sp, Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) {
                Library.hide(ctx, a.id, !off); if (!off) pages.back()
            }
            Spacer(Modifier.height(14.dp))
        }}
        itemsIndexed(whole, key = { _, s -> s.id }) { i, s -> Box(Modifier.cascade(i + 1)) {
            // numbered by place on the whole record; a song you don't have a step dimmer
            val number = if (whole.size > a.songs.size) "${i + 1}" else s.track.takeIf { it > 0 }?.toString() ?: "${i + 1}"
            SongRow(s, number = number, pages = pages, yours = s.id in yours) { Playback.play(whole, i) }
        }}
    } }
}

/** A row in "lists": its name big, a word on it small. */
@Composable
private fun ListRow(name: String, said: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp)) {
        BasicText(name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (said.isNotEmpty()) BasicText(said, style = pixel(12.sp, Ink.mid))
    }
}

/**
 * The songs you love: play and shuffle; the latest loved first (a tester's ask, 2026-10-26), or a to z.
 * Latest is by when each was loved where that's known (here, or a star's date on the server); the rest
 * after, YouTube's likes in YouTube's own order (its newest first).
 */
@Composable
private fun LovedPage(pages: Pages) {
    val songs by Library.songs.collectAsState()
    val lovedIds by Loved.ids.collectAsState()
    val tube by Loved.tube.collectAsState()
    val liked by YouTubeAccount.liked.collectAsState()
    val recent by Loved.recent.collectAsState()
    val theirs = remember(songs, lovedIds, tube, liked, recent) {
        val all = (liked + tube + songs.filter { it.id in lovedIds }).distinctBy { it.id }
        if (recent) all.sortedByDescending { Loved.lovedAt(it) ?: Long.MIN_VALUE }   // stable: the undated keep their order
        else all.sortedBy { Library.sortName(it.title) }
    }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            BasicText("LOVED", style = headline(44.sp))
            Spacer(Modifier.height(8.dp))
            BasicText("${theirs.size} ${if (theirs.size == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
            if (theirs.isNotEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(theirs, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.play(theirs.shuffled(), 0) }
                Spacer(Modifier.weight(1f))
                // the order: the one chosen lit
                Word("latest", pixel(14.sp, if (recent) Ink.ink else Ink.dim), Modifier.padding(end = 20.dp), touch = 8.dp) { Loved.order(true) }
                Word("a to z", pixel(14.sp, if (recent) Ink.dim else Ink.ink), touch = 8.dp) { Loved.order(false) }
            }
            Spacer(Modifier.height(14.dp))
        }}
        itemsIndexed(theirs, key = { _, s -> s.id }) { i, s -> Box(Modifier.cascade(i + 1)) { SongRow(s, pages = pages) { Playback.play(theirs, i) } }}
    } }
}

/** A playlist's page: its name big, play and shuffle, its songs in its order. */
@Composable
private fun PlaylistPage(id: String, name: String, pages: Pages) {
    val edited by Server.edited.collectAsState()
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(id, edited) { songs = Server.playlist(id) }
    val lists by Server.playlists.collectAsState()
    val mine = lists.firstOrNull { it.id == id }?.mine == true
    val scope = rememberCoroutineScope()
    var sure by remember { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(3_000); sure = false } }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            BasicText(name.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            val all = songs
            BasicText(
                if (all == null) "reading it" else listOfNotNull("${all.size} ${if (all.size == 1) "song" else "songs"}",
                    all.sumOf { it.duration }.takeIf { it > 0 }?.let { "${it / 60_000} min" }).joinToString("   "),
                style = pixel(12.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
                KeepOffline(all)
            }
            // deleting asks once more: the word says so, lit, for a moment
            if (mine) Word(if (sure) "delete it" else "delete", pixel(12.sp, if (sure) Ink.ink else Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) {
                if (!sure) sure = true else scope.launch { if (Server.deletePlaylist(id)) pages.back() }
            }
            Spacer(Modifier.height(14.dp))
        }}
        val all = songs.orEmpty()
        // no keys: a playlist can hold one song twice
        itemsIndexed(all) { i, s -> Box(Modifier.cascade(i + 1)) {
            SongRow(s, pages = pages, onRemove = if (mine) ({
                songs = all.filterIndexed { j, _ -> j != i }    // gone at once; the server's answer reads it again
                scope.launch { Server.removeFrom(id, i) }
            }) else null) { Playback.play(all, i) }
        }}
    } }
}

/** An artist's page: the name big, their records as covers, every song they're on. */
@Composable
private fun ArtistPage(name: String, pages: Pages, tubeId: String = "") {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val records = remember(albums, name) { albums.filter { it.artist.equals(name, ignoreCase = true) } }
    val theirs = remember(songs, name) { songs.filter { s -> s.artist.split(", ").any { it.equals(name, ignoreCase = true) } || s.albumArtist.equals(name, ignoreCase = true) } }
    // YouTube Music's page for them (the full build): kept from last time at once, then read again
    val info by androidx.compose.runtime.produceState<ArtistInfo?>(null, name, tubeId) {
        YouTubeAccount.artist(name, tubeId).collect { if (it != null || value == null) value = it }
    }
    val i = info
    // who fans also like, read ahead (wifi), so the next tap opens at once
    val ctx = LocalContext.current
    LaunchedEffect(i?.id) { i?.like?.take(5)?.forEach { YouTubeAccount.warm(ctx, it.name, it.id) } }
    // their best-known songs, the library's own copy where there is one (it plays from the phone or the server)
    val top = remember(i, theirs) {
        i?.top.orEmpty().take(5).map { t -> theirs.firstOrNull { it.title.equals(t.title, ignoreCase = true) } ?: t }
    }
    val playable = remember(top, theirs) { (top + theirs).distinctBy { it.id } }
    LaunchedEffect(playable.firstOrNull()?.id) { Playback.warm(playable.firstOrNull()) }
    // their records: yours first, then YouTube's that you don't have
    val tubeAlbums = remember(i, records) { i?.albums.orEmpty().filter { t -> records.none { it.title.equals(t.title, ignoreCase = true) } } }
    var aboutOpen by remember(name) { mutableStateOf(false) }
    val list = rememberLazyListState()
    // opened the first time (nothing kept): the photo's room held and the rest kept back till YouTube Music answers, a
    // second and a half at most, so it comes in once (it showed your songs, then pushed them down under all of it)
    var patient by remember(name, tubeId) { mutableStateOf(true) }
    LaunchedEffect(name, tubeId) { delay(1_500); patient = false }
    val waiting = YouTubeAccount.available && info == null && patient
    FrostedPage(onClose = { pages.close() }) { frost, top0 -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top0, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            // their photo across the page in the dither (the player's), developing as it opens, their name on it
            val photo = i?.photo
            if (photo != null || waiting) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                val side = minOf(maxWidth, 560.dp)
                if (photo != null) Dithered(remember(photo) { photoSong(name, photo) }, Modifier.size(side).align(Alignment.Center))
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
                    ).joinToString("   ").ifEmpty { if (info == null && YouTubeAccount.available) "reading it from youtube music" else "" },
                    style = pixel(12.sp, Ink.mid),
                )
                Spacer(Modifier.height(16.dp))
                if (playable.isNotEmpty()) Row {
                    Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(playable, 0) }
                    Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(playable.shuffled(), 0) }
                    Word("radio", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.startRadio(playable.first()) }
                    // signed in: followed there (lit), or not
                    val who by YouTubeAccount.who.collectAsState()
                    val followed by YouTubeAccount.followed.collectAsState()
                    val page = i
                    if (who != null && page != null && page.id.isNotEmpty()) {
                        val on = page.id in followed
                        val scope = rememberCoroutineScope()
                        Word(if (on) "following" else "follow", pixel(14.sp, if (on) Ink.ink else Ink.mid), touch = 8.dp) {
                            scope.launch { YouTubeAccount.follow(TubeArtist(page.name, page.photo, page.id), !on) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }}
        if (waiting) return@LazyColumn
        // the songs of theirs you have (the phone, your server) first, above all YouTube has: they're why you came
        if (theirs.isNotEmpty()) {
            item { Label("yours") }
            itemsIndexed(theirs, key = { _, s -> s.id }) { n, s -> Box(Modifier.cascade(n + 1)) {
                Box(Modifier.padding(horizontal = 20.dp)) { SongRow(s, pages = pages) { Playback.play(theirs, n) } }
            }}
            item { Spacer(Modifier.height(18.dp)) }
        }
        if (top.isNotEmpty()) {
            item { Label("top songs") }
            // a chart: the place big beside each
            itemsIndexed(top, key = { _, s -> "t" + s.id }) { n, s -> Box(Modifier.cascade(n + 1)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("${n + 1}", style = headline(34.sp), modifier = Modifier.width(48.dp))
                    Box(Modifier.weight(1f)) { SongRow(s, pages = pages) { Playback.play(top, n) } }
                }
            }}
            item { Spacer(Modifier.height(10.dp)) }
        }
        if (records.isNotEmpty() || tubeAlbums.isNotEmpty()) item {
            Label("records")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(records, key = { it.id }) { a -> RecordTile(a, Modifier.width(150.dp)) {
                    pages.open(Page.Record(a.id, (i?.albums.orEmpty() + i?.singles.orEmpty()).firstOrNull { it.title.equals(a.title, ignoreCase = true) }))
                } }
                items(tubeAlbums, key = { it.url }) { r -> TubeTile(r, Modifier.width(150.dp)) { pages.open(Page.Tube(r)) } }
            }
            Spacer(Modifier.height(18.dp))
        }
        i?.singles?.takeIf { it.isNotEmpty() }?.let { singles ->
            item {
                Label("singles and eps")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(singles, key = { it.url }) { r -> TubeTile(r, Modifier.width(130.dp)) { pages.open(Page.Tube(r)) } }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        // what's said of them: four lines, a tap for the rest
        i?.about?.takeIf { it.isNotEmpty() }?.let { about ->
            // its first sentence pulled out big, as a lyric is; the rest small under it
            item {
                val (said, rest) = remember(about) { pullQuote(about) }
                Label("about")
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
                Label("fans also like")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(like, key = { it.id.ifEmpty { it.name } }) { a ->
                        Column(Modifier.width(130.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                            onClick = felt { pages.open(Page.TubeArtist(a)) })) {
                            CoverOf(remember(a.face) { a.face?.let { faceSong(a.name, it) } }, a.name, Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(8.dp))
                            UnderCover(a.name, "artist", pixel(12.sp, Ink.mid))
                        }
                    }
                }
            }
        }
    } }
}

/** The songs kept on the phone (your server's, YouTube Music's), to play with no signal; each let go from its options. */
@Composable
private fun KeptPage(pages: Pages) {
    val ctx = LocalContext.current
    val kept by Offline.kept.collectAsState()
    val songs = remember(kept) { kept.mapNotNull { Library.song(it) } + Offline.tubeSongs(ctx).filter { it.id in kept } }
        .let { l -> remember(l) { l.distinctBy { it.id }.sortedBy { Library.sortName(it.artist) + it.album + it.title } } }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                BasicText("KEPT ON THIS PHONE", style = headline(36.sp), maxLines = 2)
                Spacer(Modifier.height(6.dp))
                BasicText("${songs.size} songs, to play with no signal · %.1f gb".format(java.util.Locale.ROOT, Offline.bytes(ctx) / 1e9), style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(14.dp))
                if (songs.isNotEmpty()) Row {
                    Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(songs, 0) }
                    Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.play(songs.shuffled(), 0) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> Box(Modifier.padding(horizontal = 20.dp)) { SongRow(s, pages = pages) { Playback.play(songs, i) } } }
    } }
}

/** Four of a week's covers in the dither, two by two, edge to edge: ink weekly's own cover. */
@Composable
fun Mosaic(songs: List<Song>, modifier: Modifier) {
    BoxWithConstraints(modifier.background(Ink.black)) {
        val cell = maxWidth / 2
        val px = with(LocalDensity.current) { cell.roundToPx() }
        val four = songs.distinctBy { it.art ?: it.albumId }.take(4)
        Column {
            for (r in 0..1) Row {
                for (c in 0..1) four.getOrNull(r * 2 + c)?.let { Dithered(it, Modifier.size(cell), px = px) } ?: Spacer(Modifier.size(cell))
            }
        }
    }
}

/** A week of ink weekly: its mosaic across the page, its name and note, its songs as a chart, the weeks before. */
@Composable
private fun WeeklyPage(number: Int, pages: Pages) {
    val weeks by Weekly.weeks.collectAsState()
    val w = weeks.firstOrNull { it.week == number } ?: weeks.firstOrNull() ?: return
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            // its name on its covers, on a black tab in their corner (a sleeve's label: pale covers would swallow it)
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                Mosaic(w.songs, Modifier.size(minOf(maxWidth, 560.dp)).align(Alignment.Center))
                BasicText(w.title.uppercase(), style = headline(56.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(Ink.black).padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 0.dp))
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(14.dp))
                BasicText(listOf("ink weekly", Weekly.day(w.date)).filter { it.isNotEmpty() }.joinToString("   "), style = pixel(12.sp, Ink.mid))
                if (w.note.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    BasicText(w.note, style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 22.sp))
                }
                Spacer(Modifier.height(12.dp))
                BasicText("${w.songs.size} songs   ${w.songs.sumOf { it.duration } / 60_000} min", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(16.dp))
                Row {
                    Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(w.songs, 0) }
                    Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.play(w.songs.shuffled(), 0) }
                }
                Spacer(Modifier.height(14.dp))
            }
        }}
        // the songs as a chart: the place big beside each (no keys: a song could be picked twice)
        itemsIndexed(w.songs) { n, s -> Box(Modifier.cascade(n + 1)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("${n + 1}", style = headline(30.sp), modifier = Modifier.width(52.dp))
                Box(Modifier.weight(1f)) { SongRow(s, pages = pages) { Playback.play(w.songs, n) } }
            }
        }}
        val before = weeks.filter { it.week != w.week }
        if (before.isNotEmpty()) {
            item { Spacer(Modifier.height(18.dp)); Label("the weeks before") }
            items(before, key = { it.week }) { b ->
                Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.Weekly(b.week)) })
                    .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("${b.week}", style = headline(22.sp, Ink.mid), modifier = Modifier.width(52.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(b.title.uppercase(), style = headline(18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        BasicText(listOf(Weekly.day(b.date), "${b.songs.size} songs").filter { it.isNotEmpty() }.joinToString("   "), style = pixel(12.sp, Ink.mid))
                    }
                }
            }
        }
    } }
}

/**
 * Bringing a Spotify playlist in: signed in to Spotify (the full build: your playlists listed, one picked), or a file
 * chosen (Exportify's CSV, or Spotify's own download), a playlist in it picked if there are several; its songs matched (yours first, else YouTube Music's), then made where you keep
 * playlists (YouTube Music, your server), or played as it is. What wasn't found is listed, dim.
 */
@Composable
private fun ImportPage(pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Import.Listed>?>(null) }
    var chosen by remember { mutableStateOf<Import.Listed?>(null) }
    var progress by remember { mutableStateOf(0) }
    var matched by remember { mutableStateOf<List<Song?>?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    // where it's being made ("youtube", "server"), and once made, the page that opens it (each made once)
    var making by remember { mutableStateOf<String?>(null) }
    val made = remember { androidx.compose.runtime.mutableStateMapOf<String, Page?>() }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = runCatching { Import.read(ctx, uri) }.getOrDefault(emptyList())
            lists = read; chosen = read.singleOrNull(); matched = null; made.clear()
            said = if (read.isEmpty()) "no playlist in that file: an exportify csv, or spotify's Playlist1.json" else null
        }
    }
    // signed in to Spotify: your playlists, read once the sign-in comes back; one tapped, its songs read in
    val spotifyAt by Spotify.at.collectAsState()
    var theirs by remember { mutableStateOf<List<Import.Theirs>?>(null) }
    var wanted by remember { mutableStateOf<Import.Theirs?>(null) }
    var reading by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(spotifyAt) {
        if (!Spotify.ready()) return@LaunchedEffect
        if (theirs == null) theirs = Spotify.playlists() ?: run { said = "spotify didn't say. sign in again"; null }
        wanted?.let { w ->
            reading = 0
            val songs = Spotify.songs(w.id) { reading = it }
            reading = null; wanted = null
            if (songs.isNullOrEmpty()) said = "spotify didn't give that one's songs" else { chosen = Import.Listed(w.name, songs); lists = null }
        }
    }
    fun open(w: Import.Theirs) {
        wanted = w; said = null
        // the passes last an hour: an older one signs in again (straight through), then reads it
        if (Spotify.ready()) scope.launch {
            reading = 0
            val songs = Spotify.songs(w.id) { reading = it }
            reading = null; wanted = null
            if (songs.isNullOrEmpty()) said = "spotify didn't give that one's songs" else { chosen = Import.Listed(w.name, songs); lists = null }
        } else Spotify.signIn(ctx)
    }
    LaunchedEffect(chosen) {
        val c = chosen ?: return@LaunchedEffect
        progress = 0; matched = null; made.clear(); said = null
        matched = Import.match(c.songs) { progress = it }
    }
    val who by YouTubeAccount.who.collectAsState()
    val account by Server.account.collectAsState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        val c = chosen
        val m = matched
        item {
            BasicText((c?.name ?: "FROM SPOTIFY").uppercase(), style = headline(40.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            when {
                c == null && Spotify.available -> {
                    val list = theirs
                    when {
                        reading != null -> BasicText("reading ${wanted?.name?.lowercase().orEmpty()}: $reading songs", style = pixel(12.sp, Ink.mid))
                        list == null -> {
                            BasicText("sign in to spotify and pick a playlist (or your liked songs). each song is found here, yours first, " +
                                "else on youtube music. your sign-in stays on this phone.", style = pixel(12.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 20.sp))
                            Spacer(Modifier.height(14.dp))
                            Word("SIGN IN", headline(40.sp), touch = 8.dp) { said = null; if (Spotify.ready()) scope.launch { theirs = Spotify.playlists() } else Spotify.signIn(ctx) }
                        }
                        else -> BasicText("${list.size - 1} playlists and your liked songs. pick one", style = pixel(12.sp, Ink.mid))
                    }
                    said?.let { Spacer(Modifier.height(12.dp)); BasicText(it, style = pixel(12.sp, Ink.mid)) }
                    // or a file, as before: exportify's csv, spotify's own download
                    if (list == null && reading == null) {
                        Spacer(Modifier.height(30.dp))
                        BasicText("or a file: an exportify.app csv, or spotify's own download of your data (Playlist1.json)", style = pixel(12.sp, Ink.mid))
                        Spacer(Modifier.height(6.dp))
                        Word("choose a file", pixel(14.sp, Ink.mid), touch = 8.dp) { pick.launch(arrayOf("text/*", "application/json", "application/octet-stream", "*/*")) }
                    }
                }
                c == null -> {
                    BasicText("a playlist from spotify, as a file: on exportify.app sign in with spotify and export one (a csv), " +
                        "or use spotify's own download of your data (Playlist1.json, every playlist). each song is found here, " +
                        "yours first" + (if (YouTubeMusic.available) ", else on youtube music" else "") + ".", style = pixel(12.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 20.sp))
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Word("choose a file", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { pick.launch(arrayOf("text/*", "application/json", "application/octet-stream", "*/*")) }
                        Word("exportify.app", pixel(14.sp, Ink.mid), touch = 8.dp) {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://exportify.app")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                    said?.let { Spacer(Modifier.height(12.dp)); BasicText(it, style = pixel(12.sp, Ink.mid)) }
                }
                m == null -> {
                    // a line filling as they're found, as the player's fills with the song
                    val filled by androidx.compose.animation.core.animateFloatAsState(
                        if (c.songs.isEmpty()) 0f else progress / c.songs.size.toFloat(), tween(Motion.ENTER, easing = Motion.expo), label = "found")
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.line)) {
                        Box(Modifier.fillMaxWidth(filled).height(2.dp).background(Ink.ink))
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText("finding ${progress} of ${c.songs.size}", style = pixel(12.sp, Ink.mid))
                }
                else -> {
                    val found = m.filterNotNull()
                    BasicText("${found.size} of ${c.songs.size} found   ${found.count { !it.tube }} yours", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(16.dp))
                    Word("play it", pixel(14.sp), touch = 8.dp) { Playback.play(found, 0) }
                    // where to keep it: each made once (a second tap made a second playlist), then "open it"
                    @Composable
                    fun Place(key: String, where: String, make: suspend () -> Pair<Boolean, Page?>) {
                        Spacer(Modifier.height(10.dp))
                        when {
                            made.containsKey(key) -> Row {
                                BasicText("made on $where", style = pixel(14.sp), modifier = Modifier.padding(end = 26.dp, top = 8.dp))
                                made[key]?.let { page -> Word("open it", pixel(14.sp), touch = 8.dp) { pages.open(page) } }
                            }
                            making == key -> BasicText("making it on $where", style = pixel(14.sp, Ink.dim), modifier = Modifier.padding(top = 8.dp))
                            else -> Word("make it on $where", pixel(14.sp, Ink.mid), touch = 8.dp) {
                                if (making != null) return@Word
                                making = key
                                scope.launch {
                                    val (ok, opened) = make()
                                    if (ok) made[key] = opened
                                    making = null
                                }
                            }
                        }
                    }
                    if (who != null) Place("youtube", "youtube music") {
                        YouTubeAccount.makePlaylistOf(c.name, found)?.let { (list, n) -> said = "$n songs on youtube music"; true to Page.TubeList(list) }
                            ?: run { said = "youtube music didn't take it"; false to null }
                    }
                    if (account != null) Place("server", "your server") {
                        val before = Server.playlists.value.filter { it.name == c.name }.map { it.id }.toSet()
                        val there = found.count { Server.idOf(it) != null }
                        if (Server.makePlaylist(c.name, found)) {
                            said = "$there songs on your server (the ones it has)"
                            true to Server.playlists.value.firstOrNull { it.name == c.name && it.id !in before }?.let { Page.Playlist(it.id, it.name) }
                        } else { said = "the server didn't take it"; false to null }
                    }
                    said?.let { Spacer(Modifier.height(10.dp)); BasicText(it, style = pixel(12.sp, Ink.mid)) }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
        // your spotify playlists: one picked
        if (c == null && reading == null && lists == null) theirs?.let { all ->
            items(all, key = { it.id }) { l ->
                Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(l) }).padding(vertical = 9.dp)) {
                    BasicText(l.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (l.by.isNotBlank()) BasicText(l.by.lowercase(), style = pixel(12.sp, Ink.mid))
                }
            }
        }
        // several playlists in the file: one picked
        if (c == null) lists?.takeIf { it.size > 1 }?.let { all ->
            items(all) { l ->
                Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { chosen = l }).padding(vertical = 9.dp)) {
                    BasicText(l.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BasicText("${l.songs.size} songs", style = pixel(12.sp, Ink.mid))
                }
            }
        }
        // the songs: found ones to play, the rest dim
        if (c != null && m != null) itemsIndexed(c.songs) { i, s ->
            val song = m.getOrNull(i)
            if (song != null) SongRow(song, number = "${i + 1}", pages = pages) { Playback.play(m.filterNotNull(), m.subList(0, i).count { it != null }) }
            else Row(Modifier.padding(vertical = 8.dp)) {
                BasicText("${i + 1}", style = pixel(12.sp, Ink.dim), modifier = Modifier.width(30.dp))
                Column {
                    BasicText(s.title.uppercase(), style = headline(19.sp, Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BasicText("${s.artist.lowercase()}   not found", style = pixel(12.sp, Ink.dim))
                }
            }
        }
    } }
}

/**
 * Your server's songs here kept on the phone: "keep offline"; while they come, "keeping 4 of 12"; all here,
 * "offline" lit, and a tap then "remove it" lit a moment (a second tap lets them go). Nothing for songs not the server's.
 */
@Composable
fun KeepOffline(songs: List<Song>) {
    val ctx = LocalContext.current
    // your server's and YouTube Music's (the full build)
    val server = remember(songs) { songs.filter { Offline.keepable(it) } }
    if (server.isEmpty()) return
    val kept by Offline.kept.collectAsState()
    val keeping by Offline.keeping.collectAsState()
    val here = server.count { it.id in kept }
    val coming = server.count { it.id in keeping }
    // while some are on their way: how far, every half second, and what's whole
    var done by remember { mutableStateOf(0f) }
    LaunchedEffect(coming > 0) {
        while (coming > 0) {
            done = withContext(Dispatchers.IO) { Offline.refresh(ctx); Offline.progress(ctx, server.map { it.id }) }
            kotlinx.coroutines.delay(500)
        }
    }
    var sure by remember { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(3_000); sure = false } }
    when {
        coming > 0 -> Filling("keeping $here of ${server.size}", done, Modifier.padding(top = 8.dp))
        here == server.size -> Word(if (sure) "remove it" else "offline", pixel(14.sp), touch = 8.dp) {
            if (!sure) sure = true else { sure = false; Offline.remove(ctx, server.map { it.id }) }
        }
        else -> Word("keep offline", pixel(14.sp, Ink.mid), touch = 8.dp) { Offline.keep(ctx, server) }
    }
}

/** Words filling with ink from the left as something comes (the rest dim): progress without a bar. */
@Composable
fun Filling(text: String, done: Float, modifier: Modifier = Modifier) {
    val shown by androidx.compose.animation.core.animateFloatAsState(done.coerceIn(0f, 1f), tween(Motion.ENTER, easing = Motion.expo), label = "filling")
    Box(modifier) {
        BasicText(text, style = pixel(14.sp, Ink.dim))
        BasicText(text, style = pixel(14.sp), modifier = Modifier.drawWithContent {
            clipRect(right = size.width * shown) { this@drawWithContent.drawContent() }
        })
    }
}

/** A bio's first sentence (if it's one to pull out: not too long), and the rest. */
fun pullQuote(about: String): Pair<String?, String> {
    val end = Regex("""[.!?](?=\s+[A-Z“"(])""").find(about)?.range?.last ?: return null to about
    val first = about.substring(0, end + 1).trim()
    return if (first.length in 20..180) first to about.substring(end + 1).trim() else null to about
}

/**
 * A picture in the dither (the player's), in its own colour, developing from black (the brightest dots first) once
 * it's read: an artist's photo, and YouTube's mixes and playlists (their own lettering made ink's, a colour to see).
 */
@Composable
fun Dithered(photo: Song, modifier: Modifier, px: Int = 1080) {
    val cover = rememberCover(photo, px)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(androidx.compose.ui.graphics.Color.Black)) {
        val side = with(density) { maxWidth.toPx() }
        val frames by androidx.compose.runtime.produceState<List<androidx.compose.ui.graphics.ImageBitmap>?>(null, cover) {
            val c = cover ?: return@produceState
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                Looks.develop(c.image.asAndroidBitmap(), c.accent, side, density.density)?.map { it.asImageBitmap() }
            }
        }
        val shown = remember(frames) { Animatable(0f) }
        LaunchedEffect(frames) { if (frames != null) { if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, tween(900, easing = Motion.expo)) } }
        frames?.let { f ->
            // the dots drawn as dots: unfiltered, as the player draws them
            Image(f[((shown.value * f.size).toInt()).coerceIn(0, f.size - 1)], null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
        }
    }
}

/** An artist's name on their photo: big and still when it fits the width, else running across it as the player's does. */
@Composable
private fun NameOn(name: String, modifier: Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = headline(64.sp)
    BoxWithConstraints(modifier) {
        val room = with(LocalDensity.current) { (maxWidth - 40.dp).toPx() }
        val wide = remember(name, room) { measurer.measure(name.uppercase(), style, maxLines = 1, softWrap = false).size.width > room }
        if (wide) Marquee(name, Marquees.on.collectAsState().value, Modifier.fillMaxWidth(), size = 96.sp)
        else BasicText(name.uppercase(), style = style, maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

/** An artist's wide photo as a song-shaped picture, kept apart from their small face. */
fun photoSong(name: String, photo: String) = faceSong(name, photo).copy(id = "yphoto:$name", albumId = "photo:$name")

/** What was looked for lately, the latest first, eight at most (as the computer's): kept as find closes, and as go plays. */
private object Searches {
    val recent = MutableStateFlow<List<String>>(emptyList())
    private var file: java.io.File? = null

    fun load(c: android.content.Context) {
        if (file != null) return
        val f = java.io.File(c.filesDir, "searches.json").also { file = it }
        recent.value = runCatching { org.json.JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun keep(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        recent.value = (listOf(t) + recent.value.filterNot { it.equals(t, true) }).take(8)
        runCatching { file?.writeText(org.json.JSONArray(recent.value).toString()) }
    }

    fun forget() { recent.value = emptyList(); file?.delete() }
}

/**
 * Find, as ink's run menu: "find >" and what's typed; your artists, records, playlists and songs, radio's stations, and
 * all YouTube Music has (its artists, records, songs and playlists). Forgiving ([Search]): accents, "&", words in any
 * order. Go plays the songs found (yours, else YouTube Music's).
 */
@Composable
private fun FindPage(query: String, onQuery: (String) -> Unit, pages: Pages) {
    val ctx = LocalContext.current
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val serverLists by Server.playlists.collectAsState()
    val tubeLists by YouTubeAccount.playlists.collectAsState()
    LaunchedEffect(Unit) { Searches.load(ctx) }
    val recent by Searches.recent.collectAsState()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Searches.keep(pages.query) } }
    val typed = query.trim()
    val q = Search.fold(typed)
    // YouTube Music too (the full build), once the typing rests
    val tube by androidx.compose.runtime.produceState(TubeFound(), q) {
        if (!YouTubeMusic.available || q.length < 2) { value = TubeFound(); return@produceState }
        kotlinx.coroutines.delay(300)
        value = YouTubeMusic.search(typed, all = true)
    }
    // radio's stations by that name (ink radio's own first)
    val stations by androidx.compose.runtime.produceState(emptyList<Song>(), q) {
        if (q.length < 2) { value = emptyList(); return@produceState }
        kotlinx.coroutines.delay(300)
        value = Stations.search(ctx, typed)
    }
    val artists = remember(albums, songs) { artistsOf(albums, songs) }
    val foundArtists = remember(q, artists) { if (q.isEmpty()) emptyList() else artists.mapNotNull { a -> Search.rank(a.name, q)?.let { it to a } }.sortedBy { it.first }.map { it.second }.take(8) }
    val foundRecords = remember(q, albums) { if (q.isEmpty()) emptyList() else albums.mapNotNull { a -> Search.rank(q, a.title, a.artist)?.let { it to a } }.sortedBy { it.first }.map { it.second }.take(12) }
    val foundSongs = remember(q, songs) { if (q.isEmpty()) emptyList() else songs.mapNotNull { s -> Search.rank(q, s.title, s.artist, s.album)?.let { it to s } }.sortedBy { it.first }.map { it.second }.take(50) }
    // your playlists: the server's, and YouTube Music's (signed in)
    val foundLists = remember(q, serverLists) { if (q.isEmpty()) emptyList() else serverLists.mapNotNull { l -> Search.rank(l.name, q)?.let { it to l } }.sortedBy { it.first }.map { it.second }.take(8) }
    val hiddenLists by HiddenLists.ids.collectAsState()
    val foundTubeLists = remember(q, tubeLists, hiddenLists) { if (q.isEmpty()) emptyList() else tubeLists.filter { it.id !in hiddenLists }.mapNotNull { l -> Search.rank(l.name, q)?.let { it to l } }.sortedBy { it.first }.map { it.second }.take(8) }
    // YouTube Music's artists, their faces (any you have already shown as yours); else whose its songs and records are
    // (Bronson, 2026-10-29: Mac DeMarco found, no page of his to open)
    val tubeArtists = remember(q, tube, foundArtists) {
        if (q.isEmpty()) emptyList()
        else tube.artists.ifEmpty { (tube.songs.flatMap { it.artist.split(", ") } + tube.albums.map { it.artist }).map { TubeArtist(it.trim(), null) } }
            .filter { a -> a.name.isNotEmpty() && Search.rank(a.name, q) != null && foundArtists.none { it.name.equals(a.name, true) } }
            .distinctBy { it.name.lowercase() }.take(8)
    }
    // go plays what's found: your songs, else YouTube Music's
    val go = foundSongs.ifEmpty { tube.songs }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val list = rememberLazyListState()
    val row = Modifier.padding(horizontal = 20.dp)
    FrostedPage { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            Row(row, verticalAlignment = Alignment.CenterVertically) {
                BasicText("find > ", style = pixel(14.sp, Ink.dim))
                // back from a result, what was typed is there, the cursor at its end (it came back at the start)
                var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length))) }
                // a recent search chosen: in the field, the cursor at its end
                LaunchedEffect(query) { if (field.text != query) field = androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length)) }
                BasicTextField(
                    field, { field = it; if (it.text != query) onQuery(it.text) }, singleLine = true,
                    textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (go.isNotEmpty()) { Searches.keep(query); Playback.play(go, 0) } }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                if (query.isNotEmpty()) Word("clear", pixel(12.sp, Ink.mid), Modifier.padding(end = 14.dp), touch = 10.dp) { onQuery("") }
                CloseArrow { pages.close() }
            }
            Spacer(Modifier.height(18.dp))
            // nothing anywhere: here, nor on YouTube Music (once it's had its moment to answer)
            if (q.isNotEmpty() && foundArtists.isEmpty() && foundRecords.isEmpty() && foundSongs.isEmpty() && foundLists.isEmpty() &&
                foundTubeLists.isEmpty() && stations.isEmpty() && tube.songs.isEmpty() && tube.albums.isEmpty() && tubeArtists.isEmpty() && tube.lists.isEmpty())
                BasicText("nothing called that", style = pixel(12.sp, Ink.mid), modifier = row)
            // what you looked for lately, a tap to look again
            if (q.isEmpty() && recent.isNotEmpty()) FlowRow(row, horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("recent", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(top = 3.dp))
                recent.forEach { r -> Word(r.lowercase(), pixel(14.sp, Ink.mid), touch = 6.dp) { onQuery(r) } }
                Word("clear", pixel(12.sp, Ink.dim), Modifier.padding(top = 2.dp), touch = 6.dp) { Searches.forget() }
            }
        }
        if (foundArtists.isNotEmpty()) {
            item { Label("artists") }
            items(foundArtists, key = { "a" + it.name }) { a -> Box(row) { ArtistRow(a) { pages.open(Page.Artist(a.name)) } } }
        }
        if (foundRecords.isNotEmpty()) item {
            Label("records")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(foundRecords, key = { it.id }) { a -> RecordTile(a, Modifier.width(130.dp)) { pages.open(Page.Record(a.id)) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (foundLists.isNotEmpty() || foundTubeLists.isNotEmpty()) {
            item { Label("your playlists") }
            items(foundLists, key = { "l" + it.id }) { l -> FoundList(l.name, "${l.count} ${if (l.count == 1) "song" else "songs"}") { pages.open(Page.Playlist(l.id, l.name)) } }
            items(foundTubeLists, key = { "tl" + it.id }) { l -> FoundList(l.name, "on youtube music") { pages.open(Page.TubeList(l)) } }
        }
        // your songs, the first few; then the rest after YouTube Music's (below a whole library's worth, it went unseen)
        val (first, rest) = foundSongs.take(10) to foundSongs.drop(10)
        if (first.isNotEmpty()) {
            item { Label("songs") }
            itemsIndexed(first, key = { _, s -> "s" + s.id }) { i, s ->
                Box(row) { SongRow(s, pages = pages) { Playback.play(foundSongs, i) } }
            }
        }
        if (stations.isNotEmpty()) item {
            Label("radio")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(stations, key = { "r" + it.id }) { s -> StationTile(s, false, false, s.artist) { Playback.play(listOf(s), 0) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        // what YouTube Music has: its artists, records, songs, then playlists
        if (tubeArtists.isNotEmpty()) item {
            Label("artists on youtube music")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(tubeArtists, key = { "f" + it.id.ifEmpty { it.name } }) { a ->
                    Column(Modifier.width(130.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                        onClick = felt { pages.open(if (a.id.isNotEmpty()) Page.TubeArtist(a) else Page.Artist(a.name)) })) {
                        CoverOf(remember(a.face) { a.face?.let { faceSong(a.name, it) } }, a.name, Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(8.dp))
                        UnderCover(a.name, "artist", pixel(12.sp, Ink.mid))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (tube.albums.isNotEmpty()) item {
            Label("records on youtube music")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(tube.albums, key = { "ta" + it.url }) { a -> TubeTile(a, Modifier.width(130.dp)) { pages.open(Page.Tube(a)) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (tube.songs.isNotEmpty()) item { Label("songs on youtube music") }
        itemsIndexed(tube.songs, key = { _, s -> "t" + s.id }) { i, s ->
            Box(row) { SongRow(s, pages = pages) { Playback.play(tube.songs, i) } }
        }
        if (tube.lists.isNotEmpty()) item {
            Label("playlists on youtube music")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(tube.lists, key = { "tp" + it.url }) { a -> TubeTile(a, Modifier.width(130.dp)) { pages.open(Page.Tube(a)) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (rest.isNotEmpty()) {
            item { Label("more of your songs") }
            itemsIndexed(rest, key = { _, s -> "s" + s.id }) { i, s ->
                Box(row) { SongRow(s, pages = pages) { Playback.play(foundSongs, i + first.size) } }
            }
        }
    } }
}

/** A playlist found: its name big, a word on it under. */
@Composable
private fun FoundList(name: String, said: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))
        .padding(horizontal = 20.dp, vertical = 9.dp)) {
        BasicText(name.uppercase(), style = headline(19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(said, style = pixel(12.sp, Ink.mid))
    }
}

/** A YouTube Music record as a tile: its cover (from its link), name and artist. */
@Composable
fun TubeTile(a: TubeAlbum, modifier: Modifier, onClick: () -> Unit) {
    val face = remember(a.url) { tubeFace(a) }
    Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        // YouTube's own playlists (its mixes, charts, editors' lists) in the dither, its lettering made ink's
        if (Curation.tubeBranded(a.url, a.artist) && a.art != null) Dithered(face, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        else CoverOf(face, a.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        UnderCover(a.title, a.artist, pixel(12.sp, Ink.mid), gap = 3.dp)
    }
}

/** A record's face for its cover: a song-shaped stand-in carrying the record's picture. */
fun tubeFace(a: TubeAlbum) = Song(
    id = "y" + a.url.hashCode(), uri = android.net.Uri.EMPTY, title = a.title, artist = a.artist, album = a.title,
    albumId = "ya" + a.url.hashCode(), albumArtist = a.artist, duration = 0, disc = 1, track = 0, year = 0, art = a.art,
)

/** A YouTube Music record's page: its cover, name, play and shuffle, its songs. */
@Composable
private fun TubeAlbumPage(a: TubeAlbum, pages: Pages) {
    // its artist read ahead (their name's a way to their page)
    val ctx = LocalContext.current
    LaunchedEffect(a.artist) { YouTubeAccount.warm(ctx, a.artist) }
    val songs by androidx.compose.runtime.produceState<List<Song>?>(null, a.url) { value = YouTubeMusic.album(a) }
    LaunchedEffect(songs?.firstOrNull()?.id) { Playback.warm(songs?.firstOrNull()) }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            BoxWithConstraints(Modifier.bleed(20.dp), contentAlignment = Alignment.Center) {
                CoverOf(remember(a.url) { tubeFace(a) }, a.title, Modifier.size(minOf(maxWidth, 560.dp)))
            }
            Spacer(Modifier.height(18.dp))
            BasicText(a.title.uppercase(), style = headline(36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Word(a.artist.lowercase(), pixel(13.sp, Ink.mid), touch = 6.dp) { pages.open(Page.Artist(a.artist)) }
            Spacer(Modifier.height(4.dp))
            val all = songs
            BasicText(if (all == null) "reading it from youtube music" else "${all.size} songs   youtube music", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
                Box(Modifier.padding(end = 26.dp)) { KeepOffline(all) }
                // signed in: saved to your library there (lit), or not
                val who by YouTubeAccount.who.collectAsState()
                val records by YouTubeAccount.records.collectAsState()
                if (who != null) {
                    val saved = remember(records, a.url) { YouTubeAccount.saved(a) }
                    val scope = rememberCoroutineScope()
                    Word(if (saved) "saved" else "save", pixel(14.sp, if (saved) Ink.ink else Ink.mid), touch = 8.dp) { scope.launch { YouTubeAccount.save(a, !saved) } }
                }
            }
            Spacer(Modifier.height(14.dp))
        }}
        val all = songs.orEmpty()
        itemsIndexed(all) { i, s -> Box(Modifier.cascade(i + 1)) { SongRow(s, number = "${i + 1}") { Playback.play(all, i) } }}
    } }
}

/** One of your playlists on YouTube Music: its name big, play and shuffle, its songs (read only: change it there). */
@Composable
private fun TubeListPage(pl: TubeList, pages: Pages) {
    // as it was last read at once, else page by page as it comes
    val songs by androidx.compose.runtime.produceState<List<Song>?>(null, pl.id) {
        YouTubeAccount.playlist(pl.id).collect { value = it }
        if (value == null) value = emptyList()
    }
    LaunchedEffect(songs?.firstOrNull()?.id) { Playback.warm(songs?.firstOrNull()) }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            BasicText(pl.name.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            val all = songs
            BasicText(
                if (all == null) "reading it from youtube music" else listOfNotNull("${all.size} ${if (all.size == 1) "song" else "songs"}",
                    all.sumOf { it.duration }.takeIf { it > 0 }?.let { "${it / 60_000} min" }, "youtube music").joinToString("   "),
                style = pixel(12.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
                KeepOffline(all)
            }
            // hide: out of ink music's lists and find, YouTube untouched (#1: "delete" alone read as that, and wasn't).
            // Yours (not a mix, not someone else's) can also be deleted from YouTube itself: said so, then "delete it
            // from youtube" lit a moment, as a server playlist's
            val hidden by HiddenLists.ids.collectAsState()
            val mine by androidx.compose.runtime.produceState(false, pl.id) { value = YouTubeAccount.mine(pl.id) }
            var sure by remember { mutableStateOf(false) }
            LaunchedEffect(sure) { if (sure) { delay(3_000); sure = false } }
            val scope = rememberCoroutineScope()
            Row(Modifier.padding(top = 10.dp)) {
                val off = pl.id in hidden
                Word(if (off) "show in ink music" else "hide", pixel(12.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) {
                    HiddenLists.set(pl.id, !off); if (!off) pages.back()
                }
                if (mine) Word(if (sure) "delete it from youtube" else "delete from youtube", pixel(12.sp, if (sure) Ink.ink else Ink.mid), touch = 8.dp) {
                    if (!sure) sure = true else scope.launch { if (YouTubeAccount.deletePlaylist(pl.id)) pages.back() }
                }
            }
            Spacer(Modifier.height(14.dp))
        }}
        val all = songs.orEmpty()
        // no keys: a playlist can hold one song twice
        itemsIndexed(all) { i, s -> Box(Modifier.cascade(i + 1)) { SongRow(s) { Playback.play(all, i) } }}
    } }
}


/** Where a held song goes into a playlist: a new one ("new >", named there), or one of yours. */
@Composable
private fun AddToPage(s: Song, pages: Pages) = AddToList(s, onDone = { pages.back() }, onClose = { pages.close() })

/** Whether a song can go into one of your playlists: the server has it, or you're signed in to YouTube Music. */
@Composable
fun canAddToList(s: Song?): Boolean {
    val account by Server.account.collectAsState()
    val who by YouTubeAccount.who.collectAsState()
    return s != null && ((account != null && Server.idOf(s) != null) || (who != null && !s.station))
}

/**
 * The playlists a song can go into, as a page over what's below (the held song's, or the player's): a new one, or one
 * of yours on your server or YouTube Music; `onDone` with the list's name once it's in.
 */
@Composable
fun AddToList(s: Song, onDone: (String) -> Unit, onClose: () -> Unit) {
    val lists by Server.playlists.collectAsState()
    val account by Server.account.collectAsState()
    val server = account != null && Server.idOf(s) != null
    val mine = remember(lists, server) { if (server) lists.filter { it.mine } else emptyList() }
    val who by YouTubeAccount.who.collectAsState()
    val tubeLists by YouTubeAccount.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    // your server's lists as they are now (one made in another app since)
    LaunchedEffect(Unit) { Server.readPlaylists() }
    val list = rememberLazyListState()
    FrostedPage(onClose = onClose) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText("ADD TO A PLAYLIST", style = headline(36.sp), maxLines = 2)
            Spacer(Modifier.height(8.dp))
            BasicText(s.title.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            if (failed) BasicText("that didn't take", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(bottom = 8.dp))
            if (server) {
                if (who != null) Label("on your server")
                NewPlaylist(s, focused = mine.isEmpty() && who == null) { ok -> if (ok) onDone("a new playlist") else failed = true }
                if (mine.isEmpty()) BasicText("no playlists of yours on your server yet: new > makes one, named as you type", style = pixel(12.sp, Ink.mid),
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
            }
        }
        items(mine, key = { it.id }) { pl ->
            Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                onClick = felt { scope.launch { if (Server.addTo(pl.id, s)) onDone(pl.name) else failed = true } }).padding(vertical = 9.dp)) {
                BasicText(pl.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("${pl.count} ${if (pl.count == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
            }
        }
        // your playlists on YouTube Music (signed in): a song from elsewhere found there by its words
        if (who != null) {
            item {
                Spacer(Modifier.height(10.dp))
                Label("on youtube music")
                NewPlaylist(s, focused = !server, make = { YouTubeAccount.makePlaylist(it, s) }) { ok -> if (ok) onDone("a new playlist") else failed = true }
                Spacer(Modifier.height(14.dp))
            }
            items(tubeLists, key = { "y" + it.id }) { pl ->
                Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { scope.launch { if (YouTubeAccount.addTo(pl, s)) onDone(pl.name) else failed = true } }).padding(vertical = 9.dp)) {
                    BasicText(pl.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (pl.said.isNotEmpty()) BasicText(pl.said.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1)
                }
            }
        }
    } }
}

/**
 * A path from a song to another, by how they sound (the server's sonic paths: Navidrome with
 * AudioMuse-AI): "to >" finds the song to end on among the server's; then the way there, a step a
 * song, to play from any of them, or keep as a playlist ("<from> to <to>"); "another" picks again.
 */
@Composable
private fun PathPage(from: Song, pages: Pages) {
    val songs by Library.songs.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var to by remember { mutableStateOf<Song?>(null) }
    var path by remember { mutableStateOf<List<Song>?>(null) }
    var kept by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(to) { path = null; kept = null; to?.let { path = Server.path(from, it) } }
    val q = query.trim().lowercase()
    val found = remember(q, songs) {
        if (q.isEmpty()) emptyList() else songs.asSequence()
            .filter { it.id != from.id && Server.idOf(it) != null && (it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)) }
            .sortedBy { if (it.title.lowercase().startsWith(q)) 0 else 1 }.take(40).toList()
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            BasicText("A PATH", style = headline(44.sp))
            Spacer(Modifier.height(8.dp))
            BasicText("from one song to another, a step at a time, by how they sound", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(18.dp))
            BasicText("from", style = pixel(12.sp, Ink.mid))
            BasicText(from.title.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(from.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
        }}
        val end = to
        if (end == null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("to > ", style = pixel(14.sp, Ink.dim))
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (q.isNotEmpty() && found.isEmpty()) BasicText("nothing on your server called that", style = pixel(12.sp, Ink.mid))
            }
            items(found, key = { "to" + it.id }) { s ->
                Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { keyboard?.hide(); to = s }).padding(vertical = 9.dp)) {
                    BasicText(s.title.uppercase(), style = headline(19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BasicText(s.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            item {
                BasicText("to", style = pixel(12.sp, Ink.mid))
                BasicText(end.title.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(end.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                val way = path
                BasicText(
                    when {
                        way == null -> "finding the way"
                        way.isEmpty() -> "the server found no way there"
                        else -> listOfNotNull("${way.size} songs", way.sumOf { it.duration }.takeIf { it > 0 }?.let { "${it / 60_000} min" }).joinToString("   ")
                    },
                    style = pixel(12.sp, Ink.mid),
                )
                Spacer(Modifier.height(14.dp))
                Row {
                    if (!way.isNullOrEmpty()) {
                        Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(way, 0) }
                        Word(when (kept) { true -> "kept"; false -> "the server didn't take it"; null -> "keep" }, pixel(14.sp, Ink.mid),
                            Modifier.padding(end = 26.dp), touch = 8.dp) {
                            if (kept == null) scope.launch { kept = Server.makePlaylist("${from.title} to ${end.title}", way) }
                        }
                    }
                    Word("another", pixel(14.sp, Ink.mid), touch = 8.dp) { to = null }
                }
                Spacer(Modifier.height(14.dp))
            }
            val way = path.orEmpty()
            // no keys: a path could pass one song twice
            itemsIndexed(way) { i, s -> Box(Modifier.cascade(i + 1)) { SongRow(s, number = "${i + 1}", pages = pages) { Playback.play(way, i) } }}
        }
    } }
}

/** "new >" and a name: go makes the playlist on the server (with `song` in it, if there's one). */
@Composable
private fun NewPlaylist(song: Song?, focused: Boolean = false, make: suspend (String) -> Boolean = { Server.makePlaylist(it, listOfNotNull(song)) }, onMade: (Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    if (focused) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun make() {
        val n = name.trim()
        if (n.isEmpty()) return
        scope.launch {
            val ok = make(n)
            if (ok) { name = ""; keyboard?.hide() }
            onMade(ok)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("new > ", style = pixel(14.sp, Ink.dim))
        BasicTextField(
            name, { name = it }, singleLine = true,
            textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { make() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        if (name.isNotBlank()) Word("make", pixel(14.sp), touch = 8.dp) { make() }
    }
}

@Composable
fun Label(text: String) {
    BasicText(text, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
}

/**
 * A section's head on INK (Bronson, 2026-10-29: the small labels were hard to read): its name in Archivo Black, a short
 * line under it, a word at its right (play all); the Mac's [Section], phone-sized.
 */
@Composable
fun Heading(title: String, line: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            BasicText(title.uppercase(), style = headline(22.sp), maxLines = 2)
            if (line != null) BasicText(line, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 3.dp))
        }
        action?.let { Box(Modifier.padding(start = 12.dp)) { it() } }
    }
}

/** How tall the soft edge beside a bar is: sharp at its far side, slightly out of focus at the bar. */
val BAND = 16.dp

/**
 * A page that scrolls on under the status bar, frosted there (its band below it). `content` gets
 * the frost its list is the source of, and how far down to start: below the status bar, and
 * below close when the page has one (kept at the top, on black, however far it's scrolled).
 */
@Composable
fun FrostedPage(onClose: (() -> Unit)? = null, content: @Composable (HazeState, Dp) -> Unit) {
    val frost = rememberHazeState()
    val bar = bars.asPaddingValues().calculateTopPadding()
    val top = bar + if (onClose != null) CLOSE_ROW else 0.dp
    // pulled down from its top (anywhere: an artist's photo, a list at its start), the page comes with the finger,
    // as the player does; past a fifth of the screen or flung, it closes, else it settles back. Its close is a down
    // arrow, so down is the way out
    var pull by remember { mutableFloatStateOf(0f) }
    var tall by remember { mutableIntStateOf(1) }
    val close by androidx.compose.runtime.rememberUpdatedState(onClose)
    val pulling = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (pull <= 0f || available.y >= 0f) return Offset.Zero
                val take = maxOf(available.y, -pull)
                pull += take
                return Offset(0f, take)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (close == null || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pull += available.y
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (pull <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                if (pull > tall / 5f || available.y > 1800f) close?.invoke()
                else androidx.compose.animation.core.animate(pull, 0f, animationSpec = tween(Motion.EXIT, easing = Motion.expo)) { v, _ -> pull = v }
                return available
            }
        }
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { tall = it.height }
            .nestedScroll(pulling).graphicsLayer { translationY = pull }.background(Ink.black),
    ) {
        content(frost, top)
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(bar).background(Ink.black))
            if (onClose != null) Row(
                Modifier.fillMaxWidth().height(CLOSE_ROW).background(Ink.black).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                CloseArrow(onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = false))
        }
    }
}

/** When the page on top was opened (uptime ms), going forward; 0 going back, or for the library under them. */
val LocalOpened = androidx.compose.runtime.compositionLocalOf { 0L }
/** The shared-element scope round the pages, and the page's own, for a word carried into the next page ([carried]). */
val LocalShared = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
val LocalPageScope = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/**
 * A page's rows arriving in turn as it opens (ink's [arrive]: up 20 px and in, 50 ms apart, the first eight, the
 * rest with the eighth); rows that come later (scrolled to), and pages gone back to, just there.
 */
@Composable
fun Modifier.cascade(i: Int): Modifier {
    if (Moves.less.collectAsState().value) return this
    val opened = LocalOpened.current
    val now = remember { android.os.SystemClock.uptimeMillis() }
    if (opened == 0L || now - opened > 600) return this
    return this.arrive(i.coerceAtMost(8), Unit)
}

/** A word that becomes the next page's title (it's what was tapped): moved and grown there, not faded and drawn again. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.carried(key: String): Modifier {
    if (Moves.less.collectAsState().value) return this
    val shared = LocalShared.current ?: return this
    val scope = LocalPageScope.current ?: return this
    return with(shared) {
        this@carried.sharedBounds(rememberSharedContentState(key), scope,
            enter = androidx.compose.animation.fadeIn(tween(Motion.FADE)), exit = androidx.compose.animation.fadeOut(tween(Motion.FADE)),
            boundsTransform = { _, _ -> tween(Motion.ENTER, easing = Motion.expo) },
            resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.scaleToBounds())
    }
}

/** How far a song row is swiped before it counts. */
private val SWIPE = 96.dp

/**
 * Full width, past the list's side margins (`by` each side): a cover edge to edge. Its height
 * says nothing of the margins, so what's under it lines up as usual.
 */
private fun Modifier.bleed(by: Dp) = layout { m, c ->
    val px = by.roundToPx()
    val w = c.maxWidth + 2 * px
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(c.maxWidth, p.height) { p.place(-px, 0) }
}

/** The row "close" sits in, at a page's top. */
private val CLOSE_ROW = 40.dp

/** A record's cover, or ink's own sleeve with its name when the files carry none (a station's, with its rings). */
@Composable
fun CoverOf(song: Song?, name: String, modifier: Modifier) {
    val cover = rememberCover(song)
    // 1-bit mode (the clickwheel three turns backwards): every cover in the dither, its own colour
    if (OneBit.on.collectAsState().value && cover != null && song != null && !song.station) return Dithered(song, modifier, px = 600)
    // a cover on its way: black a moment, the name cover only for a record with none (it flashed at each new song)
    val late by androidx.compose.runtime.produceState(false, song?.albumId) { kotlinx.coroutines.delay(1_500); value = true }
    BoxWithConstraints(modifier) {
        if (cover != null) Image(cover.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (song?.station == true) StationCover(song, Modifier.fillMaxSize())
        else if (late || song == null) NameCover(name, maxWidth)
    }
}

/**
 * What's playing, along the foot of the library: the line along the cover's top edge filling
 * with the song in its colour, the cover, the song, play / pause. A tap opens the player.
 * Stopped, it goes (a tester's ask, 2026-10-26), and comes back when something plays.
 */
@Composable
private fun MiniPlayer(frost: HazeState, onOpen: () -> Unit) {
    val now by Playback.now.collectAsState()
    val showing = now?.takeIf { !it.stopped }
    // the last song shown stays drawn while it goes
    var last by remember { mutableStateOf(showing) }
    if (showing != null) last = showing
    androidx.compose.animation.AnimatedVisibility(
        showing != null,
        enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 3 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
        exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { it / 3 } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
    ) { last?.let { MiniPlayerOf(it, frost, onOpen) } }
}

@Composable
private fun MiniPlayerOf(n: Now, frost: HazeState, onOpen: () -> Unit) {
    val cover = rememberCover(n.song)
    // black; what's about to go behind it softens just above it
    Column(Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = true))
    Column(Modifier.fillMaxWidth().background(Ink.black).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 12.dp)) {
        Line(n, cover?.accent ?: Ink.ink)
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onOpen)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // the same size as the player's, lyrics up: one block, wherever it's seen
            CoverOf(n.song, n.album.ifEmpty { n.title }, Modifier.size(72.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BasicText(n.title.uppercase(), style = headline(20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                BasicText(n.artist.lowercase() + if (n.device.isNotEmpty()) "  on ${n.device.lowercase()}" else "",
                    style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Word(if (n.playing) "pause" else "play", pixel(14.sp), Modifier.padding(start = 14.dp), touch = 12.dp) { Playback.toggle() }
        }
    }
    }
}

/** The 1 px line filling with the song in the cover's colour (no times: the player has them). */
@Composable
private fun Line(n: Now, accent: androidx.compose.ui.graphics.Color) {
    val pos by androidx.compose.runtime.produceState(n.positionNow(), n) {
        while (true) { value = n.positionNow(); if (!n.moving) break; kotlinx.coroutines.delay(500) }
    }
    val frac = if (n.duration > 0) (pos.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line)) {
        Box(Modifier.fillMaxWidth(frac).height(1.dp).background(accent))
    }
}

/** A name as compared: case and marks aside. */
private fun plainName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** How long the player takes to sink away on stop: with the music's own fade out (Soft, 250 ms). */
private const val STOP_MS = 280
