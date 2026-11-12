package app.ink.music

/**
 * A genre's station (2026-11-11, Bronson: "when its just youtube its bad"; it was the first playlist of each of YouTube
 * Music's first four shelves, nothing of what its page shows): the page's own picks (the editors' lists, the critics'
 * songs) leading, your songs that fit the genre, YouTube Music's playlists a quarter of it; radio after.
 */
object GenreStation {
    /** Taken in turn, two picks, one of yours, one of theirs, as long as any are left; each song once (by `key`), `most` at most. */
    fun <T> blend(picks: List<T>, yours: List<T>, theirs: List<T>, key: (T) -> String, most: Int = 80): List<T> {
        // the picks' one iterator asked twice a turn: the second pick is the next one
        val p = picks.iterator()
        val turn = listOf(p, p, yours.iterator(), theirs.iterator())
        val seen = HashSet<String>()
        val out = ArrayList<T>()
        while (out.size < most && turn.any { it.hasNext() }) {
            for (it in turn) {
                if (out.size >= most) break
                while (it.hasNext()) { val s = it.next(); if (seen.add(key(s))) { out.add(s); break } }
            }
        }
        return out
    }
}
