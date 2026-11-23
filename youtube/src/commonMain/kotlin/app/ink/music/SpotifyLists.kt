package app.ink.music

/**
 * Public Spotify playlists read for INK's picks, song data only (the phone's since 2026-11-07; shared with the computer
 * since the same day, with the magazines' own lists): each list's public embed page, the track list in the page's own
 * data, signed in as nobody. Titles and artists, nothing played from there (each song found as any pick is: yours,
 * your server's, YouTube Music's). Kept with YouTube's code: never in the play build. A few pages, twice a day at most.
 */
object SpotifyLists {
    const val EDITORS = "editors' picks"
    /** Lists ink follows and passes on as its own picks, not credited by name (2026-11-23): "ink curated". */
    const val INK = "ink curated"

    /**
     * A list: its id, who it's from (shown with its songs), and how many of it to take: its first, or (`turn`, a list
     * that's an archive, not this week's) a different run of that many each day, through the whole list in turn.
     */
    class Playlist(val id: String, val from: String, val max: Int, val turn: Boolean = false)

    /** Spotify's editorial indie lists (Fresh Finds, Lorem, POLLEN, Indie Pop, All New Indie), "editors' picks"; then
     * the magazines' own: The Line of Best Fit's new music discovery (40 a week) and DIY's Neu Playlist (50); and
     * antoniahelena's top ten (2026-11-23: ten she's into, electronic and left-field pop) and her archive (a hundred,
     * further back: twenty a day in turn), shown as ink curated. */
    val ALL = listOf(
        Playlist("37i9dQZF1DWWjGdmeTyeJ6", EDITORS, 25), Playlist("37i9dQZF1DXdwmD5Q7Gxah", EDITORS, 25), Playlist("37i9dQZF1DWWBHeXOYZf74", EDITORS, 25),
        Playlist("37i9dQZF1DWWEcRhUVtL8n", EDITORS, 25), Playlist("37i9dQZF1DXdbXrPNafg9d", EDITORS, 25),
        Playlist("5359l8Co8qztllR0Mxk4Zv", "the line of best fit", 40), Playlist("29J24iEHulf3BGk7yzwGIH", "diy", 50),
        Playlist("5k1KNpY2RJjt3UmY8EapvQ", INK, 10), Playlist("60qW3dUqraghlWMN0Saqh5", INK, 20, turn = true),
    )

    /** Every list's songs, taken in turn (a list each, so no one list fills a shelf); one that won't answer passed over. */
    fun picks(now: Long, log: (String) -> Unit = {}): List<Curation.Pick> {
        val first = read(now, log)
        if (first.isNotEmpty()) return first
        // none answered at all (2026-11-23: the phone's network not up yet as the app started, every list "unable to
        // resolve host", and INK went half a day without them): once more, a few seconds on
        Thread.sleep(5_000)
        return read(now, log)
    }

    private fun read(now: Long, log: (String) -> Unit): List<Curation.Pick> {
        val each = ALL.map { l ->
            runCatching {
                if (!l.turn) list(l.id, l.from, l.max, now)
                else whole(l.id, l.from, now).let { all ->
                    // today's run of it: the day's place along the list, wrapping round
                    if (all.size <= l.max) all else {
                        val from = ((now / 86_400_000L) * l.max % all.size).toInt()
                        (all.drop(from) + all.take(from)).take(l.max)
                    }
                }
            }.onFailure { log("spotify ${l.id}: $it") }.getOrDefault(emptyList())
        }
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { Curation.key(it) }
            .also { log("spotify: ${it.size} songs from ${each.count { l -> l.isNotEmpty() }} lists") }
    }

    /**
     * A whole list read another way first, as (title, artist): the phone signed in to Spotify reads every song as
     * Spotify's own player does; null where it can't (signed out, the computer).
     */
    @Volatile var reader: ((String) -> List<Pair<String, String>>?)? = null
    /** Told of a whole list so read (the curator's phone sends it on to inkhome.app, for everyone). */
    @Volatile var readWhole: ((String, List<Pair<String, String>>) -> Unit)? = null

    /**
     * A whole list, every song (an archive of 700: the embed page shows only its first 100): read with a Spotify
     * sign-in where there is one ([reader]); else from inkhome.app, which keeps the whole list a signed-in phone sent it
     * (functions/lists there); else the embed page's 100.
     */
    fun whole(id: String, from: String, now: Long): List<Curation.Pick> {
        runCatching { reader?.invoke(id) }.getOrNull()?.filter { it.first.isNotBlank() && it.second.isNotBlank() }?.takeIf { it.size > 100 }?.let { all ->
            runCatching { readWhole?.invoke(id, all) }
            return all.map { (t, a) -> Curation.Pick(false, t.replace(Regex("\\s+-\\s+(From|Remaster).*$", RegexOption.IGNORE_CASE), ""), a, from, "", "", now, null) }
        }
        return fromSite(id, from, now)
    }

    private fun fromSite(id: String, from: String, now: Long): List<Curation.Pick> = runCatching {
        val r = http("https://inkhome.app/lists/$id.json", connectMs = 8_000, readMs = 15_000)
        if (r.code != 200) error("site ${r.code}")
        val a = JSONObject(r.text).getJSONArray("songs")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Curation.Pick(false, o.optString("t").replace(Regex("\\s+-\\s+(From|Remaster).*$", RegexOption.IGNORE_CASE), ""), o.optString("a"), from, "", "", now,
                o.optString("art").takeIf { it.startsWith("http") })
        }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }.ifEmpty { error("site: none") }
    }.getOrElse { list(id, from, 500, now) }

    /** One public list's songs, in its order, the first `max` (blocking; throws when it won't answer twice). */
    fun list(id: String, from: String, max: Int, now: Long): List<Curation.Pick> {
        // once more if it won't answer (one list said 404 to the phone, then answered)
        val page = runCatching { get(id) }.getOrElse { get(id) }
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
        val list = JSONObject(data).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state").getJSONObject("data")
            .getJSONObject("entity").getJSONArray("trackList")
        return (0 until minOf(list.length(), max)).map { list.getJSONObject(it) }.map { t ->
            // "sunandmoon, Ogi, Anoop": the artists as ink music writes them
            val artist = t.optString("subtitle").replace(' ', ' ').split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            Curation.Pick(false, t.optString("title").replace(Regex("\\s+-\\s+(From|Remaster).*$", RegexOption.IGNORE_CASE), ""), artist, from, "", "", now, null)
        }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
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
