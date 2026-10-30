package app.ink.music

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Your Spotify playlists, read the way Spotify's own web player reads them (its API is closed to small apps; the
 * file from Exportify was the only way in, and people lost their way there, r/inkmusic 2026-10-30; after BitChord's).
 * You sign in on Spotify's page ([SpotifySignIn]); the web player then asks for your library with its own two
 * passes (a bearer and a client token), and those are taken off its first request, never made here: Spotify turns
 * home-made ones away. They last an hour, in memory only; your password and cookie stay in the WebView's own store.
 * Then the library (Liked Songs first) and a playlist's songs, by the web player's own queries.
 */
object Spotify {
    const val available = true

    private const val QUERY = "https://api-partner.spotify.com/pathfinder/v2/query"
    // the web player's queries, by hash (BitChord's, 2026-10-30): its library, a playlist, your liked songs
    private const val LIBRARY = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
    private const val PLAYLIST = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    const val LIKED_ID = "liked"

    /**
     * The browser it says it is: the phone's own Chrome (the WebView's name, less its "wv"), as YouTube's sign-in does,
     * and the same in its own asks after. A desktop Chrome's name drew Spotify's login as a blank page: the page's
     * other headers still said Android (2026-10-30).
     */
    @Volatile var agent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private set

    /** A WebView named as the phone's Chrome. */
    internal fun named(w: WebView) {
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv)", ")").replace(Regex("Version/\\S+ "), "")
        agent = w.settings.userAgentString
    }
    const val SIGN_IN = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

    @Volatile private var bearer: String? = null
    @Volatile private var client: String? = null
    private val _at = MutableStateFlow(0L)
    /** When the passes were taken (0: never): the import page reads the library once it changes. */
    val at: StateFlow<Long> = _at

    /** Taken within the hour (they last one). */
    fun ready() = bearer != null && System.currentTimeMillis() - _at.value < 50 * 60_000L

    fun signIn(c: Context) {
        c.startActivity(Intent(c, SpotifySignIn::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun take(b: String, c: String?) {
        bearer = b; client = c; _at.value = System.currentTimeMillis()
    }

    /** Signed in: Spotify's session cookie is there (a visitor's player asks with an anonymous pass, no use here). */
    fun signedIn() = CookieManager.getInstance().getCookie("https://open.spotify.com")?.contains("sp_dc=") == true

    /** One of the web player's asks of Spotify: the first signed-in one carries both passes, taken. True if taken. */
    internal fun caught(request: WebResourceRequest): Boolean {
        val host = request.url.host.orEmpty()
        if (host != "api-partner.spotify.com" && !host.endsWith("spclient.spotify.com")) return false
        val h = request.requestHeaders.mapKeys { it.key.lowercase() }
        val auth = h["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() } ?: return false
        if (!signedIn() || ready()) return false
        take(auth, h["client-token"])
        return true
    }

    private val gate = Mutex()

    /**
     * Passes within the hour: the ones held, else new ones from the web player loaded out of sight with your kept
     * sign-in (its cookie, in the WebView's own store), never a page shown. False signed out, or if it didn't answer.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fresh(c: Context): Boolean = gate.withLock {
        if (ready()) return@withLock true
        if (!signedIn()) return@withLock false
        val got = CompletableDeferred<Unit>()
        val web = withContext(Dispatchers.Main) {
            WebView(c.applicationContext).apply {
                // a page out of sight still wants a size (Spotify's lays itself out off the height)
                layout(0, 0, 1080, 2340)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                named(this)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (caught(request)) got.complete(Unit)
                        return null
                    }
                }
                loadUrl("https://open.spotify.com/")
            }
        }
        withTimeoutOrNull(25_000) { got.await() }
        withContext(Dispatchers.Main) { runCatching { web.stopLoading(); web.destroy() } }
        android.util.Log.i("ink-spotify", "fresh passes: ${ready()}")
        ready()
    }

    // ---- canvas: the looping clip Spotify shows behind a song ----

    private const val CANVAS = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"
    @Volatile private var canvasHash: String? = null
    private val canvases = HashMap<String, Pair<String?, Long>>()
    private var canvasFile: java.io.File? = null

    /**
     * A song's Canvas (an .mp4, tall, looping), or null: none, signed out, or Spotify wouldn't say. The song found on
     * Spotify by title and artist (the same artist), then its canvas asked as the web player asks. Kept, a found one
     * and a none (a week: one may come), so a song is asked about once.
     */
    suspend fun canvas(c: Context, title: String, artist: String): String? {
        if (title.isBlank() || artist.isBlank()) return null
        val key = Search.fold("$artist / $title")
        synchronized(canvases) {
            if (canvasFile == null) {
                canvasFile = java.io.File(c.cacheDir, "spotify-canvas.json")
                runCatching { JSONObject(canvasFile!!.readText()).let { o -> o.keys().forEach { k -> val e = o.getJSONObject(k); canvases[k] = e.optString("url").ifEmpty { null } to e.optLong("at") } } }
            }
            canvases[key]?.let { (url, at) -> if (url != null || System.currentTimeMillis() - at < 7 * 86_400_000L) return url }
        }
        if (!fresh(c)) return null
        val asked = withContext(Dispatchers.IO) { runCatching { track(title, artist)?.let { canvasOf(it) } } }
            .onFailure { android.util.Log.w("ink-spotify", "canvas: $it") }
        if (asked.isFailure) return null
        val url = asked.getOrNull()
        android.util.Log.i("ink-spotify", "canvas $artist / $title: ${if (url != null) "found" else "none"}")
        synchronized(canvases) {
            canvases[key] = url to System.currentTimeMillis()
            runCatching {
                val o = JSONObject(); canvases.forEach { (k, v) -> o.put(k, JSONObject().put("url", v.first ?: "").put("at", v.second)) }
                canvasFile?.writeText(o.toString())
            }
        }
        return url
    }

    private const val SEARCH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
    @Volatile private var searchHash: String? = null

    /**
     * The song on Spotify (its uri), by the web player's own search (its public API turns the web player's pass away,
     * a 429): the first with the same title and artist.
     */
    private fun track(title: String, artist: String): String? {
        val vars = JSONObject().put("searchTerm", "$title $artist").put("offset", 0).put("limit", 10).put("numberOfTopResults", 5)
            .put("includeAudiobooks", false).put("includePreReleases", false)
        val root = runCatching { ask("searchTracks", searchHash ?: SEARCH, vars) }.getOrElse { e ->
            if ("PersistedQuery" !in e.message.orEmpty()) throw e
            searchHash = liveHash("searchTracks") ?: throw e
            ask("searchTracks", searchHash!!, vars)
        }
        val items = root.optJSONObject("data")?.optJSONObject("searchV2")?.optJSONObject("tracksV2")?.optJSONArray("items") ?: return null
        val a = Search.fold(artist); val t = Search.fold(title.substringBefore(" (").substringBefore(" - "))
        for (i in 0 until items.length()) {
            val d = items.optJSONObject(i)?.optJSONObject("item")?.optJSONObject("data") ?: continue
            val name = Search.fold(d.optString("name").substringBefore(" (").substringBefore(" - "))
            val artists = d.optJSONObject("artists")?.optJSONArray("items") ?: continue
            val same = (0 until artists.length()).any { j ->
                Search.fold(artists.optJSONObject(j)?.optJSONObject("profile")?.optString("name").orEmpty()).let { n -> n == a || a.startsWith("$n ") || n.startsWith("$a ") }
            }
            if (same && (name == t || name.startsWith(t) || t.startsWith(name))) return d.optString("uri").takeIf { it.startsWith("spotify:track:") }
        }
        android.util.Log.i("ink-spotify", "search: no match of ${items.length()} for $artist / $title")
        return null
    }

    /** The song's canvas by the web player's own query; its hash read again from the player's scripts once stale. */
    private fun canvasOf(uri: String): String? {
        repeat(2) { n ->
            val hash = canvasHash ?: CANVAS
            val root = runCatching { ask("canvas", hash, JSONObject().put("trackUri", uri)) }
            val failed = root.exceptionOrNull()
            if (failed != null) {
                if (n == 0 && "PersistedQuery" in failed.message.orEmpty()) { canvasHash = liveHash("canvas") ?: return null; return@repeat }
                throw failed
            }
            val canvas = root.getOrThrow().optJSONObject("data")?.optJSONObject("trackUnion")?.optJSONObject("canvas") ?: return null
            // a video only (some are still pictures)
            return canvas.optString("url").takeIf { it.startsWith("https://") && Regex("\\.mp4(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        }
        return null
    }

    /** A query's current hash, from the web player's own scripts (it changes when Spotify rebuilds the player). */
    private fun liveHash(operation: String): String? = runCatching {
        val html = get("https://open.spotify.com/", auth = false)
        Regex("https://open\\.spotifycdn\\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\\.js").findAll(html).map { it.value }.distinct().take(8)
            .firstNotNullOfOrNull { js ->
                val body = runCatching { get(js, auth = false) }.getOrNull() ?: return@firstNotNullOfOrNull null
                Regex("[\"'](\\w+)[\"']\\s*,\\s*[\"']query[\"']\\s*,\\s*[\"']([0-9a-f]{64})[\"']").findAll(body)
                    .firstOrNull { it.groupValues[1] == operation }?.groupValues?.get(2)
            }
    }.getOrNull()

    private fun get(url: String, auth: Boolean = true): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", agent)
        if (auth) {
            c.setRequestProperty("Authorization", "Bearer ${bearer ?: error("not signed in")}")
            client?.let { c.setRequestProperty("client-token", it) }
            c.setRequestProperty("app-platform", "WebPlayer")
            c.setRequestProperty("Origin", "https://open.spotify.com")
            c.setRequestProperty("Referer", "https://open.spotify.com/")
        }
        val code = c.responseCode
        if (code >= 400) error("get: http $code")
        return c.inputStream.use { it.bufferedReader().readText() }
    }

    /** Your playlists, Liked Songs first; null when Spotify wouldn't say (signed out, or its queries changed). */
    suspend fun playlists(): List<Import.Theirs>? = withContext(Dispatchers.IO) {
        runCatching {
            val out = mutableListOf(Import.Theirs(LIKED_ID, "liked songs", ""))
            var offset = 0
            while (true) {
                val vars = JSONObject().put("filters", JSONArray().put("Playlists")).put("order", JSONObject.NULL).put("textFilter", "")
                    .put("features", JSONArray(listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS")))
                    .put("limit", 50).put("offset", offset).put("flatten", true).put("expandedFolders", JSONArray())
                    .put("folderUri", JSONObject.NULL).put("includeFoldersWhenFlattening", false)
                val lib = ask("libraryV3", LIBRARY, vars).getJSONObject("data").getJSONObject("me").getJSONObject("libraryV3")
                val items = lib.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val w = items.optJSONObject(i)?.optJSONObject("item") ?: continue
                    val d = w.optJSONObject("data") ?: continue
                    if (d.optString("__typename") != "Playlist") continue
                    val name = d.optString("name").ifBlank { continue }
                    val by = d.optJSONObject("ownerV2")?.optJSONObject("data")?.optString("name").orEmpty()
                    out += Import.Theirs(w.optString("_uri").substringAfterLast(':'), name, by)
                }
                offset += items.length()
                if (items.length() == 0 || offset >= lib.optInt("totalCount", offset)) break
            }
            out
        }.onFailure { android.util.Log.w("ink-spotify", "library: $it") }.getOrNull()
    }

    /** A playlist's songs (or your liked ones), all of them, [got] told how many as each page comes. */
    suspend fun songs(id: String, got: (Int) -> Unit = {}): List<Import.Said>? = withContext(Dispatchers.IO) {
        runCatching {
            val out = mutableListOf<Import.Said>()
            var offset = 0
            while (true) {
                val (items, total) = if (id == LIKED_ID) {
                    val t = ask("fetchLibraryTracks", LIKED, JSONObject().put("offset", offset).put("limit", 100))
                        .getJSONObject("data").getJSONObject("me").getJSONObject("library").getJSONObject("tracks")
                    (t.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("track")?.optJSONObject("data") } } to t.optInt("totalCount")
                } else {
                    val c = ask("fetchPlaylist", PLAYLIST, JSONObject().put("uri", "spotify:playlist:$id").put("offset", offset).put("limit", 100).put("enableWatchFeedEntrypoint", false))
                        .getJSONObject("data").getJSONObject("playlistV2").getJSONObject("content")
                    (c.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("itemV2")?.optJSONObject("data") } } to c.optInt("totalCount")
                }
                items.forEach { d ->
                    val title = d.optString("name").ifBlank { return@forEach }
                    val artists = d.optJSONObject("artists")?.optJSONArray("items")
                    val artist = artists?.optJSONObject(0)?.optJSONObject("profile")?.optString("name").orEmpty()
                    out += Import.Said(title, artist, d.optJSONObject("albumOfTrack")?.optString("name").orEmpty())
                }
                offset += 100
                got(out.size)
                if (items.isEmpty() || offset >= total) break
            }
            out
        }.onFailure { android.util.Log.w("ink-spotify", "songs: $it") }.getOrNull()
    }

    private fun ask(operation: String, hash: String, vars: JSONObject): JSONObject {
        val b = bearer ?: error("not signed in")
        val body = JSONObject().put("variables", vars).put("operationName", operation)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash)))
        val c = URL(QUERY).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("Authorization", "Bearer $b")
        client?.let { c.setRequestProperty("client-token", it) }
        c.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("app-platform", "WebPlayer")
        c.setRequestProperty("Origin", "https://open.spotify.com")
        c.setRequestProperty("Referer", "https://open.spotify.com/")
        c.setRequestProperty("User-Agent", agent)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.bufferedReader().readText() }.orEmpty()
        if (code >= 400) error("$operation: http $code")
        val root = JSONObject(text)
        root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }?.let { error("$operation: $it") }
        return root
    }
}

/**
 * Signing in to Spotify on its own page, on a screen of its own (as YouTube's). Once it lands on the web player
 * signed in, the page is hidden ("reading your spotify") while the player makes its first ask of Spotify; that
 * ask's two passes are taken (Spotify.take) and it closes. Signed in before, it goes straight through.
 */
class SpotifySignIn : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var web by remember { mutableStateOf<WebView?>(null) }
            // on the web player, signed in: its page hidden while its first ask is waited for
            var reading by remember { mutableStateOf(false) }
            var said by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(reading) {
                if (!reading) return@LaunchedEffect
                delay(25_000)
                said = "spotify didn't answer. close, and try again"
            }
            BackHandler { web?.takeIf { it.canGoBack() && !reading }?.goBack() ?: finish() }
            Column(Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("spotify", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                    Word("close", pixel(12.sp, Ink.mid), touch = 10.dp) { finish() }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        // never in a layer of its own (an alpha one drew it blank): hidden by the black panel over it
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            WebView(c).apply {
                                // filling its space, not the wrap-content Compose hands a view: Spotify's page sizes
                                // itself off the page's height, and a zero one drew it blank (BitChord found it)
                                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                Spotify.named(this)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                setBackgroundColor(android.graphics.Color.BLACK)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        android.util.Log.i("ink-spotify", "page: ${url.substringBefore('?')}")
                                    }

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                                        if (request.isForMainFrame) android.util.Log.w("ink-spotify", "page error: ${error.errorCode} ${error.description}")
                                    }

                                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                        reading = url.startsWith("https://open.spotify.com") && Spotify.signedIn()
                                    }

                                    // the web player's own asks of Spotify: the first signed-in one carries both passes
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                        if (Spotify.caught(request)) runOnUiThread { finish() }
                                        return null
                                    }
                                }
                                // the page's own words, in the log (what it says when it won't draw)
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                        android.util.Log.i("ink-spotify", "page says: ${m.message().take(200)}")
                                        return true
                                    }
                                }
                                web = this
                                loadUrl(Spotify.SIGN_IN)
                            }
                        },
                    )
                    if (reading) Column(Modifier.fillMaxSize().background(Ink.black).padding(20.dp)) {
                        BasicText(if (said == null) "READING YOUR SPOTIFY" else "NOT SIGNED IN", style = headline(40.sp, if (said == null) Ink.mid else Ink.ink))
                        Spacer(Modifier.height(8.dp))
                        said?.let { BasicText(it, style = pixel(12.sp, Ink.mid)) }
                    }
                }
            }
        }
    }

}
