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
)

/**
 * Artists' pages from YouTube Music (the phone's YouTubeAccount.artist, signed out): found by name (a search,
 * half a second more) or by their page's id, read whole (InnerTube, shared), and kept on disk, so a page opened
 * before shows at once while it's read again.
 */
object Artists {
    private val dir = File(Home.cache, "artists")
    private val reads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ArtistInfo?>>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Their page: the one kept (at once), then the one read now. */
    fun of(name: String, id: String = ""): Flow<ArtistInfo?> = flow {
        val key = key(name, id)
        val was = kept(key)
        if (was != null) emit(was)
        val found = read(name, id.ifEmpty { was?.id.orEmpty() }, key)
        if (found != null) emit(found) else if (was == null) emit(null)
    }.flowOn(Dispatchers.IO)

    /** Read ahead (fans also like, before one's clicked), quietly. */
    fun warm(name: String, id: String) { scope.launch { if (kept(key(name, id)) == null) read(name, id, key(name, id)) } }

    /** One read at a time for each: asked again while it's under way, the same read is waited on. */
    private suspend fun read(name: String, id: String, key: String): ArtistInfo? {
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
        val page = id.ifEmpty { find(name)?.takeIf { it.name.equals(name, ignoreCase = true) }?.id.orEmpty() }
        if (page.isEmpty()) return null
        val a = InnerTube().artist(page)
        val who = a.name.ifEmpty { name }
        fun record(it: InnerTube.Item) = TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, who, it.art)
        ArtistInfo(page, who, a.photo, a.about, a.audience, a.top.map { YouTube.song(it) }, a.albums.map(::record), a.singles.map(::record),
            a.like.map { TubeArtist(it.title, it.art, it.id) })
    }.onFailure { Log.d("artist $name: $it") }.getOrNull()

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
            TubeAlbum(it.getString("url"), it.getString("title"), it.getString("artist"), it.optString("art").ifEmpty { null }) } }
        ArtistInfo(
            o.getString("id"), o.getString("name"), o.optString("photo").ifEmpty { null }, o.optString("about"), o.optString("audience"),
            o.getJSONArray("top").let { a -> (0 until a.length()).map { songOf(a.getJSONObject(it)).also(YouTube::remember) } },
            albums("albums"), albums("singles"),
            o.getJSONArray("like").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
                TubeArtist(it.getString("name"), it.optString("face").ifEmpty { null }, it.optString("id")) } },
        )
    }.getOrNull()

    private fun keep(key: String, a: ArtistInfo) = runCatching {
        fun albums(l: List<TubeAlbum>) = JSONArray(l.map { JSONObject().put("url", it.url).put("title", it.title).put("artist", it.artist).put("art", it.art ?: "") })
        dir.mkdirs()
        Home.write(File(dir, "$key.json"), JSONObject().put("id", a.id).put("name", a.name).put("photo", a.photo ?: "").put("about", a.about)
            .put("audience", a.audience).put("top", JSONArray(a.top.map { it.json() })).put("albums", albums(a.albums)).put("singles", albums(a.singles))
            .put("like", JSONArray(a.like.map { JSONObject().put("name", it.name).put("face", it.face ?: "").put("id", it.id) })).toString())
    }

    /** What's said of them, its first sentence pulled out (big, as a lyric is) when it's a fair length; the rest. */
    fun pullQuote(about: String): Pair<String?, String> {
        val end = Regex("""[.!?](?=\s+[A-Z“"(])""").find(about)?.range?.last ?: return null to about
        val first = about.substring(0, end + 1).trim()
        return if (first.length in 20..180) first to about.substring(end + 1).trim() else null to about
    }
}

/** A face or photo as a song-shaped picture (a cover to read), kept apart from records'. */
fun faceSong(name: String, face: String, photo: Boolean = false) = Song(
    id = (if (photo) "yphoto:" else "yface:") + name, path = "", title = name, artist = name, album = name,
    albumId = (if (photo) "photo:" else "face:") + name, albumArtist = name, duration = 0, disc = 1, track = 0, year = 0, art = face,
)
