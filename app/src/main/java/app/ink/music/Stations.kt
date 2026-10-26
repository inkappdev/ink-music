package app.ink.music

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Internet radio, from radio-browser.info (a free, open, community-kept list of stations): the
 * stations people play most for a genre. A station is a song that never ends: "s<uuid>", its
 * stream as its address, its name as its title, ink's own sleeve as its cover (station logos are
 * small and every shape; a word works). In every build, so a phone with nothing on it still plays.
 */
object Stations {
    /** The genres offered, as said, and the tag asked for. */
    val genres = listOf(
        "lofi" to "lofi", "jazz" to "jazz", "indie" to "indie", "classical" to "classical", "ambient" to "ambient",
        "electronic" to "electronic", "hip hop" to "hip hop", "soul" to "soul", "rock" to "rock", "folk" to "folk",
        "house" to "house", "reggae" to "reggae",
    )

    private val known = ConcurrentHashMap<String, Song>()
    private val byTag = ConcurrentHashMap<String, List<Song>>()

    fun known(id: String): Song? = known[id]

    /** One of ink radio's stations: the song that plays it, and the line said about it. */
    data class Curated(val song: Song, val city: String, val line: String, val genre: String, val star: Boolean)

    @Volatile private var curated: List<Curated>? = null

    /**
     * ink radio: stations chosen by hand (assets/ink-radio.json, from ~/src/radio: independent,
     * listener-powered and community stations, each with its city and a line on it), the stars first.
     * "s<id>": the song is the station, its city where an artist would be.
     */
    fun curated(c: android.content.Context): List<Curated> = curated ?: runCatching {
        val all = org.json.JSONObject(c.assets.open("ink-radio.json").bufferedReader().use { it.readText() }).getJSONArray("stations")
        (0 until all.length()).map { all.getJSONObject(it) }.map { o ->
            val id = "s" + o.getString("id")
            val city = o.optString("city").substringBefore(" (")
            val song = Song(
                id = id, uri = Uri.parse(o.getString("url")), title = o.getString("name"), artist = city,
                album = "", albumId = id, albumArtist = o.getString("name"), duration = 0, disc = 1, track = 0, year = 0,
                format = "radio", mime = if (o.optString("codec").lowercase().startsWith("aac")) "audio/aac" else "audio/mpeg",
            ).also { known[id] = it }
            Curated(song, city, o.optString("line"), o.optString("genre"), o.optBoolean("star"))
        }
    }.getOrDefault(emptyList()).also { curated = it }

    /** The stations most played for `tag` (direct streams, 96 kbps or better; HLS left out, the player has no HLS). */
    suspend fun top(tag: String, n: Int = 20): List<Song> = byTag[tag] ?: withContext(Dispatchers.IO) {
        val url = "https://all.api.radio-browser.info/json/stations/search?tag=" + URLEncoder.encode(tag, "UTF-8") +
            "&order=clickcount&reverse=true&hidebroken=true&hls=false&limit=60"
        val list = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "ink music/" + BuildConfig.VERSION_NAME)   // as the list asks
            try { JSONArray(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
        }.getOrNull() ?: return@withContext emptyList()
        val seen = HashSet<String>()
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optInt("bitrate") >= 96 && it.optString("url_resolved").startsWith("http") }
            .filter { seen.add(it.optString("name").trim().lowercase()) }   // one of each (stations are often listed twice)
            .take(n)
            .map { o ->
                val id = "s" + o.optString("stationuuid")
                Song(
                    id = id, uri = Uri.parse(o.optString("url_resolved")), title = o.optString("name").trim(),
                    artist = tag + " radio", album = "", albumId = id, albumArtist = o.optString("name").trim(),
                    duration = 0, disc = 1, track = 0, year = 0, format = "radio",
                    mime = when (o.optString("codec").uppercase()) { "AAC", "AAC+" -> "audio/aac"; "OGG" -> "audio/ogg"; else -> "audio/mpeg" },
                ).also { known[id] = it }
            }
            .also { if (it.isNotEmpty()) byTag[tag] = it }
    }
}
