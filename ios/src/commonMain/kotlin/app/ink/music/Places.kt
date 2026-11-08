package app.ink.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** What's pushed over the places: a record, a show, find, settings. */
sealed class Page {
    data class Record(val album: Album) : Page()
    data class Show(val show: Podcasts.Show) : Page()
    /** An artist: by name (your server's), with YouTube Music's id where it's known. */
    data class Artist(val name: String, val id: String = "") : Page()
    data object Find : Page()
    data object Settings : Page()
    /** One of settings' pages: music, look, sound, listening, about. */
    data class SettingsPart(val part: String) : Page()
}

/** The pages pushed over the places (back closes the top one), the pairing page, the place shown. */
object Pages {
    val stack = mutableStateListOf<Page>()
    var place by mutableStateOf("listen")
    var pairing by mutableStateOf(false)
    var player by mutableStateOf(false)
    /** Up next, open over the player. */
    var queue by mutableStateOf(false)
    /** The top page's frost, for the mini player's soft edge over it. */
    var frost by mutableStateOf<HazeState?>(null)
    fun open(p: Page) { stack += p }
    fun close() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
}

/** The places, as the phone's (its albums are your server's records); find and settings open over them, as there. */
val PLACES = listOf("listen", "ink", "albums", "radio", "podcasts")

/** How far down a place's own words start: under the bar with the place's word. */
private val HEAD = 64.dp

/**
 * The places, as the phone's: one swiped to the next; the place you're on one big word at the top with its chevron (a
 * tap drops every place down as big words, ink's menu), find at the right. The mini player at the foot, the player over all.
 */
@Composable
fun Places() {
    val now by Sound.now.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val foot = if (now != null) 120.dp else 30.dp
    val pager = rememberPagerState(PLACES.indexOf(Pages.place).coerceAtLeast(0)) { PLACES.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { Pages.place = PLACES[it]; menu = false } }
    LaunchedEffect(Pages.place) {
        val i = PLACES.indexOf(Pages.place)
        if (i >= 0 && i != pager.currentPage) pager.animateScrollToPage(i, animationSpec = tween(Motion.ENTER, easing = Motion.expo))
    }
    val frost = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(WindowInsets.safeDrawing)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { PLACES[it] },
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(Motion.ENTER, easing = Motion.expo))) { i ->
                when (PLACES[i]) {
                    "listen" -> ListenPlace(HEAD, foot)
                    "albums" -> RecordsPlace(HEAD, foot)
                    "ink" -> InkPlace(HEAD, foot)
                    "podcasts" -> PodcastsPlace(HEAD, foot)
                    "radio" -> RadioPlace(HEAD, foot)
                }
            }
        }
        // the bar on black: the place's word and its chevron, find
        Column(Modifier.fillMaxWidth().background(Ink.black).windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { menu = !menu }),
                    verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(Pages.place, label = "place",
                        transitionSpec = { fadeIn(tween(Motion.ENTER, easing = Motion.expo)) togetherWith fadeOut(tween(Motion.EXIT)) },
                    ) { t -> BasicText(t.uppercase(), style = headline(26.sp)) }
                    Spacer(Modifier.width(10.dp))
                    // the menu's chevron: down while shut, up while open
                    val turn by animateFloatAsState(if (menu) 180f else 0f, tween(Motion.ENTER, easing = Motion.expo), label = "chevron")
                    Sprite(CLOSE, Ink.mid, 2.dp, Modifier.graphicsLayer { rotationZ = turn })
                }
                Word("find", pixel(14.sp, Ink.mid), touch = 8.dp) { menu = false; Pages.open(Page.Find) }
            }
            // what goes up behind the bar softens just under it
            Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = false))
        }
        // the places, dropped down under the word: big, the one you're on lit, each arriving in turn; a tap goes there,
        // a tap anywhere else folds it away. Black, never a frosted panel
        AnimatedVisibility(menu, modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 52.dp),
            enter = expandVertically(tween(Motion.ENTER, easing = Motion.expo), expandFrom = Alignment.Top) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = shrinkVertically(tween(Motion.EXIT, easing = Motion.expo), shrinkTowards = Alignment.Top) + fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
            Column(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) { menu = false }
                .padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
                PLACES.forEachIndexed { i, p ->
                    Word(p.uppercase(), headline(48.sp, if (p == Pages.place) Ink.ink else Ink.mid), Modifier.arrive(i, menu), touch = 4.dp) {
                        menu = false
                        scope.launch { pager.animateScrollToPage(i, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Word("settings", pixel(14.sp, Ink.mid), Modifier.arrive(PLACES.size, menu), touch = 8.dp) { menu = false; Pages.open(Page.Settings) }
                Row(Modifier.arrive(PLACES.size + 1, menu).padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("made by ", style = pixel(12.sp, Ink.dim))
                    Word("ink", pixel(12.sp, Ink.mid), touch = 8.dp) { openLink("https://inkhome.app") }
                }
            }
        }
        Pages.stack.lastOrNull()?.let { p ->
            when (p) {
                is Page.Record -> Record(p.album) { Pages.close() }
                is Page.Show -> ShowPage(p.show, foot) { Pages.close() }
                is Page.Artist -> ArtistPage(p.name, p.id) { Pages.close() }
                Page.Find -> Over("find") { FindPlace(HEAD, foot) }
                Page.Settings -> SettingsPage(null) { Pages.close() }
                is Page.SettingsPart -> SettingsPage(p.part) { Pages.close() }
            }
        }
        if (Pages.pairing) Pairing { Pages.pairing = false }
        // the mini player rises in; the player slides up over everything
        var last by remember { mutableStateOf(now) }
        if (now != null) last = now
        AnimatedVisibility(now != null, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 3 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { it / 3 } + fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
            last?.let { n -> CompositionLocalProvider(LocalFrost provides (Pages.frost ?: frost)) { MiniPlayer(n) { Pages.player = true } } }
        }
        AnimatedVisibility(Pages.player && now != null,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { it } + fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
            last?.let { n -> Player(now ?: n) { Pages.player = false } }
        }
    }
}

/** A page over the places (find, settings): its word at the top as a place's, the close arrow at the right. */
@Composable
private fun Over(name: String, content: @Composable () -> Unit) {
    val frost = rememberFrost()
    Box(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Box(Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
        Column(Modifier.fillMaxWidth().background(Ink.black).windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(name.uppercase(), style = headline(26.sp), modifier = Modifier.weight(1f))
                Pixel(CLOSE, "close", Ink.mid) { Pages.close() }
            }
            Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = false))
        }
    }
}

/** A shelf's name: small, in pixels, at 70%. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    BasicText(text.lowercase(), style = pixel(12.sp, Ink.mid), modifier = modifier.padding(top = 26.dp, bottom = 10.dp))
}

/** A name and whose under it, as in a list. */
@Composable
fun Named(title: String, by: String, lit: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 8.dp)) {
        BasicText(title.uppercase(), style = headline(18.sp, if (lit) Ink.ink else Ink.ink.copy(alpha = .92f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (by.isNotEmpty()) BasicText(by.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A row of words to choose between, the chosen lit. */
@Composable
fun Choose(options: List<String>, chosen: String, onPick: (String) -> Unit) {
    Row(Modifier.padding(top = 4.dp)) {
        for (o in options) Word(o, pixel(14.sp, if (o == chosen) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { onPick(o) }
    }
}
