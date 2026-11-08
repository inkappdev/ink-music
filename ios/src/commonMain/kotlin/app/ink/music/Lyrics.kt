package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One sung line: when it starts (ms into the song; -1 for words with no times) and its words; [parts], where it's
 * timed word by word (Better Lyrics), each word with when it starts (its space kept in its text).
 */
data class Line(val at: Long, val words: String, val parts: List<Pair<Long, String>> = emptyList())

/**
 * A song's lyrics, as the phone finds them (its Lyrics.kt): Better Lyrics' word by word first, else lrclib's synced
 * lines (or its plain words, untimed), else YouTube Music's (timed by Musixmatch). Answers kept on the disk (lyrics/);
 * lrclib out of reach isn't an answer, so that song is asked again after a minute. On or off, kept.
 */
object Lyrics {
    const val LEAD = 250L   // a line shown this much early: reading lags hearing

    private val _on = MutableStateFlow(Store.get("lyrics") != "off")
    val on: StateFlow<Boolean> = _on
    fun toggle() { _on.value = !_on.value; Store.set("lyrics", if (_on.value) "on" else "off") }

    private val dir get() = File(Here.dir, "lyrics")
    private val retry = HashMap<String, Long>()

    /** The song's lines (synced, else untimed: at -1); empty for none; null when lrclib couldn't be reached. Blocking. */
    fun of(s: Song): List<Line>? {
        val artist = s.artist.split(',').first().trim()
        val key = "$artist|${s.title}"
        val base = File(dir, sha1(key.encodeToByteArray()).hex()).getPath()
        val lrc = File("$base.lrc")
        val asked = File("$base.yt")
        val better = File("$base.bl")
        if (!better.exists()) {
            val timed = BetterLyrics.of(s.title, artist, s.duration / 1000, AGENT)
            if (timed != null) runCatching { dir.mkdirs(); better.writeText("") }
            if (!timed.isNullOrEmpty()) {
                val lines = timed.map { Line(it.at, it.words, it.parts) }
                runCatching { lrc.writeText(lrcOf(lines)) }
                return lines
            }
        }
        if (lrc.exists()) runCatching { lrc.readText() }.getOrNull()?.let { text ->
            if (text.isBlank() && !asked.exists()) tube(s, lrc, asked)?.takeIf { it.isNotEmpty() }?.let { return it }
            return parse(text).ifEmpty { plain(File("$base.txt")) }
        }
        if ((retry[key] ?: 0L) > nowMs()) return null
        val got = ask(artist, s.title, s.album, s.duration / 1000)
        if (got == null) {
            retry[key] = nowMs() + 60_000
            tube(s, lrc, asked)?.takeIf { it.isNotEmpty() }?.let { return it }
            return null
        }
        runCatching { dir.mkdirs(); lrc.writeText(got.first); File("$base.txt").writeText(got.second) }
        if (got.first.isBlank()) tube(s, lrc, asked)?.takeIf { it.isNotEmpty() }?.let { return it }
        return parse(got.first).ifEmpty { plain(got.second) }
    }

    private fun plain(text: String): List<Line> =
        text.lines().map { Line(-1, it.trim()) }.dropWhile { it.words.isEmpty() }.dropLastWhile { it.words.isEmpty() }
    private fun plain(f: File): List<Line> = runCatching { plain(f.readText()) }.getOrDefault(emptyList())

    /** YouTube Music's timed lines (the song's own where it's YouTube's, else the one its search finds first). */
    private fun tube(s: Song, lrc: File, asked: File): List<Line>? = runCatching {
        val id = if (s.kind == "youtube") s.id else Tube.api.searchSongs("${s.artist} ${s.title}", 1).firstOrNull()?.videoId ?: return@runCatching emptyList()
        val lines = Tube.api.lyrics(id).map { Line(it.first, it.second) }
        runCatching {
            dir.mkdirs()
            if (lines.isNotEmpty()) lrc.writeText(lrcOf(lines))
            asked.writeText("")
        }
        lines
    }.getOrNull()

    /** (synced, plain) from lrclib: "" for none. null: lrclib never answered. */
    private fun ask(artist: String, title: String, album: String, seconds: Long): Pair<String, String>? {
        fun q(vararg kv: Pair<String, Any>) = kv.joinToString("&") { (k, v) -> k + "=" + urlEncode(v.toString()) }
        val urls = listOf(
            "https://lrclib.net/api/get?" + q("artist_name" to artist, "track_name" to title, "album_name" to album, "duration" to seconds),
            "https://lrclib.net/api/search?" + q("artist_name" to artist, "track_name" to title),
        )
        var answered = false
        var words = ""
        for (url in urls) {
            val r = runCatching { http(url, headers = mapOf("User-Agent" to AGENT), connectMs = 10_000, readMs = 10_000) }.getOrNull() ?: continue
            if (r.code == 404) { answered = true; continue }
            if (r.code != 200) continue
            val hits = runCatching { r.text.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONArray().put(JSONObject(it)) } }.getOrNull() ?: continue
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

    private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
    private val wordStamp = Regex("""<(\d+):(\d+(?:\.\d+)?)>([^<]*)""")

    private fun clock(ms: Long): String {
        val m = (ms / 60_000).toString().padStart(2, '0')
        val cs = (ms % 60_000) / 10
        return "$m:${(cs / 100).toString().padStart(2, '0')}.${(cs % 100).toString().padStart(2, '0')}"
    }

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
}
