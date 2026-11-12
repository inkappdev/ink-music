package app.ink.music

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
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

    // NewPipe as both apps use it (the youtube module's Pipe), its timings in the phone's log
    private fun init() { Pipe.log = { android.util.Log.d("ink-youtube", it) }; Pipe.init() }

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

    /** Songs and records by these words, and for find (`all`) artists and playlists too, all asked at once. */
    suspend fun search(q: String, all: Boolean = false): TubeFound = withContext(Dispatchers.IO) {
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
                    .filterIsInstance<PlaylistInfoItem>().map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), Pipe.best(it.thumbnails)) }
            }.getOrDefault(emptyList())
        }
        // and for find, who (artists, their faces and pages) and playlists by those words
        val artists = async {
            if (!all) return@async emptyList()
            runCatching {
                SearchInfo.getInfo(yt, f.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")).relatedItems
                    .filterIsInstance<org.schabi.newpipe.extractor.channel.ChannelInfoItem>().map {
                        TubeArtist(it.name, Pipe.best(it.thumbnails), Regex("/channel/(UC[A-Za-z0-9_-]+)").find(it.url)?.groupValues?.get(1).orEmpty())
                    }
            }.getOrDefault(emptyList())
        }
        val lists = async {
            if (!all) return@async emptyList()
            runCatching {
                SearchInfo.getInfo(yt, f.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS), "")).relatedItems
                    .filterIsInstance<PlaylistInfoItem>().map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), Pipe.best(it.thumbnails)) }
            }.getOrDefault(emptyList())
        }
        TubeFound(songs.await().take(20), albums.await().take(12), artists.await().take(8), lists.await().take(12))
            .also { android.util.Log.d("ink-youtube", "search \"$q\": ${it.songs.size} songs, ${it.albums.size} records, ${it.artists.size} artists, ${it.lists.size} playlists in ${System.currentTimeMillis() - t0} ms") }
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
    /** YouTube's own loudness for a song, as a ReplayGain gain (dB), where its signed-in answer said it. */
    val loudness = java.util.concurrent.ConcurrentHashMap<String, Float>()

    fun stream(asked: String, forSpeaker: Boolean, small: Boolean = false): Pair<String, String>? {
        // the version chosen by hand for this song, if one is (Versions)
        val id = app?.let { Versions.of(it, asked) }?.let { "y$it" } ?: asked
        val key = id + (if (forSpeaker) ":s" else "") + (if (small) ":small" else "")
        streams[key]?.takeIf { it.third > System.currentTimeMillis() }?.let { return it.first to it.second }
        // asked again while it's being found (the player's loader and the one reading ahead, at once): that answer
        // waited for, not found twice (2026-11-11: the log had a song found twice in the same moment, on two threads)
        val lock = finding.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            try {
                streams[key]?.takeIf { it.third > System.currentTimeMillis() }?.let { return@synchronized it.first to it.second }
                find(id, key, forSpeaker, small)
            } finally { finding.remove(key, lock) }
        }
    }
    private val finding = ConcurrentHashMap<String, Any>()
    // the two ways a song is found, run side by side when the first is slow
    private val race = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "ink-find").apply { isDaemon = true } }

    /**
     * A song's stream found. Signed in: the stream YouTube Music's web player gets as you (Premium: its 256k AAC; your
     * uploads); if it hasn't answered in 2 s, NewPipe's (signed out) asked alongside, and whichever answers first plays
     * (2026-11-11: a slow moment for YouTube shouldn't be a silent one here); if it fails, NewPipe's. A speaker always
     * gets NewPipe's (it fetches the link itself, from its own address).
     */
    private fun find(id: String, key: String, forSpeaker: Boolean, small: Boolean): Pair<String, String>? {
        init()
        val t0 = System.currentTimeMillis()
        val pipe = java.util.concurrent.Callable { pipeStream(id, forSpeaker, small) }
        val signedOk = SIGNED && !forSpeaker && id !in refusedSongs && refusedSongs.size < 3
        val t = if (signedOk) app?.let { YouTubeAccount.signed(it) } else null
        val got: Triple<String, String, Int>? = if (t == null) pipe.call() else {
            val done = java.util.concurrent.ExecutorCompletionService<Triple<String, String, Int>?>(race)
            done.submit { signedStream(t, id, small) }
            var running = 1
            var first = runCatching { done.poll(2_000, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                ?.let { running--; runCatching { it.get() }.getOrNull() }
            // slow, or it failed: NewPipe's asked (alongside, if the signed one's still going)
            if (first == null) {
                if (running == 1) android.util.Log.d("ink-youtube", "find $id: signed slow after ${System.currentTimeMillis() - t0} ms, newpipe alongside")
                done.submit(pipe); running++
                while (first == null && running > 0) {
                    first = runCatching { done.poll(30, java.util.concurrent.TimeUnit.SECONDS)?.get() }.getOrNull(); running--
                }
            }
            first
        }
        val (url, mime, kbps) = got ?: return null
        // its bitrate, for the player to say
        rates[key] = kbps
        streams[key] = Triple(url, mime, System.currentTimeMillis() + 4 * 3_600_000L)
        return url to mime
    }

    /** NewPipe's stream, signed out (blocking). */
    private fun pipeStream(id: String, forSpeaker: Boolean, small: Boolean): Triple<String, String, Int>? {
        val t0 = System.currentTimeMillis()
        val info = Pipe.streamInfo(id.removePrefix("y")) ?: return null
        android.util.Log.d("ink-youtube", "stream $id in ${System.currentTimeMillis() - t0} ms")
        return Pipe.audio(info, forSpeaker, small)
    }

    /**
     * Everything the first song needs, made ready as the player starts (blocking; 2026-11-11: the first song after a
     * start took 1.8-2 s to find, against 0.4-0.7 after: YouTube Music's page asked, the player's code read, the solver
     * started and the player prepared in it, all as that song waited): done now, ahead of it.
     */
    fun warmUp(c: android.content.Context) = runCatching {
        attach(c)
        init()
        if (!SIGNED) return@runCatching
        val t = YouTubeAccount.signed(c) ?: return@runCatching
        val t0 = System.currentTimeMillis()
        val kept = ready(c, t)
        // the solver's player and the first token at once, each on its own (2026-11-11: the first token alone was 0.8 s
        // of the first song's wait)
        val solver = race.submit { playerUrl?.let { Solver.warm(c, it) } }
        val token = race.submit { account?.let { PoTokens.signed("dQw4w9WgXcQ", it) } }
        runCatching { solver.get(20, java.util.concurrent.TimeUnit.SECONDS); token.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        android.util.Log.d("ink-youtube", "warmed up in ${System.currentTimeMillis() - t0} ms${if (kept) " (the page as kept)" else ""}")
        // the page as kept: asked again behind it, in case YouTube's player has moved on since
        if (kept) refresh(c, t)
    }.onFailure { android.util.Log.d("ink-youtube", "warm up: $it") }

    private fun sessionFile(c: android.content.Context) = java.io.File(c.filesDir, "youtube-session")

    /**
     * The account's id and its page's player, asked once a run, the player's code kept for the solver. Kept on the phone
     * a day too (2026-11-11: asking for the page was 0.8 s of the first song's wait, and they seldom change), so a start
     * has them at once; true when they came from what was kept.
     */
    @Synchronized private fun ready(c: android.content.Context, t: InnerTube): Boolean {
        if (account != null && playerUrl != null) return false
        runCatching {
            val (at, sync, js) = sessionFile(c).readLines().let { Triple(it[0].toLong(), it[1], it[2]) }
            if (System.currentTimeMillis() - at < 86_400_000L && sync.isNotBlank() && js.startsWith("http")) {
                account = sync; playerUrl = js
                keptPlayer(c, js, t)?.let { (code, sts) -> Solver.keep(js, code); playerSts = sts }
                return true
            }
        }
        val (sync, js) = t.page()
        account = sync; playerUrl = js
        if (js != null) keptPlayer(c, js, t)?.let { (code, sts) -> Solver.keep(js, code); playerSts = sts }
        keepSession(c)
        return false
    }

    /** The page asked again (behind a start that used the kept one): a new player taken up and kept. */
    private fun refresh(c: android.content.Context, t: InnerTube) {
        val (sync, js) = t.page()
        if (sync == null || js == null) return
        synchronized(this) {
            if (js != playerUrl) keptPlayer(c, js, t)?.let { (code, sts) -> Solver.keep(js, code); playerSts = sts }
            account = sync; playerUrl = js
            keepSession(c)
        }
    }

    private fun keepSession(c: android.content.Context) = runCatching {
        val sync = account ?: return@runCatching; val js = playerUrl ?: return@runCatching
        sessionFile(c).writeText("${System.currentTimeMillis()}\n$sync\n$js")
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
        ready(c, t)
        val sync = account ?: return null.also { android.util.Log.d("ink-youtube", "signed $vid: no account id") }
        val js = playerUrl ?: return null.also { android.util.Log.d("ink-youtube", "signed $vid: no player") }
        val tA = System.currentTimeMillis()
        val tokens = PoTokens.signed(vid, sync)
        val tB = System.currentTimeMillis()
        val answer = t.player(vid, playerSts, tokens?.first)
        val tC = System.currentTimeMillis()
        val status = answer.optJSONObject("playabilityStatus")
        if (status?.optString("status") != "OK") {
            android.util.Log.d("ink-youtube", "signed $vid: ${status?.optString("status")} ${status?.optString("reason")}"); return null
        }
        // its loudness as YouTube has it (signed in, the answer's whole: playerConfig.audioConfig), as a ReplayGain
        // gain against -18 LUFS, for even volume (Levelling: YouTube's songs carry no tags; 2026-11-08)
        answer.optJSONObject("playerConfig")?.optJSONObject("audioConfig")?.let { a ->
            val lufs = if (a.has("perceptualLoudnessDb")) a.optDouble("perceptualLoudnessDb") else if (a.has("loudnessDb")) a.optDouble("loudnessDb") - 14 else Double.NaN
            if (!lufs.isNaN()) loudness[id] = (-18 - lufs).toFloat()
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
        val tD = System.currentTimeMillis()
        val (sig, nAnswer) = Solver.solve(c, js, cipher?.get("s"), n)
            ?: return null.also { android.util.Log.d("ink-youtube", "signed $vid: the solver couldn't") }
        if (cipher != null) url += "&" + (cipher["sp"] ?: "signature") + "=" + java.net.URLEncoder.encode(sig ?: return null, "UTF-8")
        if (n != null && nAnswer != null) url = url.replace("n=" + java.net.URLEncoder.encode(n, "UTF-8"), "n=" + java.net.URLEncoder.encode(nAnswer, "UTF-8"))
            .let { if (it == url) url.replace("n=$n", "n=$nAnswer") else it }
        tokens?.second?.let { url += "&pot=" + java.net.URLEncoder.encode(it, "UTF-8") }
        // tried before it's trusted: a link with a step half-done (no poToken minted, the signature or n not
        // solved on that phone's WebView) is turned away (403), and played nothing for testers on 1.4.5; turned
        // away, the stream NewPipe finds signed out plays instead
        val tE = System.currentTimeMillis()
        if (!opens(url)) {
            android.util.Log.d("ink-youtube", "signed $vid: turned away (${if (tokens != null) "with" else "no"} poToken, n ${if (nAnswer != null) "solved" else "none"})")
            return null
        }
        val kbps = rate(pick) / 1000
        android.util.Log.d("ink-youtube", "signed $vid: itag ${pick.optInt("itag")} ${pick.optString("mimeType").substringBefore(';')} $kbps kbps " +
            "(${if (tokens != null) "with" else "no"} poToken, ${if (cipher != null) "cipher" else "plain"}, n ${if (nAnswer != null) "solved" else "none"}) in ${System.currentTimeMillis() - t0} ms " +
            "(page ${tA - t0}, poToken ${tB - tA}, player ${tC - tB}, solver ${tE - tD}, checked ${System.currentTimeMillis() - tE})")
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
        return TubeArtist(found.name, Pipe.best(found.thumbnails), id).also { artists[name.lowercase()] = it }
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
            .map { TubeAlbum(it.url, it.name, it.uploaderName.orEmpty(), Pipe.best(it.thumbnails)) }
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

    /** YouTube Music's moods & moments and genres (blocking; signed out the same). */
    fun moods(): List<TubeMood> = InnerTube().moods().map { TubeMood(it.title, it.params, it.genre) }

    /** A mood's flavors: shelves of YouTube Music's playlists for it (blocking). */
    fun mood(params: String): List<Pair<String, List<TubeList>>> = InnerTube().mood(params).map { s ->
        s.title to s.items.filter { it.kind == InnerTube.Kind.PLAYLIST }.map { TubeList(it.id.removePrefix("VL"), it.title, it.subtitle, it.art) }
    }.filter { it.second.isNotEmpty() }

    /** A station from a mood: the first playlist of each of its first flavors, their songs taken in turn (blocking). */
    fun moodStation(params: String): List<Song> {
        val lists = InnerTube().mood(params).take(4).mapNotNull { it.items.firstOrNull { i -> i.kind == InnerTube.Kind.PLAYLIST } }
        val each = lists.map { runCatching { InnerTube().tracks(it.id, 30).map { t -> trackSong(t) }.shuffled() }.getOrDefault(emptyList()) }
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }.take(80)
    }

    /** A genre's editors' lists, as picks: the first list of each of its first three shelves, 20 songs each, taken in turn (blocking). */
    fun editorsSongs(params: String): List<Song> {
        val lists = InnerTube().mood(params).take(3).mapNotNull { it.items.firstOrNull { i -> i.kind == InnerTube.Kind.PLAYLIST } }
        val each = lists.map { runCatching { InnerTube().tracks(it.id, 20).map { t -> trackSong(t) } }.getOrDefault(emptyList()) }
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }
    }

    /** A record someone picked, found on YouTube Music: its name and artist matched; null when it hasn't it. */
    suspend fun findRecord(title: String, artist: String): TubeAlbum? =
        search("$artist $title").albums.firstOrNull { Pipe.same(it.title, it.artist, title, artist) }

    /** YouTube Music's song by these words, if it has one (blocking). */
    fun find(title: String, artist: String): Song? {
        // a version chosen by hand for these words (Versions): that one
        app?.let { Versions.byWords(it, title, artist) }?.let { v ->
            return Song(id = "y$v", uri = Uri.parse("ytm://$v"), title = title, artist = artist, album = "", albumId = "y$v", albumArtist = artist,
                duration = 0, disc = 1, track = 0, year = 0, format = "youtube").also(::remember)
        }
        init()
        return runCatching {
            SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery("$artist $title", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")).relatedItems
                .filterIsInstance<StreamInfoItem>().take(8).map { song(it) }
                .firstOrNull { Pipe.same(it.title, it.artist, title, artist) }
        }.getOrNull()
    }

    private fun song(
        it: StreamInfoItem, album: String = "", albumId: String? = null, albumArtist: String? = null, track: Int = 0, art: String? = null,
    ): Song {
        val vid = Pipe.videoId(it.url)
        val (title, artist) = Pipe.tidy(it.name.orEmpty(), it.uploaderName.orEmpty())
        return Song(
            id = "y$vid", uri = Uri.parse("ytm://$vid"), title = title.ifEmpty { "untitled" }, artist = artist.ifEmpty { "unknown" },
            album = album, albumId = albumId ?: "y$vid", albumArtist = albumArtist?.ifEmpty { null } ?: artist,
            duration = it.duration.coerceAtLeast(0) * 1000, disc = 1, track = track, year = 0,
            art = Pipe.best(it.thumbnails) ?: art, format = "youtube",
        ).also(::remember)
    }
}
