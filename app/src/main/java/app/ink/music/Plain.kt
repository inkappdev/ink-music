package app.ink.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * A YouTube song as a plain MP4 file, for a DLNA receiver (Bronson, 2026-10-30): YouTube's audio is fragmented MP4, a
 * stream with no length or index at its head, and a Denon given it couldn't seek, never knew the song's length, said
 * "transitioning" all through it, raced through its start, and, given it as next, opened it minutes early and found it
 * gone when it got there. Here: the whole stream fetched at once, its audio put in a plain MP4 as it is (Android's own
 * extractor and muxer; nothing re-encoded), kept in the cache (the last dozen), served from the phone ([Serve]). Made
 * the first time it's asked for (the next song's while this one plays: [prepare]); asked twice at once, made once.
 */
object Plain {
    private val making = ConcurrentHashMap<String, Any>()

    private fun dir(c: Context) = File(c.cacheDir, "speaker").apply { mkdirs() }

    /** Made ahead, out of sight. */
    fun prepare(c: Context, s: Song) {
        if (!s.tube) return
        Thread({ runCatching { of(c, s.id.removePrefix("y")) } }, "plain ahead").apply { isDaemon = true }.start()
    }

    fun of(c: Context, video: String): File? {
        val done = File(dir(c), "$video.m4a")
        if (done.length() > 0) return done.also { it.setLastModified(System.currentTimeMillis()) }
        synchronized(making.getOrPut(video) { Any() }) {
            if (done.length() > 0) return done
            val t0 = System.currentTimeMillis()
            val raw = File(dir(c), "$video.raw")
            try {
                val link = YouTubeMusic.stream("y$video", true)?.first ?: return null
                // the whole file asked for at once (its length is in the link): YouTube slows a plain long read to playing speed
                val whole = Regex("[?&]clen=(\\d+)").find(link)?.groupValues?.get(1)?.toLongOrNull()
                    ?.takeIf { "range=" !in link }?.let { "$link&range=0-${it - 1}" } ?: link
                val conn = URL(whole).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 20_000
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { i -> raw.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                remux(raw, done)
                android.util.Log.d("ink-cast", "plain $video: ${done.length() / 1024} kB in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) {
                android.util.Log.w("ink-cast", "plain $video: $e"); done.delete(); return null
            } finally { raw.delete() }
            // the last dozen kept
            dir(c).listFiles { f -> f.name.endsWith(".m4a") }?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
            return done.takeIf { it.length() > 0 }
        }
    }

    /** The audio of `from` put as it is into a plain MP4 at `to`. */
    private fun remux(from: File, to: File) {
        val ex = MediaExtractor()
        ex.setDataSource(from.path)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val mux = MediaMuxer(to.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val t = mux.addTrack(ex.getTrackFormat(track))
            mux.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(t, buf, info)
                ex.advance()
            }
            mux.stop()
        } finally { runCatching { mux.release() }; ex.release() }
    }
}
