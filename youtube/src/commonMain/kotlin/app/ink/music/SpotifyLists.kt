package app.ink.music

/**
 * Public Spotify playlists read for INK's picks, song data only (the phone's since 2026-11-07; shared with the computer
 * since the same day, with the magazines' own lists): each list's public embed page, the track list in the page's own
 * data, signed in as nobody. Titles and artists, nothing played from there (each song found as any pick is: yours,
 * your server's, YouTube Music's). Kept with YouTube's code: never in the play build. A few pages, twice a day at most.
 */
object SpotifyLists {
    const val EDITORS = "editors' picks"

    /** A list: its id, who it's from (shown with its songs), and how many of it to take. */
    class Playlist(val id: String, val from: String, val max: Int)

    /** Spotify's editorial indie lists (Fresh Finds, Lorem, POLLEN, Indie Pop, All New Indie), "editors' picks"; then
     * the magazines' own: The Line of Best Fit's new music discovery (40 a week) and DIY's Neu Playlist (50). */
    val ALL = listOf(
        Playlist("37i9dQZF1DWWjGdmeTyeJ6", EDITORS, 25), Playlist("37i9dQZF1DXdwmD5Q7Gxah", EDITORS, 25), Playlist("37i9dQZF1DWWBHeXOYZf74", EDITORS, 25),
        Playlist("37i9dQZF1DWWEcRhUVtL8n", EDITORS, 25), Playlist("37i9dQZF1DXdbXrPNafg9d", EDITORS, 25),
        Playlist("5359l8Co8qztllR0Mxk4Zv", "the line of best fit", 40), Playlist("29J24iEHulf3BGk7yzwGIH", "diy", 50),
    )

    /** Every list's songs, taken in turn (a list each, so no one list fills a shelf); one that won't answer passed over. */
    fun picks(now: Long, log: (String) -> Unit = {}): List<Curation.Pick> {
        val each = ALL.map { l ->
            runCatching {
                // once more if it won't answer (one list said 404 to the phone, then answered)
                val page = runCatching { get(l.id) }.getOrElse { get(l.id) }
                val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
                val list = JSONObject(data).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state").getJSONObject("data")
                    .getJSONObject("entity").getJSONArray("trackList")
                (0 until minOf(list.length(), l.max)).map { list.getJSONObject(it) }.map { t ->
                    // "sunandmoon, Ogi, Anoop": the artists as ink music writes them
                    val artist = t.optString("subtitle").replace(' ', ' ').split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                    Curation.Pick(false, t.optString("title").replace(Regex("\\s+-\\s+(From|Remaster).*$", RegexOption.IGNORE_CASE), ""), artist, l.from, "", "", now, null)
                }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            }.onFailure { log("spotify ${l.id}: $it") }.getOrDefault(emptyList())
        }
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { Curation.key(it) }
            .also { log("spotify: ${it.size} songs from ${each.count { l -> l.isNotEmpty() }} lists") }
    }

    private fun get(id: String): String {
        val r = http("https://open.spotify.com/embed/playlist/$id", "GET", mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
            "Accept-Language" to "en",
        ), null, 8_000, 15_000)
        if (r.code >= 400) throw IllegalStateException("spotify answered ${r.code}")
        return r.text
    }
}
