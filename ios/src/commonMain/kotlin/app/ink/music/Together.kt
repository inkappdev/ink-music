package app.ink.music

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The iPhone kept in step with your computer, as the phone is (2026-11-07; [Sync] in core, the same files and the same
 * sealing): paired once by the code the computer shows, then called every minute while the app's open. It brings the
 * server's sign-in, ListenBrainz's and Last.fm's, what you've heard and the podcasts you follow; it takes this
 * iPhone's listens back, and what each is playing goes both ways ("continue here").
 */
object Together {
    /** A device's playing, offered to carry on with here. */
    data class Offer(val from: String, val peer: String, val songs: List<Song>, val index: Int, val position: Long, val playing: Boolean, val at: Long)

    val kept by lazy { Sync.Kept(Here.dir) }
    val peers = MutableStateFlow<List<Sync.Peer>>(emptyList())
    val theirs = MutableStateFlow<Offer?>(null)
    val said = MutableStateFlow<String?>(null)
    /** Bumped when what came across changes what's shown (a sign-in, listens, podcasts). */
    val changed = MutableStateFlow(0)
    private val one = Mutex()
    private var loop: Job? = null

    fun open() {
        peers.value = kept.peers()
        loop?.cancel()
        loop = Here.work.launch { while (true) { now(); delay(60_000) } }
    }

    fun forget(id: String) { kept.forget(id); peers.value = kept.peers() }

    /** A song as the other is told it: a server song by the phone's id for it ("n" and the server's). */
    fun wire(s: Song): JSONObject = Sync.song(when (s.kind) { "server" -> "n" + s.id; "youtube" -> "y" + s.id; else -> s.id }, s.title, s.artist, s.album,
        if (s.kind == "server") "na" + s.albumId else s.albumId, s.artist, s.duration, s.cover?.takeIf { it.startsWith("http") })

    /** A song the other told of, here: a server song by its id, a station by its, YouTube's by its. */
    private fun resolve(o: JSONObject): Song? {
        val id = o.optString("id")
        return when {
            id.startsWith("n") && !id.startsWith("na") -> Song(id.drop(1), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("albumId").removePrefix("na"), o.optString("albumId").removePrefix("na").ifEmpty { id.drop(1) }, o.optLong("duration"), 0, 1)
            id.startsWith("s") -> Radio.stations().firstOrNull { it.song.id == id }?.song
            id.startsWith("y") -> Song(id.drop(1), o.optString("title"), o.optString("artist"), o.optString("album"), o.optString("albumId"),
                o.optString("art").ifEmpty { null }, o.optLong("duration"), 0, 1, kind = "youtube")
            else -> null
        }
    }

    private val side = object : Sync.Side {
        override val dir: File get() = Here.dir
        override val name: String get() = deviceName()
        override fun mine(line: JSONObject): JSONObject? = null
        override fun now(): JSONObject? {
            val n = Sound.now.value ?: return null
            val q = Sound.queue()
            val from = (n.index - 50).coerceAtLeast(0)
            val part = q.subList(from, minOf(q.size, n.index + 250))
            return JSONObject().put("queue", JSONArray(part.map(::wire))).put("index", n.index - from)
                .put("position", n.position).put("playing", n.playing).put("at", Sound.resumedAt)
        }
        override fun loved(key: String, on: Boolean, song: JSONObject) {}
        override fun signedIn(what: String) { if (what == "server") Server.reload(); changed.value++ }
        // YouTube's sign-in asked of the computer (it hands on the phone's): the iPhone can't sign in itself
        override fun wants(): List<String> = listOf("youtube")
        override fun takeYoutube(auth: String) { Tube.take(auth); changed.value++ }
        override fun heardChanged() { changed.value++ }
        override fun podcastsChanged() { changed.value++ }
        override fun theirs(peer: Sync.Peer, now: JSONObject?) { theirs.value = now?.let { offer(peer, it) } }
        override fun resumedAt(): Long = Sound.resumedAt
        override fun stop() { Here.work.launch(Dispatchers.Main) { if (Sound.now.value?.playing == true) Sound.pause() } }
    }

    private fun offer(peer: Sync.Peer, o: JSONObject): Offer? {
        val q = o.optJSONArray("queue") ?: return null
        val index = o.optInt("index")
        val songs = ArrayList<Song>(); var at = -1
        for (i in 0 until q.length()) {
            val s = q.optJSONObject(i)?.let(::resolve) ?: continue
            if (i == index) at = songs.size
            songs += s
        }
        if (songs.isEmpty() || at < 0) return null
        return Offer(peer.name.removePrefix("ink music on ").lowercase(), peer.id, songs, at, o.optLong("position"), o.optBoolean("playing"), o.optLong("at"))
    }

    /** Carried on with here, where it was; the other told to stop. */
    fun take(o: Offer) {
        Sound.play(o.songs, o.index)
        val s = o.songs[o.index]
        if (o.position > 5_000 && s.kind != "station") Sound.seek(o.position)
        theirs.value = null
        kept.handed(o.peer, nowMs())
        Here.work.launch { delay(500); now() }
    }

    /** In step with every paired computer this wifi has, now. */
    suspend fun now() {
        if (kept.peers().isEmpty()) return
        one.withLock {
            // what plays, read on the screens' thread where the player lives
            for (p in kept.peers()) {
                val tries = listOfNotNull(p.at.takeIf { ':' in it }) + look(Sync.SERVICE, 4_000).map { it.second }
                var reached = false
                for (at in tries.distinct()) {
                    val ok = runCatching { exchange(p, at) }.getOrDefault(false)
                    if (ok) { reached = true; break }
                }
                said.value = if (reached) null else "not on this wifi"
            }
            peers.value = kept.peers()
        }
    }

    private suspend fun exchange(p: Sync.Peer, at: String): Boolean {
        val body = withContext(Dispatchers.Main) { Sync.ask(side, kept, p) }
        return Here.off {
            val r = http("http://$at/sync", "POST", mapOf("Content-Type" to "application/json"), body.encodeToByteArray(), connectMs = 3_000, readMs = 60_000, follow = 0)
            if (r.code != 200) false else { Sync.answered(side, kept, p, r.text, at); true }
        }
    }

    /** What's on this wifi announcing `type`, looked for a while: (its name, "host:port"). */
    suspend fun look(type: String, ms: Long): List<Pair<String, String>> {
        val found = ArrayList<Pair<String, String>>()
        val stop = withContext(Dispatchers.Main) { browse(type) { n, at -> found += n to at } }
        var waited = 0L
        while (waited < ms) { delay(200); waited += 200; if (found.isNotEmpty() && waited > 1_000) break }
        withContext(Dispatchers.Main) { stop() }
        return found.toList()
    }

    /**
     * Paired with the computer at `at` ("host:port", ink music there waiting: settings, your phone, pair your phone):
     * its code checked against its key first, so it's that computer and no other. What happened, said.
     */
    suspend fun pair(name: String, at: String, code: String): String = Here.off {
        runCatching {
            val hello = JSONObject(http("http://$at/hello", connectMs = 8_000, readMs = 15_000).text)
            val theirs = Handover.decode(hello.getString("key"))
            if (Handover.code(theirs).filter(Char::isDigit) != code) return@off "that isn't the code on ${name.lowercase()}: look again"
            val mine = Handover.keys()
            val hi = JSONObject().put("id", kept.id()).put("name", deviceName())
            val (iv, data) = Handover.seal(mine, theirs, hi.toString())
            val body = JSONObject().put("key", Handover.encode(mine.publicKey())).put("iv", iv).put("data", data).toString()
            val answer = JSONObject(http("http://$at/pair", "POST", mapOf("Content-Type" to "application/json"), body.encodeToByteArray(),
                connectMs = 8_000, readMs = 30_000).text)
            if (!answer.optBoolean("ok")) return@off "it didn't take: ${answer.optString("why").ifEmpty { "update ink music there" }.lowercase()}"
            val named = answer.optString("name").ifEmpty { name }
            kept.put(Sync.Peer(answer.optString("id"), named, Handover.kept(mine, theirs), seen = nowMs(), at = ""))
            peers.value = kept.peers()
            Here.work.launch { now() }
            "paired with ${named.lowercase()}"
        }.getOrElse { "couldn't reach ${name.lowercase()}: ${it.message.orEmpty().lowercase()}" }
    }
}
