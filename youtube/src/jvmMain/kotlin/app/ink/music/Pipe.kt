package app.ink.music

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.HttpURLConnection
import java.net.URL

/**
 * NewPipe's extractor (GPL-3.0) as ink music uses it, the same on the phone and the desktop: how it reaches
 * YouTube, a song's words as a person would say them, the picture to show, the stream to play. Each app makes
 * its own songs from what this finds (the phone's YouTubeMusic, the desktop's YouTube).
 */
object Pipe {
    /** Where its timings go (the phone's log; nowhere on the desktop unless asked). */
    @Volatile var log: (String) -> Unit = {}
    @Volatile private var ready = false

    fun init() {
        if (ready) return
        synchronized(this) { if (!ready) { NewPipe.init(Fetch, Localization("en", "US")); ready = true } }
    }

    /** Told why a song's stream couldn't be read (the computer's log): it had been dropped without a word. */
    @Volatile var failed: ((vid: String, why: Throwable) -> Unit)? = null

    /**
     * A song's stream, read without the videos YouTube sets beside it (a half-megabyte, two thirds of the wait). Each of
     * its asks cut off at [Fetch.STREAM_DEADLINE], and the whole tried once more if it fails (2026-11-25, a tester's
     * songs taking 15-30 s to start on five phones, or never: one ask that stalls, on a connection the far end had
     * quietly dropped, had held the song till the 20 s read timeout; a second try, on a new connection, comes at once,
     * as BitChord measured).
     */
    fun streamInfo(vid: String): StreamInfo? {
        init()
        Fetch.lean.set(true); Fetch.deadline.set(Fetch.STREAM_DEADLINE)
        return try {
            var last: Throwable? = null
            repeat(2) { attempt ->
                val t0 = System.currentTimeMillis()
                runCatching { StreamInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, "https://www.youtube.com/watch?v=$vid") }
                    .onSuccess { return it }
                    .onFailure { e -> last = e; log("newpipe $vid: try ${attempt + 1} failed in ${System.currentTimeMillis() - t0} ms: ${e.javaClass.simpleName}") }
            }
            last?.let { e -> runCatching { failed?.invoke(vid, e) } }
            null
        }
        finally { Fetch.lean.set(false); Fetch.deadline.set(0L) }
    }

    /**
     * The audio to play: its link, type and kbps. For a speaker the m4a (every speaker plays AAC), else the best
     * there is, or (`small`: data saver) the smallest.
     */
    fun audio(info: StreamInfo, forSpeaker: Boolean, small: Boolean): Triple<String, String, Int>? {
        val direct = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val rate = { a: AudioStream -> a.averageBitrate.takeIf { b -> b > 0 } ?: a.bitrate }
        val pick = (if (forSpeaker) direct.filter { it.format == MediaFormat.M4A }.ifEmpty { direct } else direct)
            .let { all -> if (small) all.minByOrNull(rate) else all.maxByOrNull(rate) } ?: return null
        // NewPipe gives kbps or bps, by the stream
        val b = rate(pick)
        return Triple(pick.content, pick.format?.mimeType ?: "audio/mp4", if (b > 10_000) b / 1000 else b)
    }

    /**
     * A music video to watch: the picture (H.264, every computer decodes it; the tallest up to [high]) and its sound
     * apart, as YouTube keeps them past 360p; else the one stream with both (null sound). `smallSound`: its smallest
     * sound (TUNER's clips, which start sooner for it).
     */
    fun video(info: StreamInfo, high: Int = 720, smallSound: Boolean = false): Pair<String, String?>? {
        val direct = { s: org.schabi.newpipe.extractor.stream.Stream -> s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val pictures = info.videoOnlyStreams.filter { direct(it) && it.format == MediaFormat.MPEG_4 && it.height in 1..high }
        val sound = audio(info, forSpeaker = true, small = smallSound)?.first
        pictures.maxByOrNull { it.height }?.let { if (sound != null) return it.content to sound }
        return info.videoStreams.filter { direct(it) }.maxByOrNull { it.height }?.let { it.content to null }
    }

    /** A video's id from its link. */
    fun videoId(url: String) = Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1) ?: url.substringAfterLast('/')

    /**
     * Whether a song found is the one asked for: its first artist the same (letters only, either holding the other:
     * "Boy Romeo" and "boy romeo, x") and its title too, a version in brackets aside. A search's first answer alone
     * was taken before, and a song's radio ran from someone else's song of that name.
     */
    fun same(foundTitle: String, foundArtist: String, title: String, artist: String): Boolean {
        fun plain(s: String) = s.lowercase().replace(Regex("""\s*[(\[].*?[)\]]"""), "").filter { it.isLetterOrDigit() }
        val a = plain(artist.substringBefore(",")); val fa = plain(foundArtist.substringBefore(","))
        val t = plain(title); val ft = plain(foundTitle)
        return a.isNotEmpty() && fa.isNotEmpty() && (fa.contains(a) || a.contains(fa)) && t.isNotEmpty() && (ft.contains(t) || t.contains(ft)) &&
            !otherRecording(foundTitle, title)
    }

    /**
     * Another recording of the song under its name: a live take, a remix, an instrumental, a karaoke or tribute
     * catalogue's copy, a clean edit, when the one asked for isn't one (or the other way round). The brackets stripped to
     * compare titles took that with them: "Otherside" was "Otherside - Live" (2026-11-16, after Mornye-Python's
     * recording guard, MIT, mornye/core/text_match.py: the words, and asking it before the titles are compared).
     */
    fun otherRecording(foundTitle: String, title: String): Boolean =
        RECORDING.containsMatchIn(foundTitle) != RECORDING.containsMatchIn(title)

    private val RECORDING = Regex("""\b(live|remix|mix|edit|instrumental|acoustic|demo|karaoke|a cappella|acapella|reprise|radio edit|extended|""" +
        """backing track|sing[- ]?along|tribute|made famous by|originally performed by|in the style of|clean version|clean edit|censored|""" +
        """sped ?up|slowed|nightcore)\b""", RegexOption.IGNORE_CASE)

    /**
     * A song's words as a person would say them. A song's own upload ("X - Topic") is already
     * clean; a video's ("Shakira, Burna Boy - Dai Dai (Official Video)", by "Shakira and 2 more")
     * loses its "Official Video", its "| ..." tail, and gives up the artists it starts with.
     */
    fun tidy(name: String, uploader: String): Pair<String, String> {
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
    fun best(images: List<Image>?): String? =
        images.orEmpty().maxByOrNull { it.width * it.height }?.url?.replace(Regex("=w\\d+-h\\d+"), "=w600-h600")

    /** How NewPipe reaches YouTube: plain HTTP, as a desktop browser. */
    object Fetch : Downloader() {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        /** On this thread, a stream's "next" (the videos beside it, NewPipe asks for them always) answered empty. */
        val lean: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        private val EMPTY = """{"responseContext":{},"contents":{},"lean":"${" ".repeat(64)}"}"""   // NewPipe refuses a short answer

        /** On this thread, each ask cut off this many ms after it begins (0: only the timeouts). */
        val deadline: ThreadLocal<Long> = ThreadLocal.withInitial { 0L }
        /**
         * A song's stream's asks: a healthy one lands in well under a second, the player's code (a few megabytes, once a
         * version) in two or three on a phone; past this it's a stalled connection, and a new try is quicker than waiting.
         */
        const val STREAM_DEADLINE = 8_000L
        // cuts a connection at its deadline (a read timeout is per read: an answer that trickles never ends)
        private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ink-pipe-deadline").apply { isDaemon = true } }

        override fun execute(request: Request): Response {
            if (lean.get() == true && request.url().contains("/youtubei/v1/next")) return Response(200, "OK", emptyMap(), EMPTY, request.url())
            // just your music (the phone's): YouTube never asked
            if (HeldBack.held(request.url())) throw java.io.IOException("held back")
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.requestMethod = request.httpMethod()
            // the player's code given longer: a few megabytes, on a slow line, and every song waits on it till it's read once
            val limit = deadline.get().let { if (it > 0 && request.url().substringBefore('?').endsWith(".js")) it * 3 else it }
            if (limit > 0) { c.connectTimeout = 5_000; c.readTimeout = 5_000 } else { c.connectTimeout = 15_000; c.readTimeout = 20_000 }
            c.setRequestProperty("User-Agent", UA)
            request.headers().forEach { (k, vs) -> vs.forEachIndexed { i, v -> if (i == 0) c.setRequestProperty(k, v) else c.addRequestProperty(k, v) } }
            val cut = if (limit > 0) watchdog.schedule({ c.disconnect() }, limit, java.util.concurrent.TimeUnit.MILLISECONDS) else null
            val t0 = System.nanoTime()
            try {
                request.dataToSend()?.let { data -> c.doOutput = true; c.outputStream.use { it.write(data) } }
                val code = c.responseCode
                if (code == 429) throw ReCaptchaException("rate limited", request.url())
                val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }
                log("newpipe ${(System.nanoTime() - t0) / 1_000_000} ms, ${(body?.length ?: 0) / 1024} kB ${request.url().substringBefore('?').takeLast(40)}")
                return Response(code, c.responseMessage, c.headerFields.filterKeys { it != null }, body, c.url.toString())
            } catch (e: java.io.IOException) {
                if (cut != null && cut.isDone) throw java.io.IOException("cut off after $limit ms: ${request.url().substringBefore('?').takeLast(40)}", e)
                throw e
            } finally { cut?.cancel(false); c.disconnect() }
        }
    }
}
