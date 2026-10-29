package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    Column(Modifier.padding(bottom = 18.dp)) {
        BasicText("RIGHT NOW", style = headline(30.sp))
        BasicText(said ?: "picked for ${Moods.said()}", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(12.dp))
        InkMoods.now().forEach { m ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Word(m.name.uppercase(), headline(40.sp), touch = 4.dp) { go(m) }
                    BasicText(m.line, style = pixel(12.sp, Ink.mid))
                }
                Word("start station", pixel(13.sp), Modifier.padding(bottom = 8.dp), touch = 8.dp) { go(m) }
            }
        }
        Spacer(Modifier.height(22.dp))
        Label("ink's moods, from what listeners tag their songs")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(InkMoods.ALL, key = { it.key }) { m -> MoodTile(m, kept[m.key]?.art) { go(m) } }
        }
    }
}

/** A mood as a tile: a cover of its songs in the dither, its name big on it, its line under it. */
@Composable
fun MoodTile(m: InkMoods.Mood, art: String?, onClick: () -> Unit) {
    Column(Modifier.width(170.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.BottomStart) {
            if (art != null) Dithered(remember(art) { faceSong("mood " + m.key, art, photo = true) }, Modifier.fillMaxSize(), px = 340)
            // its name on a black tab in the corner (a sleeve's label: pale dithers swallowed it)
            BasicText(m.name.uppercase(), style = headline(22.sp), maxLines = 2,
                modifier = Modifier.background(Ink.black).padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        BasicText(m.line, style = pixel(12.sp, Ink.mid), maxLines = 2)
    }
}
