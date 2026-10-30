package app.ink.music

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A song's Spotify Canvas (the looping clip Spotify shows behind a song, an .mp4, tall), asked as the web player asks,
 * with its passes (the phone's sign-in takes them from its web player; the computer is handed them by the phone, in
 * sync: Bronson, 2026-10-30). The song found on Spotify by title and artist (the same artist), then its canvas. Kept,
 * a found one and a none (a week: one may come), so a song is asked about once.
 */
object SpotifyCanvas {
    /** The web player's passes: its bearer and client token, and the browser it said it was. They last an hour. */
    data class Pass(val bearer: String, val client: String?, val agent: String, val at: Long) {
        fun json(): String = JSONObject().put("bearer", bearer).put("client", client ?: "").put("agent", agent).put("at", at).toString()
        val fresh get() = System.currentTimeMillis() - at < 50 * 60_000L

        companion object {
            fun of(text: String): Pass? = runCatching {
                val o = JSONObject(text)
                Pass(o.getString("bearer").ifEmpty { return null }, o.optString("client").ifEmpty { null }, o.getString("agent"), o.getLong("at"))
            }.getOrNull()
        }
    }

    /** Turned away: Spotify ended the passes before their hour. */
    class Refused : Exception("http 401")

    private const val QUERY = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val CANVAS = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"
    private const val SEARCH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
    @Volatile private var canvasHash: String? = null
    @Volatile private var searchHash: String? = null

    private val kept = HashMap<String, Pair<String?, Long>>()
    @Volatile private var file: File? = null

    /** Where what's been found is kept (each app its own cache folder). */
    fun keepIn(f: File) { synchronized(kept) {
        if (file != null) return
        file = f
        runCatching { JSONObject(f.readText()).let { o -> o.keys().forEach { k -> val e = o.getJSONObject(k); kept[k] = e.optString("url").ifEmpty { null } to e.optLong("at") } } }
    } }

    /** What's known of the song already: Pair(true, url or null) if asked lately, else Pair(false, null). */
    fun known(title: String, artist: String): Pair<Boolean, String?> = synchronized(kept) {
        kept[Search.fold("$artist / $title")]?.let { (url, at) -> if (url != null || System.currentTimeMillis() - at < 7 * 86_400_000L) return true to url }
        false to null
    }

    /**
     * The song's canvas, asked with `pass` (and kept): null if it has none. Throws if Spotify couldn't be asked
     * ([Refused] for passes it turned away), so a song isn't marked as having none when it wasn't asked.
     */
    fun find(title: String, artist: String, pass: Pass): String? {
        val url = track(title, artist, pass)?.let { canvasOf(it, pass) }
        synchronized(kept) {
            kept[Search.fold("$artist / $title")] = url to System.currentTimeMillis()
            runCatching {
                val o = JSONObject(); kept.forEach { (k, v) -> o.put(k, JSONObject().put("url", v.first ?: "").put("at", v.second)) }
                file?.writeText(o.toString())
            }
        }
        return url
    }

    /**
     * The song on Spotify (its uri), by the web player's own search (its public API turns the web player's pass away,
     * a 429): the first with the same title and artist.
     */
    private fun track(title: String, artist: String, pass: Pass): String? {
        val vars = JSONObject().put("searchTerm", "$title $artist").put("offset", 0).put("limit", 10).put("numberOfTopResults", 5)
            .put("includeAudiobooks", false).put("includePreReleases", false)
        val root = runCatching { ask("searchTracks", searchHash ?: SEARCH, vars, pass) }.getOrElse { e ->
            if ("PersistedQuery" !in e.message.orEmpty()) throw e
            searchHash = liveHash("searchTracks", pass) ?: throw e
            ask("searchTracks", searchHash!!, vars, pass)
        }
        val items = root.optJSONObject("data")?.optJSONObject("searchV2")?.optJSONObject("tracksV2")?.optJSONArray("items") ?: return null
        val a = Search.fold(artist); val t = Search.fold(title.substringBefore(" (").substringBefore(" - "))
        for (i in 0 until items.length()) {
            val d = items.optJSONObject(i)?.optJSONObject("item")?.optJSONObject("data") ?: continue
            val name = Search.fold(d.optString("name").substringBefore(" (").substringBefore(" - "))
            val artists = d.optJSONObject("artists")?.optJSONArray("items") ?: continue
            val same = (0 until artists.length()).any { j ->
                Search.fold(artists.optJSONObject(j)?.optJSONObject("profile")?.optString("name").orEmpty()).let { n -> n == a || a.startsWith("$n ") || n.startsWith("$a ") }
            }
            if (same && (name == t || name.startsWith(t) || t.startsWith(name))) return d.optString("uri").takeIf { it.startsWith("spotify:track:") }
        }
        return null
    }

    /** The song's canvas by the web player's own query; its hash read again from the player's scripts once stale. */
    private fun canvasOf(uri: String, pass: Pass): String? {
        repeat(2) { n ->
            val hash = canvasHash ?: CANVAS
            val root = runCatching { ask("canvas", hash, JSONObject().put("trackUri", uri), pass) }
            val failed = root.exceptionOrNull()
            if (failed != null) {
                if (n == 0 && "PersistedQuery" in failed.message.orEmpty()) { canvasHash = liveHash("canvas", pass) ?: return null; return@repeat }
                throw failed
            }
            val canvas = root.getOrThrow().optJSONObject("data")?.optJSONObject("trackUnion")?.optJSONObject("canvas") ?: return null
            // a video only (some are still pictures)
            return canvas.optString("url").takeIf { it.startsWith("https://") && Regex("\\.mp4(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        }
        return null
    }

    /** A query's current hash, from the web player's own scripts (it changes when Spotify rebuilds the player). */
    private fun liveHash(operation: String, pass: Pass): String? = runCatching {
        val html = get("https://open.spotify.com/", pass)
        Regex("https://open\\.spotifycdn\\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\\.js").findAll(html).map { it.value }.distinct().take(8)
            .firstNotNullOfOrNull { js ->
                val body = runCatching { get(js, pass) }.getOrNull() ?: return@firstNotNullOfOrNull null
                Regex("[\"'](\\w+)[\"']\\s*,\\s*[\"']query[\"']\\s*,\\s*[\"']([0-9a-f]{64})[\"']").findAll(body)
                    .firstOrNull { it.groupValues[1] == operation }?.groupValues?.get(2)
            }
    }.getOrNull()

    private fun get(url: String, pass: Pass): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", pass.agent)
        val code = c.responseCode
        if (code >= 400) error("get: http $code")
        return c.inputStream.use { it.bufferedReader().readText() }
    }

    /** One of the web player's queries (the computer's JVM sends Origin only as allowed at its start: Main). */
    fun ask(operation: String, hash: String, vars: JSONObject, pass: Pass): JSONObject {
        val body = JSONObject().put("variables", vars).put("operationName", operation)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash)))
        val c = URL(QUERY).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("Authorization", "Bearer ${pass.bearer}")
        pass.client?.let { c.setRequestProperty("client-token", it) }
        c.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("app-platform", "WebPlayer")
        c.setRequestProperty("Origin", "https://open.spotify.com")
        c.setRequestProperty("Referer", "https://open.spotify.com/")
        c.setRequestProperty("User-Agent", pass.agent)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.bufferedReader().readText() }.orEmpty()
        if (code == 401) throw Refused()
        if (code >= 400) error("$operation: http $code")
        val root = JSONObject(text)
        root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }?.let { error("$operation: $it") }
        return root
    }
}
