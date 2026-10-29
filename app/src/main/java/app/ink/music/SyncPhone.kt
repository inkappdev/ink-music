package app.ink.music

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone's side of [Sync]: while ink music is open, every minute (and soon after a love), it calls the computers
 * it's paired with on this wifi (where each was last, else found again: `_inksync._tcp`) and the two hand each other
 * what they haven't got: what you've heard, loves, sign-ins, what each was playing (the computer's offered here to
 * continue with). Paired from "sync with a computer" in settings ([Computers.pair]). Nothing goes anywhere else.
 */
object SyncPhone {
    private var app: Context? = null
    lateinit var kept: Sync.Kept
    /** The computers paired, as last in step. */
    val peers = MutableStateFlow<List<Sync.Peer>>(emptyList())
    /** What a paired computer was last playing: to carry on with here. */
    val theirs = MutableStateFlow<Offer?>(null)
    /** The last call's word ("in step", "not on this wifi"), for settings. */
    val said = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val one = Mutex()
    private var soon: Job? = null
    private var loop: Job? = null
    /** The queue here and where in it, as read just before a call. */
    @Volatile private var playing: Triple<List<Song>, Int, Long>? = null
    /** A love the computer made, being done here: not one to tell it about again. */
    @Volatile private var applying = false

    data class Offer(val from: String, val songs: List<Song>, val index: Int, val position: Long, val playing: Boolean, val at: Long, val peer: String = "") {
        val song: Song get() = songs[index]
    }
    val dismissed = MutableStateFlow(0L)

    fun start(c: Context) {
        if (app != null) return
        app = c.applicationContext
        kept = Sync.Kept(c.filesDir)
        peers.value = kept.peers()
    }

    /** While the app's open: in step now, and every minute. */
    fun open() {
        loop?.cancel()
        loop = scope.launch {
            var n = 0
            while (true) {
                now()
                // the queue the computer left on the server (away from the wifi), every five minutes: offered when newest
                if (n++ % 5 == 0) Server.savedQueue()?.let { q ->
                    if (q.at > (theirs.value?.at ?: 0L)) theirs.value = Offer("your server", q.songs, q.index, q.position, false, q.at)
                }
                delay(60_000)
            }
        }
    }

    fun closed() { loop?.cancel(); loop = null; soon(1_000) }

    /** In step a moment from now (a love, a sign-in), a few changes going as one. */
    fun soon(ms: Long = 8_000) {
        soon?.cancel()
        soon = scope.launch { delay(ms); now() }
    }

    /** In step with every paired computer this wifi has, now. */
    suspend fun now() {
        val c = app ?: return
        if (kept.peers().isEmpty()) return
        one.withLock {
            // what plays here, read where the player's controller lives (the main thread) before going
            playing = kotlinx.coroutines.withContext(Dispatchers.Main) { runCatching { Playback.handover() }.getOrNull() }
            for (p in kept.peers()) {
                val at = reach(c, p)
                said.value = if (at == null) "not on this wifi" else null
            }
            peers.value = kept.peers()
        }
    }

    fun forget(id: String) { kept.forget(id); peers.value = kept.peers() }

    /** Paired (from the pairing): kept, and in step at once. */
    fun paired(p: Sync.Peer) { kept.put(p); peers.value = kept.peers(); scope.launch { now() } }

    /** A love made here: told to the computer at the next call. */
    fun loved(s: Song, on: Boolean) {
        if (applying || s.station) return
        kept.putLove(Sync.songKey(s.title, s.artist), on, System.currentTimeMillis(), wire(s))
        soon()
    }

    // ---- reaching a computer ----

    /** Where it was last, else found on the wifi; the exchange done. Where it was reached, or null. */
    private suspend fun reach(c: Context, p: Sync.Peer): String? {
        val tries = listOfNotNull(p.at.takeIf { ':' in it }) + (withTimeoutOrNull(6_000) { find(c) } ?: emptyList())
        for (at in tries.distinct()) {
            val ok = runCatching { exchange(p, at) }.onFailure { android.util.Log.d("ink-sync", "$at: $it") }.getOrDefault(false)
            if (ok) return at
        }
        return null
    }

    private fun exchange(p: Sync.Peer, at: String): Boolean {
        val side = side ?: return false
        val body = Sync.ask(side, kept, p)
        val conn = URL("http://$at/sync").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000; conn.readTimeout = 60_000; conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return false
            Sync.answered(side, kept, p, conn.inputStream.bufferedReader().readText(), at)
            true
        } finally { conn.disconnect() }
    }

    /** The computers on this wifi announcing [Sync.SERVICE] ("host:port"), looked for a few seconds. */
    private suspend fun find(c: Context): List<String> {
        val m = c.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = java.util.concurrent.CopyOnWriteArrayList<String>()
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                m.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = (if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else null) ?: @Suppress("DEPRECATION") info.host ?: return
                        host.hostAddress?.let { found += "$it:${info.port}" }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        try { m.discoverServices(Sync.SERVICE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { return emptyList() }
        try {
            repeat(30) { if (found.isNotEmpty()) { delay(400); return found.toList() }; delay(200) }
            return found.toList()
        } finally { runCatching { m.stopServiceDiscovery(l) } }
    }

    // ---- this phone's side ----

    /** A song as the computer's told it. */
    fun wire(s: Song): JSONObject = Sync.song(s.id, s.title, s.artist, s.album, s.albumId, s.albumArtist, s.duration, s.art)

    private var byKey: Pair<List<Song>, Map<String, Song>>? = null
    private fun byKey(): Map<String, Song> {
        val songs = Library.songs.value
        byKey?.takeIf { it.first === songs }?.let { return it.second }
        return songs.associateBy { Sync.songKey(it.title, it.artist) }.also { byKey = songs to it }
    }

    /** A song the computer told of, here: the same one by id (a server's), by name and artist (a file), else YouTube's or a station by id. */
    fun resolve(o: JSONObject): Song? {
        val c = app ?: return null
        val id = o.optString("id"); val title = o.optString("title"); val artist = o.optString("artist")
        Library.song(id)?.let { return it }
        byKey()[Sync.songKey(title, artist)]?.let { return it }
        return when {
            id.startsWith("y") && YouTubeMusic.available -> YouTubeMusic.known(id) ?: Song(
                id = id, uri = android.net.Uri.parse("ytm://" + id.removePrefix("y")), title = title, artist = artist,
                album = o.optString("album"), albumId = o.optString("albumId").ifEmpty { id }, albumArtist = o.optString("albumArtist").ifEmpty { artist },
                duration = o.optLong("duration"), disc = 1, track = 0, year = 0, art = o.optString("art").ifEmpty { null },
            ).also(YouTubeMusic::remember)
            id.startsWith("s") -> Stations.curated(c).firstOrNull { it.song.id == id }?.song
            else -> null
        }
    }

    private val side: Sync.Side? get() = app?.let { c -> object : Sync.Side {
        override val dir: File get() = c.filesDir
        override val name: String get() = deviceName(c)

        override fun mine(line: JSONObject): JSONObject? =
            (Library.song(line.optString("id")) ?: byKey()[Sync.songKey(line.optString("title"), line.optString("artist"))])
                ?.let { JSONObject().put("id", it.id).put("record", it.albumId) }

        override fun now(): JSONObject? {
            val (songs, index, position) = playing ?: return null
            val n = Playback.now.value
            val from = (index - 50).coerceAtLeast(0)
            val part = songs.subList(from, minOf(songs.size, index + 250))
            return JSONObject().put("queue", JSONArray(part.map(::wire))).put("index", index - from)
                .put("position", position).put("playing", n?.playing == true).put("at", playedAt)
        }

        override fun loved(key: String, on: Boolean, song: JSONObject) {
            val s = resolve(song) ?: return
            if ((s.id in Loved.ids.value) == on) return
            applying = true
            try { Loved.toggle(s) } finally { applying = false }
        }

        override fun youtube(): String? = YouTubeAccount.handover(c)

        override fun signedIn(what: String) {
            when (what) {
                "server" -> Server.reload(c)
                "listenbrainz" -> ListenBrainz.start(c)
                "lastfm" -> LastFm.start(c)
            }
        }

        override fun heardChanged() { Listened.read(c) }

        override fun theirs(peer: Sync.Peer, now: JSONObject?) {
            theirs.value = now?.let { offer(peer.name, it, peer.id) }
        }

        override fun resumedAt(): Long = runCatching { File(c.filesDir, "sync-resumed").readText().trim().toLong() }.getOrDefault(0L)

        override fun stop() {
            kotlinx.coroutines.runBlocking(Dispatchers.Main) { if (Playback.now.value?.playing == true) Playback.toggle() }
        }
    } }

    /**
     * When what plays here last changed (a song, play, pause), so the newer of the two is the one carried on with: a
     * change seen here (kept, sync-played), or the last listen the player wrote, whichever's later (music plays on with
     * the app closed). What the app sees as it opens (the queue it was left with) isn't a change.
     */
    val playedAt: Long get() {
        val c = app ?: return 0L
        val seen = runCatching { File(c.filesDir, "sync-played").readText().trim().toLong() }.getOrDefault(0L)
        val heard = runCatching {
            File(c.filesDir, "heard.jsonl").let { f -> f.useLines { it.lastOrNull { l -> l.isNotBlank() && "\"from\"" !in l } } }
                ?.let { JSONObject(it).let { o -> o.optLong("at") + o.optLong("ms") } }
        }.getOrNull() ?: 0L
        return maxOf(seen, heard)
    }
    private var last: Pair<String?, Boolean>? = null

    /** What plays here, as the screens hear it (Playback's now): a change timed, and the computer told soon after a pause. */
    fun heard(n: Now?) {
        val c = app ?: return
        if (n == null) return
        val k = n.song?.id to n.playing
        if (last == null) { last = k; return }
        if (k == last) return
        last = k
        runCatching { File(c.filesDir, "sync-played").writeText(System.currentTimeMillis().toString()) }
        // kept on the server too, for the computer away from home (read here, on the controller's thread)
        Playback.handover()?.let { (songs, index, position) -> Server.saveQueue(songs, index, position) }
        if (!k.second) soon(3_000)
    }

    private fun offer(from: String, o: JSONObject, peer: String = ""): Offer? {
        val q = o.optJSONArray("queue") ?: return null
        val index = o.optInt("index")
        val songs = ArrayList<Song>(); var at = -1
        for (i in 0 until q.length()) {
            val s = q.optJSONObject(i)?.let(::resolve) ?: continue
            if (i == index) at = songs.size
            songs += s
        }
        if (songs.isEmpty() || at < 0) return null
        return Offer(from.lowercase(), songs, at, o.optLong("position"), o.optBoolean("playing"), o.optLong("at"), peer)
    }

    /** Carried on with here, where it was. */
    fun take(o: Offer) {
        Playback.play(o.songs, o.index, if (o.position > 5_000 && !o.song.station) o.position else 0L)
        dismissed.value = o.at
        // the computer told to stop, at once
        if (o.peer.isNotEmpty()) { kept.handed(o.peer, System.currentTimeMillis()); soon(500) }
    }

    /** The phone as the computer lists it: its own name (settings, about), else its model. */
    fun deviceName(c: Context): String =
        runCatching { android.provider.Settings.Global.getString(c.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
}

/**
 * The player's side of [Sync], live (its own process: the app closed, playing, or casting): on each change (a song,
 * play, pause, the speaker's) it tells the computers paired what plays now and where (`/now`), so the computer offers
 * it to continue there at once; while it plays it waits on each (`/wait`), which answers the moment the computer
 * carries on with it, and pauses here (the speaker, if it's casting). When play's pressed is kept (sync-resumed).
 */
class Live(private val c: Context, private val player: () -> androidx.media3.common.Player?, private val device: () -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var telling: Job? = null
    private var waiting: Job? = null
    private var wasPlaying = false
    @Volatile private var pausedAt = 0L
    private val resumed get() = File(c.filesDir, "sync-resumed")
    private val kept by lazy { Sync.Kept(c.filesDir) }

    /** Something changed (main thread): told soon, a burst of changes as one; waiting on the computer while it plays. */
    fun changed() {
        val p = player() ?: return
        val playing = p.playWhenReady
        if (playing && !wasPlaying) runCatching { resumed.writeText(System.currentTimeMillis().toString()) }
        wasPlaying = playing
        val now = runCatching { now(p) }.getOrNull()
        telling?.cancel()
        telling = scope.launch { delay(400); tell(now) }
        // waiting on the computer while it plays, and a while after a pause (play pressed there brings it back)
        if (!playing) pausedAt = System.currentTimeMillis()
        if (waiting?.isActive != true && (playing || p.mediaItemCount > 0)) waiting = scope.launch { waitAll() }
    }

    /** What plays, as the computer's told it (read on the main thread, the player's). */
    private fun now(p: androidx.media3.common.Player): JSONObject? {
        if (p.mediaItemCount == 0) return null
        val index = p.currentMediaItemIndex
        val from = (index - 50).coerceAtLeast(0)
        val queue = JSONArray()
        for (i in from until minOf(p.mediaItemCount, index + 250)) {
            val item = p.getMediaItemAt(i); val md = item.mediaMetadata
            queue.put(Sync.song(item.mediaId, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), md.albumTitle?.toString().orEmpty(),
                "", md.albumArtist?.toString() ?: md.artist?.toString().orEmpty(), (md.durationMs ?: 0L), md.artworkUri?.toString()))
        }
        return JSONObject().put("queue", queue).put("index", index - from).put("position", p.currentPosition).put("positionAt", System.currentTimeMillis())
            .put("playing", p.playWhenReady).put("at", System.currentTimeMillis()).put("device", device())
    }

    private fun peers() = kept.peers().filter { ':' in it.at }

    private fun tell(now: JSONObject?) {
        for (p in peers()) runCatching {
            val r = post(p.at, "/now", Sync.sealed(kept, p, JSONObject().put("now", now ?: JSONObject.NULL)), 4_000) ?: return@runCatching
            stopIfHanded(Sync.replied(p, r).optLong("handed"))
        }
    }

    /** Waiting on each computer to carry on with it or steer it, again and again while it plays (and a quarter hour after). */
    private suspend fun waitAll() {
        while (true) {
            if (!wasPlaying && System.currentTimeMillis() - pausedAt > 15 * 60_000L) return
            val since = runCatching { resumed.readText().trim().toLong() }.getOrDefault(0L)
            val list = peers()
            if (list.isEmpty()) return
            for (p in list) {
                val r = runCatching { post(p.at, "/wait", Sync.sealed(kept, p, JSONObject().put("since", since)), 30_000) }.getOrNull()
                if (r == null) { delay(10_000); continue }
                val m = runCatching { Sync.replied(p, r) }.getOrNull() ?: continue
                // steered from the computer's bottom bar: done here (the speaker, if it's casting)
                m.optString("do").takeIf { it.isNotEmpty() }?.let { what ->
                    main.post {
                        player()?.run {
                            when (what) { "play" -> play(); "pause" -> pause(); "next" -> seekToNextMediaItem(); "previous" -> seekToPrevious() }
                        }
                    }
                }
                if (stopIfHanded(m.optLong("handed"))) return
            }
        }
    }

    /** Carried on with there since play was last pressed here: paused (the speaker, casting). */
    private fun stopIfHanded(handed: Long): Boolean {
        val since = runCatching { resumed.readText().trim().toLong() }.getOrDefault(0L)
        if (handed <= since) return false
        main.post { player()?.takeIf { it.playWhenReady }?.pause() }
        return true
    }

    private fun post(at: String, path: String, body: String, read: Int): String? {
        val conn = URL("http://$at$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000; conn.readTimeout = read; conn.requestMethod = "POST"; conn.doOutput = true
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } finally { conn.disconnect() }
    }
}
