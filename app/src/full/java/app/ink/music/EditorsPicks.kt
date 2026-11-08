package app.ink.music


/**
 * More picks for INK (2026-11-07, the full build only), song data only, shown as "editors' picks" and "youtube music":
 * - Spotify's editorial indie playlists, and The Line of Best Fit's and DIY's own ([SpotifyLists]): song data from
 *   their public embed pages, nothing played from there.
 * - YouTube Music's own editors' lists for indie & alternative (its moods and genres page): the first of its shelves'.
 * - BIRP!'s monthly indie pop / rock playlist ([Birp]), shown as "birp!"; triple j's and alexrainbirdMusic's ([TubeCurators]).
 */
object EditorsPicks {
    const val EDITORS = SpotifyLists.EDITORS
    const val TUBE = "youtube music"

    /** Every one, newest lists first; any that won't answer passed over. */
    fun read(): List<Curation.Pick> = runCatching { editors() }.getOrDefault(emptyList()) + runCatching { tube() }.getOrDefault(emptyList()) +
        runCatching { birp() }.getOrDefault(emptyList()) + runCatching { curators() }.getOrDefault(emptyList())

    // triple j's New Music Hitlist, alexrainbirdMusic's New Indie Music (TubeCurators, shared with the computer)
    private fun curators(): List<Curation.Pick> =
        TubeCurators.picks(System.currentTimeMillis(), java.time.LocalDate.now().year)
            .also { l -> android.util.Log.d("ink-picks", "curators: " + l.groupBy { it.from }.map { "${it.key} ${it.value.size}" }.joinToString()) }

    // BIRP!'s monthly indie pop / rock playlist, from YouTube itself (Birp, shared with the computer)
    private fun birp(): List<Curation.Pick> {
        val d = java.time.LocalDate.now()
        return Birp.picks(System.currentTimeMillis(), d.monthValue, d.year).also { android.util.Log.d("ink-picks", "birp!: ${it.size} songs") }
    }

    // Spotify's editorial lists and the magazines' own (SpotifyLists, shared with the computer)
    private fun editors(): List<Curation.Pick> = SpotifyLists.picks(System.currentTimeMillis()) { android.util.Log.d("ink-picks", it) }

    private fun tube(): List<Curation.Pick> {
        val now = System.currentTimeMillis()
        val indie = YouTubeMusic.moods().firstOrNull { it.genre && it.title.contains("indie", ignoreCase = true) } ?: return emptyList()
        val songs = YouTubeMusic.editorsSongs(indie.params)
        // a video's title as a song's: "(Official Video)", "[Visualizer]" and their like off its end
        val video = Regex("""\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|music video|mv)\b[^)\]]*[)\]]\s*$""", RegexOption.IGNORE_CASE)
        return songs.map { Curation.Pick(false, it.title.replace(video, "").replace(video, "").trim(), it.artist, TUBE, "", "", now, it.art) }
            .also { android.util.Log.d("ink-picks", "youtube music's ${indie.title}: ${it.size} songs") }
    }
}
