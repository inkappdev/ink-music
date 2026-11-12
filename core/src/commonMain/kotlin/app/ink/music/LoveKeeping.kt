package app.ink.music

/**
 * Keep what you love (2026-11-11, Bronson's idea: "when you heart a song it auto downloads it for you"): a song loved is
 * kept offline (your server's, YouTube Music's; a file's on the device already), on wifi unless said, and let go when
 * it's unloved, but only if the love is what kept it (one kept by hand stays). This is the rule each app's keeper
 * follows; the keeping and remembering are theirs.
 */
object LoveKeeping {
    const val OFF = "off"
    const val WIFI = "on wifi"
    const val ALWAYS = "always"

    /**
     * More loves than this arriving at once are a sync catching up (a server's stars or YouTube's likes read for the
     * first time), not loves made now: noted, not kept (the settings offer to keep those, once).
     */
    const val BATCH = 10

    /** What a change in the loved means: the newly loved to keep, and what's been seen since (`seen` null: the first look). */
    data class Turn(val keep: Set<String>, val seen: Set<String>)

    /**
     * Loved now against loved before. Only ever added to (a server's stars missing a moment at start, before a sync,
     * aren't unloves: an unlove is said by the app, [unloved]), so a song back in sight isn't kept again.
     */
    fun turn(loved: Set<String>, seen: Set<String>?): Turn {
        if (seen == null) return Turn(emptySet(), loved)
        val added = loved - seen
        return Turn(if (added.size > BATCH) emptySet() else added, seen + loved)
    }

    /**
     * Keep what you play most (2026-11-11, after SimpMusic's smart downloads): the `n` songs most in your rotation now
     * (each listen `(id, when)` in the last `days`), by how memory holds them (after Flow, the ACT-R model's): each play
     * counts the less the longer ago it was (as the hours since it, to the power of -1/2), so three plays this week
     * outweigh ten in the summer; the most first.
     */
    fun mostPlayed(heard: List<Pair<String, Long>>, now: Long, n: Int = 50, days: Int = 90): List<String> {
        val from = now - days * 86_400_000L
        val held = HashMap<String, Double>()
        for ((id, at) in heard) if (at >= from) {
            val hours = ((now - at) / 3_600_000.0).coerceAtLeast(.05)
            held[id] = (held[id] ?: 0.0) + 1 / kotlin.math.sqrt(hours)
        }
        return held.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    /** About how many bytes a song takes kept: its seconds at the rate it's kept (a FLAC's guessed, YouTube's its stream's). */
    fun about(seconds: Long, small: Boolean, tube: Boolean): Long =
        seconds * when { tube && small -> 12_000L; tube -> 20_000L; small -> 40_000L; else -> 110_000L }

    /** Bytes as a person says them: "about 1.4 gb", "about 320 mb". */
    fun said(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "about ${(bytes / 100_000_000L) / 10.0} gb"
        else -> "about ${maxOf(1L, bytes / 1_000_000L)} mb"
    }
}
