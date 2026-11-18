package app.ink.music

/**
 * New releases as ink shows them (2026-11-18, Bronson: YouTube Music's list "has some shit in there"): its list,
 * signed out, is mostly the American radio's week, and padded out with singles, deluxe and live editions, remasters,
 * demos, soundtracks and holiday records. Shown last, under new from your artists, the critics' records and out this
 * Friday, and only its records and EPs that are records in their own right; artists near your taste first.
 */
object NewOut {
    private val padding = Regex(
        """\b(live|deluxe|remaster(ed)?|anniversary|expanded|edition|demos?|sessions?|versions?|acoustic|instrumentals?|""" +
            """sped up|slowed|karaoke|tour|soundtrack|ost|score|from the|featured in|music from|inspired by|orchestral|""" +
            """christmas|navidad|holiday|greatest hits|best of|collection|anthology)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** A record worth a place: an album or an EP (`kind` as YouTube says it: "Album", "Single", "EP"), not padding. */
    fun kept(title: String, kind: String): Boolean = !kind.trim().equals("single", true) && !padding.containsMatchIn(title)

    /** The artists near you (`near` 0 to 1, Taste.of) first, YouTube's own order kept among the rest. */
    fun <T> ordered(xs: List<T>, artist: (T) -> String, near: (String) -> Double): List<T> {
        val (close, rest) = xs.partition { near(artist(it)) >= .3 }
        return close.sortedByDescending { near(artist(it)) } + rest
    }
}
