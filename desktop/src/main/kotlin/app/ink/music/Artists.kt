package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.io.File

/** An artist on YouTube Music: their name and photo, and their page ("UC...") when known. */
data class TubeArtist(val name: String, val face: String?, val id: String = "")

/**
 * An artist as YouTube Music knows them, for their page: their photo (wide), what's said of them,
 * how many listen a month, their best-known songs, records and singles, and the artists like them.
 */
data class ArtistInfo(
    val id: String, val name: String, val photo: String?, val about: String, val audience: String,
    val top: List<Song>, val albums: List<TubeAlbum>, val singles: List<TubeAlbum>, val like: List<TubeArtist>,
    /** The playlists they're on (YouTube Music's "featured on", and their own), 2026-11-08. */
    val featured: List<TubeAlbum> = emptyList(),
)

/**
 * Artists' pages from YouTube Music (the phone's YouTubeAccount.artist, signed out): found by name (a search,
 * half a second more) or by their page's id, read whole (InnerTube, shared), and kept on disk, so a page opened
 * before shows at once while it's read again.
 */
object Artists {
    /**
     * A record's or song's artists, each a name of its own: YouTube lists collaborators with ", " (only that: an "&"
     * is often one name's own, Iron & Wine).
     */
    fun named(artists: String): List<String> = artists.split(", ").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(artists) }

    private val dir = File(Home.cache, "artists")
    private val reads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ArtistInfo?>>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Their page: the one kept (at once), then the one read now. */
    fun of(name: String, id: String = ""): Flow<ArtistInfo?> = flow {
        val key = key(name, id)
        val was = kept(key)
        if (was != null) emit(was)
        // read in the last half hour: as it is (however often it's asked; the one read then is fresh enough)
        if (was != null && System.currentTimeMillis() - File(dir, "$key.json").lastModified() < 30 * 60_000L) return@flow
        val found = read(name, id.ifEmpty { was?.id.orEmpty() }, key)
        if (found != null) emit(found) else if (was == null) emit(null)
    }.flowOn(Dispatchers.IO)

    /** Read ahead (fans also like, before one's clicked), quietly. */
    fun warm(name: String, id: String) { scope.launch { if (kept(key(name, id)) == null) read(name, id, key(name, id)) } }

    /** One read at a time for each: asked again while it's under way, the same read is waited on. */
    private suspend fun read(name: String, id: String, key: String): ArtistInfo? {
        // local files only: not started (Local)
        if (Local.on) return null
        val d = reads.computeIfAbsent(key) {
            scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { fetch(name, id)?.also { keep(key, it) } }
        }
        d.invokeOnCompletion { reads.remove(key, d) }
        d.start()
        return d.await()
    }

    private fun key(name: String, id: String) = (id.ifEmpty { name.lowercase() }).replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Their page from YouTube Music: by its id, else found by name first. */
    private fun fetch(name: String, id: String): ArtistInfo? = runCatching {
        // by its id; else by their name (only one called just that); else through a record of theirs you have, where the
        // name finds someone else or no one (2026-11-16: "Ace" found Yungeen Ace; "Five-a-Side" finds Ace)
        val page = id.ifEmpty { find(name)?.takeIf { it.name.equals(name, ignoreCase = true) }?.id.orEmpty() }
            .ifEmpty { byRecord(name).orEmpty() }
        if (page.isEmpty()) return null
        val a = InnerTube().artist(page)
        val who = a.name.ifEmpty { name }
        // its year and kind from the line under it ("2021", "Single • 2021", "EP • 2025")
        fun record(it: InnerTube.Item) = TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, who, it.art,
            year = Regex("""\b(19|20)\d{2}\b""").findAll(it.subtitle).lastOrNull()?.value?.toInt() ?: 0,
            kind = it.subtitle.substringBefore(" • ").trim().lowercase().takeIf { k -> k == "single" || k == "ep" }.orEmpty())
        // no bio of YouTube's own, or a scrap: one found (Wikipedia's, else Last.fm's), kept with the page
        val about = if (Bio.scant(a.about)) Bio.find(who, LastFm.key) ?: a.about else a.about
        ArtistInfo(page, who, a.photo, about, a.audience, a.top.map { YouTube.song(it) }, a.albums.map(::record), a.singles.map(::record),
            a.like.map { TubeArtist(it.title, it.art, it.id) },
            a.featured.map { TubeAlbum("${InnerTube.BASE}/playlist?list=${it.id.removePrefix("VL")}", it.title, "youtube music", it.art) })
    }.onFailure { Log.d("artist $name: $it") }.getOrNull()

    /** Their page's id through one of their records here (the first two tried). */
    private fun byRecord(name: String): String? = Library.albums.value.filter { it.artist.equals(name, ignoreCase = true) }.take(2)
        .firstNotNullOfOrNull { InnerTube().artistByRecord(it.title, name) }
        ?.also { Log.d("artist $name: found through a record of theirs") }

    /** An artist by name, with their photo and page id (a search). */
    private fun find(name: String): TubeArtist? = runCatching {
        Pipe.init()
        val yt = ServiceList.YouTube
        val found = SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(name, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")).relatedItems
            .filterIsInstance<ChannelInfoItem>().firstOrNull() ?: return null
        val id = Regex("/channel/(UC[A-Za-z0-9_-]+)").find(found.url)?.groupValues?.get(1).orEmpty()
        TubeArtist(found.name, Pipe.best(found.thumbnails), id)
    }.getOrNull()

    private fun kept(key: String): ArtistInfo? = runCatching {
        val o = JSONObject(File(dir, "$key.json").readText())
        fun albums(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            TubeAlbum(it.getString("url"), it.getString("title"), it.getString("artist"), it.optString("art").ifEmpty { null },
                it.optInt("year"), it.optString("kind")) } }
        ArtistInfo(
            o.getString("id"), o.getString("name"), o.optString("photo").ifEmpty { null }, o.optString("about"), o.optString("audience"),
            o.getJSONArray("top").let { a -> (0 until a.length()).map { songOf(a.getJSONObject(it)).also(YouTube::remember) } },
            albums("albums"), albums("singles"),
            o.getJSONArray("like").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
                TubeArtist(it.getString("name"), it.optString("face").ifEmpty { null }, it.optString("id")) } },
            if (o.has("featured")) albums("featured") else emptyList(),
        )
    }.getOrNull()

    private fun keep(key: String, a: ArtistInfo) = runCatching {
        fun albums(l: List<TubeAlbum>) = JSONArray(l.map { JSONObject().put("url", it.url).put("title", it.title).put("artist", it.artist).put("art", it.art ?: "")
            .put("year", it.year).put("kind", it.kind) })
        dir.mkdirs()
        Home.write(File(dir, "$key.json"), JSONObject().put("id", a.id).put("name", a.name).put("photo", a.photo ?: "").put("about", a.about)
            .put("audience", a.audience).put("top", JSONArray(a.top.map { it.json() })).put("albums", albums(a.albums)).put("singles", albums(a.singles))
            .put("like", JSONArray(a.like.map { JSONObject().put("name", it.name).put("face", it.face ?: "").put("id", it.id) }))
            .put("featured", albums(a.featured)).toString())
    }

    // ---- faces: an artist's square picture, for a tile ----

    /**
     * Their square picture (YouTube Music's profile one, as fans-also-like shows) by name, "" where it has none of
     * theirs: kept in faces.json (2026-11-18: the grid had shown their page's wide header cut to a square from its
     * middle, faces and lettering cut off).
     */
    private val faces = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var facesRead = false
    private val facesAsked = kotlinx.coroutines.sync.Semaphore(3)

    private fun loadFaces() {
        if (facesRead) return
        synchronized(faces) {
            if (facesRead) return
            runCatching { JSONObject(File(dir, "faces.json").readText()).let { o -> o.keys().forEach { k -> faces[k] = o.getString(k) } } }
            facesRead = true
        }
    }
    private fun keepFaces() = runCatching { dir.mkdirs(); Home.write(File(dir, "faces.json"), JSONObject(faces as Map<*, *>).toString()) }

    /** Their square picture if it's known already (no reading, no asking): a tile's first look. */
    fun faceKnown(name: String): String? = if (!facesRead) null else faces[name.lowercase()]?.ifEmpty { null }

    /**
     * Their picture for a tile: the square one, kept or (`ask`) looked for once; where YouTube Music has none of
     * theirs, their page's wide photo if it's kept. Null: none yet.
     */
    suspend fun face(name: String, ask: Boolean = true): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        loadFaces()
        val k = name.lowercase()
        val known = faces[k]
        val square = known?.ifEmpty { null } ?: if (known != null || !ask || Local.on) null else {
            facesAsked.acquire()
            try {
                // only theirs (the name the same): a search that couldn't be made isn't kept, so it's asked again later
                val found = find(name)
                val url = found?.takeIf { it.name.equals(name, ignoreCase = true) }?.face
                if (found != null) { faces[k] = url.orEmpty(); keepFaces() }
                url
            } finally { facesAsked.release() }
        }
        square ?: kept(key(name, ""))?.photo
    }

    /** The next tiles' pictures read ahead as the grid scrolls, so they're there when they come into sight. */
    fun warmFaces(names: List<String>) { scope.launch { loadFaces(); names.forEach { if (faces[it.lowercase()] == null) face(it) } } }

    /** What's said of them, its first sentence pulled out (big, as a lyric is) when it's a fair length; the rest. */
    fun pullQuote(about: String): Pair<String?, String> = Bio.pullQuote(about)
}

/** A face or photo as a song-shaped picture (a cover to read), kept apart from records'. */
fun faceSong(name: String, face: String, photo: Boolean = false) = Song(
    id = (if (photo) "yphoto:" else "yface:") + name, path = "", title = name, artist = name, album = name,
    albumId = (if (photo) "photo:" else "face:") + name, albumArtist = name, duration = 0, disc = 1, track = 0, year = 0, art = face,
)
