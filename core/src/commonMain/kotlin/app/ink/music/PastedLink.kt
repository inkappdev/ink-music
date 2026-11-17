package app.ink.music

/**
 * A song or a record from another service's link, pasted into find (2026-11-16, Bronson: Song.link from Mornye's
 * partners; Song.link's public API closed, PUBLIC_API_ACCESS_DEPRECATED, and UniTune's has none hosted, so read here):
 * its title, artist and length from the service's own public answers, for find to play it from YouTube Music.
 *  - Spotify: its embed page (open.spotify.com/embed/...), the song or record as the player has it;
 *  - Apple Music: Apple's own search API's lookup by id (a song's `?i=` or /song/, else the record);
 *  - Deezer: its public API;
 *  - YouTube and YouTube Music: the video's id, played as it is.
 * Tidal's public answers say nothing of the song: not read. Blocking: off the main thread.
 */
object PastedLink {
    enum class Kind { SONG, RECORD }

    /** What a link was: a song (with its YouTube id where the link was YouTube's) or a record; seconds 0 where unsaid. */
    data class Found(val kind: Kind, val title: String, val artist: String, val seconds: Long = 0, val tubeId: String? = null, val service: String)

    /** Whether these words are a link this can read (find asks before it searches them as words). */
    fun isLink(q: String): Boolean = Regex("""^\s*https?://\S+\s*$""").matches(q) &&
        listOf("spotify.com", "music.apple.com", "deezer.com", "deezer.page.link", "youtube.com", "youtu.be").any { it in q }

    fun read(q: String, agent: String = BROWSER): Found? = runCatching {
        val url = q.trim()
        when {
            "spotify.com" in url -> spotify(url, agent)
            "music.apple.com" in url -> apple(url)
            "deezer.com" in url || "deezer.page.link" in url -> deezer(url, agent)
            "youtu" in url -> youtube(url)
            else -> null
        }
    }.getOrNull()

    private fun spotify(url: String, agent: String): Found? {
        val (kind, id) = Regex("""spotify\.com/(?:intl-[a-z]+/)?(track|album)/([A-Za-z0-9]+)""").find(url)?.destructured ?: return null
        val html = http("https://open.spotify.com/embed/$kind/$id", headers = mapOf("User-Agent" to agent)).text
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        val e = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity") ?: return null
        val title = e.optString("name").ifEmpty { e.optString("title") }.ifEmpty { return null }
        val artists = e.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf(String::isNotEmpty) } }.orEmpty()
        val artist = artists.joinToString(", ").ifEmpty { e.optString("subtitle") }.ifEmpty { return null }
        return Found(if (kind == "album") Kind.RECORD else Kind.SONG, title, artist, e.optLong("duration") / 1000, service = "spotify")
    }

    private fun apple(url: String): Found? {
        val song = Regex("""[?&]i=(\d+)""").find(url)?.groupValues?.get(1) ?: Regex("""/song/[^/]+/(\d+)""").find(url)?.groupValues?.get(1)
        val record = Regex("""/album/[^/]+/(\d+)""").find(url)?.groupValues?.get(1)
        val id = song ?: record ?: return null
        val r = JSONObject(http("https://itunes.apple.com/lookup?id=$id").text).optJSONArray("results")?.optJSONObject(0) ?: return null
        val artist = r.optString("artistName").ifEmpty { return null }
        return if (song != null) Found(Kind.SONG, r.optString("trackName").ifEmpty { return null }, artist, r.optLong("trackTimeMillis") / 1000, service = "apple music")
        else Found(Kind.RECORD, r.optString("collectionName").ifEmpty { return null }, artist, service = "apple music")
    }

    private fun deezer(url: String, agent: String): Found? {
        // a share link (deezer.page.link/...) goes to the song's own page first
        val whole = if ("page.link" in url) http(url, headers = mapOf("User-Agent" to agent), follow = 0).header("location") ?: return null else url
        val (kind, id) = Regex("""deezer\.com/(?:[a-z]{2}/)?(track|album)/(\d+)""").find(whole)?.destructured ?: return null
        val o = JSONObject(http("https://api.deezer.com/$kind/$id").text)
        val title = o.optString("title").ifEmpty { return null }
        val artist = o.optJSONObject("artist")?.optString("name").orEmpty().ifEmpty { return null }
        return Found(if (kind == "album") Kind.RECORD else Kind.SONG, title, artist, o.optLong("duration"), service = "deezer")
    }

    private fun youtube(url: String): Found? {
        val id = Regex("""(?:[?&]v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1) ?: return null
        return Found(Kind.SONG, "", "", tubeId = id, service = "youtube")
    }

    /** As a browser asks (Spotify's embed page answers one). */
    const val BROWSER = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
}
