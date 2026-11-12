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

/**
 * One sung line: when it starts (ms into the song; -1 for words with no times) and its words; [parts], where it's
 * timed word by word (Better Lyrics), each word with when it starts (its space kept in its text).
 */
data class Line(val at: Long, val words: String, val parts: List<Pair<Long, String>> = emptyList())

/**
 * Synced lyrics from lrclib, as ink has them (copied from ink's Lyrics.kt); a song lrclib has only plain (untimed)
 * words for, those, read as they are (none shown till 2026-11-01: the words were there). Where lrclib has none, YouTube Music's (the full build, ink music's
 * own: timed by Musixmatch for song uploads). Answers are cached on disk; lrclib being unreachable
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

    /** The selected lyrics source; "auto" preserves the original best-available behaviour. */
    fun selectedSource(): String = prefs().getString("source", "auto") ?: "auto"

    fun selectSource(id: String) {
        if (LyricsSources.sources.none { it.id == id }) return
        prefs().edit().putString("source", id).apply()
        retry.clear()
    }

    fun spicyKey(): String = prefs().getString("spicy_key", "").orEmpty()

    fun setSpicyKey(key: String) {
        prefs().edit().putString("spicy_key", key.trim()).apply()
        retry.clear()
    }

    private fun cachedSource(t: Now, base: File, id: String): List<Line>? {
        val file = File("${base.path}.$id.lrc")
        if (file.exists()) return runCatching { parse(file.readText()).ifEmpty { plain(file) } }.getOrNull()
        val artist = t.artist.split(',').first().trim()
        val lines = when (id) {
            "better" -> BetterLyrics.of(t.title, artist, t.duration / 1000, AGENT)?.map { Line(it.at, it.words, it.parts) }
            "youtube" -> YouTubeMusic.lyrics(t.song?.id.orEmpty(), t.title, artist)
            else -> null
        }
        return lines?.takeIf { it.isNotEmpty() }?.also { result ->
            runCatching { dir.mkdirs(); file.writeText(lrcOf(result)) }
        }
    }

    private suspend fun selected(t: Now, base: File, id: String): List<Line>? {
        if (id == "better" || id == "youtube") return cachedSource(t, base, id)
        if (id == "better_qq") {
            cachedSource(t, base, id)?.let { return it }
            val artist = t.artist.split(',').first().trim()
            val lines = LyricsSources.fetch(id, t.title, artist, t.album, t.duration / 1000, spicyKey())
            if (lines != null) runCatching { dir.mkdirs(); File("${base.path}.$id.lrc").writeText(lrcOf(lines)) }
            return lines
        }
        cachedSource(t, base, id)?.let { return it }
        val artist = t.artist.split(',').first().trim()
        val lines = if (id == "lrclib") {
            val result = ask(artist, t.title, t.album, t.duration / 1000) ?: return null
            parse(result.first).ifEmpty { plain(result.second) }
        } else LyricsSources.fetch(id, t.title, artist, t.album, t.duration / 1000)
        if (lines != null) runCatching {
            dir.mkdirs()
            File("${base.path}.$id.lrc").writeText(lrcOf(lines))
        }
        return lines
    }

    private suspend fun additional(t: Now, base: File, ids: List<String>): List<Line>? =
        kotlinx.coroutines.coroutineScope {
            // Start every provider together, but inspect answers in the configured priority order.
            // This avoids making a slow catalogue hold up every source behind it.
            val jobs = ids.map { id ->
                kotlinx.coroutines.async {
                    val file = File("${base.path}.$id.lrc")
                    if (file.exists()) {
                        id to runCatching { parse(file.readText()).ifEmpty { plain(file) } }.getOrNull()
                    } else {
                        val artist = t.artist.split(',').first().trim()
                        val lines = LyricsSources.fetch(id, t.title, artist, t.album, t.duration / 1000, spicyKey())
                        if (!lines.isNullOrEmpty()) {
                            runCatching { dir.mkdirs(); file.writeText(lrcOf(lines)) }
                            android.util.Log.d("ink-lyrics", "${t.artist} - ${t.title}: ${lines.size} lines from $id")
                        }
                        id to lines
                    }
                }
            }
            for (job in jobs) {
                val (_, lines) = job.await()
                if (!lines.isNullOrEmpty()) {
                    jobs.filter { it !== job }.forEach { it.cancel() }
                    return@coroutineScope lines
                }
            }
            null
        }

    /** The song's lines (synced, or else untimed: at -1); empty when there are none; null when lrclib couldn't be reached. */
    suspend fun of(t: Now): List<Line>? = withContext(Dispatchers.IO) {
        val artist = t.artist.split(',').first().trim()
        val key = "$artist|${t.title}"
        val base = File(dir, sha1(key))
        val chosen = selectedSource()
        if (chosen != "auto") return@withContext selected(t, base, chosen)
        val lrc = File("${base.path}.lrc")
        val asked = File("${base.path}.yt")   // YouTube Music asked too (when lrclib had none)
        val better = File("${base.path}.bl")  // Better Lyrics asked (word by word, when it has them)
        // Follow Lokal-style priority: recording-aware and syllable-timed catalogues first.
        additional(t, base, listOf("bini", "spicy", "better_qq", "lyricsplus", "unison"))?.let { return@withContext it }
        // word by word first, where Better Lyrics has the song (asked once a song, words kept before it too)
        if (!better.exists()) {
            val timed = BetterLyrics.of(t.title, artist, t.duration / 1000, AGENT)
            if (timed != null) runCatching { dir.mkdirs(); better.writeText("") }
            if (!timed.isNullOrEmpty()) {
                val lines = timed.map { Line(it.at, it.words, it.parts) }
                runCatching { lrc.writeText(lrcOf(lines)) }
                android.util.Log.d("ink-lyrics", "${t.artist} - ${t.title}: ${lines.size} lines from better lyrics, ${lines.count { it.parts.isNotEmpty() }} word by word")
                return@withContext lines
            }
        }
        if (lrc.exists()) runCatching { lrc.readText() }.getOrNull()?.let { text ->
            // lrclib had none, and YouTube wasn't asked then (kept before 2026-10-27): asked now, once
            if (text.isBlank() && !asked.exists() && YouTubeMusic.available) tube(t, lrc, asked)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            val existing = parse(text).ifEmpty { plain(File("${base.path}.txt")) }
            if (existing.isNotEmpty()) return@withContext existing
            additional(t, base, listOf("kugou", "ovh"))?.let { return@withContext it }
            return@withContext existing
        }
        if ((retry[key] ?: 0L) > SystemClock.elapsedRealtime()) return@withContext null
        val got = ask(artist, t.title, t.album, t.duration / 1000)
        if (got == null) {
            retry[key] = SystemClock.elapsedRealtime() + 60_000
            // lrclib down (503s, 2026-11-01): YouTube Music's meanwhile, kept if it has some; else lrclib again in a minute
            if (YouTubeMusic.available) tube(t, lrc, asked)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            additional(t, base, listOf("kugou", "ovh"))?.let { return@withContext it }
            return@withContext null
        }
        // kept, so lrclib isn't asked again (a full disk only means asking again)
        runCatching {
            dir.mkdirs()
            lrc.writeText(got.first)
            File("${base.path}.txt").writeText(got.second)
        }
        // none synced on lrclib: YouTube Music's (the full build), timed by Musixmatch
        if (got.first.isBlank() && YouTubeMusic.available) tube(t, lrc, asked)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val primary = parse(got.first).ifEmpty { plain(got.second) }
        if (primary.isNotEmpty()) primary else additional(t, base, listOf("kugou", "ovh")) ?: primary
    }

    /** Untimed words as lines (at -1), a verse's gap kept as a blank one. */
    private fun plain(text: String): List<Line> =
        text.lines().map { Line(-1, it.trim()) }.dropWhile { it.words.isEmpty() }.dropLastWhile { it.words.isEmpty() }
    private fun plain(f: File): List<Line> = runCatching { plain(f.readText()) }.getOrDefault(emptyList())

    /** YouTube Music's timed lines for the song, kept as lrclib's are (null: YouTube couldn't be asked, so again next time). */
    private fun tube(t: Now, lrc: File, asked: File): List<Line>? {
        val lines = YouTubeMusic.lyrics(t.song?.id.orEmpty(), t.title, t.artist.split(',').first().trim()) ?: return null
        runCatching {
            dir.mkdirs()
            if (lines.isNotEmpty()) lrc.writeText(lines.joinToString("\n") {
                "[%02d:%05.2f]%s".format(java.util.Locale.US, it.at / 60_000, (it.at % 60_000) / 1000.0, it.words)
            })
            asked.writeText("")
        }
        android.util.Log.d("ink-lyrics", "${t.artist} - ${t.title}: ${lines.size} lines from youtube music")
        return lines
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
    // a word's own time in a line (enhanced lrc: <mm:ss.xx>word)
    private val wordStamp = Regex("""<(\d+):(\d+(?:\.\d+)?)>([^<]*)""")

    private fun clock(ms: Long) = "%02d:%05.2f".format(java.util.Locale.US, ms / 60_000, (ms % 60_000) / 1000.0)

    /** Lines kept as lrc, a line timed word by word as enhanced lrc. */
    private fun lrcOf(lines: List<Line>) = lines.joinToString("\n") { l ->
        "[${clock(l.at)}]" + if (l.parts.isEmpty()) l.words else l.parts.joinToString("") { (at, w) -> "<${clock(at)}>$w" }
    }

    private fun parse(text: String): List<Line> = text.lineSequence().flatMap { raw ->
        val rest = stamp.replace(raw, "")
        val parts = wordStamp.findAll(rest).mapNotNull { m ->
            val at = ((m.groupValues[1].toLongOrNull() ?: return@mapNotNull null) * 60_000 + (m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null) * 1000).toLong()
            at to m.groupValues[3]
        }.toList()
        val words = if (parts.isEmpty()) rest.trim() else parts.joinToString("") { it.second }.trim()
        stamp.findAll(raw).mapNotNull { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            Line((min * 60_000 + sec * 1000).toLong(), words, parts)
        }
    }.sortedBy { it.at }.toList()

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
