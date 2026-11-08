package app.ink.music

/**
 * A cover for a record whose files have none (2026-10-29), both apps: looked up by
 * its name and whose it is in the catalogs ([Curation.cover]: iTunes, then Deezer; then the app's `elsewhere`), the link kept in `dir` so each
 * record is asked about once; one not found is asked again after a week. Two at a time: iTunes turns a crowd away.
 * A miss is kept only when iTunes could be reached, so a record looked at offline isn't written off.
 */
object FoundCovers {
    private val gate = Signal()
    private var asking = 0
    private const val WEEK = 7L * 24 * 3600 * 1000
    private const val RETRIED = 1794155438000L   // 2026-11-08, when this was written
    private val nameless = setOf("", "unknown", "unknown album", "unknown artist", "untitled")

    /** The cover's link for the record `album` by `artist`, or null for none (or none yet). Blocking. */
    fun link(dir: File, album: String, artist: String, elsewhere: ((String, String) -> String?)? = null): String? {
        val a = album.trim(); val by = artist.trim()
        if (a.lowercase().trim('[', ']', '<', '>') in nameless || by.lowercase().trim('[', ']', '<', '>') in nameless) return null
        val key = sha1("${a.lowercase()}|${by.lowercase()}".encodeToByteArray()).hex()
        val f = File(dir, "found-$key")
        if (f.exists()) {
            val kept = runCatching { f.readText() }.getOrDefault("")
            if (kept.isNotEmpty()) return kept
            // a miss from before the names were asked several ways (2026-11-08) asked again now
            if (nowMs() - f.lastModified() < WEEK && f.lastModified() > RETRIED) return null
        }
        // two at a time
        gate.run { while (asking >= 2) gate.await(60_000); asking++ }
        try {
            // the catalogs, then the app's own last place (YouTube Music's record of that name: 2026-11-08, "make sure an
            // album always has art")
            val url = Curation.cover(a, by, record = true) ?: elsewhere?.let { runCatching { it(a, by) }.getOrNull() }
            if (url == null && !reachable()) return null
            runCatching { dir.mkdirs(); f.writeText(url.orEmpty()) }
            return url
        } finally { gate.run { asking--; gate.wake() } }
    }

    private fun reachable(): Boolean = runCatching {
        http("https://itunes.apple.com/", "HEAD", connectMs = 5_000, readMs = 5_000, follow = 0).code > 0
    }.getOrDefault(false)
}
