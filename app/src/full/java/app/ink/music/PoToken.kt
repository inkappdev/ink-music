package app.ink.music

// YouTube's poToken, minted the way NewPipe's app does (app/src/main/java/org/schabi/newpipe/util/
// potoken, GPL-3.0, by the NewPipe team; po_token.html is theirs too): a WebView with no network
// runs YouTube's BotGuard challenge, fetched over plain HTTP, and signs each video id with the
// integrity token it earns. Ported without RxJava (CompletableFuture), nanojson or okio (org.json,
// java.util.Base64). The full build only.

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PoTokenException(message: String) : Exception(message)
/** The system's WebView is too old or broken to run BotGuard: no poTokens on this phone. */
class BadWebViewException(message: String) : Exception(message)

private fun jsError(error: String): Exception = if ("SyntaxError" in error) BadWebViewException(error) else PoTokenException(error)

/** NewPipe's provider: one generator at a time, made again when its integrity token runs out. */
object PoTokens : PoTokenProvider {
    private var app: Context? = null
    private var broken = false
    private val lock = Any()
    private var visitorData: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenWebView? = null

    fun attach(c: Context) { app = c.applicationContext }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (broken || app == null) return null
        return try {
            web(videoId, forceRecreate = false).also { android.util.Log.i("ink-potoken", "minted for $videoId") }
        } catch (e: BadWebViewException) {
            broken = true; android.util.Log.w("ink-potoken", "this WebView can't run BotGuard: ${e.message}"); null
        } catch (e: Exception) {
            android.util.Log.w("ink-potoken", "none for $videoId: $e"); null
        }
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    // signed in: the streaming poToken is bound to the account (its data sync id), not a visitor
    private var signedFor: String? = null
    private var signedStreaming: String? = null

    /**
     * Tokens for a song played signed in (blocking): the player's, bound to the song, and the streaming one,
     * bound to [account] (its data sync id), made once an account. Null when BotGuard can't run here.
     */
    fun signed(videoId: String, account: String): Pair<String, String>? {
        if (broken || app == null) return null
        return try {
            web(videoId, forceRecreate = false)   // a generator ready (made, or made again once expired)
            val gen = synchronized(lock) { generator!! }
            val streaming = synchronized(lock) {
                if (signedFor != account || signedStreaming == null) { signedStreaming = gen.generate(account).get(15, TimeUnit.SECONDS); signedFor = account }
                signedStreaming!!
            }
            gen.generate(videoId).get(15, TimeUnit.SECONDS) to streaming
        } catch (e: Exception) {
            android.util.Log.w("ink-potoken", "none signed in for $videoId: $e"); null
        }
    }

    private fun web(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val c = app ?: throw PoTokenException("no context")
        val (gen, visitor, streaming, recreated) = synchronized(lock) {
            val again = generator == null || forceRecreate || generator!!.isExpired()
            if (again) {
                val info = InnertubeClientRequestInfo.ofWebClient()
                info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                    info, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(), YoutubeParsingHelper.YOUTUBEI_V1_URL, null, false,
                )
                generator?.let { old -> Handler(Looper.getMainLooper()).post { old.close() } }
                generator = PoTokenWebView.create(c).get(30, TimeUnit.SECONDS)
                signedStreaming = null
                // the streaming poToken is made exactly once, before any player token
                streamingPot = generator!!.generate(visitorData!!).get(15, TimeUnit.SECONDS)
            }
            Quad(generator!!, visitorData!!, streamingPot!!, again)
        }
        val player = try { gen.generate(videoId).get(15, TimeUnit.SECONDS) } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            if (cause is BadWebViewException) throw cause
            // the WebView's state can be lost (the process in the background): once more, from scratch
            if (recreated) throw cause else return web(videoId, forceRecreate = true)
        }
        return PoTokenResult(visitor, player, streaming)
    }

    private data class Quad(val gen: PoTokenWebView, val visitor: String, val streaming: String, val recreated: Boolean)
}

/** One WebView running BotGuard: made once, then signs ids until its integrity token expires. */
class PoTokenWebView private constructor(context: Context, private val ready: CompletableFuture<PoTokenWebView>) {
    @SuppressLint("SetJavaScriptEnabled")
    private val web = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.safeBrowsingEnabled = false
        settings.userAgentString = USER_AGENT
        settings.blockNetworkLoads = true   // the page needs no network: the challenge is fetched for it
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if ("Uncaught" in m.message()) {
                    val e = BadWebViewException("\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})")
                    fail(e)
                    popAll().forEach { it.second.completeExceptionally(e) }
                }
                return super.onConsoleMessage(m)
            }
        }
    }
    private val pending = mutableListOf<Pair<String, CompletableFuture<String>>>()
    private lateinit var expires: Instant

    init { web.addJavascriptInterface(this, JS) }

    private fun load(context: Context) = io.execute {
        val html = runCatching { context.assets.open("po_token.html").bufferedReader().use { it.readText() } }.getOrElse { return@execute fail(it) }
        main.post {
            web.loadDataWithBaseURL(
                "https://www.youtube.com",
                html.replaceFirst("</script>", "\n$JS.downloadAndRunBotguard()</script>"),
                "text/html", "utf-8", null,
            )
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        botguard("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]") { body ->
            web.evaluateJavascript(
                """try {
                    data = ${challenge(body)}
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null,
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) = fail(jsError(error))

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        botguard("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]") { body ->
            val data = JSONArray(body)
            // ten minutes' margin, as NewPipe keeps
            expires = Instant.now().plusSeconds(data.getLong(1) - 600)
            web.evaluateJavascript("this.integrityToken = ${u8(b64(data.getString(0)))}") { ready.complete(this) }
        }
    }

    /** A poToken for `id` (a video id, or the visitor data for the streaming one). */
    fun generate(id: String): CompletableFuture<String> {
        val f = CompletableFuture<String>()
        main.post {
            synchronized(pending) { pending.add(id to f) }
            web.evaluateJavascript(
                """try {
                    identifier = "$id"
                    u8Identifier = ${u8(id.toByteArray())}
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ","
                        poTokenU8String += poTokenU8[i]
                    }
                    $JS.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) {
                    $JS.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }""",
            ) {}
        }
        return f
    }

    @JavascriptInterface
    fun onObtainPoTokenError(id: String, error: String) { pop(id)?.completeExceptionally(jsError(error)) }

    @JavascriptInterface
    fun onObtainPoTokenResult(id: String, poTokenU8: String) {
        val token = runCatching {
            Base64.getEncoder().encodeToString(poTokenU8.split(",").map { it.trim().toUByte().toByte() }.toByteArray())
                .replace("+", "-").replace("/", "_")
        }.getOrElse { pop(id)?.completeExceptionally(it); return }
        pop(id)?.complete(token)
    }

    fun isExpired() = Instant.now().isAfter(expires)

    private fun pop(id: String) = synchronized(pending) {
        pending.indexOfFirst { it.first == id }.takeIf { it >= 0 }?.let { pending.removeAt(it).second }
    }

    private fun popAll() = synchronized(pending) { pending.toList().also { pending.clear() } }

    /** A BotGuard request (initialization only): a non-200 answer or no answer fails the whole thing. */
    private fun botguard(url: String, data: String, then: (String) -> Unit) = io.execute {
        val body = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Content-Type", "application/json+protobuf")
                c.setRequestProperty("x-goog-api-key", GOOGLE_API_KEY)
                c.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")
                c.outputStream.use { it.write(data.toByteArray()) }
                if (c.responseCode != 200) throw PoTokenException("Invalid response code: ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }.getOrElse { return@execute fail(it) }
        main.post { runCatching { then(body) }.onFailure { fail(it) } }
    }

    private fun fail(e: Throwable) {
        main.post { close() }
        ready.completeExceptionally(e)
    }

    fun close() {
        web.clearHistory()
        web.clearCache(true)
        web.loadUrl("about:blank")
        web.onPause()
        web.removeAllViews()
        web.destroy()
    }

    companion object {
        // BotGuard's public key and request key, as NewPipe found them
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS = "PoTokenWebView"
        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newCachedThreadPool()

        fun create(c: Context): CompletableFuture<PoTokenWebView> {
            val f = CompletableFuture<PoTokenWebView>()
            if (!main.post { runCatching { PoTokenWebView(c, f).load(c) }.onFailure { f.completeExceptionally(it) } })
                f.completeExceptionally(PoTokenException("no main thread"))
            return f
        }

        /** Create's answer as the object `runBotGuard` takes (descrambled when it comes scrambled). */
        private fun challenge(raw: String): String {
            val scrambled = JSONArray(raw)
            val data = if (scrambled.length() > 1 && scrambled.opt(1) is String) JSONArray(String(b64(scrambled.getString(1)).map { (it + 97).toByte() }.toByteArray()))
            else scrambled.getJSONArray(0)
            fun firstString(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.opt(it) }.firstOrNull { it is String } } ?: JSONObject.NULL
            return JSONObject()
                .put("messageId", data.optString(0))
                .put("interpreterJavascript", JSONObject()
                    .put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(data.optJSONArray(1)))
                    .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(data.optJSONArray(2))))
                .put("interpreterHash", data.optString(3))
                .put("program", data.optString(4))
                .put("globalName", data.optString(5))
                .put("clientExperimentsStateBlob", data.optString(7))
                .toString()
        }

        /** YouTube's base64 ("-", "_", "." for "+", "/", "="). */
        private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').replace('.', '='))
        private fun u8(bytes: ByteArray) = "new Uint8Array([" + bytes.joinToString(",") { it.toUByte().toString() } + "])"
    }
}
