package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ink's moods here ([InkMoods]): each mood's songs and a cover for its tile kept for the week (Last.fm's tags, iTunes'
 * cover, a few asks at a time), and its station: the first songs found on YouTube Music at once, the rest behind.
 */
object Moodboard {
    data class Kept(val songs: List<Pair<String, String>>, val art: String?)

    val kept = MutableStateFlow<Map<String, Kept>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(Home.cache, "ink-moods.json")
    private var started = false

    private fun week() = java.time.LocalDate.now().toEpochDay() / 7

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val o = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (o != null) kept.value = read(o)
            if (o?.optLong("week") == week() && kept.value.size == InkMoods.ALL.size) return@launch
            val made = HashMap<String, Kept>()
            for (m in InkMoods.ALL) {
                val songs = InkMoods.songs(m, LastFm.key, week())
                if (songs.isEmpty()) continue
                // its tile's cover: the first of its songs iTunes knows
                val art = songs.take(4).firstNotNullOfOrNull { (t, a) -> Curation.itunes(t, a, false).also { kotlinx.coroutines.delay(400) } }
                made[m.key] = Kept(songs, art)
                kept.value = kept.value + (m.key to made.getValue(m.key))
            }
            if (made.isNotEmpty()) runCatching { Home.write(file, write(made).toString()) }
        }
    }

    private fun read(o: JSONObject): Map<String, Kept> = InkMoods.ALL.mapNotNull { m ->
        val k = o.optJSONObject(m.key) ?: return@mapNotNull null
        val a = k.optJSONArray("songs") ?: return@mapNotNull null
        m.key to Kept((0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }, k.optString("art").ifEmpty { null })
    }.toMap()

    private fun write(all: Map<String, Kept>) = JSONObject().put("week", week()).apply {
        all.forEach { (key, k) -> put(key, JSONObject().put("art", k.art ?: "").put("songs", JSONArray(k.songs.map { JSONArray(listOf(it.first, it.second)) }))) }
    }

    /** The mood as a station: its songs in a new order each time, the first few found at once, the rest after. */
    suspend fun play(m: InkMoods.Mood): Boolean {
        val songs = kept.value[m.key]?.songs?.shuffled() ?: withContext(Dispatchers.IO) { InkMoods.songs(m, LastFm.key, week()) }.shuffled()
        if (songs.isEmpty()) return false
        val first = find(songs.take(8))
        if (first.isEmpty()) return false
        if (!Player.radio.value) Player.radio(true)
        Player.play(first)
        scope.launch {
            songs.drop(8).take(40).chunked(8).forEach { group -> find(group).takeIf { it.isNotEmpty() }?.let { more -> withContext(Dispatchers.Main) { Player.follow(Player.queue.value.lastOrNull() ?: return@withContext, more) } } }
        }
        return true
    }

    private suspend fun find(group: List<Pair<String, String>>): List<Song> = withContext(Dispatchers.IO) {
        coroutineScope { group.map { (t, a) -> async { YouTube.find(t, a) } }.awaitAll().filterNotNull() }
    }
}

/** Right now: three of ink's moods for the hour and the day, big, each a station; then all of them as tiles. */
@Composable
fun InkMoodsShelf() {
    LaunchedEffect(Unit) { Moodboard.start() }
    val kept by Moodboard.kept.collectAsState()
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    fun go(m: InkMoods.Mood) = scope.launch { said = "${m.name}: finding its songs"; said = if (Moodboard.play(m)) null else "${m.name}: couldn't find its songs" }
    // one row of tiles (Bronson, 2026-10-29: the three as words and the rest as tiles didn't belong together): right
    // now's three bigger, then the rest
    Column {
        Section("RIGHT NOW", said ?: "moods for ${Moods.said()}, then the rest")
        val now = remember { InkMoods.now() }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
            items(now, key = { "now" + it.key }) { m -> MoodTile(m, kept[m.key]?.art, 250.dp) { go(m) } }
            items(InkMoods.ALL.filter { it !in now }, key = { it.key }) { m -> MoodTile(m, kept[m.key]?.art) { go(m) } }
        }
    }
}

/** A mood as a tile: a cover of its songs in the dither, its name big on it, its line under it. */
@Composable
fun MoodTile(m: InkMoods.Mood, art: String?, side: androidx.compose.ui.unit.Dp = 170.dp, onClick: () -> Unit) {
    Column(Modifier.width(side).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.BottomStart) {
            if (art != null) Dithered(remember(art) { faceSong("mood " + m.key, art, photo = true) }, Modifier.fillMaxSize(), px = 340)
            // its name on a black tab in the corner (a sleeve's label: pale dithers swallowed it)
            BasicText(m.name.uppercase(), style = headline(if (side > 200.dp) 30.sp else 22.sp), maxLines = 2,
                modifier = Modifier.background(Ink.black).padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        BasicText(m.line, style = pixel(12.sp, Ink.mid), maxLines = 2)
    }
}

/** ink's festivals here: the file read (it comes with the app), and a festival's station (each artist's best known song in turn). */
object Fests {
    val all: List<Festivals.Festival> by lazy {
        Festivals.read(runCatching { Fests::class.java.getResourceAsStream("/ink-festivals.json")!!.bufferedReader().use { it.readText() } }.getOrDefault("{}"))
    }

    suspend fun play(f: Festivals.Festival): Boolean {
        val songs = withContext(Dispatchers.IO) {
            coroutineScope {
                f.artists.take(16).map { name ->
                    async { kotlinx.coroutines.withTimeoutOrNull(10_000) { Artists.of(name, "").firstOrNull { it != null } }?.top?.filter { it.tube }?.take(2).orEmpty() }
                }.awaitAll()
            }.let { each -> (0 until 2).flatMap { i -> each.mapNotNull { it.getOrNull(i) } } }
        }
        if (songs.isEmpty()) return false
        if (!Player.radio.value) Player.radio(true)
        Player.play(songs)
        return true
    }
}

/**
 * Festivals soon: one row of posters, each a festival on now or within two months, set as a festival's poster is (its
 * name, when and where, the lineup as type, the first names biggest); a click opens the festival (Bronson, 2026-10-28:
 * a row of faces each took too much of the page).
 */
@Composable
fun FestivalsShelf(open: (Page) -> Unit) {
    val soon = remember { Festivals.soon(Fests.all) }
    if (soon.isEmpty()) return
    Column {
        Section("FESTIVALS SOON", "who's playing, to know before you go")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(soon, key = { it.name }) { f -> FestivalPoster(f, Modifier.width(250.dp)) { open(Page.Festival(f.name)) } }
        }
    }
}

/** A festival as its poster: when and where small, its name big, the lineup in type that shrinks down the bill, its dates at the foot. */
@Composable
fun FestivalPoster(f: Festivals.Festival, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.aspectRatio(.8f).border(1.dp, Ink.line).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(16.dp)) {
        BasicText("${f.when_()} · ${f.city}", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        BasicText(f.name.uppercase(), style = headline(26.sp).copy(lineHeight = 28.sp), maxLines = 2)
        Spacer(Modifier.height(14.dp))
        // the bill: the first two big, the next four smaller, the rest counted
        f.artists.take(2).forEach { BasicText(it.uppercase(), style = headline(17.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        Spacer(Modifier.height(4.dp))
        f.artists.drop(2).take(4).forEach { BasicText(it.uppercase(), style = headline(12.sp, Ink.mid), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        if (f.artists.size > 6) BasicText("and ${f.artists.size - 6} more", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.weight(1f))
        BasicText(dates(f), style = pixel(12.sp, Ink.mid))
    }
}

/** "nov 2 – 8", "dec 11 – 12", "nov 30 – dec 2". */
fun dates(f: Festivals.Festival): String {
    val m = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
    return (if (f.start.month == f.end.month) "${m.format(f.start)} – ${f.end.dayOfMonth}" else "${m.format(f.start)} – ${m.format(f.end)}").lowercase()
}

/** A festival's page: its name and when, its whole lineup as faces in a grid, a station of them, the lineup's source. */
@Composable
fun FestivalPage(name: String, open: (Page) -> Unit, onClose: () -> Unit) {
    val f = remember(name) { Fests.all.firstOrNull { it.name == name } } ?: return Close(onClose)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp), Modifier.fillMaxSize().feather(grid), grid,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = SIDE, end = SIDE, top = 10.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    BasicText(f.name.uppercase(), style = headline(64.sp))
                    BasicText("${dates(f)} · ${f.city} · ${f.when_()} · ${f.artists.size} artists", style = pixel(13.sp, Ink.mid))
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        Word("station", pixel(14.sp), touch = 8.dp) { scope.launch { said = "finding their songs"; said = if (Fests.play(f)) null else "couldn't find their songs" } }
                        if (f.link.isNotBlank()) Word("the lineup, announced", pixel(14.sp, Ink.mid), touch = 8.dp) { browse(f.link) }
                    }
                    said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            items(f.artists.size, key = { f.artists[it] }) { i -> LineupTile(f.artists[i], open) }
        }
    }
}

/** An artist on a lineup: their photo in the dither (read from YouTube Music, kept), their name; a tap opens their page. */
@Composable
private fun LineupTile(name: String, open: (Page) -> Unit) {
    val info by androidx.compose.runtime.produceState<ArtistInfo?>(null, name) { Artists.of(name, "").collect { if (it != null) value = it } }
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(Page.Artist(info?.name ?: name, info?.id ?: "")) })) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            info?.photo?.let { Dithered(remember(it) { faceSong(name, it, photo = true) }, Modifier.fillMaxSize(), px = 260) } ?: NameCover(name, 150.dp)
        }
        Spacer(Modifier.height(6.dp))
        BasicText(name.uppercase(), style = headline(13.sp), maxLines = 2)
    }
}
