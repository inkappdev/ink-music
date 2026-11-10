package app.ink.music

import kotlin.concurrent.Volatile

/**
 * YouTube Music's own API (InnerTube, as its web page speaks it: "WEB_REMIX"), by hand, for what
 * NewPipe doesn't do: your library, signed in. Signed in by music.youtube.com's cookies, each
 * request signed as the page signs its own (SAPISIDHASH; Metrolist's way, GPL-3.0). Answers are
 * read by renderer wherever they sit, not by path, so a shelf moved still reads. Shared (the phone's full
 * build, the computer, the iPhone; tried in a unit test); never in the play build. Not affiliated with YouTube or Google.
 */
class InnerTube(
    private val cookie: String? = null,
    private val visitorData: String? = null,
    private val dataSyncId: String? = null,
    private val version: String = VERSION,
) {
    /** A song as YouTube Music lists it. */
    data class Track(
        val videoId: String, val title: String, val artists: List<String>,
        val album: String?, val albumId: String?, val seconds: Int, val art: String?,
    )

    /** A record, playlist or artist as a tile or a row: its browse id ("MPREb_", "VL", "UC"). */
    data class Item(val id: String, val title: String, val subtitle: String, val art: String?, val kind: Kind)

    enum class Kind { RECORD, PLAYLIST, ARTIST, OTHER }

    /** One of the home page's shelves: its title, its tiles. */
    data class Shelf(val title: String, val items: List<Item>)

    val signedIn get() = sapisid != null

    private val sapisid = cookie?.split(';')?.map { it.trim() }?.let { parts ->
        (parts.firstOrNull { it.startsWith("SAPISID=") } ?: parts.firstOrNull { it.startsWith("__Secure-3PAPISID=") })?.substringAfter('=')
    }

    // a brand account's id rides along as onBehalfOfUser; if YouTube refuses it, asked again without
    @Volatile private var onBehalf = !dataSyncId.isNullOrBlank()

    /** Who's signed in: their name, and their handle or email. */
    data class Who(val name: String, val handle: String)

    fun who(): Who? = runCatching {
        val h = all(post("account/account_menu", JSONObject()), "activeAccountHeaderRenderer").firstOrNull() ?: return null
        Who(text(h.optJSONObject("accountName")), text(h.optJSONObject("channelHandle")).ifEmpty { text(h.optJSONObject("email")) })
    }.getOrNull()

    /** A page's songs, every one (its continuations followed), up to [max]; [each] told the songs so far after every page. */
    fun tracks(browseId: String, max: Int = 2_000, each: ((List<Track>) -> Unit)? = null): List<Track> {
        val out = ArrayList<Track>()
        pages(browseId, max) { page ->
            // your own playlist's last page is YouTube Music's "Suggestions" shelf, songs it picks: not the list's
            val suggested = all(page, "musicShelfRenderer").filter { text(it.optJSONObject("title")) == "Suggestions" }
                .flatMap { all(it, "musicResponsiveListItemRenderer") }.toSet()
            all(page, "musicResponsiveListItemRenderer").filter { it !in suggested }.mapNotNullTo(out, ::track)
            each?.invoke(out.distinctBy { it.videoId }.take(max))
            out.size
        }
        return out.distinctBy { it.videoId }.take(max)
    }

    /** A page's records, playlists or artists (tiles or rows), every one. */
    fun items(browseId: String, max: Int = 500): List<Item> {
        val out = ArrayList<Item>()
        pages(browseId, max) { page ->
            all(page, "musicTwoRowItemRenderer").mapNotNullTo(out, ::tile)
            all(page, "musicResponsiveListItemRenderer").mapNotNullTo(out, ::row)
            out.size
        }
        return out.distinctBy { it.id }.take(max)
    }

    /** Your home on YouTube Music (signed in, it's yours): its shelves, a few pages of them. */
    fun home(max: Int = 30): List<Shelf> {
        val out = ArrayList<Shelf>()
        pages("FEmusic_home", max) { page ->
            all(page, "musicCarouselShelfRenderer").forEach { shelf ->
                val title = all(shelf, "musicCarouselShelfBasicHeaderRenderer").firstOrNull()?.let { text(it.optJSONObject("title")) }.orEmpty()
                out += Shelf(title, all(shelf, "musicTwoRowItemRenderer").mapNotNull(::tile))
            }
            out.size
        }
        return out
    }

    /**
     * A search of YouTube Music, as its own search box asks (2026-11-07, the iPhone, which has no NewPipe): its songs,
     * or its records, artists or playlists ([kind]), by its filters (the same `params` its page sends).
     */
    fun searchSongs(q: String, max: Int = 30): List<Track> = runCatching {
        all(post("search", JSONObject().put("query", q).put("params", SEARCH_SONGS)), "musicResponsiveListItemRenderer")
            .mapNotNull(::track).distinctBy { it.videoId }.take(max)
    }.getOrDefault(emptyList())

    fun searchItems(q: String, kind: Kind, max: Int = 20): List<Item> = runCatching {
        val params = when (kind) { Kind.RECORD -> SEARCH_RECORDS; Kind.ARTIST -> SEARCH_ARTISTS; else -> SEARCH_PLAYLISTS }
        all(post("search", JSONObject().put("query", q).put("params", params)), "musicResponsiveListItemRenderer")
            .mapNotNull(::row).filter { it.kind == kind }.distinctBy { it.id }.take(max)
    }.getOrDefault(emptyList())

    /** A mood or a genre on YouTube Music's own page of them (its `params` open it). */
    data class Mood(val title: String, val params: String, val genre: Boolean)

    /** YouTube Music's moods & moments and genres (chill, focus, workout...; african to soundtracks), signed out the same. */
    fun moods(): List<Mood> = runCatching {
        val page = post("browse", JSONObject().put("browseId", "FEmusic_moods_and_genres"))
        all(page, "gridRenderer").flatMap { grid ->
            val head = all(grid.optJSONObject("header"), "gridHeaderRenderer").firstOrNull()?.let { text(it.optJSONObject("title")) }.orEmpty()
            all(grid, "musicNavigationButtonRenderer").mapNotNull { b ->
                val params = b.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint")?.optString("params")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                Mood(text(b.optJSONObject("buttonText")), params, genre = head.lowercase().startsWith("genre"))
            }
        }.distinctBy { it.title }
    }.getOrDefault(emptyList())

    /** A mood's (or genre's) flavors: shelves of YouTube Music's playlists for it ("coffee shop blends", "chilled"...). */
    fun mood(params: String): List<Shelf> = runCatching {
        val page = post("browse", JSONObject().put("browseId", "FEmusic_moods_and_genres_category").put("params", params))
        (all(page, "musicCarouselShelfRenderer") + all(page, "gridRenderer")).mapNotNull { shelf ->
            val head = (all(shelf.optJSONObject("header"), "musicCarouselShelfBasicHeaderRenderer") + all(shelf.optJSONObject("header"), "gridHeaderRenderer"))
                .firstOrNull()?.let { text(it.optJSONObject("title")) }.orEmpty()
            val items = all(shelf, "musicTwoRowItemRenderer").mapNotNull(::tile)
            if (items.isEmpty()) null else Shelf(head, items)
        }
    }.getOrDefault(emptyList())

    /**
     * A mix's songs as its player queue (a mix may have no page of its own to browse); with [videoId], the radio
     * from that song ("RDAMVM<id>"), as you if signed in (YouTube builds it from what you listen to).
     */
    fun queue(playlistId: String, max: Int = 50, videoId: String? = null): List<Track> = runCatching {
        val body = JSONObject().put("playlistId", playlistId).put("isAudioOnly", true).apply { videoId?.let { put("videoId", it) } }
        all(post("next", body), "playlistPanelVideoRenderer").mapNotNull { r ->
            val id = r.optString("videoId").ifEmpty { return@mapNotNull null }
            val by = runs(r.optJSONObject("longBylineText"))
            val artists = by.filter { kind(pageType(browse(it))) == Kind.ARTIST }.map { it.optString("text") }
                .ifEmpty { listOfNotNull(by.firstOrNull()?.optString("text")?.trim()?.takeIf { it.isNotEmpty() }) }
            val album = by.firstOrNull { kind(pageType(browse(it))) == Kind.RECORD }
            Track(id, text(r.optJSONObject("title")), artists, album?.optString("text"), browse(album)?.optString("browseId"),
                text(r.optJSONObject("lengthText")).let { if (TIME.matches(it)) seconds(it) else 0 }, art(r.optJSONObject("thumbnail")))
        }.distinctBy { it.videoId }.take(max)
    }.getOrDefault(emptyList())

    /** An artist's records with their subtitles ("Single • 2026"), newest first as the page has them. */
    fun records(browseId: String): List<Item> =
        all(post("browse", JSONObject().put("browseId", browseId)), "musicTwoRowItemRenderer").mapNotNull(::tile)
            .filter { it.kind == Kind.RECORD }.distinctBy { it.id }

    /** An artist's page as YouTube Music has it: who they are, how many listen, their best-known songs, records, and who's like them. */
    data class Artist(
        val name: String, val photo: String?, val about: String, val audience: String,
        val top: List<Track>, val albums: List<Item>, val singles: List<Item>, val like: List<Item>,
        /** The playlists they're on ("featured on": YouTube Music's own, mostly), 2026-11-08. */
        val featured: List<Item> = emptyList(),
        /** The shelves' titles as the page had them (for telling a new one apart). */
        val shelves: List<String> = emptyList(),
    )

    /**
     * An artist's top songs: the page's shelf holds five; the rest from its own list (its title's and "see all"'s link,
     * one read more) when that's fewer than six, so a page shows six, three a column (2026-11-09: five sat 3 and 2).
     */
    private fun top(page: JSONObject): List<Track> {
        val shelf = all(page, "musicShelfRenderer").firstOrNull()
        val first = all(page, "musicShelfRenderer").flatMap { all(it, "musicResponsiveListItemRenderer") }.mapNotNull(::track).distinctBy { it.videoId }
        if (first.size >= 6 || shelf == null) return first
        val more = all(shelf, "browseEndpoint").map { it.optString("browseId") }.firstOrNull { it.startsWith("VL") }
            ?.let { runCatching { tracks(it, 12) }.getOrDefault(emptyList()) }.orEmpty()
        return (first + more).distinctBy { it.videoId }
    }

    /** An artist's page, one read: the header (photo, bio, monthly audience) and its shelves, told apart by their titles. */
    fun artist(browseId: String): Artist {
        val page = post("browse", JSONObject().put("browseId", browseId))
        val head = all(page, "musicImmersiveHeaderRenderer").plus(all(page, "musicVisualHeaderRenderer")).firstOrNull()
        val shelves = all(page, "musicCarouselShelfRenderer").map { shelf ->
            all(shelf, "musicCarouselShelfBasicHeaderRenderer").firstOrNull()?.let { text(it.optJSONObject("title")) }.orEmpty().lowercase() to
                all(shelf, "musicTwoRowItemRenderer").mapNotNull(::tile)
        }
        fun shelf(vararg titles: String) = shelves.filter { (t, _) -> titles.any { t.startsWith(it) } }.flatMap { it.second }.distinctBy { it.id }
        val records = shelves.flatMap { it.second }.filter { it.kind == Kind.RECORD }.distinctBy { it.id }
        val singles = shelf("singles", "ep").filter { it.kind == Kind.RECORD }
        return Artist(
            name = text(head?.optJSONObject("title")),
            // the wide photo (2880 x 1200 or so), as it is: square ones are cut from its middle where it's shown
            photo = biggest(head?.optJSONObject("thumbnail")),
            about = text(head?.optJSONObject("description")).trim(),
            audience = text(head?.optJSONObject("monthlyListenerCount")),
            top = top(page),
            albums = records.filter { r -> singles.none { it.id == r.id } },
            singles = singles,
            like = shelf("fans might also like", "fans also like", "similar").filter { it.kind == Kind.ARTIST },
            featured = shelf("featured on", "playlists").filter { it.kind == Kind.PLAYLIST },
            shelves = shelves.map { it.first },
        )
    }

    /** A song liked on YouTube Music, or not any more (signed in): did it take? */
    fun like(videoId: String, on: Boolean): Boolean = runCatching {
        post(if (on) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
        true
    }.getOrDefault(false)

    /**
     * A song played, told to YouTube Music (signed in: it goes in your history there, and its mixes learn from it),
     * as its own page does: the player's playback-tracking link, called back with a new playback id. Did it take?
     */
    fun played(videoId: String): Boolean = runCatching {
        val answer = post("player", JSONObject().put("videoId", videoId))
        val tracking = all(answer, "videostatsPlaybackUrl").firstOrNull()?.optString("baseUrl")?.takeIf { it.isNotEmpty() }
            ?: return false.also { trace?.invoke("played $videoId: no tracking link (${answer.optJSONObject("playabilityStatus")?.let { it.optString("status") + " " + it.optString("reason") }})") }
        val cpn = (1..16).map { CPN[kotlin.random.Random.nextInt(CPN.length)] }.joinToString("")
        val code = http(tracking.replace("https://s.youtube.com", BASE) + "&ver=2&c=WEB_REMIX&cpn=$cpn", headers = headers(), connectMs = 15_000, readMs = 20_000).code
        trace?.invoke("played $videoId: $code from ${tracking.substringBefore('?')}")
        code in 200..299
    }.onFailure { trace?.invoke("played $videoId: $it") }.getOrDefault(false)

    /**
     * A song's lyrics, timed (ms it starts, its words): its lyrics page found from its player queue, then read as
     * YouTube Music's Android app reads it, the one that's given the times (Musixmatch's). A song upload's are
     * timed; a music video's mostly aren't. Empty for none, or none timed.
     */
    fun lyrics(videoId: String): List<Pair<Long, String>> {
        val id = all(post("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true)), "browseEndpoint")
            .map { it.optString("browseId") }.firstOrNull { it.startsWith("MPLY") } ?: return emptyList()
        val client = JSONObject().put("clientName", "ANDROID_MUSIC").put("clientVersion", "7.27.52")
            .put("androidSdkVersion", 34).put("hl", "en").put("gl", "US")
        val r = http("$BASE/youtubei/v1/browse?prettyPrint=false", "POST",
            mapOf("Content-Type" to "application/json", "User-Agent" to "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14) gzip"),
            JSONObject().put("browseId", id).put("context", JSONObject().put("client", client)).toString().encodeToByteArray(), 15_000, 20_000)
        if (r.code >= 400) return emptyList()
        val page = JSONObject(r.text)
        val lines = all(page, "lyricsData").firstOrNull()?.optJSONArray("timedLyricsData") ?: return emptyList()
        return (0 until lines.length()).mapNotNull { i ->
            val l = lines.optJSONObject(i) ?: return@mapNotNull null
            val at = l.optJSONObject("cueRange")?.optString("startTimeMilliseconds")?.toLongOrNull() ?: return emptyList()   // untimed: none
            at to l.optString("lyricLine")
        }
    }

    /**
     * What YouTube Music's own page carries, signed in: your account's id ("<id>||": a signed-in streaming poToken
     * is bound to it; ytcfg's DATASYNC_ID, in no API answer) and its player's code's address ("/s/player/...").
     */
    fun page(): Pair<String?, String?> = runCatching {
        val page = http("$BASE/", headers = headers()).also { if (it.code >= 400) throw IOException("page ${it.code}") }.text
        Regex("\"DATASYNC_ID\":\"([^\"]+)\"").find(page)?.groupValues?.get(1) to
            Regex("\"jsUrl\":\"([^\"]+)\"").find(page)?.groupValues?.get(1)?.let { if (it.startsWith("http")) it else BASE + it }
    }.onFailure { trace?.invoke("page: $it") }.getOrDefault(null to null)

    /** What music.youtube.com's page says of this session: the account's id (signed in), the player's address, the visitor's id. */
    data class Session(val dataSyncId: String?, val playerUrl: String?, val visitorData: String?)

    fun session(): Session? = runCatching {
        val page = http("$BASE/", headers = headers()).also { if (it.code >= 400) throw IOException("page ${it.code}") }.text
        fun cfg(k: String) = Regex("\"$k\":\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        Session(cfg("DATASYNC_ID"), cfg("jsUrl")?.let { if (it.startsWith("http")) it else BASE + it },
            cfg("VISITOR_DATA")?.replace("\\u003d", "=")?.replace("%3D", "="))
    }.onFailure { trace?.invoke("session: $it") }.getOrNull()

    /** A player's code (a few megabytes of YouTube's own JavaScript), and the date it signs with ("signatureTimestamp"). */
    fun playerCode(url: String): Pair<String, Int?>? = runCatching {
        val code = http(url, headers = mapOf("User-Agent" to UA), readMs = 30_000).also { if (it.code >= 400) throw IOException("player ${it.code}") }.text
        code to Regex("(?:signatureTimestamp|sts):(\\d{5})").find(code)?.groupValues?.get(1)?.toIntOrNull()
    }.onFailure { trace?.invoke("player code: $it") }.getOrNull()

    /**
     * A song's player answer as YouTube Music's web player gets it, signed in (a Premium account is offered its
     * 256k AAC; your uploads play): the player's date [sts] (for its signatures) and the song's poToken.
     */
    fun player(videoId: String, sts: Int?, poToken: String?): JSONObject {
        val body = JSONObject().put("videoId", videoId).put("racyCheckOk", true).put("contentCheckOk", true)
        sts?.let { body.put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("signatureTimestamp", it))) }
        poToken?.let { body.put("serviceIntegrityDimensions", JSONObject().put("poToken", it)) }
        return post("player", body)
    }

    /**
     * A record's page ("MPREb_..."), one read: its own playlist's id ("OLAK5uy_...") and its songs, in order. A song
     * with a music video is listed as the video (its silent intro, its edit: a listener heard Deftones' "my mind is a
     * mountain" start 5 s late, 2026-11-01); its menu's song credits ("MPTC" and an id) name the record's own audio,
     * which YouTube Music itself plays there, so that's the song's.
     */
    fun album(browseId: String): Pair<String?, List<Track>> {
        val page = post("browse", JSONObject().put("browseId", browseId))
        return Regex("OLAK5uy_[A-Za-z0-9_-]+").find(page.toString())?.value to
            all(page, "musicResponsiveListItemRenderer").mapNotNull { r ->
                track(r)?.let { t -> AUDIO.find(r.optJSONObject("menu")?.toString().orEmpty())?.groupValues?.get(1)?.let { t.copy(videoId = it) } ?: t }
            }.distinctBy { it.videoId }
    }
    private val AUDIO = Regex("\"MPTC([A-Za-z0-9_-]{11})\"")

    // your library, written (signed in): each a yes if YouTube took it

    /** A record or a playlist saved to your library, or not any more (by its playlist id: a record's is "OLAK5uy_..."). */
    fun save(playlistId: String, on: Boolean): Boolean = runCatching {
        post(if (on) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("playlistId", playlistId))); true
    }.getOrDefault(false)

    /** A song added to the end of one of your playlists. */
    fun addTo(playlistId: String, videoId: String): Boolean = runCatching {
        val r = post("browse/edit_playlist", JSONObject().put("playlistId", playlistId)
            .put("actions", JSONArray().put(JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId))))
        r.optString("status").let { it.isEmpty() || it == "STATUS_SUCCEEDED" }
    }.getOrDefault(false)

    /** A new playlist of yours (private), with these songs: its id. */
    fun makePlaylist(title: String, videoIds: List<String>): String? = runCatching {
        post("playlist/create", JSONObject().put("title", title).put("privacyStatus", "PRIVATE").put("videoIds", JSONArray(videoIds)))
            .optString("playlistId").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Whether a playlist is yours (its page there is one you can edit: someone else's, a mix, isn't). */
    fun mine(playlistId: String): Boolean = runCatching {
        all(post("browse", JSONObject().put("browseId", "VL$playlistId")), "musicEditablePlaylistDetailHeaderRenderer").isNotEmpty()
    }.getOrDefault(false)

    /** One of your playlists deleted. */
    fun deletePlaylist(playlistId: String): Boolean = runCatching {
        post("playlist/delete", JSONObject().put("playlistId", playlistId)); true
    }.getOrDefault(false)

    /** An artist followed (their channel, "UC..."), or not any more. */
    fun follow(channelId: String, on: Boolean): Boolean = runCatching {
        post(if (on) "subscription/subscribe" else "subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(channelId))); true
    }.getOrDefault(false)

    /** A record's own playlist ("OLAK5uy_..."), which NewPipe opens, from its browse id ("MPREb_..."). */
    fun albumPlaylist(browseId: String): String? = runCatching {
        Regex("OLAK5uy_[A-Za-z0-9_-]+").find(post("browse", JSONObject().put("browseId", browseId)).toString())?.value
    }.getOrNull()

    /** Reads a page, then its continuations, while [took] (how many so far) is under [max]. */
    private fun pages(browseId: String, max: Int, took: (JSONObject) -> Int) {
        var page = post("browse", JSONObject().put("browseId", browseId))
        repeat(40) {
            if (took(page) >= max) return
            val next = all(page, "continuationItemRenderer").firstNotNullOfOrNull {
                it.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotEmpty)
            }
            val old = all(page, "nextContinuationData").firstNotNullOfOrNull { it.optString("continuation").takeIf(String::isNotEmpty) }
            page = when {
                next != null -> post("browse", JSONObject().put("continuation", next))
                old != null -> post("browse", JSONObject(), "&ctoken=$old&continuation=$old&type=next")
                else -> return
            }
        }
    }

    private fun post(endpoint: String, body: JSONObject, query: String = ""): JSONObject = try {
        request(endpoint, body, query, onBehalf)
    } catch (e: IOException) {
        if (!onBehalf) throw e
        onBehalf = false
        request(endpoint, body, query, false)
    }

    private fun request(endpoint: String, body: JSONObject, query: String, asBrand: Boolean): JSONObject {
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", version).put("hl", "en").put("gl", "US")
        visitorData?.let { client.put("visitorData", it) }
        val context = JSONObject().put("client", client)
        if (asBrand) context.put("user", JSONObject().put("onBehalfOfUser", dataSyncId))
        body.put("context", context)
        val t0 = nowMs()
        val r = http("$BASE/youtubei/v1/$endpoint?prettyPrint=false$query", "POST", headers() + ("Content-Type" to "application/json"),
            body.toString().encodeToByteArray(), 15_000, 20_000)
        val text = r.text
        if (r.code >= 400) throw IOException("youtube music answered ${r.code}: ${text.take(200)}")
        return JSONObject(text).also { trace?.invoke("$endpoint ${nowMs() - t0} ms, ${text.length / 1024} kB") }
    }

    /** As the web page asks: its client, your visitor id, and signed in, your cookies and the request signed. */
    private fun headers(): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Origin", BASE); put("X-Origin", BASE); put("Referer", "$BASE/")
        put("X-YouTube-Client-Name", "67"); put("X-YouTube-Client-Version", version)
        visitorData?.let { put("X-Goog-Visitor-Id", it) }
        if (sapisid != null) {
            put("Cookie", cookie!!)
            put("Authorization", sign(sapisid))
            put("X-Goog-AuthUser", "0")
        }
    }

    companion object {
        private const val CPN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        /** Each request's time and size, for the log (the app's: "ink-youtube"). */
        @Volatile var trace: ((String) -> Unit)? = null
        const val BASE = "https://music.youtube.com"
        // the search page's filters (songs, records, artists, community playlists), as it sends them
        private const val SEARCH_SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        private const val SEARCH_RECORDS = "EgWKAQIYAWoMEA4QChADEAQQCRAF"
        private const val SEARCH_ARTISTS = "EgWKAQIgAWoMEA4QChADEAQQCRAF"
        private const val SEARCH_PLAYLISTS = "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU="
        /** The web client's version when none was read at sign-in (YouTube takes one a little old). */
        const val VERSION = "1.20251020.01.00"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        /** "SAPISIDHASH <time>_<sha1 of time, SAPISID and origin>", as the web page signs. */
        fun sign(sapisid: String, now: Long = nowMs() / 1000): String {
            val sha = sha1("$now $sapisid $BASE".encodeToByteArray()).hex()
            return "SAPISIDHASH ${now}_$sha"
        }

        /** Every object under [key], anywhere in [root] (not looking inside one found). */
        fun all(root: Any?, key: String): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            fun walk(x: Any?) {
                when (x) {
                    is JSONObject -> for (k in x.keys()) {
                        val v = x.opt(k)
                        if (k == key && v is JSONObject) out += v else walk(v)
                    }
                    is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
                }
            }
            walk(root)
            return out
        }

        /** A text's runs as one string. */
        fun text(o: JSONObject?): String {
            o?.optString("simpleText")?.takeIf { it.isNotEmpty() }?.let { return it }
            val runs = o?.optJSONArray("runs") ?: return ""
            return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }

        private fun runs(o: JSONObject?): List<JSONObject> =
            o?.optJSONArray("runs")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

        private fun browse(o: JSONObject?) = o?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")

        private fun pageType(b: JSONObject?) = b?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()

        private fun kind(pageType: String) = when {
            pageType == "MUSIC_PAGE_TYPE_ALBUM" -> Kind.RECORD
            pageType == "MUSIC_PAGE_TYPE_PLAYLIST" -> Kind.PLAYLIST
            pageType.contains("ARTIST") || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" -> Kind.ARTIST
            else -> Kind.OTHER
        }

        /** The largest picture, square ones asked for at 600 px. */
        private fun art(o: JSONObject?): String? = biggest(o)?.replace(Regex("=w\\d+-h\\d+"), "=w600-h600")

        /** The largest picture under [o], as it is. */
        private fun biggest(o: JSONObject?): String? {
            var best: JSONObject? = null
            fun look(x: Any?) {
                when (x) {
                    is JSONObject -> x.keys().forEach { k ->
                        val v = x.opt(k)
                        if (k == "thumbnails" && v is JSONArray) {
                            for (i in 0 until v.length()) v.optJSONObject(i)?.let { t ->
                                if (best == null || t.optInt("width") * t.optInt("height") > best!!.optInt("width") * best!!.optInt("height")) best = t
                            }
                        } else look(v)
                    }
                    is JSONArray -> for (i in 0 until x.length()) look(x.opt(i))
                }
            }
            look(o)
            return best?.optString("url")?.takeIf { it.isNotEmpty() }
        }

        private val TIME = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

        private fun seconds(s: String) = s.split(':').fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }

        /** A song row: its video, title, artists (linked, else the second column's first words), record, length. */
        fun track(r: JSONObject): Track? {
            if (r.optString("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") return null
            val cols = r.optJSONArray("flexColumns")?.let { a ->
                (0 until a.length()).map { a.optJSONObject(it)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text") }
            }.orEmpty()
            val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotEmpty() }
                ?: all(cols.firstOrNull(), "watchEndpoint").firstOrNull()?.optString("videoId")?.takeIf { it.isNotEmpty() }
                ?: all(r.optJSONObject("overlay"), "watchEndpoint").firstOrNull()?.optString("videoId")?.takeIf { it.isNotEmpty() }
                ?: return null
            val title = text(cols.firstOrNull()).ifEmpty { return null }
            val rest = cols.drop(1).flatMap { runs(it) }
            val artists = ArrayList<String>()
            var album: String? = null; var albumId: String? = null; var length = 0
            for (run in rest) {
                val t = run.optString("text")
                val b = browse(run)
                when {
                    b != null && kind(pageType(b)) == Kind.ARTIST -> artists += t
                    b != null && kind(pageType(b)) == Kind.RECORD -> { album = t; albumId = b.optString("browseId") }
                    TIME.matches(t.trim()) -> length = seconds(t.trim())
                }
            }
            // an artist without a link (a video's uploader): the second column's first words
            if (artists.isEmpty()) runs(cols.getOrNull(1)).firstOrNull()?.optString("text")?.trim()
                ?.takeIf { it.isNotEmpty() && it != "Song" && it != "Video" && !TIME.matches(it) }?.let { artists += it }
            if (length == 0) runs(r.optJSONArray("fixedColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.optJSONObject("text")).firstOrNull()?.optString("text")?.trim()?.takeIf { TIME.matches(it) }?.let { length = seconds(it) }
            return Track(videoId, title, artists, album, albumId, length, art(r.optJSONObject("thumbnail")))
        }

        /** A tile: a record, playlist or artist; a mix that only plays (no page), as its playlist. */
        fun tile(r: JSONObject): Item? {
            val nav = r.optJSONObject("navigationEndpoint")
            val b = nav?.optJSONObject("browseEndpoint") ?: runs(r.optJSONObject("title")).firstNotNullOfOrNull { browse(it) }
            if (b == null) {
                val list = (nav?.optJSONObject("watchPlaylistEndpoint") ?: nav?.optJSONObject("watchEndpoint"))?.optString("playlistId").orEmpty()
                if (list.isEmpty()) return null
                return Item("VL$list", text(r.optJSONObject("title")), text(r.optJSONObject("subtitle")), art(r.optJSONObject("thumbnailRenderer")), Kind.PLAYLIST)
            }
            val id = b.optString("browseId").ifEmpty { return null }
            return Item(id, text(r.optJSONObject("title")), text(r.optJSONObject("subtitle")), art(r.optJSONObject("thumbnailRenderer")), kind(pageType(b)))
        }

        /** A row that opens a page (a library's artist), not a song. */
        fun row(r: JSONObject): Item? {
            val b = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return null
            val id = b.optString("browseId").ifEmpty { return null }
            val cols = r.optJSONArray("flexColumns")?.let { a ->
                (0 until a.length()).map { a.optJSONObject(it)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text") }
            }.orEmpty()
            return Item(id, text(cols.firstOrNull()), cols.drop(1).joinToString(" • ") { text(it) }, art(r.optJSONObject("thumbnail")), kind(pageType(b)))
        }
    }
}
