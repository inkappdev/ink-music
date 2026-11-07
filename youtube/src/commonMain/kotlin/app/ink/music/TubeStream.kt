package app.ink.music

/**
 * What a system runs for YouTube's streams (2026-11-07, the iPhone): yt-dlp's solver for a stream's scrambled
 * signature and speed-limit parameter, and BotGuard for the poToken YouTube asks with each. The iPhone's are
 * JavaScriptCore and a hidden WKWebView; a test's, node.
 */
interface TubeJs {
    /** The answers to a stream's challenges (its signature `sig`, its `n`) from the player whose code is given; null if it couldn't. */
    fun solve(playerUrl: String, playerCode: String, sig: String?, n: String?): Pair<String?, String?>?
    /** A poToken for `id` (a video's id; or the visitor's or account's, for the streaming one); null if BotGuard can't run. */
    fun poToken(id: String): String?
}

/**
 * A song's audio, found three ways in turn, each link tried before it's trusted (its start and its middle: a song
 * asked without what YouTube wants gives its first bytes, then turns the rest away), the best AAC (what AVPlayer plays):
 *  - as YouTube's visionOS app asks, with a visitor's id (NewPipe 0.26.5's way): a plain link, no token, no
 *    challenges; every song played through, tried 2026-11-07;
 *  - as YouTube Music's web player gets it (the phone's signed-in path, YouTubeMusic.signedStream, made shared): the
 *    song's poToken and the player's date, its challenges solved, the streaming poToken on the end (signed out bound
 *    to the visitor, signed in to the account); 256k on Premium. Signed out on the iPhone (2026-11-07) YouTube took
 *    the request but turned a song's bytes away after the first: kept for when the first way closes;
 *  - the iOS app's link, which plays a music video (not a song) with no token.
 * Blocking: off the main thread.
 */
class TubeStream(private val js: TubeJs, private val dir: File, private val signed: InnerTube? = null) {
    data class Stream(val url: String, val mime: String, val kbps: Int, val how: String)

    private val guard = Guard()
    private var t: InnerTube? = null
    private var session: InnerTube.Session? = null
    private var code: String? = null
    private var sts: Int? = null
    private var streaming: String? = null

    /** Each step's word, for the log ("ink-youtube"). */
    var log: (String) -> Unit = {}

    private fun ready(): Pair<InnerTube, InnerTube.Session>? = guard.run {
        val s = session
        if (t != null && s != null && code != null) return@run t!! to s
        val got = (signed ?: InnerTube()).session() ?: return@run null
        val tube = signed ?: InnerTube(null, got.visitorData)
        val url = got.playerUrl ?: return@run null
        val kept = player(url, tube) ?: return@run null
        t = tube; session = got; code = kept.first; sts = kept.second
        streaming = (if (signed != null) got.dataSyncId else got.visitorData)?.let { js.poToken(it) }
        log("session: ${if (signed != null) "signed in" else "a visitor"}, player ${url.substringAfter("/player/").substringBefore('/')}, " +
            "streaming poToken ${if (streaming != null) "made" else "none"}")
        tube to got
    }

    /** A player's code and date: kept on the disk a version (a few megabytes, asked once). */
    private fun player(url: String, tube: InnerTube): Pair<String, Int?>? {
        val f = File(File(dir, "player"), sha1(url.encodeToByteArray()).hex().take(16) + ".js")
        runCatching { f.readText() }.getOrNull()?.takeIf { it.length > 100_000 }?.let { c ->
            return c to Regex("(?:signatureTimestamp|sts):(\\d{5})").find(c)?.groupValues?.get(1)?.toIntOrNull()
        }
        return tube.playerCode(url)?.also { (c, _) ->
            runCatching { f.getParentFile()?.listFiles()?.forEach { it.delete() }; f.getParentFile()?.mkdirs(); f.writeText(c) }
        }
    }

    /** Forgotten, so the next song makes a new session (a token turned away, the player changed). */
    fun reset() = guard.run { t = null; session = null; code = null; streaming = null }

    /** The slow parts made ahead, as the app opens (signed in: the session, the player's code, BotGuard's token). */
    fun warm() {
        if (signed == null) return
        val (_, got) = ready() ?: return
        // the solver reads the player once (near two seconds on the iPhone): done now, not on the first song
        runCatching { js.solve(got.playerUrl ?: return, guard.run { code } ?: return, null, "warm") }
    }

    // the web player's turned away twice running: the plain way first, till it gives a song again
    private var webMisses = 0

    // signed in, the web player's first: only it offers Premium's 256k and your uploads
    fun stream(videoId: String): Stream? {
        val t0 = nowMs()
        val webFirst = signed != null && guard.run { webMisses } < 2
        val s = if (webFirst) web(videoId) ?: vision(videoId) ?: ios(videoId) else vision(videoId) ?: web(videoId) ?: ios(videoId)
        log("$videoId: ${s?.how ?: "none"} in ${nowMs() - t0} ms")
        return s
    }

    private var visitor: String? = null

    /** As YouTube's visionOS app asks: its visitor id made once, then each song's plain link. */
    private fun vision(videoId: String): Stream? = runCatching {
        val client = JSONObject().put("clientName", "VISIONOS").put("clientVersion", VISION_VERSION).put("clientScreen", "WATCH").put("platform", "MOBILE")
            .put("deviceMake", "Apple").put("deviceModel", "RealityDevice14,1").put("osName", "visionOS").put("osVersion", "25.6.0.23O471").put("hl", "en").put("gl", "US")
        fun ask(endpoint: String, body: JSONObject, v: String?): JSONObject {
            val headers = mapOf("Content-Type" to "application/json", "User-Agent" to VISION_UA) + (v?.let { mapOf("X-Goog-Visitor-Id" to it) } ?: emptyMap())
            v?.let { client.put("visitorData", it) }
            return JSONObject(http("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false", "POST", headers,
                body.put("context", JSONObject().put("client", client)).toString().encodeToByteArray()).text)
        }
        val v = guard.run { visitor } ?: ask("visitor_id", JSONObject(), null).optJSONObject("responseContext")?.optString("visitorData")
            ?.takeIf { it.isNotEmpty() }?.also { guard.run { visitor = it } } ?: return null.also { log("$videoId: no visitor") }
        val cpn = (1..16).map { CPN[kotlin.random.Random.nextInt(CPN.length)] }.joinToString("")
        val answer = ask("player", JSONObject().put("videoId", videoId).put("cpn", cpn).put("contentCheckOk", true).put("racyCheckOk", true), v)
        val status = answer.optJSONObject("playabilityStatus")
        if (status?.optString("status") != "OK") return null.also { log("$videoId visionos: ${status?.optString("status")} ${status?.optString("reason")}"); guard.run { visitor = null } }
        val pick = aac(answer) ?: return null
        val link = pick.optString("url").ifEmpty { return null }
        if (!opens(link, pick.optString("contentLength").toLongOrNull())) return null.also { log("$videoId: visionos turned away"); guard.run { visitor = null } }
        Stream(link, pick.optString("mimeType").substringBefore(';'), rate(pick) / 1000, "visionos").also { log("$videoId: visionos, itag ${pick.optInt("itag")} ${it.kbps} kbps") }
    }.onFailure { log("$videoId visionos: $it") }.getOrNull()

    private fun web(videoId: String): Stream? = webTried(videoId).also { s -> guard.run { webMisses = if (s != null) 0 else webMisses + 1 } }

    private fun webTried(videoId: String): Stream? = runCatching {
        val (tube, s) = ready() ?: return null.also { log("$videoId: no session") }
        val url = s.playerUrl ?: return null
        val pot = js.poToken(videoId)
        val answer = tube.player(videoId, sts, pot)
        val status = answer.optJSONObject("playabilityStatus")
        if (status?.optString("status") != "OK") return null.also { log("$videoId: ${status?.optString("status")} ${status?.optString("reason")}") }
        val pick = aac(answer) ?: return null.also { log("$videoId: no aac") }
        val cipher = pick.optString("signatureCipher").takeIf { it.isNotEmpty() }?.split('&')?.associate { kv ->
            kv.substringBefore('=') to urlDecode(kv.substringAfter('='))
        }
        var link = cipher?.get("url") ?: pick.optString("url").ifEmpty { return null }
        val n = Regex("[?&]n=([^&]+)").find(link)?.groupValues?.get(1)?.let(::urlDecode)
        val (sig, nAnswer) = js.solve(url, code ?: return null, cipher?.get("s"), n) ?: return null.also { log("$videoId: the solver couldn't") }
        if (cipher != null) link += "&" + (cipher["sp"] ?: "signature") + "=" + urlEncode(sig ?: return null)
        if (n != null && nAnswer != null) link = link.replace("n=" + urlEncode(n), "n=" + urlEncode(nAnswer)).let { if (it == link) link.replace("n=$n", "n=$nAnswer") else it }
        streaming?.let { link += "&pot=" + urlEncode(it) }
        val how = "web, ${if (pot != null) "with" else "no"} poToken, ${if (cipher != null) "cipher" else "plain"}, n ${if (nAnswer != null) "solved" else "none"}"
        if (!opens(link, pick.optString("contentLength").toLongOrNull())) return null.also { log("$videoId: turned away ($how)"); if (pot != null) reset() }
        Stream(link, pick.optString("mimeType").substringBefore(';'), rate(pick) / 1000, how).also { log("$videoId: itag ${pick.optInt("itag")} ${it.kbps} kbps ($how)") }
    }.onFailure { log("$videoId: $it") }.getOrNull()

    /** The iOS client's link, plain (no challenges): a music video's plays through with no token. */
    private fun ios(videoId: String): Stream? = runCatching {
        val client = JSONObject().put("clientName", "IOS").put("clientVersion", IOS_VERSION).put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone").put("osVersion", "18.3.2.22D82").put("hl", "en").put("gl", "US")
        val body = JSONObject().put("videoId", videoId).put("context", JSONObject().put("client", client)).put("racyCheckOk", true).put("contentCheckOk", true)
        val r = http("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", "POST",
            mapOf("Content-Type" to "application/json", "User-Agent" to IOS_UA), body.toString().encodeToByteArray())
        val pick = aac(JSONObject(r.text)) ?: return null
        val link = pick.optString("url").ifEmpty { return null }
        if (!opens(link, pick.optString("contentLength").toLongOrNull())) return null.also { log("$videoId: the ios client's turned away") }
        Stream(link, pick.optString("mimeType").substringBefore(';'), rate(pick) / 1000, "ios client").also { log("$videoId: ios client, ${it.kbps} kbps") }
    }.onFailure { log("$videoId ios: $it") }.getOrNull()

    private fun rate(f: JSONObject) = f.optInt("averageBitrate").takeIf { it > 0 } ?: f.optInt("bitrate")

    /** The best AAC audio a player answer offers. */
    private fun aac(answer: JSONObject): JSONObject? {
        val formats = answer.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
        return (0 until formats.length()).map { formats.getJSONObject(it) }.filter { it.optString("mimeType").startsWith("audio/mp4") }.maxByOrNull(::rate)
    }

    /**
     * Whether a link gives its bytes: its start, and its middle too (a token-less song gives its first bytes, then
     * turns the rest away).
     */
    private fun opens(url: String, length: Long?): Boolean = runCatching {
        fun at(from: Long) = http(url, headers = mapOf("Range" to "bytes=$from-${from + 1}"), connectMs = 6_000, readMs = 6_000, follow = 3).code in 200..206
        at(0) && (length == null || length < 400_000 || at(length / 2))
    }.getOrDefault(false)

    companion object {
        private const val CPN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        const val VISION_VERSION = "1.02"
        const val VISION_UA = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)"
        const val IOS_VERSION = "20.10.4"
        const val IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    }
}
