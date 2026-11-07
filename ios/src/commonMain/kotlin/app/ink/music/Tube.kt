package app.ink.music

/** The iPhone's runners for YouTube's streams (iosMain: JavaScriptCore and a hidden WKWebView). */
expect fun tubeJs(): TubeJs

/**
 * YouTube Music on the iPhone (2026-11-07): its search, records, playlists and mixes by InnerTube (shared with the
 * phone and the computer); its songs' audio found as each plays ([TubeStream]). Signed in by the sign-in your
 * computer hands on in sync (the phone's, handed it; the iPhone can't sign in itself: Google turns embedded browsers
 * away), kept in youtube.json. A song's id is YouTube's ("youtube" its kind), a record's or list's its browse id.
 */
object Tube {
    data class Who(val name: String, val handle: String)

    private val file get() = File(Here.dir, "youtube.json")
    val who = kotlinx.coroutines.flow.MutableStateFlow<Who?>(null)
    private var signed: InnerTube? = null
    private var signedOut: InnerTube? = null
    private var tubeStreams: TubeStream? = null

    /** YouTube Music as you if signed in, else as a visitor. */
    val api: InnerTube get() = guard.run { signed ?: signedOut ?: InnerTube().also { signedOut = it } }
    private val streams: TubeStream get() = guard.run {
        tubeStreams ?: TubeStream(tubeJs(), Here.dir, signed).apply { log = { println("ink-youtube $it") } }.also { tubeStreams = it }
    }

    private fun of(o: JSONObject) = InnerTube(o.optString("cookie"), o.optString("visitor").ifEmpty { null }, o.optString("sync").ifEmpty { null },
        o.optString("version").ifEmpty { InnerTube.VERSION })

    /** The kept sign-in read, as the app opens. */
    fun start() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        guard.run { signed = of(o); tubeStreams = null; found.clear() }
        who.value = Who(o.optString("name"), o.optString("handle"))
    }

    /** A sign-in handed on by the computer: tried (who are they?), kept if it works. Blocking. */
    fun take(auth: String) {
        val o = runCatching { JSONObject(auth) }.getOrNull() ?: return
        val cookie = o.optString("cookie").ifEmpty { return }
        if (runCatching { JSONObject(file.readText()).optString("cookie") }.getOrNull() == cookie) return
        val w = of(o).who() ?: return
        Keep.write(file, JSONObject(auth).put("name", w.name).put("handle", w.handle).toString())
        start()
        library.value = null
        warm()
    }

    fun signOut() {
        file.delete()
        guard.run { signed = null; tubeStreams = null; found.clear() }
        who.value = null; library.value = null
    }

    /** Your YouTube Music, read once a run (and after a sign-in): your mixes, your playlists, your liked songs, your home. */
    data class Library(val mixes: List<Album>, val lists: List<Album>, val liked: List<Song>, val home: List<Pair<String, List<Album>>>)
    val library = kotlinx.coroutines.flow.MutableStateFlow<Library?>(null)

    fun readLibrary(): Library? {
        if (who.value == null) return null
        library.value?.let { return it }
        val t = api
        val home = runCatching { t.home(12) }.getOrDefault(emptyList())
        val mixes = home.flatMap { s -> s.items.filter { it.kind == InnerTube.Kind.PLAYLIST && (it.id.startsWith("VLRDTMAK") || it.id.startsWith("RDTMAK") || "mix" in s.title.lowercase()) } }
            .distinctBy { it.id }.map(::list)
        val lists = runCatching { t.items("FEmusic_liked_playlists", 200) }.getOrDefault(emptyList())
            .filter { it.kind == InnerTube.Kind.PLAYLIST && it.id != "VLLM" && it.id != "VLSE" }.map(::list)
        val liked = runCatching { t.tracks("VLLM", 500) }.getOrDefault(emptyList()).map { song(it) }
        val shelves = home.filter { s -> s.items.any { it.kind == InnerTube.Kind.RECORD } }
            .map { s -> s.title to s.items.filter { it.kind == InnerTube.Kind.RECORD }.map(::record) }.filter { it.second.isNotEmpty() }
        return Library(mixes, lists, liked, shelves).also { library.value = it }
    }

    /** A playlist or a mix as a record-shaped thing: its id, its name, whose, its picture. */
    fun list(i: InnerTube.Item): Album = Album(i.id, i.title, i.subtitle.substringAfter(" • ", "").ifEmpty { i.subtitle }, i.art, 0, youtube = true)

    /** Heard here: told to YouTube's history (so its mixes know), as the phone does. */
    fun played(id: String) { if (who.value != null) runCatching { api.played(id) } }
    private val found = HashMap<String, Pair<String, Long>>()
    private val guard = Guard()

    fun song(t: InnerTube.Track, record: String? = null): Song = Song(t.videoId, t.title, t.artists.joinToString(", "), t.album ?: record.orEmpty(),
        t.albumId.orEmpty(), t.art, t.seconds * 1000L, 0, 1, kind = "youtube")

    fun record(i: InnerTube.Item): Album = Album(i.id, i.title, i.subtitle.split(" • ").getOrNull(1) ?: i.subtitle, i.art,
        i.subtitle.split(" • ").lastOrNull()?.toIntOrNull() ?: 0, youtube = true)

    /** The first song's slow parts made as the app opens (blocking). */
    fun warm() { if (who.value != null) runCatching { streams.warm() } }

    /** A song's stream link (blocking), kept a few hours (YouTube's links last six). */
    fun stream(id: String): String? {
        guard.run { found[id]?.takeIf { nowMs() - it.second < 4 * 3_600_000L }?.first }?.let { return it }
        val s = streams.stream(id) ?: return null
        guard.run { found[id] = s.url to nowMs() }
        return s.url
    }

    /** A record's songs, in order; a playlist's; a mix's first page (a mix never ends). */
    fun songs(a: Album): List<Song> = when {
        a.id.startsWith("MPREb") -> api.album(a.id).second.map { song(it, a.title) }
        a.id.removePrefix("VL").startsWith("RD") -> api.queue(a.id.removePrefix("VL"), 50).map { song(it) }
        else -> api.tracks(if (a.id.startsWith("VL")) a.id else "VL" + a.id, 500).map { song(it) }
    }.mapIndexed { i, s -> s.copy(track = i + 1) }

    /** A critic's pick found on YouTube Music: its record of that name by that artist (a song's, for a song), or null. */
    fun find(title: String, artist: String, record: Boolean): Album? {
        val t = Search.fold(title.substringBefore(" (").substringBefore(" [")); val a = Search.fold(artist)
        fun by(s: String) = Search.fold(s).let { it == a || it.contains(a) || a.contains(it) }
        return if (record) api.searchItems("$artist $title", InnerTube.Kind.RECORD, 8).firstOrNull { i ->
            Search.fold(i.title.substringBefore(" (")) == t && by(i.subtitle)
        }?.let(::record)
        else api.searchSongs("$artist $title", 8).firstOrNull { s -> Search.fold(s.title.substringBefore(" (")) == t && s.artists.any(::by) }
            ?.let { s -> s.albumId?.let { Album(it, s.album.orEmpty(), s.artists.joinToString(", "), s.art, 0, youtube = true) } }
    }
}
