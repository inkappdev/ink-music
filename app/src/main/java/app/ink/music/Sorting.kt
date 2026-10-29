package app.ink.music

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * The library's orders, each tab its own, chosen in the row of words at its top (lit is the one in use; the lit one
 * tapped again turns it round, or for shuffle shuffles anew). Beside a to z: the year, as a timeline; what you've
 * played lately, and most; the forgotten (artists and songs you played a lot, gone quiet for weeks); this hour (songs
 * by how often you've played them at this time of day, on this kind of day); records by their cover's colour, the
 * shelf as a spectrum. All of it worked out on the phone from your own listening ([History]); nothing asked of
 * anyone. (2026-10-28, in place of settings' a to z or shuffled.)
 */
enum class Sort(val word: String, val turns: Boolean = true) {
    AZ("a-z"), ARTIST("artist"), YEAR("year"), HOUR("this hour"), LATELY("lately"), MOST("most"),
    FORGOTTEN("forgotten"), COLOUR("color"), SHUFFLE("shuffle", turns = false),
}

object Sorts {
    val RECORDS = listOf(Sort.AZ, Sort.ARTIST, Sort.YEAR, Sort.LATELY, Sort.COLOUR, Sort.SHUFFLE)
    val ARTISTS = listOf(Sort.AZ, Sort.MOST, Sort.LATELY, Sort.FORGOTTEN, Sort.SHUFFLE)
    val SONGS = listOf(Sort.AZ, Sort.HOUR, Sort.LATELY, Sort.MOST, Sort.FORGOTTEN, Sort.SHUFFLE)

    data class Chosen(val sort: Sort = Sort.AZ, val turned: Boolean = false)

    private val _chosen = MutableStateFlow(mapOf<String, Chosen>())
    val chosen: StateFlow<Map<String, Chosen>> = _chosen
    private var prefs: android.content.SharedPreferences? = null

    fun start(c: Context) {
        val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE).also { prefs = it }
        // settings' old "shuffled" carried over: shuffle on every tab
        val old = if (p.getBoolean("shuffled", false)) Sort.SHUFFLE else Sort.AZ
        _chosen.value = listOf("albums", "artists", "songs").associateWith { tab ->
            Chosen(p.getString("sort-$tab", null)?.let { n -> Sort.entries.firstOrNull { it.name == n } } ?: old, p.getBoolean("sort-$tab-turned", false))
        }
    }

    fun of(tab: String) = _chosen.value[tab] ?: Chosen()

    fun tap(tab: String, s: Sort) {
        val was = of(tab)
        val next = when {
            was.sort != s -> Chosen(s)
            s == Sort.SHUFFLE -> { Order.reshuffle(); was }
            else -> was.copy(turned = !was.turned)
        }
        prefs?.edit()?.putString("sort-$tab", next.sort.name)?.putBoolean("sort-$tab-turned", next.turned)?.apply()
        _chosen.value = _chosen.value + (tab to next)
    }

    /** `list` (a to z already) in `chosen`'s order; `key` its own id for shuffling and the history, `name` for ties. */
    fun <T> order(list: List<T>, chosen: Chosen, seed: Long, key: (T) -> String, compare: Comparator<T>? = null, score: (T) -> Double?): List<T> {
        val out = when (chosen.sort) {
            Sort.AZ -> list
            Sort.ARTIST -> if (compare != null) list.sortedWith(compare) else list
            Sort.SHUFFLE -> Order.of(list, seed, key)
            // highest first; what has no score (never played, no year, no colour yet) after, a to z, never turned
            else -> {
                val (scored, rest) = list.map { it to score(it) }.partition { it.second != null }
                val s = scored.sortedByDescending { it.second }.map { it.first }
                return (if (chosen.turned) s.reversed() else s) + rest.map { it.first }
            }
        }
        return if (chosen.turned) out.reversed() else out
    }
}

/**
 * Your listening as the orders read it: for each song (its id), record (album id) and artist (name, lower case), how
 * many listens and the last; for songs, the minute of the week each listen began.
 */
class Listening(
    val plays: Map<String, Int> = emptyMap(),
    val last: Map<String, Long> = emptyMap(),
    val when_: Map<String, IntArray> = emptyMap(),
) {
    fun lately(k: String): Double? = last[k]?.toDouble()
    fun most(k: String): Double? = plays[k]?.toDouble()

    /** Played three times or more, and not for six weeks: the more it was played, the higher. */
    fun forgotten(k: String, now: Long): Double? {
        val n = plays[k] ?: return null
        val l = last[k] ?: return null
        return if (n >= 3 && now - l > 42 * DAY) n + (now - l) / (365.0 * DAY) else null
    }

    /**
     * How much a song belongs to now: each listen counts by how near its hour was to this one (the same hour 1, an hour
     * off .6, two .25) and more when it was the same kind of day (weekday, weekend).
     */
    fun hour(k: String, nowMinute: Int): Double? {
        val times = when_[k] ?: return null
        val day = nowMinute / (24 * 60); val h = nowMinute % (24 * 60) / 60
        val weekend = day >= 5
        var sum = 0.0
        for (t in times) {
            val d = Math.abs(t % (24 * 60) / 60 - h).let { minOf(it, 24 - it) }
            val w = when (d) { 0 -> 1.0; 1 -> .6; 2 -> .25; else -> 0.0 }
            if (w > 0) sum += w * (if ((t / (24 * 60) >= 5) == weekend) 1.0 else .4)
        }
        return sum.takeIf { it > 0 }
    }

    companion object {
        const val DAY = 86_400_000L

        /** The minute of the week (Monday 0:00 is 0) of a wall-clock time, here. */
        fun minuteOfWeek(at: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = at }
            val day = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
            return day * 24 * 60 + c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }

        /** Read from what's been heard, the library telling each song's record and artist. */
        fun of(heard: List<History.Heard>): Listening {
            val plays = HashMap<String, Int>(); val last = HashMap<String, Long>(); val times = HashMap<String, MutableList<Int>>()
            fun count(k: String, at: Long) { plays[k] = (plays[k] ?: 0) + 1; if (at > (last[k] ?: 0L)) last[k] = at }
            for (h in heard) {
                val s = Library.song(h.id)
                count(h.id, h.at)
                times.getOrPut(h.id) { ArrayList() } += minuteOfWeek(h.at)
                s?.albumId?.let { count("r:$it", h.at) }
                (listOfNotNull(s?.albumArtist) + (s?.artist ?: h.artist).split(", ")).filter { it.isNotBlank() }.distinct()
                    .forEach { count("a:" + it.lowercase(), h.at) }
            }
            return Listening(plays, last, times.mapValues { it.value.toIntArray() })
        }
    }
}

/** Your listening, read again as ink music opens (a list of a year's listens reads in a moment). */
object Listened {
    private val _now = MutableStateFlow(Listening())
    val now: StateFlow<Listening> = _now
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun read(c: Context) { val app = c.applicationContext; scope.launch { val all = History.read(app); _now.value = Listening.of(all); EggsNow.take(all) } }
}

/**
 * Each record's cover colour (hue, saturation, brightness), for the colour order: worked out once from a small copy
 * of its cover, in the background, and kept (files/colours.json). The vivid colour the player uses on its line
 * ([Covers.accentOf]); a cover with none (black and white, grey) goes after the colours, light to dark.
 */
object Colours {
    private val _known = MutableStateFlow(mapOf<String, FloatArray>())
    val known: StateFlow<Map<String, FloatArray>> = _known
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var filling = false

    private fun file(c: Context) = File(c.filesDir, "colours.json")

    fun start(c: Context) {
        if (_known.value.isNotEmpty()) return
        val app = c.applicationContext
        scope.launch {
            _known.value = runCatching {
                val o = JSONObject(file(app).readText())
                o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> FloatArray(3) { a.getDouble(it).toFloat() } } }
            }.getOrDefault(emptyMap())
        }
    }

    /** Records whose colour isn't known yet, worked out one after another (asked for when the colour order's chosen). */
    fun fill(c: Context, albums: List<Album>) {
        if (filling) return
        filling = true
        val app = c.applicationContext
        scope.launch {
            val have = HashMap(_known.value)
            var since = 0
            for (a in albums) {
                if (a.id in have) continue
                val s = a.songs.firstOrNull() ?: continue
                val bmp = runCatching {
                    if (s.server) s.art?.let { Server.cover(it, 64) }
                    else app.contentResolver.loadThumbnail(s.uri, android.util.Size(64, 64), null)
                }.getOrNull()
                val hsv = FloatArray(3)
                if (bmp != null) android.graphics.Color.colorToHSV(Covers.accentOf(bmp).toArgbInt(), hsv) else hsv[2] = -1f
                have[a.id] = hsv
                // shown as it comes, kept every so often
                if (++since % 24 == 0) { _known.value = HashMap(have); keep(app, have) }
            }
            _known.value = HashMap(have); keep(app, have)
            filling = false
        }
    }

    private fun keep(c: Context, m: Map<String, FloatArray>) = runCatching {
        file(c).writeText(JSONObject().apply { m.forEach { (k, v) -> put(k, org.json.JSONArray(v.map { it.toDouble() })) } }.toString())
    }

    /** Where a record sits in the spectrum: reds first round to violets, then the colourless light to dark; null not known. */
    fun place(hsv: FloatArray?): Double? {
        if (hsv == null || hsv[2] < 0f) return null
        val grey = hsv[1] < .18f || hsv[2] < .12f
        // descending order: colours high (360 - hue, so red first), the colourless below them
        return if (grey) hsv[2].toDouble() else 1_000.0 + (360.0 - hsv[0])
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt() = android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
}

/** The row of orders at a tab's top: lit is the one in use (a pixel arrow beside it says which way; tapped, it turns). */
@Composable
fun SortRow(tab: String, sorts: List<Sort>, modifier: Modifier = Modifier, end: @Composable () -> Unit = {}) {
    val all by Sorts.chosen.collectAsState()
    val c = all[tab] ?: Sorts.Chosen()
    // it slides when it's wider than the page (Android's own text size), softened at its end while there's more
    val slid = rememberScrollState()
    val soft = dev.chrisbanes.haze.rememberHazeState()
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
    Row(
        Modifier.fillMaxWidth().hazeSource(soft).horizontalScroll(slid),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        sorts.forEach { s ->
            val on = s == c.sort
            val word = if (s == Sort.AZ && on && c.turned) "z-a" else s.word
            Row(verticalAlignment = Alignment.CenterVertically) {
                Word(word, pixel(13.sp, if (on) Ink.ink else Ink.dim), touch = 8.dp) { Sorts.tap(tab, s) }
                if (on && s.turns && s != Sort.AZ) Sprite(if (c.turned) UP else DOWN, Ink.ink, 2.dp, Modifier.padding(start = 2.dp))
            }
        }
        end()
    }
    if (slid.value < slid.maxValue) androidx.compose.foundation.layout.Box(
        Modifier.matchParentSize().wrapContentWidth(Alignment.End).width(BAND).softEdgeEnd(soft),
    )
    }
}

private val DOWN = listOf("#####", ".###.", "..#..")
private val UP = listOf("..#..", ".###.", "#####")
