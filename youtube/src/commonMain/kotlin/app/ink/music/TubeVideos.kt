package app.ink.music

/**
 * New music videos by an artist, signed out (measured 2026-11-07 on thirty artists: 29 found, about four real videos a
 * week between them). YouTube Music's videos shelf has no dates, and a channel's uploads feed failed for most, so: the
 * channel that uploaded the videos on their YouTube Music page, then that channel's Videos tab on youtube.com, newest
 * first, each "3 weeks ago". YouTube's own label for a video ("official music video") is on visualizers, lyric videos
 * and whole-record streams too, and missing from a small artist's real ones, so a video is told by its title and length.
 * Shared (the computer now; the phone's full build later). Not affiliated with YouTube or Google.
 */
object TubeVideos {
    /** The channel an artist's videos come from: its id and name, and whether it's theirs (else a label's, and theirs are picked out by name). */
    data class Channel(val id: String, val name: String, val own: Boolean)

    /** One upload: its video, title as it's written, about how many days ago, and its length (0: not said). */
    data class Upload(val id: String, val title: String, val days: Int, val seconds: Int)

    /**
     * Where their new videos are: their own channel, and another uploader on their page (a label's, a song with
     * someone else on that one's: Toro y Moi's "Motive" is Flume's), where theirs are picked out by name. At most two.
     */
    fun channelsOf(artist: String, browseId: String): List<Channel> = runCatching {
        val page = post(MUSIC, "browse", remix(), JSONObject().put("browseId", browseId))
        val shelf = InnerTube.all(page, "musicCarouselShelfRenderer").firstOrNull { s ->
            InnerTube.all(s, "musicCarouselShelfBasicHeaderRenderer").firstOrNull()?.let { InnerTube.text(it.optJSONObject("title")) }
                ?.lowercase()?.contains("video") == true
        } ?: return emptyList()
        val vids = InnerTube.all(shelf, "watchEndpoint").mapNotNull { it.optString("videoId").takeIf(String::isNotEmpty) }.distinct().take(4)
        val seen = vids.mapNotNull { v ->
            runCatching { post(MUSIC, "player", remix(), JSONObject().put("videoId", v)).optJSONObject("videoDetails") }.getOrNull()?.let { d ->
                val id = d.optString("channelId").takeIf { it.startsWith("UC") } ?: return@let null
                val name = d.optString("author").removeSuffix(" - Topic")
                Channel(id, name, plain(name).let { n -> n.isNotEmpty() && (n.contains(plain(artist)) || plain(artist).contains(n)) })
            }
        }
        val counts = seen.groupingBy { it }.eachCount()
        (listOfNotNull(seen.firstOrNull { it.own }) + counts.keys.filter { !it.own }.sortedByDescending { counts[it] }).distinctBy { it.id }.take(2)
    }.getOrDefault(emptyList())

    /**
     * A channel's Videos tab, newest first, read as youtube.com's own page reads it: `pages` of it (about thirty each),
     * each further page asked as the page asks for more when it's scrolled to its foot (its continuation). A page that
     * fails ends it with what's been read.
     */
    fun uploads(channel: String, pages: Int = 1): List<Upload> {
        val web = JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", WEB_VERSION).put("hl", "en").put("gl", "US"))
        val out = LinkedHashMap<String, Upload>()
        var body = JSONObject().put("browseId", channel).put("params", VIDEOS_TAB)
        for (i in 0 until pages.coerceAtLeast(1)) {
            val page = runCatching { post(WEB, "browse", web, body) }.getOrNull() ?: break
            read(page).forEach { out.putIfAbsent(it.id, it) }
            // the grid's last item is what asks for more (the first continuationItemRenderer on the page): its token, if there's more. Not any
            // continuation on the page: the sort chips (latest, popular, oldest) have theirs too, and the last of those
            // answered the channel's front page, no videos (2026-11-12)
            val more = InnerTube.all(page, "continuationItemRenderer").firstOrNull()
                ?.let { InnerTube.all(it, "continuationCommand").firstOrNull() }?.optString("token")?.ifEmpty { null } ?: break
            body = JSONObject().put("continuation", more)
        }
        return out.values.toList()
    }

    /** One page of a Videos tab's uploads (the first, or one asked for more). */
    private fun read(tab: JSONObject): List<Upload> = runCatching {
        // the old row (videoRenderer) or the new one (a lockup), whichever this answer has
        val old = InnerTube.all(tab, "videoRenderer").mapNotNull { v ->
            val id = v.optString("videoId").ifEmpty { return@mapNotNull null }
            Upload(id, InnerTube.text(v.optJSONObject("title")), days(InnerTube.text(v.optJSONObject("publishedTimeText"))),
                seconds(InnerTube.text(v.optJSONObject("lengthText"))))
        }
        val new = InnerTube.all(tab, "lockupViewModel").mapNotNull { l ->
            val id = l.optString("contentId").ifEmpty { return@mapNotNull null }
            val meta = InnerTube.all(l, "lockupMetadataViewModel").firstOrNull() ?: return@mapNotNull null
            val said = meta.toString()
            val length = InnerTube.all(l, "thumbnailBadgeViewModel").firstNotNullOfOrNull { b -> b.optString("text").takeIf { TIME.matches(it) } }.orEmpty()
            Upload(id, meta.optJSONObject("title")?.optString("content").orEmpty(), AGO.find(said)?.value?.let(::days) ?: NEVER, seconds(length))
        }
        (old + new).distinctBy { it.id }
    }.getOrDefault(emptyList())

    /**
     * Whether an upload is a new music video of [artist]'s: not a lyric video, visualizer, the song's audio, a whole
     * record, a teaser, a live take or a talk; a song's length (75 s to 12 min, when said); their name in its title.
     */
    fun isVideo(u: Upload, artist: String, channel: Channel): Boolean {
        val t = u.title.lowercase()
        if (NOT.any { it in t } || LIVE.containsMatchIn(t)) return false
        if (u.seconds != 0 && u.seconds !in 75..720) return false
        // their name in it, on their own channel too: every real one has it ("Fontaines D.C. - Japan"), while a channel of
        // sessions by others (Blogothèque) or someone else's under their name (Mk.gee's) doesn't
        return plain(u.title).contains(plain(artist))
    }

    private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun days(ago: String): Int {
        val m = Regex("""(\d+) (second|minute|hour|day|week|month|year)s? ago""").find(ago) ?: return NEVER
        val n = m.groupValues[1].toInt()
        return when (m.groupValues[2]) { "second", "minute", "hour" -> 0; "day" -> n; "week" -> n * 7; "month" -> n * 30; else -> n * 365 }
    }

    private fun seconds(s: String) = if (!TIME.matches(s.trim())) 0 else s.trim().split(':').fold(0) { a, p -> a * 60 + (p.toIntOrNull() ?: 0) }

    private fun remix() = JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", InnerTube.VERSION).put("hl", "en").put("gl", "US"))

    private fun post(base: String, endpoint: String, context: JSONObject, body: JSONObject): JSONObject {
        body.put("context", context)
        val r = http("$base/youtubei/v1/$endpoint?prettyPrint=false", "POST",
            mapOf("User-Agent" to UA, "Origin" to base, "Referer" to "$base/", "Content-Type" to "application/json"),
            body.toString().encodeToByteArray(), 10_000, 15_000)
        if (r.code >= 400) throw IllegalStateException("youtube answered ${r.code}")
        return JSONObject(r.text)
    }

    private const val NEVER = 9_999
    private const val MUSIC = "https://music.youtube.com"
    private const val WEB = "https://www.youtube.com"
    private const val WEB_VERSION = "2.20251020.01.00"
    // the channel page's Videos tab, as youtube.com asks for it
    private const val VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private val TIME = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
    private val AGO = Regex("""\d+ (second|minute|hour|day|week|month|year)s? ago""")
    private val NOT = listOf(
        "lyric", "visuali", "official audio", "(audio)", "[audio]", "audio only", "full album", "album stream", "full ep",
        "teaser", "trailer", "behind the", "episode", "extended scene", "making of", "interview", "documentary", "#shorts", "reaction", "session",
        "tour", "announce", "podcast", "q&a", "commentary", "track by track", "snippet", "preview", "unboxing", "tiny desk",
    )
    private val LIVE = Regex("""(\blive (at|from|on|in|for|with)\b|[(\[]live|- live\b|\blive[)\]])""")
}
