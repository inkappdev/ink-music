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

/** What YouTube Music found for find: songs, records, artists, playlists. */
data class TubeFound(
    val songs: List<Song> = emptyList(), val albums: List<TubeAlbum> = emptyList(),
    val artists: List<TubeArtist> = emptyList(), val lists: List<TubeAlbum> = emptyList(),
)

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

    /**
     * Songs and records by these words, and for find (`all`) artists and playlists too, all asked at once; null when
     * YouTube Music didn't answer at all.
     */
    suspend fun search(q: String, all: Boolean = false): TubeFound? = withContext(Dispatchers.IO) {
        runCatching { Pipe.init() }.onFailure { Log.d("youtube: $it"); return@withContext null }
        val f = yt.searchQHFactory
        fun <T> ask(filter: String, what: String, read: (List<org.schabi.newpipe.extractor.InfoItem>) -> List<T>) = async {
            runCatching { read(SearchInfo.getInfo(yt, f.fromQuery(q, listOf(filter), "")).relatedItems) }
                .onFailure { Log.d("youtube $what: $it") }.getOrNull()
        }
        val songs = ask(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, "songs") { l -> l.filterIsInstance<StreamInfoItem>().map { song(it) } }
        val records = ask(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS, "records") { l ->
            l.filterIsInstance<PlaylistInfoItem>().map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), Pipe.best(it.thumbnails)) }
        }
        val artists = if (!all) null else ask(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS, "artists") { l ->
            l.filterIsInstance<org.schabi.newpipe.extractor.channel.ChannelInfoItem>().map {
                TubeArtist(it.name, Pipe.best(it.thumbnails), Regex("/channel/(UC[A-Za-z0-9_-]+)").find(it.url)?.groupValues?.get(1).orEmpty())
            }
        }
        val lists = if (!all) null else ask(YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS, "playlists") { l ->
            l.filterIsInstance<PlaylistInfoItem>().map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), Pipe.best(it.thumbnails)) }
        }
        val (s, r, a) = Triple(songs.await(), records.await(), artists?.await())
        if (s == null && r == null && a == null) null
        else TubeFound(s.orEmpty().take(20), r.orEmpty().take(12), a.orEmpty().take(8), lists?.await().orEmpty().take(12))
    }

    /**
     * A record's songs, in order: YouTube Music's own page for it (a record from a search comes as its playlist,
     * "OLAK5uy_...", read as one), kept for the run.
     */
    suspend fun album(record: TubeAlbum): List<Song> = withContext(Dispatchers.IO) {
        albums[record.url]?.let { return@withContext it }
        // as you when signed in (your own playlists are private)
        val tube = TubeAccount.tube() ?: InnerTube()
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
            // as you, signed in: YouTube makes the radio from what you listen to
            (TubeAccount.tube() ?: InnerTube()).queue("RDAMVM$vid", videoId = vid).filter { it.videoId != vid && (it.seconds == 0 || it.seconds in 60..900) }.map { trackSong(it) }
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
            .filterIsInstance<StreamInfoItem>().take(8).map { song(it) }
                .firstOrNull { Pipe.same(it.title, it.artist, title, artist) }
    }.getOrNull()

    /** A song's audio stream (blocking: the player's load hook, a speaker's ask): the best there is, or for a speaker the m4a. */
    fun stream(id: String, forSpeaker: Boolean = false, small: Boolean = false): String? {
        val key = id + (if (forSpeaker) ":s" else "") + (if (small) ":small" else "")
        streams[key]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val info = Pipe.streamInfo(id.removePrefix("y")) ?: return null
        val (url, _, _) = Pipe.audio(info, forSpeaker = forSpeaker, small = small) ?: return null
        streams[key] = url to System.currentTimeMillis() + 4 * 3_600_000L
        return url
    }

    /** A song as YouTube Music's own pages list it; on a record's page, the record's words and cover. */
    /** A song from YouTube Music's own pages (an artist's top songs). */
    fun song(t: InnerTube.Track): Song = trackSong(t)

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
