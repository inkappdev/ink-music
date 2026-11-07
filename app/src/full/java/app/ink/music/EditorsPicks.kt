package app.ink.music

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * More picks for INK (2026-11-07, the full build only), song data only, shown as "editors' picks" and "youtube music":
 * - Spotify's editorial indie playlists (Fresh Finds, Lorem, POLLEN, Indie Pop, All New Indie), read from their public
 *   embed pages (the track list in the page's own data), signed in as nobody: titles and artists, nothing played from
 *   there (each song is found as any pick is: yours, your server's, YouTube Music's). A few pages, twice a day at most.
 * - YouTube Music's own editors' lists for indie & alternative (its moods and genres page): the first of its shelves'.
 */
object EditorsPicks {
    private val SPOTIFY = listOf("37i9dQZF1DWWjGdmeTyeJ6", "37i9dQZF1DXdwmD5Q7Gxah", "37i9dQZF1DWWBHeXOYZf74", "37i9dQZF1DWWEcRhUVtL8n", "37i9dQZF1DXdbXrPNafg9d")
    const val EDITORS = "editors' picks"
    const val TUBE = "youtube music"

    /** Every one, newest lists first; any that won't answer passed over. */
    fun read(): List<Curation.Pick> = runCatching { editors() }.getOrDefault(emptyList()) + runCatching { tube() }.getOrDefault(emptyList())

    private fun editors(): List<Curation.Pick> {
        val now = System.currentTimeMillis()
        val each = SPOTIFY.map { id ->
            runCatching {
                // once more after a moment if it won't answer (one list said 404 to the phone, then answered)
                val page = runCatching { get("https://open.spotify.com/embed/playlist/$id") }.getOrElse { Thread.sleep(1_500); get("https://open.spotify.com/embed/playlist/$id") }
                val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
                val list = JSONObject(data).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state").getJSONObject("data")
                    .getJSONObject("entity").getJSONArray("trackList")
                (0 until minOf(list.length(), 25)).map { list.getJSONObject(it) }.map { t ->
                    // "sunandmoon, Ogi, Anoop": the artists as ink music writes them
                    val artist = t.optString("subtitle").replace(' ', ' ').split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                    Curation.Pick(false, t.optString("title").replace(Regex("\\s+-\\s+(From|Remaster).*$", RegexOption.IGNORE_CASE), ""), artist, EDITORS, "", "", now, null)
                }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            }.onFailure { android.util.Log.d("ink-picks", "editors $id: $it") }.getOrDefault(emptyList())
        }
        // taken in turn, a list each, so no one list fills the shelf
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { Curation.key(it) }
            .also { android.util.Log.d("ink-picks", "editors: ${it.size} songs from ${each.count { l -> l.isNotEmpty() }} lists") }
    }

    private fun tube(): List<Curation.Pick> {
        val now = System.currentTimeMillis()
        val indie = YouTubeMusic.moods().firstOrNull { it.genre && it.title.contains("indie", ignoreCase = true) } ?: return emptyList()
        val songs = YouTubeMusic.editorsSongs(indie.params)
        // a video's title as a song's: "(Official Video)", "[Visualizer]" and their like off its end
        val video = Regex("""\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|music video|mv)\b[^)\]]*[)\]]\s*$""", RegexOption.IGNORE_CASE)
        return songs.map { Curation.Pick(false, it.title.replace(video, "").replace(video, "").trim(), it.artist, TUBE, "", "", now, it.art) }
            .also { android.util.Log.d("ink-picks", "youtube music's ${indie.title}: ${it.size} songs") }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
        c.setRequestProperty("Accept-Language", "en")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
}
