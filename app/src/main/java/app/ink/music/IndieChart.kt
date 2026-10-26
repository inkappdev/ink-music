package app.ink.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * The week on indie radio: the songs played most this past week on KEXP (Seattle), KCRW (Santa
 * Monica) and WXPN (Philadelphia), counted from what each station says it played (KEXP's public
 * API; KCRW's and WXPN's own playlist feeds). Ranked by how many of the stations played a song, then
 * by how often: real DJs' picks, new each week, no taste needed. Counted at most once a day and kept;
 * each song played from the library if it's there, else found on YouTube Music.
 */
object IndieChart {
    data class Entry(val artist: String, val title: String, val stations: List<String>, val plays: Int, val song: Song?)

    private const val SIZE = 25
    private const val DAY = 24 * 3_600_000L

    private fun file(c: Context) = File(c.filesDir, "indie-chart.json")

    /** This week's chart: kept if counted in the last day, else counted anew (blocking network; call off the main thread). */
    suspend fun week(c: Context): List<Entry> = withContext(Dispatchers.IO) {
        kept(c)?.let { (at, entries) -> if (System.currentTimeMillis() - at < DAY) return@withContext entries }
        val counted = runCatching { count() }.getOrDefault(emptyList())
        if (counted.isEmpty()) return@withContext kept(c)?.second.orEmpty()
        val played = resolve(counted.take(SIZE))
        keep(c, played)
        played
    }

    private class Tally(val artist: String, val title: String) { val stations = LinkedHashSet<String>(); var plays = 0 }

    /** Every song the three stations played since a week ago, counted (blocking). */
    private fun count(): List<Entry> {
        val since = LocalDate.now().minusDays(7)
        val all = HashMap<String, Tally>()
        fun add(station: String, artist: String?, title: String?) {
            if (artist.isNullOrBlank() || title.isNullOrBlank() || artist.startsWith("[")) return
            val t = all.getOrPut(Radio.key(title, artist.substringBefore(" feat").substringBefore(" & "))) { Tally(artist.trim(), title.trim()) }
            t.stations += station; t.plays++
        }
        // KEXP: the week, a page at a time
        var next: String? = "https://api.kexp.org/v2/plays/?limit=500&airdate_after=${since}T00:00:00"
        var pages = 0
        while (next != null && pages++ < 20) {
            val d = runCatching { JSONObject(get(next)) }.getOrNull() ?: break
            val r = d.optJSONArray("results") ?: break
            for (i in 0 until r.length()) r.optJSONObject(i)?.takeIf { it.optString("play_type") == "trackplay" }
                ?.let { add("kexp", it.optString("artist"), it.optString("song")) }
            next = d.optString("next").takeIf { it.startsWith("http") }
        }
        // WXPN: a day's playlist each
        for (day in 0L until 7L) runCatching {
            val a = JSONArray(get("https://origin.xpn.org/utils/playlist/json/${since.plusDays(day)}.json"))
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { add("wxpn", it.optString("artist"), it.optString("song")) }
        }
        // KCRW: newest first, a page at a time, till the week is passed
        for (page in 1..40) {
            val a = runCatching { JSONArray(get("https://tracklist-api.kcrw.com/Music/all/$page")) }.getOrNull() ?: break
            if (a.length() == 0) break
            var older = false
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                if (it.optString("date").let { d -> d.isNotEmpty() && d < since.toString() }) older = true
                add("kcrw", it.optString("artist"), it.optString("title"))
            }
            if (older) break
        }
        return all.values.sortedWith(compareByDescending<Tally> { it.stations.size }.thenByDescending { it.plays })
            .map { Entry(it.artist, it.title, it.stations.toList(), it.plays, null) }
    }

    /** Each song as one that plays: the library's own if it's there, else YouTube Music's, five asked at a time. */
    private suspend fun resolve(entries: List<Entry>): List<Entry> = coroutineScope {
        val mine = Library.songs.value.associateBy { Radio.key(it.title, it.artist) }
        entries.chunked(5).flatMap { batch ->
            batch.map { e ->
                async(Dispatchers.IO) {
                    val song = mine[Radio.key(e.title, e.artist)] ?: YouTubeMusic.find(e.title, e.artist)
                    e.copy(song = song)
                }
            }.awaitAll()
        }.filter { it.song != null }
    }

    internal fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "ink music/" + BuildConfig.VERSION_NAME)
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun keep(c: Context, entries: List<Entry>) = runCatching {
        file(c).writeText(JSONObject().put("at", System.currentTimeMillis()).put("entries", JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().put("artist", e.artist).put("title", e.title).put("stations", JSONArray(e.stations)).put("plays", e.plays)
                    .apply { e.song?.let { put("song", it.json()) } })
            }
        }).toString())
    }

    private fun kept(c: Context): Pair<Long, List<Entry>>? = runCatching {
        val o = JSONObject(file(c).readText())
        val a = o.getJSONArray("entries")
        o.getLong("at") to (0 until a.length()).map { a.getJSONObject(it) }.map { e ->
            val song = e.optJSONObject("song")?.let(::songOf)?.also { if (it.tube) YouTubeMusic.remember(it) }
            Entry(e.getString("artist"), e.getString("title"), (0 until e.getJSONArray("stations").length()).map { e.getJSONArray("stations").getString(it) },
                e.getInt("plays"), song)
        }
    }.getOrNull()
}
