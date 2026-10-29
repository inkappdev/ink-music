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

    /** A song's stream, read without the videos YouTube sets beside it (a half-megabyte, two thirds of the wait). */
    fun streamInfo(vid: String): StreamInfo? {
        init()
        Fetch.lean.set(true)
        return try { runCatching { StreamInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, "https://www.youtube.com/watch?v=$vid") }.getOrNull() }
        finally { Fetch.lean.set(false) }
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
        return a.isNotEmpty() && fa.isNotEmpty() && (fa.contains(a) || a.contains(fa)) && t.isNotEmpty() && (ft.contains(t) || t.contains(ft))
    }

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
                log("newpipe ${(System.nanoTime() - t0) / 1_000_000} ms, ${(body?.length ?: 0) / 1024} kB ${request.url().substringBefore('?').takeLast(40)}")
                return Response(code, c.responseMessage, c.headerFields.filterKeys { it != null }, body, c.url.toString())
            } finally { c.disconnect() }
        }
    }
}
