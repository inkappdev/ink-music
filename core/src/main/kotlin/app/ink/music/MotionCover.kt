package app.ink.music

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * A record's moving cover: Apple Music's motion artwork (the looping sleeve its album pages show, `editorialVideo` in
 * its catalog), the square one, as an HLS link (after BitChord's AppleMusicCanvas, 2026-10-30). The catalog is asked
 * with the web player's own anonymous pass, the one music.apple.com gives every visitor, read out of its script as a
 * browser gets it and kept till it lapses (Bronson's call, 2026-10-30, over a developer key of our own; Apple may
 * change it, and the covers go still). Only where a label made one: most records have none, and the still cover
 * stays. Found and not-found both kept (`dir`; a week for a miss, one may come), so a record is asked about once.
 */
object MotionCover {
    private const val CATALOG = "https://amp-api.music.apple.com/v1/catalog"
    private const val AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    /** Where what's been found is kept (each app sets its own); null keeps it in memory only. */
    @Volatile var dir: File? = null

    // the pass is good only from apple.com, said in Origin, a header the JVM's own HTTP drops unless allowed (Android
    // sends it): read once, at the first request, so the computer allows it at its start too (Main)
    init { System.setProperty("sun.net.http.allowRestrictedHeaders", "true") }

    private val known = HashMap<String, Pair<String?, Long>>()
    private var loaded = false
    @Volatile private var pass: String? = null
    @Volatile private var passUntil = 0L
    @Volatile private var nextTry = 0L
    private val refused = HashSet<String>()

    /** The record's moving cover (an .m3u8), or null: none made, or Apple wouldn't say. */
    @Synchronized fun of(artist: String, album: String): String? {
        if (artist.isBlank() || album.isBlank()) return null
        load()
        val key = Search.fold("$artist / $album")
        known[key]?.let { (url, at) -> if (url != null || System.currentTimeMillis() - at < 7 * 86_400_000L) return url }
        // its script carries several passes, only one the catalog's: one turned away is struck off, the next tried
        var asked: Result<String?> = Result.failure(IllegalStateException("no pass"))
        for (n in 0 until 4) {
            val bearer = pass() ?: return null
            asked = runCatching { ask(artist, album, bearer) }
            if (bearer !in refused) break
        }
        if (asked.isFailure) return null   // couldn't ask: asked again next time
        val url = asked.getOrNull()
        known[key] = url to System.currentTimeMillis()
        save()
        return url
    }

    private val records = HashMap<String, String?>()

    /**
     * A song's record's moving cover, when the song came without its record's name (YouTube's search finds songs so):
     * the record named by Apple's own search for the song (the same artist and title), then its cover.
     */
    fun ofSong(artist: String, title: String): String? {
        if (artist.isBlank() || title.isBlank()) return null
        val key = Search.fold("$artist / $title")
        // asked before: the record then, or none (asked once)
        if (synchronized(this) { records.containsKey(key) }) return synchronized(this) { records[key] }?.let { of(artist, it) }
        val album = run {
            val bearer = synchronized(this) { pass() } ?: return null
            val found = runCatching { record(artist, title, bearer) }.getOrElse { return null }
            synchronized(this) { records[key] = found }
            found
        } ?: return null
        return of(artist, album)
    }

    private fun record(artist: String, title: String, bearer: String): String? {
        val shop = java.util.Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"
        val term = URLEncoder.encode("$artist $title", "UTF-8")
        val hits = JSONObject(get("$CATALOG/$shop/search?term=$term&types=songs&limit=10", bearer))
            .optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data") ?: return null
        val a = Search.fold(artist); val t = Search.fold(cut(title))
        for (i in 0 until hits.length()) {
            val at = hits.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val theirArtist = Search.fold(at.optString("artistName"))
            if (!(theirArtist == a || theirArtist.startsWith("$a ") || a.startsWith("$theirArtist "))) continue
            val theirs = Search.fold(cut(at.optString("name")))
            if (theirs != t && !theirs.startsWith("$t ") && !t.startsWith("$theirs ")) continue
            return at.optString("albumName").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun ask(artist: String, album: String, bearer: String): String? {
        val shop = java.util.Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"
        val term = URLEncoder.encode("$artist $album", "UTF-8")
        val text = get("$CATALOG/$shop/search?term=$term&types=albums&limit=10&extend=editorialVideo", bearer)
        val hits = JSONObject(text).optJSONObject("results")?.optJSONObject("albums")?.optJSONArray("data") ?: return null
        val a = Search.fold(artist); val r = Search.fold(cut(album))
        for (i in 0 until hits.length()) {
            val at = hits.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            // the same artist and record (a deluxe or remaster is the same record; a tribute isn't)
            val theirArtist = Search.fold(at.optString("artistName"))
            if (!(theirArtist == a || theirArtist.startsWith("$a ") || a.startsWith("$theirArtist "))) continue
            if (Search.fold(cut(at.optString("name"))) != r) continue
            val v = at.optJSONObject("editorialVideo") ?: continue
            return listOf("motionDetailSquare", "motionSquareVideo1x1").firstNotNullOfOrNull { v.optJSONObject(it)?.optString("video")?.takeIf { u -> u.startsWith("http") } }
        }
        return null
    }

    /** A record's name without its edition: "Blue (Deluxe Edition)", "Blue - Remastered" are Blue. */
    private fun cut(name: String) = name.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").substringBefore(" - ").trim()

    /** The web player's own anonymous pass: in one of its scripts, a JWT naming itself the web player's. */
    private fun pass(): String? {
        val now = System.currentTimeMillis()
        pass?.let { if (now < passUntil - 3_600_000L) return it }
        if (now < nextTry) return null
        nextTry = now + 30 * 60_000L
        val page = runCatching { get("https://music.apple.com/us/browse", null) }.getOrNull() ?: return null
        val scripts = Regex("/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\\.js").findAll(page).map { it.value }.distinct()
        for (path in scripts) {
            val js = runCatching { get("https://music.apple.com$path", null) }.getOrNull() ?: continue
            val found = Regex("ey[A-Za-z0-9_-]+\\.ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").findAll(js).map { it.value }.distinct()
                .mapNotNull { jwt -> runCatching {
                    val parts = jwt.split(".")
                    val head = String(Base64.getUrlDecoder().decode(parts[0])); val body = String(Base64.getUrlDecoder().decode(parts[1]))
                    val exp = Regex("\"exp\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLong()?.times(1000) ?: return@runCatching null
                    Triple(jwt, exp, "WebPlayKid" in head || "AMPWebPlay" in body)
                }.getOrNull() }
                .filter { it.second > now && it.first !in refused }.toList()
            val best = found.firstOrNull { it.third } ?: found.firstOrNull() ?: continue
            pass = best.first; passUntil = best.second
            return best.first
        }
        return null
    }

    private fun get(url: String, bearer: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", AGENT)
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer $bearer")
            c.setRequestProperty("Origin", "https://music.apple.com")
            c.setRequestProperty("Referer", "https://music.apple.com/")
        }
        val code = c.responseCode
        // turned away: the pass lapsed or changed, read again next time
        if ((code == 401 || code == 403) && bearer != null) { refused += bearer; pass = null; passUntil = 0; nextTry = 0 }
        if (code >= 400) error("http $code")
        return c.inputStream.use { it.bufferedReader().readText() }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = dir?.let { File(it, "motion-covers.json") } ?: return
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { k -> val e = o.getJSONObject(k); known[k] = e.optString("url").ifEmpty { null } to e.optLong("at") }
        }
    }

    private fun save() {
        val f = dir?.let { File(it, "motion-covers.json") } ?: return
        runCatching {
            val o = JSONObject()
            known.forEach { (k, v) -> o.put(k, JSONObject().put("url", v.first ?: "").put("at", v.second)) }
            f.writeText(o.toString())
        }
    }
}
