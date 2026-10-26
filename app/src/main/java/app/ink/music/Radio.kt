package app.ink.music

import android.content.Context

/**
 * Radio: when the queue runs out, more like the last song. Your server's first (Navidrome's similar
 * songs: Last.fm's idea of it, from what you have), else YouTube Music's (the songs it sets beside
 * one; the full build only). Run in the player's process, so it goes on with the app closed.
 */
object Radio {
    /** How many come at a time; and at most how many by one artist among them. */
    private const val BATCH = 10
    private const val SAME_ARTIST = 3

    /**
     * Songs to follow the seed (blocking): none already queued (`queued`: ids, and title/artist
     * keys, so the same song from another source counts too).
     */
    fun after(c: Context, id: String, title: String, artist: String, queued: Set<String>): List<Song> {
        val found = if (id.startsWith("y")) YouTubeMusic.related(id) else Server.similar(c, id, title, artist).ifEmpty {
            YouTubeMusic.find(title, artist)?.let { YouTubeMusic.related(it.id) }.orEmpty()
        }
        val seen = HashSet(queued)
        val by = HashMap<String, Int>()
        return found.filter { s ->
            val who = s.artist.lowercase()
            if (!seen.add(s.id) || !seen.add(key(s.title, s.artist)) || (by[who] ?: 0) >= SAME_ARTIST) return@filter false
            by[who] = (by[who] ?: 0) + 1
            true
        }.take(BATCH)
    }

    /** A song as said, whatever it's from: its title and first artist, case and marks aside. */
    fun key(title: String, artist: String) =
        title.lowercase().substringBefore(" (").filter { it.isLetterOrDigit() } + "/" + artist.lowercase().substringBefore(",").filter { it.isLetterOrDigit() }
}
