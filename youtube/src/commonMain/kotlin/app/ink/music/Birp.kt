package app.ink.music

/**
 * BIRP!'s monthly indie pop / rock playlist (Bronson, 2026-11-07: "a great source for ink curation"), for INK's picks.
 * BIRP! puts each month's on YouTube itself ("Indie Pop / Rock Playlist | BIRP! November 2026", its own channel, 130
 * songs or so; every month since 2024 at least), so it's read from YouTube Music signed out: a search for the month's,
 * then all its songs, already YouTube's (nothing to match up, as Spotify's lists need). Early in a month, before the new one
 * is up, last month's. Shared (the phone's full build, the computer); not affiliated with BIRP!.
 */
object Birp {
    const val FROM = "birp!"

    /**
     * The month's songs in BIRP!'s order, all of them (130 or so: the first 40 alone, the same all month, were spent by
     * mid-month; INK's freshness brings up ones not seen yet); `months` its name and year, this month first, then the last.
     */
    fun songs(months: List<Pair<String, Int>>, max: Int = 200): List<InnerTube.Track> {
        val yt = InnerTube()
        for ((month, year) in months) {
            val want = "birp! $month $year".lowercase()
            // theirs: its title the month's, and BIRP! who made it (others copy the name)
            val list = runCatching { yt.searchItems("Indie Pop / Rock Playlist BIRP! $month $year", InnerTube.Kind.PLAYLIST, 10) }.getOrNull()
                ?.firstOrNull { want in it.title.lowercase() && it.subtitle.trim().lowercase().startsWith("birp!") } ?: continue
            val tracks = runCatching { yt.tracks(list.id, max) }.getOrNull().orEmpty().take(max)
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    /**
     * The month's songs as INK's picks, by "birp!", in BIRP!'s order (each a minute older than the one before it, so the
     * newest-first order keeps theirs); its covers YouTube Music's own. [month] 1 to 12.
     */
    fun picks(now: Long, month: Int, year: Int): List<Curation.Pick> =
        songs(months(month, year)).mapIndexed { i, t ->
            Curation.Pick(false, t.title, t.artists.joinToString(", "), FROM, "", "", now - i * 60_000L, t.art)
        }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }

    /** This month and the last, as BIRP! names them ("November", 2026). */
    fun months(month: Int, year: Int): List<Pair<String, Int>> {
        val names = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val last = if (month == 1) 12 to year - 1 else month - 1 to year
        return listOf(names[month - 1] to year, names[last.first - 1] to last.second)
    }
}
