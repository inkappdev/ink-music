package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An episode as the player plays it: its show where an artist would be, its own picture or the show's. */
fun episodeSong(show: Podcasts.Show, e: Podcasts.Episode) =
    Song(e.id, e.title, show.title, show.title, show.id, e.art ?: show.art, e.length, 0, 1, kind = "episode", url = e.url)

/** podcasts: the shows you follow, their new episodes, what's listened to most, and a search of Apple's directory. */
@Composable
fun PodcastsPlace(top: Dp, foot: Dp) {
    val changed by Together.changed.collectAsState()
    var bump by remember { mutableStateOf(0) }
    val following by produceState(emptyList<Podcasts.Show>(), changed, bump) { value = Here.off { Podcasts.following() } }
    val fresh by produceState(emptyList<Pair<Podcasts.Show, Podcasts.Episode>>(), changed, bump) {
        value = Here.off { Podcasts.fresh() }
        Here.off { Podcasts.refresh() }
        value = Here.off { Podcasts.fresh() }
    }
    val chart by produceState(emptyList<Podcasts.Show>()) { value = Here.off { Podcasts.top(n = 30) } }
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Podcasts.Show>?>(null) }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { found = null; return@LaunchedEffect }
        delay(400)
        found = Here.off { Podcasts.search(q) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = foot)) {
        item {
            Column(Modifier.fillMaxWidth()) {
                BasicTextField(q, { q = it }, singleLine = true, textStyle = headline(20.sp), cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> Box { if (q.isEmpty()) BasicText("find a show", style = pixel(14.sp, Ink.dim)); inner() } })
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            }
        }
        found?.let { list ->
            if (list.isEmpty()) item { Label("none by that name") }
            items(list, key = { "f" + it.feed }) { s -> Named(s.title, s.author) { Pages.open(Page.Show(s)) } }
            return@LazyColumn
        }
        if (following.isNotEmpty()) item {
            Label("yours")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(following, key = { it.feed }) { s -> ShowTile(s, Modifier.width(120.dp)) }
            }
        }
        if (fresh.isNotEmpty()) {
            item { Label("new") }
            items(fresh, key = { "e" + it.second.id }) { (show, e) -> EpisodeRow(show, e) }
        }
        if (chart.isNotEmpty()) {
            item { Label("most listened to") }
            items(chart.chunked(2), key = { "c" + it.first().feed }) { two ->
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    two.forEach { ShowTile(it, Modifier.weight(1f)) }
                    if (two.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ShowTile(s: Podcasts.Show, modifier: Modifier) {
    Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null) { Pages.open(Page.Show(s)) }) {
        Cover(s.art, 400, s.title, Modifier.fillMaxWidth().aspectRatio(1f))
        BasicText(s.title.uppercase(), style = headline(14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

/** An episode: its name, its show (or when it came), how long, where you left it. */
@Composable
private fun EpisodeRow(show: Podcasts.Show, e: Podcasts.Episode, own: Boolean = false, list: List<Podcasts.Episode> = emptyList()) {
    val now by Sound.now.collectAsState()
    val place = remember(e.id, now?.song?.id) { Podcasts.place(e.id) }
    val lit = now?.song?.id == e.id
    val left = when {
        place < 0 -> "heard"
        place > 0 && e.length > 0 -> clock(e.length - place) + " left"
        e.length > 0 -> clock(e.length)
        else -> ""
    }
    val by = listOf(if (own) Moment.since(e.published) else show.title, left).filter { it.isNotEmpty() }.joinToString("   ")
    Named(e.title, by, lit) {
        // a show's episodes play on from the one chosen, older ones after
        val queue = if (list.isEmpty()) listOf(e) else list.drop(list.indexOf(e).coerceAtLeast(0))
        Sound.play(queue.map { episodeSong(show, it) }, 0)
    }
}

/** A show: its picture, its name, follow, what it's about, its episodes newest first. */
@Composable
fun ShowPage(show: Podcasts.Show, foot: Dp, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val read by produceState<Pair<Podcasts.Show, List<Podcasts.Episode>>?>(Podcasts.kept(show.feed), show.feed) {
        Here.off { Podcasts.fetch(show.feed) }?.let { value = it }
    }
    var follows by remember { mutableStateOf(Podcasts.follows(show.feed)) }
    val s = read?.first ?: show
    LazyColumn(Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = foot)) {
        item {
            Word("back", pixel(14.sp, Ink.mid)) { onClose() }
            Cover(s.art ?: show.art, 900, s.title, Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 8.dp))
            BasicText(s.title.uppercase(), style = headline(34.sp), modifier = Modifier.padding(top = 16.dp))
            if (s.author.isNotEmpty()) BasicText(s.author.lowercase(), style = pixel(13.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
            Word(if (follows) "following" else "follow", pixel(16.sp, if (follows) Ink.ink else Ink.mid), Modifier.padding(top = 10.dp)) {
                follows = !follows
                val on = follows
                scope.launch { Here.off { Podcasts.follow(s, on) }; Here.work.launch { Together.now() } }
            }
            if (s.about.isNotEmpty()) BasicText(s.about, style = pixel(12.sp, Ink.mid), maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            if (read == null) Label("reading its feed")
        }
        val eps = read?.second.orEmpty()
        items(eps.take(200), key = { it.id }) { e -> EpisodeRow(s, e, own = true, list = eps.take(200)) }
    }
}
