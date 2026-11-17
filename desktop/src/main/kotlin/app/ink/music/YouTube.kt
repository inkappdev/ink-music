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

/**
 * A YouTube Music record found: its link, name, artist and cover; on an artist's page, its year and whether it's a single
 * or an EP (2026-11-16, Bronson: years on albums, singles and EPs, the latest first). 0 and "": not known.
 */
data class TubeAlbum(val url: String, val title: String, val artist: String, val art: String?, val year: Int = 0, val kind: String = "") {
    /** What's said under its cover on an artist's page: "2021", "single · 2021", "ep · 2025". */
    val dated: String get() = listOf(kind, if (year > 0) "$year" else "").filter { it.isNotEmpty() }.joinToString(" · ")
    /**
     * A playlist (a mix, a chart, someone's list), not a record: a record's link is a playlist's too ("list=OLAK5uy_...")
     * or its browse page (MPRE...). 2026-11-09: "list=" alone took DIIV's ZIRP! for a playlist (mix and covers on it,
     * no critics' quote, no artist link).
     */
    val playlist: Boolean get() = "list=" in url && "list=OLAK5uy_" !in url
}

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
    // why a song's stream couldn't be read, into the log (a song skipped had said nothing)
    init { Pipe.failed = { vid, e -> Log.d("stream: y$vid couldn't be read: ${e::class.simpleName}: ${e.message?.take(160)}") } }
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
        // local files only: YouTube Music not asked (Local); found nothing, not "couldn't reach"
        if (Local.on) return@withContext TubeFound()
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
    suspend fun album(record: TubeAlbum): List<Song> = albums[record.url] ?: kept(record) ?: read(record)

    /**
     * A list as last read whole, from the cache (2026-11-16, Bronson: "the speed of loading youtube music playlists": a
     * list read whole each time it was opened, ~0.4 s a hundred songs, 3.7 s for 871, and again each start). Null if
     * it was never read here.
     */
    fun kept(record: TubeAlbum): List<Song>? {
        albums[record.url]?.let { return it }
        val f = keptFile(record)
        if (!f.exists()) return null
        return runCatching { org.json.JSONArray(f.readText()).let { a -> (0 until a.length()).map { songOf(a.getJSONObject(it)) } } }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.also { songs -> songs.forEach(::remember) }
    }

    private val listsDir by lazy { java.io.File(Home.cache, "youtube-lists").apply { mkdirs() } }
    private fun keptFile(record: TubeAlbum) = java.io.File(listsDir, Integer.toHexString(record.url.hashCode()) + ".json")

    /**
     * A record's or list's songs read from YouTube Music now, [each] told the songs so far as each page comes (the first
     * hundred in ~0.3 s): kept for the run and in the cache once read whole.
     */
    suspend fun read(record: TubeAlbum, each: ((List<Song>) -> Unit)? = null): List<Song> = withContext(Dispatchers.IO) {
        if (Local.on) return@withContext emptyList()
        // as you when signed in (your own playlists are private)
        val tube = TubeAccount.tube() ?: InnerTube()
        val list = Regex("[?&]list=([^&]+)").find(record.url)?.groupValues?.get(1)
        // a mix ("RD...") goes on for ever, page after page of more like it: its first 50, as the phone reads it, its
        // queue where it has no page (2026-11-10: Discover Mix was read to the 40-page stop, 1,200 songs in 15 s)
        val mix = list?.startsWith("RD") == true
        fun songs(id: String, tracks: List<InnerTube.Track>) = if (id.startsWith("OLAK5uy_") || "/browse/" in record.url) tracks.mapIndexed { i, t ->
            trackSong(t, album = record.title, albumId = "ya$id", albumArtist = record.artist, number = i + 1, art = record.art)
        } else tracks.map { trackSong(it) }
        // the songs so far, page by page, while a list is read (a record comes in one)
        val so = each?.let { tell -> { t: List<InnerTube.Track> -> tell(songs(list ?: "", t)) } }
        val (own, tracks) = runCatching {
            if ("/browse/" in record.url) tube.album(record.url.substringAfterLast('/'))
            else list to tube.tracks("VL$list", max = if (mix) 50 else 2_000, each = so).ifEmpty { if (mix) tube.queue(list!!) else emptyList() }
        }.getOrNull() ?: return@withContext emptyList()
        val id = own ?: list ?: record.url.hashCode().toString()
        songs(id, tracks).also {
            if (it.isEmpty()) return@also
            albums[record.url] = it
            runCatching {
                Home.write(keptFile(record), org.json.JSONArray(it.map { s -> s.json() }).toString())
                // the 150 read last kept (~400 kB for 900 songs): the rest let go
                listsDir.listFiles()?.sortedByDescending { f -> f.lastModified() }?.drop(150)?.forEach { f -> f.delete() }
            }
        }
    }

    /**
     * A record's whole tracklist on YouTube Music, for one you have only part of (2026-11-10, Bronson: the rest of an
     * album filled in): found by its name and whose it is (the same record, never another of that name); null where
     * YouTube Music hasn't it. Kept for the run.
     */
    suspend fun whole(title: String, artist: String): List<Song>? {
        if (Local.on) return null
        val k = "$artist/$title".lowercase()
        wholes[k]?.let { return it }
        val found = search("$artist $title")?.albums?.firstOrNull { Pipe.same(it.title, it.artist, title, artist) } ?: return null
        return album(found).takeIf { it.isNotEmpty() }?.also { wholes[k] = it }
    }
    private val wholes = ConcurrentHashMap<String, List<Song>>()

    /** Songs like this one (blocking): YouTube Music's radio for it (its mix, "RDAMVM<id>"). Read once. */
    fun related(id: String): List<Song> {
        mixes[id]?.let { return it }
        if (Local.on) return emptyList()
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

    /**
     * Videos on youtube.com itself by these words, as songs (2026-11-15, a tester: songs that were never put on YouTube Music
     * are often up as a video): a song's length (1 to 10 minutes: how-tos and longer streams left out), its title and artist read from the video's title and
     * channel, its audio played as any YouTube song's. Shown in find apart from YouTube Music's own.
     */
    suspend fun videos(q: String): List<Song> = withContext(Dispatchers.IO) {
        if (Local.on) return@withContext emptyList()
        TubeVideos.search(q).filter { it.seconds == 0 || it.seconds in 60..600 }.map { f ->
            val (title, artist) = Pipe.tidy(f.title, f.channel)
            Song(id = "y${f.id}", path = "", title = title.ifEmpty { f.title }, artist = artist.ifEmpty { f.channel.ifEmpty { "unknown" } },
                album = "", albumId = "y${f.id}", albumArtist = artist.ifEmpty { f.channel }, duration = f.seconds * 1000L, disc = 1, track = 0,
                year = 0, format = "youtube", art = "https://i.ytimg.com/vi/${f.id}/hqdefault.jpg").also(::remember)
        }
    }

    /** A YouTube video by its id, as a song (its title and artist read from its own): a link pasted into find (blocking). */
    fun byId(vid: String): Song? = known["y$vid"] ?: runCatching {
        val info = Pipe.streamInfo(vid) ?: return null
        val (title, artist) = Pipe.tidy(info.name.orEmpty(), info.uploaderName.orEmpty())
        Song(id = "y$vid", path = "", title = title.ifEmpty { info.name.orEmpty() }, artist = artist.ifEmpty { "unknown" }, album = "",
            albumId = "y$vid", albumArtist = artist, duration = info.duration.coerceAtLeast(0) * 1000, disc = 1, track = 0, year = 0,
            format = "youtube", art = Pipe.best(info.thumbnails)).also(::remember)
    }.getOrNull()

    /** YouTube Music's song by these words, if it has one (blocking). */
    fun find(title: String, artist: String): Song? = if (Local.on) null else runCatching {
        Pipe.init()
        SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery("$artist $title", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")).relatedItems
            .filterIsInstance<StreamInfoItem>().take(8).map { song(it) }
                .firstOrNull { Pipe.same(it.title, it.artist, title, artist) }
    }.getOrNull()

    /**
     * A song's audio stream (blocking: the player's load hook, a speaker's ask): the best there is, or for a speaker the m4a.
     * One of your uploads signed in first (it's private: no other way has it); anything no other way has, signed in last.
     */
    fun stream(id: String, forSpeaker: Boolean = false, small: Boolean = false): String? {
        val key = id + (if (forSpeaker) ":s" else "") + (if (small) ":small" else "")
        streams[key]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val url = (if (TubeAccount.uploaded(id)) signed(id) else null)
            // a song whose link wouldn't play, asked the other way from the start
            ?: (if (id in otherWay) null else newPipe(id, forSpeaker, small)) ?: other(id, forSpeaker)
            ?: (if (TubeAccount.uploaded(id)) null else signed(id)) ?: return null
        streams[key] = url to System.currentTimeMillis() + 4 * 3_600_000L
        return url
    }

    private fun newPipe(id: String, forSpeaker: Boolean, small: Boolean): String? {
        val info = Pipe.streamInfo(id.removePrefix("y")) ?: return null
        return Pipe.audio(info, forSpeaker = forSpeaker, small = small)?.first
            ?: null.also { Log.d("stream: $id read, but no audio to play in it") }
    }

    /**
     * The other way to a song's audio (2026-11-16, a Windows tester's songs skipping): as YouTube's visionOS app asks
     * (TubeStream, the iPhone's), its link tried at its start and middle before it's trusted. Asked when NewPipe has
     * none, and for a song whose link wouldn't play ([playedBadly]). Not for a speaker (it wants the m4a NewPipe picks).
     */
    private fun other(id: String, forSpeaker: Boolean): String? {
        if (forSpeaker) return null
        // one at a time: two at once (the player's and automix's measuring, the same song) turned one away
        val s = synchronized(tubeStream) { runCatching { tubeStream.stream(id.removePrefix("y")) }.getOrNull() }
        Log.d(if (s != null) "stream: $id the other way (${s.how})" else "stream: $id none either way")
        return s?.url
    }

    private val tubeStream by lazy {
        // no JavaScript here: the visionOS way needs none, and the web player's way (challenges, tokens) steps aside
        val none = object : TubeJs {
            override fun solve(playerUrl: String, playerCode: String, sig: String?, n: String?): Pair<String?, String?>? = null
            override fun poToken(id: String): String? = null
        }
        TubeStream(none, java.io.File(Home.cache, "tube-stream").apply { mkdirs() }).apply { log = { Log.d("tubestream: $it") } }
    }

    /**
     * A song as YouTube Music's web player gets it signed in (2026-11-17, for your uploads, which no other way has): the
     * shared TubeStream as you, its challenges worked out by a JavaScript runtime on the computer ([Solve]). Null signed
     * out, or with no runtime to solve with. Its link is the best AAC (what a speaker plays too).
     */
    private fun signed(id: String): String? {
        val t = TubeAccount.tube() ?: return null
        if (Solve.runtime == null) return null.also { Log.d("stream: $id signed in needs deno, node or bun to solve with: none here") }
        val cookie = TubeAccount.cookie()
        val ts = synchronized(signedStreams) {
            signedStreams.first.takeIf { signedStreams.second == cookie }
                ?: TubeStream(Solve, java.io.File(Home.cache, "tube-stream-signed").apply { mkdirs() }, t).apply { log = { Log.d("tubestream signed: $it") } }
                    .also { signedStreams = it to cookie }
        }
        // one at a time, as the other way's
        val s = synchronized(ts) { runCatching { ts.stream(id.removePrefix("y")) }.getOrNull() }
        Log.d(if (s != null) "stream: $id signed in (${s.how})" else "stream: $id none signed in")
        return s?.url
    }
    // the signed-in finder and the sign-in it was made for (a new sign-in, a new one)
    @Volatile private var signedStreams: Pair<TubeStream?, String?> = null to null

    /** Songs whose link wouldn't play this run: asked the other way from now on. */
    private val otherWay = ConcurrentHashMap.newKeySet<String>()

    /**
     * A YouTube song whose link mpv couldn't play: its link forgotten, asked the other way from now on. True the first
     * time (worth trying it again), false if it was already the other way's.
     */
    fun playedBadly(id: String): Boolean {
        streams.keys.removeIf { it == id || it.startsWith("$id:") }
        return otherWay.add(id)
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
