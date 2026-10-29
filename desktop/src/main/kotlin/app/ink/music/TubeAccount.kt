package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Your YouTube Music on the computer, signed in from the phone ([Handover]: the phone hands its sign-in over the home
 * network, sealed): your playlists in lists, your liked songs as loves (the heart likes there), your mixes on listen,
 * radio made as you, and the YouTube songs you play told to your history there (as its own app does). The cookies are
 * kept in a file only you can read, and go only to music.youtube.com. Signing out forgets them.
 */
object TubeAccount {
    data class Who(val name: String, val handle: String)

    val who = MutableStateFlow<Who?>(null)
    val playlists = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val liked = MutableStateFlow<List<Song>>(emptyList())
    val mixes = MutableStateFlow<List<TubeAlbum>>(emptyList())

    private val file = File(Home.dir, "youtube.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var auth: JSONObject? = null

    /** YouTube Music as you, or null signed out. */
    fun tube(): InnerTube? = auth?.let { InnerTube(it.optString("cookie"), it.optString("visitor").ifEmpty { null },
        it.optString("sync").ifEmpty { null }, it.optString("version").ifEmpty { InnerTube.VERSION }) }

    fun start() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        auth = o
        who.value = Who(o.optString("name"), o.optString("handle"))
        sync()
    }

    /** Your playlists, liked songs and mixes, read again. */
    fun sync() {
        val t = tube() ?: return
        scope.launch {
            runCatching {
                playlists.value = t.items("FEmusic_liked_playlists", 200).filter { it.kind == InnerTube.Kind.PLAYLIST }
                    // liked music is the loved list here; "episodes for later" isn't music
                    .filter { it.id != "VLLM" && it.id != "VLSE" }
                    .map { TubeAlbum("${InnerTube.BASE}/playlist?list=${it.id.removePrefix("VL")}", it.title, it.subtitle.substringAfter(" • ", "").ifEmpty { it.subtitle }, it.art) }
            }.onFailure { Log.d("youtube lists: $it") }
            runCatching { liked.value = t.tracks("VLLM", 1_000).map(YouTube::song); Loved.putTube(liked.value) }.onFailure { Log.d("youtube liked: $it") }
            runCatching {
                // your mixes: the home page's personal mixes ("RDTMAK" playlists; a shelf called a mix as well)
                mixes.value = t.home(12).flatMap { s ->
                    s.items.filter { it.kind == InnerTube.Kind.PLAYLIST && (it.id.startsWith("VLRDTMAK") || it.id.startsWith("RDTMAK") || "mix" in s.title.lowercase()) }
                }.distinctBy { it.id }.map { TubeAlbum("${InnerTube.BASE}/playlist?list=${it.id.removePrefix("VL")}", it.title, it.subtitle, it.art) }
            }.onFailure { Log.d("youtube mixes: $it") }
        }
    }

    /** A YouTube song liked (or not) there: the heart. */
    fun like(s: Song, on: Boolean) {
        val t = tube() ?: return
        scope.launch { runCatching { t.like(s.id.removePrefix("y"), on) } }
    }

    /** A YouTube song played here, told to your history there. */
    fun played(s: Song) {
        val t = tube() ?: return
        scope.launch { runCatching { t.played(s.id.removePrefix("y")) } }
    }

    fun signOut() {
        file.delete(); auth = null
        who.value = null; playlists.value = emptyList(); liked.value = emptyList(); mixes.value = emptyList()
        Loved.putTube(emptyList())
    }

    /** The sign-in handed over: tried (who are they?), kept if it works. */
    private fun take(o: JSONObject): Who? {
        val cookie = o.optString("cookie").ifEmpty { return null }
        val t = InnerTube(cookie, o.optString("visitor").ifEmpty { null }, o.optString("sync").ifEmpty { null }, o.optString("version").ifEmpty { InnerTube.VERSION })
        val w = t.who()?.let { Who(it.name, it.handle) } ?: return null
        val kept = JSONObject().put("cookie", cookie).put("visitor", o.optString("visitor")).put("sync", o.optString("sync"))
            .put("version", o.optString("version")).put("name", w.name).put("handle", w.handle)
        runCatching {
            Home.write(file, kept.toString())
            // only you can read it
            file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true)
        }
        auth = kept; who.value = w
        sync()
        return w
    }

    // ---- waiting for the phone ----

    /** While waiting: the code to type on the phone; null when not. */
    val code = MutableStateFlow<String?>(null)
    /** What happened, said ("signed in as ...", "that didn't work"). */
    val said = MutableStateFlow<String?>(null)
    private var server: ServerSocket? = null
    private var dns: javax.jmdns.JmDNS? = null

    /** The computer as the phone lists it. */
    val name: String get() = runCatching { java.net.InetAddress.getLocalHost().hostName.substringBefore(".") }.getOrDefault("this computer")

    /** Ready for the phone: a key and its code shown, announced on the network, for ten minutes. */
    fun await() {
        stop()
        said.value = null
        val keys = Handover.keys()
        code.value = Handover.code(keys.public)
        val s = runCatching { ServerSocket(0) }.getOrNull() ?: return
        server = s
        Thread({
            while (!s.isClosed) {
                val k = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { answer(k, keys) }
                runCatching { k.close() }
            }
        }, "handover").apply { isDaemon = true }.start()
        scope.launch {
            runCatching {
                val at = Serve.local() ?: java.net.InetAddress.getLocalHost()
                dns = javax.jmdns.JmDNS.create(at).also { it.registerService(javax.jmdns.ServiceInfo.create("${Handover.SERVICE}.local.", name, s.localPort, "ink music")) }
            }.onFailure { Log.d("handover dns: $it") }
            kotlinx.coroutines.delay(10 * 60_000L)
            if (server === s) stop()
        }
    }

    fun stop() {
        runCatching { server?.close() }; server = null
        val d = dns; dns = null
        if (d != null) scope.launch { runCatching { d.unregisterAllServices(); d.close() } }
        code.value = null
    }

    /** One request from the phone: GET /hello (the key and the name), POST /hand (the sign-in, sealed). */
    private fun answer(k: Socket, keys: java.security.KeyPair) {
        k.soTimeout = 20_000
        val input = k.getInputStream().buffered()
        fun line(): String { val b = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
        val first = line().split(" ")
        var length = 0
        while (true) { val h = line(); if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(":").trim().toIntOrNull() ?: 0 }
        val body = String(input.readNBytes(length.coerceIn(0, 64 * 1024)))
        val (status, reply) = when (first.getOrNull(0) to first.getOrNull(1)) {
            "GET" to "/hello" -> 200 to JSONObject().put("key", Handover.encode(keys.public)).put("name", name).put("app", "ink music")
            "POST" to "/hand" -> runCatching {
                val o = JSONObject(body)
                val text = runCatching { Handover.open(keys, Handover.decode(o.getString("key")), o.getString("iv"), o.getString("data")) }
                    .getOrElse { error("it couldn't be opened here, so it wasn't for this computer") }
                val w = take(JSONObject(text)) ?: error("youtube didn't know that sign-in")
                said.value = "signed in as ${w.name.lowercase()}"
                scope.launch { kotlinx.coroutines.delay(500); stop() }
                200 to JSONObject().put("ok", true).put("name", name)
            }.getOrElse { e -> said.value = "that didn't work: ${e.message?.lowercase()}"; 400 to JSONObject().put("ok", false).put("why", e.message.orEmpty()) }
            else -> 404 to JSONObject()
        }
        val out = reply.toString().toByteArray()
        k.getOutputStream().apply {
            write("HTTP/1.1 $status ${if (status == 200) "OK" else "No"}\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(out); flush()
        }
    }
}
