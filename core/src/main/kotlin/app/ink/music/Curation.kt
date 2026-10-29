package app.ink.music

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * What people pick, read from their own public feeds (Bronson, 2026-10-28: "as cool and curated as possible, even
 * signed out"): critics' records (Pitchfork's reviews, Stereogum's album of the week, Bandcamp Daily's album of the
 * day, NME's and the Quietus's reviews, Aquarium Drunkard), songs (Pitchfork's track reviews, Gorilla vs. Bear), and
 * what DJs are playing this minute on the stations ink radio keeps. Each a name and who picked it, with the line they
 * said where there is one; the apps find it on YouTube Music to play and its cover on iTunes. Plain Java, shared.
 */
object Curation {
    /** A record or a song someone picked: what, by whom, who picked it and when, what they said. */
    data class Pick(
        val record: Boolean, val title: String, val artist: String, val from: String,
        val said: String, val link: String, val at: Long, val art: String?,
    )

    /** A song on the air now: the station's key (ink radio's id), its name, the song and its cover. */
    data class Aired(val station: String, val name: String, val title: String, val artist: String, val art: String?)

    // ---- the critics' feeds ----

    private class Item(val title: String, val link: String, val at: Long, val text: String, val raw: String, val tags: List<String>, val thumb: String?)

    private fun items(url: String): List<Item> = runCatching {
        Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(get(url)).map { m ->
            val it = m.groupValues[1]
            fun tag(t: String) = Regex("<$t[^>]*>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)?.let(::cdata).orEmpty()
            val raw = tag("description")
            Item(
                title = plain(tag("title")), link = plain(tag("link")),
                at = runCatching { ZonedDateTime.parse(plain(tag("pubDate")), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0L),
                text = sentence(plain(raw)), raw = raw,
                tags = Regex("<category[^>]*>(.*?)</category>", RegexOption.DOT_MATCHES_ALL).findAll(it).map { c -> plain(cdata(c.groupValues[1])) }.toList(),
                thumb = Regex("""<media:thumbnail url="([^"]+)"""").find(it)?.groupValues?.get(1),
            )
        }.toList()
    }.getOrDefault(emptyList())

    private val QUOTES = "\"“”‘’'"

    /** Every pick from every feed, the newest first (a feed that won't answer is passed over). */
    fun picks(): List<Pick> = listOf(::pitchforkRecords, ::pitchforkSongs, ::stereogum, ::bandcamp, ::nme, ::quietus, ::aquarium, ::gorilla, ::npr, ::dusted)
        .flatMap { f -> runCatching { f() }.getOrDefault(emptyList()) }
        .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
        .sortedByDescending { it.at }

    /** The artist from Pitchfork's link (".../reviews/albums/the-olivia-tremor-control-the-same-place/"), the title's slug taken off its end. */
    private fun slugArtist(link: String, title: String): String {
        val slug = link.trimEnd('/').substringAfterLast('/')
        // as Pitchfork makes its slugs: apostrophes gone ("It’s" its), "&" and, a version in brackets left off
        val t = title.lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("[’']"), "").replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return slug.removeSuffix(t).trim('-').split('-').joinToString(" ")
    }

    private fun pitchforkRecords() = items("https://pitchfork.com/feed/feed-album-reviews/rss").map { i ->
        // the cover's own file name says it best: "The Olivia Tremor Control The Same Place.jpg"
        val file = i.thumb?.let { URLDecoder.decode(it.substringAfterLast('/'), "UTF-8").substringBeforeLast('.') }.orEmpty()
        val artist = file.takeIf { it.endsWith(i.title, true) }?.dropLast(i.title.length)?.trim(' ', '-', '–')?.ifEmpty { null } ?: slugArtist(i.link, i.title)
        Pick(true, i.title, artist, "pitchfork", i.text, i.link, i.at, i.thumb?.takeIf { file.isNotEmpty() })
    }

    private fun pitchforkSongs() = items("https://pitchfork.com/feed/feed-track-reviews/rss").map { i ->
        val title = i.title.substringBefore(" [").trim(*QUOTES.toCharArray())
        Pick(false, title, slugArtist(i.link, title), "pitchfork", i.text, i.link, i.at, null)
    }

    /** "Album Of The Week: DIIV ZIRP!", the record in italics in its description. */
    private fun stereogum() = items("https://www.stereogum.com/category/album-of-the-week/feed/").mapNotNull { i ->
        val m = Regex("Album Of The Week:\\s*(.+?)\\s*<em>(.+?)</em>").find(i.raw.replace("&lt;", "<").replace("&gt;", ">")) ?: return@mapNotNull null
        Pick(true, plain(m.groupValues[2]), plain(m.groupValues[1]), "stereogum", "", i.link, i.at, null)
    }

    /** Bandcamp Daily's album of the day: "Josh Johnson, “Animative”". */
    private fun bandcamp() = items("https://daily.bandcamp.com/feed").filter { "Album of the Day" in it.tags }.mapNotNull { i ->
        val m = Regex("^(.+),\\s*[“\"](.+)[”\"]$").find(i.title) ?: return@mapNotNull null
        Pick(true, m.groupValues[2], m.groupValues[1], "bandcamp daily", i.text.removeSuffix("Read full story on the Bandcamp Daily.").trim(), i.link, i.at, null)
    }

    /** NME's reviews: "DIIV – ‘ZIRP!’ review: pop meets politics on their most adventurous record yet". */
    private fun nme() = items("https://www.nme.com/reviews/album/feed").mapNotNull { i ->
        val m = Regex("^(.+?)\\s+[–-]\\s+[‘'\"“](.+?)[’'\"”]\\s+review:?\\s*(.*)$").find(i.title) ?: return@mapNotNull null
        Pick(true, m.groupValues[2], m.groupValues[1], "nme", m.groupValues[3], i.link, i.at, null)
    }

    /** The Quietus's reviews: "Dis Fig – What To Make Of These Mortal Hands". */
    private fun quietus() = items("https://www.thequietus.com/feed").filter { "Reviews" in it.tags }.mapNotNull { i ->
        val (a, t) = i.title.split(" – ", " - ").takeIf { it.size == 2 } ?: return@mapNotNull null
        // its blurb begins with the artist and record again
        Pick(true, t, a, "the quietus", i.text.removePrefix("$a $t").trim(), i.link, i.at, null)
    }

    /** Aquarium Drunkard: "Surprise Chef :: Frogs In A Pond" (its shows, interviews and podcasts aside). */
    private fun aquarium() = items("https://aquariumdrunkard.com/feed/").filter { "Podcast" !in it.tags }.mapNotNull { i ->
        val (a, t) = i.title.split(" :: ").takeIf { it.size == 2 } ?: return@mapNotNull null
        // its columns (films, interviews, shows) aren't records
        if (a.startsWith("Transmissions") || a == "Videodrome" || t.startsWith("(") || t.contains("Interview", true) || a.contains("Aquarium Drunkard")) return@mapNotNull null
        Pick(true, t, a, "aquarium drunkard", i.text, i.link, i.at, null)
    }

    /** Gorilla vs. Bear: "Helena Deland – Tip of My Tongue" a song; "listen to Boyhood’s <i>I’ll Dream InStead</i>" a record. */
    private fun gorilla() = items("https://www.gorillavsbear.net/feed/").mapNotNull { i ->
        val record = Regex("^listen to (.+?)[’']s (.+)$", RegexOption.IGNORE_CASE).find(i.title)
        when {
            record != null -> Pick(true, record.groupValues[2], record.groupValues[1], "gorilla vs. bear", "", i.link, i.at, null)
            i.title.startsWith("photos", true) || i.title.startsWith("video", true) -> null
            else -> i.title.split(" – ").takeIf { it.size == 2 }?.let { (a, t) -> Pick(false, t.substringBefore(" / "), a, "gorilla vs. bear", "", i.link, i.at, null) }
        }
    }

    /**
     * NPR Music's New Music Friday: each week public radio's DJs (KCRW, Radio Milwaukee, KUTX...) and NPR's critics on
     * the week's best records, listed in the episode's notes: "Album No. 1 - Michael Kiwanuka, 'Fudge'", then the
     * lightning round's "- Josh Johnson, 'Animative'". Added 2026-10-29 (Bronson: the picks were Pitchfork heavy).
     */
    private fun npr() = items("https://feeds.npr.org/510019/podcast.xml").filter { it.title.startsWith("New Music Friday") }.take(4).flatMap { i ->
        val notes = plain(i.raw.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")).replace('\u00A0', ' ').replace(Regex("\\s+"), " ")
        // a title can hold an apostrophe ('It's a Style Sport'): it ends at the quote before the next pick, a time or the end
        val title = "'(.+?)'(?=\\s*(?:\\(|-\\s|•|Sample|Credits|$))"
        val top = Regex("Album No\\.?\\s*\\d+\\s*-\\s*(.+?),\\s*$title").findAll(notes).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val quick = notes.substringAfter("Lightning Round", "").let { r -> Regex("(?:-|•)\\s*(.+?),\\s*$title").findAll(r).map { it.groupValues[1] to it.groupValues[2] }.toList() }
        // the starting five first: a little newer than the lightning round, so they lead when the week's are shown
        (top.mapIndexed { n, (a, t) -> Pick(true, t, a.trim(), "npr music", "", i.link, i.at + 60_000L * (10 - n), null) } +
            quick.map { (a, t) -> Pick(true, t, a.trim(), "npr music", "", i.link, i.at, null) })
    }

    /** Dusted, the independent and experimental reviews: "Imperial Teen — All Over You (Merge)", a review a day. */
    private fun dusted() = items("https://dustedmagazine.tumblr.com/rss").mapNotNull { i ->
        // its titles come escaped twice ("&amp;mdash;")
        val m = Regex("^(.+?)\\s+[—–]\\s+(.+?)(?:\\s+\\(([^()]*)\\))?$").find(plain(i.title)) ?: return@mapNotNull null
        Pick(true, m.groupValues[2], m.groupValues[1], "dusted", i.text, i.link, i.at, null)
    }

    /**
     * Picks shown fairly: the publications taking turns, each one's newest first, so one that reviews five records a day
     * (Pitchfork) doesn't fill the shelf; `from` is a pick's publication.
     */
    fun <T> fair(items: List<T>, from: (T) -> String): List<T> {
        val each = items.groupBy(from).values.toList()
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { n -> each.mapNotNull { it.getOrNull(n) } }
    }

    /**
     * The record of the day: one picked by people, from the last fortnight, the same all day; one more than one critic
     * named first. Null with none.
     */
    fun recordOfTheDay(picks: List<Pick>, day: LocalDate = LocalDate.now()): Pick? = recordsOfTheDay(picks, day).firstOrNull()

    /**
     * The day's record and the ones to fall back on, in turn: the most named of the last two weeks (today's turn through
     * them first), then the rest of the fortnight's by how many named them, the newest first. The apps show the first
     * that YouTube Music has (a record's often reviewed before it's on streaming, or sold only on Bandcamp: Josh
     * Johnson's Animative, 2026-10-29).
     */
    fun recordsOfTheDay(picks: List<Pick>, day: LocalDate = LocalDate.now()): List<Pick> {
        val since = System.currentTimeMillis() - 14L * 86_400_000
        val records = picks.filter { it.record && it.at >= since }.ifEmpty { picks.filter { it.record } }
        if (records.isEmpty()) return emptyList()
        val named = records.groupBy { key(it) }.values.sortedWith(compareByDescending<List<Pick>> { it.size }.thenByDescending { g -> g.maxOf { it.at } })
        val best = named.filter { it.size == named.first().size }
        val turn = (day.toEpochDay() % best.size).toInt()
        // the first who picked each, with what was said (the line said, where any did)
        return (best.drop(turn) + best.take(turn) + named.drop(best.size)).map { g -> g.firstOrNull { it.said.isNotBlank() } ?: g.first() }
    }

    /** Who else named the same record ("pitchfork, nme"). */
    fun namedBy(p: Pick, picks: List<Pick>): List<String> = picks.filter { key(it) == key(p) }.map { it.from }.distinct()

    fun key(p: Pick) = (p.artist + "/" + p.title).lowercase().filter { it.isLetterOrDigit() || it == '/' }

    // ---- on the air now ----

    /** What the stations ink keeps are playing this minute (each a few seconds' ask, in turn; a quiet one passed over). */
    fun onAir(): List<Aired> = listOf(
        Triple("skexp", "kexp", ::kexp), Triple("skcrw-e24", "kcrw eclectic24", ::kcrw), Triple("sthe-current", "the current", ::current),
        Triple("swxpn", "wxpn", ::xpn), Triple("sfip", "fip", ::fip), Triple("swfuv", "wfuv", ::wfuv),
    ).mapNotNull { (key, name, f) -> runCatching { f() }.getOrNull()?.let { (t, a, art) -> Aired(key, name, t.trim(), a.trim(), art) } }
        // a show, not a song ("|Echoes|", "[station id]")
        .filter { it.title.isNotBlank() && it.artist.isNotBlank() && !it.artist.startsWith("|") && !it.artist.startsWith("[") }

    private fun recent(at: Long) = System.currentTimeMillis() - at < 12 * 60_000L

    private fun kexp(): Triple<String, String, String?>? = org.json.JSONObject(get("https://api.kexp.org/v2/plays/?limit=1")).getJSONArray("results").optJSONObject(0)
        ?.takeIf { it.optString("play_type") == "trackplay" && recent(OffsetDateTime.parse(it.getString("airdate")).toInstant().toEpochMilli()) }
        ?.let { Triple(it.optString("song"), it.optString("artist"), it.optString("image_uri").ifEmpty { it.optString("thumbnail_uri") }.ifEmpty { null }) }

    private fun kcrw(): Triple<String, String, String?>? = org.json.JSONArray(get("https://tracklist-api.kcrw.com/Music/all/1")).optJSONObject(0)
        ?.takeIf { recent(OffsetDateTime.parse(it.getString("datetime")).toInstant().toEpochMilli()) }
        ?.let { Triple(it.optString("title"), it.optString("artist"), it.optString("albumImageLarge").replace("100x100bb", "600x600bb").ifEmpty { null }) }

    private fun current(): Triple<String, String, String?>? = org.json.JSONObject(get("https://nowplaying.publicradio.org/the-current/playlist")).getJSONObject("data").getJSONArray("songs").optJSONObject(0)
        ?.takeIf { recent(OffsetDateTime.parse(it.getString("played_at")).toInstant().toEpochMilli()) }
        ?.let { Triple(it.optString("title"), it.optString("artist"), it.optString("art_url").ifEmpty { null }) }

    private fun xpn(): Triple<String, String, String?>? {
        val there = java.time.ZoneId.of("America/New_York")
        return org.json.JSONArray(get("https://origin.xpn.org/utils/playlist/json/${LocalDate.now(there)}.json")).optJSONObject(0)
            ?.takeIf { recent(java.time.LocalDateTime.parse(it.getString("timeslice"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(there).toInstant().toEpochMilli()) }
            ?.let { Triple(it.optString("song"), it.optString("artist"), it.optString("image").ifEmpty { null }) }
    }

    private fun fip(): Triple<String, String, String?>? {
        val now = System.currentTimeMillis() / 1000
        val steps = org.json.JSONObject(get("https://api.radiofrance.fr/livemeta/pull/7")).getJSONObject("steps")
        return steps.keys().asSequence().map { steps.getJSONObject(it) }
            .firstOrNull { it.optString("embedType") == "song" && it.optLong("start") <= now + 30 && now < it.optLong("end") }
            ?.let { st ->
                val by = st.optJSONArray("highlightedArtists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }
                Triple(st.optString("title"), by?.ifEmpty { null } ?: st.optString("authors"), st.optString("visual").ifEmpty { null })
            }
    }

    private fun wfuv(): Triple<String, String, String?>? {
        val there = java.time.ZoneId.of("America/New_York")
        return org.json.JSONObject(get("https://wfuv.org/sites/default/files/player/playlist/now_playing.json")).optJSONObject("live")
            ?.takeIf { it.optString("event_type") == "MUSIC" && recent(java.time.LocalDateTime.parse(it.getString("timestamp"), DateTimeFormatter.ofPattern("MMddyyyy-HHmmss")).atZone(there).toInstant().toEpochMilli()) }
            ?.let { Triple(it.optString("title"), it.optString("artist"), null) }
    }

    // ---- covers ----

    /** A record's (or song's) cover from Apple's iTunes search (no key), artist and name matched, at its largest. */
    fun itunes(title: String, artist: String, record: Boolean): String? = runCatching {
        fun plainName(s: String) = s.lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "").filter { it.isLetterOrDigit() }
        val term = java.net.URLEncoder.encode("$artist $title", "UTF-8")
        val o = org.json.JSONObject(get("https://itunes.apple.com/search?term=$term&entity=${if (record) "album" else "song"}&limit=8"))
        val results = o.optJSONArray("results") ?: return null
        (0 until results.length()).map { results.getJSONObject(it) }.firstOrNull { r ->
            val a = plainName(r.optString("artistName")); val want = plainName(artist.substringBefore(","))
            val t = plainName(r.optString(if (record) "collectionName" else "trackName")); val wt = plainName(title)
            a.isNotEmpty() && (a.contains(want) || want.contains(a)) && wt.isNotEmpty() && (t.startsWith(wt) || wt.startsWith(t))
        }?.optString("artworkUrl100")?.takeIf { it.isNotEmpty() }?.replace(Regex("/\\d+x\\d+[a-z-]*\\.(jpg|png)$"), "/1200x1200bb.$1")
    }.getOrNull()

    // ---- text ----

    private fun cdata(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")

    /** Tags gone, entities read, spaces made one. */
    private fun plain(s: String): String = s.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&#8217;", "’").replace("&#8216;", "‘").replace("&#8220;", "“").replace("&#8221;", "”")
        .replace("&#8211;", "–").replace("&#8212;", "—").replace("&#038;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        // the named ones (Dusted's "&mdash;")
        .replace(Regex("&([a-z]+);")) { NAMED[it.groupValues[1]] ?: it.value }
        // a feed that escaped its tags ("&lt;i&gt;"), read: the tags it then shows, gone too
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\s+"), " ").trim()

    private val NAMED = mapOf("mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "hellip" to "…",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "ouml" to "ö",
        "uuml" to "ü", "auml" to "ä", "ccedil" to "ç", "apos" to "'", "amp" to "&")

    /** A blurb's first sentence (what's said, short). */
    private fun sentence(s: String): String {
        val t = s.removeSuffix("…").trim()
        val end = Regex("[.!?](\\s|$)").find(t)?.range?.first
        return (if (end != null && end > 20) t.substring(0, end + 1) else t).take(220)
    }

    /** A page's text, its redirects followed (Stereogum answers 308, which Java's connection leaves be). */
    fun get(url: String): String {
        var at = URL(url)
        repeat(5) {
            val c = at.openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) $AGENT")
            try {
                if (c.responseCode in 300..399) { at = URL(at, c.getHeaderField("Location") ?: error("redirect nowhere")); return@repeat }
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }
        error("too many redirects: $url")
    }
}
