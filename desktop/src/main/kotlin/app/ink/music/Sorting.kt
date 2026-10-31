package app.ink.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
 * shelf as a spectrum. All of it worked out here from your own listening ([History]); nothing asked of
 * anyone. (2026-10-28, in place of settings' a to z or shuffled.)
 */
enum class Sort(val word: String, val turns: Boolean = true) {
    AZ("a-z"), ARTIST("artist"), YEAR("year"), ADDED("added"), HOUR("this hour"), LATELY("lately"), MOST("most"),
    FORGOTTEN("forgotten"), COLOUR("color"), SHUFFLE("shuffle", turns = false),
}

object Sorts {
    val RECORDS = listOf(Sort.AZ, Sort.ARTIST, Sort.YEAR, Sort.ADDED, Sort.LATELY, Sort.COLOUR, Sort.SHUFFLE)
    val ARTISTS = listOf(Sort.AZ, Sort.MOST, Sort.LATELY, Sort.FORGOTTEN, Sort.SHUFFLE)
    val SONGS = listOf(Sort.AZ, Sort.HOUR, Sort.LATELY, Sort.MOST, Sort.FORGOTTEN, Sort.SHUFFLE)

    data class Chosen(val sort: Sort = Sort.AZ, val turned: Boolean = false)

    private val _chosen = MutableStateFlow(mapOf<String, Chosen>())
    val chosen: StateFlow<Map<String, Chosen>> = _chosen
    private val file = File(Home.dir, "sorts.json")

    fun start() {
        val o = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        _chosen.value = listOf("albums", "artists", "songs").associateWith { tab ->
            Chosen(o.optString("sort-$tab").let { n -> Sort.entries.firstOrNull { it.name == n } } ?: Sort.AZ, o.optBoolean("sort-$tab-turned"))
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
        _chosen.value = _chosen.value + (tab to next)
        val o = JSONObject()
        _chosen.value.forEach { (t, c) -> o.put("sort-$t", c.sort.name).put("sort-$t-turned", c.turned) }
        runCatching { Home.write(file, o.toString()) }
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

/** Your listening, read as ink music opens and after each listen (a year's listens read in a moment). */
object Listened {
    private val _now = MutableStateFlow(Listening())
    val now: StateFlow<Listening> = _now
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun read() { scope.launch { val all = History.read(); _now.value = Listening.of(all); EggsNow.take(all) } }
}

/** Shuffle's order for this run: each song, record or artist by its own key's place (the phone's Order). */
object Order {
    private val _seed = MutableStateFlow(java.util.Random().nextLong())
    val seed: StateFlow<Long> = _seed

    /** Shuffle chosen again: a new order. */
    fun reshuffle() { _seed.value = java.util.Random().nextLong() }

    fun <T> of(list: List<T>, seed: Long, key: (T) -> String): List<T> = list.sortedBy { item ->
        var z = key(item).hashCode().toLong() xor seed
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z xor (z ushr 31)
    }
}

/**
 * Each record's cover colour (hue, saturation, brightness), for the color order: worked out once from a small copy
 * of its cover, in the background, and kept (colours.json). The vivid colour the player uses on its line
 * ([Covers.accentOf]); a cover with none (black and white, grey) goes after the colours, light to dark.
 */
object Colours {
    private val _known = MutableStateFlow(mapOf<String, FloatArray>())
    val known: StateFlow<Map<String, FloatArray>> = _known
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var filling = false
    private val file = File(Home.dir, "colours.json")

    fun start() {
        scope.launch {
            _known.value = runCatching {
                val o = JSONObject(file.readText())
                o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> FloatArray(3) { a.getDouble(it).toFloat() } } }
            }.getOrDefault(emptyMap())
        }
    }

    /** Records whose colour isn't known yet, worked out one after another (asked for when the color order's chosen). */
    fun fill(albums: List<Album>) {
        if (filling) return
        filling = true
        scope.launch {
            val have = HashMap(_known.value)
            var since = 0
            for (a in albums) {
                if (a.id in have) continue
                val s = a.songs.firstOrNull() ?: continue
                have[a.id] = Covers.accentOf(s)?.let(::hsv) ?: floatArrayOf(0f, 0f, -1f)
                // shown as it comes, kept every so often
                if (++since % 24 == 0) { _known.value = HashMap(have); keep(have) }
            }
            _known.value = HashMap(have); keep(have)
            filling = false
        }
    }

    private fun keep(m: Map<String, FloatArray>) = runCatching {
        Home.write(file, JSONObject().apply { m.forEach { (k, v) -> put(k, org.json.JSONArray(v.map { it.toDouble() })) } }.toString())
    }

    /** Where a record sits in the spectrum: reds first round to violets, then the colourless light to dark; null not known. */
    fun place(hsv: FloatArray?): Double? {
        if (hsv == null || hsv[2] < 0f) return null
        val grey = hsv[1] < .18f || hsv[2] < .12f
        // descending order: colours high (360 - hue, so red first), the colourless below them
        return if (grey) hsv[2].toDouble() else 1_000.0 + (360.0 - hsv[0])
    }

    private fun hsv(c: androidx.compose.ui.graphics.Color): FloatArray {
        val max = maxOf(c.red, c.green, c.blue); val min = minOf(c.red, c.green, c.blue); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == c.red -> 60f * (((c.green - c.blue) / d) % 6f)
            max == c.green -> 60f * ((c.blue - c.red) / d + 2f)
            else -> 60f * ((c.red - c.green) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
    }
}

/** The row of orders at a tab's top: lit is the one in use (a pixel arrow beside it says which way; chosen again, it turns). */
@Composable
fun SortRow(tab: String, sorts: List<Sort>, modifier: Modifier = Modifier) {
    val all by Sorts.chosen.collectAsState()
    val c = all[tab] ?: Sorts.Chosen()
    Row(modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        sorts.forEach { s ->
            val on = s == c.sort
            val word = if (s == Sort.AZ && on && c.turned) "z-a" else s.word
            Row(verticalAlignment = Alignment.CenterVertically) {
                Word(word, pixel(13.sp, if (on) Ink.ink else Ink.dim), touch = 8.dp) { Sorts.tap(tab, s) }
                if (on && s.turns && s != Sort.AZ) Sprite(if (c.turned) UP else DOWN, Ink.ink, 2.dp, Modifier.padding(start = 2.dp))
            }
        }
    }
}

private val DOWN = listOf("#####", ".###.", "..#..")
private val UP = listOf("..#..", ".###.", "#####")
