package app.ink.music

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

class Main : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // opened again (the home screen, the notification) over ink music's own screens: those stay
        // as they were (the YouTube sign-in, mid-way), not a second library over them
        if (!isTaskRoot) { finish(); return }
        enableEdgeToEdge()
        Lyrics.start(this)
        Moments.start(this)
        Looks.start(this)
        Loved.start(this)
        YouTubeMusic.attach(this)
        Server.start(this)
        YouTubeAccount.start(this)
        ListenBrainz.start(this)
        Order.start(this)
        Seeds.start(this)
        Stations.curated(this)
        setContent { App() }
    }

    override fun onStart() {
        super.onStart()
        Playback.connect(this)
        Order.opened()
    }

    override fun onStop() {
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
    private val _shuffled = MutableStateFlow(false)
    val shuffled: StateFlow<Boolean> = _shuffled
    private val _seed = MutableStateFlow(java.util.Random().nextLong())
    val seed: StateFlow<Long> = _seed
    private var away = 0L
    private var prefs: android.content.SharedPreferences? = null

    fun start(c: android.content.Context) {
        prefs = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        _shuffled.value = prefs!!.getBoolean("shuffled", false)
    }

    fun set(on: Boolean) {
        prefs?.edit()?.putBoolean("shuffled", on)?.apply()
        _shuffled.value = on
        if (on) _seed.value = java.util.Random().nextLong()
    }

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
    data class Record(val id: String) : Page
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
}

/** Where the library's pages go: pushed in from the right, back out to the right (the iPod's). */
class Pages {
    val stack = mutableStateListOf<Page>()
    var forward by mutableStateOf(true)
    fun open(p: Page) { forward = true; stack.add(p) }
    fun back() { forward = false; stack.removeLastOrNull() }
}

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
    var query by rememberSaveable { mutableStateOf("") }
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
                LibraryPage(pages)
                AnimatedContent(
                    targetState = pages.stack.lastOrNull(),
                    transitionSpec = {
                        val f = pages.forward
                        ContentTransform(
                            slideInHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { if (f) (it * .2f).toInt() else -(it * .1f).toInt() } +
                                fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
                            slideOutHorizontally(tween(Motion.EXIT, easing = Motion.expo)) { if (f) -(it * .1f).toInt() else (it * .1f).toInt() } +
                                fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
                        )
                    },
                    label = "pages",
                ) { p ->
                    when (p) {
                        null -> Box(Modifier.fillMaxSize())
                        is Page.Record -> RecordPage(p.id, pages)
                        is Page.Artist -> ArtistPage(p.name, pages)
                        Page.Find -> FindPage(query, { query = it }, pages)
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
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { foot = with(density) { it.height.toDp() } }) {
            MiniPlayer(frost, onOpen = { playerUp = true })
        }
        androidx.compose.animation.AnimatedVisibility(
            playerUp,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { PlayerPage(onClose = { playerUp = false }, onOpen = { playerUp = false; pages.open(it) }) }
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
private fun LibraryPage(pages: Pages) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val read by Library.read.collectAsState()
    var tab by rememberSaveable { mutableStateOf("listen") }
    // nothing on the phone or a server: no records, artists or songs to show
    val tabs = if (songs.isEmpty()) listOf("listen", "lists") else listOf("listen", "albums", "artists", "songs", "lists")
    val pager = rememberPagerState(tabs.indexOf(tab).coerceAtLeast(0)) { tabs.size }
    // the tab row follows a swipe; a tab tapped goes there on ink's curve
    LaunchedEffect(pager, tabs) { snapshotFlow { pager.currentPage }.collect { tab = tabs.getOrElse(it) { "listen" } } }
    LaunchedEffect(tabs) { pager.scrollToPage(tabs.indexOf(tab).coerceAtLeast(0)) }
    val scope = rememberCoroutineScope()
    val listenList = rememberLazyListState()
    val grid = rememberLazyGridState()
    val artistList = rememberLazyListState()
    val listsList = rememberLazyListState()
    val songList = rememberLazyListState()
    val shuffled by Order.shuffled.collectAsState()
    val seed by Order.seed.collectAsState()
    // the library in the run's order, when shuffled; a new order starts at the top
    val artists = remember(albums, songs, shuffled, seed) { artistsOf(albums, songs).let { if (shuffled) Order.of(it, seed) { a -> a.name } else it } }
    val records = remember(albums, shuffled, seed) { if (shuffled) Order.of(albums, seed) { it.id } else albums }
    val everySong = remember(songs, shuffled, seed) { if (shuffled) Order.of(songs, seed) { it.id } else songs }
    LaunchedEffect(shuffled, seed) { grid.scrollToItem(0); artistList.scrollToItem(0); songList.scrollToItem(0) }
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
            "albums" -> LazyVerticalGrid(
                GridCells.Adaptive(150.dp), state = grid, contentPadding = pad,
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(records, key = { it.id }) { a -> RecordTile(a, Modifier.fillMaxWidth()) { pages.open(Page.Record(a.id)) } }
            }
            "artists" -> LazyColumn(state = artistList, contentPadding = pad) {
                items(artists, key = { it.name }) { a -> ArtistRow(a) { pages.open(Page.Artist(a.name)) } }
            }
            // lists: the songs you love, your year, then your playlists (and "new >" for one more)
            "lists" -> {
                val lists by Server.playlists.collectAsState()
                val tubeLists by YouTubeAccount.playlists.collectAsState()
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
                        Spacer(Modifier.height(10.dp))
                        if (account != null) { NewPlaylist(null) {}; Spacer(Modifier.height(10.dp)) }
                    }
                    items(lists, key = { it.id }) { pl ->
                        Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                            onClick = felt { pages.open(Page.Playlist(pl.id, pl.name)) }).padding(vertical = 9.dp)) {
                            BasicText(pl.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            BasicText("${pl.count} ${if (pl.count == 1) "song" else "songs"}", style = pixel(10.sp, Ink.mid))
                        }
                    }
                    // your youtube music: its playlists, liked music first as YouTube lists them (read only)
                    items(tubeLists, key = { "y" + it.id }) { pl ->
                        ListRow(pl.name, listOf("youtube music", pl.said.lowercase()).filter { it.isNotEmpty() }.joinToString("   ")) {
                            pages.open(Page.TubeList(pl))
                        }
                    }
                }
            }
            else -> LazyColumn(state = songList, contentPadding = pad) {
                itemsIndexed(everySong, key = { _, s -> s.id }) { i, s -> SongRow(s, swipe = false, onClick = { Playback.play(everySong, i) }) }
            }
        }
       }
      }
    }
    // the tab row on black; what scrolls up toward it softens just under it
    Column(Modifier.fillMaxWidth().onSizeChanged { head = with(density) { it.height.toDp() } }) {
      Column(Modifier.fillMaxWidth().background(Ink.black).windowInsetsPadding(bars)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // the tabs slide sideways when they're more than the row holds; find and the cog stay put
            // (softening as they near them)
            val slide = rememberHazeState()
            Box(Modifier.weight(1f)) {
                Row(Modifier.hazeSource(slide).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { i, t ->
                        Word(t, pixel(14.sp, if (t == tab) Ink.ink else Ink.dim), Modifier.padding(end = 12.dp), touch = 8.dp) {
                            scope.launch { pager.animateScrollToPage(i, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) }
                        }
                    }
                }
                Box(Modifier.matchParentSize().wrapContentWidth(Alignment.End).width(BAND).softEdgeEnd(slide))
            }
            Spacer(Modifier.width(12.dp))
            // the cog is on the listen page, by its greeting
            Word("find", pixel(14.sp, Ink.mid), touch = 8.dp) { pages.open(Page.Find) }
        }
      }
      Box(Modifier.fillMaxWidth().height(BAND).softEdge(tabsFrost, towardBottom = false))
    }
    }
}

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
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp)) {
        BasicText(a.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(
            listOfNotNull(a.records.takeIf { it > 0 }?.let { "$it ${if (it == 1) "record" else "records"}" }, "${a.songs} ${if (a.songs == 1) "song" else "songs"}").joinToString("   "),
            style = pixel(10.sp, Ink.mid),
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
        UnderCover(a.title, a.artist, pixel(10.sp, Ink.mid), gap = 3.dp)
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
private fun SongRow(s: Song, number: String? = null, pages: Pages? = null, onRemove: (() -> Unit)? = null, swipe: Boolean = true, onClick: () -> Unit) {
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
            number?.let { BasicText(it, style = pixel(11.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.width(30.dp).padding(bottom = 3.dp)) }
            Column(Modifier.weight(1f)) {
                BasicText(s.title.uppercase(), style = headline(19.sp, if (on || now == null) Ink.ink else Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (number == null) BasicText(s.artist.lowercase(), style = pixel(10.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // a swipe's word stays a moment where the length was: it's done
            done?.let { BasicText(it, style = pixel(11.sp), modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)) }
                ?: if (s.duration > 0) BasicText(clock(s.duration), style = pixel(11.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)) else Unit
        }
        if (open) FlowRow(Modifier.padding(start = if (number != null) 30.dp else 0.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Word("play next", pixel(13.sp), touch = 8.dp) { open = false; Playback.playNext(s) }
            Word("add to queue", pixel(13.sp), touch = 8.dp) { open = false; Playback.add(s) }
            Word("radio", pixel(13.sp), touch = 8.dp) { open = false; Playback.startRadio(s) }
            val lovedIds by Loved.ids.collectAsState()
            Word(if (s.id in lovedIds) "unlove" else "love", pixel(13.sp), touch = 8.dp) { open = false; Loved.toggle(s) }
            val account by Server.account.collectAsState()
            if (pages != null && account != null && Server.idOf(s) != null)
                Word("add to playlist", pixel(13.sp), touch = 8.dp) { open = false; pages.open(Page.AddTo(s)) }
            val sonic by Server.sonic.collectAsState()
            if (pages != null && sonic && account != null && Server.idOf(s) != null)
                Word("path to", pixel(13.sp), touch = 8.dp) { open = false; pages.open(Page.Path(s)) }
            onRemove?.let { Word("remove", pixel(13.sp), touch = 8.dp) { open = false; it() } }
            if (pages != null && number == null && s.album.isNotEmpty() && !s.tube) Word("record", pixel(13.sp, Ink.mid), touch = 8.dp) { open = false; pages.open(Page.Record(s.albumId)) }
            if (pages != null) Word("artist", pixel(13.sp, Ink.mid), touch = 8.dp) { open = false; pages.open(Page.Artist(s.albumArtist)) }
        }
    }}
}

/** A record's page: its cover, its name, its songs as words; a tap plays from there. */
@Composable
private fun RecordPage(id: String, pages: Pages) {
    val albums by Library.albums.collectAsState()
    val a = albums.firstOrNull { it.id == id } ?: return
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BoxWithConstraints(Modifier.bleed(20.dp), contentAlignment = Alignment.Center) {
                CoverOf(a.songs.first(), a.title, Modifier.size(minOf(maxWidth, 560.dp)))
            }
            Spacer(Modifier.height(18.dp))
            BasicText(a.title.uppercase(), style = headline(36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Word(a.artist.lowercase(), pixel(13.sp, Ink.mid), touch = 6.dp) { pages.open(Page.Artist(a.artist)) }
            Spacer(Modifier.height(4.dp))
            BasicText(
                listOfNotNull(a.year.takeIf { it > 0 }?.toString(), "${a.songs.size} ${if (a.songs.size == 1) "song" else "songs"}",
                    a.length.takeIf { it > 0 }?.let { "${(it / 60_000).coerceAtLeast(1)} min" }).joinToString("   "),
                style = pixel(11.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(a.songs, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.play(a.songs.shuffled(), 0) }
            }
            Spacer(Modifier.height(14.dp))
        }
        itemsIndexed(a.songs, key = { _, s -> s.id }) { i, s ->
            SongRow(s, number = s.track.takeIf { it > 0 }?.toString() ?: "${i + 1}", pages = pages) { Playback.play(a.songs, i) }
        }
    } }
}

/** A row in "lists": its name big, a word on it small. */
@Composable
private fun ListRow(name: String, said: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp)) {
        BasicText(name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (said.isNotEmpty()) BasicText(said, style = pixel(10.sp, Ink.mid))
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
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText("LOVED", style = headline(44.sp))
            Spacer(Modifier.height(8.dp))
            BasicText("${theirs.size} ${if (theirs.size == 1) "song" else "songs"}", style = pixel(11.sp, Ink.mid))
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
        }
        itemsIndexed(theirs, key = { _, s -> s.id }) { i, s -> SongRow(s, pages = pages) { Playback.play(theirs, i) } }
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
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText(name.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            val all = songs
            BasicText(
                if (all == null) "reading it" else listOfNotNull("${all.size} ${if (all.size == 1) "song" else "songs"}",
                    all.sumOf { it.duration }.takeIf { it > 0 }?.let { "${it / 60_000} min" }).joinToString("   "),
                style = pixel(11.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
            }
            // deleting asks once more: the word says so, lit, for a moment
            if (mine) Word(if (sure) "delete it" else "delete", pixel(12.sp, if (sure) Ink.ink else Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) {
                if (!sure) sure = true else scope.launch { if (Server.deletePlaylist(id)) pages.back() }
            }
            Spacer(Modifier.height(14.dp))
        }
        val all = songs.orEmpty()
        // no keys: a playlist can hold one song twice
        itemsIndexed(all) { i, s ->
            SongRow(s, pages = pages, onRemove = if (mine) ({
                songs = all.filterIndexed { j, _ -> j != i }    // gone at once; the server's answer reads it again
                scope.launch { Server.removeFrom(id, i) }
            }) else null) { Playback.play(all, i) }
        }
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
    FrostedPage(onClose = { pages.back() }) { frost, top0 -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top0, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            // their photo across the page in the dither (the player's), developing as it opens, their name on it
            val photo = i?.photo
            if (photo != null) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                val side = minOf(maxWidth, 560.dp)
                Dithered(remember(photo) { photoSong(name, photo) }, Modifier.size(side).align(Alignment.Center))
                NameOn(i.name, Modifier.fillMaxWidth().padding(bottom = 6.dp))
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
                    Word("radio", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.startRadio(playable.first()) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (top.isNotEmpty()) {
            item { Label("top songs") }
            // a chart: the place big beside each
            itemsIndexed(top, key = { _, s -> "t" + s.id }) { n, s ->
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("${n + 1}", style = headline(34.sp), modifier = Modifier.width(48.dp))
                    Box(Modifier.weight(1f)) { SongRow(s, pages = pages) { Playback.play(top, n) } }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
        if (records.isNotEmpty() || tubeAlbums.isNotEmpty()) item {
            Label("records")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(records, key = { it.id }) { a -> RecordTile(a, Modifier.width(150.dp)) { pages.open(Page.Record(a.id)) } }
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
        // the songs of theirs you have: on the phone, on your server
        if (theirs.isNotEmpty()) {
            item { Label("yours") }
            itemsIndexed(theirs, key = { _, s -> s.id }) { n, s ->
                Box(Modifier.padding(horizontal = 20.dp)) { SongRow(s, pages = pages) { Playback.play(theirs, n) } }
            }
            item { Spacer(Modifier.height(18.dp)) }
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
        LaunchedEffect(frames) { if (frames != null) shown.animateTo(1f, tween(900, easing = Motion.expo)) }
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
        if (wide) Marquee(name, true, Modifier.fillMaxWidth(), size = 96.sp)
        else BasicText(name.uppercase(), style = style, maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

/** An artist's wide photo as a song-shaped picture, kept apart from their small face. */
fun photoSong(name: String, photo: String) = faceSong(name, photo).copy(id = "yphoto:$name", albumId = "photo:$name")

/**
 * Find, as ink's run menu: "find >" and what's typed; records, artists and songs whose names
 * hold it, the ones that start with it first. Go plays the first song found.
 */
@Composable
private fun FindPage(query: String, onQuery: (String) -> Unit, pages: Pages) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    // YouTube Music too (the full build), once the typing rests
    val tube by androidx.compose.runtime.produceState(TubeFound(), query) {
        val typed = query.trim()
        if (!YouTubeMusic.available || typed.length < 2) { value = TubeFound(); return@produceState }
        kotlinx.coroutines.delay(300)
        value = YouTubeMusic.search(typed)
    }
    val artists = remember(albums, songs) { artistsOf(albums, songs) }
    val q = query.trim().lowercase()
    fun rank(s: String): Int? { val l = s.lowercase(); return when { l.startsWith(q) -> 0; l.contains(" $q") -> 1; l.contains(q) -> 2; else -> null } }
    val foundArtists = remember(q, artists) { if (q.isEmpty()) emptyList() else artists.mapNotNull { a -> rank(a.name)?.let { it to a } }.sortedBy { it.first }.map { it.second }.take(8) }
    val foundRecords = remember(q, albums) { if (q.isEmpty()) emptyList() else albums.mapNotNull { a -> listOfNotNull(rank(a.title), rank(a.artist)?.plus(1)).minOrNull()?.let { it to a } }.sortedBy { it.first }.map { it.second }.take(12) }
    val foundSongs = remember(q, songs) { if (q.isEmpty()) emptyList() else songs.mapNotNull { s -> listOfNotNull(rank(s.title), rank(s.artist)?.plus(1), rank(s.album)?.plus(2)).minOrNull()?.let { it to s } }.sortedBy { it.first }.map { it.second }.take(50) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val list = rememberLazyListState()
    FrostedPage { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("find > ", style = pixel(14.sp, Ink.dim))
                BasicTextField(
                    query, onQuery, singleLine = true,
                    textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { foundSongs.firstOrNull()?.let { Playback.play(foundSongs, 0) } }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                Word("close", pixel(11.sp, Ink.mid), touch = 10.dp) { pages.back() }
            }
            Spacer(Modifier.height(18.dp))
            // nothing anywhere: here, nor on YouTube Music (once it's had its moment to answer)
            if (q.isNotEmpty() && foundArtists.isEmpty() && foundRecords.isEmpty() && foundSongs.isEmpty() &&
                tube.songs.isEmpty() && tube.albums.isEmpty())
                BasicText("nothing called that", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (foundArtists.isNotEmpty()) {
            item { Label("artists") }
            items(foundArtists, key = { "a" + it.name }) { a -> Box(Modifier.padding(horizontal = 20.dp)) { ArtistRow(a) { pages.open(Page.Artist(a.name)) } } }
        }
        if (foundRecords.isNotEmpty()) item {
            Label("records")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(foundRecords, key = { it.id }) { a -> RecordTile(a, Modifier.width(130.dp)) { pages.open(Page.Record(a.id)) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (foundSongs.isNotEmpty()) {
            item { Label("songs") }
            itemsIndexed(foundSongs, key = { _, s -> "s" + s.id }) { i, s ->
                Box(Modifier.padding(horizontal = 20.dp)) { SongRow(s, pages = pages) { Playback.play(foundSongs, i) } }
            }
        }
        // what YouTube Music has: its records, then its songs
        if (tube.albums.isNotEmpty() || tube.songs.isNotEmpty()) item { Label("on youtube music") }
        if (tube.albums.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(tube.albums, key = { "ta" + it.url }) { a -> TubeTile(a, Modifier.width(130.dp)) { pages.open(Page.Tube(a)) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        itemsIndexed(tube.songs, key = { _, s -> "t" + s.id }) { i, s ->
            Box(Modifier.padding(horizontal = 20.dp)) { SongRow(s) { Playback.play(tube.songs, i) } }
        }
    } }
}

/** A YouTube Music record as a tile: its cover (from its link), name and artist. */
@Composable
fun TubeTile(a: TubeAlbum, modifier: Modifier, onClick: () -> Unit) {
    val face = remember(a.url) { tubeFace(a) }
    Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(face, a.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        UnderCover(a.title, a.artist, pixel(10.sp, Ink.mid), gap = 3.dp)
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
    val songs by androidx.compose.runtime.produceState<List<Song>?>(null, a.url) { value = YouTubeMusic.album(a) }
    LaunchedEffect(songs?.firstOrNull()?.id) { Playback.warm(songs?.firstOrNull()) }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BoxWithConstraints(Modifier.bleed(20.dp), contentAlignment = Alignment.Center) {
                CoverOf(remember(a.url) { tubeFace(a) }, a.title, Modifier.size(minOf(maxWidth, 560.dp)))
            }
            Spacer(Modifier.height(18.dp))
            BasicText(a.title.uppercase(), style = headline(36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            BasicText(a.artist.lowercase(), style = pixel(13.sp, Ink.mid))
            Spacer(Modifier.height(4.dp))
            val all = songs
            BasicText(if (all == null) "reading it from youtube music" else "${all.size} songs   youtube music", style = pixel(11.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
            }
            Spacer(Modifier.height(14.dp))
        }
        val all = songs.orEmpty()
        itemsIndexed(all) { i, s -> SongRow(s, number = "${i + 1}") { Playback.play(all, i) } }
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
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText(pl.name.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            val all = songs
            BasicText(
                if (all == null) "reading it from youtube music" else listOfNotNull("${all.size} ${if (all.size == 1) "song" else "songs"}",
                    all.sumOf { it.duration }.takeIf { it > 0 }?.let { "${it / 60_000} min" }, "youtube music").joinToString("   "),
                style = pixel(11.sp, Ink.mid),
            )
            Spacer(Modifier.height(16.dp))
            if (!all.isNullOrEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
            }
            Spacer(Modifier.height(14.dp))
        }
        val all = songs.orEmpty()
        // no keys: a playlist can hold one song twice
        itemsIndexed(all) { i, s -> SongRow(s) { Playback.play(all, i) } }
    } }
}


/** Where a held song goes into a playlist: a new one ("new >", named there), or one of yours. */
@Composable
private fun AddToPage(s: Song, pages: Pages) {
    val lists by Server.playlists.collectAsState()
    val mine = remember(lists) { lists.filter { it.mine } }
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText("ADD TO A PLAYLIST", style = headline(36.sp), maxLines = 2)
            Spacer(Modifier.height(8.dp))
            BasicText(s.title.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            NewPlaylist(s, focused = mine.isEmpty()) { ok -> if (ok) pages.back() else failed = true }
            if (failed) BasicText("the server didn't take it", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(14.dp))
        }
        items(mine, key = { it.id }) { pl ->
            Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                onClick = felt { scope.launch { if (Server.addTo(pl.id, s)) pages.back() else failed = true } }).padding(vertical = 9.dp)) {
                BasicText(pl.name.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("${pl.count} ${if (pl.count == 1) "song" else "songs"}", style = pixel(12.sp, Ink.mid))
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
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText("A PATH", style = headline(44.sp))
            Spacer(Modifier.height(8.dp))
            BasicText("from one song to another, a step at a time, by how they sound", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(18.dp))
            BasicText("from", style = pixel(11.sp, Ink.mid))
            BasicText(from.title.uppercase(), style = headline(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(from.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
        }
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
                BasicText("to", style = pixel(11.sp, Ink.mid))
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
            itemsIndexed(way) { i, s -> SongRow(s, number = "${i + 1}", pages = pages) { Playback.play(way, i) } }
        }
    } }
}

/** "new >" and a name: go makes the playlist on the server (with `song` in it, if there's one). */
@Composable
private fun NewPlaylist(song: Song?, focused: Boolean = false, onMade: (Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    if (focused) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun make() {
        val n = name.trim()
        if (n.isEmpty()) return
        scope.launch {
            val ok = Server.makePlaylist(n, listOfNotNull(song))
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
    BasicText(text, style = pixel(11.sp, Ink.mid), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
}

/** How tall the soft edge beside a bar is: sharp at its far side, slightly out of focus at the bar. */
val BAND = 16.dp

/**
 * A page that scrolls on under the status bar, frosted there (its band below it). `content` gets
 * the frost its list is the source of, and how far down to start: below the status bar, and
 * below "close" when the page has one (kept at the top, on black, however far it's scrolled).
 */
@Composable
fun FrostedPage(onClose: (() -> Unit)? = null, content: @Composable (HazeState, Dp) -> Unit) {
    val frost = rememberHazeState()
    val bar = bars.asPaddingValues().calculateTopPadding()
    val top = bar + if (onClose != null) CLOSE_ROW else 0.dp
    Box(Modifier.fillMaxSize().background(Ink.black)) {
        content(frost, top)
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(bar).background(Ink.black))
            if (onClose != null) Row(
                Modifier.fillMaxWidth().height(CLOSE_ROW).background(Ink.black).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Word("close", pixel(11.sp, Ink.mid), touch = 10.dp, onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = false))
        }
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
    BoxWithConstraints(modifier) {
        if (cover != null) Image(cover.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (song?.station == true) StationCover(song, Modifier.fillMaxSize())
        else NameCover(name, maxWidth)
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
