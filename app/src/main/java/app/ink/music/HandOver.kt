package app.ink.music

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A computer with ink music waiting for a sign-in on this wifi (`_inkmusic._tcp`). */
data class Computer(val name: String, val host: String, val port: Int)

/**
 * Pair a computer (the Mac app): ink music on it shows a code and waits; here it's found on the wifi, picked, and its
 * code typed; the two pair ([Computers.pair]) only after the code matches the computer's own key, so it's that
 * computer and no other, and this phone's sign-in goes with it.
 */
@Composable
fun HandOverPage(pages: Pages) {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<Computer>() }
    var picked by remember { mutableStateOf<Computer?>(null) }
    var code by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val stop = Computers.find(ctx) { c -> if (found.none { it.name == c.name }) found += c }
        onDispose { stop() }
    }
    FrostedPage(onClose = { pages.close() }) { frost, top ->
        Column(
            Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
        ) {
            BasicText("SYNC WITH A COMPUTER", style = headline(40.sp))
            Spacer(Modifier.height(8.dp))
            BasicText("on the computer: ink music, settings, your phone, pair your phone. it shows a code; on the same wifi, it's listed here. " +
                "paired, it's signed in to what this phone is, and the two keep in step on your wifi: what you've heard, what you love, " +
                "what you were playing", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(20.dp))
            val p = picked
            if (p == null) {
                if (found.isEmpty()) BasicText("looking on this wifi", style = pixel(13.sp, Ink.mid))
                found.forEach { c -> Word(c.name.uppercase(), headline(28.sp), touch = 8.dp) { picked = c; said = null } }
                // not listed (a wifi that keeps devices apart, a firewall): its address, as the computer shows it
                Spacer(Modifier.height(28.dp))
                var typed by remember { mutableStateOf("") }
                Field("or its address, under the code on the computer", typed, { typed = it.trim() }, "192.168.1.20:52114", KeyboardType.Uri)
                Word("NEXT", headline(28.sp, if (typed.contains(':')) Ink.ink else Ink.mid), touch = 8.dp) {
                    val host = typed.substringBeforeLast(':'); val port = typed.substringAfterLast(':').toIntOrNull()
                    if (host.isNotEmpty() && port != null) { picked = Computer(host, host, port); said = null }
                }
            } else {
                BasicText(p.name.uppercase(), style = headline(28.sp))
                Spacer(Modifier.height(14.dp))
                Field("the code on its screen", code, { code = it.filter(Char::isDigit).take(6) }, "123 456", KeyboardType.Number)
                Word(if (sending) "PAIRING" else "PAIR", headline(40.sp, if (sending || code.length < 6) Ink.mid else Ink.ink), touch = 10.dp) {
                    if (sending || code.length < 6) return@Word
                    sending = true; said = null
                    scope.launch {
                        said = withContext(Dispatchers.IO) { Computers.pair(ctx, p, code) }
                        sending = false
                    }
                }
                Spacer(Modifier.height(8.dp))
                Word("another computer", pixel(13.sp, Ink.mid), touch = 8.dp) { picked = null; code = ""; said = null }
            }
            said?.let { Spacer(Modifier.height(14.dp)); BasicText(it, style = pixel(13.sp)) }
        }
    }
}

object Computers {
    /** Finding computers waiting on this wifi; the returned function stops looking. */
    fun find(ctx: Context, onFound: (Computer) -> Unit): () -> Unit {
        val m = ctx.getSystemService(NsdManager::class.java) ?: return {}
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                m.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = (if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else null) ?: @Suppress("DEPRECATION") info.host ?: return
                        main.post { onFound(Computer(info.serviceName, host.hostAddress ?: return@post, info.port)) }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        try { m.discoverServices(Handover.SERVICE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { return {} }
        return { runCatching { m.stopServiceDiscovery(l) } }
    }

    /**
     * The code checked against the computer's key, then the two paired ([Sync]): this phone's id and name, and its
     * YouTube Music sign-in, sent sealed; each works out the key the two keep. A computer from before pairing takes the
     * sign-in alone. What happened, said.
     */
    fun pair(ctx: Context, to: Computer, code: String): String = runCatching {
        val hello = JSONObject(get(to, "/hello"))
        val theirs = Handover.decode(hello.getString("key"))
        // it's that computer: the code it shows was made from this key
        if (Handover.code(theirs).filter(Char::isDigit) != code) return "that isn't the code on ${to.name.lowercase()}: look again"
        SyncPhone.start(ctx)
        val auth = YouTubeAccount.handover(ctx)
        val mine = Handover.keys()
        val hi = JSONObject().put("id", SyncPhone.kept.id()).put("name", SyncPhone.deviceName(ctx))
        auth?.let { runCatching { hi.put("youtube", JSONObject(it)) } }
        val (iv, data) = Handover.seal(mine, theirs, hi.toString())
        val answer = JSONObject(post(to, "/pair", JSONObject().put("key", Handover.encode(mine.public)).put("iv", iv).put("data", data).toString()))
        if (!answer.has("ok")) {
            // a computer from before pairing: the sign-in, as it was
            auth ?: return "update ink music on ${to.name.lowercase()} to pair with it"
            val (iv2, data2) = Handover.seal(mine, theirs, auth)
            val old = JSONObject(post(to, "/hand", JSONObject().put("key", Handover.encode(mine.public)).put("iv", iv2).put("data", data2).toString()))
            return if (old.optBoolean("ok")) "signed in on ${to.name.lowercase()}" else "it didn't take: ${old.optString("why").lowercase()}"
        }
        if (!answer.optBoolean("ok")) return "it didn't take: ${answer.optString("why").lowercase()}"
        val name = answer.optString("name").ifEmpty { to.name }
        SyncPhone.paired(Sync.Peer(answer.optString("id"), name, Handover.kept(mine, theirs), seen = System.currentTimeMillis(), at = ""))
        "paired with ${name.lowercase()}" + if (auth != null) ", and signed in to youtube music there" else ""
    }.getOrElse { "couldn't reach ${to.name.lowercase()}: ${it.message?.lowercase()}" }

    private fun get(to: Computer, path: String): String {
        val c = URL("http://${to.host}:${to.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 15_000
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun post(to: Computer, path: String, body: String): String {
        val c = URL("http://${to.host}:${to.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 30_000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            (if (c.responseCode >= 400) c.errorStream else c.inputStream).bufferedReader().readText()
        } finally { c.disconnect() }
    }
}
