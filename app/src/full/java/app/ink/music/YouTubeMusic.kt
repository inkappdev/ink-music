package app.ink.music

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    // songs whose signed stream failed as it played: theirs NewPipe's from then on; three, and signed streams are
    // left alone till the player starts again (that phone can't make them)
    private val refusedSongs = ConcurrentHashMap.newKeySet<String>()
    private val related = ConcurrentHashMap<String, List<Song>>()   // id -> the songs YouTube sets beside it
    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) { if (!ready) { NewPipe.init(Fetch, Localization("en", "US")); ready = true } }
    }

    /** Before any stream: YouTube's poToken minted on this phone (NewPipe's way: [PoTokens]). */
    @Volatile private var app: android.content.Context? = null

    fun attach(c: android.content.Context) {
        app = c.applicationContext
        PoTokens.attach(c)
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setPoTokenProvider(PoTokens)
    }

    /** A YouTube song seen this run (a search, a record), for the queue and the player. */
    fun known(id: String): Song? = known[id]
    fun remember(s: Song) { known[s.id] = s }

    /** Songs and records by these words, both asked at once. */
    suspend fun search(q: String): TubeFound = withContext(Dispatchers.IO) {
        init()
        val t0 = System.currentTimeMillis()
        val f = yt.searchQHFactory
        val songs = async {
            runCatching {
                SearchInfo.getInfo(yt, f.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")).relatedItems
                    .filterIsInstance<StreamInfoItem>().map { song(it) }
            }.getOrDefault(emptyList())
        }
        val albums = async {
            runCatching {
                SearchInfo.getInfo(yt, f.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), "")).relatedItems
                    .filterIsInstance<PlaylistInfoItem>().map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), best(it.thumbnails)) }
            }.getOrDefault(emptyList())
        }
        TubeFound(songs.await().take(20), albums.await().take(12))
            .also { android.util.Log.d("ink-youtube", "search \"$q\": ${it.songs.size} songs, ${it.albums.size} records in ${System.currentTimeMillis() - t0} ms") }
    }

    private val albums = ConcurrentHashMap<String, List<Song>>()

    /**
     * A record's songs, in order: YouTube Music's own page for it, one read (a record from a search comes as its
     * playlist, "OLAK5uy_...", read as one), kept for the run. A chart's playlist: each song its own cover.
     */
    suspend fun album(record: TubeAlbum): List<Song> = withContext(Dispatchers.IO) {
        albums[record.url]?.let { return@withContext it }
        val t0 = System.currentTimeMillis()
        val tube = InnerTube()
        val list = Regex("[?&]list=([^&]+)").find(record.url)?.groupValues?.get(1)
        val (own, tracks) = runCatching {
            if ("/browse/" in record.url) tube.album(record.url.substringAfterLast('/'))
            else list to tube.tracks("VL$list")
        }.onFailure { android.util.Log.d("ink-youtube", "album ${record.url}: $it") }.getOrNull() ?: return@withContext emptyList()
        val id = own ?: list ?: record.url.hashCode().toString()
        val songs = if (id.startsWith("OLAK5uy_") || "/browse/" in record.url) tracks.mapIndexed { i, t ->
            trackSong(t, album = record.title, albumId = "ya$id", albumArtist = record.artist, number = i + 1, art = record.art)
        } else tracks.map { trackSong(it) }
        android.util.Log.d("ink-youtube", "album ${record.title}: ${songs.size} songs in ${System.currentTimeMillis() - t0} ms")
        songs.also { if (it.isNotEmpty()) albums[record.url] = it }
    }

    /** A song as YouTube Music's own pages list it; on a record's page, the record's words and cover. */
    private fun trackSong(t: InnerTube.Track, album: String? = null, albumId: String? = null, albumArtist: String? = null, number: Int = 0, art: String? = null): Song {
        val artist = t.artists.joinToString(", ").ifEmpty { albumArtist ?: "unknown" }
        return Song(
            id = "y${t.videoId}", uri = Uri.parse("ytm://${t.videoId}"), title = t.title, artist = artist,
            album = album ?: t.album.orEmpty(), albumId = albumId ?: t.albumId?.let { "ya$it" } ?: "y${t.videoId}",
            albumArtist = albumArtist ?: t.artists.firstOrNull() ?: artist, duration = t.seconds * 1000L, disc = 1, track = number,
            year = 0, art = t.art ?: art, format = "youtube",
        ).also(::remember)
    }

    /**
     * A song's audio stream (blocking: the player's loader, or a speaker's thread): its link and
     * type. For a speaker the m4a (every speaker plays AAC), else the best there is.
     */
    fun stream(id: String, forSpeaker: Boolean, small: Boolean = false): Pair<String, String>? {
        val key = id + (if (forSpeaker) ":s" else "") + (if (small) ":small" else "")
        streams[key]?.takeIf { it.third > System.currentTimeMillis() }?.let { return it.first to it.second }
        init()
        // signed in: the stream YouTube Music's web player gets as you (Premium: its 256k AAC; your uploads),
        // else, or if that fails, the one NewPipe finds signed out. A speaker always gets NewPipe's (it fetches the
        // link itself, from its own address)
        if (SIGNED && !forSpeaker && id !in refusedSongs && refusedSongs.size < 3) app?.let { YouTubeAccount.signed(it) }?.let { t ->
            signedStream(t, id, small)?.let { (url, mime, kbps) ->
                rates[key] = kbps
                streams[key] = Triple(url, mime, System.currentTimeMillis() + 4 * 3_600_000L)
                return url to mime
            }
        }
        val t0 = System.currentTimeMillis()
        // without the videos YouTube sets beside it (a half-megabyte, two thirds of the wait): the stream only
        Fetch.lean.set(true)
        val info = try { runCatching { StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=" + id.removePrefix("y")) }.getOrNull() } finally { Fetch.lean.set(false) }
            ?: return null
        android.util.Log.d("ink-youtube", "stream $id in ${System.currentTimeMillis() - t0} ms")
        val direct = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        // the best there is, or (data saver, Quality) the smallest
        val pick = (if (forSpeaker) direct.filter { it.format == MediaFormat.M4A }.ifEmpty { direct } else direct)
            .let { all -> val rate = { a: org.schabi.newpipe.extractor.stream.AudioStream -> a.averageBitrate.takeIf { b -> b > 0 } ?: a.bitrate }
                if (small) all.minByOrNull(rate) else all.maxByOrNull(rate) } ?: return null
        val mime = pick.format?.mimeType ?: "audio/mp4"
        // its bitrate, for the player to say (NewPipe gives kbps or bps, by the stream)
        (pick.averageBitrate.takeIf { it > 0 } ?: pick.bitrate).let { b -> rates[key] = if (b > 10_000) b / 1000 else b }
        streams[key] = Triple(pick.content, mime, System.currentTimeMillis() + 4 * 3_600_000L)
        return pick.content to mime
    }

    /**
     * Whether songs are asked for signed in: YouTube's web player scrambles each stream's signature and speed-limit
     * parameter, and NewPipe (0.26.5, and its dev branch) can no longer work them out of today's player code, so
     * yt-dlp's solver does ([Solver]).
     */
    private const val SIGNED = true

    private val rates = ConcurrentHashMap<String, Int>()
    // the signed-in account's id and its page's player (asked once a run), and that player's code and date
    @Volatile private var account: String? = null
    @Volatile private var playerUrl: String? = null
    @Volatile private var playerSts: Int? = null

    /** A player's code and date: kept on the phone a version (a few megabytes, asked once, not each start). */
    private fun keptPlayer(c: android.content.Context, url: String, t: InnerTube): Pair<String, Int?>? {
        val f = java.io.File(java.io.File(c.cacheDir, "player"), url.hashCode().toUInt().toString() + ".js")
        runCatching { f.readText() }.getOrNull()?.takeIf { it.length > 100_000 }?.let { code ->
            return code to Regex("(?:signatureTimestamp|sts):(\\d{5})").find(code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return t.playerCode(url)?.also { (code, _) ->
            runCatching { f.parentFile?.listFiles()?.forEach { it.delete() }; f.parentFile?.mkdirs(); f.writeText(code) }   // only the one in use kept
        }
    }

    /**
     * A song's audio as YouTube Music's web player gets it signed in (blocking): the player asked as you, with the
     * song's poToken and the player's date; the best audio it offers (or the smallest); its signature and its
     * speed-limit parameter worked out from YouTube's own player by yt-dlp's solver ([Solver]); the streaming
     * poToken on the end. Its link, type and kbps; null if anything's missing (then it's NewPipe's, signed out).
     */
    private fun signedStream(t: InnerTube, id: String, small: Boolean): Triple<String, String, Int>? = runCatching {
        val c = app ?: return null
        val vid = id.removePrefix("y")
        val t0 = System.currentTimeMillis()
        if (account == null || playerUrl == null) {
            val (sync, js) = t.page()
            account = sync; playerUrl = js
            if (js != null) keptPlayer(c, js, t)?.let { (code, sts) -> Solver.keep(js, code); playerSts = sts }
        }
        val sync = account ?: return null.also { android.util.Log.d("ink-youtube", "signed $vid: no account id") }
        val js = playerUrl ?: return null.also { android.util.Log.d("ink-youtube", "signed $vid: no player") }
        val tokens = PoTokens.signed(vid, sync)
        val answer = t.player(vid, playerSts, tokens?.first)
        val status = answer.optJSONObject("playabilityStatus")
        if (status?.optString("status") != "OK") {
            android.util.Log.d("ink-youtube", "signed $vid: ${status?.optString("status")} ${status?.optString("reason")}"); return null
        }
        val formats = answer.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
        val audio = (0 until formats.length()).map { formats.getJSONObject(it) }.filter { it.optString("mimeType").startsWith("audio/") }
        val rate = { f: org.json.JSONObject -> f.optInt("averageBitrate").takeIf { it > 0 } ?: f.optInt("bitrate") }
        val pick = (if (small) audio.minByOrNull(rate) else audio.maxByOrNull(rate)) ?: return null
        // the link, its scrambled signature (if it came as a cipher) and its scrambled n
        val cipher = pick.optString("signatureCipher").takeIf { it.isNotEmpty() }?.split('&')?.associate { kv ->
            kv.substringBefore('=') to java.net.URLDecoder.decode(kv.substringAfter('='), "UTF-8")
        }
        var url = cipher?.get("url") ?: pick.optString("url").ifEmpty { return null }
        val n = Regex("[?&]n=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val (sig, nAnswer) = Solver.solve(c, js, cipher?.get("s"), n)
            ?: return null.also { android.util.Log.d("ink-youtube", "signed $vid: the solver couldn't") }
        if (cipher != null) url += "&" + (cipher["sp"] ?: "signature") + "=" + java.net.URLEncoder.encode(sig ?: return null, "UTF-8")
        if (n != null && nAnswer != null) url = url.replace("n=" + java.net.URLEncoder.encode(n, "UTF-8"), "n=" + java.net.URLEncoder.encode(nAnswer, "UTF-8"))
            .let { if (it == url) url.replace("n=$n", "n=$nAnswer") else it }
        tokens?.second?.let { url += "&pot=" + java.net.URLEncoder.encode(it, "UTF-8") }
        // tried before it's trusted: a link with a step half-done (no poToken minted, the signature or n not
        // solved on that phone's WebView) is turned away (403), and played nothing for testers on 1.4.5; turned
        // away, the stream NewPipe finds signed out plays instead
        if (!opens(url)) {
            android.util.Log.d("ink-youtube", "signed $vid: turned away (${if (tokens != null) "with" else "no"} poToken, n ${if (nAnswer != null) "solved" else "none"})")
            return null
        }
        val kbps = rate(pick) / 1000
        android.util.Log.d("ink-youtube", "signed $vid: itag ${pick.optInt("itag")} ${pick.optString("mimeType").substringBefore(';')} $kbps kbps " +
            "(${if (tokens != null) "with" else "no"} poToken, ${if (cipher != null) "cipher" else "plain"}, n ${if (nAnswer != null) "solved" else "none"}) in ${System.currentTimeMillis() - t0} ms")
        Triple(url, pick.optString("mimeType").substringBefore(';'), kbps)
    }.onFailure { android.util.Log.d("ink-youtube", "signed $id: $it") }.getOrNull()

    /** A song's stream failed as it played: its links forgotten, and the next one NewPipe's, signed out. */
    fun refused(id: String) {
        streams.keys.removeAll { it.startsWith(id) }
        if (refusedSongs.add(id)) android.util.Log.d("ink-youtube", "refused $id: signed out from here (${refusedSongs.size})")
    }

    /** Whether a stream's link gives its first bytes (200 or 206). */
    private fun opens(url: String): Boolean = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 6_000; c.readTimeout = 6_000
            c.setRequestProperty("Range", "bytes=0-1")
            c.responseCode in 200..206
        } finally { c.disconnect() }
    }.getOrDefault(false)

    /** The bitrate (kbps) of the stream last picked for a song, 0 if none. */
    fun kbps(id: String, small: Boolean): Int = rates[id + if (small) ":small" else ""] ?: 0

    private fun keepRelated(id: String, info: StreamInfo) {
        // songs only (a "- Topic" upload is a song; the rest is video recommendations), not mixes or live streams
        related[id] = info.relatedItems.filterIsInstance<StreamInfoItem>()
            .filter { it.duration in 60..600 && it.uploaderName.orEmpty().endsWith(" - Topic") }.map { song(it) }
    }

    /**
     * Songs like this one (blocking): YouTube Music's radio for it (its mix, "RDAMVM<id>"; signed in, the one it
     * makes for you), else the songs among those YouTube sets beside it. Read once.
     */
    fun related(id: String): List<Song> {
        mixes[id]?.let { return it }
        init()
        val vid = id.removePrefix("y")
        // signed in: the radio YouTube makes for you from this song (what you listen to shapes it)
        app?.let { YouTubeAccount.signed(it) }?.let { t ->
            runCatching { t.queue("RDAMVM$vid", videoId = vid).filter { it.videoId != vid && (it.seconds == 0 || it.seconds in 60..900) }.map { trackSong(it) } }
                .onFailure { android.util.Log.d("ink-radio", "your radio for $id: $it") }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { android.util.Log.d("ink-radio", "your radio for $id: ${it.size}"); return it.also { s -> mixes[id] = s } }
        }
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

    /** An artist by name, with their photo and page id (blocking; kept). */
    fun artist(name: String): TubeArtist? {
        artists[name.lowercase()]?.let { return it }
        init()
        val found = runCatching {
            SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(name, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")).relatedItems
                .filterIsInstance<ChannelInfoItem>().firstOrNull()
        }.getOrNull() ?: return null
        // their page's id ("UC..."), from the channel's link
        val id = Regex("/channel/(UC[A-Za-z0-9_-]+)").find(found.url)?.groupValues?.get(1).orEmpty()
        return TubeArtist(found.name, best(found.thumbnails), id).also { artists[name.lowercase()] = it }
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

    /**
     * A song's timed lyrics from YouTube Music (blocking): a YouTube song's own, else the song found by its words.
     * Empty for none; null when YouTube couldn't be asked.
     */
    fun lyrics(id: String, title: String, artist: String): List<Line>? = runCatching {
        val vid = if (id.startsWith("y") && !id.startsWith("yface")) id.removePrefix("y") else find(title, artist)?.id?.removePrefix("y") ?: return emptyList()
        // "♪" is YouTube's mark for music without words: a gap, as lrclib's blank line
        InnerTube().lyrics(vid).map { (at, words) -> Line(at, if (words.trim() == "♪") "" else words) }
    }.onFailure { android.util.Log.d("ink-youtube", "lyrics $title: $it") }.getOrNull()

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
        /** On this thread, a stream's "next" (the videos beside it, NewPipe asks for them always) answered empty. */
        val lean = ThreadLocal.withInitial { false }
        private val EMPTY = """{"responseContext":{},"contents":{},"lean":"${" ".repeat(64)}"}"""   // NewPipe refuses a short answer

        override fun execute(request: Request): Response {
            if (lean.get() == true && request.url().contains("/youtubei/v1/next")) return Response(200, "OK", emptyMap(), EMPTY, request.url())
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.requestMethod = request.httpMethod()
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UA)
            request.headers().forEach { (k, vs) -> vs.forEachIndexed { i, v -> if (i == 0) c.setRequestProperty(k, v) else c.addRequestProperty(k, v) } }
            request.dataToSend()?.let { data -> c.doOutput = true; c.outputStream.use { it.write(data) } }
            val t0 = System.nanoTime()
            try {
                val code = c.responseCode
                if (code == 429) throw ReCaptchaException("rate limited", request.url())
                val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }
                android.util.Log.d("ink-youtube", "newpipe ${(System.nanoTime() - t0) / 1_000_000} ms, ${(body?.length ?: 0) / 1024} kB ${request.url().substringBefore('?').takeLast(40)}")
                return Response(code, c.responseMessage, c.headerFields.filterKeys { it != null }, body, c.url.toString())
            } finally { c.disconnect() }
        }
    }
}
