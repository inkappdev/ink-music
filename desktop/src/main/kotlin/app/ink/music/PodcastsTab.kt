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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What's typed in find a show, kept while you look at one and come back. */
private var showQuery by mutableStateOf("")

/**
 * Podcasts, a place on the rail beside radio (the phone's, 2026-10-31): find a show (Apple's directory, or its feed's
 * link pasted), the newest episodes of the shows you follow, your shows, and what's listened to most now. What you
 * follow and where you stopped come over from the phone by sync, and go back to it.
 */
@Composable
fun PodcastsTab(open: (Page) -> Unit) {
    val following by Pods.following.collectAsState()
    val fresh by Pods.fresh.collectAsState()
    val places by Pods.places.collectAsState()
    val now by Player.now.collectAsState()
    LaunchedEffect(Unit) { Pods.refresh() }
    // what plays moved on: where you stopped, read again
    LaunchedEffect(now?.song?.id, now?.playing) { Pods.load() }
    val top by produceState<List<Podcasts.Show>?>(null) { value = withContext(Dispatchers.IO) { Podcasts.top() } }
    // typed: shows by that name, after a moment's pause; a feed's link pasted: that show
    val found by produceState<List<Podcasts.Show>?>(null, showQuery) {
        val q = showQuery.trim()
        if (q.length < 2) { value = null; return@produceState }
        delay(450)
        value = withContext(Dispatchers.IO) { if (q.startsWith("http")) listOfNotNull(Podcasts.fetch(q)?.first) else Podcasts.search(q) }
    }
    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
        contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "find") { FindShow() }
        if (showQuery.trim().length >= 2) {
            val f = found
            if (f == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("finding shows", style = pixel(12.sp, Ink.mid)) }
            else if (f.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("no shows by that name", style = pixel(12.sp, Ink.mid)) }
            else items(f, key = { "f" + it.feed }) { s -> ShowTile(s) { open(Page.Show(s.feed, s)) } }
            return@LazyVerticalGrid
        }
        if (fresh.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "freshHead") {
                Section("NEW EPISODES", "from the shows you follow", Modifier.padding(top = 20.dp)) {
                    Word("play them", pixel(14.sp), touch = 8.dp) { Player.play(fresh.take(8)) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "fresh") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    fresh.take(8).forEach { e -> EpisodeRow(e, places[e.id] ?: 0L, withShow = true, on = now?.song?.id == e.id, open = open) }
                }
            }
        }
        if (following.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "yoursHead") { Section("YOUR SHOWS", "${following.size} followed", Modifier.padding(top = 20.dp)) }
            items(following, key = { "y" + it.feed }) { s -> ShowTile(s) { open(Page.Show(s.feed, s)) } }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "topHead") {
            Section("LISTENED TO MOST", if (following.isEmpty()) "follow a show and its new episodes are here first" else "right now, everywhere",
                Modifier.padding(top = 20.dp))
        }
        val t = top
        if (t == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("finding shows", style = pixel(12.sp, Ink.mid)) }
        else items(t, key = { "t" + it.feed }) { s -> ShowTile(s) { open(Page.Show(s.feed, s)) } }
    }
}

/** "find a show", typed in Archivo as typed (its case kept), or a feed's link pasted. */
@Composable
private fun FindShow() {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        BasicText("find a show, or paste its feed", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (showQuery.isEmpty()) BasicText("SONG EXPLODER", style = headline(30.sp, Ink.dim))
                BasicTextField(showQuery, { showQuery = it }, singleLine = true, textStyle = headline(30.sp), cursorBrush = SolidColor(Ink.ink),
                    modifier = Modifier.fillMaxWidth())
            }
            if (showQuery.isNotEmpty()) Word("clear", pixel(13.sp, Ink.mid), Modifier.padding(start = 16.dp), touch = 8.dp) { showQuery = "" }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}

/** A show as a tile: its cover, its name, who makes it. */
@Composable
private fun ShowTile(s: Podcasts.Show, onClick: () -> Unit) {
    Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(remember(s.feed, s.art) { Pods.face(s) }, s.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        Spacer(Modifier.height(8.dp))
        UnderCover(s.title, s.author)
    }
}

/**
 * An episode as words: its title (35% once heard through), its date and length or what's left, and its show (a click
 * opens it) where the list is many shows'. A click plays it from where you stopped; the one playing lit; play next and
 * add to queue beside it.
 */
@Composable
private fun EpisodeRow(e: Song, place: Long, withShow: Boolean, on: Boolean, open: (Page) -> Unit) {
    val heard = place < 0
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(listOf(e)) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withShow) {
            CoverOf(e, e.album, Modifier.size(56.dp), px = 160)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(e.title.uppercase(), style = headline(17.sp, if (heard && !on) Ink.dim else Ink.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp)) {
                if (withShow) Word(e.album.lowercase() + " · ", pixel(12.sp, Ink.mid)) { Pods.page(e)?.let(open) }
                BasicText(if (on) "playing" else Pods.said(e, place), style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Word("play next", pixel(12.sp, Ink.dim), Modifier.padding(start = 16.dp), touch = 8.dp) { Player.playNext(e) }
        Word("add to queue", pixel(12.sp, Ink.dim), Modifier.padding(start = 16.dp), touch = 8.dp) { Player.add(e) }
    }
}

/** A show: its cover and name, follow, what it's about beside every episode newest first. */
@Composable
fun ShowPage(feed: String, first: Podcasts.Show?, open: (Page) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // as kept at once, then read again from its feed
    var show by remember(feed) { mutableStateOf(first) }
    var episodes by remember(feed) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(feed) { mutableStateOf(false) }
    LaunchedEffect(feed) {
        withContext(Dispatchers.IO) { Podcasts.kept(feed) }?.let { (s, list) -> show = s; episodes = list.map { Pods.song(s, it) } }
        val read = withContext(Dispatchers.IO) { Podcasts.fetch(feed) }
        if (read != null) { show = read.first; episodes = read.second.map { Pods.song(read.first, it) } } else if (episodes == null) failed = true
    }
    val following by Pods.following.collectAsState()
    val followed = following.any { it.feed == feed }
    val places by Pods.places.collectAsState()
    val now by Player.now.collectAsState()
    LaunchedEffect(now?.song?.id, now?.playing) { Pods.load() }
    var shown by remember(feed) { mutableStateOf(60) }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 16.dp)) {
            val s = show
            Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                if (s != null) CoverOf(remember(s.feed, s.art) { Pods.face(s) }, s.title, Modifier.size(320.dp), px = 800)
                Spacer(Modifier.height(16.dp))
                BasicText((s?.title ?: "").uppercase(), style = headline(30.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (!s?.author.isNullOrBlank()) BasicText(s!!.author.lowercase(), style = pixel(14.sp), modifier = Modifier.padding(top = 8.dp))
                episodes?.let { BasicText("${it.size} episodes", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(16.dp))
                // lit is followed
                if (s != null) Word(if (followed) "FOLLOWING" else "FOLLOW", headline(22.sp, if (followed) Ink.ink else Ink.mid), touch = 8.dp) {
                    scope.launch { Pods.follow(s, !followed) }
                }
                val newest = episodes?.firstOrNull { (places[it.id] ?: 0L) >= 0 }
                if (newest != null) Word("play the newest", pixel(14.sp), Modifier.padding(top = 8.dp), touch = 8.dp) { Player.play(listOf(newest)) }
                val about = s?.about.orEmpty()
                if (about.isNotBlank()) BasicText(about.lowercase(), style = pixel(12.sp, Ink.mid).copy(lineHeight = 19.sp),
                    modifier = Modifier.padding(top = 18.dp, bottom = 32.dp))
            }
            Spacer(Modifier.width(40.dp))
            val list = rememberLazyListState()
            LazyColumn(Modifier.weight(1f).fillMaxHeight().feather(list), list, contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (failed) item { BasicText("couldn't read this show's feed", style = pixel(12.sp, Ink.mid)) }
                if (episodes == null && !failed) item { BasicText("reading its episodes", style = pixel(12.sp, Ink.mid)) }
                val all = episodes.orEmpty()
                items(all.take(shown), key = { it.id }) { e -> EpisodeRow(e, places[e.id] ?: 0L, withShow = false, on = now?.song?.id == e.id, open = open) }
                if (all.size > shown) item(key = "more") { Word("older episodes", pixel(14.sp, Ink.mid), touch = 8.dp) { shown += 60 } }
            }
        }
    }
}

/**
 * An episode's controls in the player, where a song's are: its speed (a click steps it: 1, 1.2, 1.5, 1.8, 2, .8;
 * kept for every episode), back 15 s, play, on 30 s.
 */
@Composable
fun EpisodeControls(n: Player.Now, modifier: Modifier = Modifier) {
    var speed by remember { mutableStateOf(Podcasts.speed) }
    val speeds = listOf(1f, 1.2f, 1.5f, 1.8f, 2f, .8f)
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Word(speedSaid(speed), headline(20.sp, if (speed != 1f) Ink.ink else Ink.dim), touch = 10.dp) {
            speed = speeds[(speeds.indexOfFirst { kotlin.math.abs(it - speed) < .01f } + 1) % speeds.size]
            Player.speed(speed)
        }
        Word("-15", headline(22.sp), touch = 10.dp) { Player.seek((Player.position.value - 15_000).coerceAtLeast(0)) }
        Pixel(if (n.playing) PAUSE else PLAY, if (n.playing) "pause" else "play", Ink.ink, cell = 3.dp) { Player.toggle() }
        Word("+30", headline(22.sp), touch = 10.dp) {
            Player.seek((Player.position.value + 30_000).let { if (n.duration > 0) it.coerceAtMost(n.duration - 1_000) else it })
        }
        Pixel(NEXT, "next", Ink.ink, cell = 3.dp) { Player.next() }
    }
}

private fun speedSaid(v: Float) = (if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(java.util.Locale.US, v)) + "×"

/** An episode's notes, in the lyrics' place: plain text, scrolled, feathered at the foot. */
@Composable
fun EpisodeNotes(id: String, modifier: Modifier = Modifier) {
    val text = remember(id) { Pods.notes(id) }
    if (text.isBlank()) return BasicText("no notes with this episode", style = pixel(13.sp, Ink.mid), modifier = modifier)
    val scroll = rememberScrollState()
    Box(modifier.feather(scroll)) {
        BasicText(text.lowercase(), style = pixel(13.sp, Ink.ink).copy(lineHeight = 21.sp),
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = 40.dp))
    }
}
