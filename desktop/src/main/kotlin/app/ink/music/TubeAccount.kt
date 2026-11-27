package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Your YouTube Music on the computer, signed in from the phone ([Handover]: the phone hands its sign-in over the home
 * network, sealed): your playlists in lists, your liked songs as loves (the heart likes there), your mixes on listen,
 * the songs you've uploaded there yourself as a source of their own on the songs page, radio made as you, and the
 * YouTube songs you play told to your history there (as its own app does). The cookies are
 * kept in a file only you can read, and go only to music.youtube.com. Signing out forgets them.
 */
object TubeAccount {
    data class Who(val name: String, val handle: String)

    val who = MutableStateFlow<Who?>(null)
    val playlists = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val liked = MutableStateFlow<List<Song>>(emptyList())
    val mixes = MutableStateFlow<List<TubeAlbum>>(emptyList())
    /**
     * The songs you've uploaded to YouTube Music yourself, newest first (2026-11-17): your own music on every device, with
     * no server to run (a free account may upload 100,000). Private, so they play only signed in ([YouTube.stream]).
     */
    val uploads = MutableStateFlow<List<Song>>(emptyList())
    /** The records in your library, the artists you follow (else the ones your songs are by), and theirs from this year (the phone's). */
    val records = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val artists = MutableStateFlow<List<TubeArtist>>(emptyList())
    val fresh = MutableStateFlow<List<TubeAlbum>>(emptyList())
    /**
     * YouTube Music answered as to no one, though the phone's sign-in was sent (why, not yet known: the phone's own reads
     * with the same cookies work). Said in settings; the lists and likes last read are kept meanwhile.
     */
    val stale = MutableStateFlow(false)
    /** Why the last read failed, in a few words, while it's failing (settings shows it: a listener can tell us). */
    val problem = MutableStateFlow<String?>(null)

    private val file = File(Home.dir, "youtube.json")
    /**
     * Your playlists, liked songs and mixes as last read whole (2026-11-16): shown from the start, and kept when a read
     * fails. Before, each start began with none, and one read gone wrong left them empty till the next start (a tester
     * on Windows: liked songs and lists gone nine times in ten).
     */
    private val library = File(Home.dir, "youtube-library.json")
    /** Your uploads as last read whole, apart from the rest (there may be a hundred thousand). */
    private val uploadsFile = File(Home.dir, "youtube-uploads.json")
    @Volatile private var syncing: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var auth: JSONObject? = null

    /** YouTube Music as you, or null signed out. */
    fun tube(): InnerTube? = auth?.let { InnerTube(it.optString("cookie"), it.optString("visitor").ifEmpty { null },
        it.optString("sync").ifEmpty { null }, it.optString("version").ifEmpty { InnerTube.VERSION }) }

    fun start() {
        // local files only: not started (Local)
        if (Local.on) return
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        auth = o
        who.value = Who(o.optString("name"), o.optString("handle"))
        restore()
        sync()
        // read again now and then while open: what you liked on the phone comes over, and a read that failed is tried
        scope.launch { while (true) { kotlinx.coroutines.delay(30 * 60_000L); if (auth != null) sync() } }
    }

    private fun restore() = runCatching {
        val o = JSONObject(library.readText())
        fun albums(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
            .map { TubeAlbum(it.getString("url"), it.getString("title"), it.optString("artist"), it.optString("art").ifEmpty { null }) }
        playlists.value = albums(o.optJSONArray("playlists"))
        mixes.value = albums(o.optJSONArray("mixes"))
        records.value = albums(o.optJSONArray("records"))
        fresh.value = albums(o.optJSONArray("fresh"))
        val p = o.optJSONArray("artists")
        artists.value = (0 until (p?.length() ?: 0)).map { p!!.getJSONObject(it) }.map { TubeArtist(it.getString("name"), it.optString("face").ifEmpty { null }, it.optString("id")) }
        val a = o.optJSONArray("liked")
        liked.value = (0 until (a?.length() ?: 0)).mapNotNull { runCatching { songOf(a!!.getJSONObject(it)) }.getOrNull() }
        if (liked.value.isNotEmpty()) Loved.putTube(liked.value)
        runCatching {
            val u = JSONArray(uploadsFile.readText())
            uploads.value = (0 until u.length()).mapNotNull { runCatching { songOf(u.getJSONObject(it)).also(YouTube::remember) }.getOrNull() }
        }.onFailure { if (uploadsFile.exists()) Log.d("youtube uploads: $it") }
    }.onFailure { if (library.exists()) Log.d("youtube library: $it") }

    // the uploads' ids, made again only when the list changes (asked for every song on the songs page)
    @Volatile private var uploadIds: Pair<List<Song>, Set<String>> = emptyList<Song>() to emptySet()

    /** Whether a song is one of your uploads. */
    fun uploaded(id: String): Boolean {
        val now = uploads.value
        val (was, ids) = uploadIds
        if (was === now) return id in ids
        return now.mapTo(HashSet()) { it.id }.also { uploadIds = now to it }.contains(id)
    }

    private fun keepLibrary() = runCatching {
        fun albums(l: List<TubeAlbum>) = JSONArray(l.map { JSONObject().put("url", it.url).put("title", it.title).put("artist", it.artist).put("art", it.art ?: "") })
        Home.write(library, JSONObject().put("playlists", albums(playlists.value)).put("mixes", albums(mixes.value))
            .put("records", albums(records.value)).put("fresh", albums(fresh.value))
            .put("artists", JSONArray(artists.value.map { JSONObject().put("name", it.name).put("face", it.face ?: "").put("id", it.id) }))
            .put("liked", JSONArray(liked.value.map { it.json() })).toString())
    }

    /**
     * Your playlists, liked songs and mixes, read again; each kept as it was if its read fails (signed out, the network),
     * and tried again a minute on, then five, then fifteen.
     */
    fun sync(fresh: Boolean = false) {
        if (tube() == null) return
        // a sign-in just handed over starts again with it; otherwise one at a time
        if (fresh) syncing?.cancel() else if (syncing?.isActive == true) return
        syncing = scope.launch {
            for (wait in listOf(0L, 60_000L, 5 * 60_000L, 15 * 60_000L)) {
                if (wait > 0) kotlinx.coroutines.delay(wait)
                // the sign-in as it is now (the phone may have handed a fresh one meanwhile)
                val t = tube() ?: return@launch
                if (read(t, fresh && wait == 0L)) return@launch
            }
        }
    }

    /**
     * One read of the three, and your uploads; whether each was read whole. The uploads whole when a sign-in's [fresh] or
     * they were last read an hour ago or more: a big library of them is a thousand pages, and they change only when you
     * upload (this reads again every half hour).
     */
    @Volatile private var freshAt = 0L

    /** Your artists' records from this year (and last, early in the year), newest first, three each at most (the phone's). */
    private fun freshFrom(t: InnerTube, people: List<TubeArtist>): List<TubeAlbum> {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val since = if (cal.get(java.util.Calendar.MONTH) <= java.util.Calendar.FEBRUARY) year - 1 else year
        val yearOf = Regex("\\b(19|20)\\d{2}\\b")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            return people.filter { it.id.isNotEmpty() }.take(20).map { a ->
                pool.submit<List<Triple<Int, String, TubeAlbum>>> {
                    runCatching { t.records(a.id) }.getOrDefault(emptyList()).mapNotNull { r ->
                        val y = yearOf.find(r.subtitle)?.value?.toInt() ?: return@mapNotNull null
                        if (y < since) null else Triple(y, a.name, TubeAlbum("${InnerTube.BASE}/browse/${r.id}", r.title, a.name, r.art))
                    }.take(3)
                }
            }.flatMap { runCatching { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
                .sortedByDescending { it.first }.map { it.third }.distinctBy { it.url }.take(20)
        } finally { pool.shutdownNow() }
    }

    /** A record's artist from its subtitle ("Album • Phoebe Bridgers • 2020"): what isn't the kind or the year. */
    private fun artistOf(subtitle: String) = subtitle.split(" • ").map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it !in setOf("Album", "Single", "EP") && !it.matches(Regex("\\d{4}")) }.orEmpty()

    private fun read(t: InnerTube, fresh: Boolean): Boolean {
        var whole = true
        var out = false
        val why = ArrayList<String>()
        fun failed(what: String, e: Throwable) {
            whole = false; if (e is InnerTube.SignedOut) out = true; Log.d("youtube $what: $e")
            why += "$what: " + (if (e is InnerTube.SignedOut) "signed out" else (e.message ?: e::class.simpleName ?: "failed").take(90))
        }
        run {
            runCatching {
                playlists.value = t.items("FEmusic_liked_playlists", 200).filter { it.kind == InnerTube.Kind.PLAYLIST }
                    // liked music is the loved list here; "episodes for later" isn't music
                    .filter { it.id != "VLLM" && it.id != "VLSE" }
                    .map { TubeAlbum("${InnerTube.BASE}/playlist?list=${it.id.removePrefix("VL")}", it.title, it.subtitle.substringAfter(" • ", "").ifEmpty { it.subtitle }, it.art) }
            }.onFailure { failed("lists", it) }
            runCatching { liked.value = t.tracks("VLLM", LIKED_MOST).map(YouTube::song); Loved.putTube(liked.value) }.onFailure { failed("liked", it) }
            runCatching {
                // your mixes: the home page's personal mixes ("RDTMAK" playlists; a shelf called a mix as well)
                mixes.value = t.home(12).flatMap { s ->
                    s.items.filter { it.kind == InnerTube.Kind.PLAYLIST && (it.id.startsWith("VLRDTMAK") || it.id.startsWith("RDTMAK") || "mix" in s.title.lowercase()) }
                }.distinctBy { it.id }.map { TubeAlbum("${InnerTube.BASE}/playlist?list=${it.id.removePrefix("VL")}", it.title, it.subtitle, it.art) }
            }.onFailure { failed("mixes", it) }
            // the records in your library; the artists you follow, else the ones your library's songs are by; and what
            // those artists put out this year (last year's too, early in the year), newest first
            runCatching {
                records.value = t.items("FEmusic_liked_albums").filter { it.kind == InnerTube.Kind.RECORD || it.id.startsWith("MPREb") }
                    .map { TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, artistOf(it.subtitle), it.art) }
            }.onFailure { failed("records", it) }
            runCatching {
                fun of(id: String) = t.items(id).filter { it.kind == InnerTube.Kind.ARTIST && it.id.startsWith("UC") }
                artists.value = of("FEmusic_library_corpus_artists").ifEmpty { of("FEmusic_library_corpus_track_artists") }.map { TubeArtist(it.title, it.art, it.id) }
            }.onFailure { failed("artists", it) }
            // (twenty artists' pages: once in six hours, not every half hour's read)
            if (fresh || this@TubeAccount.fresh.value.isEmpty() || System.currentTimeMillis() - freshAt > 6 * 3_600_000L)
                runCatching { freshFrom(t, artists.value).takeIf { it.isNotEmpty() }?.let { this@TubeAccount.fresh.value = it; freshAt = System.currentTimeMillis() } }.onFailure { Log.d("youtube fresh: $it") }
            if (fresh || !uploadsFile.exists() || System.currentTimeMillis() - uploadsFile.lastModified() >= 3_600_000L) runCatching {
                uploads.value = t.uploads().map(YouTube::song)
                Home.write(uploadsFile, JSONArray(uploads.value.map { it.json() }).toString())
            }.onFailure { failed("uploads", it) }
        }
        // what was read is kept (what wasn't stays as it was), whole or not
        keepLibrary()
        if (out) stale.value = true else if (whole) stale.value = false
        problem.value = why.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return whole
    }

    /**
     * A YouTube song liked (or not) there: the heart. Among your liked songs here at once (the loved list shows them),
     * as the phone does; put back if YouTube turns it down.
     */
    fun like(s: Song, on: Boolean) {
        val t = tube() ?: return
        val before = liked.value
        liked.value = if (on) listOf(s) + before.filter { it.id != s.id } else before.filter { it.id != s.id }
        scope.launch {
            val ok = runCatching { t.like(s.id.removePrefix("y"), on) }.getOrDefault(false)
            if (!ok) { Log.d("youtube: like ${s.id} $on refused"); liked.value = before }
        }
    }

    /** A song's id there: its own, or one from elsewhere found there by its words (blocking). */
    private fun videoOf(s: Song): String? = (if (s.tube) s.id else YouTube.find(s.title, s.artist)?.id)?.removePrefix("y")

    /** The playlist's id in a list's link (…/playlist?list=PL…). */
    fun listId(a: TubeAlbum): String = a.url.substringAfter("list=").substringBefore("&")

    /** A song added to one of your playlists there; false if it isn't there or YouTube said no. */
    suspend fun addTo(list: TubeAlbum, s: Song): Boolean = withContext(Dispatchers.IO) {
        val t = tube() ?: return@withContext false
        val vid = videoOf(s) ?: return@withContext false
        runCatching { t.addTo(listId(list), vid) }.getOrDefault(false)
    }

    /** A new playlist of yours there (private), with this song in it. */
    suspend fun makePlaylist(name: String, s: Song): Boolean = makePlaylist(name, listOf(s))

    /** A new playlist of yours there (private), these songs in it, in order (each from elsewhere found there first). */
    suspend fun makePlaylist(name: String, songs: List<Song>): Boolean = withContext(Dispatchers.IO) {
        val t = tube() ?: return@withContext false
        val first = songs.firstOrNull() ?: return@withContext false
        val id = runCatching { t.makePlaylist(name, songs.mapNotNull { videoOf(it) }.distinct()) }.getOrNull() ?: return@withContext false
        playlists.value = listOf(TubeAlbum("${InnerTube.BASE}/playlist?list=$id", name, "", first.art?.takeIf { first.tube })) + playlists.value
        true
    }

    /** A YouTube song played here, told to your history there. */
    fun played(s: Song) {
        val t = tube() ?: return
        scope.launch { runCatching { t.played(s.id.removePrefix("y")) } }
    }

    fun signOut() {
        file.delete(); auth = null
        library.delete(); stale.value = false; problem.value = null; syncing?.cancel()
        uploadsFile.delete()
        who.value = null; playlists.value = emptyList(); liked.value = emptyList(); mixes.value = emptyList(); uploads.value = emptyList()
        records.value = emptyList(); artists.value = emptyList(); this.fresh.value = emptyList()
        Loved.putTube(emptyList())
    }

    /** The cookie signed in with, if any (a sign-in handed again is taken only if it's another). */
    fun cookie(): String? = auth?.optString("cookie")

    /** The sign-in as the phone hands it (cookie, visitor, sync, version), to hand on to a device that asks (the iPhone). */
    fun handover(): String? = auth?.let { a ->
        JSONObject().put("cookie", a.optString("cookie")).put("visitor", a.optString("visitor")).put("sync", a.optString("sync"))
            .put("version", a.optString("version")).toString()
    }

    /** The sign-in handed over: tried (who are they?), kept if it works. */
    fun take(o: JSONObject): Who? {
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
        auth = kept; who.value = w; stale.value = false
        sync(fresh = true)
        return w
    }

    // ---- waiting for the phone ----

    /** While waiting: the code to type on the phone; null when not. */
    val code = MutableStateFlow<String?>(null)
    /** Where the phone can reach it by hand, if the wifi doesn't show it ("192.168.1.20:52114"). */
    val at = MutableStateFlow<String?>(null)
    /** What the QR code beside the code says ([Handover.link]): the code, the port and every address, for the phone's camera. */
    val link = MutableStateFlow<String?>(null)
    private var announcing: Process? = null
    /** What happened, said ("signed in as ...", "that didn't work"). */
    val said = MutableStateFlow<String?>(null)
    private var server: ServerSocket? = null
    private var dns: List<javax.jmdns.JmDNS> = emptyList()

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
                val all = Serve.addresses().ifEmpty { listOf(java.net.InetAddress.getLocalHost().hostAddress) }
                at.value = "${all.first()}:${s.localPort}"
                link.value = Handover.link(code.value.orEmpty(), s.localPort, all.take(4))
                // a Mac's own announcer (mDNSResponder, as every Mac app's): a second one in the app (JmDNS) fought it
                // for the port and the phone never heard; elsewhere JmDNS, on each of the likeliest few (one per network:
                // the wifi's may not be the first)
                if (Home.mac) announcing = ProcessBuilder("/usr/bin/dns-sd", "-R", name, Handover.SERVICE, "local", s.localPort.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                else dns = all.take(3).mapNotNull { ip ->
                    runCatching {
                        javax.jmdns.JmDNS.create(java.net.InetAddress.getByName(ip))
                            .also { it.registerService(javax.jmdns.ServiceInfo.create("${Handover.SERVICE}.local.", name, s.localPort, "ink music")) }
                    }.onFailure { Log.d("handover dns $ip: $it") }.getOrNull()
                }
            }.onFailure { Log.d("handover dns: $it") }
            // ten minutes; the QR code kept to the addresses as they are (the computer moved onto the phone's hotspot,
            // say, the phone's way round a wifi that keeps devices apart)
            repeat(120) {
                kotlinx.coroutines.delay(5_000)
                val c = code.value
                if (server !== s || c == null) return@launch
                val now = Serve.addresses()
                if (now.isNotEmpty()) { at.value = "${now.first()}:${s.localPort}"; link.value = Handover.link(c, s.localPort, now.take(4)) }
            }
            if (server === s) stop()
        }
    }

    fun stop() {
        runCatching { server?.close() }; server = null
        announcing?.destroy(); announcing = null
        at.value = null
        link.value = null
        val d = dns; dns = emptyList()
        if (d.isNotEmpty()) scope.launch { d.forEach { runCatching { it.unregisterAllServices(); it.close() } } }
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
            // a phone pairing ([Sync]): who it is and its sign-in, sealed; the key the two keep worked out on each side
            "POST" to "/pair" -> runCatching {
                val o = JSONObject(body)
                val theirs = Handover.decode(o.getString("key"))
                val text = runCatching { Handover.open(keys, theirs, o.getString("iv"), o.getString("data")) }
                    .getOrElse { error("it couldn't be opened here, so it wasn't for this computer") }
                val hello = JSONObject(text)
                SyncHub.paired(hello.getString("id"), hello.optString("name", "your phone"), Handover.kept(keys, theirs))
                val w = hello.optJSONObject("youtube")?.let { take(it) }
                said.value = "paired with ${hello.optString("name", "your phone").lowercase()}" + (w?.let { ", signed in as ${it.name.lowercase()}" } ?: "")
                scope.launch { kotlinx.coroutines.delay(500); stop() }
                // and where to call for sync, so a phone that got here without mDNS (by the QR code) needn't find it again
                200 to JSONObject().put("ok", true).put("name", name).put("id", SyncHub.kept.id()).put("sync", SyncHub.port())
            }.getOrElse { e -> said.value = "that didn't work: ${e.message?.lowercase()}"; 400 to JSONObject().put("ok", false).put("why", e.message.orEmpty()) }
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
