package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

/** A YouTube Music record found: its link, name, artist and cover. */
data class TubeAlbum(val url: String, val title: String, val artist: String, val art: String?)

/**
 * YouTube Music on the desktop, signed out (the phone's YouTubeMusic, the parts so far): search songs and
 * records, open a record, songs like one (radio), and a song's stream as it starts (they expire, so each is
 * found as it plays and kept a few hours). Songs are "y<videoId>", queued as "ytm://<videoId>" and resolved
 * by the player ([Player]'s load hook). NewPipe and YouTube Music's own API (InnerTube) are the phone's own
 * code, shared (the youtube module). Not affiliated with YouTube or Google.
 */
object YouTube {
    private val yt get() = ServiceList.YouTube
    private val known = ConcurrentHashMap<String, Song>()
    private val streams = ConcurrentHashMap<String, Pair<String, Long>>()   // id -> url, until
    private val albums = ConcurrentHashMap<String, List<Song>>()
    private val mixes = ConcurrentHashMap<String, List<Song>>()

    /** A YouTube song seen this run (a search, a record), for the queue and the player. */
    fun known(id: String): Song? = known[id]
    fun remember(s: Song) { known[s.id] = s }

    /** Songs and records by these words, both asked at once. */
    suspend fun search(q: String): Pair<List<Song>, List<TubeAlbum>> = withContext(Dispatchers.IO) {
        Pipe.init()
        val f = yt.searchQHFactory
        val songs = async {
            runCatching {
                SearchInfo.getInfo(yt, f.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")).relatedItems
                    .filterIsInstance<StreamInfoItem>().map { song(it) }
            }.getOrDefault(emptyList())
        }
        val records = async {
            runCatching {
                SearchInfo.getInfo(yt, f.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), "")).relatedItems
                    .filterIsInstance<PlaylistInfoItem>().map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), Pipe.best(it.thumbnails)) }
            }.getOrDefault(emptyList())
        }
        songs.await().take(20) to records.await().take(12)
    }

    /**
     * A record's songs, in order: YouTube Music's own page for it (a record from a search comes as its playlist,
     * "OLAK5uy_...", read as one), kept for the run.
     */
    suspend fun album(record: TubeAlbum): List<Song> = withContext(Dispatchers.IO) {
        albums[record.url]?.let { return@withContext it }
        val tube = InnerTube()
        val list = Regex("[?&]list=([^&]+)").find(record.url)?.groupValues?.get(1)
        val (own, tracks) = runCatching {
            if ("/browse/" in record.url) tube.album(record.url.substringAfterLast('/'))
            else list to tube.tracks("VL$list")
        }.getOrNull() ?: return@withContext emptyList()
        val id = own ?: list ?: record.url.hashCode().toString()
        val songs = if (id.startsWith("OLAK5uy_") || "/browse/" in record.url) tracks.mapIndexed { i, t ->
            trackSong(t, album = record.title, albumId = "ya$id", albumArtist = record.artist, number = i + 1, art = record.art)
        } else tracks.map { trackSong(it) }
        songs.also { if (it.isNotEmpty()) albums[record.url] = it }
    }

    /** Songs like this one (blocking): YouTube Music's radio for it (its mix, "RDAMVM<id>"). Read once. */
    fun related(id: String): List<Song> {
        mixes[id]?.let { return it }
        val vid = id.removePrefix("y")
        val mix = runCatching {
            InnerTube().queue("RDAMVM$vid", videoId = vid).filter { it.videoId != vid && (it.seconds == 0 || it.seconds in 60..900) }.map { trackSong(it) }
        }.getOrDefault(emptyList()).ifEmpty {
            runCatching {
                Pipe.init()
                PlaylistInfo.getInfo(yt, "https://music.youtube.com/watch?v=$vid&list=RDAMVM$vid").relatedItems
                    .filter { it.duration in 60..900 }.map { song(it) }.filter { it.id != id }
            }.getOrDefault(emptyList())
        }
        return mix.also { if (it.isNotEmpty()) mixes[id] = it }
    }

    /** YouTube Music's song by these words, if it has one (blocking). */
    fun find(title: String, artist: String): Song? = runCatching {
        Pipe.init()
        SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery("$artist $title", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")).relatedItems
            .filterIsInstance<StreamInfoItem>().firstOrNull()?.let { song(it) }
    }.getOrNull()

    /** A song's audio stream (blocking: the player's load hook): the best there is, its link. */
    fun stream(id: String): String? {
        streams[id]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val info = Pipe.streamInfo(id.removePrefix("y")) ?: return null
        val (url, _, _) = Pipe.audio(info, forSpeaker = false, small = false) ?: return null
        streams[id] = url to System.currentTimeMillis() + 4 * 3_600_000L
        return url
    }

    /** A song as YouTube Music's own pages list it; on a record's page, the record's words and cover. */
    private fun trackSong(t: InnerTube.Track, album: String? = null, albumId: String? = null, albumArtist: String? = null, number: Int = 0, art: String? = null): Song {
        val artist = t.artists.joinToString(", ").ifEmpty { albumArtist ?: "unknown" }
        return Song(
            id = "y${t.videoId}", path = "", title = t.title, artist = artist,
            album = album ?: t.album.orEmpty(), albumId = albumId ?: t.albumId?.let { "ya$it" } ?: "y${t.videoId}",
            albumArtist = albumArtist ?: t.artists.firstOrNull() ?: artist, duration = t.seconds * 1000L, disc = 1, track = number,
            year = 0, format = "youtube", art = t.art ?: art,
        ).also(::remember)
    }

    private fun song(it: StreamInfoItem): Song {
        val vid = Pipe.videoId(it.url)
        val (title, artist) = Pipe.tidy(it.name.orEmpty(), it.uploaderName.orEmpty())
        return Song(
            id = "y$vid", path = "", title = title.ifEmpty { "untitled" }, artist = artist.ifEmpty { "unknown" },
            album = "", albumId = "y$vid", albumArtist = artist, duration = it.duration.coerceAtLeast(0) * 1000,
            disc = 1, track = 0, year = 0, format = "youtube", art = Pipe.best(it.thumbnails),
        ).also(::remember)
    }
}
