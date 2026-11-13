package app.ink.music

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.util.Xml
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.xmlpull.v1.XmlPullParser

/**
 * LastWave media materializer. Media3 receives a verified local file rather than
 * an expiring catalog URL or an MPD document. Downloads are bounded and atomic.
 */
internal object AddonMedia {
    private const val MAX_BYTES = 512L * 1024L * 1024L
    private const val MAX_MANIFEST = 2L * 1024L * 1024L
    private const val MAX_SEGMENTS = 512

    fun materialize(ctx: Context, response: org.json.JSONObject, addonBase: String): String {
        val hybrid = findObject(response, "hybrid")
        if (hybrid != null) return downloadHybrid(ctx, hybrid, addonBase)
        val direct = AddonMusic.playableUrl(response)
        if (direct != null && !direct.substringBefore('?').endsWith(".mpd", true)) {
            return download(ctx, direct, addonBase)
        }
        val inline = response.optString("manifestXml").takeIf { it.isNotBlank() }
            ?: response.optString("mpd").takeIf { it.trimStart().startsWith("<MPD") }
        val manifestUrl = direct?.takeIf { it.substringBefore('?').endsWith(".mpd", true) }
        val xml = inline ?: manifestUrl?.let { String(fetch(ctx, it, addonBase, MAX_MANIFEST), Charsets.UTF_8) }
            ?: throw IOException(response.optString("error").ifBlank { "Addon returned no playable media" })
        return downloadDash(ctx, xml, manifestUrl ?: addonBase, addonBase)
    }

    private fun findObject(root: org.json.JSONObject, key: String, depth: Int = 0): org.json.JSONObject? {
        if (depth > 6) return null
        root.optJSONObject(key)?.let { return it }
        for (name in listOf("data", "stream", "result", "audio", "media")) {
            root.optJSONObject(name)?.let { findObject(it, key, depth + 1)?.let { found -> return found } }
        }
        return null
    }

    /** Supports the unencrypted byte-range variant. Encrypted CENC plans fail closed. */
    private fun downloadHybrid(ctx: Context, plan: org.json.JSONObject, addonBase: String): String {
        if (plan.optBoolean("encrypted", false) || plan.has("key"))
            throw IOException("Encrypted hybrid CENC is not yet supported by this adapter")
        val url = plan.optString("cdnUrl")
        val init = plan.optJSONObject("initRange") ?: throw IOException("Hybrid init range missing")
        val ranges = plan.optJSONArray("ranges") ?: throw IOException("Hybrid ranges missing")
        if (url.isBlank() || ranges.length() == 0 || ranges.length() > MAX_SEGMENTS)
            throw IOException("Invalid hybrid media plan")
        fun pair(value: org.json.JSONObject): Pair<Long, Long> {
            val start = value.optLong("start", -1)
            val end = value.optLong("end", -1)
            if (start < 0 || end < start || end - start > 16L * 1024 * 1024)
                throw IOException("Invalid hybrid byte range")
            return start to end
        }
        val all = mutableListOf(pair(init))
        for (i in 0 until ranges.length()) all += pair(ranges.optJSONObject(i) ?: throw IOException("Invalid hybrid segment"))
        if (all.first().first != 0L || all.zipWithNext().any { (a,b) -> b.first <= a.second })
            throw IOException("Overlapping or unordered hybrid ranges")
        val output = java.io.ByteArrayOutputStream()
        for ((start, end) in all) {
            val bytes = fetchRange(ctx, url, addonBase, start, end)
            if (output.size().toLong() + bytes.size > MAX_BYTES) throw IOException("Hybrid media exceeds size limit")
            output.write(bytes)
        }
        val bytes = output.toByteArray()
        if (bytes.size < 12 || String(bytes, 4, 4, Charsets.US_ASCII) != "ftyp")
            throw IOException("Invalid hybrid MP4 media")
        return save(ctx, bytes, "m4a")
    }

    private fun fetchRange(ctx: Context, raw: String, addonBase: String, start: Long, end: Long): ByteArray {
        val uri = Uri.parse(raw)
        val host = uri.host ?: throw IOException("Unsafe hybrid CDN URL")
        val base = Uri.parse(addonBase)
        if (uri.scheme != "https" || uri.userInfo?.isNotEmpty() == true || uri.fragment != null ||
            (host != base.host && (!host.contains(".") || host.endsWith(".") ||
                listOf(".local", ".localhost", ".internal", ".lan", ".home").any(host::endsWith))))
            throw IOException("Disallowed hybrid CDN URL")
        val connection = URL(raw).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.setRequestProperty("Range", "bytes=$start-$end")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = connection.responseCode
            if (code != 206 && !(code == 200 && start == 0L))
                throw IOException("Hybrid CDN did not honor byte ranges (HTTP $code)")
            val expected = end - start + 1
            if (connection.contentLengthLong >= 0 && connection.contentLengthLong != expected)
                throw IOException("Incomplete hybrid byte range")
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size.toLong() != expected) throw IOException("Incomplete hybrid byte range")
            return bytes
        } finally { connection.disconnect() }
    }

    private fun download(ctx: Context, raw: String, addonBase: String): String {
        val bytes = fetch(ctx, raw, addonBase, MAX_BYTES)
        if (!looksLikeAudio(bytes)) throw IOException("Addon response is not a supported audio file")
        return save(ctx, bytes, extension(raw))
    }

    private fun downloadDash(ctx: Context, xml: String, manifestBase: String, addonBase: String): String {
        if (xml.length > MAX_MANIFEST || !xml.contains("<MPD")) throw IOException("Invalid DASH manifest")
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var adaptationAudio = false
        var representation = false
        var representationId = ""
        var template: Map<String, String>? = null
        var base = manifestBase
        val timeline = mutableListOf<Int>()
        var inTimeline = false
        var periods = 0
        var drm = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "Period" -> periods++
                    "ContentProtection" -> drm = true
                    "AdaptationSet" -> adaptationAudio =
                        parser.getAttributeValue(null, "contentType") == "audio" ||
                        parser.getAttributeValue(null, "mimeType")?.startsWith("audio/") == true
                    "Representation" -> if (adaptationAudio && !representation) { representation = true; representationId = parser.getAttributeValue(null, "id").orEmpty() }
                    "BaseURL" -> if (representation || adaptationAudio) {
                        val value = parser.nextText().trim()
                        if (value.isNotEmpty()) base = java.net.URI(base).resolve(value).toString()
                    }
                    "SegmentTemplate" -> if (representation || adaptationAudio) {
                        template = (0 until parser.attributeCount).associate {
                            parser.getAttributeName(it) to parser.getAttributeValue(it)
                        }
                    }
                    "SegmentTimeline" -> inTimeline = true
                    "S" -> if (inTimeline) {
                        val repeat = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                        val duration = parser.getAttributeValue(null, "d")?.toIntOrNull()
                            ?: throw IOException("Invalid DASH timeline")
                        if (repeat < 0 || repeat > MAX_SEGMENTS || duration <= 0 || timeline.size + repeat + 1 > MAX_SEGMENTS)
                            throw IOException("Unsupported DASH timeline")
                        repeatPlus(repeat + 1) { timeline.add(duration) }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "SegmentTimeline" -> inTimeline = false
                    "AdaptationSet" -> adaptationAudio = false
                }
            }
            event = parser.next()
        }
        if (drm) throw IOException("DRM-protected DASH is not supported by this adapter")
        if (periods != 1) throw IOException("Only single-period static DASH is supported")
        val t = template ?: throw IOException("DASH SegmentTemplate is missing")
        val init = t["initialization"] ?: throw IOException("DASH initialization is missing")
        val media = t["media"] ?: throw IOException("DASH media template is missing")
        if (!media.contains("$" + "Number") || timeline.isEmpty()) throw IOException("Unsupported DASH segment template")
        val repId = representationId
        fun expand(value: String, number: Int? = null): String {
            var result = value.replace("$" + "RepresentationID$", repId)
            val re = Regex("""\${'\$'}Number(?:%0(\d+)d)?\${'\$'}""")
            result = re.replace(result) { match ->
                val n = number ?: throw IOException("Invalid initialization template")
                n.toString().padStart(match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0, '0')
            }
            return java.net.URI(base).resolve(result).toString()
        }
        val start = t["startNumber"]?.toIntOrNull() ?: 1
        val pieces = mutableListOf<ByteArray>()
        pieces += fetch(ctx, expand(init), addonBase, 16L * 1024 * 1024)
        timeline.indices.forEach { i ->
            pieces += fetch(ctx, expand(media, start + i), addonBase, 16L * 1024 * 1024)
        }
        val total = pieces.sumOf { it.size.toLong() }
        if (total > MAX_BYTES) throw IOException("DASH media exceeds the size limit")
        val joined = ByteArray(total.toInt())
        var offset = 0
        pieces.forEach { it.copyInto(joined, offset); offset += it.size }
        if (joined.size < 12 || String(joined, 4, 4, Charsets.US_ASCII) != "ftyp") throw IOException("Invalid DASH MP4 initialization")
        return save(ctx, joined, "m4a")
    }

    private inline fun repeatPlus(count: Int, block: () -> Unit) { repeat(count) { block() } }

    private fun fetch(ctx: Context, raw: String, addonBase: String, max: Long): ByteArray {
        val uri = Uri.parse(raw)
        val host = uri.host ?: throw IOException("Unsafe addon media URL")
        if (uri.scheme != "https" || java.net.URI(raw).rawUserInfo != null || host.isBlank() ||
            uri.userInfo?.isNotEmpty() == true || uri.fragment != null) throw IOException("Unsafe addon media URL")
        val base = Uri.parse(addonBase)
        val ipLiteral = runCatching { java.net.InetAddress.getByName(host) }.getOrNull()
            ?.let { it.hostAddress == host || host.startsWith("[") } == true
        if (host != base.host && (ipLiteral || !host.contains(".") || host.endsWith(".") ||
            listOf(".local", ".localhost", ".internal", ".lan", ".home").any(host::endsWith)))
            throw IOException("Disallowed addon media host")
        val connection = URL(raw).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "audio/*,video/mp4,application/dash+xml,*/*")
        AddonMusic.signedHeaders(ctx, raw).forEach { (k,v) -> connection.setRequestProperty(k,v) }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Addon media HTTP $code")
            val declared = connection.contentLengthLong
            if (declared > max) throw IOException("Addon media exceeds size limit")
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > max) throw IOException("Addon media exceeds size limit")
                    output.write(buffer, 0, n)
                }
            }
            if (declared >= 0 && output.size().toLong() != declared) throw IOException("Incomplete addon media response")
            return output.toByteArray()
        } finally { connection.disconnect() }
    }

    private fun save(ctx: Context, bytes: ByteArray, ext: String): String {
        val dir = File(ctx.cacheDir, "lastwave-media").apply { mkdirs() }
        val target = File(dir, "lw-${java.util.UUID.randomUUID()}.$ext")
        val part = File(target.path + ".part")
        try {
            FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
            if (!part.renameTo(target)) throw IOException("Could not finalize addon media cache")
            // Verify the completed file with Android's demuxer before Media3 sees it.
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(target.absolutePath)
                val hasAudio = (0 until extractor.trackCount).any { index ->
                    extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                }
                if (!hasAudio) throw IOException("Addon file contains no decodable audio track")
            } catch (error: Exception) {
                target.delete()
                if (error is IOException) throw error
                throw IOException("Addon media failed audio validation", error)
            } finally { extractor.release() }
            return Uri.fromFile(target).toString()
        } finally { if (part.exists()) part.delete() }
    }

    private fun looksLikeAudio(b: ByteArray): Boolean =
        (b.size >= 4 && String(b, 0, 4, Charsets.US_ASCII) in setOf("fLaC", "OggS", "RIFF", "ID3")) ||
        (b.size >= 8 && String(b, 4, 4, Charsets.US_ASCII) == "ftyp") ||
        (b.size >= 4 && b[0] == 0x1a.toByte() && b[1] == 0x45.toByte() && b[2] == 0xdf.toByte() && b[3] == 0xa3.toByte()) ||
        (b.size >= 2 && b[0] == 0xff.toByte() && (b[1].toInt() and 0xe0) == 0xe0)

    private fun extension(url: String): String = when (Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "flac", "mp3", "m4a", "mp4", "ogg", "opus", "wav", "aac", "webm" ->
            Uri.parse(url).lastPathSegment!!.substringAfterLast('.').lowercase()
        else -> "audio"
    }
}