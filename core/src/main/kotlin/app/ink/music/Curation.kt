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
    fun picks(): List<Pick> = listOf(::pitchforkRecords, ::pitchforkSongs, ::stereogum, ::bandcamp, ::nme, ::quietus, ::aquarium, ::gorilla, ::npr, ::dusted, ::stereofox, ::fader)
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

    /** Stereofox's songs, a few a day (electronic, r&b, dream pop, uk garage...): "Logic1000 – The Girl (feat. yunè pinku)". */
    private fun stereofox() = items("https://www.stereofox.com/feed/").filter { "Songs" in it.tags }.mapNotNull { i ->
        val (a, t) = i.title.split(" – ").takeIf { it.size == 2 } ?: return@mapNotNull null
        Pick(false, t.replace(Regex("\\s*\\((ft|feat)\\.?[^)]*\\)", RegexOption.IGNORE_CASE), "").trim(), a.trim(), "stereofox", i.text, i.link, i.at, null)
    }

    /** The FADER's songs you need right now, each Wednesday: the list is on its page ("Lambrini Girls, “Spilt”"), the feed says only that it's up. */
    private fun fader() = items("https://www.thefader.com/feed/").filter { it.title.contains("songs you need", true) }.take(2).flatMap { i ->
        // each song its own headline: <h5 class="headline">Dead Man's Bones, “Lost In The Night”</h5>
        Regex("""<h5 class="headline"[^>]*>(.*?)</h5>""", RegexOption.DOT_MATCHES_ALL).findAll(get(i.link))
            .mapNotNull { m -> Regex("^(.{1,80}?),\\s*[“\"](.{1,100})[”\"]$").find(plain(m.groupValues[1])) }
            .mapIndexed { n, m -> Pick(false, m.groupValues[2], m.groupValues[1], "the fader", "", i.link, i.at - 60_000L * n, null) }.toList()
    }

    /** Dusted, the independent and experimental reviews: "Imperial Teen — All Over You (Merge)", a review a day. */
    private fun dusted() = items("https://dustedmagazine.tumblr.com/rss").mapNotNull { i ->
        // its titles come escaped twice ("&amp;mdash;")
        val m = Regex("^(.+?)\\s+[—–]\\s+(.+?)(?:\\s+\\(([^()]*)\\))?$").find(plain(i.title)) ?: return@mapNotNull null
        Pick(true, m.groupValues[2], m.groupValues[1], "dusted", i.text, i.link, i.at, null)
    }

    // ---- electronic ----

    /**
     * Electronic music, picked by its own writers (Bronson, 2026-10-29: "a good curation of electronic music like
     * Resident Advisor", whose picks have no feed): Bandcamp Daily's monthly electronic and ambient columns, A Closer
     * Listen, Igloo and Headphone Commute's reviews (records); Inverted Audio's and Headphone Commute's premieres
     * (club tracks). Apart from the critics' picks, so those aren't made all ambient. The newest first.
     */
    fun electronic(): List<Pick> = listOf({ bandcampColumn("best-electronic") }, { bandcampColumn("best-ambient") }, ::closerListen, ::igloo, ::headphone, ::inverted)
        .flatMap { f -> runCatching { f() }.getOrDefault(emptyList()) }
        .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
        .sortedByDescending { it.at }

    /**
     * A Bandcamp Daily column's newest month: each record its heading, the artist and the record's own page (so its
     * cover is the page's).
     */
    private fun bandcampColumn(slug: String): List<Pick> {
        val post = Regex("""href="(/$slug/[^"]+)"""").find(get("https://daily.bandcamp.com/$slug"))?.groupValues?.get(1) ?: return emptyList()
        val link = "https://daily.bandcamp.com$post"
        val page = get(link)
        val at = Regex(""""datePublished"\s*:\s*"([^"]+)"""").find(page)?.groupValues?.get(1)
            ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: (System.currentTimeMillis() - 14L * 86_400_000)
        // each record a heading: the artist, then its link with its name ("<h3>claire rousay & Martyna Basta<br><a
        // href=...><em>hotel room</em></a></h3>"; the player beside it names the label's page, not the artist)
        val records = Regex("""<h3>\s*([^<]+?)<br\s*/?>\s*<a href="([^"]+)"[^>]*>(.*?)</a>\s*</h3>""", RegexOption.DOT_MATCHES_ALL).findAll(page).map { m ->
            Pick(true, plain(m.groupValues[3]), plain(m.groupValues[1]), "bandcamp daily", "", m.groupValues[2], 0, null) to m.range.first
        }
        // a track: the artist, then its name in quotes ("<h3>Yazmin Lacey<a href=...>“Sweetest Season (Karen Nyame KG Remix)”</a>")
        val tracks = Regex("""<h3>\s*([^<]+?)\s*<a href="([^"]+)"[^>]*>\s*[“"]([^”“"<]+)[”“"]""").findAll(page).map { m ->
            Pick(false, plain(m.groupValues[3]), plain(m.groupValues[1]), "bandcamp daily", "", m.groupValues[2], 0, null) to m.range.first
        }
        // in the column's order
        return (records + tracks).sortedBy { it.second }.mapIndexed { n, (p, _) -> p.copy(at = at - 60_000L * n) }.filter { it.artist.isNotBlank() }.toList()
    }

    /** A Closer Listen, ambient to modern composition, a review a day: "Carmen Villain ~ Memoria". */
    private fun closerListen() = items("https://acloserlisten.com/feed/").mapNotNull { i ->
        val (a, t) = i.title.split(" ~ ").takeIf { it.size == 2 } ?: return@mapNotNull null
        Pick(true, t.trim(), a.trim(), "a closer listen", i.text, i.link, i.at, null)
    }

    /** Igloo's reviews: "Vigilant :: Thread of Memories (Glacial Movements)", the label left off; its splits ("[2View]") passed over. */
    private fun igloo() = items("https://igloomag.com/feed").filter { "Reviews" in it.tags && "[2View]" !in it.title }.mapNotNull { i ->
        val (a, t) = i.title.split(" :: ").takeIf { it.size == 2 } ?: return@mapNotNull null
        Pick(true, t.replace(Regex("\\s*\\([^()]*\\)\\s*$"), "").trim(), a.trim(), "igloo", i.text, i.link, i.at, null)
    }

    /** Headphone Commute: its reviews of records ("Benoît Pioulard – Stanza IV") and its track premieres. */
    private fun headphone() = items("https://headphonecommute.com/feed/").mapNotNull { i ->
        val record = i.tags.any { it in setOf("Albums", "EPs", "Reviews") }
        val song = "Track Premiere" in i.tags
        if (!record && !song) return@mapNotNull null
        val (a, t) = i.title.split(" – ").takeIf { it.size == 2 } ?: return@mapNotNull null
        Pick(record, t.trim(), a.trim(), "headphone commute", i.text, i.link, i.at, null)
    }

    /** Inverted Audio's premieres, house and techno and the edges of both: "Premiere: Masaka Masaka – Say". */
    private fun inverted() = items("https://inverted-audio.com/feed/").filter { it.title.startsWith("Premiere:") }.mapNotNull { i ->
        val (a, t) = i.title.removePrefix("Premiere:").split(" – ").takeIf { it.size == 2 } ?: return@mapNotNull null
        Pick(false, t.trim(), a.trim(), "inverted audio", i.text, i.link, i.at, null)
    }

    // ---- new from your artists ----

    /** A pick by an artist you know: the pick, the artist as you know them, how often you've played them (0: only kept). */
    data class Yours(val pick: Pick, val artist: String, val plays: Int)

    /** An artist's name as compared: lower case, "the" off its front, letters and digits only. */
    private fun bare(name: String) = name.lowercase().removePrefix("the ").filter { it.isLetterOrDigit() }

    /**
     * New from your artists (Bronson, 2026-10-29, listen's top): the picks (out this Friday, the critics', electronic's)
     * by an artist you've played or keep, the most played first. `known` is every artist of yours (lower case) and how
     * often you've played them; a pick's artists are split where several share it ("Axel Boman & Superpitcher") and
     * matched whole, so "Air" isn't "Air Supply". One a record.
     */
    fun yours(picks: List<Pick>, known: Map<String, Int>): List<Yours> {
        val byBare = HashMap<String, Pair<String, Int>>()
        known.forEach { (name, n) -> bare(name).takeIf { it.length >= 2 }?.let { k -> if ((byBare[k]?.second ?: -1) < n) byBare[k] = name to n } }
        val split = Regex("""\s*(?:,|&|/|\+|\bx\b|\band\b|\bwith\b|\bfeat\.?|\bft\.?|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)
        return picks.mapNotNull { p ->
            (listOf(p.artist) + p.artist.split(split)).map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { a -> byBare[bare(a)] }.maxByOrNull { it.second }?.let { (name, n) -> Yours(p, name, n) }
        }.distinctBy { key(it.pick) }.sortedByDescending { it.plays }
    }

    // ---- out this friday ----

    /** A record out this week: who, what, its label and genres, where it's heard (Bandcamp, Apple Music), the thread's mark. */
    data class Release(val title: String, val artist: String, val label: String, val genres: List<String>, val link: String, val mark: String)

    /** This week's releases: the week's day ("october 23rd"), the thread, the records; null when Reddit won't say. */
    data class Week(val day: String, val thread: String, val releases: List<Release>)

    /**
     * Every record out this week, from r/indieheads' New Music Friday thread (Bronson, 2026-10-29): its newest one,
     * read from Reddit's search feed (its JSON turns apps away), each record "<strong>Artist - <a>Title</a></strong>",
     * then "Label: ..." and "Genre: ...". One person types it by hand each Friday, so a record that strays from that
     * shape is passed over; Reddit may close the feed to apps, and then the shelf isn't shown.
     */
    fun outThisFriday(): Week? = runCatching {
        val feed = get("https://www.reddit.com/r/indieheads/search.rss?q=title%3A%22new+music+friday%22&restrict_sr=1&sort=new")
        val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(feed).map { it.groupValues[1] }
            .firstOrNull { Regex("<title>New Music Friday", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return null
        val title = plain(Regex("<title>(.*?)</title>").find(entry)?.groupValues?.get(1).orEmpty())
        val thread = Regex("<link href=\"([^\"]+)\"").find(entry)?.groupValues?.get(1).orEmpty()
        val body = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1).orEmpty()
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        val one = Regex("""<p>\s*([^<]*?)\s*<strong>(.+?)\s+[-–]\s+(?:<a href="([^"]*)">)?(.+?)(?:</a>)?\s*</strong>\s*</p>\s*<p>\s*Label:\s*(.*?)</p>\s*<p>\s*Genres?:\s*(.*?)</p>""",
            RegexOption.DOT_MATCHES_ALL)
        val releases = one.findAll(body).map { m ->
            val g = m.groupValues
            Release(plain(g[4]), plain(g[2]), plain(g[5]).takeUnless { it.equals("n/a", true) }.orEmpty(),
                plain(g[6]).split(",").map { it.trim() }.filter { it.isNotEmpty() }, g[3], plain(g[1]))
        }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }.distinctBy { key(Pick(true, it.title, it.artist, "", "", "", 0, null)) }.toList()
        if (releases.isEmpty()) null
        else Week(title.substringAfter(":").substringBefore(",").substringBefore("(").trim().lowercase(), thread, releases)
    }.getOrNull()

    /** The kinds a week's records are filtered by: a broad name, and the words in their genres that put a record in it. */
    private val KINDS = listOf(
        "indie and rock" to listOf("indie", "rock", "punk", "shoegaze", "post-", "slowcore", "emo", "grunge", "power pop", "dream pop", "jangle"),
        "folk and songwriters" to listOf("folk", "singer-songwriter", "singer/songwriter", "country", "americana", "bluegrass"),
        "pop" to listOf("pop"),
        "hip hop and r&b" to listOf("hip hop", "hip-hop", "rap", "r&b", "soul", "funk", "trap"),
        "electronic" to listOf("electronic", "house", "techno", "idm", "electro", "dance", "synth", "club", "uk garage", "breakbeat"),
        "ambient and out there" to listOf("ambient", "experimental", "drone", "minimal", "classical", "industrial", "noise", "musique"),
        "jazz" to listOf("jazz"),
        "metal and heavy" to listOf("metal", "hardcore", "doom", "sludge", "grind"),
    )

    /**
     * The cover a record's link shows: a Bandcamp page's own (its og:image, made 700 across), an Apple Music link's
     * (iTunes looked up by its id); null for anything else.
     */
    fun coverAt(link: String): String? = runCatching {
        when {
            "bandcamp.com" in link -> Regex("""<meta property="og:image" content="([^"]+)"""").find(get(link))?.groupValues?.get(1)
                ?.replace(Regex("_\\d+\\.jpg$"), "_16.jpg")
            "music.apple.com" in link -> Regex("/(\\d{6,})").findAll(link.substringBefore("?")).lastOrNull()?.groupValues?.get(1)?.let { id ->
                org.json.JSONObject(get("https://itunes.apple.com/lookup?id=$id")).optJSONArray("results")?.optJSONObject(0)
                    ?.optString("artworkUrl100")?.takeIf { it.isNotEmpty() }?.replace(Regex("/\\d+x\\d+[a-z-]*\\.(jpg|png)$"), "/1200x1200bb.$1")
            }
            else -> null
        }
    }.getOrNull()

    /** A week's records by kind (a record can be in several), the kinds with three or more, the most first. */
    fun kinds(releases: List<Release>): List<Pair<String, List<Release>>> = KINDS.map { (name, words) ->
        name to releases.filter { r -> r.genres.any { g -> words.any { w -> w in g.lowercase() } } }
    }.filter { it.second.size >= 3 }.sortedByDescending { it.second.size }

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

    /**
     * A record's or song's cover, from the catalogs in turn: iTunes, then Deezer (Bronson, 2026-10-29: covers missing
     * on the new shelves; iTunes knows little that's small or brand new, and turns a crowd of asks away).
     */
    fun cover(title: String, artist: String, record: Boolean): String? = itunes(title, artist, record) ?: deezer(title, artist, record)

    /** Deezer's cover for a record or song (its open search, no key): the same artist and name, at its largest. */
    fun deezer(title: String, artist: String, record: Boolean): String? = runCatching {
        fun plainName(s: String) = s.lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "").filter { it.isLetterOrDigit() }
        val q = java.net.URLEncoder.encode("artist:\"${artist.substringBefore(",").trim()}\" ${if (record) "album" else "track"}:\"$title\"", "UTF-8")
        val o = org.json.JSONObject(get("https://api.deezer.com/search/${if (record) "album" else "track"}?q=$q&limit=8"))
        val results = o.optJSONArray("data") ?: return null
        val want = plainName(artist.substringBefore(",")); val wt = plainName(title)
        (0 until results.length()).map { results.getJSONObject(it) }.firstOrNull { r ->
            val a = plainName(r.optJSONObject("artist")?.optString("name").orEmpty())
            val t = plainName(r.optString("title"))
            a.isNotEmpty() && (a.contains(want) || want.contains(a)) && wt.isNotEmpty() && (t.startsWith(wt) || wt.startsWith(t))
        }?.let { r -> (if (record) r else r.optJSONObject("album"))?.optString("cover_xl")?.takeIf { it.isNotEmpty() } }
    }.getOrNull()

    /**
     * Whether a YouTube Music playlist is YouTube's own (shown in the dither, its lettering made ink's): its mixes
     * ("RD..."), its charts ("PL4fGSI1pDJn..."), its editors' lists, anything it signs as YouTube Music or a chart.
     */
    fun tubeBranded(url: String, by: String): Boolean =
        Regex("[?&]list=(RD|PL4fGSI1pDJn)").containsMatchIn(url) ||
            by.trim().lowercase() in setOf("youtube music", "youtube", "chart", "charts")

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
