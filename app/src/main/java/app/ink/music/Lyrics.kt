package app.ink.music

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** lrclib asks every app to say who it is. */
const val AGENT = "ink music/0.1 (+https://inkhome.app; hello@inkhome.app)"

/** One sung line: when it starts (ms into the song) and its words. */
data class Line(val at: Long, val words: String)

/**
 * Synced lyrics from lrclib, as ink has them (copied from ink's Lyrics.kt): synced lines only, a song lrclib has
 * only plain (untimed) words for shows none. Answers are cached on disk; lrclib being unreachable
 * is not an answer, so that song is asked again after a minute.
 */
object Lyrics {
    const val LEAD = 250L   // show a line this much early: reading lags hearing

    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on

    private lateinit var dir: File
    private lateinit var ctx: Context
    private val retry = mutableMapOf<String, Long>()   // key -> when lrclib may be asked again

    fun start(context: Context) {
        ctx = context.applicationContext
        dir = File(ctx.filesDir, "lyrics")
        _on.value = prefs().getBoolean("on", true)
    }

    private fun prefs() = ctx.getSharedPreferences("lyrics", Context.MODE_PRIVATE)

    fun toggle() {
        _on.value = !_on.value
        prefs().edit().putBoolean("on", _on.value).apply()
    }

    /** The song's synced lines; empty when there are none; null when lrclib couldn't be reached. */
    suspend fun of(t: Now): List<Line>? = withContext(Dispatchers.IO) {
        val artist = t.artist.split(',').first().trim()
        val key = "$artist|${t.title}"
        val base = File(dir, sha1(key))
        val lrc = File("${base.path}.lrc")
        if (lrc.exists()) runCatching { lrc.readText() }.getOrNull()?.let { return@withContext parse(it) }
        if ((retry[key] ?: 0L) > SystemClock.elapsedRealtime()) return@withContext null
        val got = ask(artist, t.title, t.album, t.duration / 1000)
        if (got == null) {
            retry[key] = SystemClock.elapsedRealtime() + 60_000
            return@withContext null
        }
        // kept, so lrclib isn't asked again (a full disk only means asking again)
        runCatching {
            dir.mkdirs()
            lrc.writeText(got.first)
            File("${base.path}.txt").writeText(got.second)
        }
        parse(got.first)
    }

    /** (synced, plain) from lrclib: "" for none. null: lrclib never answered. */
    private fun ask(artist: String, title: String, album: String, seconds: Long): Pair<String, String>? {
        fun q(vararg kv: Pair<String, Any>) =
            kv.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), "UTF-8") }
        val urls = listOf(
            "https://lrclib.net/api/get?" + q(
                "artist_name" to artist, "track_name" to title, "album_name" to album, "duration" to seconds,
            ),
            "https://lrclib.net/api/search?" + q("artist_name" to artist, "track_name" to title),
        )
        var answered = false
        var words = ""
        for (url in urls) {
            val body = try {
                get(url)
            } catch (e: FileNotFoundException) {   // 404: "no such track" is an answer
                answered = true; continue
            } catch (e: Exception) {
                continue
            }
            // not json (a wifi's sign-in page): no answer
            val hits = try {
                body.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONArray().put(JSONObject(it)) }
            } catch (e: Exception) { continue }
            answered = true
            for (i in 0 until hits.length()) {
                val hit = hits.optJSONObject(i) ?: continue
                val synced = hit.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                if (synced != null) return synced to ""
                if (hit.optBoolean("instrumental")) return "" to ""
                if (words.isEmpty()) words = hit.optString("plainLyrics").takeIf { it != "null" }.orEmpty()
            }
        }
        return if (answered) "" to words else null
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", AGENT)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

    private fun parse(text: String): List<Line> = text.lineSequence().flatMap { raw ->
        val words = stamp.replace(raw, "").trim()
        stamp.findAll(raw).mapNotNull { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            Line((min * 60_000 + sec * 1000).toLong(), words)
        }
    }.sortedBy { it.at }.toList()

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
