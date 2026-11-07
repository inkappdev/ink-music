@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.ink.music

import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.JavaScriptCore.JSContext
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.io.encoding.Base64

actual fun tubeJs(): TubeJs = IosTube

/**
 * The iPhone's runners for YouTube's streams: yt-dlp's solver (ejs, in the app) in JavaScriptCore, which answers at
 * once and needs no page; BotGuard (po_token.html, NewPipe's, as the phone runs it) in a hidden WKWebView, as a
 * browser runs it, its two requests to Google made here. Each player version prepared once.
 */
object IosTube : TubeJs {
    private val solving = Guard()
    private var ctx: JSContext? = null
    private val prepared = HashSet<String>()

    override fun solve(playerUrl: String, playerCode: String, sig: String?, n: String?): Pair<String?, String?>? = solving.run {
        runCatching {
            val c = ctx ?: JSContext().also { c ->
                val lib = bundleText("yt.solver.lib.min", "js") ?: error("no solver")
                val core = bundleText("yt.solver.core.min", "js") ?: error("no solver")
                c.evaluateScript(lib + "\nvar meriyah = lib.meriyah, astring = lib.astring;\n" + core + "\n" + SOLVE)
                ctx = c
            }
            if (playerUrl !in prepared) {
                // the player's few megabytes handed in as a value, not written into a script
                c.evaluateScript("inkPrepare")?.callWithArguments(listOf(playerUrl, playerCode))
                prepared += playerUrl
            }
            val out = c.evaluateScript("inkSolve(${quote(playerUrl)}, ${q(sig)}, ${q(n)})")?.toString() ?: error("no answer")
            val o = JSONObject(out)
            if (o.has("error")) error(o.getString("error"))
            o.optString("sig").ifEmpty { null }.takeIf { sig != null } to o.optString("n").ifEmpty { null }.takeIf { n != null }
        }.onFailure { println("ink-youtube solver: $it") }.getOrNull()
    }

    private fun q(s: String?) = if (s == null) "null" else quote(s)

    override fun poToken(id: String): String? = BotGuard.token(id)

    private const val SOLVE = """
        var inkPrepared = {};
        function inkPrepare(url, code) {
            var out = jsc({ type: "player", player: code, output_preprocessed: true, requests: [] });
            inkPrepared[url] = out.preprocessed_player;
        }
        function inkSolve(url, sig, n) {
            try {
                var requests = [];
                if (sig) requests.push({ type: "sig", challenges: [sig] });
                if (n) requests.push({ type: "n", challenges: [n] });
                var out = jsc({ type: "preprocessed", preprocessed_player: inkPrepared[url], requests: requests });
                var answers = {};
                (out.responses || []).forEach(function (r, i) {
                    var kind = requests[i].type, challenge = requests[i].challenges[0];
                    if (r.type === "result") answers[kind] = r.data[challenge]; else answers[kind + "Error"] = r.error;
                });
                return JSON.stringify(answers);
            } catch (e) { return JSON.stringify({ error: String(e) }); }
        }
    """
}

/** A string as JavaScript (and JSON) writes it, quoted. */
private fun quote(s: String): String = JSONArray().put(s).toString().removePrefix("[").removeSuffix("]")

/** A wait across threads: the calling one (never the main) waits on what the main thread does. */
private class Wait {
    private val sem = dispatch_semaphore_create(0)
    var value: String? = null
    fun done(v: String?) { value = v; dispatch_semaphore_signal(sem) }
    fun await(ms: Long): String? { dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, ms * NSEC_PER_MSEC.toLong())); return value }
}

/**
 * BotGuard in a hidden WKWebView: made once, then it signs ids till its integrity token runs out (ten minutes kept
 * in hand, as NewPipe does), then made again. Blocking: called off the main thread.
 */
object BotGuard {
    private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    /** What the WebView says it is, its own (WebKit's, on an iPhone): what BotGuard sees must be what it's told. */
    var ua: String = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1"
        private set

    private val one = Guard()
    private var web: WKWebView? = null
    private var expires = 0L
    private var broken = false
    private var starting: Wait? = null
    private val pending = HashMap<String, Wait>()

    fun token(id: String): String? = one.run {
        if (broken) return@run null
        if (web == null || nowMs() > expires) {
            val w = Wait()
            starting = w
            main { start() }
            if (w.await(30_000) != "ready") { println("ink-potoken: botguard didn't start (${w.value})"); main { close() }; return@run null }
        }
        val w = Wait()
        main {
            pending[id] = w
            web?.evaluateJavaScript("""try {
                    var poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, ${u8(id.encodeToByteArray())});
                    PoTokenWebView.onObtainPoTokenResult(${quote(id)}, Array.prototype.join.call(poTokenU8, ","));
                } catch (error) { PoTokenWebView.onObtainPoTokenError(${quote(id)}, error + "\n" + error.stack); }""", null)
        }
        w.await(15_000).also { if (it == null) println("ink-potoken: none for $id") }
    }

    private fun main(f: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { f() }

    private val handler = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            val m = runCatching { JSONObject(didReceiveScriptMessage.body as String) }.getOrNull() ?: return
            when (m.optString("k")) {
                "run" -> request("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]") { body ->
                    web?.evaluateJavaScript("""try {
                            var data = ${challenge(body)};
                            runBotGuard(data).then(function (result) {
                                this.webPoSignalOutput = result.webPoSignalOutput;
                                PoTokenWebView.onRunBotguardResult(result.botguardResponse);
                            }, function (error) { PoTokenWebView.onJsInitializationError(error + "\n" + error.stack); });
                        } catch (error) { PoTokenWebView.onJsInitializationError(error + "\n" + error.stack); }""", null)
                }
                "result" -> request("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"$REQUEST_KEY\", \"${m.optString("a")}\" ]") { body ->
                    val data = JSONArray(body)
                    expires = nowMs() + (data.getLong(1) - 600) * 1000
                    web?.evaluateJavaScript("this.integrityToken = ${u8(yt64(data.getString(0)))}") { _, _ -> starting?.done("ready") }
                }
                "error" -> {
                    println("ink-potoken: ${m.optString("a").take(300)}")
                    if ("SyntaxError" in m.optString("a")) broken = true
                    starting?.done("error")
                }
                "pot" -> pending.remove(m.optString("a"))?.done(runCatching {
                    Base64.Default.encode(m.optString("b").split(",").map { it.trim().toInt().toByte() }.toByteArray()).replace('+', '-').replace('/', '_')
                }.getOrNull())
                "poterr" -> { println("ink-potoken: ${m.optString("b").take(200)}"); pending.remove(m.optString("a"))?.done(null) }
            }
        }
    }

    private fun start() {
        close()
        val html = bundleText("po_token", "html") ?: run { starting?.done("no page"); return }
        val shim = """
            function inkPost(k, a, b) { window.webkit.messageHandlers.ink.postMessage(JSON.stringify({ k: k, a: a === undefined ? "" : String(a), b: b === undefined ? "" : String(b) })); }
            var PoTokenWebView = {
                downloadAndRunBotguard: function () { inkPost("run"); },
                onRunBotguardResult: function (r) { inkPost("result", r); },
                onJsInitializationError: function (e) { inkPost("error", e); },
                onObtainPoTokenResult: function (id, t) { inkPost("pot", id, t); },
                onObtainPoTokenError: function (id, e) { inkPost("poterr", id, e); }
            };
            window.addEventListener("error", function (e) { inkPost("error", "Uncaught " + e.message); });
        """
        val page = html.replaceFirst("<script>", "<script>$shim\n").replaceFirst("</script>", "\nPoTokenWebView.downloadAndRunBotguard()</script>")
        val config = WKWebViewConfiguration()
        config.userContentController.addScriptMessageHandler(handler, "ink")
        val w = WKWebView(frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = config)
        w.evaluateJavaScript("navigator.userAgent") { v, _ -> (v as? String)?.let { ua = it } }
        w.alpha = 0.0
        w.userInteractionEnabled = false
        // in the window, so its timers run as a page's do
        (UIApplication.sharedApplication.keyWindow ?: (UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow))?.addSubview(w)
        web = w
        w.loadHTMLString(page, baseURL = NSURL.URLWithString("https://www.youtube.com"))
    }

    private fun close() {
        web?.let { it.configuration.userContentController.removeScriptMessageHandlerForName("ink"); it.removeFromSuperview() }
        web = null
    }

    /** One of BotGuard's two requests, made here off the main thread; its answer handed back on it. */
    private fun request(url: String, data: String, then: (String) -> Unit) {
        Here.work.launch {
            val r = runCatching {
                http(url, "POST", mapOf("User-Agent" to ua, "Accept" to "application/json", "Content-Type" to "application/json+protobuf",
                    "x-goog-api-key" to API_KEY, "x-user-agent" to "grpc-web-javascript/0.1"), data.encodeToByteArray(), 15_000, 20_000)
            }.getOrNull()
            main {
                if (r == null || r.code != 200) { println("ink-potoken: $url ${r?.code}"); starting?.done("refused") }
                else runCatching { then(r.text) }.onFailure { println("ink-potoken: $it"); starting?.done("error") }
            }
        }
    }

    /** Create's answer as the object `runBotGuard` takes (descrambled when it comes scrambled). */
    private fun challenge(raw: String): String {
        val scrambled = JSONArray(raw)
        val data = if (scrambled.length() > 1 && scrambled.opt(1) is String) JSONArray(yt64(scrambled.getString(1)).map { (it + 97).toByte() }.toByteArray().decodeToString())
        else scrambled.getJSONArray(0)
        fun firstString(a: JSONArray?): Any = a?.let { arr -> (0 until arr.length()).map { arr.opt(it) }.firstOrNull { it is String } } ?: JSON_NULL
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
    private fun yt64(s: String): ByteArray = Base64.Default.decode(s.replace('-', '+').replace('_', '/').replace('.', '='))
    private fun u8(bytes: ByteArray) = "new Uint8Array([" + bytes.joinToString(",") { (it.toInt() and 0xff).toString() } + "])"
}
