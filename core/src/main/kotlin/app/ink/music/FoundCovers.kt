package app.ink.music

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A cover for a record whose files have none (Bronson, 2026-10-29: "fill it in for them"), both apps: looked up by
 * its name and whose it is in the catalogs ([Curation.cover]: iTunes, then Deezer), the link kept in `dir` so each
 * record is asked about once; one not found is asked again after a week. Two at a time: iTunes turns a crowd away.
 * A miss is kept only when iTunes could be reached, so a record looked at offline isn't written off.
 */
object FoundCovers {
    private val asking = java.util.concurrent.Semaphore(2)
    private const val WEEK = 7L * 24 * 3600 * 1000
    private val nameless = setOf("", "unknown", "unknown album", "unknown artist", "untitled")

    /** The cover's link for the record `album` by `artist`, or null for none (or none yet). Blocking. */
    fun link(dir: File, album: String, artist: String): String? {
        val a = album.trim(); val by = artist.trim()
        if (a.lowercase().trim('[', ']', '<', '>') in nameless || by.lowercase().trim('[', ']', '<', '>') in nameless) return null
        val key = java.security.MessageDigest.getInstance("SHA-1").digest("${a.lowercase()}|${by.lowercase()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val f = File(dir, "found-$key")
        if (f.exists()) {
            val kept = runCatching { f.readText() }.getOrDefault("")
            if (kept.isNotEmpty()) return kept
            if (System.currentTimeMillis() - f.lastModified() < WEEK) return null
        }
        asking.acquire()
        try {
            val url = Curation.cover(a, by, record = true)
            if (url == null && !reachable()) return null
            runCatching { dir.mkdirs(); f.writeText(url.orEmpty()) }
            return url
        } finally { asking.release() }
    }

    private fun reachable(): Boolean = runCatching {
        val c = URL("https://itunes.apple.com/").openConnection() as HttpURLConnection
        c.connectTimeout = 5_000; c.readTimeout = 5_000; c.requestMethod = "HEAD"
        try { c.responseCode > 0 } finally { c.disconnect() }
    }.getOrDefault(false)
}
