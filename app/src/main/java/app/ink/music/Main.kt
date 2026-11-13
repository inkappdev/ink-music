package app.ink.music

import kotlinx.coroutines.flow.first
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.fillMaxHeight
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
        // a computer's QR code read by the camera: kept before anything, so even a Main closing itself below hands it on
        Computers.take(intent)
        // opened again (the home screen, the notification) over ink music's own screens: those stay
        // as they were (the YouTube sign-in, mid-way), not a second library over them
        if (!isTaskRoot) { finish(); return }
        // a TV never stands upright: the manifest's portrait is the phone's
        Device.tv = isTv(this)
        if (isTv(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enableEdgeToEdge()
        Lyrics.start(this)
        Moments.start(this)
        Looks.start(this)
        Marquees.start(this)
        CoverPrint.start(this)
        ListCovers.start(this)
        ListsGrid.start(this)
        Loved.start(this)
        YouTubeMusic.attach(this)
        Server.start(this)
        YouTubeAccount.start(this)
        HiddenLists.load(this)
        Moving.start(this)
        Receiver.log = { android.util.Log.d("ink-receiver", it) }
        ListenBrainz.start(this)
        LastFm.start(this)
        Offline.start(this)
        KeepLoved.start(this)
        KeepMost.start(this)
        SongsFrom.start(this)
        Pods.start(this)
        Order.start(this)
        Seeds.start(this)
        Fresh.start(this)
        Hints.start(this)
        SetHeard.start(this)
        Stations.curated(this)
        setContent { TvFocus { App() } }
        // the next song's cover (and the last's) ready before it plays (Covers.ahead)
        lifecycleScope.launch { Covers.ahead(this@Main) }
        // the genres opened most, their editors' songs read ahead (their pages open with them there)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Moods.kept(this@Main)
            GenreSongs.warm(this@Main, Moods.counts.value.entries.sortedByDescending { it.value }.map { it.key }.take(6))
        }
        // casting: the volume buttons, while ink music is open, go to the player's session, which turns the speaker
        // (Android gives them to a session only while it plays, and the phone's own player is paused under a speaker)
        lifecycleScope.launch {
            Casting.volume.map { it != null }.distinctUntilChanged().collect { on ->
                mediaController = Playback.token?.takeIf { on }?.let { android.media.session.MediaController(this@Main, it) }
            }
        }
    }

    /** Open already, and a computer's QR code read by the camera: the pairing page takes it (App). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        Computers.take(intent)
    }

    /** A TV's remote pressed with nothing focused (Compose then hears nothing): the first thing on screen takes focus. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        val dpad = event.keyCode in android.view.KeyEvent.KEYCODE_DPAD_UP..android.view.KeyEvent.KEYCODE_DPAD_CENTER
        return event.action == android.view.KeyEvent.ACTION_DOWN && dpad && isTv(this) && TvKeys.wake?.invoke() == true
    }

    override fun onStart() {
        super.onStart()
        // your listening read again for the orders (lately, most, this hour: what was played since counts)
        Listened.read(this)
        Colours.start(this)
        Playback.connect(this)
        Order.opened()
        // a TV: the phones paired with it listened for (it's their "computer")
        if (isTv(this)) TvHub.start(this)
        // a paired computer: in step now, and every minute while open
        SyncPhone.start(this)
        SyncPhone.open()
        // what's new is new since you were last here; your taste made again
        Fresh.opened(this)
    }

    override fun onStop() {
        Fresh.left(this)
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
    /** Several songs picked (Picking), into a playlist. */
    data class AddToMany(val songs: List<Song>) : Page
    /** A music video, watched ([WatchPage]). */
    data class Watch(val clip: MusicVideos.Clip, val list: List<MusicVideos.Clip> = emptyList()) : Page
    /** A YouTube song's version chosen by hand ([VersionPage]). */
    data class Version(val song: Song) : Page
    data class Path(val song: Song) : Page
    data object Loved : Page
    data object Year : Page
    data object Mapped : Page
    data object Pick : Page
    data object InkRadio : Page
    data class TubeList(val list: app.ink.music.TubeList) : Page
    data class TubeArtist(val artist: app.ink.music.TubeArtist) : Page
    data class Weekly(val week: Int) : Page
    data object Kept : Page
    /** A smart playlist (SmartLists): its songs, filled from its rules as it opens. */
    data class Smart(val id: String) : Page
    /** A smart playlist made or changed: null, a new one. */
    data class SmartEdit(val id: String?) : Page
    data object Import : Page
    data object Equalizer : Page
    data object HandOver : Page
    data class Mood(val mood: TubeMood) : Page
    data class Festival(val name: String) : Page
    data class SettingsPart(val part: String) : Page
    /** A podcast ([ShowPage]); `first`, the show as it was found, shown while its feed is read. */
    data class Show(val feed: String, val first: Podcasts.Show? = null) : Page {
        override fun equals(other: Any?) = other is Show && other.feed == feed
        override fun hashCode() = feed.hashCode()
    }
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
    // a computer's QR code read by the phone's own camera: its pairing page, on top (it pairs from there)
    val scanned by Computers.scanned.collectAsState()
    LaunchedEffect(scanned) { if (scanned != null && pages.stack.lastOrNull() != Page.HandOver) pages.open(Page.HandOver) }
    var playerUp by rememberSaveable { mutableStateOf(false) }
    // what plays on another device, opened from its bar ([RemotePlayerPage])
    var remoteUp by remember { mutableStateOf(false) }
    BackHandler(playerUp) { playerUp = false }
    // a TV: a song, a record, a mix begun from the sofa brings the player up (as ink tv shows what starts playing)
    if (onTv()) LaunchedEffect(Unit) {
        var seen = Playback.begun.value
        Playback.begun.collect { if (it != seen) { seen = it; playerUp = true } }
    }
    BackHandler(pages.stack.isNotEmpty() && !playerUp) { pages.back() }
    // songs being picked: back lets them go (before it closes the page they're on)
    val picking = Picking.picked.collectAsState().value.isNotEmpty()
    BackHandler(picking && !playerUp) { Picking.clear() }

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
                // a TV: the library under a page (or the player) takes no focus, and the remote starts afresh on what's
                // in front (its focus was left on the cover that opened it, under the page)
                val covered = pages.stack.isNotEmpty() || playerUp
                val tv = onTv()
                // (the player takes it itself, on play)
                val libraryFocus = rememberTvStart()
                val pageFocus = rememberTvStart()
                TvRefocus(pages.stack.size, playerUp, after = Motion.ENTER + 320L) { if (playerUp) null else if (pages.stack.isEmpty()) libraryFocus else pageFocus }
                Box(if (tv) Modifier.covered(covered, libraryFocus) else Modifier) {
                    CompositionLocalProvider(LocalTvStart provides libraryFocus) {
                        LibraryPage(pages, backHome = pages.stack.isEmpty() && !playerUp, onPlayer = { playerUp = true })
                    }
                }
                // the pages too, while the player is up over them
                androidx.compose.animation.SharedTransitionLayout(if (tv) Modifier.covered(playerUp, pageFocus) else Modifier) { val shared = this
                CompositionLocalProvider(LocalTvStart provides pageFocus) {
                // each page's scroll kept while it's under another (back from a list opened on a genre's page went to
                // the page's top, 2026-11-08: PageScroll), by its place in the stack; let go once it's closed
                LaunchedEffect(pages.stack.toList()) {
                    val now = pages.stack.mapIndexed { i, pg -> "$i:${pg.hashCode()}" }.toSet()
                    PageScroll.kept.keys.retainAll(now)
                }
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
                    // back, one page, where close would take more: a page of browsing over another (2026-11-07: an
                    // artist's fans also like, then another, and only the down arrow, all the way out)
                    val at = pages.stack.indexOf(p)
                    val back: (() -> Unit)? = if (p != null && p.browsing() && at > 0 && pages.stack[at - 1].browsing()) ({ pages.back() }) else null
                    CompositionLocalProvider(LocalOpened provides opened, LocalShared provides shared, LocalPageScope provides this, LocalBack provides back,
                        LocalPageKey provides (if (at >= 0) "$at:${p.hashCode()}" else null)) {
                    when (p) {
                        null -> Box(Modifier.fillMaxSize())
                        is Page.Record -> RecordPage(p.id, pages, p.tube)
                        is Page.Artist -> ArtistPage(p.name, pages)
                        Page.Find -> FindPage(pages.query, { pages.query = it }, pages)
                        // a TV's settings: one short page (TvSettingsPage)
                        Page.Settings -> if (onTv()) TvSettingsPage(pages) else SettingsPage(pages)
                        is Page.Playlist -> PlaylistPage(p.id, p.name, pages)
                        is Page.Tube -> TubeAlbumPage(p.album, pages)
                        is Page.AddTo -> AddToPage(p.song, pages)
                        is Page.AddToMany -> AddToList(p.songs, onDone = { pages.back(); Picking.clear() }, onClose = { pages.close() })
                        is Page.Watch -> WatchPage(p.clip, p.list, pages)
                        is Page.Version -> VersionPage(p.song, pages)
                        is Page.Path -> PathPage(p.song, pages)
                        Page.Loved -> LovedPage(pages)
                        Page.Year -> YearPage(pages)
                        Page.Mapped -> MappedPage(pages)
                        Page.Pick -> ArtistPicker("YOUR ARTISTS", skip = false) { pages.back() }
                        Page.InkRadio -> InkRadioPage(pages)
                        is Page.TubeList -> TubeListPage(p.list, pages)
                        is Page.TubeArtist -> ArtistPage(p.artist.name, pages, p.artist.id)
                        is Page.Weekly -> WeeklyPage(p.week, pages)
                        Page.Kept -> KeptPage(pages)
                        is Page.Smart -> SmartPage(p.id, pages)
                        is Page.SmartEdit -> SmartEditPage(p.id, pages)
                        Page.Import -> ImportPage(pages)
                        Page.Equalizer -> EqualizerPage(pages)
                        Page.HandOver -> HandOverPage(pages)
                        is Page.Mood -> MoodPage(p.mood, pages)
                        is Page.Festival -> FestivalPage(p.name, pages)
                        is Page.Show -> ShowPage(p.feed, p.first, pages)
                        is Page.SettingsPart -> if (p.part == "look") LookPage(pages) else SettingsPage(pages, p.part)
                    }
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
            // a TV: only while something plays; paused or stopped it was in the way (2026-11-03), and "now
            // playing" at the top is the way back to the player
            val playingNow = Playback.now.collectAsState().value?.playing == true
            // nothing playing here, something on another ink device: that, live, steered from here ([Everywhere])
            // (paused there: only while nothing waits here either, the mini player's own song, as it shows one)
            val here = Playback.now.collectAsState().value
            val elsewhere = Everywhere.remote.collectAsState().value?.takeIf { it.playing || here == null || here.stopped }
            // songs picked: what to do with them all, in the mini player's place
            if (picking && !playerUp) PickBar(pages)
            else if (!stopping && !sideways() && !playingNow && elsewhere != null) Box(if (onTv()) Modifier.covered(playerUp) else Modifier) { RemoteMini(frost, elsewhere, onOpen = { remoteUp = true }) }
            else if (!stopping && !sideways() && (!onTv() || playingNow)) Box(if (onTv()) Modifier.covered(playerUp) else Modifier) { MiniPlayer(frost, onOpen = { playerUp = true }) }
        }
        androidx.compose.animation.AnimatedVisibility(
            playerUp,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = if (stopping) slideOutVertically(tween(STOP_MS, easing = Motion.expo)) { it } + fadeOut(tween(STOP_MS, delayMillis = STOP_MS / 2))
                else slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { PlayerPage(onClose = { playerUp = false }, onOpen = { playerUp = false; pages.open(it); if (it == Page.Find) OnAirSong.take()?.let { q -> pages.query = q } }, onStop = { stopping = true; playerUp = false }) }
        val remote = Everywhere.remote.collectAsState().value
        LaunchedEffect(remote == null) { if (remote == null) remoteUp = false }
        androidx.compose.animation.AnimatedVisibility(
            remoteUp && remote != null,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { remote?.let { RemotePlayerPage(it) { remoteUp = false } } }
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
private fun LibraryPage(pages: Pages, backHome: Boolean = false, onPlayer: () -> Unit = {}) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val read by Library.read.collectAsState()
    var tab by rememberSaveable { mutableStateOf("listen") }
    // nothing on the phone or a server: no records, artists or songs to show
    // INK: ink weekly, ink radio and everything people picked, one place (explore till 2026-10-29)
    val tabs = if (songs.isEmpty()) listOf("listen", "ink", "radio", "podcasts", "lists") else listOf("listen", "ink", "albums", "artists", "songs", "radio", "podcasts", "lists")
    val pager = rememberPagerState(tabs.indexOf(tab).coerceAtLeast(0)) { tabs.size }
    // the places' menu, dropped down from the place's word
    var menu by remember { mutableStateOf(false) }
    // the tab row follows a swipe; a tab tapped goes there on ink's curve
    LaunchedEffect(pager, tabs) { snapshotFlow { pager.currentPage }.collect { tab = tabs.getOrElse(it) { "listen" } } }
    LaunchedEffect(tabs) { pager.scrollToPage(tabs.indexOf(tab).coerceAtLeast(0)) }
    val scope = rememberCoroutineScope()
    // back on another tab goes to listen, as a home; back on listen leaves (a tester's back left the app from albums)
    // a TV shows the place on its own, no pager: a remote doesn't swipe, and a pager's pages took no focus from the
    // place's word above them (the onn, 2026-11-03: down went nowhere); places change from the menu
    val tv = onTv()
    val placeIn = remember { androidx.compose.ui.focus.FocusRequester() }
    // a TV: the week read and its covers drawn as it starts, so INK has them at once
    if (tv) {
        val libCtx = LocalContext.current
        LaunchedEffect(Unit) { Weekly.load(libCtx) }
        val weeks by Weekly.weeks.collectAsState()
        LaunchedEffect(weeks.firstOrNull()?.week) { weeks.firstOrNull()?.let { w -> withContext(Dispatchers.IO) { TvMosaic.warm(libCtx, w.songs) } } }
    }
    if (tv) androidx.compose.runtime.DisposableEffect(placeIn) { TvKeys.placeIn = placeIn; onDispose { if (TvKeys.placeIn === placeIn) TvKeys.placeIn = null } }
    // a TV: back from deep in a place goes to the rail, on the place's own word (2026-11-10); from the rail, to listen
    BackHandler(backHome && (if (tv) TvKeys.railAt < 0 || tab != tabs[0] else pager.currentPage != 0)) {
        if (tv && TvKeys.railAt < 0 && TvKeys.railHome != null) { runCatching { TvKeys.railHome?.requestFocus() } }
        else if (tv) tab = tabs[0]
        else scope.launch { pager.animateScrollToPage(0, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) }
    }
    val listenList = rememberLazyListState()
    val exploreList = rememberLazyListState()
    val radioGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val podcastGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
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
                // newest in first: the phone's index's date added, a server song's when the server took it in
                Sort.ADDED -> a.songs.maxOf { it.added }.takeIf { it > 0 }?.toDouble()
                Sort.LATELY -> heard.lately("r:" + a.id)
                Sort.COLOUR -> Colours.place(colours[a.id])
                else -> null
            }
        }
    }
    // the songs tab's songs, as the computer's: the library's and every YouTube song you have (liked there, loved here,
    // kept on the phone), each from where it comes (SongsFrom); and what's kept offline, from anywhere
    val liked by YouTubeAccount.liked.collectAsState()
    val lovedTube by Loved.tube.collectAsState()
    val keptIds by Offline.kept.collectAsState()
    val keptTube = remember(keptIds) { Offline.tubeSongs(ctx).filter { it.id in keptIds } }
    val pool = remember(songs, liked, lovedTube, keptTube) {
        val have = songs.mapTo(HashSet()) { it.id }
        (songs + (liked + lovedTube + keptTube).filter { it.id !in have }.distinctBy { it.id }).sortedBy { Library.sortName(it.title) }
    }
    val counts = remember(pool, keptIds) {
        pool.groupingBy(SongsFrom::of).eachCount() + (pool.count { it.id in keptIds }.takeIf { it > 0 }?.let { mapOf("kept" to it) } ?: emptyMap())
    }
    // the genres your songs are in (each artist's Last.fm tags, Genres), most first; tagged once the songs tab is open
    val tagged by GenrePicks.tagged.collectAsState()
    LaunchedEffect(tab, pool.size) { if (tab == "songs" && pool.isNotEmpty()) GenrePicks.tag(ctx, pool.map { it.artist }) }
    val genres = remember(pool, tagged, tab) {
        if (tab != "songs") emptyList()
        else Genres.names.map { g -> g to pool.count { Genres.fits(it.artist, g) } }.filter { it.second > 0 }.sortedByDescending { it.second }
    }
    val from by SongsFrom.chosen.collectAsState()
    val genre by SongsFrom.genre.collectAsState()
    val everySong = remember(pool, from, genre, keptIds, tagged, sc, seed, heard, minute) {
        val shown = pool.filter { s ->
            (from == "all" || from !in counts || (if (from == "kept") s.id in keptIds else SongsFrom.of(s) == from)) &&
                (genre.isEmpty() || Genres.fits(s.artist, genre))
        }
        Sorts.order(shown, sc, seed, { it.id },
            compareBy<Song>({ Library.sortName(it.albumArtist.ifBlank { it.artist }) }, { it.year }, { Library.sortName(it.album) }, { it.disc }, { it.track })) { s ->
            when (sc.sort) {
                Sort.HOUR -> heard.hour(s.id, minute); Sort.LATELY -> heard.lately(s.id); Sort.MOST -> heard.most(s.id)
                Sort.FORGOTTEN -> heard.forgotten(s.id, now)
                // a timeline: the oldest first
                Sort.YEAR -> s.year.takeIf { it > 0 }?.let { -it.toDouble() }
                // newest in first: the phone's index's date added, a server song's when the server took it in
                Sort.ADDED -> s.added.takeIf { it > 0 }?.toDouble()
                Sort.LENGTH -> s.duration.takeIf { it > 0 }?.toDouble()
                else -> null
            }
        }
    }
    // a new order starts at the top
    LaunchedEffect(ac, seed) { artistList.scrollToItem(0) }
    LaunchedEffect(rc, seed) { grid.scrollToItem(0) }
    LaunchedEffect(sc, seed) { songList.scrollToItem(0) }
    val tabsFrost = rememberHazeState()
    var head by remember { mutableStateOf(0.dp) }
    // pull a list down past its top to find (Pull.kt), as ink's launcher does
    val (pull, pulling) = rememberPull { menu = false; Hints.did(Hints.PULL); pages.open(Page.Find) }
    // a place swiped to: the hint for swiping across is done
    val swiping by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(swiping) { if (swiping) Hints.did(Hints.ACROSS) }
    val density = LocalDensity.current
    val foot = LocalFoot.current
    val sides = bars.only(WindowInsetsSides.Horizontal)
    // the lists start under the tab row (not under its band) and leave room to scroll under the mini player
    val pad = if (tv) PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp)
        else PaddingValues(start = 20.dp, end = 20.dp, top = (head - BAND).coerceAtLeast(0.dp) + 6.dp, bottom = foot + 24.dp)
    Box(Modifier.fillMaxSize()) {
    // a TV: the place between the place's word and the mini player, not under them. Compose's remote looks for the
    // next thing down group by group, and a list running up under the word counted as above it, so down from the word
    // skipped the whole page (the onn, 2026-11-03). Only the soft strip of each bar overlaps it, so what runs out of
    // view still softens there, as on the phone
    // a TV: the place's list feathers into the place's word and the mini player (ink tv's fade, ink's sizes: 28 dp,
    // 44 at the foot), its top once scrolled, its foot while there's more; inside the blur's source, so the soft strip
    // over it shows the faded list too
    val placeList: androidx.compose.foundation.gestures.ScrollableState = when (tab) {
        "listen" -> listenList; "ink" -> exploreList; "radio" -> radioGrid; "podcasts" -> podcastGrid
        "albums" -> grid; "artists" -> artistList; "lists" -> listsList; else -> songList
    }
    // and while the places' menu is down over it, the place takes no focus: the remote is on the menu
    val menuFocus = rememberTvStart()
    // the menu folded, the remote back on the place's word (the library's own start)
    val placeFocus = LocalTvStart.current ?: rememberTvStart()
    TvRefocus(menu) { if (menu) menuFocus else placeFocus }
    Box(Modifier.fillMaxSize().then(if (tv) Modifier.padding(start = TV_RAIL, top = 24.dp, bottom = (foot - BAND).coerceAtLeast(0.dp)).covered(menu, placeFocus) else Modifier)
        .hazeSource(tabsFrost).then(if (tv) Modifier.feather(placeList, 28.dp, 44.dp) else Modifier).windowInsetsPadding(sides)) {
      val place: @Composable (Int) -> Unit = { page ->
       // what scrolls sideways inside a page (a row of covers) keeps its scroll: run to its end, it doesn't turn the page
       Box(Modifier.fillMaxSize().nestedScroll(keepSideways)) {
        when (tabs[page]) {
            // a TV: listen made light, five rows of covers (TvListenPage)
            "listen" -> if (tv) TvListenPage(pages, listenList) else ListenPage(pages, pad, listenList)
            "ink" -> ExplorePage(pages, pad, exploreList)
            // radio, a place of its own: the tower, what's on, every station
            "radio" -> RadioPlace(pages, pad, radioGrid)
            // podcasts, beside radio: find a show, the new episodes of yours, your shows
            "podcasts" -> PodcastsPlace(pages, pad, podcastGrid)
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
                val gridded by ListsGrid.on.collectAsState()
                if (gridded) ListsTiles(pages, pad, minutes) else
                LazyColumn(state = listsList, contentPadding = pad) {
                    item { ListsView() }
                    item {
                        if (lovedIds.isNotEmpty()) ListRow("loved", "${lovedIds.size} ${if (lovedIds.size == 1) "song" else "songs"}", heart = true) { pages.open(Page.Loved) }
                        if (!tv) ListRow("your year", minutes?.let { if (it > 0) "%,d minutes so far".format(it) else "starts now" } ?: "") { pages.open(Page.Year) }
                        if (!tv) ListRow("ink mapped", if (Mapped.SOON) "coming soon" else "your listening as land, these days") { pages.open(Page.Mapped) }
                        // what's on the phone to play with no signal (your server's, YouTube's)
                        val keptIds by Offline.kept.collectAsState()
                        if (keptIds.isNotEmpty()) ListRow("kept on this phone", "${keptIds.size} ${if (keptIds.size == 1) "song" else "songs"}, offline") { pages.open(Page.Kept) }
                        // lists that fill themselves from rules (SmartLists), and a new one
                        SmartRows(pages)
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
                if (counts.size > 1 || genres.isNotEmpty() || genre.isNotEmpty()) item(key = "filters") { SongFilters(counts, genres) }
                itemsIndexed(everySong, key = { _, s -> s.id }) { i, s -> SongRow(s, swipe = false, onClick = { Playback.play(everySong, i) }) }
            }
        }
       }
      }
      // (a TV: the place itself asked for, when its word in the rail is pressed)
      if (tv) Box(Modifier.fillMaxSize().focusRequester(placeIn)) { place(tabs.indexOf(tab).coerceAtLeast(0)) }
      else {
        // pulled, the page follows the finger down and the words under the tab row say what letting go does
        PullHint(pull, Modifier.padding(top = (head - BAND).coerceAtLeast(0.dp)))
        HorizontalPager(
          pager, Modifier.fillMaxSize().nestedScroll(pulling).graphicsLayer { translationY = pull.px }, key = { tabs[it] },
          flingBehavior = stillOnItsPage(pager, PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(Motion.ENTER, easing = Motion.expo))),
        ) { page -> place(page) }
      }
    }
    // a TV: the places as a rail down the left, ink tv's (2026-11-10, in place of the place's word and its menu)
    if (tv) TvRail(tabs, tab, onPlace = { tab = it }, placeIn = placeIn, onFind = { pages.open(Page.Find) }, onPlayer = onPlayer,
        onSettings = { pages.open(Page.Settings) }, onYear = { pages.open(Page.Year) }, foot = foot)
    // the tab row on black; what scrolls up toward it softens just under it (not on a TV: the rail has the places)
    if (!tv) Column(Modifier.fillMaxWidth().onSizeChanged { head = with(density) { it.height.toDp() } }) {
      Column(Modifier.fillMaxWidth().background(Ink.black).windowInsetsPadding(bars)) {
        // the place you're on, one big word (2026-10-28: the row of words got crowded at six); a tap drops
        // every place down as big words, ink's menu (no hamburger: a word where an icon would go). Find stays put.
        // (on a TV, while the menu is down, the remote is on the menu: back folds it)
        Row(Modifier.fillMaxWidth().then(if (tv) Modifier.covered(menu) else Modifier).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // the whole row a tap on the phone; on a TV the word alone, so the remote's lit place is the word
            Row(Modifier.weight(1f).then(if (tv) Modifier.tvFirst() else Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { menu = !menu; Hints.did(Hints.PLACES) })),
                verticalAlignment = Alignment.CenterVertically) {
              Row(if (tv) Modifier.topRow(0).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { menu = !menu }) else Modifier,
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.animation.AnimatedContent(tab, label = "place",
                    transitionSpec = { androidx.compose.animation.fadeIn(tween(Motion.ENTER, easing = Motion.expo)) togetherWith androidx.compose.animation.fadeOut(tween(Motion.EXIT)) },
                ) { t -> BasicText(t.uppercase(), style = headline(26.sp)) }
                Spacer(Modifier.width(10.dp))
                // the menu's chevron: down while shut, up while open
                val turn by androidx.compose.animation.core.animateFloatAsState(if (menu) 180f else 0f, tween(Motion.ENTER, easing = Motion.expo), label = "chevron")
                Sprite(listOf("##...##", ".##.##.", "..###..", "...#..."), Ink.mid, 2.dp, Modifier.graphicsLayer { rotationZ = turn })
              }
            }
            // a TV: what's playing a word away at the top, not a scroll down to the mini player at the foot
            if (tv && Playback.now.collectAsState().value != null) Word("now playing", pixel(14.sp, Ink.mid), Modifier.padding(end = 28.dp).topRow(1), touch = 8.dp, onClick = onPlayer)
            Word("find", pixel(14.sp, Ink.mid), Modifier.topRow(2), touch = 8.dp) { menu = false; pages.open(Page.Find) }
        }
        // the first days: how to find, then the places, typed under the place's word till each is used (Hints)
        if (!tv) {
            val hinted by Hints.done.collectAsState()
            val hint = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
            // the gestures first, everywhere; then the tab's own; then, on listen, the two pointers (once each)
            val key = Hints.next(hinted, Hints.PULL, Hints.PLACES, Hints.ACROSS) ?: when (tab) {
                "albums" -> Hints.next(hinted, Hints.TURN, Hints.ORDER)
                "artists", "songs" -> Hints.next(hinted, Hints.ORDER)
                "listen" -> remember(hinted) { when {
                    Hints.due(hinted, Hints.LOOK, 3) -> Hints.LOOK
                    Hints.due(hinted, Hints.COMPUTER, 14) -> Hints.COMPUTER
                    else -> null
                } }
                else -> null
            }
            when (key) {
                Hints.PULL -> Typed("pull down to find anything", pixel(12.sp, Ink.mid), hint)
                Hints.PLACES -> Typed("tap ${tab.lowercase()} for every place", pixel(12.sp, Ink.mid), hint)
                Hints.ACROSS -> Typed("swipe across for the place beside", pixel(12.sp, Ink.mid), hint)
                Hints.TURN -> Typed("turn your phone sideways for a wall of covers", pixel(12.sp, Ink.mid), hint)
                Hints.ORDER -> Typed("tap an order to change it: year, color, lately", pixel(12.sp, Ink.mid), hint)
                // the pointers, a tap away from what they say
                Hints.LOOK -> Typed("make it yours: settings, the look", pixel(12.sp, Ink.mid), hint.clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { Hints.did(Hints.LOOK); pages.open(Page.SettingsPart("look")) }))
                Hints.COMPUTER -> Typed("ink music for your computer, in step with this one", pixel(12.sp, Ink.mid), hint.clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { Hints.did(Hints.COMPUTER); pages.open(Page.HandOver) }))
            }
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
            // a tap off the words folds it on the phone; a TV's back does that, and the remote lands on the words, not the panel
            Modifier.fillMaxSize().background(Ink.black)
                .then(if (tv) Modifier.padding(bottom = foot).tvStart(menuFocus).verticalScroll(rememberScrollState()) else Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { menu = false })
                .windowInsetsPadding(sides).padding(start = 20.dp, end = 20.dp, top = 10.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                // a TV's screen is short: the places a size smaller, so all of them and settings sit above the mini player
                Word(t.uppercase(), headline(if (tv) 32.sp else 48.sp, if (t == tab) Ink.ink else Ink.mid), Modifier.arrive(i, menu).then(if (t == tab) Modifier.tvFirst() else Modifier), touch = 4.dp) {
                    menu = false
                    if (tv) tab = t
                    else scope.launch { pager.animateScrollToPage(i, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Word("settings", pixel(14.sp, Ink.mid), Modifier.arrive(tabs.size, menu), touch = 8.dp) { menu = false; pages.open(Page.Settings) }
            Word("your year", pixel(14.sp, Ink.mid), Modifier.arrive(tabs.size + 1, menu), touch = 8.dp) { menu = false; pages.open(Page.Year) }
            Word("ink mapped", pixel(14.sp, Ink.mid), Modifier.arrive(tabs.size + 2, menu), touch = 8.dp) { menu = false; pages.open(Page.Mapped) }
            // pairing a computer a tap from anywhere, not deep in settings; once one's paired, its name opens its setting
            if (!tv) {
                val peers by SyncPhone.peers.collectAsState()
                Word(if (peers.isEmpty()) "pair a computer" else "your computer", pixel(14.sp, Ink.mid), Modifier.arrive(tabs.size + 3, menu), touch = 8.dp) {
                    menu = false; pages.open(if (peers.isEmpty()) Page.HandOver else Page.SettingsPart("music"))
                }
            }
            // made by ink, its heart (it beats; seven taps and hearts float up), ink its website: the quiet funnel
            Row(Modifier.arrive(tabs.size + 4, menu).padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
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
    if (!onTv()) androidx.compose.runtime.DisposableEffect(onRecords) {
        activity?.requestedOrientation = if (onRecords) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    if (onRecords && sideways()) {
        LaunchedEffect(Unit) { Hints.did(Hints.TURN) }
        RecordFlow(records) { pages.open(Page.Record(it.id)) }
    }
    }
}

/** The phone turned sideways (only the records can be: [RecordFlow]); a TV, always wide, never is. */
@Composable
fun sideways() = !onTv() && androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

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
internal fun SongRow(s: Song, number: String? = null, pages: Pages? = null, onRemove: (() -> Unit)? = null, swipe: Boolean = true, yours: Boolean = true,
                    cover: Boolean = false, onClick: () -> Unit) {
    val now by Playback.now.collectAsState()
    val on = now?.song?.id == s.id
    var open by remember { mutableStateOf(false) }
    // songs being picked (held, "pick more"): a tap or a hold picks this one or lets it go
    val pickedNow by Picking.picked.collectAsState()
    val picking = pickedNow.isNotEmpty()
    val picked = picking && pickedNow.any { it.id == s.id }
    LaunchedEffect(picking) { if (picking) open = false }
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
        Modifier.fillMaxWidth().offset { IntOffset(slid.value.roundToInt(), 0) }.background(Ink.black).warmOnRest(s)
            // a tap and a hold the remote gives too (OK, OK held): a finger-only gesture left a TV's songs unreachable
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (picking) Picking.toggle(s) else { open = !open; Hints.did(Hints.SWIPE) } },
                onClick = { if (picking) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); Picking.toggle(s) } else if (open) open = false else tap() })
            .then(if (!swipe || picking) Modifier else Modifier.pointerInput(s.id) {
            var pulled = 0f
            fun letGo(act: Boolean) {
                if (act) when (armed) {
                    1 -> { Playback.playNext(s); done = "next"; Hints.did(Hints.SWIPE) }
                    -1 -> { Playback.add(s); done = "queued"; Hints.did(Hints.SWIPE) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
        // picking: a square before it, filled once it's picked
        if (picking) {
            Box(Modifier.size(12.dp).then(if (picked) Modifier.background(Ink.ink) else Modifier.border(1.dp, Ink.mid)))
            Spacer(Modifier.width(14.dp))
        }
        // its record's cover beside it, small and square (a list's covers word)
        if (cover) { CoverOf(s, s.album.ifEmpty { s.title }, Modifier.size(44.dp), px = 160); Spacer(Modifier.width(14.dp)) }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            number?.let { BasicText(it, style = pixel(12.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.width(30.dp).padding(bottom = 3.dp)) }
            Column(Modifier.weight(1f)) {
                BasicText(s.title.uppercase(), style = headline(19.sp, when { on -> Ink.ink; !yours -> if (now == null) Ink.mid else Ink.dim; now == null -> Ink.ink; else -> Ink.mid }), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (number == null) BasicText(s.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // a swipe's word stays a moment where the length was: it's done
            done?.let { BasicText(it, style = pixel(12.sp), modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)) }
                ?: if (s.duration > 0) BasicText(clock(s.duration), style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)) else Unit
        }
        }
        if (open) FlowRow(Modifier.padding(start = (if (cover) 58.dp else 0.dp) + (if (number != null) 30.dp else 0.dp), top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            // this one picked, and then any others tapped: all of them at once from the bar along the foot (PickBar)
            Word("pick more", pixel(13.sp), touch = 8.dp) { open = false; Picking.toggle(s) }
            Word("play next", pixel(13.sp), touch = 8.dp) { open = false; Playback.playNext(s) }
            Word("add to queue", pixel(13.sp), touch = 8.dp) { open = false; Playback.add(s) }
            Word("similar", pixel(13.sp), touch = 8.dp) { open = false; Playback.startRadio(s) }
            val lovedIds by Loved.ids.collectAsState()
            Word(if (s.id in lovedIds) "unlove" else "love", pixel(13.sp), touch = 8.dp) { open = false; Loved.toggle(s) }
            val account by Server.account.collectAsState()
            val tubeWho by YouTubeAccount.who.collectAsState()
            if (pages != null && ((account != null && Server.idOf(s) != null) || (tubeWho != null && !s.station && !s.episode)))
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
            // the wrong YouTube version playing (a live take, the video): the right one's link pasted in (Versions)
            if (pages != null && s.tube && YouTubeMusic.available) Word("youtube version", pixel(13.sp, Ink.mid), touch = 8.dp) { open = false; pages.open(Page.Version(s)) }
            if (pages != null && number == null && s.album.isNotEmpty() && !s.tube) Word("record", pixel(13.sp, Ink.mid), touch = 8.dp) { open = false; pages.open(Page.Record(s.albumId)) }
            if (pages != null) Word("artist", pixel(13.sp, Ink.mid), touch = 8.dp) { open = false; pages.open(Page.Artist(s.albumArtist)) }
        }
    }}
}

/**
 * What critics said of a record, on its page (2026-11-09: "reviews from critics? cool little editorial snippets in
 * nice font?"): each publication that picked it (Picks.said), its first sentence as the record of the day says one,
 * big and in quotes, who said it under it and "read it" to their page. Two at most. Nothing where no critic picked it.
 */
@Composable
fun Critics(title: String, artist: String, most: Int = 2) {
    val tv = onTv()
    val all by Picks.said.collectAsState()
    val said = remember(all, title, artist) { Curation.saidOf(all, title, artist).take(most) }
    val ctx = LocalContext.current
    said.forEach { p ->
        Spacer(Modifier.height(22.dp))
        BasicText("\u201c${p.said}\u201d".uppercase(), style = headline(18.sp).copy(lineHeight = 23.sp), maxLines = 6, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("\u2014 ${p.from.lowercase()}", style = pixel(12.sp, Ink.mid))
            // not on a TV: a review opened in a browser there is no place to read
            if (p.link.isNotBlank() && !tv) Word("read it", pixel(12.sp, Ink.mid), Modifier.padding(start = 16.dp), touch = 8.dp) {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p.link)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
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
    val list = pageList()
    // the cover, the record's name and its words: over the songs on the phone, beside them on a TV
    val head: @Composable (Dp?) -> Unit = { side -> Column(Modifier.cascade(0)) {
            if (side != null) CoverOf(a.songs.first(), a.title, Modifier.size(side))
            else BoxWithConstraints(Modifier.bleed(20.dp), contentAlignment = Alignment.Center) {
                CoverOf(a.songs.first(), a.title, Modifier.size(headerSide(maxWidth)))
            }
            Spacer(Modifier.height(18.dp))
            BasicText(a.title.uppercase(), style = headline(if (side != null) 28.sp else 36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
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
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(whole, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(whole.shuffled(), 0) }
                KeepOffline(a.songs)
            }
            // out of ink music, the files untouched (#1); a hidden one's page says so and brings it back
            val ctx = LocalContext.current
            Word(if (off) "hidden: show it again" else "hide", pixel(12.sp, Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) {
                Library.hide(ctx, a.id, !off); if (!off) pages.back()
            }
            // what critics said of it, where any picked it
            Critics(a.title, a.artist)
            Spacer(Modifier.height(14.dp))
        } }
    val songs: LazyListScope.() -> Unit = {
        // the first records opened: what a song does besides play, till a song's been swiped or held (Hints)
        item(key = "hint") {
            val hinted by Hints.done.collectAsState()
            if (Hints.SWIPE !in hinted && !onTv()) Typed("swipe a song right to play it next, left for the end of the queue. hold it for more",
                pixel(12.sp, Ink.mid), Modifier.padding(bottom = 12.dp))
        }
        itemsIndexed(whole, key = { _, s -> s.id }) { i, s -> Box(Modifier.cascade(i + 1)) {
            // numbered by place on the whole record; a song you don't have a step dimmer
            val number = if (whole.size > a.songs.size) "${i + 1}" else s.track.takeIf { it > 0 }?.toString() ?: "${i + 1}"
            SongRow(s, number = number, pages = pages, yours = s.id in yours) { Playback.play(whole, i) }
        }}
    }
    // the whole record arriving from YouTube: the list kept your first song at its top (a list holds to the row it
    // showed); on a TV, beside the cover, it starts at the record's first song again
    val tv = onTv()
    if (tv) LaunchedEffect(whole.size) { list.scrollToItem(0) }
    FrostedPage(onClose = { pages.close() }) { frost, top ->
        if (tv) TvBeside(frost, top, list, { head(it) }, songs)
        else LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
        ) {
            item { head(null) }
            songs()
        }
    }
}

/**
 * A list's page (a playlist's): on a phone its head over its rows in one list; on a TV the head held still on the left,
 * its rows on the right ([TvBeside]), so play and the rest are a press left, never scrolled away (2026-11-10: pressing up
 * to reach them on a TV closed the page instead).
 */
@Composable
fun ListPageBody(frost: HazeState, top: Dp, list: androidx.compose.foundation.lazy.LazyListState, head: @Composable () -> Unit, rows: LazyListScope.() -> Unit) {
    if (onTv()) TvBeside(frost, top, list, { head() }, rows)
    else LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { head() }
        rows()
    }
}

/**
 * A page on a TV: what it is on the left (a cover and its words, held still), its list on the right; the two side by
 * side on the wide screen, as the computer's are, rather than the phone's one column with the cover the screen's width.
 */
@Composable
fun TvBeside(frost: HazeState, top: Dp, list: androidx.compose.foundation.lazy.LazyListState, side: @Composable (Dp) -> Unit, rows: LazyListScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = (LocalFoot.current - BAND).coerceAtLeast(0.dp))) {
    // the cover as big as leaves its words room under it (a TV's screen is short): the side held still, whole
    val cover = (maxHeight - 250.dp).coerceIn(160.dp, 300.dp)
    // right from the side: the list's first row in view (its top, opened), not whichever row is level with the word
    // (2026-11-10: from radio it went to the fifth song)
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    Row(Modifier.fillMaxSize()) {
        // the side a group: right out of it goes straight to where the list was left (its restorer), never first to the
        // row level with the word (each trip back landed a little higher, 2026-11-10)
        Column(Modifier.width(300.dp).focusProperties {
            onExit = { if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right) first.requestFocus() }
        }.focusGroup().verticalScroll(rememberScrollState())) { side(cover) }
        Spacer(Modifier.width(40.dp))
        // left from the list: the side's first press (play), not whichever word is level with the row (a playlist's
        // "delete from youtube", 2026-11-10)
        val start = LocalTvStart.current
        val go = remember(start) { { start?.first?.let { runCatching { it.requestFocus(); true }.getOrDefault(false) } ?: false } }
        androidx.compose.runtime.DisposableEffect(go) { onDispose { if (TvKeys.besideLeft === go) TvKeys.besideLeft = null } }
        // back from the side: to the row it was on (2026-11-10: from records, over to shuffle and back, it went to yours)
        LazyColumn(state = list, modifier = Modifier.weight(1f).focusRequester(first).focusRestorer().hazeSource(frost).feather(list, 28.dp, 44.dp)
            .onFocusChanged { f -> if (f.hasFocus) TvKeys.besideLeft = go else if (TvKeys.besideLeft === go) TvKeys.besideLeft = null },
            contentPadding = PaddingValues(bottom = 24.dp)) { rows() }
    }
    }
}

/** A subtle heartbeat: a quick double-thump every 1.5 s, gently scaling its content; still where the phone moves less. */
@Composable
private fun Heartbeat(content: @Composable () -> Unit) {
    val less by Moves.less.collectAsState()
    if (less) { content(); return }
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "beat")
    val scale by t.animateFloat(1f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.keyframes { durationMillis = 1500; 1f at 0; 1.07f at 120; 1f at 250; 1.04f at 360; 1f at 520 }), label = "beat")
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { content() }
}

/** A row in "lists": its name big, a word on it small; `heart` sets ink's red pixel heart before the name (loved). */
@Composable
private fun ListRow(name: String, said: String, heart: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (heart) { Sprite(HEART, HeartRed, 3.dp); Spacer(Modifier.width(10.dp)) }
            BasicText(name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
    val covers by ListCovers.on.collectAsState()
    val songs by Library.songs.collectAsState()
    val lovedIds by Loved.ids.collectAsState()
    val tube by Loved.tube.collectAsState()
    val liked by YouTubeAccount.liked.collectAsState()
    val recent by Loved.recent.collectAsState()
    val who by YouTubeAccount.who.collectAsState()
    // your youtube likes live inside loved; this narrows to just them, so it's one list and not a redundant twin
    var onlyLiked by remember { mutableStateOf(false) }
    val only = onlyLiked && who != null && liked.isNotEmpty()
    val theirs = remember(songs, lovedIds, tube, liked, recent, only) {
        val all = if (only) liked else (liked + tube + songs.filter { it.id in lovedIds }).distinctBy { it.id }
        if (recent) all.sortedByDescending { Loved.lovedAt(it) ?: Long.MIN_VALUE }   // stable: the undated keep their order
        else all.sortedBy { Library.sortName(it.title) }
    }
    val mixKey = if (only) "liked" else "loved"
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("LOVED", style = headline(44.sp))
                Spacer(Modifier.width(14.dp)); Heartbeat { Sprite(HEART, HeartRed, 5.dp) }
            }
            Spacer(Modifier.height(8.dp))
            BasicText("${theirs.size} ${if (theirs.size == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
            if (theirs.isNotEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(theirs, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(theirs.shuffled(), 0) }
                CoversWord()
                Spacer(Modifier.weight(1f))
                // the order: the one chosen lit
                Word("latest", pixel(14.sp, if (recent) Ink.ink else Ink.dim), Modifier.padding(end = 20.dp), touch = 8.dp) { Loved.order(true) }
                Word("a to z", pixel(14.sp, if (recent) Ink.dim else Ink.ink), touch = 8.dp) { Loved.order(false) }
            }
            // narrow to just your youtube likes — they live in loved, so there's one list, not two that look alike
            if (who != null && liked.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row {
                    Word("everything", pixel(14.sp, if (!only) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { onlyLiked = false }
                    Word("youtube likes", pixel(14.sp, if (only) Ink.ink else Ink.dim), touch = 8.dp) { onlyLiked = true }
                }
            }
            // mixed by tempo and key: its own line (the row has the order's words too, and ran off the screen)
            if (theirs.size >= 3) { Spacer(Modifier.height(6.dp)); MixWord(mixKey, theirs) }
            MixProgress(mixKey)
            Spacer(Modifier.height(14.dp))
        }}
        itemsIndexed(theirs, key = { _, s -> s.id }) { i, s -> Box(Modifier.cascade(i + 1)) { SongRow(s, pages = pages, cover = covers) { Playback.play(theirs, i) } }}
    } }
}

/**
 * Where to keep songs found here (a Spotify playlist brought in): on YouTube Music, on your server. Each made
 * once (a second tap made a second playlist), then "open it".
 */
@Composable
internal fun KeepIt(name: String, found: List<Song>, pages: Pages) {
    val scope = rememberCoroutineScope()
    // where it's being made ("youtube", "server"), and once made, the page that opens it
    var making by remember(name, found) { mutableStateOf<String?>(null) }
    val made = remember(name, found) { androidx.compose.runtime.mutableStateMapOf<String, Page?>() }
    var said by remember(name, found) { mutableStateOf<String?>(null) }
    val who by YouTubeAccount.who.collectAsState()
    val account by Server.account.collectAsState()
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
        YouTubeAccount.makePlaylistOf(name, found)?.let { (list, n) -> said = "$n songs on youtube music"; true to Page.TubeList(list) }
            ?: run { said = "youtube music didn't take it"; false to null }
    }
    if (account != null) Place("server", "your server") {
        val before = Server.playlists.value.filter { it.name == name }.map { it.id }.toSet()
        val there = found.count { Server.idOf(it) != null }
        if (Server.makePlaylist(name, found)) {
            said = "$there songs on your server (the ones it has)"
            true to Server.playlists.value.firstOrNull { it.name == name && it.id !in before }?.let { Page.Playlist(it.id, it.name) }
        } else { said = "the server didn't take it"; false to null }
    }
    said?.let { Spacer(Modifier.height(10.dp)); BasicText(it, style = pixel(12.sp, Ink.mid)) }
}

/** A playlist's page: its name big, play and shuffle, its songs in its order. */
@Composable
private fun PlaylistPage(id: String, name: String, pages: Pages) {
    val covers by ListCovers.on.collectAsState()
    val edited by Server.edited.collectAsState()
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(id, edited) { songs = Server.playlist(id) }
    val lists by Server.playlists.collectAsState()
    val mine = lists.firstOrNull { it.id == id }?.mine == true
    val scope = rememberCoroutineScope()
    var sure by remember { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(3_000); sure = false } }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> ListPageBody(frost, top, list, head = { Column(Modifier.cascade(0)) {
            BasicText(name.uppercase(), style = headline(if (Device.tv) 30.sp else 44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            val all = songs
            BasicText(
                if (all == null) "reading it" else listOfNotNull("${all.size} ${if (all.size == 1) "song" else "songs"}",
                    all.sumOf { it.duration }.takeIf { it > 0 }?.let { "${it / 60_000} min" }).joinToString("   "),
                style = pixel(12.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) FlowRow {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
                MixWord(name, all, Modifier.padding(end = 26.dp))
                CoversWord(Modifier.padding(end = 26.dp))
                KeepOffline(all)
            }
            MixProgress(name)
            // deleting asks once more: the word says so, lit, for a moment
            if (mine) Word(if (sure) "delete it" else "delete", pixel(12.sp, if (sure) Ink.ink else Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) {
                if (!sure) sure = true else scope.launch { if (Server.deletePlaylist(id)) pages.back() }
            }
            Spacer(Modifier.height(14.dp))
        }}) {
        val all = songs.orEmpty()
        // no keys: a playlist can hold one song twice
        itemsIndexed(all) { i, s -> Box(Modifier.cascade(i + 1)) {
            SongRow(s, pages = pages, cover = covers, onRemove = if (mine) ({
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
        i?.top.orEmpty().take(6).map { t -> theirs.firstOrNull { it.title.equals(t.title, ignoreCase = true) } ?: t }
    }
    val playable = remember(top, theirs) { (top + theirs).distinctBy { it.id } }
    LaunchedEffect(playable.firstOrNull()?.id) { Playback.warm(playable.firstOrNull()) }
    // their records: yours first, then YouTube's that you don't have
    val tubeAlbums = remember(i, records) { i?.albums.orEmpty().filter { t -> records.none { it.title.equals(t.title, ignoreCase = true) } } }
    var aboutOpen by remember(name) { mutableStateOf(false) }
    val list = pageList()
    // opened the first time (nothing kept): the photo's room held and the rest kept back till YouTube Music answers, a
    // second and a half at most, so it comes in once (it showed your songs, then pushed them down under all of it)
    var patient by remember(name, tubeId) { mutableStateOf(true) }
    LaunchedEffect(name, tubeId) { delay(1_500); patient = false }
    val waiting = YouTubeAccount.available && info == null && patient
    // a TV: who they are held still at the left, their music at the right (2026-11-10: the phone's photo, the screen's
    // width, put play far under it; the remote landing there scrolled the top away)
    if (onTv()) {
        FrostedPage(onClose = { pages.close() }) { frost, top0 ->
            TvBeside(frost, top0, list, { room -> TvArtistSide(name, i, room, records.size + tubeAlbums.size, theirs.size, playable, info == null) }) {
                if (top.isNotEmpty()) {
                    item { TvLabel("top songs") }
                    itemsIndexed(top, key = { _, s -> "t" + s.id }) { n, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText("${n + 1}", style = headline(26.sp, Ink.mid), modifier = Modifier.width(40.dp))
                            Box(Modifier.weight(1f)) { SongRow(s.copy(duration = 0), pages = pages) { Playback.play(top, n) } }
                        }
                    }
                }
                if (theirs.isNotEmpty()) {
                    item { TvLabel("yours") }
                    itemsIndexed(theirs.take(30), key = { _, s -> s.id }) { n, s -> SongRow(s, pages = pages) { Playback.play(theirs, n) } }
                }
                if (records.isNotEmpty() || tubeAlbums.isNotEmpty()) item(key = "records") {
                    TvLabel("records")
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(records, key = { it.id }) { a -> RecordTile(a, Modifier.width(140.dp)) {
                            pages.open(Page.Record(a.id, (i?.albums.orEmpty() + i?.singles.orEmpty()).firstOrNull { it.title.equals(a.title, ignoreCase = true) }))
                        } }
                        items(tubeAlbums, key = { it.url }) { r -> TubeTile(r, Modifier.width(140.dp)) { pages.open(Page.Tube(r)) } }
                    }
                }
                i?.singles?.takeIf { it.isNotEmpty() }?.let { singles -> item(key = "singles") {
                    TvLabel("singles and eps")
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(singles, key = { it.url }) { r -> TubeTile(r, Modifier.width(120.dp)) { pages.open(Page.Tube(r)) } }
                    }
                } }
                i?.like?.takeIf { it.isNotEmpty() }?.let { like -> item(key = "like") {
                    TvLabel("fans also like")
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(like, key = { it.id.ifEmpty { it.name } }) { a ->
                            Column(Modifier.width(120.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                                onClick = felt { pages.open(Page.TubeArtist(a)) })) {
                                CoverOf(remember(a.face) { a.face?.let { faceSong(a.name, it) } }, a.name, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                                Spacer(Modifier.height(8.dp))
                                UnderCover(a.name, "artist", pixel(12.sp, Ink.mid))
                            }
                        }
                    }
                } }
            }
        }
        return
    }
    FrostedPage(onClose = { pages.close() }) { frost, top0 -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top0, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            // their photo across the page, in the dither (the player's) developing as it opens or as it is (settings,
            // the look), their moving one over it where Apple Music has one, their name on it
            val photo = i?.photo
            if (photo != null || waiting) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                val side = headerSide(maxWidth)
                val grain by Moving.artistGrain.collectAsState()
                val dithered = Moving.artistDither.collectAsState().value && !grain
                if (photo != null) Box(Modifier.size(side).align(Alignment.Center)) {
                    val still = remember(photo) { photoSong(name, photo) }
                    when {
                        grain -> Grainy(still, Modifier.fillMaxSize())
                        dithered -> Dithered(still, Modifier.fillMaxSize())
                        else -> AsItIs(still, Modifier.fillMaxSize())
                    }
                    val c = rememberCover(still, 1080)
                    val accent = c?.accent ?: Ink.ink
                    // in the grain look their clip moves under the grain, out of focus (the player's blur)
                    ArtistVideo(i.name.ifBlank { name }, accent, when { grain -> Look.BLUR; dithered -> Look.DITHER; else -> null }, Modifier.fillMaxSize())
                    if (grain) FilmGrain(Modifier.fillMaxSize(), moving = true)
                    // as it is (or in the grain), a light photo held down so the name on it reads (nameDim)
                    if (!dithered && c != null) {
                        val dim = remember(c) { nameDim(c.image) }
                        if (dim < 1f) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 1f - dim)))
                    }
                }
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
                    Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(playable, 0) }
                    Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(playable.shuffled(), 0) }
                    Word("similar", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.startRadio(playable.first()) }
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
                    // no times in the chart: YouTube's five come without one, the rest with (2026-11-09: only the sixth said)
                    Box(Modifier.weight(1f)) { SongRow(s.copy(duration = 0), pages = pages) { Playback.play(top, n) } }
                }
            }}
            item { Spacer(Modifier.height(10.dp)) }
        }
        if (records.isNotEmpty() || tubeAlbums.isNotEmpty()) item {
            Label("records")
            LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(singles, key = { it.url }) { r -> TubeTile(r, Modifier.width(130.dp)) { pages.open(Page.Tube(r)) } }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        // their music videos (MusicVideos: the full build's), newest first
        if (i != null && i.id.startsWith("UC")) item(key = "videos") { ArtistVideos(name, i.id, pages) }
        // what's said of them: four lines, a tap for the rest
        // with no bio anywhere, a line ink writes from what it knows (Bio.facts): never an empty space
        (i?.about?.let { Bio.of(it) }?.takeIf { it.first.isNotEmpty() } ?: i?.let { info ->
            val by = Picks.said.value.filter { Curation.folded(it.artist.substringBefore(", ")) == Curation.folded(info.name) }.map { it.from }.distinct()
            Bio.facts(records.size + tubeAlbums.size, info.singles.size, by, info.like.map { it.name }, info.audience)?.let { it to null }
        })?.let { (about, from) ->
            // its first sentence pulled out big, as a lyric is; the rest small under it; where it's from, quietly after
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
                // where it's from only with the whole of it open (2026-11-09: not on the page; the licence asks for a credit)
                if (aboutOpen) from?.let { BasicText("from $it", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 20.dp, top = 10.dp)) }
                Spacer(Modifier.height(22.dp))
            }
        }
        // their own places (Socials: Instagram, TikTok, Bandcamp, their site...), words that open them; read once the
        // page knows their records, which tell one of a shared name
        item(key = "socials") {
            val ready = i != null || !waiting
            val titles = remember(records, i) { records.map { it.title } + i?.albums.orEmpty().map { it.title } }
            val socials by androidx.compose.runtime.produceState(emptyList<Socials.Link>(), name, ready) {
                if (ready) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Socials.of(java.io.File(ctx.cacheDir, "socials"), name, titles) }
            }
            if (socials.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    socials.forEach { l ->
                        Word(l.word, pixel(14.sp, Ink.mid), touch = 8.dp) {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(l.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            }
        }
        // the playlists they're on (YouTube Music's "featured on", and their own), each opening as any list
        i?.featured?.takeIf { it.isNotEmpty() }?.let { lists ->
            item(key = "featured") {
                Label("on playlists")
                LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(lists, key = { it.id }) { l -> Box(Modifier.width(130.dp)) { Tile(null, l.name, "youtube music", face = l.art, dither = true) { pages.open(Page.TubeList(l)) } } }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        i?.like?.takeIf { it.isNotEmpty() }?.let { like ->
            item {
                Label("fans also like")
                LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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

/** A heading over a TV page's list, at the list's own left. */
@Composable
private fun TvLabel(text: String) = BasicText(text.uppercase(), style = headline(18.sp, Ink.mid), modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))

/**
 * An artist on a TV, the left: their photo (still, in the dither or as it is), their name, how many listen and what
 * there is, play, shuffle and radio (the remote starts on play), and the first line said of them.
 */
@Composable
private fun TvArtistSide(name: String, i: ArtistInfo?, room: Dp, records: Int, yours: Int, playable: List<Song>, reading: Boolean) {
    val side = room.coerceAtMost(240.dp)
    Box(Modifier.size(side)) {
        val photo = i?.photo
        if (photo != null) {
            val still = remember(photo) { photoSong(name, photo) }
            // as it is on a TV (2026-11-10), whatever the phone's set
            AsItIs(still, Modifier.fillMaxSize())
        } else Box(Modifier.fillMaxSize().background(Ink.black))
    }
    Spacer(Modifier.height(14.dp))
    BasicText((i?.name ?: name).uppercase(), style = headline(30.sp).copy(lineHeight = 30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(6.dp))
    BasicText(listOfNotNull(
        i?.audience?.takeIf { it.isNotEmpty() }?.lowercase()?.replace("monthly audience", "a month"),
        records.takeIf { it > 0 }?.let { "$it ${if (it == 1) "record" else "records"}" },
        yours.takeIf { it > 0 }?.let { "$it of yours" },
    ).joinToString("  ·  ").ifEmpty { if (reading && YouTubeAccount.available) "reading it from youtube music" else "" }, style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(14.dp))
    if (playable.isNotEmpty()) Row {
        Word("play", pixel(15.sp), Modifier.padding(end = 24.dp).tvFirst(), touch = 8.dp) { Playback.play(playable, 0) }
        Word("shuffle", pixel(15.sp, Ink.mid), Modifier.padding(end = 24.dp), touch = 8.dp) { Playback.play(playable.shuffled(), 0) }
        Word("similar", pixel(15.sp, Ink.mid), touch = 8.dp) { Playback.startRadio(playable.first()) }
    }
    i?.about?.let { Bio.of(it) }?.first?.takeIf { it.isNotEmpty() }?.let { about ->
        val said = remember(about) { pullQuote(about).first ?: about }
        Spacer(Modifier.height(16.dp))
        BasicText(said.lowercase(), style = pixel(12.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 18.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** The songs kept on the phone (your server's, YouTube Music's), to play with no signal; each let go from its options. */
@Composable
private fun KeptPage(pages: Pages) {
    val covers by ListCovers.on.collectAsState()
    val ctx = LocalContext.current
    val kept by Offline.kept.collectAsState()
    val songs = remember(kept) { kept.mapNotNull { Library.song(it) } + Offline.tubeSongs(ctx).filter { it.id in kept } }
        .let { l -> remember(l) { l.distinctBy { it.id }.sortedBy { Library.sortName(it.artist) + it.album + it.title } } }
    val list = pageList()
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
                    Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(songs, 0) }
                    Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(songs.shuffled(), 0) }
                    MixWord("kept", songs, Modifier.padding(end = 26.dp))
                    CoversWord()
                }
                MixProgress("kept")
                Spacer(Modifier.height(10.dp))
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> Box(Modifier.padding(horizontal = 20.dp)) { SongRow(s, pages = pages, cover = covers) { Playback.play(songs, i) } } }
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
                for (c in 0..1) four.getOrNull(r * 2 + c)?.let {
                    if (Device.tv) TvDithered(it, Modifier.size(cell), px) else Covered(it, Modifier.size(cell), px = px)
                } ?: Spacer(Modifier.size(cell))
            }
        }
    }
}

/**
 * A TV's dithered cover, drawn once (no developing in: a dozen frames each on a slow box, every time INK opened, the
 * week's covers always late; 2026-11-10) and kept, so a page come back to has it at once; a short fade the first time.
 */
object TvMosaic {
    private val kept = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(12)
    private fun key(s: Song, px: Int) = (s.art ?: s.albumId) + ":" + px
    fun kept(s: Song, px: Int) = kept.get(key(s, px))

    suspend fun draw(c: android.content.Context, s: Song, px: Int, density: Float): androidx.compose.ui.graphics.ImageBitmap? {
        kept.get(key(s, px))?.let { return it }
        val cover = Covers.of(c, s, px) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            Looks.draw(cover.image.asAndroidBitmap(), Look.DITHER, cover.accent, px.toFloat(), density)?.asImageBitmap()
        }?.also { kept.put(key(s, px), it) }
    }

    /** This week's four drawn ahead, at the size INK shows them on a TV (half a header: [headerSide]). */
    suspend fun warm(c: android.content.Context, songs: List<Song>) {
        val m = c.resources.displayMetrics
        val h = m.heightPixels / m.density
        val px = (minOf(420f, h * .55f) / 2f * m.density).roundToInt()
        songs.distinctBy { it.art ?: it.albumId }.take(4).forEach { runCatching { draw(c, it, px, m.density) } }
    }
}

@Composable
private fun TvDithered(s: Song, modifier: Modifier, px: Int) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val had = remember(s, px) { TvMosaic.kept(s, px) }
    val img by androidx.compose.runtime.produceState(had, s, px) { if (value == null) value = TvMosaic.draw(ctx, s, px, density) }
    val shown = remember(s, px) { Animatable(if (had != null) 1f else 0f) }
    LaunchedEffect(img) { if (img != null) shown.animateTo(1f, tween(300)) }
    Box(modifier.background(Ink.black)) {
        img?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }, contentScale = ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None) }
    }
}

/** A week of ink weekly: its mosaic across the page, its name and note, its songs as a chart, the weeks before. */
@Composable
private fun WeeklyPage(number: Int, pages: Pages) {
    val weeks by Weekly.weeks.collectAsState()
    val w = weeks.firstOrNull { it.week == number } ?: weeks.firstOrNull() ?: return
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            // its name on its covers, on a black tab in their corner (a sleeve's label: pale covers would swallow it)
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                Mosaic(w.songs, Modifier.size(headerSide(maxWidth)).align(Alignment.Center))
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
                    Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(w.songs, 0) }
                    Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(w.songs.shuffled(), 0) }
                    MixWord("ink weekly $number", w.songs)
                }
                MixProgress("ink weekly $number")
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
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = runCatching { Import.read(ctx, uri) }.getOrDefault(emptyList())
            lists = read; chosen = read.singleOrNull(); matched = null
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
        progress = 0; matched = null; said = null
        matched = Import.match(c.songs) { progress = it }
    }
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
                    KeepIt(c.name, found, pages)
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
fun pullQuote(about: String): Pair<String?, String> = Bio.pullQuote(about)

/**
 * A picture in the dither (the player's), in its own colour, developing from black (the brightest dots first) once
 * it's read: an artist's photo, and YouTube's mixes and playlists (their own lettering made ink's, a colour to see), these
 * in the look chosen for them ([Covered]).
 */
@Composable
fun Dithered(photo: Song, modifier: Modifier, px: Int = 1080, print: Print = Print.DITHER) {
    // the grain look is a picture, not dots: drawn apart
    if (print == Print.GRAIN) return Grainy(photo, modifier, px)
    val cover = rememberCover(photo, px)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(androidx.compose.ui.graphics.Color.Black)) {
        val side = with(density) { maxWidth.toPx() }
        val frames by androidx.compose.runtime.produceState<List<androidx.compose.ui.graphics.ImageBitmap>?>(null, cover, print) {
            val c = cover ?: return@produceState
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                Looks.develop(c.image.asAndroidBitmap(), c.accent, side, density.density, print = print)?.map { it.asImageBitmap() }
            }
        }
        val shown = remember(frames) { Animatable(0f) }
        LaunchedEffect(frames) { if (frames != null) { if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, tween(900, easing = Motion.expo)) } }
        frames?.let { f ->
            // the dots drawn as dots, each exactly a whole number of pixels (Looks.cell), the grid centered and cut by
            // the edges, never stretched to fit (2026-11-11: dots of two sizes where a dot came to 7.5 pixels)
            val cell = Looks.cell(density.density)
            val frame = f[((shown.value * f.size).toInt()).coerceIn(0, f.size - 1)]
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val w = frame.width * cell; val h = frame.height * cell
                drawImage(frame, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(frame.width, frame.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(w, h), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            }
        }
    }
}

/** A mix's or a list's picture in the look chosen for them (settings, the look, COVERS): the dither, tiles or grain. */
@Composable
fun Covered(photo: Song, modifier: Modifier, px: Int = 1080) = Dithered(photo, modifier, px, CoverPrint.on.collectAsState().value)

/** A picture as it is, filling its space (an artist's photo with the dither off). */
@Composable
private fun AsItIs(photo: Song, modifier: Modifier, px: Int = 1080) {
    val cover = rememberCover(photo, px)
    Box(modifier.background(androidx.compose.ui.graphics.Color.Black)) {
        cover?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
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
    // (2026-10-29: Mac DeMarco found, no page of his to open)
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
    val list = pageList()
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
            LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
            LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(stations, key = { "r" + it.id }) { s -> StationTile(s, false, false, s.artist) { Playback.play(listOf(s), 0) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        // what YouTube Music has: its artists, records, songs, then playlists
        if (tubeArtists.isNotEmpty()) item {
            Label("artists on youtube music")
            LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
            LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
            LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
        if (Curation.tubeBranded(a.url, a.artist) && a.art != null) Covered(face, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
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
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column(Modifier.cascade(0)) {
            BoxWithConstraints(Modifier.bleed(20.dp), contentAlignment = Alignment.Center) {
                // YouTube's own (its mixes, liked music...): in the dither, as on its tile, never its branding
                val face = remember(a.url) { tubeFace(a) }
                if (Curation.tubeBranded(a.url, a.artist) && a.art != null) Covered(face, Modifier.size(headerSide(maxWidth)), px = 1080)
                else CoverOf(face, a.title, Modifier.size(headerSide(maxWidth)))
            }
            Spacer(Modifier.height(18.dp))
            BasicText(a.title.uppercase(), style = headline(36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            // a record's artist, their page; a playlist's byline (its maker, "auto playlist") only words
            if (a.playlist || Curation.tubeBranded(a.url, a.artist)) BasicText(a.artist.lowercase(), style = pixel(13.sp, Ink.mid))
            else Word(a.artist.lowercase(), pixel(13.sp, Ink.mid), touch = 6.dp) { pages.open(Page.Artist(a.artist)) }
            Spacer(Modifier.height(4.dp))
            val all = songs
            BasicText(if (all == null) "reading it from youtube music" else "${all.size} songs   youtube music", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
                // a playlist or mix played as a DJ's arc, each into the next on the beat (a record keeps its own order)
                if (a.playlist) MixWord(a.title, all, Modifier.padding(end = 26.dp))
                // signed in: saved to your library there (lit), or not
                val who by YouTubeAccount.who.collectAsState()
                val records by YouTubeAccount.records.collectAsState()
                if (who != null) {
                    val saved = remember(records, a.url) { YouTubeAccount.saved(a) }
                    val scope = rememberCoroutineScope()
                    Word(if (saved) "saved" else "save", pixel(14.sp, if (saved) Ink.ink else Ink.mid), touch = 8.dp) { scope.launch { YouTubeAccount.save(a, !saved) } }
                }
            }
            // kept on the phone: its own line under the row (as the computer's: beside the rest it was cut short)
            if (!all.isNullOrEmpty()) Box(Modifier.padding(top = 10.dp)) { KeepOffline(all) }
            // the mix as it's found: its tempo beating, the songs dropping in
            if (a.playlist) MixProgress(a.title)
            // what critics said of it (a record's, not a playlist's)
            if (!a.playlist) Critics(a.title, a.artist)
            Spacer(Modifier.height(14.dp))
        }}
        val all = songs.orEmpty()
        itemsIndexed(all) { i, s -> Box(Modifier.cascade(i + 1)) { SongRow(s, number = "${i + 1}") { Playback.play(all, i) } }}
    } }
}

/** One of your playlists on YouTube Music: its name big, play and shuffle, its songs (read only: change it there). */
@Composable
private fun TubeListPage(pl: TubeList, pages: Pages) {
    val covers by ListCovers.on.collectAsState()
    // as it was last read at once, else page by page as it comes
    val songs by androidx.compose.runtime.produceState<List<Song>?>(null, pl.id) {
        YouTubeAccount.playlist(pl.id).collect { value = it }
        if (value == null) value = emptyList()
    }
    LaunchedEffect(songs?.firstOrNull()?.id) { Playback.warm(songs?.firstOrNull()) }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> ListPageBody(frost, top, list, head = { Column(Modifier.cascade(0)) {
            BasicText(pl.name.uppercase(), style = headline(if (Device.tv) 30.sp else 44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            val all = songs
            BasicText(
                if (all == null) "reading it from youtube music" else listOfNotNull("${all.size} ${if (all.size == 1) "song" else "songs"}",
                    all.sumOf { it.duration }.takeIf { it > 0 }?.let { "${it / 60_000} min" }, "youtube music").joinToString("   "),
                style = pixel(12.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) FlowRow {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
                MixWord(pl.name, all, Modifier.padding(end = 26.dp))
                CoversWord(Modifier.padding(end = 26.dp))
                KeepOffline(all)
            }
            MixProgress(pl.name)
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
        }}) {
        val all = songs.orEmpty()
        // no keys: a playlist can hold one song twice
        itemsIndexed(all) { i, s -> Box(Modifier.cascade(i + 1)) { SongRow(s, cover = covers) { Playback.play(all, i) } }}
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
    return s != null && ((account != null && Server.idOf(s) != null) || (who != null && !s.station && !s.episode))
}

/**
 * The playlists a song can go into, as a page over what's below (the held song's, or the player's): a new one, or one
 * of yours on your server or YouTube Music; `onDone` with the list's name once it's in.
 */
@Composable
fun AddToList(s: Song, onDone: (String) -> Unit, onClose: () -> Unit) = AddToList(listOf(s), onDone, onClose)

/** Several songs into one playlist (picked: Picking): the server's ones there, every one found on YouTube Music there. */
@Composable
fun AddToList(songs: List<Song>, onDone: (String) -> Unit, onClose: () -> Unit) {
    val s = songs.first()
    val onServer = songs.filter { Server.idOf(it) != null }
    val onTube = songs.filter { !it.station && !it.episode }
    val lists by Server.playlists.collectAsState()
    val account by Server.account.collectAsState()
    val server = account != null && onServer.isNotEmpty()
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
            BasicText(if (songs.size == 1) s.title.lowercase() else "${songs.size} songs", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            if (failed) BasicText("that didn't take", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(bottom = 8.dp))
            if (server) {
                if (who != null) Label("on your server")
                NewPlaylist(s, focused = mine.isEmpty() && who == null, make = { Server.makePlaylist(it, onServer) }) { ok -> if (ok) onDone("a new playlist") else failed = true }
                if (mine.isEmpty()) BasicText("no playlists of yours on your server yet: new > makes one, named as you type", style = pixel(12.sp, Ink.mid),
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
            }
        }
        items(mine, key = { it.id }) { pl ->
            Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                onClick = felt { scope.launch { if (onServer.map { Server.addTo(pl.id, it) }.all { it }) onDone(pl.name) else failed = true } }).padding(vertical = 9.dp)) {
                BasicText(pl.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("${pl.count} ${if (pl.count == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
            }
        }
        // your playlists on YouTube Music (signed in): a song from elsewhere found there by its words
        if (who != null) {
            item {
                Spacer(Modifier.height(10.dp))
                Label("on youtube music")
                NewPlaylist(s, focused = !server, make = { if (onTube.size == 1) YouTubeAccount.makePlaylist(it, s) else YouTubeAccount.makePlaylistOf(it, onTube) != null }) { ok -> if (ok) onDone("a new playlist") else failed = true }
                Spacer(Modifier.height(14.dp))
            }
            items(tubeLists, key = { "y" + it.id }) { pl ->
                Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { scope.launch { if (onTube.map { YouTubeAccount.addTo(pl, it) }.all { it }) onDone(pl.name) else failed = true } }).padding(vertical = 9.dp)) {
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
    val list = pageList()
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
                        Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(way, 0) }
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
 * A section's head on INK (2026-10-29: the small labels were hard to read): its name in Archivo Black, a short
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
            // (not on a TV: up at a list's top there, a scroll past it, counted as a pull and closed the page, 2026-11-10;
            // back closes it there)
            .then(if (Device.tv) Modifier else Modifier.nestedScroll(pulling)).graphicsLayer { translationY = pull }.background(Ink.black),
    ) {
        // a TV: the page between its close row and the mini player, overlapping only their soft strips (so what runs
        // out of view still softens), as the library's place is: the remote finds the page under the close arrow
        // then, and starts on the page, not on the arrow (it went close, then straight to the mini player)
        if (onTv()) {
            val foot = LocalFoot.current
            val inside = rememberTvStart()
            val fm = androidx.compose.ui.platform.LocalFocusManager.current
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(Motion.ENTER + 120L); inside.enter(fm) }
            Box(Modifier.fillMaxSize().padding(top = top, bottom = (foot - BAND).coerceAtLeast(0.dp)).tvStart(inside)) {
                CompositionLocalProvider(LocalFoot provides BAND, LocalTvStart provides inside) { content(frost, 0.dp) }
            }
        } else content(frost, top)
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(bar).background(Ink.black))
            if (onClose != null) Row(
                Modifier.fillMaxWidth().height(CLOSE_ROW).background(Ink.black).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // back beside it (a TV's remote has its own)
                LocalBack.current?.takeIf { !Device.tv }?.let { b -> Pixel(BACK_ARROW, "back", Ink.mid, Modifier.offset(x = (-8).dp), onClick = b) }
                Spacer(Modifier.weight(1f))
                CloseArrow(onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = false))
        }
    }
}

/** When the page on top was opened (uptime ms), going forward; 0 going back, or for the library under them. */
val LocalOpened = androidx.compose.runtime.compositionLocalOf { 0L }
/** One page back, where close would close more than this one (a trail of browsing); null where close is back. */
val LocalBack = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }
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
fun CoverOf(song: Song?, name: String, modifier: Modifier, px: Int = 640) {
    val cover = rememberCover(song, px)
    // 1-bit mode (the clickwheel three turns backwards): every cover in the dither, its own colour
    if (OneBit.on.collectAsState().value && cover != null && song != null && !song.station) return Dithered(song, modifier, px = 600)
    // a cover on its way: black a moment, the name cover only for a record with none (it flashed at each new song)
    val late by androidx.compose.runtime.produceState(false, song?.albumId) { kotlinx.coroutines.delay(1_500); value = true }
    // a cover come from the network or the disk develops in from black, as a print does (2026-11-08, Bronson: "loading
    // in can feel abrupt"); one already in memory (scrolled back to) is there at once, and with less motion, always
    val there = remember(song?.albumId) { cover != null }
    val shown = remember(song?.albumId) { Animatable(if (there) 1f else 0f) }
    LaunchedEffect(cover != null) {
        if (cover != null && shown.value < 1f) { if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, tween(DEVELOP, easing = Motion.expo)) }
    }
    BoxWithConstraints(modifier) {
        if (song?.station == true && cover == null) StationCover(song, Modifier.fillMaxSize())
        else if ((cover == null || shown.value < 1f) && (late || song == null)) NameCover(name, maxWidth)
        if (cover != null) Image(cover.image, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }, contentScale = ContentScale.Crop)
    }
}

/** How long a cover takes to develop in (ms): the dither's 900 made quicker, a shelf's worth arriving at once. */
private const val DEVELOP = 600

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
                // a DJ set: the track playing in it, typed out as it's heard (SetHeard)
                val inSet by SetHeard.now.collectAsState()
                if (SetHeard.isSet(n)) inSet?.let { t -> TypedLine("now: ${t.artist.lowercase()} - ${t.title.lowercase()}", pixel(12.sp), Modifier.padding(top = 2.dp)) }
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

/** A list's word for covers beside its songs, lit when on (one choice for every list: [ListCovers]). */
@Composable
private fun CoversWord(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val on by ListCovers.on.collectAsState()
    Word("covers", pixel(14.sp, if (on) Ink.ink else Ink.dim), modifier, touch = 8.dp) { ListCovers.set(ctx, !on) }
}

/** Lists as a list or a grid: two words, the one chosen lit. */
@Composable
private fun ListsView() {
    val ctx = LocalContext.current
    val grid by ListsGrid.on.collectAsState()
    Row(Modifier.padding(bottom = 10.dp)) {
        Word("list", pixel(14.sp, if (!grid) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { ListsGrid.set(ctx, false) }
        Word("grid", pixel(14.sp, if (grid) Ink.ink else Ink.dim), touch = 8.dp) { ListsGrid.set(ctx, true) }
    }
}

/** A list as a tile: its art square, its name and what it holds under it. */
@Composable
private fun ListTile(name: String, said: String, onClick: () -> Unit, heart: Boolean = false, art: @Composable () -> Unit) {
    Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Ink.black)) {
            art()
            // loved: ink's red heart in the corner (the one colour that isn't the art)
            if (heart) Box(Modifier.align(Alignment.TopStart).background(Ink.black).padding(7.dp)) { Sprite(HEART, HeartRed, 3.dp) }
        }
        Spacer(Modifier.height(8.dp))
        UnderCover(name, said, pixel(12.sp, Ink.mid), gap = 3.dp)
    }
}

/** A list's art from its songs: four of its records' covers, two by two; fewer records, the first; none, its name. */
@Composable
private fun Mosaic(songs: List<Song>, name: String) {
    val four = remember(songs) { songs.distinctBy { it.albumId }.take(4) }
    when {
        four.size == 4 -> Column(Modifier.fillMaxSize()) {
            four.chunked(2).forEach { row -> Row(Modifier.weight(1f)) { row.forEach { s -> CoverOf(s, s.album, Modifier.weight(1f).aspectRatio(1f), px = 320) } } }
        }
        four.isNotEmpty() -> CoverOf(four[0], four[0].album, Modifier.fillMaxSize(), px = 480)
        else -> Lettered(name)
    }
}

/**
 * The lists tab as a grid: loved, liked on YouTube Music and kept as mosaics of their records; your year its name; your
 * server's playlists their own art (Navidrome's mosaic); YouTube's lists theirs (YouTube's own made ink's, dithered).
 */
@Composable
private fun ListsTiles(pages: Pages, pad: PaddingValues, minutes: Long?) {
    val ctx = LocalContext.current
    val songs by Library.songs.collectAsState()
    val lovedIds by Loved.ids.collectAsState()
    val lovedTube by Loved.tube.collectAsState()
    val liked by YouTubeAccount.liked.collectAsState()
    val tubeWho by YouTubeAccount.who.collectAsState()
    val keptIds by Offline.kept.collectAsState()
    val lists by Server.playlists.collectAsState()
    val tubeLists by YouTubeAccount.playlists.collectAsState()
    val hidden by HiddenLists.ids.collectAsState()
    val account by Server.account.collectAsState()
    val loved = remember(songs, lovedIds, lovedTube, liked) {
        (songs.filter { it.id in lovedIds } + lovedTube + liked).distinctBy { it.id }.sortedByDescending { Loved.lovedAt(it) ?: Long.MIN_VALUE }
    }
    val kept = remember(keptIds) { (keptIds.mapNotNull { Library.song(it) } + Offline.tubeSongs(ctx).filter { it.id in keptIds }).distinctBy { it.id } }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), contentPadding = pad,
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "view") { ListsView() }
        if (lovedIds.isNotEmpty()) item(key = "loved") {
            ListTile("loved", "${lovedIds.size} ${if (lovedIds.size == 1) "song" else "songs"}", { pages.open(Page.Loved) }, heart = true) { Mosaic(loved, "loved") }
        }
        // (not on a TV: your year's a phone's thing, 2026-11-10)
        if (!Device.tv) item(key = "year") {
            ListTile("your year", minutes?.let { if (it > 0) "%,d minutes so far".format(it) else "starts now" } ?: "", { pages.open(Page.Year) }) {
                YearArt(minutes ?: 0)
            }
        }
        // ink mapped beside it: your listening as land (2026-11-11)
        if (!Device.tv) item(key = "mapped") {
            ListTile("ink mapped", if (Mapped.SOON) "coming soon" else "your listening as land", { pages.open(Page.Mapped) }) { MappedArt() }
        }
        if (kept.isNotEmpty()) item(key = "kept") {
            ListTile("kept on this phone", "${kept.size} ${if (kept.size == 1) "song" else "songs"}, offline", { pages.open(Page.Kept) }) { Mosaic(kept, "kept") }
        }
        if (account != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "new") { NewPlaylist(null) {} }
        items(lists, key = { it.id }) { pl ->
            ListTile(pl.name, "${pl.count} ${if (pl.count == 1) "song" else "songs"}", { pages.open(Page.Playlist(pl.id, pl.name)) }) {
                // the server's own art for it (Navidrome's mosaic of its songs), by a song-shaped stand-in
                val face = remember(pl.id, pl.art) { pl.art?.let { Song("npl" + pl.id, android.net.Uri.EMPTY, pl.name, "", pl.name, "npl" + pl.id, "", 0, 1, 0, 0, art = it) } }
                if (face != null) CoverOf(face, pl.name, Modifier.fillMaxSize(), px = 480) else Lettered(pl.name)
            }
        }
        items(tubeLists.filter { it.id !in hidden }, key = { "y" + it.id }) { pl ->
            ListTile(pl.name, listOf("youtube music", pl.said.lowercase()).filter { it.isNotEmpty() }.joinToString("   "), { pages.open(Page.TubeList(pl)) }) {
                val a = remember(pl.id) { TubeAlbum("https://music.youtube.com/playlist?list=${pl.id}", pl.name, "", pl.art) }
                val face = remember(pl.id) { tubeFace(a) }
                if (pl.art == null) Lettered(pl.name)
                else if (Curation.tubeBranded(a.url, pl.said)) Covered(face, Modifier.fillMaxSize(), px = 400)
                else CoverOf(face, pl.name, Modifier.fillMaxSize(), px = 480)
            }
        }
    }
}

/**
 * A list with no picture of its own: its name set big in ink's headline on black, from the foot up, a hairline round
 * it (the grey name cover read as a missing picture).
 */
@Composable
private fun Lettered(name: String) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Ink.black).border(1.dp, Ink.line)) {
        val size = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth * .15f).toSp() }
        BasicText(name.uppercase(), style = headline(size).copy(lineHeight = size * 1.02f), maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

/**
 * Your year as a tile: the year big (the minutes under the tile), and along the top edge the line of how far the year
 * has gone, filling as the progress line does.
 */
@Composable
private fun YearArt(minutes: Long) {
    val now = remember { java.time.LocalDate.now() }
    val gone = (now.dayOfYear - 1f) / now.lengthOfYear()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Ink.black).border(1.dp, Ink.line)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.line)) { Box(Modifier.fillMaxWidth(gone).fillMaxSize().background(Ink.ink)) }
        val size = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth * .3f).toSp() }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            BasicText("${now.year}", style = headline(size).copy(lineHeight = size), maxLines = 1)
        }
    }
}


/** The page being drawn's place in the stack ("index:page"), for what it keeps while it's under another. */
val LocalPageKey = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** Each page's scroll, kept while it's under another page (its item and how far into it), let go once it's closed. */
object PageScroll { val kept = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>() }

/**
 * A page's list, begun where it was left when it's come back to (2026-11-08, Bronson: "can it remember where you were
 * down the page instead of going back to the top"): kept as the page is covered, read as it's drawn again.
 */
@Composable
fun pageList(): androidx.compose.foundation.lazy.LazyListState {
    val k = LocalPageKey.current
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
    if (k != null) androidx.compose.runtime.DisposableEffect(k, state) {
        onDispose { PageScroll.kept[k] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    return state
}

/**
 * How far to dim an artist's photo shown as it is so their white name reads over its foot (2026-11-09: Yard Act's light
 * photo took the name): the part the name covers (the foot of the square cut from the middle, as it's drawn) judged by
 * its light parts, the brightest tenth (by the average, Yard Act's black shirts hid the cream wall the letters cross),
 * brought down to .4 at most, evenly, never below .4 of itself; a dark photo stays as it is (1). As the canvas behind
 * the player is held down: evenly, no gradient.
 */
fun nameDim(image: androidx.compose.ui.graphics.ImageBitmap): Float {
    val w = image.width; val h = image.height
    val side = minOf(w, h); val left = (w - side) / 2; val top = (h - side) / 2
    val from = top + side * 7 / 10
    val rows = top + side - from
    val px = IntArray(side * rows)
    runCatching { image.readPixels(px, left, from, side, rows) }.onFailure { return 1f }
    val step = maxOf(1, side / 96)
    val lights = ArrayList<Float>()
    for (y in 0 until rows step step) for (x in 0 until side step step) {
        val c = px[y * side + x]
        lights += (.299f * (c shr 16 and 255) + .587f * (c shr 8 and 255) + .114f * (c and 255)) / 255f
    }
    if (lights.isEmpty()) return 1f
    lights.sort()
    val light = lights[lights.size * 9 / 10]
    return if (light <= .4f) 1f else (.4f / light).coerceAtLeast(.4f)
}


/**
 * Where the songs tab's songs come from, and the genre it shows, as the computer's (2026-11-10): everything, this phone's
 * files, your server, YouTube, or what's kept offline from wherever it came; kept between starts.
 */
object SongsFrom {
    private var prefs: android.content.SharedPreferences? = null
    val chosen = kotlinx.coroutines.flow.MutableStateFlow("all")
    /** The genre shown, "" for every one. */
    val genre = kotlinx.coroutines.flow.MutableStateFlow("")
    val WORDS = listOf("all" to "everything", "phone" to "this ${Device.word}", "server" to "your server", "youtube" to "youtube", "kept" to "kept offline")

    fun start(c: android.content.Context) {
        val p = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).also { prefs = it }
        chosen.value = p.getString("songs-from", null) ?: "all"
        genre.value = p.getString("songs-genre", null).orEmpty()
    }
    fun choose(f: String) { chosen.value = f; prefs?.edit()?.putString("songs-from", f)?.apply() }
    /** A genre shown ("": every genre). */
    fun setGenre(g: String) { genre.value = g; prefs?.edit()?.putString("songs-genre", g)?.apply() }
    fun of(s: Song) = when { s.tube -> "youtube"; s.server -> "server"; else -> "phone" }
}

/**
 * The songs tab's filters under its orders: two drop-downs side by side (2026-11-10: the sources and the genres in one
 * wrapping line were messy and hard to tap), where they come from (everything, this phone, your server, youtube, kept
 * offline) and the genre (every genre, or one, most first). Each shows what's chosen with an arrow; tapped, its list
 * drops down, a row a tap, with how many. A source and a genre together narrow it to both.
 */
@Composable
private fun SongFilters(counts: Map<String, Int>, genres: List<Pair<String, Int>>) {
    val from by SongsFrom.chosen.collectAsState()
    val genre by SongsFrom.genre.collectAsState()
    val now = if (from in counts) from else "all"
    val sources = SongsFrom.WORDS.filter { (k, _) -> k == "all" || k in counts }
    // the chosen genre always there, even with none of its songs tagged yet
    val allGenres = if (genre.isNotEmpty() && genres.none { it.first == genre }) genres + (genre to 0) else genres
    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        if (counts.size > 1) DropWord(sources.first { it.first == now }.second, now != "all",
            listOf(Triple("everything", null, now == "all")) + sources.filter { it.first != "all" }.map { (k, w) -> Triple(w, counts[k], k == now) }) { i ->
            SongsFrom.choose(if (i == 0) "all" else sources.filter { it.first != "all" }[i - 1].first)
        }
        if (allGenres.isNotEmpty()) DropWord(genre.ifEmpty { "every genre" }, genre.isNotEmpty(),
            listOf(Triple("every genre", null, genre.isEmpty())) + allGenres.map { (g, n) -> Triple(g, n.takeIf { it > 0 }, g == genre) }) { i ->
            SongsFrom.setGenre(if (i == 0) "" else allGenres[i - 1].first)
        }
    }
}

/**
 * A word with an arrow that drops a list down under it: what's chosen lit (dim while it's the "every" one), each row a
 * whole line to tap, its count beside it. Black, a hairline round it, scrolling past a screen's half.
 */
@Composable
private fun DropWord(word: String, lit: Boolean, rows: List<Triple<String, Int?, Boolean>>, onPick: (Int) -> Unit) {
    var down by remember { mutableStateOf(false) }
    // the list there while it's down or still folding away (2026-11-10: it unfolds from under the word, its rows rising
    // in one after another as the places menu's do, and folds back up)
    val open = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    open.targetState = down
    val less by Moves.less.collectAsState()
    Box {
        Row(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { down = !down })
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(word, style = pixel(14.sp, if (lit || down) Ink.ink else Ink.mid))
            // the arrow turns over as it drops
            val turn by androidx.compose.animation.core.animateFloatAsState(if (down) 180f else 0f, tween(Motion.ENTER, easing = Motion.expo), label = "arrow")
            Sprite(GENRES_DOWN, if (lit || down) Ink.ink else Ink.mid, 2.dp, Modifier.padding(start = 6.dp).graphicsLayer { rotationZ = turn })
        }
        if (open.currentState || open.targetState) androidx.compose.ui.window.Popup(
            offset = with(LocalDensity.current) { androidx.compose.ui.unit.IntOffset(0, 40.dp.roundToPx()) },
            onDismissRequest = { down = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                open,
                enter = if (less) fadeIn(tween(Motion.FADE)) else androidx.compose.animation.expandVertically(tween(Motion.ENTER, easing = Motion.expo), expandFrom = Alignment.Top) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
                exit = if (less) fadeOut(tween(Motion.FADE)) else androidx.compose.animation.shrinkVertically(tween(Motion.EXIT, easing = Motion.expo), shrinkTowards = Alignment.Top) + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
            ) {
                Column(Modifier.widthIn(min = 200.dp).background(Ink.black).border(1.dp, Ink.line).heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    rows.forEachIndexed { i, (w, n, on) ->
                        // each row rising in 30 ms after the one before (the first dozen; the rest with the last)
                        val shown = remember { androidx.compose.animation.core.Animatable(if (less) 1f else 0f) }
                        LaunchedEffect(Unit) { if (!less) { kotlinx.coroutines.delay(minOf(i, 12) * 30L); shown.animateTo(1f, tween(Motion.ENTER, easing = Motion.expo)) } }
                        Row(Modifier.fillMaxWidth().graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 12.dp.toPx() }
                            .clickable(remember { MutableInteractionSource() }, indication = null,
                                onClick = felt { onPick(i); down = false }).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(w, style = pixel(15.sp, if (on) Ink.ink else Ink.mid), modifier = Modifier.weight(1f, fill = false))
                            n?.let { BasicText("  $it", style = pixel(12.sp, Ink.dim)) }
                        }
                    }
                }
            }
        }
    }
}

private val GENRES_DOWN = listOf("#...#", ".#.#.", "..#..")
private val GENRES_UP = listOf("..#..", ".#.#.", "#...#")


/**
 * Songs picked to do one thing with all of them (2026-11-10, as Prism's select many): begun from a held song's "pick
 * more", then each tap picks a song or lets it go, wherever songs are listed; back, done or an action lets them all go.
 */
object Picking {
    val picked = kotlinx.coroutines.flow.MutableStateFlow<List<Song>>(emptyList())
    fun toggle(s: Song) { picked.value = if (picked.value.any { it.id == s.id }) picked.value.filter { it.id != s.id } else picked.value + s }
    fun clear() { picked.value = emptyList() }
}

/** In the mini player's place while songs are picked: how many, and what to do with them all; done lets them go. */
@Composable
private fun PickBar(pages: Pages) {
    val picked by Picking.picked.collectAsState()
    val ctx = LocalContext.current
    val lovedIds by Loved.ids.collectAsState()
    val account by Server.account.collectAsState()
    val who by YouTubeAccount.who.collectAsState()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(said) { if (said != null) { delay(1_200); Picking.clear(); said = null } }
    Column(Modifier.fillMaxWidth().background(Ink.black).border(androidx.compose.foundation.BorderStroke(1.dp, Ink.line))
        .windowInsetsPadding(bars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(said ?: "${picked.size} PICKED", style = headline(20.sp), modifier = Modifier.weight(1f))
            Word("done", pixel(13.sp, Ink.mid), touch = 8.dp) { Picking.clear() }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // play next keeps them in the order picked: each put after the one before
            Word("play next", pixel(13.sp), touch = 8.dp) { picked.reversed().forEach { Playback.playNext(it) }; said = "PLAYING NEXT" }
            Word("add to queue", pixel(13.sp), touch = 8.dp) { picked.forEach { Playback.add(it) }; said = "QUEUED" }
            if ((account != null && picked.any { Server.idOf(it) != null }) || (who != null && picked.any { !it.station && !it.episode }))
                Word("add to playlist", pixel(13.sp), touch = 8.dp) { pages.open(Page.AddToMany(picked)) }
            if (picked.any { it.id !in lovedIds }) Word("love", pixel(13.sp), touch = 8.dp) { picked.filter { it.id !in lovedIds }.forEach { Loved.toggle(it) }; said = "LOVED" }
            val keep = picked.filter { Offline.keepable(it) }
            if (keep.isNotEmpty()) Word("keep offline", pixel(13.sp), touch = 8.dp) { Offline.keep(ctx, keep); said = "KEEPING OFFLINE" }
        }
    }
}


/**
 * Smart playlists on the phone (SmartLists in core): kept in files/smart-lists.json; each filled as it's opened from
 * the songs you have (the library's, YouTube's liked and loved, the kept), as the songs tab's pool.
 */
object Smart {
    val lists = kotlinx.coroutines.flow.MutableStateFlow<List<SmartLists.SmartList>>(emptyList())
    private var file: java.io.File? = null
    fun start(c: android.content.Context) {
        if (file != null) return
        file = java.io.File(c.filesDir, "smart-lists.json").also { f -> lists.value = runCatching { SmartLists.load(f.readText()) }.getOrDefault(emptyList()) }
    }
    private fun keep() { file?.let { f -> runCatching { Keep.write(f, SmartLists.save(lists.value)) } } }
    fun put(l: SmartLists.SmartList) { lists.value = if (lists.value.any { it.id == l.id }) lists.value.map { if (it.id == l.id) l else it } else lists.value + l; keep() }
    fun remove(id: String) { lists.value = lists.value.filter { it.id != id }; keep() }

    /** What's known of a phone song, for the rules. */
    fun facts(c: android.content.Context, heard: Listening, loved: Set<String>, kept: Set<String>) = object : SmartLists.Facts<Song> {
        override fun id(s: Song) = s.id
        override fun loved(s: Song) = s.id in loved
        override fun plays(s: Song) = heard.plays[s.id] ?: 0
        override fun last(s: Song) = heard.last[s.id] ?: 0L
        override fun added(s: Song) = s.added
        override fun bpm(s: Song) = Tempos.kept(c, s.id)?.bpm ?: 0.0
        override fun length(s: Song) = s.duration
        override fun from(s: Song) = when (SongsFrom.of(s)) { "youtube" -> "youtube"; "server" -> "your server"; else -> "this phone" }
        override fun kept(s: Song) = s.id in kept
        override fun genre(s: Song, g: String) = Genres.fits(s.artist, g)
    }
}

/** The songs a smart playlist picks from: as the songs tab's (the library's, YouTube's liked and loved, the kept). */
@Composable
private fun smartPool(): List<Song> {
    val ctx = LocalContext.current
    val songs by Library.songs.collectAsState()
    val liked by YouTubeAccount.liked.collectAsState()
    val lovedTube by Loved.tube.collectAsState()
    val keptIds by Offline.kept.collectAsState()
    val keptTube = remember(keptIds) { Offline.tubeSongs(ctx).filter { it.id in keptIds } }
    return remember(songs, liked, lovedTube, keptTube) {
        val have = songs.mapTo(HashSet()) { it.id }
        songs + (liked + lovedTube + keptTube).filter { it.id !in have }.distinctBy { it.id }
    }
}

/** A smart playlist's songs, picked now. */
@Composable
private fun smartSongs(l: SmartLists.SmartList?): List<Song> {
    val ctx = LocalContext.current
    val pool = smartPool()
    val heard by Listened.now.collectAsState()
    val loved by Loved.ids.collectAsState()
    val kept by Offline.kept.collectAsState()
    val tagged by GenrePicks.tagged.collectAsState()
    return androidx.compose.runtime.produceState(emptyList<Song>(), l, pool, heard, loved, kept, tagged) {
        value = if (l == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            SmartLists.pick(pool, l, Smart.facts(ctx, heard, loved, kept), System.currentTimeMillis())
        }
    }.value
}

/** In lists: each smart playlist (its name, what its rules say) and a new one. */
@Composable
private fun SmartRows(pages: Pages) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Smart.start(ctx) }
    val all by Smart.lists.collectAsState()
    all.forEach { l -> ListRow(l.name, "smart · ${SmartLists.said(l)}") { pages.open(Page.Smart(l.id)) } }
    Word("new smart playlist", pixel(13.sp, Ink.mid), Modifier.padding(vertical = 8.dp), touch = 8.dp) { pages.open(Page.SmartEdit(null)) }
}

/** A smart playlist's page: its name, what its rules say, play, shuffle, mix, change it; its songs, picked as it opens. */
@Composable
private fun SmartPage(id: String, pages: Pages) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Smart.start(ctx) }
    val all by Smart.lists.collectAsState()
    val l = all.firstOrNull { it.id == id }
    val songs = smartSongs(l)
    if (l == null) return
    val covers by ListCovers.on.collectAsState()
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                BasicText(l.name.uppercase(), style = headline(36.sp), maxLines = 2)
                Spacer(Modifier.height(6.dp))
                BasicText("smart · ${SmartLists.said(l)} · ${songs.size} ${if (songs.size == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(14.dp))
                Row {
                    if (songs.isNotEmpty()) {
                        Word("play", pixel(14.sp), Modifier.padding(end = 26.dp).tvFirst(), touch = 8.dp) { Playback.play(songs, 0) }
                        Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(songs.shuffled(), 0) }
                        MixWord(l.name, songs, Modifier.padding(end = 26.dp))
                    }
                    Word("change", pixel(14.sp, Ink.mid), touch = 8.dp) { pages.open(Page.SmartEdit(l.id)) }
                }
                MixProgress(l.name)
                if (songs.isEmpty()) BasicText("nothing meets all of these yet" + if (SmartLists.Kind.TEMPO in l.rules) ": a tempo is known once a song's been mixed or played" else "",
                    style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp))
                Spacer(Modifier.height(10.dp))
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> Box(Modifier.padding(horizontal = 20.dp)) { SongRow(s, pages = pages, cover = covers) { Playback.play(songs, i) } } }
    } }
}

/**
 * A smart playlist made or changed: its name, the starters (a new one), then each kind of rule with its choices as
 * words (one tapped sets it, tapped again lets it go; genre drops its list from your genres), the order and how many,
 * how many songs that finds now; save, or delete.
 */
@Composable
private fun SmartEditPage(id: String?, pages: Pages) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Smart.start(ctx) }
    val was = Smart.lists.collectAsState().value.firstOrNull { it.id == id }
    var name by rememberSaveable { mutableStateOf(was?.name ?: "") }
    var rules by remember { mutableStateOf(was?.rules ?: emptyMap()) }
    var order by remember { mutableStateOf(was?.order ?: SmartLists.Order.SHUFFLED) }
    var most by remember { mutableStateOf(was?.most ?: 50) }
    val draft = SmartLists.SmartList(was?.id ?: java.util.UUID.randomUUID().toString(), name.ifBlank { "smart playlist" }, rules, order, most)
    val found = smartSongs(draft)
    // the genres your songs are in, most first (as the songs tab's drop-down)
    val pool = smartPool()
    val tagged by GenrePicks.tagged.collectAsState()
    val genres = remember(pool, tagged) { Genres.names.map { g -> g to pool.count { Genres.fits(it.artist, g) } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(12) }
    val list = pageList()
    @Composable
    fun Choice(word: String, lit: Boolean, onClick: () -> Unit) = Word(word, pixel(14.sp, if (lit) Ink.ink else Ink.dim), touch = 8.dp, onClick = onClick)
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText(if (was == null) "NEW SMART PLAYLIST" else "CHANGE IT", style = headline(36.sp), maxLines = 2)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("name > ", style = pixel(14.sp, Ink.dim))
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) BasicText("gym, sunday, late", style = pixel(14.sp, Ink.dim))
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
                }
            }
            // a new one: the starters, a tap fills it in
            if (was == null) {
                Spacer(Modifier.height(18.dp))
                BasicText("START FROM", style = headline(16.sp, Ink.mid))
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SmartLists.STARTERS.forEach { st -> Choice(st.name, rules == st.rules) { rules = st.rules; order = st.order; if (name.isBlank()) name = st.name } }
                }
            }
            Spacer(Modifier.height(18.dp))
            BasicText("EVERY ONE OF THESE", style = headline(16.sp, Ink.mid))
            SmartLists.Kind.entries.forEach { k ->
                val choices = if (k == SmartLists.Kind.GENRE) genres.map { it.first } else k.choices
                if (choices.isEmpty()) return@forEach
                Spacer(Modifier.height(10.dp))
                BasicText(k.word, style = pixel(12.sp, Ink.mid))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    choices.forEach { c ->
                        Choice(c, rules[k] == c) { rules = if (rules[k] == c) rules - k else rules + (k to c) }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            BasicText("IN WHAT ORDER", style = headline(16.sp, Ink.mid))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { SmartLists.Order.entries.forEach { o -> Choice(o.word, order == o) { order = o } } }
            Spacer(Modifier.height(10.dp))
            BasicText("HOW MANY", style = headline(16.sp, Ink.mid))
            Row { listOf(25, 50, 100, 0).forEach { n -> Box(Modifier.padding(end = 20.dp)) { Choice(if (n == 0) "all" else "$n", most == n) { most = n } } } }
            Spacer(Modifier.height(22.dp))
            BasicText(if (rules.isEmpty()) "pick at least one" else "finds ${found.size} ${if (found.size == 1) "song" else "songs"} now", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(10.dp))
            Row {
                if (rules.isNotEmpty()) Word("SAVE", headline(22.sp), Modifier.padding(end = 28.dp), touch = 8.dp) {
                    Smart.put(draft); pages.back(); if (was == null) pages.open(Page.Smart(draft.id))
                }
                if (was != null) Word("delete", pixel(14.sp, Ink.mid), touch = 8.dp) { Smart.remove(was.id); pages.close() }
            }
        }
    } }
}


/** How wide a TV's rail of places is. */
private val TV_RAIL = 220.dp

/**
 * A TV's places, ink tv's way (2026-11-10, after Prism's TV and ink tv's own launcher): stacked words down the left, the
 * place shown lit, the remote stepping up and down them ([railItem]); resting on a place a moment shows it (as ink tv's
 * list shows its reel), OK goes at once. Then find, what's playing, your year and settings, smaller. Right goes into the
 * place, left from the place's edge comes back here.
 */
@Composable
private fun TvRail(tabs: List<String>, tab: String, onPlace: (String) -> Unit, placeIn: androidx.compose.ui.focus.FocusRequester, onFind: () -> Unit, onPlayer: () -> Unit,
                   onSettings: () -> Unit, onYear: () -> Unit, foot: Dp) {
    val playing = Playback.now.collectAsState().value != null
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // a word pressed: its place, and the remote into it (2026-11-10: pressing did nothing, the place already showing)
    fun into(t: String) {
        onPlace(t)
        // its first thing, asked of the place (moving right from the word went down the rail to settings, 2026-11-10);
        // a moment for a place just changed to come in, a few tries while its rows arrive
        scope.launch {
            delay(if (t == tab) 0L else Motion.ENTER.toLong())
            repeat(6) { intoPlace(); if (TvKeys.railAt < 0) return@launch; delay(150) }
        }
    }
    // a little smaller, and scrolled if it must be, so settings stays above the mini player (it went under it)
    Column(Modifier.width(TV_RAIL).fillMaxHeight().padding(bottom = foot).verticalScroll(rememberScrollState()).padding(start = 28.dp, top = 24.dp, bottom = 12.dp)) {
        BasicText("INK MUSIC", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(bottom = 12.dp))
        tabs.forEachIndexed { i, t ->
            var focused by remember { mutableStateOf(false) }
            // resting on a place a moment shows it; passing through doesn't
            LaunchedEffect(focused) { if (focused && t != tab) { delay(350); onPlace(t) } }
            Word(t.uppercase(), headline(21.sp, if (t == tab) Ink.ink else Ink.mid),
                Modifier.railItem(i, home = t == tab).then(if (t == tab) Modifier.tvFirst() else Modifier)
                    .onFocusChanged { focused = it.isFocused }.padding(vertical = 1.dp), touch = 4.dp) { into(t) }
        }
        Spacer(Modifier.height(16.dp))
        // the small words: lit white with a square before them while the remote's on one (2026-11-10: grey to begin
        // with, the screen's dim round them hardly showed which)
        @Composable
        fun Small(word: String, i: Int, onClick: () -> Unit) {
            var on by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (on) Ink.ink else Ink.black))
                Spacer(Modifier.width(8.dp))
                Word(word, pixel(15.sp, if (on) Ink.ink else Ink.mid), Modifier.railItem(i).onFocusChanged { on = it.isFocused }, touch = 6.dp, onClick = onClick)
            }
        }
        var at = tabs.size
        Small("find", at++, onFind)
        if (playing) Small("now playing", at++, onPlayer) else at++
        // (your year not on a TV, 2026-11-10: a phone's thing, read up close)
        Small("settings", at, onSettings)
    }
}
