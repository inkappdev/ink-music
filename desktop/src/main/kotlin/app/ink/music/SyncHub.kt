package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * The computer's side of [Sync]: paired phones call it on the home wifi (`_inksync._tcp`, port [Sync.PORT]) and the two
 * hand each other what they haven't got: what you've heard, loves, sign-ins, what each was playing. Always listening
 * while the app is open and a phone's paired; pairing itself is the phone's "sign in a computer" ([TubeAccount.await]):
 * one code, and this computer is signed in to what the phone is and kept in step with it from then on.
 */
object SyncHub {
    val kept = Sync.Kept(Home.dir)
    /** The phones paired, as last seen. */
    val peers = MutableStateFlow(kept.peers())
    /** What a paired phone was last playing: to carry on with here. */
    val theirs = MutableStateFlow<Continue.Offer?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var announcing: Process? = null
    private var dns: javax.jmdns.JmDNS? = null
    /** When what plays here last changed (a song, play, pause), so the newer of the two is the one carried on with. */
    @Volatile var playedAt = runCatching { File(Home.dir, "queue.json").lastModified() }.getOrDefault(0L)
    /** When play was last pressed here (paused to playing), so a stop the phone asks for from before it is passed over. */
    @Volatile var resumedAt = 0L
    /** A love the phone made, being done here: not one to tell it about again. */
    @Volatile private var applying = false

    fun start() {
        scope.launch {
            // what the app opens with (the queue it was left with) isn't a change
            var first = true
            var was = false
            Player.now.map { it?.song?.id to it?.playing }.distinctUntilChanged().collect { (id, _) ->
                if (id == null) return@collect
                if (first) { first = false; return@collect }
                playedAt = System.currentTimeMillis()
                if (Player.now.value?.playing == true && !was) resumedAt = playedAt
                was = Player.now.value?.playing == true
                // kept on the server too, for the phone away from home
                Player.now.value?.let { n -> Server.saveQueue(Player.queue.value, n.index, Player.position.value) }
            }
        }
        // the queue the phone left on the server (away from the wifi): offered, when it's the newest
        scope.launch {
            while (true) {
                Server.savedQueue()?.let { q ->
                    if (q.at > (theirs.value?.at ?: 0L)) theirs.value = Continue.Offer("your server", q.songs, q.index, q.position, false, q.at)
                }
                kotlinx.coroutines.delay(5 * 60_000L)
            }
        }
        if (peers.value.isNotEmpty()) listen()
    }

    /** Woken when this computer carries on with a phone's playing, so its player's waiting call answers at once. */
    val handing = Object()

    /** What a phone's player says it plays now (null: nothing, it stopped). */
    private fun live(peer: Sync.Peer, now: JSONObject?) {
        if (now == null) { if (theirs.value?.peer == peer.id) theirs.value = null; return }
        Continue.offer(peer.name, now, ::resolve, peer.id)?.let { theirs.value = it }
    }

    /** A phone paired (from the pairing's sealed hello): kept, and listened for from now on. */
    fun paired(id: String, name: String, key: String) {
        kept.put(Sync.Peer(id, name, key, seen = System.currentTimeMillis(), at = "paired"))
        peers.value = kept.peers()
        listen()
    }

    fun forget(id: String) {
        kept.forget(id)
        peers.value = kept.peers()
        if (peers.value.isEmpty()) stop()
    }

    /** A love made here: told to the phone at its next call (the latest change wins). */
    fun loved(s: Song, on: Boolean) {
        if (applying || s.station) return
        kept.putLove(Sync.songKey(s.title, s.artist), on, System.currentTimeMillis(), wire(s))
    }

    // ---- listening for the phone ----

    @Synchronized private fun listen() {
        if (server != null) return
        val s = runCatching { ServerSocket(Sync.PORT) }.getOrNull() ?: runCatching { ServerSocket(0) }.getOrNull() ?: return
        server = s
        Thread({
            while (!s.isClosed) {
                val k = runCatching { s.accept() }.getOrNull() ?: break
                scope.launch { runCatching { answer(k) }.onFailure { Log.d("sync: $it") }; runCatching { k.close() } }
            }
        }, "sync").apply { isDaemon = true }.start()
        scope.launch {
            runCatching {
                // a Mac's own announcer (as the pairing's: JmDNS fought mDNSResponder); elsewhere JmDNS
                if (Home.mac) announcing = ProcessBuilder("/usr/bin/dns-sd", "-R", TubeAccount.name, Sync.SERVICE, "local", s.localPort.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                else dns = javax.jmdns.JmDNS.create(Serve.local() ?: java.net.InetAddress.getLocalHost())
                    .also { it.registerService(javax.jmdns.ServiceInfo.create("${Sync.SERVICE}.local.", TubeAccount.name, s.localPort, "ink music")) }
            }.onFailure { Log.d("sync dns: $it") }
        }
    }

    /** The app quitting: nothing left listening or announcing (dns-sd would go on saying this computer's here). */
    fun close() = stop()

    @Synchronized private fun stop() {
        runCatching { server?.close() }; server = null
        announcing?.destroy(); announcing = null
        val d = dns; dns = null
        if (d != null) scope.launch { runCatching { d.unregisterAllServices(); d.close() } }
    }

    private fun answer(k: Socket) {
        k.soTimeout = 30_000
        val input = k.getInputStream().buffered()
        fun line(): String { val b = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
        val first = line().split(" ")
        var length = 0
        while (true) { val h = line(); if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(":").trim().toIntOrNull() ?: 0 }
        val body = String(input.readNBytes(length.coerceIn(0, 64 shl 20)))
        val (status, reply) = when (first.getOrNull(0) to first.getOrNull(1)) {
            // the phone's player (the app closed too): what it plays now, as it changes
            "POST" to "/now" -> runCatching {
                Sync.unsealed(kept, body)?.let { (peer, m) ->
                    live(peer, m.optJSONObject("now"))
                    200 to Sync.reply(peer, JSONObject().put("handed", kept.handedAt(peer.id)))
                } ?: (403 to "{}")
            }.getOrElse { 400 to "{}" }
            // and while it plays, waiting here to be told this computer's carried on with it (answered the moment it is)
            "POST" to "/wait" -> runCatching {
                Sync.unsealed(kept, body)?.let { (peer, m) ->
                    val since = m.optLong("since")
                    val until = System.currentTimeMillis() + 25_000
                    synchronized(handing) {
                        while (kept.handedAt(peer.id) <= since && System.currentTimeMillis() < until) handing.wait(maxOf(1L, until - System.currentTimeMillis()))
                    }
                    200 to Sync.reply(peer, JSONObject().put("handed", kept.handedAt(peer.id)))
                } ?: (403 to "{}")
            }.getOrElse { 400 to "{}" }
            "POST" to "/sync" -> runCatching {
                val r = Sync.answer(side, kept, body, k.inetAddress.hostAddress + ":" + (server?.localPort ?: Sync.PORT))
                peers.value = kept.peers()
                if (r == null) 403 to "{}" else 200 to r
            }.getOrElse { Log.d("sync: $it"); 400 to "{}" }
            else -> 404 to "{}"
        }
        val out = reply.toByteArray()
        k.getOutputStream().apply {
            write("HTTP/1.1 $status ${if (status == 200) "OK" else "No"}\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(out); flush()
        }
    }

    // ---- this computer's side ----

    /** A song as the phone's told it. */
    fun wire(s: Song): JSONObject = Sync.song(s.id, s.title, s.artist, s.album, s.albumId, s.albumArtist, s.duration, s.art)

    private var byKey: Pair<List<Song>, Map<String, Song>>? = null
    private fun byKey(): Map<String, Song> {
        val songs = Library.songs.value
        byKey?.takeIf { it.first === songs }?.let { return it.second }
        return songs.associateBy { Sync.songKey(it.title, it.artist) }.also { byKey = songs to it }
    }

    /** A song the phone told of, here: the same one by id (a server's), by its name and artist (a file), else YouTube's or a station by id. */
    fun resolve(o: JSONObject): Song? {
        val id = o.optString("id"); val title = o.optString("title"); val artist = o.optString("artist")
        Library.song(id)?.let { return it }
        byKey()[Sync.songKey(title, artist)]?.let { return it }
        return when {
            id.startsWith("y") -> Song(id, "", title, artist, o.optString("album"), o.optString("albumId").ifEmpty { id }, o.optString("albumArtist").ifEmpty { artist },
                o.optLong("duration"), 1, 0, 0, format = "youtube", art = o.optString("art").ifEmpty { null }).also(YouTube::remember)
            id.startsWith("s") -> Stations.curated().firstOrNull { it.song.id == id }?.song
            else -> null
        }
    }

    val side = object : Sync.Side {
        override val dir: File get() = Home.dir
        override val name: String get() = TubeAccount.name

        override fun mine(line: JSONObject): JSONObject? =
            (Library.song(line.optString("id")) ?: byKey()[Sync.songKey(line.optString("title"), line.optString("artist"))])
                ?.let { JSONObject().put("id", it.id).put("record", it.albumId) }

        override fun now(): JSONObject? {
            val q = Player.queue.value
            if (q.isEmpty()) return null
            val n = Player.now.value
            val at = n?.index ?: 0
            // the queue around what's playing (a long one in full would be most of the message)
            val from = (at - 50).coerceAtLeast(0)
            val part = q.subList(from, minOf(q.size, at + 250))
            return JSONObject().put("queue", JSONArray(part.map(::wire))).put("index", at - from)
                .put("position", Player.position.value).put("playing", n?.playing == true).put("at", playedAt)
        }

        override fun loved(key: String, on: Boolean, song: JSONObject) {
            val s = resolve(song) ?: return
            if ((s.id in Loved.ids.value) == on) return
            applying = true
            try { Loved.toggle(s) } finally { applying = false }
        }

        override fun takeYoutube(auth: String) {
            val o = runCatching { JSONObject(auth) }.getOrNull() ?: return
            if (o.optString("cookie") == TubeAccount.cookie()) return
            TubeAccount.take(o)
        }

        override fun signedIn(what: String) {
            when (what) {
                "server" -> Server.start()
                "listenbrainz" -> ListenBrainz.start(Home.dir)
                "lastfm" -> LastFm.start(Home.dir)
            }
        }

        override fun heardChanged() { Listened.read() }

        override fun theirs(peer: Sync.Peer, now: JSONObject?) {
            theirs.value = now?.let { Continue.offer(peer.name, it, ::resolve, peer.id) }
        }

        override fun resumedAt(): Long = resumedAt

        override fun stop() { if (Player.now.value?.playing == true) Player.toggle() }
    }
}

/** What the other device was playing, offered here: "on your phone: … continue here". */
object Continue {
    data class Offer(val from: String, val songs: List<Song>, val index: Int, val position: Long, val playing: Boolean, val at: Long, val peer: String = "",
                     val device: String = "", val positionAt: Long = 0L, val seen: Long = System.currentTimeMillis()) {
        val song: Song get() = songs[index]
        /** Where it is now: where it was said to be, and on since, if it's playing. */
        fun positionNow(): Long = if (playing && positionAt > 0) position + (System.currentTimeMillis() - positionAt).coerceIn(0, 10 * 60_000L) else position
        /** Playing there now (said in the last few minutes): offered whatever played here before. */
        val live: Boolean get() = playing && System.currentTimeMillis() - seen < 3 * 60_000L
    }

    /** Its queue found here; null if none of it could be, or nothing played there. */
    fun offer(from: String, o: JSONObject, resolve: (JSONObject) -> Song?, peer: String = ""): Offer? {
        val q = o.optJSONArray("queue") ?: return null
        val index = o.optInt("index")
        val songs = ArrayList<Song>(); var at = -1
        for (i in 0 until q.length()) {
            val s = q.optJSONObject(i)?.let(resolve) ?: continue
            if (i == index) at = songs.size
            songs += s
        }
        if (songs.isEmpty() || at < 0) return null
        return Offer(from.lowercase(), songs, at, o.optLong("position"), o.optBoolean("playing"), o.optLong("at"), peer,
            o.optString("device"), o.optLong("positionAt"))
    }

    /** Carried on with here, where it was; the phone told to stop (at its next call, or its player's next ask). */
    fun take(o: Offer) {
        val p = o.positionNow()
        val at = if (p > 5_000 && !o.song.station) p else 0L
        // the phone (or its speaker) told to stop first, so the two don't play over each other
        if (o.peer.isNotEmpty()) {
            SyncHub.kept.handed(o.peer, System.currentTimeMillis())
            synchronized(SyncHub.handing) { SyncHub.handing.notifyAll() }
        }
        Casting.on.value?.let { to -> Casting.play(to, o.songs, o.index, at) } ?: Player.takeBack(o.songs, o.index, at, go = true)
        dismissed.value = o.at
    }

    /** The offer last put aside ("not now"), by when it was. */
    val dismissed = MutableStateFlow(0L)
}

/**
 * What the phone was playing, offered at the top of listen when it's newer than what played here and nothing plays
 * here: its cover, "on <phone>", the song; continue here (where it was), or not now.
 */
@androidx.compose.runtime.Composable
fun continueOffer(): Continue.Offer? {
    val offer by SyncHub.theirs.collectAsState()
    val now by Player.now.collectAsState()
    val dismissed by Continue.dismissed.collectAsState()
    val o = offer ?: return null
    if (now?.playing == true || o.at <= dismissed) return null
    // playing there now: always offered; else only when it's newer than what played here
    return o.takeIf { it.live || it.at > SyncHub.playedAt }
}

@androidx.compose.runtime.Composable
fun ContinueHere(o: Continue.Offer) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Continue.take(o) })) {
            CoverOf(o.song, o.song.album.ifEmpty { o.song.title }, Modifier.fillMaxSize(), px = 200)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            BasicText((if (o.playing) "playing now on " else "on ") + o.from + (if (o.device.isNotEmpty()) " · on ${o.device.lowercase()}" else ""), style = pixel(12.sp, Ink.mid))
            BasicText(o.song.title.uppercase(), style = headline(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(o.song.artist.lowercase(), style = pixel(13.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(18.dp))
        Word("CONTINUE HERE", headline(20.sp), touch = 8.dp) { Continue.take(o) }
        Spacer(Modifier.width(24.dp))
        Word("not now", pixel(13.sp, Ink.mid), touch = 8.dp) { Continue.dismissed.value = o.at }
    }
}
