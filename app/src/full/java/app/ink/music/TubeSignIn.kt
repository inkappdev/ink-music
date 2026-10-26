package app.ink.music

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONTokener

/**
 * Signing in to YouTube Music, on Google's own page (darkened where Android can), on a screen of
 * its own: a WebView can't draw inside the library's frosted layers, and here the keyboard has
 * room. Old cookies go first; once the page lands on YouTube Music with a session, its cookies and
 * the page's own ids (visitor, account, client version) are taken, tried, and it closes.
 */
class TubeSignIn : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scope = rememberCoroutineScope()
            var web by remember { mutableStateOf<WebView?>(null) }
            var taken by remember { mutableStateOf(false) }
            var said by remember { mutableStateOf<String?>(null) }
            BackHandler { web?.takeIf { it.canGoBack() && !taken }?.goBack() ?: finish() }
            Column(Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("youtube music", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                    Word("close", pixel(12.sp, Ink.mid), touch = 10.dp) { finish() }
                }
                if (taken || said != null) Column(Modifier.padding(20.dp)) {
                    BasicText(if (said == null) "SIGNING IN" else "NOT SIGNED IN", style = headline(40.sp, if (said == null) Ink.mid else Ink.ink))
                    Spacer(Modifier.height(8.dp))
                    said?.let { BasicText(it, style = pixel(12.sp, Ink.mid)) }
                } else AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // as Chrome says itself, not "wv": Google turns a right password away in an app's WebView
                            settings.userAgentString = settings.userAgentString.replace("; wv)", ")").replace(Regex("Version/\\S+ "), "")
                            // its sign-in passes its cookies between accounts.google.com and youtube.com
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
                            setBackgroundColor(android.graphics.Color.BLACK)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    if (taken || !url.startsWith(InnerTube.BASE)) return
                                    val cookie = CookieManager.getInstance().getCookie(InnerTube.BASE) ?: return
                                    if ("SAPISID=" !in cookie) return
                                    taken = true
                                    view.evaluateJavascript(
                                        "(function(){try{return JSON.stringify([ytcfg.get('VISITOR_DATA'),ytcfg.get('DATASYNC_ID'),ytcfg.get('INNERTUBE_CLIENT_VERSION')])}catch(e){return '[]'}})()",
                                    ) { result ->
                                        val ids = runCatching { JSONArray(JSONTokener(result).nextValue() as String) }.getOrDefault(JSONArray())
                                        fun id(i: Int) = ids.optString(i).takeIf { it.isNotEmpty() && it != "null" }
                                        scope.launch {
                                            if (YouTubeAccount.signIn(this@TubeSignIn, cookie, id(0), id(1), id(2))) finish()
                                            else said = "youtube music didn't take it. close, and try again"
                                        }
                                    }
                                }
                            }
                            web = this
                            YouTubeAccount.forgetWeb { post { loadUrl(SIGN_IN) } }
                        }
                    },
                )
            }
        }
    }

    private companion object {
        /** Google's sign-in, then youtube.com's own sign-in step (its cookies), then YouTube Music. */
        const val SIGN_IN = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
    }
}
