package app.ink.music

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * YouTube Music's own API (InnerTube, as its web page speaks it: "WEB_REMIX"), by hand, for what
 * NewPipe doesn't do: your library, signed in. Signed in by music.youtube.com's cookies, each
 * request signed as the page signs its own (SAPISIDHASH; Metrolist's way, GPL-3.0). Answers are
 * read by renderer wherever they sit, not by path, so a shelf moved still reads. Plain JVM (tried
 * in a unit test); the full build only. Not affiliated with YouTube or Google.
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

    fun who(): TubeWho? = runCatching {
        val h = all(post("account/account_menu", JSONObject()), "activeAccountHeaderRenderer").firstOrNull() ?: return null
        TubeWho(text(h.optJSONObject("accountName")), text(h.optJSONObject("channelHandle")).ifEmpty { text(h.optJSONObject("email")) })
    }.getOrNull()

    /** A page's songs, every one (its continuations followed), up to [max]; [each] told the songs so far after every page. */
    fun tracks(browseId: String, max: Int = 2_000, each: ((List<Track>) -> Unit)? = null): List<Track> {
        val out = ArrayList<Track>()
        pages(browseId, max) { page ->
            all(page, "musicResponsiveListItemRenderer").mapNotNullTo(out, ::track)
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

    /** A mix's songs as its player queue (a mix may have no page of its own to browse). */
    fun queue(playlistId: String, max: Int = 50): List<Track> = runCatching {
        all(post("next", JSONObject().put("playlistId", playlistId).put("isAudioOnly", true)), "playlistPanelVideoRenderer").mapNotNull { r ->
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
    )

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
            top = all(page, "musicShelfRenderer").flatMap { all(it, "musicResponsiveListItemRenderer") }.mapNotNull(::track).distinctBy { it.videoId },
            albums = records.filter { r -> singles.none { it.id == r.id } },
            singles = singles,
            like = shelf("fans might also like", "fans also like", "similar").filter { it.kind == Kind.ARTIST },
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
        val cpn = (1..16).map { CPN[java.util.concurrent.ThreadLocalRandom.current().nextInt(CPN.length)] }.joinToString("")
        val c = URL(tracking.replace("https://s.youtube.com", BASE) + "&ver=2&c=WEB_REMIX&cpn=$cpn").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            headers(c)
            trace?.invoke("played $videoId: ${c.responseCode} from ${tracking.substringBefore('?')}")
            c.responseCode in 200..299
        } finally { c.disconnect() }
    }.onFailure { trace?.invoke("played $videoId: $it") }.getOrDefault(false)

    /** A record's page ("MPREb_..."), one read: its own playlist's id ("OLAK5uy_...") and its songs, in order. */
    fun album(browseId: String): Pair<String?, List<Track>> {
        val page = post("browse", JSONObject().put("browseId", browseId))
        return Regex("OLAK5uy_[A-Za-z0-9_-]+").find(page.toString())?.value to
            all(page, "musicResponsiveListItemRenderer").mapNotNull(::track).distinctBy { it.videoId }
    }

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
        val c = URL("$BASE/youtubei/v1/$endpoint?prettyPrint=false$query").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 20_000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers(c)
            val t0 = System.nanoTime()
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val t1 = System.nanoTime()
            if (code >= 400) throw IOException("youtube music answered $code: ${text.take(200)}")
            return JSONObject(text).also { trace?.invoke("$endpoint ${(t1 - t0) / 1_000_000} ms + read ${(System.nanoTime() - t1) / 1_000_000} ms, ${text.length / 1024} kB") }
        } finally { c.disconnect() }
    }

    /** As the web page asks: its client, your visitor id, and signed in, your cookies and the request signed. */
    private fun headers(c: HttpURLConnection) {
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Origin", BASE); c.setRequestProperty("X-Origin", BASE); c.setRequestProperty("Referer", "$BASE/")
        c.setRequestProperty("X-YouTube-Client-Name", "67"); c.setRequestProperty("X-YouTube-Client-Version", version)
        visitorData?.let { c.setRequestProperty("X-Goog-Visitor-Id", it) }
        if (sapisid != null) {
            c.setRequestProperty("Cookie", cookie)
            c.setRequestProperty("Authorization", sign(sapisid))
            c.setRequestProperty("X-Goog-AuthUser", "0")
        }
    }

    companion object {
        private const val CPN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        /** Each request's time and size, for the log (the app's: "ink-youtube"). */
        @Volatile var trace: ((String) -> Unit)? = null
        const val BASE = "https://music.youtube.com"
        /** The web client's version when none was read at sign-in (YouTube takes one a little old). */
        const val VERSION = "1.20251020.01.00"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        /** "SAPISIDHASH <time>_<sha1 of time, SAPISID and origin>", as the web page signs. */
        fun sign(sapisid: String, now: Long = System.currentTimeMillis() / 1000): String {
            val sha = MessageDigest.getInstance("SHA-1").digest("$now $sapisid $BASE".toByteArray()).joinToString("") { "%02x".format(it) }
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
