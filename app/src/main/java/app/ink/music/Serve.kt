package app.ink.music

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A speaker fetches the phone's own songs from here while it plays them (a server song it fetches
 * from the server). Only on this wifi, only under a random path made fresh each time ink music
 * starts, only songs and covers: nothing else of the phone's is reachable. Ranges are answered,
 * so the speaker can seek.
 */
object Serve {
    private var socket: ServerSocket? = null
    private var base: String? = null
    private var app: Context? = null
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val pool = Executors.newCachedThreadPool()

    @Synchronized
    fun start(c: Context) {
        app = c.applicationContext
        val ip = address(c) ?: return
        val s = socket ?: runCatching { ServerSocket(0) }.getOrNull()?.also { srv ->
            socket = srv
            Thread({ while (!srv.isClosed) runCatching { srv.accept() }.getOrNull()?.let { k -> pool.execute { answer(k) } } }, "serve")
                .apply { isDaemon = true }.start()
        } ?: return
        base = "http://$ip:${s.localPort}/$token"
    }

    /** Where a speaker fetches `s`: the server's stream, or the phone's file from here. */
    fun url(s: Song, lossless: Boolean): String? = if (s.station) s.uri.toString() else if (s.tube) YouTubeMusic.stream(s.id, true)?.first else if (s.server) {
        // the original where the speaker can take it; else mp3 at 320, which every speaker plays
        Server.account.value?.let { a ->
            if (lossless) Server.url(a, "stream", "id" to s.id.removePrefix("n"), "format" to "raw")
            else Server.url(a, "stream", "id" to s.id.removePrefix("n"), "format" to "mp3", "maxBitRate" to 320)
        }
    } else base?.let { "$it/f/${s.id.removePrefix("l")}" }

    /** Its cover, for the speaker's screen: the server's, or the phone's from here. */
    fun art(s: Song): String? = if (s.station) null else if (s.tube) s.art else if (s.server) {
        s.art?.let { art -> Server.account.value?.let { Server.url(it, "getCoverArt", "id" to art, "size" to 600) } }
    } else base?.let { "$it/a/${s.id.removePrefix("l")}" }

    fun type(s: Song, lossless: Boolean): String = when {
        s.station -> s.mime
        s.tube -> YouTubeMusic.stream(s.id, true)?.second ?: "audio/mp4"
        s.server && lossless -> s.mime.ifEmpty { "audio/flac" }
        s.server -> "audio/mpeg"
        else -> runCatching { app?.contentResolver?.getType(s.uri) }.getOrNull() ?: "audio/flac"
    }

    /** What reaches the speaker, as said: "flac 24/96", or "mp3 320" when it's the server's mp3. */
    fun quality(s: Song, lossless: Boolean): String = when {
        s.station -> "radio"
        s.tube -> "aac"
        s.server && !lossless -> "mp3 320"
        s.quality.isNotEmpty() -> s.quality
        else -> (runCatching { app?.contentResolver?.getType(s.uri) }.getOrNull() ?: "").substringAfter('/').removePrefix("x-")
    }

    /** The phone's address on the wifi (IPv4: the speakers dial it). */
    private fun address(c: Context): String? {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun answer(k: Socket) = runCatching {
        k.soTimeout = 15_000
        k.use { sock ->
            val input = sock.getInputStream().bufferedReader()
            val first = input.readLine() ?: return@use
            var range: String? = null
            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("range:", ignoreCase = true)) range = h.substringAfter(':').trim()
            }
            val parts = first.split(' ')
            val head = parts.getOrNull(0) == "HEAD"
            val path = parts.getOrNull(1).orEmpty().split('/').filter { it.isNotEmpty() }
            val out = sock.getOutputStream()
            if (path.size != 3 || path[0] != token) return@use status(out, 404)
            val id = path[2].toLongOrNull() ?: return@use status(out, 404)
            when (path[1]) {
                "f" -> file(out, id, range, head)
                "a" -> cover(out, id, head)
                else -> status(out, 404)
            }
        }
    }

    private fun file(out: OutputStream, id: Long, range: String?, head: Boolean) {
        val c = app ?: return status(out, 404)
        val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val pfd = runCatching { c.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return status(out, 404)
        pfd.use {
            val size = it.statSize
            val type = runCatching { c.contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
            // "bytes=start-end" (end or start may be missing)
            var from = 0L
            var to = size - 1
            val partial = range?.startsWith("bytes=") == true
            if (partial) {
                val (a, b) = range!!.removePrefix("bytes=").substringBefore(',').split('-').let { p -> p.getOrNull(0).orEmpty() to p.getOrNull(1).orEmpty() }
                if (a.isEmpty()) { from = (size - (b.toLongOrNull() ?: 0)).coerceAtLeast(0) }
                else { from = a.toLongOrNull() ?: 0; to = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1) }
                if (from > to || from >= size) return status(out, 416)
            }
            val length = to - from + 1
            val h = StringBuilder()
                .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n")
                .append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n")
            if (partial) h.append("Content-Range: bytes $from-$to/$size\r\n")
            out.write(h.append("\r\n").toString().toByteArray())
            if (head) return out.flush()
            FileInputStream(it.fileDescriptor).use { f ->
                f.channel.position(from)
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            out.flush()
        }
    }

    private fun cover(out: OutputStream, id: Long, head: Boolean) {
        val song = Library.song("l$id") ?: return status(out, 404)
        val bmp = Covers.kept(song.albumId)?.image?.asAndroidBitmap()
            ?: runCatching { app?.contentResolver?.loadThumbnail(song.uri, android.util.Size(600, 600), null) }.getOrNull()
            ?: return status(out, 404)
        val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
        if (!head) out.write(jpg)
        out.flush()
    }

    private fun status(out: OutputStream, code: Int) {
        val word = when (code) { 404 -> "Not Found"; 416 -> "Range Not Satisfiable"; else -> "Error" }
        out.write("HTTP/1.1 $code $word\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }
}
