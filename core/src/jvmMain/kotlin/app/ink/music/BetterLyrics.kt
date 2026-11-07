package app.ink.music

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics timed word by word, from Better Lyrics' service (as Meld has them; 2026-11-01): its TTML, each line and each
 * word with when it's sung, so the sung line lights word by word as it's sung,
 * not by a guess. It answers without a key only for songs it already has (most anything played much); a miss, a key
 * asked for, or no answer is none. Not ours and not public: a quiet extra, kept on the disk once found (Lyrics).
 */
object BetterLyrics {
    /** A line: when it's sung (ms), its words, and each word with when it starts (its spacing kept in its text). */
    data class Timed(val at: Long, val words: String, val parts: List<Pair<Long, String>>)

    /** The song's lines (empty: none to be had); null when the service couldn't be reached. Blocking. */
    fun of(title: String, artist: String, seconds: Long, agent: String): List<Timed>? {
        val q = "s=" + enc(title) + "&a=" + enc(artist) + (if (seconds > 0) "&d=$seconds" else "")
        return try {
            val r = http("https://lyrics-api.boidu.dev/getLyrics?$q", headers = mapOf("User-Agent" to agent), connectMs = 8_000, readMs = 10_000)
            when (r.code) {
                200 -> parse(JSONObject(r.text).optString("ttml"))
                in 400..499 -> emptyList()   // not had, or a key wanted: none
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** TTML's lines (<p begin>) and words (<span begin>), the text between spans (a space) kept with the word before. */
    fun parse(ttml: String): List<Timed> {
        if (ttml.isBlank()) return emptyList()
        val out = ArrayList<Timed>()
        for (p in Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(ttml)) {
            val at = attr(p.groupValues[1], "begin")?.let(::ms) ?: continue
            val body = p.groupValues[2]
            val parts = ArrayList<Pair<Long, String>>()
            var last = 0
            for (s in Regex("""<span\b([^>]*)>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
                // what came between this span and the one before (a space) goes with the word before
                val between = text(body.substring(last, s.range.first))
                if (between.isNotEmpty() && parts.isNotEmpty()) parts[parts.size - 1] = parts.last().let { it.first to it.second + between }
                val w = text(s.groupValues[2])
                val start = attr(s.groupValues[1], "begin")?.let(::ms) ?: parts.lastOrNull()?.first ?: at
                if (w.isNotEmpty()) parts += start to w
                last = s.range.last + 1
            }
            val words = if (parts.isEmpty()) text(body) else parts.joinToString("") { it.second }.trim()
            if (words.isNotBlank()) out += Timed(at, words, if (parts.size > 1) parts else emptyList())
        }
        return out.sortedBy { it.at }
    }

    private fun attr(s: String, name: String) = Regex("""\b$name="([^"]*)"""").find(s)?.groupValues?.get(1)
    private fun text(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ")

    /** "19.529", "2:50.942", "1:02:50.9", "12.5s" to ms. */
    private fun ms(t: String): Long? {
        val s = t.trim().removeSuffix("s")
        val parts = s.split(':')
        var total = 0.0
        for (p in parts) total = total * 60 + (p.toDoubleOrNull() ?: return null)
        return (total * 1000).toLong()
    }
}
