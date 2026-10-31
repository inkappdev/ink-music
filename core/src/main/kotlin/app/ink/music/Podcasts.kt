package app.ink.music

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Podcasts (Bronson, 2026-10-31: "can we add podcasts?"): a show is its RSS feed, an episode a file in it. Found through
 * Apple's directory (its search and its chart: free, no key, the feed's link in each), or a feed's link pasted. The
 * shows you follow, each show's episodes as last read, and where you stopped in each episode are kept as files in
 * [dir] (the app's: both the screens and the player read them, so files, never prefs). Plain Kotlin: the computer's
 * too, later.
 */
object Podcasts {
    /** Where it all's kept: each app sets it before anything else (`files/podcasts`). */
    @Volatile var dir: File? = null

    data class Show(
        val feed: String, val title: String, val author: String = "", val art: String? = null,
        val about: String = "", val link: String = "",
    ) {
        /** The show's own id: its episodes' albumId, the place its page is found by. */
        val id get() = "e:" + hash(feed)
    }

    data class Episode(
        val id: String, val feed: String, val title: String, val url: String, val mime: String = "",
        val length: Long = 0,        // ms; 0 when the feed doesn't say (the player says once it plays)
        val published: Long = 0,     // ms
        val notes: String = "",      // the show notes, as plain text
        val art: String? = null,     // the episode's own picture, else null (the show's then)
    )

    // ---- finding ----

    /** Shows by name, from Apple's directory. */
    fun search(term: String): List<Show> = runCatching {
        val q = URLEncoder.encode(term.trim(), "UTF-8")
        val a = JSONObject(get("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=$q")).optJSONArray("results") ?: JSONArray()
        (0 until a.length()).mapNotNull { show(a.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    /**
     * What's listened to most now: Apple's chart (ids only), their feeds looked up in one go. iTunes' own chart feed:
     * Apple's newer one (rss.applemarketingtools.com) answered 504 (2026-10-31).
     */
    fun top(country: String = "us", n: Int = 30): List<Show> = runCatching {
        val chart = JSONObject(get("https://itunes.apple.com/$country/rss/toppodcasts/limit=$n/json"))
            .optJSONObject("feed")?.optJSONArray("entry") ?: return emptyList()
        val ids = (0 until chart.length()).mapNotNull {
            chart.optJSONObject(it)?.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.takeIf(String::isNotEmpty)
        }
        val a = JSONObject(get("https://itunes.apple.com/lookup?id=${ids.joinToString(",")}")).optJSONArray("results") ?: JSONArray()
        val byId = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optLong("collectionId").toString() }
        ids.mapNotNull { byId[it]?.let(::show) }
    }.getOrDefault(emptyList())

    private fun show(o: JSONObject?): Show? {
        val feed = o?.optString("feedUrl")?.takeIf { it.startsWith("http") } ?: return null
        return Show(feed, o.optString("collectionName").ifEmpty { o.optString("trackName") }, o.optString("artistName"),
            o.optString("artworkUrl600").ifEmpty { o.optString("artworkUrl100") }.takeIf { it.isNotEmpty() })
    }

    // ---- a feed ----

    /** A show and its episodes, newest first, read from its feed now (and kept); null if it can't be read. */
    fun fetch(feed: String): Pair<Show, List<Episode>>? = runCatching {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // a feed is someone else's file: no entities fetched or expanded from it
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(bytes(feed).inputStream())
        val channel = doc.getElementsByTagName("channel").item(0) as? Element ?: return null
        val showArt = child(channel, "image")?.getAttribute("href")?.takeIf { it.isNotEmpty() }
            ?: child(channel, "image")?.let { child(it, "url")?.textContent?.trim() }
        val show = Show(
            feed, text(channel, "title"), text(channel, "author").ifEmpty { text(channel, "owner") }, showArt,
            plain(text(channel, "summary").ifEmpty { text(channel, "description") }), text(channel, "link"),
        )
        val items = channel.getElementsByTagName("item")
        val episodes = (0 until items.length).mapNotNull { i ->
            val it = items.item(i) as? Element ?: return@mapNotNull null
            val enc = child(it, "enclosure") ?: return@mapNotNull null
            val url = enc.getAttribute("url").takeIf { u -> u.startsWith("http") } ?: return@mapNotNull null
            val guid = text(it, "guid").ifEmpty { url }
            Episode(
                id = "e" + hash(feed + "\n" + guid).take(14), feed = feed, title = text(it, "title"), url = url,
                mime = enc.getAttribute("type"), length = duration(text(it, "duration")), published = date(text(it, "pubDate")),
                notes = plain(text(it, "encoded").ifEmpty { text(it, "description") }.ifEmpty { text(it, "summary") }),
                art = child(it, "image")?.getAttribute("href")?.takeIf { a -> a.isNotEmpty() },
            )
        }.sortedByDescending { it.published }
        keep(show, episodes)
        show to episodes
    }.getOrNull()

    /** A show as last read (its episodes too), without the network. */
    fun kept(feed: String): Pair<Show, List<Episode>>? = synchronized(this) {
        cache[feed]?.let { return it }
        val f = dir?.let { File(it, "shows/${hash(feed)}.json") }?.takeIf { it.isFile } ?: return null
        runCatching {
            val o = JSONObject(f.readText())
            val show = showOf(o.getJSONObject("show"))
            val a = o.getJSONArray("episodes")
            (show to (0 until a.length()).map { episodeOf(a.getJSONObject(it), feed) }).also { cache[feed] = it }
        }.getOrNull()
    }

    private val cache = HashMap<String, Pair<Show, List<Episode>>>()
    private val episodes = HashMap<String, Pair<Show, Episode>>()

    private fun keep(show: Show, list: List<Episode>) = synchronized(this) {
        cache[show.feed] = show to list
        list.forEach { episodes[it.id] = show to it }
        val d = dir ?: return
        // the newest 300 kept (a show twenty years on has thousands: Rogan's 2,773)
        val o = JSONObject().put("show", showJson(show)).put("episodes", JSONArray(list.take(300).map(::episodeJson)))
        write(File(d, "shows/${hash(show.feed)}.json"), o.toString())
    }

    /** An episode by its id, and its show: from the shows read this run, else every show kept. */
    fun episode(id: String): Pair<Show, Episode>? = synchronized(this) {
        episodes[id]?.let { return it }
        dir?.let { File(it, "shows") }?.listFiles()?.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val show = showOf(o.getJSONObject("show"))
                if (show.feed !in cache) {
                    val a = o.getJSONArray("episodes")
                    val list = (0 until a.length()).map { episodeOf(a.getJSONObject(it), show.feed) }
                    cache[show.feed] = show to list
                    list.forEach { e -> episodes[e.id] = show to e }
                }
            }
        }
        episodes[id]
    }

    // ---- following ----

    fun following(): List<Show> = runCatching {
        val a = JSONArray(File(dir ?: return emptyList(), "following.json").readText())
        (0 until a.length()).map { showOf(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun follows(feed: String) = following().any { it.feed == feed }

    fun follow(show: Show, on: Boolean) = synchronized(this) {
        val d = dir ?: return
        val now = following().filter { it.feed != show.feed }.let { if (on) listOf(show) + it else it }
        write(File(d, "following.json"), JSONArray(now.map(::showJson)).toString())
    }

    /** The newest episodes of the shows you follow (the last `days`), not yet heard through, newest first. */
    fun fresh(days: Int = 14, n: Int = 20): List<Pair<Show, Episode>> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val places = places()
        return following().flatMap { s -> kept(s.feed)?.let { (show, list) -> list.filter { it.published > since }.map { show to it } }.orEmpty() }
            .filter { (places[it.second.id]?.first ?: 0L) >= 0L }
            .sortedByDescending { it.second.published }.take(n)
    }

    /** Every show followed read again, those last read longer ago than `older` ms. */
    fun refresh(older: Long = 3_600_000L) {
        val d = dir ?: return
        following().forEach { s ->
            val f = File(d, "shows/${hash(s.feed)}.json")
            if (!f.isFile || System.currentTimeMillis() - f.lastModified() > older) fetch(s.feed)
        }
    }

    // ---- where you stopped ----

    /** Each episode's place: (ms, when said); -1 heard through. */
    fun places(): Map<String, Pair<Long, Long>> = synchronized(this) {
        runCatching {
            val o = JSONObject(File(dir ?: return emptyMap(), "places.json").readText())
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { it.getLong(0) to it.optLong(1) } }
        }.getOrDefault(emptyMap())
    }

    /** Where `id` was stopped (ms), 0 never begun, -1 heard through. */
    fun place(id: String): Long = places()[id]?.first ?: 0L

    fun setPlace(id: String, ms: Long) = synchronized(this) {
        val d = dir ?: return
        val o = runCatching { JSONObject(File(d, "places.json").readText()) }.getOrDefault(JSONObject())
        o.put(id, JSONArray().put(ms).put(System.currentTimeMillis()))
        // the last thousand kept
        if (o.length() > 1000) o.keys().asSequence().toList()
            .sortedBy { o.getJSONArray(it).optLong(1) }.take(o.length() - 1000).forEach { o.remove(it) }
        write(File(d, "places.json"), o.toString())
    }

    /** How fast episodes play (1 the way it was made). */
    var speed: Float
        get() = runCatching { File(dir!!, "speed").readText().trim().toFloat() }.getOrDefault(1f).coerceIn(.5f, 3f)
        set(v) { dir?.let { write(File(it, "speed"), v.toString()) } }

    // ---- the files ----

    fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val t = File(f.parentFile, f.name + ".new")
        t.writeText(text)
        if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
    }

    private fun showJson(s: Show) = JSONObject().put("feed", s.feed).put("title", s.title).put("author", s.author)
        .put("art", s.art ?: "").put("about", s.about).put("link", s.link)
    private fun showOf(o: JSONObject) = Show(o.getString("feed"), o.optString("title"), o.optString("author"),
        o.optString("art").takeIf { it.isNotEmpty() }, o.optString("about"), o.optString("link"))
    private fun episodeJson(e: Episode) = JSONObject().put("id", e.id).put("title", e.title).put("url", e.url).put("mime", e.mime)
        .put("length", e.length).put("published", e.published).put("notes", e.notes).put("art", e.art ?: "")
    private fun episodeOf(o: JSONObject, feed: String) = Episode(o.getString("id"), feed, o.optString("title"), o.getString("url"),
        o.optString("mime"), o.optLong("length"), o.optLong("published"), o.optString("notes"), o.optString("art").takeIf { it.isNotEmpty() })

    // ---- reading a feed ----

    /** An element's first child of that name, any namespace (itunes:image, image). */
    private fun child(e: Element, name: String): Element? {
        val kids = e.childNodes
        for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { if ((it.localName ?: it.tagName.substringAfter(':')) == name) return it }
        return null
    }
    private fun text(e: Element, name: String) = child(e, name)?.textContent?.trim().orEmpty()

    /** "1:02:03", "62:03" or seconds, as ms. */
    private fun duration(s: String): Long {
        if (s.isEmpty()) return 0
        val parts = s.split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
        return (parts.fold(0.0) { a, p -> a * 60 + p } * 1000).toLong()
    }

    private fun date(s: String): Long = runCatching {
        java.time.ZonedDateTime.parse(s.trim().replace(Regex("\\s+"), " "), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrElse {
        // "Mon, 3 Nov 2026 10:00:00 EST" and other zones RFC 1123's parser doesn't know: the zone dropped
        runCatching {
            val bare = s.trim().split(Regex("\\s+")).take(5).joinToString(" ")
            java.time.LocalDateTime.parse(bare, java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss", java.util.Locale.ENGLISH))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(0L)
    }

    /** Show notes' HTML as plain text: paragraphs kept, links' words kept, tags and entities gone. */
    fun plain(html: String): String {
        var t = html.replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</h\\d>"), "\n").replace(Regex("(?i)<li[^>]*>"), "\n· ")
        t = t.replace(Regex("<[^>]+>"), "")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: "" }
        return t.lines().map { it.trim().replace(Regex("\\s+"), " ") }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun get(url: String) = String(bytes(url))

    /** A link's bytes, its redirects followed (feeds move: http to https, one host to another). */
    private fun bytes(url: String): ByteArray {
        var at = URL(url)
        repeat(6) {
            val c = at.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENT)
            try {
                if (c.responseCode in 300..399) { at = URL(at, c.getHeaderField("Location") ?: error("redirect nowhere")); return@repeat }
                if (c.responseCode !in 200..299) error("${c.responseCode} $url")
                return c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
        error("too many redirects: $url")
    }
}
