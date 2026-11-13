package app.ink.music

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * BitChord-compatible JSON addon protocol, with optional token-route request signing.
 * Addons are remote HTTP sources, never executable modules.
 */
object AddonMusic {
    private const val PREFS = "bitChordAddon"

    fun baseUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", "").orEmpty()

    fun secret(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("secret", "").orEmpty()

    fun configure(ctx: Context, rawUrl: String, hmacSecret: String) {
        val base = rawUrl.trim().trimEnd('/').removeSuffix("/manifest.json").trimEnd('/')
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", base).putString("secret", hmacSecret.trim()).apply()
    }

    suspend fun search(ctx: Context, query: String): List<Song> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val base = baseUrl(ctx)
            if (base.isBlank() || query.isBlank()) return@withContext emptyList()
            runCatching {
                val params = settings(ctx, base)
                params["q"] = query
                val result = request(ctx, "$base/search", params, intent = "stream")
                val tracks = tracksArray(result) ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until tracks.length()) {
                        val track = tracks.optJSONObject(i) ?: continue
                        val id = track.optString("id")
                        val title = track.optString("title")
                        if (id.isBlank() || title.isBlank()) continue
                        val artist = track.optString("artist")
                        val album = track.optString("album")
                        val streamUrl = playableUrl(track).orEmpty()
                        val payload = JSONObject().put("base", base).put("id", id).put("stream", streamUrl)
                        val encoded = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8),
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                        val uri = Uri.parse("addon://$encoded")
                        val duration = (track.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0)
                        add(Song(
                            id = "a$encoded", uri = uri, title = title, artist = artist, album = album,
                            albumId = "", albumArtist = artist, duration = duration, disc = 1,
                            track = 0, year = 0, art = track.optString("artworkURL").takeIf { it.startsWith("http") }
                                ?: track.optString("albumArtworkURL").takeIf { it.startsWith("http") },
                            format = track.optString("format"),
                        ))
                    }
                }
            }.getOrDefault(emptyList())
        }

    /**
     * Find an exact recording in the configured addon and return its playable URL.
     * Called by the player's resolving data source on its IO loader thread.
     */
    fun findStream(ctx: Context, title: String, artist: String, album: String, durationSeconds: Long): String? {
        val base = baseUrl(ctx)
        if (base.isBlank() || title.isBlank() || artist.isBlank()) return null
        return runCatching {
            val params = settings(ctx, base)
            val searchParams = LinkedHashMap(params).apply {
                put("q", listOf(title, artist, album).filter { it.isNotBlank() }.joinToString(" "))
            }
            val response = request(ctx, "$base/search", searchParams, intent = "stream")
            val tracks = tracksArray(response) ?: return@runCatching null
            fun norm(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "").lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            val wantedTitle = norm(title)
            val wantedArtist = norm(artist.substringBefore(',').trim())
            val candidates = (0 until tracks.length()).mapNotNull { i ->
                val track = tracks.optJSONObject(i) ?: return@mapNotNull null
                if (track.optString("id").isBlank()) return@mapNotNull null
                if (norm(track.optString("title")) != wantedTitle) return@mapNotNull null
                val foundArtist = norm(track.optString("artist"))
                if (foundArtist.isNotEmpty() && foundArtist != wantedArtist &&
                    !foundArtist.contains(wantedArtist) && !wantedArtist.contains(foundArtist)) return@mapNotNull null
                val seconds = track.optDouble("duration", 0.0).toLong()
                val difference = if (durationSeconds > 0 && seconds > 0) kotlin.math.abs(seconds - durationSeconds) else 0L
                if (difference > 12) return@mapNotNull null
                val foundAlbum = norm(track.optString("album"))
                val wantedAlbum = norm(album)
                val albumPenalty = if (wantedAlbum.isBlank() || foundAlbum.isBlank() || foundAlbum == wantedAlbum) 0 else 1
                Triple(track, difference, (if (foundArtist == wantedArtist) 0 else 2) + albumPenalty)
            }.sortedWith(compareBy<Triple<JSONObject, Long, Int>> { it.second }.thenBy { it.third })
            val track = candidates.firstOrNull()?.first ?: return@runCatching null
            val direct = playableUrl(track)
            if (direct != null) return@runCatching AddonMedia.materialize(ctx, JSONObject().put("url", direct), base)
            val id = track.getString("id")
            // The search query belongs only on /search; stream endpoints receive quality/atmos only.
            val streamParams = LinkedHashMap(params).apply { remove("q") }
            val stream = request(ctx, "$base/stream/${Uri.encode(id)}", streamParams, intent = "stream")
            AddonMedia.materialize(ctx, stream, base)
        }.onFailure { android.util.Log.w("ink-addon", "lookup failed: ${it.message}") }.getOrNull()
    }

    /** Called by Playback when it rebuilds a queued addon item from Media3 metadata. */
    fun songFrom(item: MediaItem): Song? {
        val uri = item.localConfiguration?.uri?.takeIf { it.scheme == "addon" } ?: return null
        val md = item.mediaMetadata
        return runCatching {
            Song(
                id = item.mediaId, uri = uri,
                title = md.title?.toString().orEmpty(), artist = md.artist?.toString().orEmpty(),
                album = md.albumTitle?.toString().orEmpty(), albumId = "",
                albumArtist = md.artist?.toString().orEmpty(), duration = 0L,
                disc = 1, track = 0, year = 0,
                art = md.artworkUri?.toString(), format = "",
            )
        }.getOrNull()
    }

    /** Resolve the addon URI to its direct stream URL. Called by Media3's resolver on its IO loader. */
    fun stream(ctx: Context, encoded: String): String {
        val decoded = JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        val base = decoded.getString("base")
        val direct = decoded.optString("stream")
        if (direct.startsWith("http")) return AddonMedia.materialize(ctx, JSONObject().put("url", direct), base)
        val id = decoded.getString("id")
        val params = settings(ctx, base).apply { remove("q") }
        val result = request(ctx, "$base/stream/${Uri.encode(id)}", params, intent = "stream")
        return AddonMedia.materialize(ctx, result, base)
    }

    /** Parse LastWave nested wrappers without treating protocol defaults as transport. */
    internal fun playableUrl(response: JSONObject): String? {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JSONObject, Boolean>())
        fun find(value: JSONObject, depth: Int): String? {
            if (depth > 6 || !seen.add(value)) return null
            for (key in listOf("directUrl", "streamUrl", "audioUrl", "downloadUrl", "mediaUrl", "url", "dataUrl", "manifestUrl", "mpdUrl", "dashUrl", "hlsUrl")) {
                val raw = value.optString(key).trim()
                if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
            }
            for (key in listOf("stream", "data", "result", "audio", "media", "manifest", "dash", "hls")) {
                when (val nested = value.opt(key)) {
                    is JSONObject -> find(nested, depth + 1)?.let { return it }
                    is String -> nested.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { return it }
                }
            }
            return null
        }
        return find(response, 0)
    }

    /** BitChord/Lokal addons may return an array directly or wrap it in data/results/items. */
    private fun tracksArray(response: JSONObject): org.json.JSONArray? {
        response.optJSONArray("tracks")?.let { return it }
        for (key in listOf("data", "results", "items", "result")) {
            when (val nested = response.opt(key)) {
                is org.json.JSONArray -> return nested
                is JSONObject -> nested.optJSONArray("tracks")?.let { return it }
            }
        }
        return null
    }

    private fun settings(ctx: Context, base: String): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        runCatching {
            val manifest = request(ctx, "$base/manifest.json", emptyMap())
            val declared = manifest.optJSONArray("settings") ?: return@runCatching
            for (i in 0 until declared.length()) {
                val setting = declared.optJSONObject(i) ?: continue
                val key = setting.optString("key")
                val value = setting.opt("default")?.toString()?.takeIf { it != "null" }
                if (key.isNotBlank() && value != null) result[key] = value.trim('"')
            }
            val quality = declared.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("key") == "quality" }
            }
            val options = quality?.optJSONArray("options")
            val tier = when (Quality.now(ctx)) {
                Quality.SAVER -> "LOW"
                Quality.HIGH -> "HIGH"
                else -> "LOSSLESS"
            }
            val words = when (tier) {
                "LOSSLESS" -> listOf("lossless", "flac", "hifi", "hi-res", "hires", "max", "best")
                "LOW" -> listOf("low", "96", "128", "min")
                else -> listOf("high", "320", "normal", "standard")
            }
            val match = options?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.opt("value")?.toString()?.trim('"') }
                    .firstOrNull { it.equals(tier, true) || words.any { word -> word in it.lowercase() } }
            }
            result["quality"] = match ?: tier.lowercase()
        }.onFailure { result.putIfAbsent("quality", "LOSSLESS") }
        // Avoid spatial-audio variants unless the addon explicitly configures them.
        result.putIfAbsent("atmos", "none")
        result.putIfAbsent("quality", when (Quality.now(ctx)) {
            Quality.SAVER -> "low"; Quality.HIGH -> "high"; else -> "lossless"
        })
        return result
    }

    private fun request(ctx: Context, endpoint: String, params: Map<String, String>, intent: String? = null): JSONObject {
        val builder = Uri.parse(endpoint).buildUpon()
        params.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        val target = builder.build().toString()
        val isStreamRequest = intent != null || Uri.parse(target).path.orEmpty().contains("/stream/")
        var lastError: IOException? = null
        repeat(2) { attempt ->
            val connection = URL(target).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json, application/dash+xml, */*")
            connection.setRequestProperty("User-Agent", if (isStreamRequest) "LastWave-Player/1.0" else "Ink-Music/1.0")
            val signedIntent = intent ?: if (isStreamRequest) "stream" else null
            signedIntent?.let { connection.setRequestProperty("X-LW-Intent", it) }
            sign(ctx, target)?.let { (ts, signature) ->
                connection.setRequestProperty("X-LW-TS", ts)
                connection.setRequestProperty("X-LW-Sign", signature)
            }
            try {
                val code = connection.responseCode
                if (code in 502..503 && attempt == 0) {
                    lastError = IOException("Addon HTTP $code")
                    Thread.sleep(150)
                    return@repeat
                }
                if (code !in 200..299) {
                    val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    throw IOException("Addon HTTP $code" + (errorBody?.takeIf { it.isNotBlank() }?.let { ": ${it.take(240)}" } ?: ""))
                }
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                return when {
                    responseBody.startsWith("[") -> JSONObject().put("tracks", org.json.JSONArray(responseBody))
                    responseBody.startsWith("http://") || responseBody.startsWith("https://") -> JSONObject().put("url", responseBody)
                    responseBody.startsWith("<MPD") || responseBody.startsWith("<?xml") -> JSONObject().put("manifestXml", responseBody)
                    else -> JSONObject(responseBody)
                }
            } finally { connection.disconnect() }
        }
        throw lastError ?: IOException("Addon request failed")
    }

    /** Headers for the audio GET itself when its URL uses a token-bearing addon route. */
    fun signedHeaders(ctx: Context, url: String): Map<String, String> =
        sign(ctx, url)?.let { (ts, signature) -> mapOf("X-LW-TS" to ts, "X-LW-Sign" to signature, "X-LW-Intent" to "stream") }.orEmpty()

    internal fun signedHeadersForMedia(url: String, addonBase: String): Map<String, String> =
        signedHeadersForBase(url, addonBase)

    private fun signedHeadersForBase(url: String, addonBase: String): Map<String, String> =
        if (Uri.parse(url).host == Uri.parse(addonBase).host) emptyMap() else emptyMap()

    /** Optional route signature: timestamp, method, path and the /a/{token} path token. */
    private fun sign(ctx: Context, url: String): Pair<String, String>? {
        val key = secret(ctx)
        if (key.isBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path ?: "/"
        val token = Regex("""/a/([^/]+)""").find(path)?.groupValues?.get(1) ?: return null
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val message = "$timestamp\nGET\n$path\n$token"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return timestamp to signature
    }
}
