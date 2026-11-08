package app.ink.music

/**
 * More curators who keep their own playlists on YouTube, as BIRP! does ([Birp]), for INK's picks (Bronson, 2026-11-07):
 * - triple j's New Music Hitlist: the station's weekly list of the new songs it's behind (Australian indie, much of it).
 * - alexrainbirdMusic's New Indie Music <year>: indie, folk and pop, kept newest first, the year's in its name (last
 *   year's in early January). Some are their own uploads ("Artist - Song", alexrainbirdMusic the uploader), some
 *   two-hour compilations: the artist taken from the title, anything not a song's length left out.
 * Read from YouTube Music signed out, each list theirs (the uploader its subtitle's first words: copies share names).
 * Shared (the phone's full build, the computer); not affiliated with either.
 */
object TubeCurators {
    const val TRIPLE_J = "triple j"
    const val RAINBIRD = "alexrainbird"

    private class Curator(val from: String, val owner: String, val max: Int, val lists: (Int) -> List<Pair<String, String>>)

    private val ALL = listOf(
        Curator(TRIPLE_J, "triple j", 60) { _ -> listOf("triple j hitlist" to "new music hitlist", "triple j New Music Hitlist" to "new music hitlist") },
        Curator(RAINBIRD, "alexrainbirdmusic", 100) { year ->
            listOf("alexrainbirdMusic New Indie Music $year" to "new indie music $year", "alexrainbirdMusic New Indie Music ${year - 1}" to "new indie music ${year - 1}")
        },
    )

    /** Every curator's newest songs as picks (each list's order kept by a minute between each), the year as it is now. */
    fun picks(now: Long, year: Int): List<Curation.Pick> = ALL.flatMap { c ->
        runCatching { songs(c, year) }.getOrDefault(emptyList()).mapIndexed { i, (title, artist, art) ->
            // a video's title as a song's: "(Official Video)", "[Visualizer]" and their like off its end
            // and the artist's name off its front where the video's title has it ("Stand Atlantic - SO COOL")
            val first = artist.substringBefore(", ")
            val bare = title.replace(VIDEO, "").replace(VIDEO, "").trim().let { t ->
                listOf(" - ", ", ", ": ").firstNotNullOfOrNull { sep -> t.takeIf { it.startsWith(first + sep, ignoreCase = true) }?.substring(first.length + sep.length) } ?: t
            }.trim()
            Curation.Pick(false, bare, artist, c.from, "", "", now - i * 60_000L, art)
        }
    }

    private val VIDEO = Regex("""\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|music video|mv)\b[^)\]]*[)\]]\s*$""", RegexOption.IGNORE_CASE)

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
