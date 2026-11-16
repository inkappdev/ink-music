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
data class Line(val at: Long, val words: String, val parts: List<Pair<Long, String>> = emptyList(), val translation: String = "", val roman: String = "")

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

    /**
     * What's under each sung line (2026-11-16, after Mornye's lyrics): nothing, its English, or how it sounds in Latin
     * letters, where Better Lyrics has them (a song in another language: Japanese, Korean, Spanish...).
     */
    val under = MutableStateFlow(UNDER_NOTHING)
    const val UNDER_NOTHING = "nothing"; const val UNDER_ENGLISH = "english"; const val UNDER_SOUND = "how it sounds"
    fun under(c: Context, what: String) { under.value = what; c.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit().putString("under", what).apply() }
    /** The words under a line, as chosen ("" for none). */
    fun underOf(l: Line): String = when (under.value) { UNDER_ENGLISH -> l.translation; UNDER_SOUND -> l.roman; else -> "" }.let { if (it.equals(l.words, true)) "" else it }

    private lateinit var dir: File
    private lateinit var ctx: Context
    private val retry = mutableMapOf<String, Long>()   // key -> when lrclib may be asked again

    fun start(context: Context) {
        ctx = context.applicationContext
        dir = File(ctx.filesDir, "lyrics")
        _on.value = prefs().getBoolean("on", true)
        under.value = prefs().getString("under", UNDER_NOTHING) ?: UNDER_NOTHING
    }

    private fun prefs() = ctx.getSharedPreferences("lyrics", Context.MODE_PRIVATE)

    fun toggle() {
        _on.value = !_on.value
        prefs().edit().putBoolean("on", _on.value).apply()
    }

    /** The song's lines (synced, or else untimed: at -1); empty when there are none; null when lrclib couldn't be reached. */
    suspend fun of(t: Now): List<Line>? = withContext(Dispatchers.IO) {
        val artist = t.artist.split(',').first().trim()
        val key = "$artist|${t.title}"
        val base = File(dir, sha1(key))
        val lrc = File("${base.path}.lrc")
        val asked = File("${base.path}.yt")   // YouTube Music asked too (when lrclib had none)
        // Better Lyrics asked (word by word, when it has them); ".bl2" since it's read for each line's English and sound
        // too, so a song asked before is asked once more for them
        val better = File("${base.path}.bl2")
        val underFile = File("${base.path}.under")   // each line's English and sound, by when it's sung
        // word by word first, where Better Lyrics has the song (asked once a song, words kept before it too)
        // its length, which Better Lyrics finds a song by: the song's own as listed first (the album's: a stream can be
        // its video's, 10 s off: Spring Day's 265 s for the record's 275, and none came, 2026-11-16), then the player's.
        // Neither known yet (a stream opening): asked later, not kept as asked: none came without it, and that had been
        // kept as asked, every song heard as it began losing its word-by-word lyrics for good
        val lengths = listOfNotNull(t.song?.duration, t.duration).map { it / 1000 }.filter { it > 0 }.distinct()
        val seconds = lengths.firstOrNull() ?: 0L
        if (!better.exists() && seconds > 0) {
            var timed: List<BetterLyrics.Timed>? = null
            for (s in lengths) {
                timed = BetterLyrics.of(t.title, artist, s, AGENT)
                android.util.Log.d("ink-lyrics", "better lyrics for $artist - ${t.title} (${s}s): ${timed?.size ?: "unreachable"}")
                if (timed == null || timed.isNotEmpty()) break
            }
            if (timed != null) runCatching { dir.mkdirs(); better.writeText("") }
            if (!timed.isNullOrEmpty()) {
                val lines = timed.map { Line(it.at, it.words, it.parts, it.translation, it.roman) }
                runCatching {
                    lrc.writeText(lrcOf(lines))
                    if (lines.any { it.translation.isNotEmpty() || it.roman.isNotEmpty() }) underFile.writeText(org.json.JSONArray(lines.map {
                        org.json.JSONObject().put("at", it.at).put("en", it.translation).put("ro", it.roman) }).toString())
                }
                android.util.Log.d("ink-lyrics", "${t.artist} - ${t.title}: ${lines.size} lines from better lyrics, ${lines.count { it.parts.isNotEmpty() }} word by word")
                return@withContext lines
            }
        }
        if (lrc.exists()) runCatching { lrc.readText() }.getOrNull()?.let { text ->
            // lrclib had none, and YouTube wasn't asked then (kept before 2026-10-27): asked now, once
            if (text.isBlank() && !asked.exists() && YouTubeMusic.available) tube(t, lrc, asked)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            return@withContext withUnder(parse(text), underFile).ifEmpty { plain(File("${base.path}.txt")) }
        }
        if ((retry[key] ?: 0L) > SystemClock.elapsedRealtime()) return@withContext null
        val got = ask(artist, t.title, t.album, t.duration / 1000)
        if (got == null) {
            retry[key] = SystemClock.elapsedRealtime() + 60_000
            // lrclib down (503s, 2026-11-01): YouTube Music's meanwhile, kept if it has some; else lrclib again in a minute
            if (YouTubeMusic.available) tube(t, lrc, asked)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
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
        parse(got.first).ifEmpty { plain(got.second) }
    }

    /** Each line's English and sound put back from where they're kept, by when it's sung. */
    private fun withUnder(lines: List<Line>, f: File): List<Line> {
        if (!f.exists()) return lines
        val by = runCatching { org.json.JSONArray(f.readText()).let { a -> (0 until a.length()).associate { i -> a.getJSONObject(i).let { it.getLong("at") to (it.optString("en") to it.optString("ro")) } } } }.getOrNull() ?: return lines
        // within 10 ms: the lrc they're kept beside holds a line's time to the hundredth (17.105 s comes back 17.11)
        val times = by.keys.sorted()
        fun near(at: Long) = times.minByOrNull { kotlin.math.abs(it - at) }?.takeIf { kotlin.math.abs(it - at) <= 10 }
        return lines.map { l -> near(l.at)?.let(by::get)?.let { (en, ro) -> l.copy(translation = en, roman = ro) } ?: l }
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
