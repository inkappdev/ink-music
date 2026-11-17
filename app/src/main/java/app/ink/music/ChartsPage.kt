package app.ink.music

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's charts (Charts, in youtube/; the computer's, 2026-11-17): the place chosen (kept; at first where the phone
 * says it is, if it's charted, else the world), and each chart's looks kept (files/charts.json), so a chart says what
 * moved since a day ago.
 */
object ChartsKept {
    val place = MutableStateFlow("")
    private val looks = HashMap<String, Charts.Looks>()
    @Volatile private var read = false
    private fun file(c: Context) = File(c.filesDir, "charts.json")

    @Synchronized fun load(c: Context) {
        if (read) return
        read = true
        val o = runCatching { JSONObject(file(c).readText()) }.getOrDefault(JSONObject())
        place.value = o.optString("place").ifEmpty {
            java.util.Locale.getDefault().country.uppercase().takeIf { k -> Charts.PLACES.any { it.first == k } } ?: "ZZ"
        }
        o.optJSONObject("looks")?.let { l ->
            l.keys().forEach { k ->
                val x = l.getJSONObject(k)
                fun ids(a: JSONArray?) = a?.let { (0 until it.length()).map(it::getString) }
                looks[k] = Charts.Looks(ids(x.optJSONArray("before")), x.optLong("at"), ids(x.optJSONArray("ids")).orEmpty())
            }
        }
    }

    fun place(c: Context, code: String) { place.value = code; save(c) }

    /** A chart looked at now with these songs: each one's move since the look a day or more before. */
    @Synchronized fun moves(c: Context, chart: String, songs: List<Song>): List<String> {
        load(c)
        val ids = songs.map { it.id }
        val l = Charts.look(looks[chart], ids)
        if (l != looks[chart]) { looks[chart] = l; save(c) }
        return Charts.moves(l.before, ids)
    }

    @Synchronized private fun save(c: Context) = runCatching {
        val l = JSONObject()
        looks.forEach { (k, v) -> l.put(k, JSONObject().put("at", v.at).put("ids", JSONArray(v.ids)).apply { v.before?.let { put("before", JSONArray(it)) } }) }
        file(c).writeText(JSONObject().put("place", place.value).put("looks", l).toString())
    }
}

/** A chart as YouTube Music's record-shaped list, to read its songs as any playlist's. */
fun Charts.Chart.album() = TubeAlbum(url, "$name ${place.lowercase()}", "charts", null)

/**
 * A chart's face, ink's own (2026-11-17: YouTube's coloured squares said its name, not ours): on black in a hairline, what
 * it is small at its top, its number huge, its genre under it in capitals, its place along its foot. `k` its scale.
 */
@Composable
fun ChartFace(c: Charts.Chart, modifier: Modifier, k: Float = 1f) {
    Box(modifier.background(Ink.black).border(1.dp, Ink.line).padding((10 * k).dp)) {
        val what = when (c.kind) {
            Charts.Kind.GENRE, Charts.Kind.TOP -> "top"
            Charts.Kind.TODAY -> "today's top"
            Charts.Kind.TRENDING -> "trending"
            Charts.Kind.LIVE -> "live, top"
        }
        BasicText(what, style = pixel((12 * k).sp, Ink.mid), modifier = Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.CenterStart)) {
            val big = (if (c.number.isEmpty()) 28 else 52) * k
            BasicText(c.number.ifEmpty { "TODAY" }, style = headline(big.sp).copy(lineHeight = big.sp))
            val word = when (c.kind) { Charts.Kind.GENRE -> c.genre; Charts.Kind.LIVE -> "performances"; Charts.Kind.TRENDING -> "now"; else -> "music videos" }
            BasicText(word.uppercase(), style = headline((15 * k).sp).copy(lineHeight = (16 * k).sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        BasicText(c.place.lowercase(), style = pixel((12 * k).sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** Listen's charts: the place's, a row of them, all of them a word away. Nothing while they're read. */
@Composable
fun ChartsShelf(pages: Pages) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChartsKept.load(ctx) }
    val place by ChartsKept.place.collectAsState()
    val charts by produceState<List<Charts.Chart>>(emptyList(), place) {
        if (place.isEmpty() || !YouTubeMusic.available) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { Charts.of(place, YouTubeMusic::chartLists) }.getOrDefault(emptyList()) }
    }
    if (charts.isEmpty()) return
    Column {
        Heading("charts", "what's played most in ${Charts.placeName(place)}, and what moved") {
            Word("all charts", pixel(13.sp), touch = 10.dp) { pages.open(Page.ChartsAll) }
        }
        LazyRow(Modifier.tvShelf(), state = tvRowState(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(charts, key = { it.id }) { c ->
                Box(Modifier.width(130.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.Chart(c)) })) {
                    ChartFace(c, Modifier.fillMaxWidth().aspectRatio(1f), .85f)
                }
            }
        }
    }
}

/** All the charts: a place chosen (the world, then countries), its charts, its genres where it has them. */
@Composable
fun ChartsPage(pages: Pages) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChartsKept.load(ctx) }
    val place by ChartsKept.place.collectAsState()
    val charts by produceState<List<Charts.Chart>?>(null, place) {
        value = null
        if (place.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { Charts.of(place, YouTubeMusic::chartLists) }.getOrDefault(emptyList()) }
    }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                BasicText("CHARTS", style = headline(44.sp))
                BasicText("what's played most, from youtube music's charts; each says what moved since you last looked", style = pixel(12.sp, Ink.mid))
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(Charts.PLACES, key = { it.first }) { (code, name) ->
                    Word(name, pixel(13.sp, if (code == place) Ink.ink else Ink.dim), touch = 8.dp) { ChartsKept.place(ctx, code) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        val c = charts
        if (c == null) item { BasicText("reading the charts", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(horizontal = 20.dp)) }
        else if (c.isEmpty()) item { BasicText("no charts there just now", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(horizontal = 20.dp)) }
        else {
            val (genres, own) = c.partition { it.kind == Charts.Kind.GENRE }
            listOf("the charts" to own, "by genre" to genres).filter { it.second.isNotEmpty() }.forEach { (label, these) ->
                item { Label(label) }
                // two across, the phone's width
                items(these.chunked(2)) { row ->
                    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { x ->
                            Box(Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.Chart(x)) })) {
                                ChartFace(x, Modifier.fillMaxWidth().aspectRatio(1f))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    } }
}

/**
 * A chart: its face, play, shuffle and mix; its songs by rank, each its move since a day or more ago ("new", "up 3",
 * "down 2").
 */
@Composable
fun ChartPage(c: Charts.Chart, pages: Pages) {
    val ctx = LocalContext.current
    val a = remember(c.id) { c.album() }
    val songs by produceState<List<Song>?>(null, c.id) { value = YouTubeMusic.album(a) }
    val moves = remember(songs) { songs?.takeIf { it.isNotEmpty() }?.let { ChartsKept.moves(ctx, c.id, it) } }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = headerSide(maxWidth)
                ChartFace(c, Modifier.size(side), side.value / 150f)
            }
            Spacer(Modifier.height(18.dp))
            BasicText(c.name.uppercase(), style = headline(36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            BasicText(c.place.lowercase() + "   " + (songs?.let { "${it.size} songs" } ?: "reading it"), style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
            val all = songs
            if (!all.isNullOrEmpty()) Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all.shuffled(), 0) }
                MixWord(a.title, all, Modifier.padding(end = 26.dp))
            }
            MixProgress(a.title)
            val news = moves.orEmpty().count { it == "new" }
            if (news > 0) BasicText("$news new since you last looked", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(14.dp))
        }
        val all = songs.orEmpty()
        itemsIndexed(all, key = { _, s -> s.id }) { i, s ->
            val move = moves?.getOrNull(i).orEmpty().let { m ->
                when { m == "new" -> "new"; m.startsWith("+") -> "up ${m.drop(1)}"; m.startsWith("-") -> "down ${m.drop(1)}"; else -> "" }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(44.dp)) {
                    BasicText("${i + 1}", style = headline(18.sp))
                    if (move.isNotEmpty()) BasicText(move, style = pixel(12.sp, if (move.startsWith("down")) Ink.mid else Ink.ink), maxLines = 1)
                }
                Box(Modifier.weight(1f)) { SongRow(s, pages = pages, cover = true) { Playback.play(all, i) } }
            }
        }
    } }
}
