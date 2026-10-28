package app.ink.music

/**
 * What a station is playing, said where a song's words go (the mini player, the player): from the stream's own
 * title (ICY: most of ink radio sends "artist - title"), which mpv reads as the stream's media title. The phone's
 * OnAir also asks a few stations' own playlists (KEXP, KCRW...); not yet here.
 */
object OnAir {
    /** What a station says it's playing: the song (no artist: a show, a line). */
    data class Said(val title: String, val artist: String?)

    fun parse(raw: String, station: String): Said? {
        val s = raw.trim().removeSuffix(".mp3").trim()
        val up = s.uppercase()
        // dublab's "-" between shows, and the like: nothing said
        if (s.none { it.isLetterOrDigit() } || "THIS STATION WILL" in up || "NOW PLAYING INFO" in up || up.endsWith("OFFLINE")) return null
        Regex("^\"(.+)\" (?:by|von) (.+?)(?: (?:on|auf) .+)?$").find(s)?.let { return Said(it.groupValues[1], it.groupValues[2]) }
        val dash = s.indexOf(" - ")
        if (dash < 0) return Said(s, null)
        val a = s.substring(0, dash).trim()
        val b = s.substring(dash + 3).trim(' ', '-')
        val first = station.substringBefore(' ').lowercase()
        return when {
            b.isEmpty() -> Said(a, null)
            a.isEmpty() || (first.length > 2 && a.lowercase().startsWith(first)) || a.lowercase() in setOf("live now", "archive") -> Said(b, null)
            else -> Said(b, a)
        }
    }
}
