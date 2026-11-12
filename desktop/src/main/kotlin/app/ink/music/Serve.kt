package app.ink.music

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A speaker fetches this computer's own songs from here while it plays them (a server song it fetches from the
 * server; the phone's Serve, the desktop's way). Only on this network, only under a random path made fresh each
 * time ink music starts, only songs and covers: nothing else of the computer's is reachable. Ranges are answered,
 * so the speaker can seek.
 */
object Serve {
    private var socket: ServerSocket? = null
    private var base: String? = null
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val pool = Executors.newCachedThreadPool()

    /** Ready to serve `toward` (a speaker): at the address this computer reaches it from. */
    @Synchronized
    fun start(toward: java.net.InetAddress? = null) {
        val ip = toward?.let(::from) ?: address() ?: return
        val s = socket ?: runCatching { ServerSocket(0) }.getOrNull()?.also { srv ->
            socket = srv
            Thread({ while (!srv.isClosed) runCatching { srv.accept() }.getOrNull()?.let { k -> pool.execute { answer(k) } } }, "serve")
                .apply { isDaemon = true }.start()
        } ?: return
        base = "http://$ip:${s.localPort}/$token"
    }

    /**
     * Where a speaker fetches `s`: the server's stream, a station's, or this computer's file from here. A YouTube song
     * `plain` (for a DLNA receiver) is made a plain file here and served from here ([Plain]).
     */
    fun url(s: Song, lossless: Boolean, plain: Boolean = false): String? = if (s.station || s.episode) s.path else if (s.tube) {
        // from here: its stream found only when the speaker asks for it
        base?.let { "$it/${if (plain) "p" else "y"}/${s.id.removePrefix("y")}" }
    } else if (s.server) {
        // the original where the speaker can take it; else mp3 at 320, which every speaker plays
        Server.account.value?.let { a ->
            if (lossless) Server.url(a, "stream", "id" to s.id.removePrefix("n"), "format" to "raw")
            else Server.url(a, "stream", "id" to s.id.removePrefix("n"), "format" to "mp3", "maxBitRate" to 320)
        }
    } else base?.let { "$it/f/${s.id.removePrefix("l")}" }

    /** Its cover, for the speaker's screen: the server's, YouTube's, or this computer's from here. */
    fun art(s: Song): String? = if (s.station) null else if (s.tube || s.episode) s.art else if (s.server) {
        s.art?.let { art -> Server.account.value?.let { Server.url(it, "getCoverArt", "id" to art, "size" to 600) } }
    } else base?.let { "$it/a/${s.id.removePrefix("l")}" }

    fun type(s: Song, lossless: Boolean): String = when {
        s.station -> if (s.path.lowercase().let { "aac" in it || ".m4a" in it }) "audio/aac" else "audio/mpeg"
        // an episode by its link's ending (most are mp3)
        s.episode -> if (s.path.substringBefore('?').lowercase().let { it.endsWith(".m4a") || it.endsWith(".mp4") || it.endsWith(".aac") }) "audio/mp4" else "audio/mpeg"
        s.tube -> "audio/mp4"   // a speaker's is always the m4a (YouTube.stream)
        s.server && !lossless -> "audio/mpeg"
        else -> mime(s.format)
    }

    private fun mime(format: String) = when (format.lowercase()) {
        "flac" -> "audio/flac"; "mp3" -> "audio/mpeg"; "m4a", "alac", "aac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "aif", "aiff" -> "audio/aiff"
        else -> "application/octet-stream"
    }

    /** What reaches the speaker, as said: "flac 24/96", or "mp3 320" when it's the server's mp3. */
    fun quality(s: Song, lossless: Boolean): String = when {
        s.station -> "radio"
        s.episode -> "podcast"
        s.tube -> "aac"
        s.server && !lossless -> "mp3 320"
        else -> s.quality
    }

    /** The address this computer reaches `there` from (its route there: no packet sent). */
    private fun from(there: java.net.InetAddress): String? = runCatching {
        java.net.DatagramSocket().use { it.connect(there, 9); (it.localAddress as? Inet4Address)?.takeIf { a -> !a.isAnyLocalAddress }?.hostAddress }
    }.getOrNull()

    /** This computer's address on the network as an address (for announcing it, mDNS). */
    fun local(): java.net.InetAddress? = address()?.let { java.net.InetAddress.getByName(it) }

    /** This computer's address on the network (IPv4, a home network's: the speakers and the phone dial it). */
    fun address(): String? = addresses().firstOrNull()

    /**
     * Every address this computer has on a network, its likeliest first: the one its way out to the internet goes from,
     * then real adapters before made-up ones. Before (2026-11-12) the first of any came first, and a laptop's WSL, Hyper-V,
     * VirtualBox or VPN adapter could be it: announced there, shown there and the firewall read there, the phone never
     * got in (Java's isVirtual is only for sub-interfaces, so those all look real). Link-local (169.254) left out.
     */
    fun addresses(): List<String> = runCatching {
        val out = from(java.net.InetAddress.getByName("1.1.1.1"))
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLinkLocalAddress && !it.isAnyLocalAddress }.map { n to it } }
            .sortedWith(compareBy({ (n, _) -> madeUp(n) }, { (_, a) -> a.hostAddress != out }, { (_, a) -> !a.isSiteLocalAddress }, { (n, _) -> n.isPointToPoint }))
            .map { it.second.hostAddress }.distinct()
    }.getOrDefault(emptyList())

    /** An adapter some program made (a VM's, WSL's, a container's, a VPN's), by the name it goes by. */
    private fun madeUp(n: NetworkInterface): Boolean {
        val name = (n.displayName.orEmpty() + " " + n.name.orEmpty()).lowercase()
        return listOf("vethernet", "hyper-v", "virtualbox", "vmware", "vmnet", "vbox", "wsl", "docker", "podman", "virbr", "veth",
            "tap-", "tun", "wireguard", "tailscale", "zerotier", "hamachi", "vpn", "npcap", "bluetooth", "utun", "bridge").any { it in name }
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
            // a YouTube song: its stream found now, and the speaker sent there
            if (path[1] == "y") return@use tube(out, path[2])
            // a YouTube song as a plain file, made now if it isn't yet
            if (path[1] == "p") {
                if (!path[2].all { it.isLetterOrDigit() || it == '-' || it == '_' }) return@use status(out, 404)
                Log.d("serve: ${path[2]} plain${if (range != null) " $range" else ""}")
                return@use send(out, Plain.of(path[2]) ?: return@use status(out, 404), "audio/mp4", range, head)
            }
            val song = Library.song("l" + path[2])?.takeIf { !it.server && !it.tube && !it.station } ?: return@use status(out, 404)
            when (path[1]) {
                "f" -> file(out, song, range, head)
                "a" -> cover(out, song, head)
                else -> status(out, 404)
            }
        }
    }

    /** A YouTube song's stream, found as the speaker asks for it: a redirect there (a speaker follows one). */
    private fun tube(out: OutputStream, video: String) {
        if (!video.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return status(out, 404)
        val link = YouTube.stream("y$video", forSpeaker = true) ?: return status(out, 404)
        Log.d("serve: $video sent on")
        out.write("HTTP/1.1 302 Found\r\nLocation: $link\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun file(out: OutputStream, song: Song, range: String?, head: Boolean) {
        send(out, File(song.path).takeIf { it.isFile } ?: return status(out, 404), mime(song.format), range, head)
    }

    private fun send(out: OutputStream, f: File, type: String, range: String?, head: Boolean) {
        RandomAccessFile(f, "r").use { raf ->
            val size = raf.length()
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
            raf.seek(from)
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
            }
            out.flush()
        }
    }

    private fun cover(out: OutputStream, song: Song, head: Boolean) {
        val jpg = Covers.jpeg(song) ?: return status(out, 404)
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
