package app.ink.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explore: what isn't yours yet. On the left the week on indie radio (the phone's indie chart: what KEXP, KCRW and
 * WXPN played most), drawn as a chart, the place big; on the right YouTube Music's new records, then its home's
 * shelves (as it shows them to anyone signed out). Side by side in a wide window, one above the other in a narrow one.
 */
@Composable
fun ExplorePage(open: (Page) -> Unit) {
    val chart by produceState<List<IndieChart.Entry>?>(null) { value = IndieChart.week() }
    val fresh by produceState<List<TubeAlbum>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { InnerTube().items("FEmusic_new_releases_albums", 24).mapNotNull(::tubeAlbum) }.getOrDefault(emptyList()) }
    }
    val shelves by produceState<List<Pair<String, List<TubeAlbum>>>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { InnerTube().home(12).map { it.title to it.items.mapNotNull(::tubeAlbum) }.filter { it.first.isNotEmpty() && it.second.size >= 3 } }
                .getOrDefault(emptyList())
        }
    }
    val moods by produceState<List<InnerTube.Mood>?>(Moods.kept()) { value = Moods.read() ?: value }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 860.dp
        val chartList = @Composable { m: Modifier ->
            val list = rememberLazyListState()
            val c = chart
            LazyColumn(m.feather(list), list, contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp)) {
                item {
                    BasicText("THE INDIE CHART", style = headline(30.sp))
                    BasicText("this week on kexp, kcrw and wxpn, the songs they played most", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    if (c == null) BasicText("counting this week's plays", style = pixel(12.sp, Ink.mid))
                }
                val songs = c.orEmpty().mapNotNull { it.song }
                itemsIndexed(c.orEmpty(), key = { i, e -> "$i" + e.title }) { i, e ->
                    val s = e.song ?: return@itemsIndexed
                    // a chart: the place big beside each
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("${i + 1}", style = headline(30.sp), modifier = Modifier.width(56.dp))
                        Box(Modifier.weight(1f)) {
                            SongRow(s, said = (s.artist + "  ·  " + e.stations.joinToString(" ")).lowercase(), open = open) {
                                Player.play(songs, songs.indexOf(s).coerceAtLeast(0))
                            }
                        }
                    }
                }
            }
        }
        val records = @Composable { m: Modifier ->
            val grid = rememberLazyGridState()
            LazyVerticalGrid(GridCells.Adaptive(150.dp), m.feather(grid), grid, contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                // right now, then every mood and genre (YouTube Music's own), before what's new
                item(span = { GridItemSpan(maxLineSpan) }) { MoodsAndGenres(moods, open) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        BasicText("NEW RECORDS", style = headline(30.sp))
                        BasicText(if (fresh == null) "reading youtube music" else "this week on youtube music", style = pixel(12.sp, Ink.mid))
                    }
                }
                items(fresh.orEmpty(), key = { "n" + it.url }) { a -> TubeTile(a, Modifier) { open(Page.Tube(a)) } }
                shelves.orEmpty().forEach { (title, items) ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(1.dp)); Label(title.lowercase()) }
                    items(items.take(12), key = { title + it.url }) { a -> TubeTile(a, Modifier) { open(Page.Tube(a)) } }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            chartList(Modifier.weight(1f).fillMaxHeight())
            records(Modifier.weight(1.1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE)) {
            chartList(Modifier.weight(1f))
            records(Modifier.weight(1f))
        }
    }
}

/** A record or playlist YouTube Music shows, as one to open: its page, its name, who (from what's said under it). */
private fun tubeAlbum(it: InnerTube.Item): TubeAlbum? = when (it.kind) {
    InnerTube.Kind.RECORD -> TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, who(it.subtitle), it.art)
    InnerTube.Kind.PLAYLIST -> TubeAlbum("${InnerTube.BASE}/playlist?list=${it.id.removePrefix("VL")}", it.title, who(it.subtitle), it.art)
    else -> null
}

/** "Album • Fcukers • 2026" as who: the part that isn't the kind or the year. */
private fun who(subtitle: String) = subtitle.split(" • ").map { it.trim() }
    .firstOrNull { it.isNotEmpty() && it !in setOf("Album", "Single", "EP", "Playlist") && !it.matches(Regex("\\d{4}")) }.orEmpty()

/**
 * Moods and genres, YouTube Music's own (chill, focus, sleep...; african to soundtracks), each a page of "flavors": its
 * playlists in shelves. Right now picks three for the hour, the day and the season; the genres you open most come first.
 * A station from a mood: a playlist from each of its first flavors, mixed, radio after.
 */
object Moods {
    private val file = java.io.File(Home.cache, "moods.json")
    private val opened = java.io.File(Home.dir, "moods-opened.json")
    val counts = kotlinx.coroutines.flow.MutableStateFlow(runCatching {
        org.json.JSONObject(opened.readText()).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
    }.getOrDefault(emptyMap()))

    fun kept(): List<InnerTube.Mood>? = runCatching {
        val a = org.json.JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { InnerTube.Mood(it.getString("title"), it.getString("params"), it.getBoolean("genre")) }
    }.getOrNull()

    suspend fun read(): List<InnerTube.Mood>? = withContext(Dispatchers.IO) {
        InnerTube().moods().takeIf { it.isNotEmpty() }?.also { list ->
            runCatching { Home.write(file, org.json.JSONArray(list.map { org.json.JSONObject().put("title", it.title).put("params", it.params).put("genre", it.genre) }).toString()) }
        }
    }

    fun open(m: InnerTube.Mood) {
        counts.value = counts.value + (m.title to (counts.value[m.title] ?: 0) + 1)
        runCatching { Home.write(opened, org.json.JSONObject(counts.value).toString()) }
    }

    /** Three moods for now: the hour, weekday or weekend, and the season's own (halloween, late october). */
    fun now(all: List<InnerTube.Mood>): List<InnerTube.Mood> {
        val t = java.time.LocalDateTime.now()
        val weekend = t.dayOfWeek == java.time.DayOfWeek.SATURDAY || t.dayOfWeek == java.time.DayOfWeek.SUNDAY
        val going = t.dayOfWeek == java.time.DayOfWeek.FRIDAY || t.dayOfWeek == java.time.DayOfWeek.SATURDAY
        val picks = when (t.hour) {
            in 5..8 -> if (weekend) listOf("Feel good", "Chill", "Romance") else listOf("Commute", "Energize", "Feel good")
            in 9..16 -> if (weekend) listOf("Feel good", "Chill", "Energize") else listOf("Focus", "Feel good", "Chill")
            in 17..20 -> if (going) listOf("Party", "Feel good", "Chill") else listOf("Chill", "Feel good", "Romance")
            in 21..23 -> if (going) listOf("Party", "Romance", "Chill") else listOf("Chill", "Romance", "Sleep")
            else -> listOf("Sleep", "Chill", "Sad")
        }.toMutableList()
        all.firstOrNull { !it.genre && it.title !in setOf("Chill", "Commute", "Energize", "Feel good", "Focus", "Gaming", "Party", "Romance", "Sad", "Sleep", "Workout") }
            ?.let { seasonal -> picks.add(0, seasonal.title) }
        return picks.mapNotNull { p -> all.firstOrNull { it.title.equals(p, true) } }.take(3)
    }

    /** "a wednesday evening in autumn". */
    fun said(): String {
        val t = java.time.LocalDateTime.now()
        val part = when (t.hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night" }
        val season = when (t.monthValue) { 12, 1, 2 -> "winter"; 3, 4, 5 -> "spring"; 6, 7, 8 -> "summer"; else -> "autumn" }
        return "a ${t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH).lowercase()} $part in $season"
    }

    /** A station from a mood: the first playlist of each of its first flavors, their songs taken in turn, radio after. */
    suspend fun station(m: InnerTube.Mood) {
        val songs = withContext(Dispatchers.IO) {
            val lists = InnerTube().mood(m.params).take(4).mapNotNull { it.items.firstOrNull { i -> i.kind == InnerTube.Kind.PLAYLIST } }
            val each = lists.map { runCatching { InnerTube().tracks(it.id, 30).map(YouTube::song).shuffled() }.getOrDefault(emptyList()) }
            (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }.take(80)
        }
        if (songs.isEmpty()) return
        if (!Player.radio.value) Player.radio(true)
        Player.play(songs)
    }
}

/** Right now (three moods for the hour, a station each), then every mood and genre as words. */
@Composable
private fun MoodsAndGenres(moods: List<InnerTube.Mood>?, open: (Page) -> Unit) {
    val all = moods ?: return
    val counts by Moods.counts.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.padding(bottom = 24.dp)) {
        BasicText("RIGHT NOW", style = headline(30.sp))
        BasicText("picked for ${Moods.said()}", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(12.dp))
        Moods.now(all).forEach { m ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                Word(m.title.uppercase(), headline(40.sp), Modifier.weight(1f), touch = 4.dp) { Moods.open(m); open(Page.Mood(m)) }
                Word("start station", pixel(13.sp), Modifier.padding(bottom = 8.dp), touch = 8.dp) { scope.launch { Moods.station(m) } }
            }
        }
        Spacer(Modifier.height(22.dp))
        Label("moods & moments")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            all.filter { !it.genre }.forEach { m -> Word(m.title.uppercase(), headline(22.sp, Ink.mid)) { Moods.open(m); open(Page.Mood(m)) } }
        }
        Spacer(Modifier.height(10.dp))
        Label("genres, the ones you open most first")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            all.filter { it.genre }.sortedByDescending { counts[it.title] ?: 0 }.forEach { m ->
                Word(m.title.lowercase(), pixel(14.sp, if ((counts[m.title] ?: 0) > 0) Ink.ink else Ink.mid), touch = 4.dp) { Moods.open(m); open(Page.Mood(m)) }
            }
        }
    }
}

/** A mood's page: its flavors, each a row of YouTube Music's playlists for it; a station from all of it. */
@Composable
fun MoodPage(m: InnerTube.Mood, open: (Page) -> Unit, onClose: () -> Unit) {
    val shelves by produceState<List<InnerTube.Shelf>?>(null, m.params) { value = withContext(Dispatchers.IO) { InnerTube().mood(m.params) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 10.dp, bottom = 32.dp)) {
            item {
                BasicText(m.title.uppercase(), style = headline(64.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(shelves?.let { "${it.size} flavors on youtube music" } ?: "reading youtube music", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                    Word("start station", pixel(14.sp), touch = 8.dp) { scope.launch { Moods.station(m) } }
                }
                Spacer(Modifier.height(10.dp))
            }
            shelves.orEmpty().forEach { shelf ->
                item(key = shelf.title) {
                    Label(shelf.title.lowercase())
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(shelf.items.mapNotNull(::tubeAlbum), key = { it.url }) { a -> TubeTile(a, Modifier.width(160.dp)) { open(Page.Tube(a)) } }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}
