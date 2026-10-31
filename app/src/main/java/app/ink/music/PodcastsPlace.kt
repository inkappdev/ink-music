package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Podcasts, a place of its own beside radio (Bronson, 2026-10-31): find a show (Apple's directory, or its feed's link
 * pasted), the newest episodes of the shows you follow, your shows, and what's listened to most now. A show's page is
 * [ShowPage].
 */
@Composable
fun PodcastsPlace(pages: Pages, pad: PaddingValues, grid: LazyGridState) {
    val ctx = LocalContext.current
    val following by Pods.following.collectAsState()
    val fresh by Pods.fresh.collectAsState()
    val places by Pods.places.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { Pods.refresh(ctx) }
    // what plays moved on: where you stopped, read again
    val now by Playback.now.collectAsState()
    LaunchedEffect(now?.song?.id, now?.playing) { Pods.load(ctx) }
    val top by produceState<List<Podcasts.Show>?>(null) { value = withContext(Dispatchers.IO) { Podcasts.top() } }
    // typed: shows by that name, after a moment's pause; a feed's link pasted: that show
    val found by produceState<List<Podcasts.Show>?>(null, query) {
        val q = query.trim()
        if (q.length < 2) { value = null; return@produceState }
        delay(450)
        value = withContext(Dispatchers.IO) {
            if (q.startsWith("http")) listOfNotNull(Podcasts.fetch(q)?.first) else Podcasts.search(q)
        }
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, contentPadding = pad,
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "find") { FindShow(query) { query = it } }
        val f = found
        if (query.trim().length >= 2) {
            if (f == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("finding shows", style = pixel(12.sp, Ink.mid)) }
            else if (f.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("no shows by that name", style = pixel(12.sp, Ink.mid)) }
            else items(f, key = { "f" + it.feed }) { s -> ShowTile(s) { pages.open(Page.Show(s.feed, s)) } }
            return@LazyVerticalGrid
        }
        if (fresh.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "freshHead") { PlaceHeading("new episodes", "from the shows you follow") }
            items(fresh.take(8), key = { "e" + it.id }, span = { GridItemSpan(maxLineSpan) }) { e ->
                EpisodeRow(e, places[e.id] ?: 0L, withShow = true, now?.song?.id == e.id)
            }
        }
        if (following.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "yoursHead") { PlaceHeading("your shows", "${following.size} followed") }
            items(following, key = { "y" + it.feed }) { s -> ShowTile(s) { pages.open(Page.Show(s.feed, s)) } }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "topHead") {
            PlaceHeading("listened to most", if (following.isEmpty()) "follow a show and its new episodes are here first" else "right now, everywhere")
        }
        val t = top
        if (t == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("finding shows", style = pixel(12.sp, Ink.mid)) }
        else items(t, key = { "t" + it.feed }) { s -> ShowTile(s) { pages.open(Page.Show(s.feed, s)) } }
    }
}

@Composable
private fun PlaceHeading(title: String, line: String) {
    Column(Modifier.padding(top = 8.dp)) {
        BasicText(title.uppercase(), style = headline(22.sp))
        BasicText(line, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 3.dp))
    }
}

/** "find a show", typed in Archivo as typed (its case kept), or a feed's link pasted. */
@Composable
private fun FindShow(value: String, onValue: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        BasicText("find a show, or paste its feed", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        Box {
            if (value.isEmpty()) BasicText("SONG EXPLODER", style = headline(24.sp, Ink.dim))
            BasicTextField(
                value, onValue, singleLine = true, textStyle = headline(24.sp), cursorBrush = SolidColor(Ink.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Word("clear", pixel(12.sp, Ink.mid), Modifier.padding(top = 8.dp), touch = 8.dp) { onValue("") }
    }
}

/** A show as a tile: its cover, its name, who makes it. */
@Composable
private fun ShowTile(s: Podcasts.Show, onClick: () -> Unit) {
    Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(remember(s.feed) { Pods.face(s) }, s.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        UnderCover(s.title, s.author.lowercase(), pixel(12.sp, Ink.mid), gap = 3.dp)
    }
}

/**
 * An episode as words: its title (35% once heard through), its date and length or what's left, and its show where the
 * list is many shows'. A tap plays it from where you stopped; the one playing lit.
 */
@Composable
fun EpisodeRow(e: Song, place: Long, withShow: Boolean, on: Boolean) {
    val heard = place < 0
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Playback.play(listOf(e), 0) })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withShow) {
            CoverOf(e, e.album, Modifier.width(56.dp).aspectRatio(1f), px = 200)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(e.title.uppercase(), style = headline(16.sp, if (heard && !on) Ink.dim else Ink.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val said = (if (withShow) e.album.lowercase() + " · " else "") + (if (on) "playing" else Pods.said(e, place))
            BasicText(said, style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
        // kept on the phone for no signal: "keep", then how far it's come, then "kept" (lit; a tap lets it go)
        val ctx = LocalContext.current
        val kept by Offline.kept.collectAsState()
        val keeping by Offline.keeping.collectAsState()
        val coming = e.id in keeping
        val progress by produceState(0f, coming) {
            while (coming) { value = withContext(Dispatchers.IO) { Offline.progress(ctx, listOf(e.id)) }; delay(1_000); Offline.refresh(ctx) }
        }
        Word(
            when { e.id in kept -> "kept"; coming -> "${(progress * 100).toInt()}%"; else -> "keep" },
            pixel(12.sp, if (e.id in kept) Ink.ink else Ink.dim), Modifier.padding(start = 12.dp), touch = 10.dp,
        ) { if (e.id in kept || coming) Offline.remove(ctx, listOf(e.id)) else Offline.keep(ctx, listOf(e)) }
    }
}

/** A show: its cover and name, follow, what it's about, every episode newest first. */
@Composable
fun ShowPage(feed: String, first: Podcasts.Show?, pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // as kept at once, then read again from its feed
    var show by remember(feed) { mutableStateOf(first) }
    var episodes by remember(feed) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(feed) { mutableStateOf(false) }
    LaunchedEffect(feed) {
        Pods.start(ctx)
        withContext(Dispatchers.IO) { Podcasts.kept(feed) }?.let { (s, list) -> show = s; episodes = list.map { Pods.song(s, it) } }
        val read = withContext(Dispatchers.IO) { Podcasts.fetch(feed) }
        if (read != null) { show = read.first; episodes = read.second.map { Pods.song(read.first, it) } } else if (episodes == null) failed = true
    }
    val following by Pods.following.collectAsState()
    val followed = following.any { it.feed == feed }
    val places by Pods.places.collectAsState()
    val now by Playback.now.collectAsState()
    LaunchedEffect(now?.song?.id, now?.playing) { Pods.load(ctx) }
    var more by remember { mutableStateOf(false) }
    var shown by remember(feed) { mutableStateOf(60) }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "head") {
            val s = show
            Column {
                if (s != null) CoverOf(remember(s.feed, s.art) { Pods.face(s) }, s.title, Modifier.fillMaxWidth(.62f).aspectRatio(1f), px = 800)
                Spacer(Modifier.height(16.dp))
                BasicText((s?.title ?: "").uppercase(), style = headline(34.sp).copy(lineHeight = 34.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!s?.author.isNullOrBlank()) BasicText(s!!.author.lowercase(), style = pixel(13.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // lit is followed
                    if (s != null) Word(if (followed) "FOLLOWING" else "FOLLOW", headline(20.sp, if (followed) Ink.ink else Ink.mid), touch = 8.dp) {
                        scope.launch { Pods.follow(ctx, s, !followed) }
                    }
                    val newest = episodes?.firstOrNull { (places[it.id] ?: 0L) >= 0 }
                    if (newest != null) Word("play the newest", pixel(14.sp, Ink.mid), Modifier.padding(start = 24.dp), touch = 8.dp) { Playback.play(listOf(newest), 0) }
                }
                val about = s?.about.orEmpty()
                if (about.isNotBlank()) BasicText(about.lowercase(), style = pixel(12.sp, Ink.mid).copy(lineHeight = 19.sp),
                    maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp).clickable(remember { MutableInteractionSource() }, indication = null) { more = !more })
                if (failed) BasicText("couldn't read this show's feed", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp))
                if (episodes == null && !failed) BasicText("reading its episodes", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp))
                episodes?.let { BasicText("${it.size} episodes", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp)) }
            }
        }
        val all = episodes.orEmpty()
        items(all.take(shown), key = { it.id }) { e -> EpisodeRow(e, places[e.id] ?: 0L, withShow = false, now?.song?.id == e.id) }
        if (all.size > shown) item(key = "more") { Word("older episodes", pixel(14.sp, Ink.mid), touch = 8.dp) { shown += 60 } }
    } }
}
