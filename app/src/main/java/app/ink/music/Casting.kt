package app.ink.music

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Playing on the speakers and TVs at home, over the Cast protocol spoken by hand (ink's Cast.kt,
 * no Google library: ink music stays free of Play services). Speakers are found on the wifi
 * (mDNS `_googlecast._tcp`). Playing on one hands it the queue from the song playing on, as a
 * Cast queue for its own Default Media Receiver: it moves through the songs by itself, so the
 * music carries on with the phone asleep. Server songs it fetches from the server; the phone's
 * own files from [Serve]. While it plays there, [Playback] shows and steers it.
 */
object Casting {
    private const val CONN = "urn:x-cast:com.google.cast.tp.connection"
    private const val BEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val MEDIA = "urn:x-cast:com.google.cast.media"
    /** Google's Default Media Receiver: plays a queue of links, no app of ours needed on the speaker. */
    private const val APP = "CC1AD845"

    /** A speaker or TV (or a group of them) found on the wifi. */
    /**
     * A speaker or TV (or a group of them, or a receiver) found on the wifi. `kind` "cast" (Cast) or
     * "dlna" (a UPnP media renderer: a Denon's HEOS, a Samsung TV), whose AVTransport is at `control`.
     */
    data class Speaker(
        val key: String, val name: String, val group: Boolean, val address: InetSocketAddress, val model: String = "",
        val kind: String = "cast", val control: String = "", val maker: String = "",
    )

    /** One speaker being played on, whichever way it's spoken to. */
    interface Remote {
        fun open()
        fun close()
        fun toggle(playing: Boolean)
        fun next()
        fun previous()
        fun seek(ms: Long)
        /** Stops our music there (never someone else's). */
        fun stopThere()
    }

    // ---- what a speaker can play ----

    /** Models whose maker says they play hi-res: the Chromecast Audio, 24-bit / 96 kHz (Google's spec). */
    private val HI_RES_MODELS = setOf("chromecast audio")
    private var prefs: android.content.SharedPreferences? = null

    /**
     * Whether `s` plays hi-res: true for a model known to, or one that has played a hi-res file;
     * false for one that refused one (it gets mp3 from then on); null not known yet.
     */
    fun hiRes(s: Speaker): Boolean? {
        val p = prefs
        return when {
            p?.getStringSet("refused", emptySet())?.contains(s.name) == true -> false
            s.model.lowercase() in HI_RES_MODELS -> true
            // HEOS (Denon, Marantz): lossless up to 24-bit / 192 kHz, the maker's spec
            s.kind == "dlna" && s.maker.lowercase().let { "denon" in it || "marantz" in it } -> true
            p?.getStringSet("proven", emptySet())?.contains(s.name) == true -> true
            else -> null
        }
    }

    private fun remember(s: Speaker, key: String) = main.post {
        val p = prefs ?: return@post
        p.edit().putStringSet(key, (p.getStringSet(key, emptySet())!! + s.name)).apply()
        publish()
    }

    private val _speakers = MutableStateFlow<List<Speaker>>(emptyList())
    val speakers: StateFlow<List<Speaker>> = _speakers

    /** Where it plays now (null: the phone), and whether that's still being set up. */
    private val _on = MutableStateFlow<Speaker?>(null)
    val on: StateFlow<Speaker?> = _on
    val connecting = MutableStateFlow(false)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var nsd: NsdManager? = null
    private var finding: NsdManager.DiscoveryListener? = null
    private val found = HashMap<String, Speaker>()
    private var link: Remote? = null

    /** The queue as handed over, so the song playing there can be found in it again (back to the phone). */
    private var handed: List<Song> = emptyList()

    // ---- the cast kept, to come back to ----

    private var files: java.io.File? = null
    private fun kept(ctx: Context) = java.io.File(ctx.filesDir, "cast-now.json")

    /** The speaker played on and the songs handed it (a Cast one: the music goes on there with ink music shut). */
    private fun keep(ctx: Context, to: Speaker, songs: List<Song>) {
        files = ctx.filesDir
        if (to.kind != "cast") return forget()
        runCatching {
            kept(ctx).writeText(JSONObject().put("key", to.key).put("name", to.name).put("group", to.group).put("model", to.model)
                .put("host", to.address.hostString).put("port", to.address.port).put("at", System.currentTimeMillis())
                .put("ids", JSONArray(songs.map { it.id }))
                // a station kept whole (one found by genre is known only the run it was found in)
                .put("radio", JSONArray(songs.filter { it.station }.map { r ->
                    JSONObject().put("id", r.id).put("url", r.uri.toString()).put("name", r.title).put("city", r.artist).put("mime", r.mime)
                })).toString())
        }
    }

    /** No cast to come back to (back on the phone, or ours no longer running there). */
    private fun forget() { files?.let { java.io.File(it, "cast-now.json").delete() } }

    /**
     * ink music opened again with a cast kept from before (in the last twelve hours): the speaker asked whether our
     * player still runs there; if it does, it's joined as it is (nothing loaded again: its song, place and queue
     * shown, steered from here); if not, forgotten. A tester opened the app and saw the phone's song, not the
     * speaker's. Main thread, once the phone's own player is known (its queue holds the songs handed over).
     */
    fun rejoin(ctx: Context) {
        if (link != null) return
        files = ctx.filesDir
        val o = runCatching { JSONObject(kept(ctx).readText()) }.getOrNull() ?: return
        if (System.currentTimeMillis() - o.optLong("at") > 12 * 3_600_000L) return forget()
        val mine = Playback.handover()?.first.orEmpty().associateBy { it.id }
        val radio = o.optJSONArray("radio")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().associate { r ->
            r.getString("id") to Song(
                id = r.getString("id"), uri = android.net.Uri.parse(r.getString("url")), title = r.getString("name"), artist = r.optString("city"),
                album = "", albumId = r.getString("id"), albumArtist = r.getString("name"), duration = 0, disc = 1, track = 0, year = 0,
                format = "radio", mime = r.optString("mime", "audio/mpeg"),
            )
        }
        val ids = o.optJSONArray("ids") ?: return forget()
        Stations.curated(ctx)
        val songs = (0 until ids.length()).mapNotNull { i ->
            ids.optString(i).let { mine[it] ?: Library.song(it) ?: YouTubeMusic.known(it) ?: Stations.known(it) ?: radio[it] }
        }
        if (songs.isEmpty()) return forget()
        val to = Speaker(o.optString("key"), o.optString("name"), o.optBoolean("group"),
            InetSocketAddress(o.optString("host"), o.optInt("port")), o.optString("model"))
        android.util.Log.d("ink-cast", "${to.name}: asked again, ${songs.size} songs kept")
        Serve.start(ctx)
        if (prefs == null) prefs = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE)
        handed = songs
        _on.value = to
        Playback.pauseHere()
        Playback.showCastQueue(songs)
        link = Link(to, songs, 0L, joining = true).also { it.open() }
    }

    // ---- finding them ----

    fun find(ctx: Context) {
        if (finding != null) return
        if (prefs == null) prefs = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE)
        val m = ctx.getSystemService(NsdManager::class.java) ?: return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { main.post { resolve(m, info) } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { found.remove(info.serviceName); publish() } }
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if (finding === this) finding = null } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        try { m.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { return }
        nsd = m
        finding = l
        // and the receivers that speak DLNA instead (a Denon, a Samsung TV)
        Dlna.find { r -> main.post { if (finding != null) { found[r.key] = r; publish() } } }
    }

    fun stopFinding() {
        finding?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        finding = null
    }

    private fun resolve(m: NsdManager, info: NsdServiceInfo) {
        if (finding == null || info.serviceName in found) return
        @Suppress("DEPRECATION")
        m.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                if (code == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ resolve(m, info) }, 400)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = (if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else null)
                    ?: @Suppress("DEPRECATION") info.host ?: return
                val name = info.attributes["fn"]?.let { String(it) }?.trim() ?: info.serviceName
                // a group (Google Home's "Everywhere") answers on its own high port; a speaker on 8009
                val model = info.attributes["md"]?.let { String(it) }?.trim().orEmpty()
                val s = Speaker(info.serviceName, name, info.port != 8009, InetSocketAddress(host, info.port), model)
                main.post { found[info.serviceName] = s; publish() }
            }
        })
    }

    private fun publish() {
        // a TV that answers both ways is listed once, by Cast (it says more)
        val castAt = found.values.filter { it.kind == "cast" }.map { it.address.address }.toSet()
        _speakers.value = found.values.filter { it.kind == "cast" || it.address.address !in castAt }
            .sortedWith(compareBy({ it.group }, { it.kind != "cast" }, { it.name.lowercase() }))
    }

    // ---- playing there ----

    /**
     * Plays `queue` on `to` from `index` at `at` ms: the phone pauses, the speaker takes the rest of
     * the queue (a hundred songs at most). Main thread.
     */
    fun play(ctx: Context, to: Speaker, queue: List<Song>, index: Int, at: Long) {
        if (queue.isEmpty()) return
        link?.close()
        handed = queue.drop(index.coerceIn(queue.indices)).take(100)
        Playback.showCastQueue(handed)
        keep(ctx, to, handed)
        Serve.start(ctx)
        if (prefs == null) prefs = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE)
        _on.value = to
        connecting.value = true
        Playback.pauseHere()
        link = (if (to.kind == "dlna") Dlna.Link(to, handed, at) else Link(to, handed, at)).also { it.open() }
    }

    /** From one speaker to another: the song it was on, from where it was; the first stops. Main thread. */
    fun moveTo(ctx: Context, to: Speaker) {
        val l = link ?: return
        val now = Playback.now.value
        val index = now?.song?.let { s -> handed.indexOfFirst { it.id == s.id } }?.takeIf { it >= 0 } ?: 0
        val at = now?.positionNow() ?: 0L
        val queue = handed
        l.stopThere()
        play(ctx, to, queue, index, at)
    }

    /** Back to the phone: the song the speaker was on, from where it was; the speaker stops. Main thread. */
    fun bringBack() {
        val l = link ?: return
        val now = Playback.now.value
        val index = now?.song?.let { s -> handed.indexOfFirst { it.id == s.id } }?.takeIf { it >= 0 } ?: 0
        val at = now?.positionNow() ?: 0L
        val playing = now?.playing == true
        val queue = handed
        l.stopThere()
        forget()
        end()
        if (queue.isNotEmpty()) Playback.play(queue, index, at, playing)
    }

    /** No longer casting (stopped here, or the speaker let go): the phone shows its own player again. */
    private fun end() {
        link?.close()
        link = null
        _on.value = null
        connecting.value = false
        Playback.castEnded()
    }

    fun toggle() { val n = Playback.now.value ?: return; link?.toggle(n.playing) }
    /** Play there (said outright, so saying it twice does nothing more). */
    fun resume() { link?.toggle(false) }

    /** The speaker's queue from its `index`th song (up next, tapped while casting). Main thread. */
    fun jump(ctx: Context, index: Int) {
        val to = _on.value ?: return
        val queue = handed
        if (index in queue.indices) play(ctx, to, queue, index, 0L)
    }
    fun next() { link?.next() }
    fun previous() { link?.previous() }
    fun seek(ms: Long) { link?.seek(ms) }

    /** What the speaker says it plays, as the screens show it (any thread). */
    internal fun show(n: Now?) = main.post {
        if (link == null) return@post
        connecting.value = false
        if (n != null) Playback.showCast(n)
    }

    /** One speaker, talked to on its own thread. */
    /** The connection let go of (the speaker gone, or taken over): back to the phone's own player. Any thread. */
    internal fun lost(r: Remote) = main.post { if (link === r) end() }

    /** A Cast speaker, talked to on its own thread. */
    private class Link(val to: Speaker, val queue: List<Song>, val at: Long, val joining: Boolean = false) : Remote {
        @Volatile private var open = true
        @Volatile private var socket: SSLSocket? = null
        private var out: DataOutputStream? = null
        @Volatile private var transport: String? = null
        @Volatile private var session = 0
        @Volatile private var appSession: String? = null
        // joining a cast from before: it's loaded there already
        private var loaded = joining
        // the speaker answered at all (joining one that's gone: let go at once, not tried again)
        @Volatile private var heard = false
        private var requests = 10
        private var current: Song? = queue.firstOrNull()
        private var duration = 0L
        // the originals first (lossless), unless this speaker refused one before; mp3 after a refusal
        @Volatile private var lossless = hiRes(to) != false

        override fun open() { Thread({ run() }, "cast ${to.name}").apply { isDaemon = true }.start() }
        override fun toggle(playing: Boolean) = command(if (playing) "PAUSE" else "PLAY")
        override fun next() = command("QUEUE_UPDATE") { put("jump", 1) }
        override fun previous() = command("QUEUE_UPDATE") { put("jump", -1) }
        override fun seek(ms: Long) = command("SEEK") { put("currentTime", ms / 1000.0) }

        override fun close() {
            open = false
            val s = socket ?: return
            Thread { runCatching { s.close() } }.start()
        }

        /** Our receiver closed on the speaker, or the queue never loaded: no coming back. */
        @Volatile private var over = false

        private fun run() {
            // a connection that breaks while the speaker plays on (the wifi blinking, "connection abort"): found
            // again, up to five times, a second more apart each; the speaker's queue left as it is and its song read
            // again. It had let go at once, and the phone showed its own song while the speaker went on with another
            var tries = 0
            while (open) {
                val began = SystemClock.elapsedRealtime()
                try { talk() } catch (e: Exception) { android.util.Log.d("ink-cast", "${to.name}: $e") }
                if (!open || over || !loaded || (joining && !heard)) break
                if (SystemClock.elapsedRealtime() - began > 60_000) tries = 0
                if (++tries > 5) break
                android.util.Log.d("ink-cast", "${to.name}: lost, again in ${tries}s ($tries of 5)")
                runCatching { socket?.close() }
                Thread.sleep(1_000L * tries)
                transport = null
            }
            // the connection gone for good (the speaker off the wifi, or another phone took it over)
            if (open) main.post { if (link === this) end() }
        }

        private fun talk() {
            val s = tls.socketFactory.createSocket() as SSLSocket
            socket = s
            android.util.Log.d("ink-cast", "${to.name}: connecting to ${to.address}")
            s.connect(to.address, 5_000)
            s.soTimeout = 6_000
            val input = DataInputStream(s.inputStream.buffered())
            out = DataOutputStream(s.outputStream)
            send("receiver-0", CONN, JSONObject().put("type", "CONNECT"))
            // found again: what the speaker is doing asked, nothing launched or loaded again
            if (loaded) send("receiver-0", RECEIVER, JSONObject().put("type", "GET_STATUS").put("requestId", ++requests))
            else send("receiver-0", RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", APP).put("requestId", 1))
            var quiet = 0
            while (open) {
                val (ns, body) = try { read(input) } catch (e: SocketTimeoutException) {
                    if (++quiet > 2) { android.util.Log.d("ink-cast", "${to.name}: silent, let go"); return }
                    send("receiver-0", BEAT, JSONObject().put("type", "PING"))
                    continue
                }
                quiet = 0
                val m = runCatching { JSONObject(body) }.getOrNull() ?: continue
                when (m.optString("type")) {
                    "PING" -> send("receiver-0", BEAT, JSONObject().put("type", "PONG"))
                    "RECEIVER_STATUS" -> receiver(m.optJSONObject("status"))
                    "MEDIA_STATUS" -> if (ns == MEDIA) media(m.optJSONArray("status")?.optJSONObject(0))
                    "LOAD_FAILED", "LOAD_CANCELLED" -> { android.util.Log.d("ink-cast", "${to.name}: ${m.optString("type")} ${body.take(300)}"); if (!loaded) { over = true; return } }
                    "LAUNCH_ERROR", "INVALID_REQUEST" -> android.util.Log.d("ink-cast", "${to.name}: ${body.take(300)}")
                    "CLOSE" -> if (m.optString("sourceId") == transport) { android.util.Log.d("ink-cast", "${to.name}: closed"); over = true; forget(); return }
                }
            }
        }

        /** Our receiver running: connect to it and hand it the queue, once. Another app taking the speaker over: done. */
        private fun receiver(status: JSONObject?) {
            val apps = status?.optJSONArray("applications") ?: JSONArray()
            val ours = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.optString("appId") == APP }
            android.util.Log.d("ink-cast", "${to.name}: receiver ${(0 until apps.length()).joinToString { apps.getJSONObject(it).optString("displayName") }} (ours ${ours != null}, loaded $loaded)")
            heard = true
            if (ours == null) {
                if (loaded) { open = false; forget(); main.post { if (link === this) end() } }
                return
            }
            appSession = ours.optString("sessionId")
            val t = ours.optString("transportId")
            if (t != transport) {
                transport = t
                send(t, CONN, JSONObject().put("type", "CONNECT"))
                // found again: where the speaker is, in which song
                if (loaded) send(t, MEDIA, JSONObject().put("type", "GET_STATUS").put("requestId", ++requests))
            }
            if (!loaded) {
                loaded = true
                load(t, queue, at)
            }
        }

        /**
         * Hands the speaker `songs` to play, from `from` ms into the first: as many as fit one message (a Cast message
         * is 64 KB at most: a hundred songs' links overran it, and the speaker hung up), the rest added as it nears
         * the end of them ([more]).
         */
        private fun load(t: String, songs: List<Song>, from: Long) {
            pending = songs
            handedOver = queue.size - songs.size   // songs is the queue from some song on (all, or from one refused)
            val first = chunk()
            android.util.Log.d("ink-cast", "${to.name}: queue of ${first.length()} of ${songs.size}, the first ${first.optJSONObject(0)?.optJSONObject("media")?.let { it.optString("contentType") + " " + it.optString("contentId").substringBefore('?').take(80) }}")
            send(t, MEDIA, JSONObject().put("type", "QUEUE_LOAD").put("requestId", ++requests).put("items", first)
                .put("startIndex", 0).put("currentTime", from / 1000.0).put("repeatMode", "REPEAT_OFF"))
        }

        /** Songs not yet handed over, and how many have been. */
        private var pending: List<Song> = emptyList()
        private var handedOver = 0

        /** The next songs as Cast items, as many as fit in about 40 KB (taken from [pending]). */
        private fun chunk(): JSONArray {
            val items = JSONArray()
            var size = 0
            while (pending.isNotEmpty()) {
                val s = pending.first()
                val url = Serve.url(s, lossless)
                if (url != null) {
                    val meta = JSONObject().put("metadataType", 3).put("title", s.title).put("artist", s.artist)
                        .put("albumName", s.album).put("albumArtist", s.albumArtist)
                    Serve.art(s)?.let { meta.put("images", JSONArray().put(JSONObject().put("url", it))) }
                    val item = JSONObject().put("autoplay", true).put("preloadTime", 8).put("media", JSONObject()
                        .put("contentId", url).put("streamType", "BUFFERED")
                        .put("contentType", Serve.type(s, lossless)).put("metadata", meta)
                        .put("customData", JSONObject().put("id", s.id)))
                    val n = item.toString().length
                    if (items.length() > 0 && size + n > 40_000) break
                    items.put(item); size += n
                }
                pending = pending.drop(1); handedOver++
            }
            return items
        }

        /** The speaker nearing the end of what it has: the next songs added to its queue. */
        private fun more() {
            val t = transport ?: return
            if (pending.isEmpty()) return
            val items = chunk()
            if (items.length() == 0) return
            android.util.Log.d("ink-cast", "${to.name}: ${items.length()} more added, ${pending.size} still to come")
            send(t, MEDIA, JSONObject().put("type", "QUEUE_INSERT").put("requestId", ++requests).put("mediaSessionId", session).put("items", items))
        }

        private fun media(st: JSONObject?) {
            st ?: return
            session = st.optInt("mediaSessionId", session)
            st.optJSONObject("media")?.let { m ->
                m.optJSONObject("customData")?.optString("id")?.let { id -> queue.firstOrNull { it.id == id }?.let { current = it } }
                duration = (m.optDouble("duration", 0.0) * 1000).toLong()
            }
            // a few songs from the end of what the speaker has: the next ones handed over
            current?.let { c -> if (pending.isNotEmpty() && queue.indexOf(c) >= handedOver - 3) more() }
            val state = st.optString("playerState")
            android.util.Log.d("ink-cast", "${to.name}: $state ${st.optString("idleReason")}")
            val s0 = current
            // a song the speaker couldn't play as the original: it gets mp3 from here on, from that song
            if (state == "IDLE" && st.optString("idleReason") == "ERROR" && lossless && s0 != null && s0.server) {
                lossless = false
                if (s0.hiRes) remember(to, "refused")
                val t = transport ?: return
                load(t, queue.drop(queue.indexOf(s0).coerceAtLeast(0)), 0L)
                return
            }
            // a hi-res original playing: this speaker plays hi-res, said from now on
            if (state == "PLAYING" && lossless && s0 != null && s0.server && s0.hiRes && hiRes(to) == null) remember(to, "proven")
            // the queue played out: the speaker stops; the phone shows the last song stopped
            val s = current ?: return
            val playing = state == "PLAYING" || state == "BUFFERING"
            show(Now(
                song = s, title = s.title, artist = s.artist, album = s.album,
                duration = duration.takeIf { it > 0 } ?: s.duration,
                position = (st.optDouble("currentTime", 0.0) * 1000).toLong(),
                at = SystemClock.elapsedRealtime(),
                playing = playing, moving = state == "PLAYING",
                index = queue.indexOf(s).coerceAtLeast(0), count = queue.size,
                device = to.name,
                quality = Serve.quality(s, lossless),
            ))
        }

        fun command(type: String, more: JSONObject.() -> Unit = {}) {
            val t = transport ?: return
            io.execute {
                runCatching {
                    send(t, MEDIA, JSONObject().put("type", type).put("mediaSessionId", session).put("requestId", ++requests).apply(more))
                }
            }
        }

        /** Stops our receiver on the speaker (only if it's still ours: another phone's music is left alone). */
        override fun stopThere() {
            val id = appSession ?: return
            io.execute { runCatching { send("receiver-0", RECEIVER, JSONObject().put("type", "STOP").put("sessionId", id).put("requestId", ++requests)) } }
        }

        // a CastMessage: version, source, destination, namespace, payload type (string), payload
        private fun send(to: String, ns: String, body: JSONObject) {
            val b = ByteArrayOutputStream()
            fun varint(v: Long) { var n = v; while (true) { val x = (n and 0x7f).toInt(); n = n ushr 7; if (n == 0L) { b.write(x); return }; b.write(x or 0x80) } }
            fun text(field: Int, s: String) { val bytes = s.toByteArray(); varint((field shl 3 or 2).toLong()); varint(bytes.size.toLong()); b.write(bytes) }
            varint(1 shl 3); varint(0)
            text(2, "sender-inkmusic"); text(3, to); text(4, ns)
            varint(5 shl 3); varint(0)
            text(6, body.toString())
            val o = out ?: return
            synchronized(o) { o.writeInt(b.size()); b.writeTo(o); o.flush() }
        }

        private fun read(input: DataInputStream): Pair<String, String> {
            val n = input.readInt()
            if (n !in 0..262_144) throw IllegalStateException("frame")
            val f = ByteArray(n).also { input.readFully(it) }
            var i = 0
            fun varint(): Long { var v = 0L; var shift = 0; while (true) { val c = f[i++].toInt(); v = v or ((c and 0x7f).toLong() shl shift); if (c and 0x80 == 0) return v; shift += 7 } }
            var ns = ""
            var body = ""
            while (i < n) {
                val key = varint().toInt()
                when (key and 7) {
                    0 -> varint()
                    2 -> {
                        val len = varint().toInt()
                        val s = String(f, i, len)
                        i += len
                        when (key ushr 3) { 4 -> ns = s; 6 -> body = s }
                    }
                    else -> throw IllegalStateException("wire")
                }
            }
            return ns to body
        }
    }

    // Cast devices sign their own certificates. Only addresses mDNS found on this wifi are dialled,
    // and only a certificate that is current and signed by its own key is taken (as ink does).
    private val tls: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    throw java.security.cert.CertificateException("not a server")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val c = chain.firstOrNull() ?: throw java.security.cert.CertificateException("no certificate")
                    c.checkValidity()
                    c.verify(c.publicKey)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }
    }
}
