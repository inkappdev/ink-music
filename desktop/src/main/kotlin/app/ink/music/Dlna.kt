package app.ink.music

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors

/**
 * Receivers that speak DLNA (UPnP media renderers) rather than Cast: a Denon's HEOS, a Samsung TV.
 * Found by SSDP (who on the wifi renders media?), each one's description read for its name, maker
 * and AVTransport, then steered with SOAP: a song's link and the next one queued, play, pause,
 * seek, and where it is, asked every second. Unlike a Cast speaker it holds only the song and the
 * next, so ink music hands it each next song as one begins.
 */
object Dlna {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    /** DLNA's word that a link can be jumped about in by byte ranges (OP=01; Serve answers ranges, YouTube's too). */
    private const val SEEKABLE = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** Looks for renderers for a few seconds, handing each one found to `found` (off the main thread). */
    fun find(found: (Casting.Speaker) -> Unit) = Thread({
        val seen = HashSet<String>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.soTimeout = 1_000
                val ask = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(ask, ask.size, group, 1900)) }
                val end = SystemClock.elapsedRealtime() + 4_000
                val buf = ByteArray(4096)
                while (SystemClock.elapsedRealtime() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    val location = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: continue
                    if (!seen.add(location)) continue
                    describe(location)?.let(found)
                }
            }
        }
    }, "dlna find").apply { isDaemon = true }.start()

    /** A renderer's description: its name, maker, model, and where its AVTransport answers. */
    private fun describe(location: String): Casting.Speaker? = runCatching {
        val xml = get(location)
        fun tag(name: String, within: String = xml) = Regex("<$name>([^<]*)</$name>").find(within)?.groupValues?.get(1)?.trim().orEmpty()
        val name = unescape(tag("friendlyName")).ifEmpty { return null }
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }.firstOrNull { "AVTransport" in tag("serviceType", it) } ?: return null
        val control = URL(URL(tag("URLBase").ifEmpty { location }), tag("controlURL", service)).toString()
        // its volume, where it has one (RenderingControl)
        val rendering = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }.firstOrNull { "RenderingControl" in tag("serviceType", it) }
            ?.let { URL(URL(tag("URLBase").ifEmpty { location }), tag("controlURL", it)).toString() }.orEmpty()
        val url = URL(location)
        Casting.Speaker(
            key = "dlna:" + tag("UDN").ifEmpty { location }, name = name, group = false,
            address = InetSocketAddress(url.host, if (url.port > 0) url.port else 80),
            model = unescape(tag("modelName")), kind = "dlna", control = control, maker = unescape(tag("manufacturer")),
            volumeControl = rendering,
        )
    }.getOrNull()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4_000; c.readTimeout = 4_000
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** "0:03:45" (or "00:03:45.120") as ms. */
    private fun ms(t: String): Long = runCatching {
        val p = t.trim().split(':')
        if (p.size != 3) return 0L
        ((p[0].toLong() * 3600 + p[1].toLong() * 60) * 1000 + (p[2].toDouble() * 1000).toLong())
    }.getOrDefault(0L)

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    /** One receiver being played on: the song and the next handed to it, and where it is asked every second. */
    class Link(private val to: Casting.Speaker, first: List<Song>, private val at: Long) : Casting.Remote {
        @Volatile private var open = true
        private val io = Executors.newSingleThreadExecutor()
        // originals only where the maker says it plays them (HEOS: lossless to 24/192); else mp3 320
        private val lossless = Casting.hiRes(to) == true
        // the queue and its links, longer when radio adds to its end ([extend])
        @Volatile private var queue: List<Song> = first
        @Volatile private var urls = first.map { Serve.url(it, lossless).orEmpty() }
        @Volatile private var index = 0
        @Volatile private var nextSet = -1
        @Volatile private var lastState = ""
        @Volatile private var position = 0L
        // the time kept here while the receiver's own is no good ("transitioning"): from where a song began or was sought
        @Volatile private var clockPos = 0L
        @Volatile private var clockAt = 0L
        private var stuckSince = 0L
        private var nudged = -1
        private fun timeFrom(from: Long) { clockPos = from; clockAt = SystemClock.elapsedRealtime(); stuckSince = 0L }
        private var misses = 0

        // asking where it is on a thread of its own; play, pause and the rest on `io`, so they never wait behind it
        override fun open() {
            Thread({
                if (!start(0, at)) return@Thread
                while (open) {
                    SystemClock.sleep(1_000)
                    if (open) runCatching { poll() }.onFailure { if (++misses > 5) { open = false; Casting.lost(this) } }
                }
            }, "dlna ${to.name}").apply { isDaemon = true }.start()
        }

        override fun close() { open = false; io.shutdown() }

        /** Hands it song `i` from `from` ms and plays; then queues the one after. */
        private fun start(i: Int, from: Long): Boolean {
            if (i !in queue.indices || urls[i].isEmpty()) return false
            index = i
            nextSet = -1
            timeFrom(from)
            if (runCatching { soap("SetAVTransportURI", "<CurrentURI>${esc(urls[i])}</CurrentURI><CurrentURIMetaData>${esc(didl(queue[i], urls[i]))}</CurrentURIMetaData>") }.isFailure) {
                open = false; Casting.lost(this); return false
            }
            runCatching { soap("Play", "<Speed>1</Speed>") }
            if (from > 2_000) {
                // a receiver takes a seek only once it plays
                for (k in 0 until 10) { if (runCatching { state() }.getOrNull() == "PLAYING") break; SystemClock.sleep(300) }
                runCatching { soap("Seek", "<Unit>REL_TIME</Unit><Target>${clock(from)}</Target>") }
            }
            queueNext()
            return true
        }

        /** The song after the one playing, handed over so it carries on by itself (where the receiver can). */
        private fun queueNext() {
            val n = index + 1
            if (n !in queue.indices || nextSet == n) return
            nextSet = n
            runCatching { soap("SetNextAVTransportURI", "<NextURI>${esc(urls[n])}</NextURI><NextURIMetaData>${esc(didl(queue[n], urls[n]))}</NextURIMetaData>") }
        }

        private fun state(): String = Regex("<CurrentTransportState>([^<]*)<").find(soap("GetTransportInfo", ""))?.groupValues?.get(1).orEmpty()

        private fun poll() {
            val st = state()
            val pos = soap("GetPositionInfo", "")
            misses = 0
            val track = unescape(Regex("<TrackURI>([^<]*)<").find(pos)?.groupValues?.get(1).orEmpty())
            position = ms(Regex("<RelTime>([^<]*)<").find(pos)?.groupValues?.get(1).orEmpty())
            val length = ms(Regex("<TrackDuration>([^<]*)<").find(pos)?.groupValues?.get(1).orEmpty())
            // it moved on to the queued song by itself: queue the one after that
            urls.indexOfFirst { it.isNotEmpty() && it == track }.takeIf { it >= 0 && it != index }?.let { index = it; timeFrom(0); queueNext() }
            // it stopped at a song's end without taking the next: hand it over
            if (st == "STOPPED" && lastState == "PLAYING" && index + 1 in queue.indices) { lastState = st; start(index + 1, 0); return }
            // something else is playing there now (another app took it): done
            if (track.isNotEmpty() && urls.none { it == track } && st == "PLAYING") { open = false; Casting.lost(this); return }
            lastState = st
            // the Denon (HEOS) says "transitioning" for minutes at a YouTube song's start, playing all the while, its
            // position stuck or jumping about (the lock screen's bar with it): timed here instead, and told "play" once,
            // a few seconds in (a pause and play by hand set it right)
            val now = SystemClock.elapsedRealtime()
            val stuck = st == "TRANSITIONING"
            if (stuck) {
                if (stuckSince == 0L) stuckSince = now
                if (now - stuckSince > 4_000 && nudged != index) { nudged = index; runCatching { soap("Play", "<Speed>1</Speed>") } }
                position = clockPos + now - clockAt
            } else { stuckSince = 0L; clockPos = position; clockAt = now }
            // its volume (0 to 100), so the phone's buttons start from where it is, and follow its own knob
            if (to.volumeControl.isNotEmpty()) runCatching { soap("GetVolume", "<Channel>Master</Channel>", RC, to.volumeControl) }.getOrNull()
                ?.let { Regex("<CurrentVolume>(\\d+)<").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?.let { Casting.heard(this, it.coerceIn(0, 100) / 100f) }
            val s = queue[index]
            Casting.show(Now(
                song = s, title = s.title, artist = s.artist, album = s.album,
                duration = length.takeIf { it > 0 } ?: s.duration, position = position, at = SystemClock.elapsedRealtime(),
                playing = st == "PLAYING" || stuck, moving = st == "PLAYING" || stuck,
                index = index, count = queue.size, device = to.name, quality = Serve.quality(s, lossless),
            ))
        }

        override fun toggle(playing: Boolean) = io.execute { runCatching { if (playing) soap("Pause", "") else soap("Play", "<Speed>1</Speed>") } }.let { }
        override fun next() = io.execute { if (index + 1 in queue.indices) start(index + 1, 0) }.let { }
        override fun previous() = io.execute { if (position > 3_000 || index == 0) runCatching { soap("Seek", "<Unit>REL_TIME</Unit><Target>0:00:00</Target>") } else start(index - 1, 0) }.let { }
        override fun seek(ms: Long) = io.execute { timeFrom(ms); runCatching { soap("Seek", "<Unit>REL_TIME</Unit><Target>${clock(ms)}</Target>") } }.let { }
        /** Radio's songs after the rest: the next one handed over, or, stopped at the end, played. */
        override fun extend(songs: List<Song>) = io.execute {
            val wasLast = index == queue.size - 1
            urls = urls + songs.map { Serve.url(it, lossless).orEmpty() }
            queue = queue + songs
            if (wasLast && lastState == "STOPPED") start(index + 1, 0) else queueNext()
        }.let { }
        /** Right after the song playing, handed over as its next (or, stopped at the end, played). */
        override fun playNext(s: Song) = io.execute {
            val wasLast = index == queue.size - 1
            urls = urls.take(index + 1) + Serve.url(s, lossless).orEmpty() + urls.drop(index + 1)
            queue = queue.take(index + 1) + s + queue.drop(index + 1)
            nextSet = -1
            if (wasLast && lastState == "STOPPED") start(index + 1, 0) else queueNext()
        }.let { }
        override fun stopThere() { Thread { runCatching { soap("Stop", "") } }.start() }
        override fun volume(level: Float) {
            if (to.volumeControl.isEmpty()) return
            io.execute { runCatching { soap("SetVolume", "<Channel>Master</Channel><DesiredVolume>${Math.round(level * 100)}</DesiredVolume>", RC, to.volumeControl) } }
        }

        /** A song as DIDL-Lite, so the receiver shows its title, artist and cover. */
        private fun didl(s: Song, url: String): String {
            val art = Serve.art(s)?.let { "<upnp:albumArtURI>${esc(it)}</upnp:albumArtURI>" }.orEmpty()
            val type = Serve.type(s, lossless)
            // its length and that it can be jumped about in, for a receiver that reads them (the Denon reads the file)
            val length = if (s.duration > 0) " duration=\"${clock(s.duration)}.000\"" else ""
            return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>${esc(s.title)}</dc:title><upnp:artist>${esc(s.artist)}</upnp:artist><upnp:album>${esc(s.album)}</upnp:album>$art" +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$type:$SEEKABLE\"$length>${esc(url)}</res></item></DIDL-Lite>"
        }

        /** One AVTransport action (or RenderingControl's, its volume); its answer's body. Throws when the receiver can't be reached. */
        private fun soap(action: String, args: String, service: String = AVT, at: String = to.control): String {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$service\">" +
                "<InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
            val c = URL(at).openConnection() as HttpURLConnection
            c.connectTimeout = 4_000; c.readTimeout = 6_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            return try {
                c.outputStream.use { it.write(body.toByteArray()) }
                (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    .also { if (c.responseCode !in 200..299 && action != "SetNextAVTransportURI" && action != "Seek") error("soap $action ${c.responseCode}") }
            } finally { c.disconnect() }
        }
    }
}
