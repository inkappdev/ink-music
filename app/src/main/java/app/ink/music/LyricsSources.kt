package app.ink.music

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional lyrics catalogues. These are independent fallbacks: a failure or a
 * no-match at one provider never prevents the next provider from being tried.
 * All timestamps are normalized to milliseconds, matching Lyrics.Line.
 */
object LyricsSources {
    data class Source(val id: String, val label: String, val detail: String)

    val sources = listOf(
        Source("auto", "Automatic", "Best available result from all enabled sources"),
        Source("better", "BetterLyrics", "Word-synced Apple Music lyrics"),
        Source("better_qq", "BetterLyrics Portato", "QQ Music karaoke timings"),
        Source("youtube", "YouTube Music", "Timed lyrics from YouTube Music"),
        Source("lrclib", "LRCLIB", "Synced and plain lyrics"),
        Source("bini", "BiniLyrics", "Apple Music TTML, word-level timing"),
        Source("spicy", "Spicy Lyrics", "Community and catalogue lyrics; API key required"),
        Source("lyricsplus", "LyricsPlus", "Community mirrors, syllable timing"),
        Source("unison", "Unison", "Community-contributed lyrics"),
        Source("kugou", "KuGou", "LRC catalogue, useful for CJK releases"),
        Source("ovh", "lyrics.ovh", "Plain-text fallback"),
    )

    private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun request(url: String, accept: String = "application/json"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 9000
        c.setRequestProperty("User-Agent", "INK-Music/2")
        c.setRequestProperty("Accept", accept)
        try {
            val code = c.responseCode
            if (code !in 200..299) return ""
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parseLrc(raw: String): List<Line> = raw.lineSequence().flatMap { line ->
        val body = stamp.replace(line, "").trim()
        if (body.isBlank()) emptySequence() else stamp.findAll(line).mapNotNull { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            Line((min * 60_000 + sec * 1000).toLong(), body)
        }
    }.sortedBy { it.at }.toList()

    private fun plain(raw: String) = raw.lines().map { Line(-1, it.trim()) }
        .dropWhile { it.words.isBlank() }.dropLastWhile { it.words.isBlank() }

    suspend fun fetch(id: String, title: String, artist: String, album: String, durationSeconds: Long, spicyKey: String = ""): List<Line>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                when (id) {
                    "ovh" -> {
                        val json = JSONObject(request("https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}"))
                        plain(json.optString("lyrics")).takeIf { it.isNotEmpty() }
                    }
                    "unison" -> {
                        val url = "https://unison.boidu.dev/lyrics?song=${enc(title)}&artist=${enc(artist)}&album=${enc(album)}&duration=$durationSeconds"
                        val data = JSONObject(request(url))
                        val lyrics = data.optJSONObject("data")?.optString("lyrics").orEmpty()
                        if (lyrics.isBlank()) null else {
                            val sync = data.optJSONObject("data")?.optString("syncType")
                            if (sync.equals("plain", true)) plain(lyrics) else parseLrc(lyrics).takeIf { it.isNotEmpty() }
                        }
                    }
                    "kugou" -> kugou(title, artist, durationSeconds)
                    "lyricsplus" -> lyricsPlus(title, artist, album, durationSeconds)
                    "bini" -> bini(title, artist, durationSeconds)
                    "spicy" -> spicy(title, artist, durationSeconds, spicyKey)
                    "better_qq" -> betterPortato(title, artist, album, durationSeconds)
                    else -> null
                }
            }.getOrNull()
        }

    private fun kugou(title: String, artist: String, duration: Long): List<Line>? {
        val q = enc("$artist $title")
        val search = JSONObject(request("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$q"))
        val songs = search.optJSONObject("data")?.optJSONArray("info") ?: return null
        var hash = ""
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val d = s.optLong("duration")
            if (duration <= 0 || d <= 0 || kotlin.math.abs(d - duration) <= 8) { hash = s.optString("hash"); break }
        }
        if (hash.isBlank()) return null
        val candidates = JSONObject(request("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=$hash"))
            .optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val key = c.optString("accesskey")
            if (id.isBlank() || key.isBlank()) continue
            val d = JSONObject(request("https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=$id&accesskey=$key"))
            val encoded = d.optString("content")
            if (encoded.isBlank()) continue
            val lrc = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            parseLrc(lrc).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun lyricsPlus(title: String, artist: String, album: String, duration: Long): List<Line>? {
        val mirrors = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app",
            "https://lyrics-plus-backend.vercel.app",
        )
        for (host in mirrors) {
            val url = "$host/v2/lyrics/get?title=${enc(title)}&artist=${enc(artist)}&duration=$duration&album=${enc(album)}"
            val json = runCatching { JSONObject(request(url)) }.getOrNull() ?: continue
            val lyrics = json.optJSONArray("lyrics") ?: continue
            val lines = ArrayList<Line>()
            for (i in 0 until lyrics.length()) {
                val line = lyrics.optJSONObject(i) ?: continue
                val at = line.optLong("time", -1L)
                if (at < 0) continue
                val syllables = line.optJSONArray("syllabus")
                val parts = ArrayList<Pair<Long, String>>()
                if (syllables != null) for (j in 0 until syllables.length()) {
                    val item = syllables.optJSONObject(j) ?: continue
                    val word = item.optString("text")
                    if (word.isNotBlank()) parts += item.optLong("time", at) to word
                }
                val text = if (parts.isNotEmpty()) parts.joinToString("") { it.second } else line.optString("text")
                if (text.isNotBlank()) lines += Line(at, text, parts)
            }
            if (lines.isNotEmpty()) return lines.sortedBy { it.at }
        }
        return null
    }

    private fun spicy(title: String, artist: String, duration: Long, apiKey: String): List<Line>? {
        if (apiKey.isBlank()) return null
        val search = JSONObject(request("https://api.deezer.com/search?q=${enc("artist:\"$artist\" track:\"$title\"")}&limit=10"))
        val tracks = search.optJSONArray("data") ?: return null
        var deezerId = ""
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val sameTitle = track.optString("title").equals(title, true)
            val sameArtist = track.optJSONObject("artist")?.optString("name").equals(artist, true)
            val durationMatches = duration <= 0 || kotlin.math.abs(track.optLong("duration") - duration) <= 5
            if (sameTitle && sameArtist && durationMatches) { deezerId = track.optString("id"); break }
        }
        if (deezerId.isBlank()) return null
        val links = JSONObject(request("https://api.song.link/v1-alpha.1/links?url=${enc("https://www.deezer.com/track/$deezerId")}"))
        val spotifyUrl = links.optJSONObject("linksByPlatform")?.optJSONObject("spotify")?.optString("url").orEmpty()
        val spotifyId = Regex("""/track/([A-Za-z0-9]{22})(?:[/?#]|$)""").find(spotifyUrl)?.groupValues?.get(1) ?: return null
        val c = URL("https://api.spicylyrics.org/v1/lyrics/$spotifyId").openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 9000
        c.setRequestProperty("User-Agent", "INK-Music/2")
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        val authenticated = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val root = JSONObject(authenticated).optJSONObject("Body") ?: return null
        val type = root.optString("Type")
        val content = root.optJSONArray("Content") ?: root.optJSONArray("Lines") ?: return null
        val out = ArrayList<Line>()
        for (i in 0 until content.length()) {
            val row = content.optJSONObject(i)
            if (row == null) {
                val plain = content.optString(i).trim()
                if (plain.isNotEmpty()) out += Line(-1, plain)
                continue
            }
            when (type.lowercase()) {
                "syllable" -> {
                    val lead = row.optJSONObject("Lead")
                    val syllables = lead?.optJSONArray("Syllables")
                    val parts = ArrayList<Pair<Long, String>>()
                    if (syllables != null) for (j in 0 until syllables.length()) {
                        val syllable = syllables.optJSONObject(j) ?: continue
                        val word = syllable.optString("Text")
                        if (word.isNotEmpty()) parts += (syllable.optDouble("StartTime") * 1000).toLong() to word
                    }
                    val text = parts.joinToString("") { it.second }.ifBlank { lead?.optString("Text").orEmpty() }
                    val start = lead?.optDouble("StartTime", 0.0) ?: parts.firstOrNull()?.first?.div(1000.0) ?: 0.0
                    if (text.isNotBlank()) out += Line((start * 1000).toLong(), text, parts)
                }
                "line" -> {
                    val text = row.optString("Text")
                    if (text.isNotBlank()) out += Line((row.optDouble("StartTime", 0.0) * 1000).toLong(), text)
                }
                else -> {
                    val text = row.optString("Text").ifBlank { row.toString() }
                    if (text.isNotBlank()) out += Line(-1, text)
                }
            }
        }
        return out.takeIf { it.isNotEmpty() }?.sortedBy { it.at }
    }

    private fun betterPortato(title: String, artist: String, album: String, duration: Long): List<Line>? {
        val url = "https://lyrics-api.boidu.dev/qq/getLyrics?s=${enc(title)}&a=${enc(artist)}&d=$duration&al=${enc(album)}"
        val body = request(url)
        val ttml = runCatching { JSONObject(body).optString("ttml") }.getOrDefault(body)
        if (ttml.isBlank()) return null
        return BetterLyrics.parse(ttml).map { Line(it.at, it.words, it.parts) }.takeIf { it.isNotEmpty() }
    }

    private fun bini(title: String, artist: String, duration: Long): List<Line>? {
        val searchUrl = "https://lyrics-api.binimum.org/?track=${enc(title)}&artist=${enc(artist)}&duration=$duration"
        val search = JSONObject(request(searchUrl))
        val results = search.optJSONArray("results") ?: return null
        var lyricsUrl = ""
        for (i in 0 until results.length()) {
            val hit = results.optJSONObject(i) ?: continue
            if (hit.optString("lyricsUrl").startsWith("https://")) {
                lyricsUrl = hit.optString("lyricsUrl")
                break
            }
        }
        if (lyricsUrl.isBlank()) return null
        val ttml = request(lyricsUrl, "application/ttml+xml, text/xml, */*")
        if (ttml.isBlank()) return null
        val out = ArrayList<Line>()
        val p = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val span = Regex("""<span\b([^>]*)>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        fun time(attrs: String): Long? {
            val raw = Regex("""begin=["']([^"']+)["']""").find(attrs)?.groupValues?.get(1) ?: return null
            val parts = raw.removeSuffix("s").split(':')
            var seconds = 0.0
            for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
            return (seconds * 1000).toLong()
        }
        fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"")
            .replace(Regex("""\s+"""), " ").trim()
        for (m in p.findAll(ttml)) {
            val at = time(m.groupValues[1]) ?: continue
            val parts = span.findAll(m.groupValues[2]).mapNotNull { word ->
                val stamp = time(word.groupValues[1]) ?: at
                val value = clean(word.groupValues[2])
                if (value.isBlank()) null else stamp to value
            }.toList()
            val text = if (parts.isEmpty()) clean(m.groupValues[2]) else parts.joinToString("") { it.second }
            if (text.isNotBlank()) out += Line(at, text, parts)
        }
        return out.takeIf { it.isNotEmpty() }?.sortedBy { it.at }
    }
}
