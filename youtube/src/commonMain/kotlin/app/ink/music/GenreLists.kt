package app.ink.music

/**
 * A genre's songs as its editors keep them (2026-11-08, Bronson: "instead of depending on last.fm tags we should just
 * pull from public spotify and apple music playlists for specific genre pages"; a dance & electronic page had played
 * WHYM, then folk). Each of YouTube Music's genres (their titles, lowercase) and the editorial lists that are it, new
 * music first: Spotify's (its public embed page, as [SpotifyLists]) and Apple Music's (its public playlist page's own
 * data). Every list's name checked against its id the day it was written. Song data only, nothing played from there
 * (each song found on YouTube Music as any pick is); shown by the list's own name, never the service's. Kept with
 * YouTube's code: never in the play build.
 */
object GenreLists {
    /** A list: where it is (Spotify's id, or Apple's "pl." one), its name as shown, how many of it to take. */
    class Kept(val id: String, val name: String, val max: Int = 40) { val apple get() = id.startsWith("pl.") }

    private val INDIE = listOf(
        Kept("37i9dQZF1DWWjGdmeTyeJ6", "fresh finds"), Kept("37i9dQZF1DXdbXrPNafg9d", "all new indie"), Kept("37i9dQZF1DWWEcRhUVtL8n", "indie pop"),
        Kept("37i9dQZF1DXdwmD5Q7Gxah", "lorem"), Kept("pl.cbb3df8d3dec44a09fd44a9c18583ef9", "breaking alternative"),
        Kept("pl.132b9a231cbf464086b4f838b2726f94", "new in alternative"), Kept("pl.0b593f1142b84a50a2c1e7088b3fb683", "alt ctrl"),
    )

    val BY: Map<String, List<Kept>> = mapOf(
        "indie & alternative" to INDIE,
        "dance & electronic" to listOf(
            Kept("37i9dQZF1DX4dyzvuaRJ0n", "mint"), Kept("37i9dQZF1DX8tZsk68tuDw", "dance rising"), Kept("pl.1ef14c917f3d47ad8cc496eb0535025b", "breaking electronic"),
            Kept("pl.77a913605bba4a968c5cb2b93d87b2ca", "breaking dance"), Kept("37i9dQZF1DXa8NOEUWPn9W", "housewerk"), Kept("pl.6bf4415b83ce4f3789614ac4c3675740", "dancexl"),
            Kept("37i9dQZF1DXbXD9pMSZomS", "lo-fi house"), Kept("37i9dQZF1DX0r3x8OtiwEM", "lowkey tech"), Kept("37i9dQZF1DX6J5NfMJS675", "techno bunker"),
        ),
        "hip-hop" to listOf(
            Kept("37i9dQZF1DX0XUsuxWHRQd", "rapcaviar"), Kept("pl.abe8ba42278f4ef490e3a9fc5ec8e8c5", "rap life"), Kept("37i9dQZF1DX2RxBh64BHjQ", "most necessary"),
            Kept("pl.1fa57a04cd794a8aa482a3492f26fbcd", "new in hip-hop"), Kept("pl.87c7af5767764860a0e3368d0bef9a6f", "hip-hop hits"),
        ),
        "r&b & soul" to listOf(
            Kept("37i9dQZF1DX4SBhb3fqCJd", "rnb x"), Kept("pl.c270d4d09b3c441783076160a6f8325e", "breaking r&b"), Kept("pl.baa060f67ea94488a6e0c7e90c8afdb0", "new in r&b"),
        ),
        "jazz" to listOf(Kept("pl.ad678216ef504fabb74ec33c76dcefff", "jazz now"), Kept("37i9dQZF1DX0SM0LYsmbMT", "jazz vibes"), Kept("37i9dQZF1DWVqfgj8NZEp1", "coffee table jazz")),
        "rock" to listOf(
            Kept("37i9dQZF1DWZryfp6NSvtz", "all new rock"), Kept("pl.03c1eea28c2b4f4c97b5d9b660e9f42e", "breaking rock"), Kept("pl.aea893d092ed40a393d217ced33bde62", "new in rock"),
            Kept("37i9dQZF1DXcF6B6QPhFDv", "marrow"), Kept("37i9dQZF1DWXRqgorJj26U", "rock classics", 20),
        ),
        "folk & acoustic" to listOf(Kept("37i9dQZF1DXaUDcU6KDCj4", "fresh folk"), Kept("pl.522581bf8c264bafa742e75df9772078", "acoustic hits"), Kept("pl.b5e8dbe8a706496496e1292466839207", "acoustic chill")),
        "pop" to listOf(
            Kept("37i9dQZF1DWUa8ZRTfalHk", "pop rising"), Kept("pl.5e279cbb888c4de5b446460fa09fce08", "breaking pop"), Kept("37i9dQZF1DXcBWIGoYBM5M", "today's top hits"),
            Kept("pl.f4d106fed2bd41149aaacabb233eb5eb", "today's hits"), Kept("pl.5ee8333dbe944d9f9151e97d92d1ead9", "a-list pop"),
        ),
        "reggae & caribbean" to listOf(Kept("pl.93db219da1b547b6add3123f999a90ed", "breaking reggae"), Kept("pl.70e5370006504b09bff1119a281119f0", "all reggae"), Kept("37i9dQZF1DXbSbnqxMTGx9", "reggae classics", 20)),
        "blues" to listOf(Kept("pl.e21e2b411a484d90b37b13c8b5316a45", "blues replay"), Kept("37i9dQZF1DXd9rSDyQguIk", "blues classics")),
        "latin" to listOf(Kept("37i9dQZF1DX10zKzsJ2jva", "viva latino"), Kept("pl.4b364b8b182f4115acbf6deb83bd5222", "¡dale play!")),
        "brazilian" to listOf(Kept("pl.274159fd15df4693956a99940aedcac9", "breaking brazilian rock and pop"), Kept("pl.b95308a1e2d446b681360718b8ac4fa2", "pop hits brazil")),
        "african" to listOf(Kept("37i9dQZF1DWYkaDif7Ztbp", "african heat"), Kept("pl.a0794db8bc6f45888834fa708a674987", "africa now"), Kept("pl.dc349df19c6f410d874c197db63ecfed", "afrobeats hits")),
        "classical" to listOf(Kept("pl.9dc583e20e344cc4bf7dc823abde7a2c", "classical hits"), Kept("37i9dQZF1DWWEJlAGA9gs0", "classical essentials"), Kept("37i9dQZF1DX4sWSpwq3LiO", "peaceful piano")),
        "metal" to listOf(Kept("pl.4a5f397e3d334008be3ca6bdb62c6534", "breaking metal"), Kept("37i9dQZF1DWTcqUzwhNmKv", "kickass metal")),
        "country & americana" to listOf(Kept("37i9dQZF1DX1lVhptIYRda", "hot country"), Kept("pl.87bb5b36a9bd49db8c975607452bfa2b", "today's country"), Kept("pl.09d2969fa441483eba00a5ec41e279eb", "americana essentials")),
        "k-pop" to listOf(Kept("37i9dQZF1DX9tPFwDMOaN1", "k-pop on!"), Kept("pl.6a3c854a49a542739e5d57291b27e122", "k-pop essentials")),
        "j-pop" to listOf(Kept("pl.dc16cb58902342cba9711cbcd9bf2840", "j-pop now")),
    )

    /** Whether ink keeps lists for this genre (soundtracks, decades and the like keep only YouTube Music's). */
    fun known(genre: String) = BY.containsKey(genre.lowercase())

    /**
     * The genre's songs, its lists taken in turn (one each, so no one list fills it), the same song once; a list that
     * won't answer passed over. Blocking: a few pages.
     */
    fun songs(genre: String, now: Long, log: (String) -> Unit = {}): List<Curation.Pick> {
        val lists = BY[genre.lowercase()] ?: return emptyList()
        val each = lists.map { l ->
            runCatching { if (l.apple) apple(l.id, l.name, l.max, now) else SpotifyLists.list(l.id, l.name, l.max, now) }
                .onFailure { log("genre ${l.name}: $it") }.getOrDefault(emptyList())
        }
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { Curation.key(it) }
            .also { log("genre $genre: ${it.size} songs from ${each.count { l -> l.isNotEmpty() }} of ${lists.size} lists") }
    }

    /** An Apple Music list's songs, from its public page's own data (its songs' lockups: name, artist, cover). */
    fun apple(id: String, from: String, max: Int, now: Long): List<Curation.Pick> {
        val r = http("https://music.apple.com/us/playlist/$id", "GET", mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
            "Accept-Language" to "en",
        ), null, 8_000, 20_000)
        if (r.code >= 400) throw IllegalStateException("apple answered ${r.code}")
        val data = Regex("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(r.text)!!.groupValues[1]
        val out = ArrayList<Curation.Pick>()
        // {"data": [{"data": {"sections": [{"items": [...]}]}}]}
        val pages = JSONObject(data).optJSONArray("data")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        for (page in pages) {
            val sections = page.optJSONObject("data")?.optJSONArray("sections") ?: continue
            for (i in 0 until sections.length()) {
                val items = sections.optJSONObject(i)?.optJSONArray("items") ?: continue
                for (k in 0 until items.length()) {
                    val t = items.optJSONObject(k) ?: continue
                    val title = t.optString("title"); val artist = t.optString("artistName")
                    if (title.isBlank() || artist.isBlank() || !t.has("duration")) continue
                    val art = t.optJSONObject("artwork")?.optJSONObject("dictionary")?.optString("url")?.takeIf { it.isNotEmpty() }
                        ?.replace("{w}", "1200")?.replace("{h}", "1200")?.replace("{f}", "jpg")?.replace("{c}", "bb")
                    out += Curation.Pick(false, title.replace(Regex("\\s+-\\s+(From|Remaster).*$", RegexOption.IGNORE_CASE), ""),
                        artist.replace(" & ", ", "), from, "", "", now, art)
                    if (out.size >= max) return out
                }
            }
        }
        return out
    }
}
