package app.ink.music

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
 * small and every shape; a word works). The phone's Stations, read the desktop's way.
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
    fun remember(s: Song) { known[s.id] = s }

    /** One of ink radio's stations: the song that plays it, and the line said about it. */
    data class Curated(val song: Song, val city: String, val line: String, val genre: String, val star: Boolean)

    @Volatile private var curated: List<Curated>? = null

    /**
     * ink radio: stations chosen by hand (the phone's assets/ink-radio.json, from ~/src/radio: independent,
     * listener-powered and community stations, each with its city and a line on it), the stars first.
     * "s<id>": the song is the station, its city where an artist would be.
     */
    fun curated(): List<Curated> = curated ?: runCatching {
        val all = org.json.JSONObject(Stations::class.java.getResourceAsStream("/ink-radio.json")!!.bufferedReader().use { it.readText() }).getJSONArray("stations")
        (0 until all.length()).map { all.getJSONObject(it) }.map { o ->
            val id = "s" + o.getString("id")
            val city = o.optString("city").substringBefore(" (")
            val song = Song(
                id = id, path = o.getString("url"), title = o.getString("name"), artist = city,
                album = "", albumId = id, albumArtist = o.getString("name"), duration = 0, disc = 1, track = 0, year = 0,
                format = "radio",
            ).also { known[id] = it }
            Curated(song, city, o.optString("line"), o.optString("genre"), o.optBoolean("star"))
        }
    }.getOrDefault(emptyList()).also { curated = it }

    /**
     * What else a genre goes by: radio-browser's tags for it (matched exactly), and the words ink
     * radio's own stations are filed under for it.
     */
    private val tags = mapOf("lofi" to listOf("lofi", "lo-fi"), "hip hop" to listOf("hip hop", "hip-hop", "hiphop"),
        "house" to listOf("house", "deep house"), "reggae" to listOf("reggae", "roots reggae"))
    private val filed = mapOf("hip hop" to listOf("hip-hop"), "house" to listOf("club"), "electronic" to listOf("electronic", "club"),
        "folk" to listOf("americana"))
    /** Genres ink radio holds enough of to be the whole list (radio-browser's are a mixed bag for these). */
    private val ours = setOf("indie", "electronic")

    /** The stations last found for a genre, if any (what the tiles light by). */
    fun found(tag: String): List<Song>? = byTag[tag]

    /**
     * A genre's stations: ink radio's own filed under it first (the stars first), then the ones
     * played most on radio-browser for it, once tidied: tagged it exactly, not a station that tags
     * itself everything to be found (more than 12), direct streams of 96 kbps or better (no HLS: the
     * list answers it though asked not to, and one in a list and none of it plays), none of
     * SomaFM's (its terms refuse apps like this), and none ink radio already has.
     */
    suspend fun top(tag: String, n: Int = 20): List<Song> = byTag[tag] ?: withContext(Dispatchers.IO) {
        val words = filed[tag] ?: listOf(tag)
        val mine = curated().filter { s -> s.genre.split(",").map { it.trim() }.any { it in words } }
            .sortedByDescending { it.star }.map { it.song }
        if (tag in ours && mine.isNotEmpty()) return@withContext mine.also { byTag[tag] = it }
        val theirs = (tags[tag] ?: listOf(tag)).flatMap { t ->
            val url = "https://all.api.radio-browser.info/json/stations/search?tag=" + URLEncoder.encode(t, "UTF-8") +
                "&tagExact=true&order=clickcount&reverse=true&hidebroken=true&hls=false&limit=80"
            val list = runCatching {
                val h = URL(url).openConnection() as HttpURLConnection
                h.connectTimeout = 10_000; h.readTimeout = 15_000
                h.setRequestProperty("User-Agent", AGENT)   // as the list asks
                try { JSONArray(h.inputStream.bufferedReader().use { it.readText() }) } finally { h.disconnect() }
            }.getOrNull() ?: return@flatMap emptyList()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        }.sortedByDescending { it.optInt("clickcount") }
        val names = curated().map { it.song.title.lowercase() }.filter { it.length >= 4 }   // "fip" would drop FIP Reggae, not ours
        val seen = HashSet<String>()
        val more = theirs.asSequence()
            .filter { o ->
                val stream = o.optString("url_resolved")
                val name = o.optString("name").trim().lowercase()
                o.optInt("bitrate") >= 96 && stream.startsWith("http") && o.optInt("hls") == 0 && ".m3u8" !in stream.lowercase() &&
                    o.optString("tags").split(",").count { it.isNotBlank() } <= 12 &&
                    "somafm" !in name && "somafm" !in stream && o.optString("countrycode") != "KP" &&
                    names.none { it in name }
            }
            .filter { seen.add(it.optString("name").trim().lowercase()) }   // one of each (stations are often listed twice)
            .take((n - mine.size).coerceAtLeast(0))
            .map { o ->
                val id = "s" + o.optString("stationuuid")
                Song(
                    id = id, path = o.optString("url_resolved"), title = o.optString("name").trim(),
                    artist = tag + " radio", album = "", albumId = id, albumArtist = o.optString("name").trim(),
                    duration = 0, disc = 1, track = 0, year = 0, format = "radio",
                ).also { known[id] = it }
            }.toList()
        (mine + more).also { if (it.isNotEmpty()) byTag[tag] = it }
    }
}
