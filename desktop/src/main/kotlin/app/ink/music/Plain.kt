package app.ink.music

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A YouTube song as a plain MP4 file, for a DLNA receiver (the phone's Plain; 2026-10-31, the Mac to the
 * Denon: songs skipping): YouTube's audio is fragmented MP4, and a Denon given it couldn't seek, said "transitioning"
 * all through it, and, given it as next, opened it minutes early and found it gone. Here: the whole stream fetched at
 * once, put back as a plain MP4 ([Unfragment]: nothing re-encoded), kept in the cache (the last dozen), served from
 * here ([Serve]). Made the first time it's asked for (the next song's while this one plays: [prepare]); asked twice
 * at once, made once.
 */
object Plain {
    private val making = ConcurrentHashMap<String, Any>()

    private val dir get() = File(Home.cache, "speaker").apply { mkdirs() }

    /** Made ahead, out of sight. */
    fun prepare(s: Song) {
        if (!s.tube) return
        Thread({ runCatching { of(s.id.removePrefix("y")) } }, "plain ahead").apply { isDaemon = true }.start()
    }

    fun of(video: String): File? {
        val done = File(dir, "$video.m4a")
        if (done.length() > 0) return done.also { it.setLastModified(System.currentTimeMillis()) }
        synchronized(making.getOrPut(video) { Any() }) {
            if (done.length() > 0) return done
            val t0 = System.currentTimeMillis()
            try {
                val link = YouTube.stream("y$video", forSpeaker = true) ?: return null
                // the whole file asked for at once (its length is in the link): YouTube slows a plain long read to playing speed
                val whole = Regex("[?&]clen=(\\d+)").find(link)?.groupValues?.get(1)?.toLongOrNull()
                    ?.takeIf { "range=" !in link }?.let { "$link&range=0-${it - 1}" } ?: link
                val conn = URL(whole).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 20_000
                if (conn.responseCode !in 200..299) return null
                val raw = conn.inputStream.use { it.readBytes() }
                // already plain (not fragmented): kept as it came
                val plain = Unfragment.plain(raw) ?: raw
                val part = File(dir, "$video.part")
                part.writeBytes(plain)
                if (!Keep.replace(part, done)) { part.delete(); return null }
                Log.d("plain $video: ${done.length() / 1024} kB in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) {
                Log.d("plain $video: $e"); done.delete(); return null
            }
            // the last dozen kept
            dir.listFiles { f -> f.name.endsWith(".m4a") }?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
            return done.takeIf { it.length() > 0 }
        }
    }
}
