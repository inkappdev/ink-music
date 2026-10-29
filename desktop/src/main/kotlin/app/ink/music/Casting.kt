package app.ink.music

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
 * Playing on the speakers and TVs at home (the phone's Casting, its Cast and DLNA links as they are): the Cast
 * protocol spoken by hand, no Google library. Speakers are found on the network (mDNS `_googlecast._tcp`, by JmDNS
 * here; DLNA's renderers by SSDP). Playing on one hands it the queue from the song playing on, as a Cast queue for
 * its own Default Media Receiver: it moves through the songs by itself. Server songs it fetches from the server;
 * the computer's own files from [Serve]. While it plays there, [Player] shows and steers it.
 */
object Casting {
    private const val CONN = "urn:x-cast:com.google.cast.tp.connection"
    private const val BEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val MEDIA = "urn:x-cast:com.google.cast.media"
    /** Google's Default Media Receiver: plays a queue of links, no app of ours needed on the speaker. */
    private const val APP = "CC1AD845"

    /**
     * A speaker or TV (or a group of them, or a receiver) found on the network. `kind` "cast" (Cast) or
     * "dlna" (a UPnP media renderer: a Denon's HEOS, a Samsung TV), whose AVTransport is at `control`.
     */
    data class Speaker(
        val key: String, val name: String, val group: Boolean, val address: InetSocketAddress, val model: String = "",
        val kind: String = "cast", val control: String = "", val maker: String = "",
        val volumeControl: String = "",   // a DLNA receiver's RenderingControl, where its volume's set ("": none)
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
        /** The speaker's own volume, 0 to 1. */
        fun volume(level: Float) {}
        /** More songs at the queue's end (radio's, found while it plays). */
        fun extend(songs: List<Song>) {}
        /** A song right after the one playing (play next). */
        fun playNext(s: Song) {}
    }

    // ---- what a speaker can play ----

    /** Models whose maker says they play hi-res: the Chromecast Audio, 24-bit / 96 kHz (Google's spec). */
    private val HI_RES_MODELS = setOf("chromecast audio")
    // speakers that refused a hi-res file, or played one (cast.json)
    private val said = java.io.File(Home.dir, "cast.json")
    private fun saidOf(key: String): Set<String> = runCatching {
        JSONObject(said.readText()).optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    }.getOrNull().orEmpty()

    /**
     * Whether `s` plays hi-res: true for a model known to, or one that has played a hi-res file;
     * false for one that refused one (it gets mp3 from then on); null not known yet.
     */
    fun hiRes(s: Speaker): Boolean? = when {
        s.name in saidOf("refused") -> false
        s.model.lowercase() in HI_RES_MODELS -> true
        // HEOS (Denon, Marantz): lossless up to 24-bit / 192 kHz, the maker's spec
        s.kind == "dlna" && s.maker.lowercase().let { "denon" in it || "marantz" in it } -> true
        s.name in saidOf("proven") -> true
        else -> null
    }

    private fun remember(s: Speaker, key: String) = main.post {
        runCatching {
            val o = runCatching { JSONObject(said.readText()) }.getOrDefault(JSONObject())
            o.put(key, JSONArray(saidOf(key) + s.name))
            Home.write(said, o.toString())
        }
        publish()
    }

    private val _speakers = MutableStateFlow<List<Speaker>>(emptyList())
    val speakers: StateFlow<List<Speaker>> = _speakers

    /** Where it plays now (null: this computer), and whether that's still being set up. */
    private val _on = MutableStateFlow<Speaker?>(null)
    val on: StateFlow<Speaker?> = _on
    val connecting = MutableStateFlow(false)

    /** The screens' thread (the phone's main Handler): Swing's. */
    private val main = object { fun post(r: () -> Unit) = javax.swing.SwingUtilities.invokeLater(r) }
    private val io = Executors.newSingleThreadExecutor()
    private val found = HashMap<String, Speaker>()
    private var link: Remote? = null
    private var finding: javax.jmdns.JmDNS? = null

    /** The queue as handed over, so the song playing there can be found in it again (back to the computer). */
    private var handed: List<Song> = emptyList()

    // ---- finding them ----

    /** Looks for speakers (Cast by mDNS, DLNA by SSDP), adding each as it answers; a few seconds of DLNA each time asked. */
    fun find() {
        Thread({
            if (finding == null) runCatching {
                val at = Serve.address()?.let { java.net.InetAddress.getByName(it) }
                val j = if (at != null) javax.jmdns.JmDNS.create(at) else javax.jmdns.JmDNS.create()
                finding = j
                j.addServiceListener("_googlecast._tcp.local.", object : javax.jmdns.ServiceListener {
                    override fun serviceAdded(e: javax.jmdns.ServiceEvent) { j.requestServiceInfo(e.type, e.name, 3_000) }
                    override fun serviceRemoved(e: javax.jmdns.ServiceEvent) { main.post { found.remove(e.name); publish() } }
                    override fun serviceResolved(e: javax.jmdns.ServiceEvent) {
                        val info = e.info
                        val host = info.inet4Addresses.firstOrNull() ?: return
                        val name = info.getPropertyString("fn")?.trim() ?: e.name
                        // a group (Google Home's "Everywhere") answers on its own high port; a speaker on 8009
                        val model = info.getPropertyString("md")?.trim().orEmpty()
                        val s = Speaker(e.name, name, info.port != 8009, InetSocketAddress(host, info.port), model)
                        main.post { found[e.name] = s; publish() }
                    }
                })
            }
        }, "cast find").apply { isDaemon = true }.start()
        // and the receivers that speak DLNA instead (a Denon, a Samsung TV)
        Dlna.find { r -> main.post { found[r.key] = r; publish() } }
    }

    private fun publish() {
        // a TV that answers both ways is listed once, by Cast (it says more)
        val castAt = found.values.filter { it.kind == "cast" }.map { it.address.address }.toSet()
        _speakers.value = found.values.filter { it.kind == "cast" || it.address.address !in castAt }
            .sortedWith(compareBy({ it.group }, { it.kind != "cast" }, { it.name.lowercase() }))
    }

    // ---- playing there ----

    /**
     * Plays `queue` on `to` from `index` at `at` ms: the computer pauses, the speaker takes the rest of
     * the queue (a hundred songs at most). Main thread.
     */
    fun play(to: Speaker, queue: List<Song>, index: Int, at: Long) {
        if (queue.isEmpty()) return
        link?.close()
        handed = queue.drop(index.coerceIn(queue.indices)).take(100)
        Serve.start(to.address.address)
        _on.value = to
        connecting.value = true
        Player.pauseHere(handed)
        // the first YouTube song's stream looked up while the speaker connects, not after it asks
        handed.first().takeIf { it.tube }?.let { s -> Thread { runCatching { YouTube.stream(s.id, forSpeaker = true) } }.start() }
        link = (if (to.kind == "dlna") Dlna.Link(to, handed, at) else Link(to, handed, at)).also { it.open() }
        // the queue on after the hundred handed over
        queue.drop(index + 100).takeIf { it.isNotEmpty() }?.let { extend(it) }
    }

    /** From one speaker to another: the song it was on, from where it was; the first stops. Main thread. */
    fun moveTo(to: Speaker) {
        val l = link ?: return
        val now = Player.now.value
        val index = now?.song?.let { s -> handed.indexOfFirst { it.id == s.id } }?.takeIf { it >= 0 } ?: 0
        val queue = handed
        l.stopThere()
        play(to, queue, index, Player.position.value)
    }

    /** Back to this computer: the song the speaker was on, from where it was; the speaker stops. Main thread. */
    fun bringBack() {
        val l = link ?: return
        val now = Player.now.value
        val index = now?.song?.let { s -> handed.indexOfFirst { it.id == s.id } }?.takeIf { it >= 0 } ?: 0
        val at = Player.position.value
        val playing = now?.playing == true
        val queue = handed
        l.stopThere()
        end()
        if (queue.isNotEmpty()) Player.takeBack(queue, index, at, playing)
    }

    /** Casting over (the speaker gone, taken over, or brought back): this computer's own player again. */
    private fun end() {
        link?.close()
        link = null
        volume.value = null
        _on.value = null
        connecting.value = false
        Player.castEnded()
    }

    fun toggle() { val n = Player.now.value ?: return; link?.toggle(n.playing) }
    fun next() { link?.next() }
    fun previous() { link?.previous() }
    fun seek(ms: Long) { link?.seek(ms) }

    /** The speaker's volume, 0 to 1, as it says it (null: not heard yet, or not casting). */
    val volume = MutableStateFlow<Float?>(null)
    private var volumeSetAt = 0L

    /** The speaker's volume set from here, in 5% steps. */
    fun setVolume(level: Float) {
        val l = link ?: return
        val v = Math.round(level.coerceIn(0f, 1f) * 20) / 20f
        volumeSetAt = SystemClock.elapsedRealtime()
        volume.value = v
        l.volume(v)
    }

    /** A DLNA receiver's volume as it says it (any thread). */
    internal fun heard(r: Remote, level: Float) = main.post { if (link === r) heardVolume(level) }

    private fun heardVolume(level: Float) {
        if (SystemClock.elapsedRealtime() - volumeSetAt < 1_500 || volume.value == level) return
        volume.value = level
    }

    /** The speaker's queue from its `index`th song (up next, clicked while casting). Main thread. */
    fun jump(index: Int) {
        val to = _on.value ?: return
        val queue = handed
        if (index in queue.indices) play(to, queue, index, 0L)
    }

    /** Songs for the queue's end (radio's, found while it plays), added to the speaker's queue. Main thread. */
    fun extend(songs: List<Song>) {
        val l = link ?: return
        val ids = handed.map { it.id }.toHashSet()
        val more = songs.filter { it.id !in ids }
        if (more.isEmpty()) return
        handed = handed + more
        Player.showCastQueue(handed)
        l.extend(more)
    }

    /**
     * Play next on the speaker: the song right after the one playing there (it went to the speaker's queue's end,
     * as radio's songs do, till 2026-10-29). Main thread.
     */
    fun playNext(s: Song) {
        val l = link ?: return
        val playing = Player.now.value?.song?.id?.let { id -> handed.indexOfFirst { it.id == id } } ?: -1
        handed = handed.take(playing + 1) + s + handed.drop(playing + 1)
        Player.showCastQueue(handed)
        l.playNext(s)
    }

    /** What the speaker says it plays, as the screens show it (any thread). */
    internal fun show(n: Now?) = main.post {
        if (link == null) return@post
        connecting.value = false
        if (n != null) Player.showCast(n)
    }

    /** The connection let go of (the speaker gone, or taken over): back to this computer's own player. Any thread. */
    internal fun lost(r: Remote) = main.post { if (link === r) end() }

    /** The cast kept to come back to: not on the desktop yet (it stays open while it plays). */
    private fun forget() {}

    /** A Cast speaker, talked to on its own thread. */
    private class Link(val to: Speaker, first: List<Song>, val at: Long, val joining: Boolean = false) : Remote {
        // the queue as handed over, longer when radio adds to its end ([extend])
        @Volatile private var queue: List<Song> = first
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
            // after whatever's still to be sent (a STOP, back to the phone: closed first, the speaker played on under it)
            io.execute { runCatching { s.close() } }
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
                try { talk() } catch (e: Exception) { Log.d("${to.name}: $e") }
                if (!open || over || !loaded || (joining && !heard)) break
                if (SystemClock.elapsedRealtime() - began > 60_000) tries = 0
                if (++tries > 5) break
                Log.d("${to.name}: lost, again in ${tries}s ($tries of 5)")
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
            Log.d("${to.name}: connecting to ${to.address}")
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
                    if (++quiet > 2) { Log.d("${to.name}: silent, let go"); return }
                    send("receiver-0", BEAT, JSONObject().put("type", "PING"))
                    continue
                }
                quiet = 0
                val m = runCatching { JSONObject(body) }.getOrNull() ?: continue
                when (m.optString("type")) {
                    "PING" -> send("receiver-0", BEAT, JSONObject().put("type", "PONG"))
                    "RECEIVER_STATUS" -> receiver(m.optJSONObject("status"))
                    "MEDIA_STATUS" -> if (ns == MEDIA) media(m.optJSONArray("status")?.optJSONObject(0))
                    "QUEUE_ITEM_IDS" -> inserted(m.optJSONArray("itemIds"))
                    "LOAD_FAILED", "LOAD_CANCELLED" -> { Log.d("${to.name}: ${m.optString("type")} ${body.take(300)}"); if (!loaded) { over = true; return } }
                    "LAUNCH_ERROR", "INVALID_REQUEST" -> Log.d("${to.name}: ${body.take(300)}")
                    "CLOSE" -> if (m.optString("sourceId") == transport) { Log.d("${to.name}: closed"); over = true; forget(); return }
                }
            }
        }

        /** Our receiver running: connect to it and hand it the queue, once. Another app taking the speaker over: done. */
        private fun receiver(status: JSONObject?) {
            val apps = status?.optJSONArray("applications") ?: JSONArray()
            val ours = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.optString("appId") == APP }
            Log.d("${to.name}: receiver ${(0 until apps.length()).joinToString { apps.getJSONObject(it).optString("displayName") }} (ours ${ours != null}, loaded $loaded)")
            heard = true
            status?.optJSONObject("volume")?.takeIf { it.has("level") }?.let { v ->
                val level = v.optDouble("level").toFloat()
                main.post { if (link === this) heardVolume(level) }
            }
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
        @Synchronized private fun load(t: String, songs: List<Song>, from: Long) {
            pending = songs
            handedOver = queue.size - songs.size   // songs is the queue from some song on (all, or from one refused)
            val first = chunk()
            Log.d("${to.name}: queue of ${first.length()} of ${songs.size}, the first ${first.optJSONObject(0)?.optJSONObject("media")?.let { it.optString("contentType") + " " + it.optString("contentId").substringBefore('?').take(80) }}")
            send(t, MEDIA, JSONObject().put("type", "QUEUE_LOAD").put("requestId", ++requests).put("items", first)
                .put("startIndex", 0).put("currentTime", from / 1000.0).put("repeatMode", "REPEAT_OFF"))
        }

        @Synchronized private fun nearEnd() { current?.let { c -> if (pending.isNotEmpty() && queue.indexOf(c) >= handedOver - 3) more() } }

        @Volatile private var finished = false

        /** Radio's songs: after the rest, or, the queue already played out, played from the first of them. */
        @Synchronized override fun extend(songs: List<Song>) {
            queue = queue + songs
            if (finished) {
                val t = transport ?: return
                finished = false
                current = songs.first()
                io.execute { runCatching { load(t, songs, 0L) } }
            } else {
                pending = pending + songs
                io.execute { runCatching { nearEnd() } }
            }
        }

        /** Songs not yet handed over, and how many have been. */
        private var pending: List<Song> = emptyList()
        private var handedOver = 0

        /** The speaker's item playing (its own id for it), and play next's songs waiting for its list of ids. */
        @Volatile private var currentItem = 0
        @Volatile private var waiting: List<JSONObject> = emptyList()

        /**
         * Right after the song playing: nothing after it handed over yet, it's the first of what's still to come,
         * handed over at once; else put in before the speaker's next item (its ids asked for: [inserted]).
         */
        @Synchronized override fun playNext(s: Song) {
            val at = current?.let { queue.indexOf(it) } ?: -1
            queue = queue.take(at + 1) + s + queue.drop(at + 1)
            if (!loaded) return   // the load takes the whole queue
            if (finished) {
                val t = transport ?: return
                finished = false
                current = s
                io.execute { runCatching { load(t, queue.drop(at + 1), 0L) } }
                return
            }
            if (at + 1 >= handedOver) {
                pending = listOf(s) + pending
                handedOver = at + 1
                io.execute { runCatching { more() } }
                return
            }
            val item = item(s) ?: return
            handedOver++
            waiting = listOf(item) + waiting
            if (session != 0) io.execute { runCatching { askIds() } }   // else once the speaker says its session ([media])
        }

        private fun askIds() {
            val t = transport ?: return
            send(t, MEDIA, JSONObject().put("type", "QUEUE_GET_ITEM_IDS").put("mediaSessionId", session).put("requestId", ++requests))
        }

        /** The speaker's item ids, in order: play next's songs put in before the one after the playing item. */
        @Synchronized private fun inserted(ids: JSONArray?) {
            val t = transport ?: return
            if (waiting.isEmpty() || ids == null) return
            val list = (0 until ids.length()).map { ids.optInt(it) }
            val before = list.indexOf(currentItem).takeIf { it >= 0 }?.let { list.getOrNull(it + 1) }
            val body = JSONObject().put("type", "QUEUE_INSERT").put("requestId", ++requests).put("mediaSessionId", session)
                .put("items", JSONArray(waiting))
            before?.let { body.put("insertBefore", it) }
            waiting = emptyList()
            Log.d("${to.name}: play next, before item $before")
            send(t, MEDIA, body)
        }

        /** A song as a Cast queue item (none for one with no link to it). */
        private fun item(s: Song): JSONObject? {
            val url = Serve.url(s, lossless) ?: return null
            val meta = JSONObject().put("metadataType", 3).put("title", s.title).put("artist", s.artist)
                .put("albumName", s.album).put("albumArtist", s.albumArtist)
            Serve.art(s)?.let { meta.put("images", JSONArray().put(JSONObject().put("url", it))) }
            return JSONObject().put("autoplay", true).put("preloadTime", 8).put("media", JSONObject()
                .put("contentId", url).put("streamType", "BUFFERED")
                .put("contentType", Serve.type(s, lossless)).put("metadata", meta)
                .put("customData", JSONObject().put("id", s.id)))
        }

        /** The next songs as Cast items, as many as fit in about 40 KB (taken from [pending]). */
        private fun chunk(): JSONArray {
            val items = JSONArray()
            var size = 0
            while (pending.isNotEmpty()) {
                val s = pending.first()
                val item = item(s)
                if (item != null) {
                    val n = item.toString().length
                    if (items.length() > 0 && size + n > 40_000) break
                    items.put(item); size += n
                }
                pending = pending.drop(1); handedOver++
            }
            return items
        }

        /** The speaker nearing the end of what it has: the next songs added to its queue. */
        @Synchronized private fun more() {
            val t = transport ?: return
            if (session == 0) return   // not loaded yet: the load takes the whole queue
            if (pending.isEmpty()) return
            val items = chunk()
            if (items.length() == 0) return
            Log.d("${to.name}: ${items.length()} more added, ${pending.size} still to come")
            send(t, MEDIA, JSONObject().put("type", "QUEUE_INSERT").put("requestId", ++requests).put("mediaSessionId", session).put("items", items))
        }

        private fun media(st: JSONObject?) {
            st ?: return
            session = st.optInt("mediaSessionId", session)
            currentItem = st.optInt("currentItemId", currentItem)
            if (waiting.isNotEmpty() && session != 0) askIds()
            st.optJSONObject("media")?.let { m ->
                m.optJSONObject("customData")?.optString("id")?.let { id -> queue.firstOrNull { it.id == id }?.let { current = it } }
                duration = (m.optDouble("duration", 0.0) * 1000).toLong()
            }
            // a few songs from the end of what the speaker has: the next ones handed over
            nearEnd()
            val state = st.optString("playerState")
            // the last song played out: nothing more there (radio's, found later, starts it again: [extend])
            if (state == "IDLE" && st.optString("idleReason") == "FINISHED" && current == queue.lastOrNull()) finished = true
            else if (state == "PLAYING" || state == "BUFFERING") finished = false
            Log.d("${to.name}: $state ${st.optString("idleReason")}")
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

        override fun volume(level: Float) = io.execute {
            runCatching { send("receiver-0", RECEIVER, JSONObject().put("type", "SET_VOLUME").put("volume", JSONObject().put("level", level.toDouble())).put("requestId", ++requests)) }
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

/** What a speaker says it plays (the phone's Now, as the links tell it). */
data class Now(
    val song: Song, val title: String, val artist: String, val album: String, val duration: Long, val position: Long,
    val at: Long, val playing: Boolean, val moving: Boolean, val index: Int, val count: Int, val device: String, val quality: String,
)

/** The phone's clock, as the links use it. */
object SystemClock {
    fun elapsedRealtime() = System.nanoTime() / 1_000_000
    fun sleep(ms: Long) = Thread.sleep(ms)
}

/** What casting says, on the error stream when asked (-Dink.log). */
object Log {
    private val on = System.getProperty("ink.log") != null
    fun d(text: String) { if (on) System.err.println("ink-cast: $text") }
}
