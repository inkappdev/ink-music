package app.ink.music

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A signed-in stream's link unscrambled: YouTube's web player is given each stream's signature and speed-limit
 * ("n") scrambled, to be worked out by its own player code. yt-dlp's solver (ejs, Unlicense, assets/ejs) reads that
 * code as a program, not by patterns (NewPipe's patterns no longer find it), and runs the two functions: here in a
 * WebView with no network, as the poToken's, made once. Each player version is prepared once and kept there.
 */
object Solver {
    private var web: SolverWebView? = null
    private val lock = Any()
    private val players = ConcurrentHashMap<String, String>()   // player url -> its code

    /** Kept for the WebView to read (it asks by url: a few megabytes don't go through a script string). */
    fun keep(url: String, code: String) { players[url] = code }
    internal fun code(url: String): String = players[url].orEmpty()

    /**
     * The answers (blocking) to a stream's challenges, from the player at [url] (its code [keep]-ed first): its
     * signature [sig] and its [n], each null if there wasn't one. Null when the solver couldn't.
     */
    fun solve(c: Context, url: String, sig: String?, n: String?): Pair<String?, String?>? = runCatching {
        val w = synchronized(lock) { web ?: SolverWebView.create(c.applicationContext).get(20, TimeUnit.SECONDS).also { web = it } }
        val answers = w.solve(url, sig, n).get(30, TimeUnit.SECONDS)
        answers.optString("sig").ifEmpty { null }.takeIf { sig != null } to answers.optString("n").ifEmpty { null }.takeIf { n != null }
    }.onFailure { android.util.Log.d("ink-youtube", "solver: $it") }.getOrNull()
}

/** The WebView: yt-dlp's solver loaded, asked for answers, each player prepared once and kept in its page. */
class SolverWebView private constructor(context: Context, private val ready: CompletableFuture<SolverWebView>) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val ids = AtomicInteger()

    @SuppressLint("SetJavaScriptEnabled")
    private val web = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true   // it only works things out: nothing goes anywhere from here
        addJavascriptInterface(this@SolverWebView, JS)
    }

    init {
        val lib = context.assets.open("ejs/yt.solver.lib.min.js").bufferedReader().use { it.readText() }
        val core = context.assets.open("ejs/yt.solver.core.min.js").bufferedReader().use { it.readText() }
        val page = """<!DOCTYPE html><html><head><script>
            $lib
            var meriyah = lib.meriyah, astring = lib.astring;
            $core
            var prepared = {};
            function inkSolve(id, url, sig, n) {
                try {
                    var requests = [];
                    if (sig) requests.push({ type: "sig", challenges: [sig] });
                    if (n) requests.push({ type: "n", challenges: [n] });
                    var input = prepared[url]
                        ? { type: "preprocessed", preprocessed_player: prepared[url], requests: requests }
                        : { type: "player", player: $JS.player(url), output_preprocessed: true, requests: requests };
                    var out = jsc(input);
                    if (out.preprocessed_player) prepared[url] = out.preprocessed_player;
                    var answers = {};
                    (out.responses || []).forEach(function (r, i) {
                        var kind = requests[i].type, challenge = requests[i].challenges[0];
                        if (r.type === "result") answers[kind] = r.data[challenge];
                        else answers[kind + "Error"] = r.error;
                    });
                    $JS.done(id, JSON.stringify(answers));
                } catch (e) {
                    $JS.failed(id, e + "\n" + (e.stack || ""));
                }
            }
            $JS.loaded();
            </script></head><body></body></html>"""
        web.loadDataWithBaseURL("about:blank", page, "text/html", "utf-8", null)
    }

    @JavascriptInterface fun loaded() { ready.complete(this) }
    @JavascriptInterface fun player(url: String): String = Solver.code(url)
    @JavascriptInterface fun done(id: String, answers: String) { pending.remove(id)?.complete(JSONObject(answers)) }
    @JavascriptInterface fun failed(id: String, error: String) { pending.remove(id)?.completeExceptionally(IllegalStateException(error)) }

    fun solve(url: String, sig: String?, n: String?): CompletableFuture<JSONObject> {
        val id = "s" + ids.incrementAndGet()
        val f = CompletableFuture<JSONObject>()
        pending[id] = f
        fun q(s: String?) = if (s == null) "null" else JSONObject.quote(s)
        main.post { web.evaluateJavascript("inkSolve(${q(id)}, ${q(url)}, ${q(sig)}, ${q(n)})", null) }
        return f
    }

    companion object {
        private const val JS = "Ink"

        /** Made on the main thread; ready once the solver's loaded. */
        fun create(context: Context): CompletableFuture<SolverWebView> {
            val ready = CompletableFuture<SolverWebView>()
            Handler(Looper.getMainLooper()).post {
                runCatching { SolverWebView(context, ready) }.onFailure { ready.completeExceptionally(it) }
            }
            return ready
        }
    }
}
