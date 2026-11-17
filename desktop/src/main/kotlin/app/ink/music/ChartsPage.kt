package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The computer's charts (Charts, in youtube/): the place chosen (kept; at first where the computer says it is, if it's
 * charted, else the world), and each chart's looks kept (charts.json), so a chart says what moved since a day ago.
 */
object ChartsKept {
    private val file get() = File(Home.dir, "charts.json")
    private val o = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    val place = MutableStateFlow(o.optString("place").ifEmpty {
        java.util.Locale.getDefault().country.uppercase().takeIf { c -> Charts.PLACES.any { it.first == c } } ?: "ZZ"
    })

    fun place(code: String) { place.value = code; save() }

    private val looks = HashMap<String, Charts.Looks>().apply {
        o.optJSONObject("looks")?.let { l ->
            l.keys().forEach { k ->
                val x = l.getJSONObject(k)
                fun ids(a: JSONArray?) = a?.let { (0 until it.length()).map(it::getString) }
                put(k, Charts.Looks(ids(x.optJSONArray("before")), x.optLong("at"), ids(x.optJSONArray("ids")).orEmpty()))
            }
        }
    }

    /** A chart looked at now with these songs: each one's move since the look a day or more before. */
    @Synchronized fun moves(chart: String, songs: List<Song>): List<String> {
        val ids = songs.map { it.id }
        val l = Charts.look(looks[chart], ids)
        if (l != looks[chart]) { looks[chart] = l; save() }
        return Charts.moves(l.before, ids)
    }

    @Synchronized private fun save() = runCatching {
        val l = JSONObject()
        looks.forEach { (k, v) -> l.put(k, JSONObject().put("at", v.at).put("ids", JSONArray(v.ids)).apply { v.before?.let { put("before", JSONArray(it)) } }) }
        Home.write(file, JSONObject().put("place", place.value).put("looks", l).toString())
    }
}

/** A chart as YouTube Music's record-shaped list, to read its songs as any playlist's. */
fun Charts.Chart.album() = TubeAlbum(url, "$name ${Charts.placeName(place).ifEmpty { place }}", "charts", null)

/**
 * A chart's tile, ink's own (2026-11-17: YouTube's coloured squares said its name, not ours): on black in a hairline, what
 * it is small at its top, its number huge, its genre under it in capitals, its place along its foot.
 */
@Composable
fun ChartTile(c: Charts.Chart, modifier: Modifier, onClick: () -> Unit) {
    WithMenu({ listOf(listOf("open" to onClick, "play" to { playTube(c.album()); Unit }, "shuffle" to { playTube(c.album(), shuffle = true); Unit })) }) {
        Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
            ChartFace(c, Modifier.fillMaxWidth().aspectRatio(1f), 1f)
        }
    }
}

/** A chart's face, `k` its scale (a tile 1, the chart's page bigger). */
@Composable
fun ChartFace(c: Charts.Chart, modifier: Modifier, k: Float) {
    Box(modifier.background(Ink.black).border(1.dp, Ink.line).padding((12 * k).dp)) {
        val what = when (c.kind) {
            Charts.Kind.GENRE, Charts.Kind.TOP -> "top"
            Charts.Kind.TODAY -> "today's top"
            Charts.Kind.TRENDING -> "trending"
            Charts.Kind.LIVE -> "live, top"
        }
        BasicText(what, style = pixel((12 * k).sp, Ink.mid), modifier = Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.CenterStart)) {
            BasicText(c.number.ifEmpty { "TODAY" }, style = headline(((if (c.number.isEmpty()) 34 else 64) * k).sp).copy(lineHeight = ((if (c.number.isEmpty()) 34 else 64) * k).sp))
            val word = when (c.kind) { Charts.Kind.GENRE -> c.genre; Charts.Kind.LIVE -> "performances"; Charts.Kind.TRENDING -> "now"; else -> "music videos" }
            BasicText(word.uppercase(), style = headline((18 * k).sp).copy(lineHeight = (19 * k).sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        BasicText(c.place.lowercase(), style = pixel((12 * k).sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** All the charts: a place chosen (the world, then countries), its charts, its genres where it has them. */
@Composable
fun ChartsPage(open: (Page) -> Unit, onClose: () -> Unit) {
    val place by ChartsKept.place.collectAsState()
    val charts by produceState<List<Charts.Chart>?>(null, place) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { Charts.of(place) { InnerTube().chartLists(it) } }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        val grid = pageGrid("charts")
        LazyVerticalGrid(
            GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
            contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Column {
                    BasicText("CHARTS", style = headline(56.sp))
                    BasicText("what's played most, from youtube music's charts; each one says what moved since you last looked", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(14.dp))
                    // opened at the place chosen, so it's in sight, lit
                    val row = androidx.compose.foundation.lazy.rememberLazyListState()
                    androidx.compose.runtime.LaunchedEffect(place) { Charts.PLACES.indexOfFirst { it.first == place }.takeIf { it > 0 }?.let { row.scrollToItem((it - 1).coerceAtLeast(0)) } }
                    Shelf(gap = 18.dp, state = row) {
                        Charts.PLACES.forEach { (code, name) ->
                            item { Word(name, pixel(13.sp, if (code == place) Ink.ink else Ink.dim), touch = 8.dp) { ChartsKept.place(code) } }
                        }
                    }
                }
            }
            val c = charts
            if (c == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("reading the charts", style = pixel(12.sp, Ink.mid)) }
            else if (c.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("no charts there just now", style = pixel(12.sp, Ink.mid)) }
            else {
                val (genres, own) = c.partition { it.kind == Charts.Kind.GENRE }
                item(span = { GridItemSpan(maxLineSpan) }, key = "l-own") { Label("the charts") }
                items(own, key = { it.id }) { x -> ChartTile(x, Modifier.fillMaxWidth()) { open(Page.Chart(x)) } }
                if (genres.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "l-genres") { Label("by genre") }
                    items(genres, key = { it.id }) { x -> ChartTile(x, Modifier.fillMaxWidth()) { open(Page.Chart(x)) } }
                }
            }
        }
    }
}

/**
 * A chart: its face, play, shuffle and mix; its songs by rank, each its move since a day or more ago ("new", "up 3",
 * "down 2"), read fresh each time (the last reading shown at once).
 */
@Composable
fun ChartPage(c: Charts.Chart, open: (Page) -> Unit, onClose: () -> Unit) {
    val a = remember(c.id) { c.album() }
    val songs by produceState<List<Song>?>(null, c.id) {
        YouTube.kept(a)?.let { value = it }
        val fresh = YouTube.read(a)
        if (fresh.isNotEmpty()) value = fresh else if (value == null) value = emptyList()
    }
    val moves = remember(songs) { songs?.takeIf { it.isNotEmpty() }?.let { ChartsKept.moves(c.id, it) } }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 16.dp)) {
            Column(Modifier.width(320.dp)) {
                ChartFace(c, Modifier.size(320.dp), 1.7f)
                Spacer(Modifier.height(16.dp))
                BasicText(c.name.uppercase(), style = headline(30.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                BasicText(c.place.lowercase() + "  ·  " + (songs?.let { "${it.size} songs" } ?: "reading"), style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(16.dp))
                songs?.takeIf { it.isNotEmpty() }?.let { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayShuffle(s)
                        MixWord(a.title, s, Modifier.padding(start = 24.dp), bold = true)
                    }
                    MixProgress(a.title)
                    val ups = moves.orEmpty().count { it == "new" }
                    if (ups > 0) BasicText("$ups new since you last looked", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp))
                }
            }
            Spacer(Modifier.width(40.dp))
            val list = pageList("chart")
            LazyColumn(Modifier.weight(1f).fillMaxHeight().feather(list), list, contentPadding = PaddingValues(bottom = 32.dp)) {
                val s = songs.orEmpty()
                itemsIndexed(s, key = { _, x -> x.id }) { i, x ->
                    val move = moves?.getOrNull(i).orEmpty().let { m ->
                        when { m == "new" -> "new"; m.startsWith("+") -> "up ${m.drop(1)}"; m.startsWith("-") -> "down ${m.drop(1)}"; else -> "" }
                    }
                    SongRow(x, number = "${i + 1}", said = listOf(move, x.artist.lowercase()).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        open = open, cover = true, order = s) { Player.play(s, i) }
                }
            }
        }
    }
}
