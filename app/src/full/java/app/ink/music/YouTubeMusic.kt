package app.ink.music

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music, the full build only, through NewPipe's extractor (GPL-3.0): search songs and
 * records, open a record, and an audio stream for a song when it's played (streams expire, so
 * they're found as each one starts and kept a few hours). Songs are "y<videoId>", played from
 * "ytm://<videoId>", resolved by the player. Not affiliated with YouTube or Google.
 */
object YouTubeMusic {
    const val available = true
    private val yt get() = ServiceList.YouTube
    private val known = ConcurrentHashMap<String, Song>()
    private val streams = ConcurrentHashMap<String, Triple<String, String, Long>>()   // id -> url, mime, until
    private val related = ConcurrentHashMap<String, List<Song>>()   // id -> the songs YouTube sets beside it
    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) { if (!ready) { NewPipe.init(Fetch, Localization("en", "US")); ready = true } }
    }

    /** Before any stream: YouTube's poToken minted on this phone (NewPipe's way: [PoTokens]). */
    fun attach(c: android.content.Context) {
        PoTokens.attach(c)
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setPoTokenProvider(PoTokens)
    }

    /** A YouTube song seen this run (a search, a record), for the queue and the player. */
    fun known(id: String): Song? = known[id]
    fun remember(s: Song) { known[s.id] = s }

    suspend fun search(q: String): TubeFound = withContext(Dispatchers.IO) {
        init()
        val f = yt.searchQHFactory
        val songs = runCatching {
            SearchInfo.getInfo(yt, f.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")).relatedItems
                .filterIsInstance<StreamInfoItem>().map { song(it) }
        }.getOrDefault(emptyList())
        val albums = runCatching {
            SearchInfo.getInfo(yt, f.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), "")).relatedItems
                .filterIsInstance<PlaylistInfoItem>().map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), best(it.thumbnails)) }
        }.getOrDefault(emptyList())
        TubeFound(songs.take(20), albums.take(12))
    }

    /** A record's songs, in order. */
    suspend fun album(record: TubeAlbum): List<Song> = withContext(Dispatchers.IO) {
        init()
        // one from your library comes as its page ("/browse/MPREb_..."): its own playlist, which NewPipe opens
        val a = if ("/browse/" !in record.url) record else record.copy(
            url = InnerTube().albumPlaylist(record.url.substringAfterLast('/'))?.let { "https://music.youtube.com/playlist?list=$it" }
                ?: return@withContext emptyList(),
        )
        val info = runCatching { PlaylistInfo.getInfo(yt, a.url) }.getOrNull() ?: return@withContext emptyList()
        val list = Regex("[?&]list=([^&]+)").find(a.url)?.groupValues?.get(1) ?: a.url.hashCode().toString()
        // a record ("OLAK5uy_..."): its songs are one record, one cover; a playlist's (a chart's) are each their own
        if (list.startsWith("OLAK5uy_")) info.relatedItems.mapIndexed { i, it ->
            song(it, album = a.title, albumId = "ya$list", albumArtist = a.artist, track = i + 1, art = a.art)
        } else info.relatedItems.map { song(it) }
    }

    /**
     * A song's audio stream (blocking: the player's loader, or a speaker's thread): its link and
     * type. For a speaker the m4a (every speaker plays AAC), else the best there is.
     */
    fun stream(id: String, forSpeaker: Boolean): Pair<String, String>? {
        val key = id + if (forSpeaker) ":s" else ""
        streams[key]?.takeIf { it.third > System.currentTimeMillis() }?.let { return it.first to it.second }
        init()
        val info = runCatching { StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=" + id.removePrefix("y")) }.getOrNull() ?: return null
        keepRelated(id, info)
        val direct = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val pick = (if (forSpeaker) direct.filter { it.format == MediaFormat.M4A }.ifEmpty { direct } else direct)
            .maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate } ?: return null
        val mime = pick.format?.mimeType ?: "audio/mp4"
        streams[key] = Triple(pick.content, mime, System.currentTimeMillis() + 4 * 3_600_000L)
        return pick.content to mime
    }

    private fun keepRelated(id: String, info: StreamInfo) {
        // songs only (a "- Topic" upload is a song; the rest is video recommendations), not mixes or live streams
        related[id] = info.relatedItems.filterIsInstance<StreamInfoItem>()
            .filter { it.duration in 60..600 && it.uploaderName.orEmpty().endsWith(" - Topic") }.map { song(it) }
    }

    /**
     * Songs like this one (blocking): YouTube Music's radio for it (its mix, "RDAMVM<id>"), else
     * the songs among those YouTube sets beside it. Read once.
     */
    fun related(id: String): List<Song> {
        mixes[id]?.let { return it }
        init()
        val vid = id.removePrefix("y")
        val mix = runCatching {
            PlaylistInfo.getInfo(yt, "https://music.youtube.com/watch?v=$vid&list=RDAMVM$vid").relatedItems
                .filter { it.duration in 60..900 }.map { song(it) }.filter { it.id != id }
        }.onFailure { android.util.Log.d("ink-radio", "no mix for $id: $it") }.getOrDefault(emptyList())
        if (mix.isNotEmpty()) return mix.also { mixes[id] = it }
        related[id]?.let { return it }
        val info = runCatching { StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$vid") }.getOrNull() ?: return emptyList()
        keepRelated(id, info)
        return related[id].orEmpty()
    }
    private val mixes = ConcurrentHashMap<String, List<Song>>()

    private val artists = ConcurrentHashMap<String, TubeArtist>()

    /** An artist by name, with their photo (blocking; kept). */
    fun artist(name: String): TubeArtist? {
        artists[name.lowercase()]?.let { return it }
        init()
        val found = runCatching {
            SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(name, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")).relatedItems
                .filterIsInstance<ChannelInfoItem>().firstOrNull()
        }.getOrNull() ?: return null
        return TubeArtist(found.name, best(found.thumbnails)).also { artists[name.lowercase()] = it }
    }

    /** An artist's best-known song (blocking): the first YouTube Music finds that's theirs. */
    fun hit(name: String): Song? = runCatching {
        init()
        val songs = SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(name, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")).relatedItems
            .filterIsInstance<StreamInfoItem>().map { song(it) }
        songs.firstOrNull { it.artist.contains(name, ignoreCase = true) } ?: songs.firstOrNull()
    }.getOrNull()

    /** An artist's records (blocking), theirs only. */
    fun records(name: String): List<TubeAlbum> = runCatching {
        init()
        SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(name, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), "")).relatedItems
            .filterIsInstance<PlaylistInfoItem>().filter { it.uploaderName.orEmpty().contains(name, ignoreCase = true) }
            .map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), best(it.thumbnails)) }
    }.getOrDefault(emptyList())

    /** YouTube Music's song by these words, if it has one (blocking). */
    fun find(title: String, artist: String): Song? {
        init()
        return runCatching {
            SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery("$artist $title", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")).relatedItems
                .filterIsInstance<StreamInfoItem>().firstOrNull()?.let { song(it) }
        }.getOrNull()
    }

    private fun song(
        it: StreamInfoItem, album: String = "", albumId: String? = null, albumArtist: String? = null, track: Int = 0, art: String? = null,
    ): Song {
        val vid = Regex("[?&]v=([^&]+)").find(it.url)?.groupValues?.get(1) ?: it.url.substringAfterLast('/')
        val (title, artist) = tidy(it.name.orEmpty(), it.uploaderName.orEmpty())
        return Song(
            id = "y$vid", uri = Uri.parse("ytm://$vid"), title = title.ifEmpty { "untitled" }, artist = artist.ifEmpty { "unknown" },
            album = album, albumId = albumId ?: "y$vid", albumArtist = albumArtist?.ifEmpty { null } ?: artist,
            duration = it.duration.coerceAtLeast(0) * 1000, disc = 1, track = track, year = 0,
            art = best(it.thumbnails) ?: art, format = "youtube",
        ).also(::remember)
    }

    /**
     * A song's words as a person would say them. A song's own upload ("X - Topic") is already
     * clean; a video's ("Shakira, Burna Boy - Dai Dai (Official Video)", by "Shakira and 2 more")
     * loses its "Official Video", its "| ..." tail, and gives up the artists it starts with.
     */
    private fun tidy(name: String, uploader: String): Pair<String, String> {
        if (uploader.endsWith(" - Topic")) return name to uploader.removeSuffix(" - Topic")
        var title = name.substringBefore(" | ").trim()
        title = title.replace(Regex("""\s*[(\[][^)\]]*(official|video|lyric|visuali[sz]er|audio|mv|m/v)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
        var artist = uploader.substringBefore(" and ").substringBefore(" & ").trim()
        val dash = title.indexOf(" - ")
        // "Artist - Song": only when what's before the dash is who uploaded it ("Song - 2011 Remaster" stays whole)
        if (dash > 0 && title.substring(0, dash).contains(artist, ignoreCase = true)) {
            artist = title.substring(0, dash).substringBefore(", ").trim(); title = title.substring(dash + 3).trim()
        }
        return title to artist
    }

    /** The largest picture; YouTube Music's square ones asked for at 600 px. */
    private fun best(images: List<Image>?): String? =
        images.orEmpty().maxByOrNull { it.width * it.height }?.url?.replace(Regex("=w\\d+-h\\d+"), "=w600-h600")

    /** How NewPipe reaches YouTube: plain HTTP, as a desktop browser. */
    private object Fetch : Downloader() {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.requestMethod = request.httpMethod()
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UA)
            request.headers().forEach { (k, vs) -> vs.forEachIndexed { i, v -> if (i == 0) c.setRequestProperty(k, v) else c.addRequestProperty(k, v) } }
            request.dataToSend()?.let { data -> c.doOutput = true; c.outputStream.use { it.write(data) } }
            try {
                val code = c.responseCode
                if (code == 429) throw ReCaptchaException("rate limited", request.url())
                val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }
                return Response(code, c.responseMessage, c.headerFields.filterKeys { it != null }, body, c.url.toString())
            } finally { c.disconnect() }
        }
    }
}
