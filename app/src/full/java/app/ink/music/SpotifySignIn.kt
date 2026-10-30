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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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

    /** The desktop Chrome the web player is happy in (a phone's gets the "open the app" page). */
    const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
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
        c.setRequestProperty("User-Agent", AGENT)
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
                        modifier = Modifier.fillMaxSize().alpha(if (reading) 0f else 1f),
                        factory = { c ->
                            WebView(c).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = Spotify.AGENT
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
                                setBackgroundColor(android.graphics.Color.BLACK)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                        reading = url.startsWith("https://open.spotify.com") && signedIn()
                                    }

                                    // the web player's own asks of Spotify: the first signed-in one carries both passes
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                        val host = request.url.host.orEmpty()
                                        if (host == "api-partner.spotify.com" || host.endsWith("spclient.spotify.com")) {
                                            val h = request.requestHeaders.mapKeys { it.key.lowercase() }
                                            val auth = h["authorization"]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() && it != h["authorization"] }
                                            if (auth != null && signedIn() && !Spotify.ready()) {
                                                Spotify.take(auth, h["client-token"])
                                                runOnUiThread { finish() }
                                            }
                                        }
                                        return null
                                    }
                                }
                                web = this
                                loadUrl(Spotify.SIGN_IN)
                            }
                        },
                    )
                    if (reading) Column(Modifier.padding(20.dp)) {
                        BasicText(if (said == null) "READING YOUR SPOTIFY" else "NOT SIGNED IN", style = headline(40.sp, if (said == null) Ink.mid else Ink.ink))
                        Spacer(Modifier.height(8.dp))
                        said?.let { BasicText(it, style = pixel(12.sp, Ink.mid)) }
                    }
                }
            }
        }
    }

    /** Signed in: Spotify's session cookie is there (a visitor's player asks with an anonymous pass, no use here). */
    private fun signedIn() = CookieManager.getInstance().getCookie("https://open.spotify.com")?.contains("sp_dc=") == true
}
