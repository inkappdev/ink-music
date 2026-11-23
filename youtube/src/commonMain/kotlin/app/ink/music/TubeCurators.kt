package app.ink.music

/**
 * More curators who keep their own playlists on YouTube, as BIRP! does ([Birp]), for INK's picks (Bronson, 2026-11-07):
 * - triple j's New Music Hitlist: the station's weekly list of the new songs it's behind (Australian indie, much of it).
 * - alexrainbirdMusic's New Indie Music <year>: indie, folk and pop, kept newest first, the year's in its name (last
 *   year's in early January). Some are their own uploads ("Artist - Song", alexrainbirdMusic the uploader), some
 *   two-hour compilations: the artist taken from the title, anything not a song's length left out.
 * - Josaleigh Pollett's weekly brosaleigh (2026-11-23, Bronson follows it): a dozen or so new indie songs a week, small
 *   bands mostly.
 * Read from YouTube Music signed out, each list theirs (the uploader its subtitle's first words: copies share names).
 * Shared (the phone's full build, the computer); not affiliated with either.
 */
object TubeCurators {
    const val TRIPLE_J = "triple j"
    const val RAINBIRD = "alexrainbird"
    const val BROSALEIGH = "josaleigh pollett"

    private class Curator(val from: String, val owner: String, val max: Int, val lists: (Int) -> List<Pair<String, String>>)

    private val ALL = listOf(
        Curator(TRIPLE_J, "triple j", 60) { _ -> listOf("triple j hitlist" to "new music hitlist", "triple j New Music Hitlist" to "new music hitlist") },
        Curator(RAINBIRD, "alexrainbirdmusic", 100) { year ->
            listOf("alexrainbirdMusic New Indie Music $year" to "new indie music $year", "alexrainbirdMusic New Indie Music ${year - 1}" to "new indie music ${year - 1}")
        },
        Curator(BROSALEIGH, "josaleigh pollett", 40) { _ -> listOf("weekly brosaleigh" to "weekly brosaleigh", "Josaleigh Pollett weekly brosaleigh" to "weekly brosaleigh") },
    )

    /** Every curator's newest songs as picks (each list's order kept by a minute between each), the year as it is now. */
    fun picks(now: Long, year: Int): List<Curation.Pick> = ALL.flatMap { c ->
        runCatching { songs(c, year) }.getOrDefault(emptyList()).mapIndexed { i, (title, artist, art) ->
            val (bare, by) = clean(title, artist)
            Curation.Pick(false, bare, by, c.from, "", "", now - i * 60_000L, art)
        }
    }

    private val VIDEO = Regex("""\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|music video|mv)\b[^)\]]*[)\]]\s*$""", RegexOption.IGNORE_CASE)
    // the same words with no brackets round them, at the very end ("... FEAT. DRIZZZ VISUALISER")
    // (only words that never end a song's own name: "Radio Video" stays)
    private val VIDEO_BARE = Regex("""\s+[-|]?\s*(visuali[sz]er|official\s+(music\s+|lyric\s+)?video|music\s+video|lyric\s+video|official\s+audio)\s*$""", RegexOption.IGNORE_CASE)
    // where a song's from, a note not its name ("(From Grand Theft Auto VI: The Album)")
    private val FROM = Regex("""\s*[(\[]\s*from\s+[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    // an upload credited to its label, not its artist ("Atlantic Records"): the artist is in the title
    private val LABEL = Regex("""\b(records|recordings|music group|entertainment|label|vevo|official)\b""", RegexOption.IGNORE_CASE)
    private val SEPARATORS = listOf(" - ", " – ", " | ", ", ", ": ")

    /**
     * A video's title and credit as a song's (2026-11-23: triple j's list showed "BICEP | Gas", "... VISUALISER", Lana
     * Del Rey's song by Atlantic Records): "(Official Video)", "[Visualizer]", a bare "visualiser" and "(From ...)" off
     * its end; the artist's name off its front where the title has it ("Stand Atlantic - SO COOL", "BICEP | Gas"); and
     * where a label's the one credited, the artist taken from the title ("Lana Del Rey - Crazy For You").
     */
    fun clean(title: String, artist: String): Pair<String, String> {
        var t = title.replace(VIDEO, "").replace(VIDEO, "").replace(FROM, "").trim()
        t = t.replace(VIDEO_BARE, "").trim()
        var by = artist
        if (LABEL.containsMatchIn(artist)) {
            SEPARATORS.take(3).firstNotNullOfOrNull { sep -> t.indexOf(sep).takeIf { it > 0 }?.let { it to sep } }?.let { (at, sep) ->
                by = t.substring(0, at).trim(); t = t.substring(at + sep.length).trim()
            }
        }
        val first = by.substringBefore(", ")
        t = SEPARATORS.firstNotNullOfOrNull { sep -> t.takeIf { it.startsWith(first + sep, ignoreCase = true) }?.substring(first.length + sep.length) } ?: t
        return t.trim() to by
    }

    private fun songs(c: Curator, year: Int): List<Triple<String, String, String?>> {
        val yt = InnerTube()
        for ((query, want) in c.lists(year)) {
            val list = runCatching { yt.searchItems(query, InnerTube.Kind.PLAYLIST, 10) }.getOrNull()
                ?.firstOrNull { want in it.title.lowercase() && it.subtitle.trim().lowercase().startsWith(c.owner) } ?: continue
            val tracks = runCatching { yt.tracks(list.id, 400) }.getOrNull().orEmpty()
            val out = tracks.filter { it.seconds == 0 || it.seconds in 60..720 }.mapNotNull { t ->
                val by = t.artists.joinToString(", ")
                // their own upload: "Artist - Song" in its title
                if (by.lowercase().replace(" ", "").startsWith(c.owner) || by.isBlank()) {
                    val dash = t.title.indexOf(" - ")
                    if (dash <= 0) null else Triple(t.title.substring(dash + 3).trim(), t.title.substring(0, dash).trim(), t.art)
                } else Triple(t.title, by, t.art)
            }.filter { (title, artist, _) -> title.isNotBlank() && artist.isNotBlank() && !title.contains("compilation", true) && !title.contains("playlist", true) }
            if (out.isNotEmpty()) return out.take(c.max)
        }
        return emptyList()
    }
}
