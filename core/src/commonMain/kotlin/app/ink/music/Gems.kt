package app.ink.music

/**
 * Made-for-you mixes leaning to the songs people keep coming back to, not the hits (2026-11-09; the idea from
 * LastWave's recommendation engine, GPL-3.0, written here in ink's own way): a song's Last.fm listeners and plays, a
 * gem scored from them, and a mix shaped as a set is (its strongest song first, its second-strongest last).
 */
object Gems {
    /** How many have heard a song on Last.fm, and how often they've played it. */
    data class Heard(val listeners: Long, val plays: Long)

    /** A song's listeners and plays on Last.fm (blocking, one read), or null when it doesn't know it. */
    fun heard(title: String, artist: String, key: String): Heard? = runCatching {
        if (key.isEmpty()) return null
        val t = JSONObject(Curation.get("https://ws.audioscrobbler.com/2.0/?method=track.getinfo&artist=${urlEncode(artist.substringBefore(", "))}" +
            "&track=${urlEncode(title)}&autocorrect=1&api_key=$key&format=json")).optJSONObject("track") ?: return null
        Heard(t.optString("listeners").toLongOrNull() ?: return null, t.optString("playcount").toLongOrNull() ?: 0L)
    }.getOrNull()

    /**
     * How much a song's a find, in radio-scores (a radio that leads to it is up to 1): known to a few thousand to a
     * hundred thousand, most (a song between 10,000 and 30,000 listeners, nearly a radio's worth); a hit, a little less
     * than nothing; and those who've heard it playing it again and again (plays a listener), more. Unknown: nothing.
     */
    fun bonus(h: Heard?): Float {
        if (h == null || h.listeners <= 0) return 0f
        val gem = when {
            h.listeners < 3_000 -> 4
            h.listeners < 10_000 -> 7
            h.listeners < 30_000 -> 9
            h.listeners < 100_000 -> 6
            h.listeners < 400_000 -> 2
            h.listeners < 1_500_000 -> 0
            else -> -2
        }
        // played again and again, counted below hit size: everyone replays a hit (Blinding Lights, 16 plays a listener,
        // scored as high as a small find)
        val again = h.plays.toDouble() / h.listeners
        val loved = if (h.listeners >= 400_000) 0 else when { again >= 6 -> 8; again >= 3 -> 5; again >= 1.5 -> 2; else -> 0 }
        return (gem + loved) / 10f
    }

    /**
     * A mix as a set: the strongest song opens it and the second-strongest closes it, the rest between in the order
     * given (already kept apart). `strength` the higher the better; fewer than three songs as they are.
     */
    fun <T> arc(songs: List<T>, strength: (T) -> Float): List<T> {
        if (songs.size < 3) return songs
        val ranked = songs.sortedByDescending(strength)
        val first = ranked[0]
        val last = ranked[1]
        return listOf(first) + songs.filter { it !== first && it !== last } + listOf(last)
    }
}
