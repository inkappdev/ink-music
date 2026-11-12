package app.ink.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.URL

/** A computer with ink music waiting for a sign-in on this wifi (`_inkmusic._tcp`). */
data class Computer(val name: String, val host: String, val port: Int)

/**
 * Pair a computer (the Mac app): ink music on it shows a code and waits; here its QR code is read by the camera
 * ([PairCamera], since 2026-11-12), or it's found on the wifi, picked, and its code typed; the two pair
 * ([Computers.pair]) only after the code matches the computer's own key, so it's that computer and no other, and this
 * phone's sign-in goes with it.
 */
@Composable
fun HandOverPage(pages: Pages) {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<Computer>() }
    var picked by remember { mutableStateOf<Computer?>(null) }
    var code by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    // read off the computer's QR code: no code to type, and the pairing goes on by itself
    var scanned by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // the camera (the computer's QR code) and, on Android 17, the wifi's devices: both asked for as the page opens
    val camera = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    fun seeing() = camera && ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var sees by remember { mutableStateOf(seeing()) }
    var reaches by remember { mutableStateOf(Server.localAllowed(ctx)) }
    // how often the camera's been asked for: turned down twice, Android won't ask again (11 and on)
    var askedCamera by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { sees = seeing(); reaches = Server.localAllowed(ctx) }
    LaunchedEffect(Unit) {
        val want = listOfNotNull(Manifest.permission.CAMERA.takeIf { camera && !sees }, Server.local?.takeIf { !reaches })
        if (want.isNotEmpty()) { if (camera && !sees) askedCamera++; ask.launch(want.toTypedArray()) }
    }
    // back from ink music's settings page with the camera allowed there
    LifecycleResumeEffect(Unit) { sees = seeing(); reaches = Server.localAllowed(ctx); onPauseOrDispose {} }
    DisposableEffect(reaches) {
        val stop = if (reaches) Computers.find(ctx) { c -> if (found.none { it.name == c.name }) found += c } else ({})
        onDispose { stop() }
    }
    fun pairBy(l: Handover.Link) {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        picked = Computer("your computer", l.hosts.first(), l.port); code = l.code; scanned = true
        sending = true; said = null
        scope.launch {
            said = withContext(Dispatchers.IO) { Computers.pair(ctx, l) }
            sending = false
        }
    }
    // opened by a scanned link (the phone's own camera): it pairs straight away
    val fromCamera by Computers.scanned.collectAsState()
    LaunchedEffect(fromCamera) { fromCamera?.let { Computers.scanned.value = null; pairBy(it) } }
    FrostedPage(onClose = { pages.close() }) { frost, top ->
        Column(
            Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
        ) {
            BasicText("SYNC WITH A COMPUTER", style = headline(40.sp))
            Spacer(Modifier.height(8.dp))
            BasicText("on the computer: ink music, settings, your phone, pair your phone, and point this at the code it shows " +
                "(on a tv: ink music shows a code as it opens). " +
                "paired, it's signed in to what this phone is, and the two keep in step on your wifi: what you've heard, what you love, " +
                "what you were playing", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(20.dp))
            val p = picked
            if (p == null) {
                // the camera first: the computer's QR code is the whole of it (2026-11-12)
                if (sees) {
                    PairCamera(Modifier.fillMaxWidth().aspectRatio(1f)) { pairBy(it) }
                    Spacer(Modifier.height(10.dp))
                    BasicText("the code on the computer, in the square", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(24.dp))
                } else if (camera) {
                    Word("SCAN THE CODE", headline(28.sp), touch = 8.dp) {
                        // turned down twice: Android won't ask again, so ink music's own page in its settings
                        if (askedCamera >= 2) {
                            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        } else { askedCamera++; ask.launch(arrayOf(Manifest.permission.CAMERA)) }
                    }
                    BasicText(if (askedCamera >= 2) "allow the camera there (permissions), and it reads the computer's code" else "with the camera: nothing to type",
                        style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(24.dp))
                }
                if (sees || camera) BasicText("or pick it here, and type its code", style = pixel(12.sp, Ink.mid))
                if (found.isEmpty()) BasicText(if (reaches) "looking on this wifi" else "ink music needs your ok to reach devices on your wifi", style = pixel(13.sp, Ink.mid))
                found.forEach { c -> Word(c.name.uppercase(), headline(28.sp), touch = 8.dp) { picked = c; said = null } }
                // not listed (a wifi that keeps devices apart, a firewall): its address, as the computer shows it
                Spacer(Modifier.height(28.dp))
                var typed by remember { mutableStateOf("") }
                Field("or its address, under the code on the computer", typed, { typed = it.trim() }, "192.168.1.20:52114", KeyboardType.Uri)
                Word("NEXT", headline(28.sp, if (typed.contains(':')) Ink.ink else Ink.mid), touch = 8.dp) {
                    val host = typed.substringBeforeLast(':'); val port = typed.substringAfterLast(':').toIntOrNull()
                    if (host.isNotEmpty() && port != null) { picked = Computer(host, host, port); said = null }
                }
            } else if (scanned) {
                // read off the screen: it pairs by itself, and what came of it is said under
                BasicText(if (sending) "PAIRING" else if (said?.startsWith("paired") == true) "PAIRED" else "NOT YET", style = headline(40.sp))
                Spacer(Modifier.height(8.dp))
                if (!sending) Word(if (said?.startsWith("paired") == true) "done" else "scan again", pixel(13.sp, Ink.mid), touch = 8.dp) {
                    if (said?.startsWith("paired") == true) pages.close() else { picked = null; scanned = false; code = ""; said = null }
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
    /** A computer's QR code read by the phone's own camera (its link opened ink music): the pairing page takes it. */
    val scanned = kotlinx.coroutines.flow.MutableStateFlow<Handover.Link?>(null)

    /** An intent that's a pairing link (https://inkhome.app/pair#..., or inkmusic://pair#... from the site's page): kept for the page. */
    fun take(i: android.content.Intent?): Boolean {
        val u = i?.data ?: return false
        if (i.action != android.content.Intent.ACTION_VIEW) return false
        val l = Handover.read("/pair#" + (u.fragment ?: return false)) ?: return false
        scanned.value = l
        return true
    }

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
    /**
     * Paired by the computer's QR code: each of its addresses asked at once (a laptop has several, and the wifi's needn't
     * be the first), the first whose key makes the code is the one; then paired as by hand. None reached: why, said.
     */
    suspend fun pair(ctx: Context, l: Handover.Link): String {
        val answers = coroutineScope {
            l.hosts.map { h ->
                async {
                    runCatching {
                        val hello = JSONObject(get(Computer(h, h, l.port), "/hello", connect = 4_000))
                        val code = Handover.code(Handover.decode(hello.getString("key"))).filter(Char::isDigit)
                        Computer(hello.optString("name").ifEmpty { h }, h, l.port) to (code == l.code)
                    }.onFailure { android.util.Log.d("ink-pair", "$h:${l.port}: $it") }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        val there = answers.firstOrNull { it.second }?.first
            ?: return if (answers.isNotEmpty()) "that code's gone: on the computer, pair your phone again, and scan the new one"
            else why(ctx, "the computer", l.hosts)
        return pair(ctx, there, l.code)
    }

    /**
     * Why the computer couldn't be reached, as far as the phone can tell: its own ok to reach the wifi (Android 17), a
     * VPN, no wifi, the two on different networks; else on the same one and kept out (the computer's firewall, or a wifi
     * that keeps its devices apart: school, work, guest), where the phone's own hotspot is the way round.
     */
    fun why(ctx: Context, name: String, hosts: List<String>): String {
        val n = name.lowercase()
        if (!Server.localAllowed(ctx)) return "ink music needs your ok to reach devices on your wifi: allow it (ink music's settings, permissions), and try again"
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val vpn = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }.getOrDefault(false)
        if (vpn) return "a vpn on this phone keeps it from $n: turn it off while you pair, and try again"
        val mine = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }.filter { (it.address as? Inet4Address)?.isSiteLocalAddress == true }
        }.getOrDefault(emptyList())
        if (mine.isEmpty()) return "this phone isn't on a wifi: join the one $n is on, and try again"
        val theirs = hosts.mapNotNull { runCatching { InetAddress.getByName(it) as? Inet4Address }.getOrNull() }
        if (theirs.none { a -> mine.any { near(it, a) } })
            return "this phone and $n are on different networks (this phone ${mine.first().address.hostAddress}, $n ${hosts.firstOrNull().orEmpty()}): " +
                "put both on the same wifi, and try again"
        return "$n is on this wifi but kept this phone out. on windows: LET IT IN, under the code on the computer; on a mac: allow ink music " +
            "on the local network (system settings, privacy and security). if that's done, this wifi keeps its devices apart (school, work " +
            "and guest wifi often do): turn on this phone's hotspot, join it on the computer, and pair again"
    }

    /** `a` on the same network as this phone's address `mine` (its prefix: 192.168.1.x on a /24). */
    private fun near(mine: InterfaceAddress, a: Inet4Address): Boolean {
        val bits = mine.networkPrefixLength.toInt().coerceIn(8, 32)
        val mask = -1 shl (32 - bits)
        fun int(x: InetAddress) = x.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        return (int(mine.address) and mask) == (int(a) and mask)
    }

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
        // where it's called for sync, as it told (a computer from before says nothing: found on the wifi then), so a phone
        // that got here by the QR code, the wifi not showing it, is in step at once
        val at = answer.optInt("sync").takeIf { it > 0 }?.let { "${to.host}:$it" }.orEmpty()
        SyncPhone.paired(Sync.Peer(answer.optString("id"), name, Handover.kept(mine, theirs), seen = System.currentTimeMillis(), at = at))
        "paired with ${name.lowercase()}" + if (auth != null) ", and signed in to youtube music there" else ""
    }.getOrElse {
        // not let in: the way through said, as far as the phone can tell (Computers.why), rather than the socket's words
        if (it is java.net.SocketTimeoutException || it is java.net.ConnectException || it is java.net.NoRouteToHostException)
            why(ctx, to.name, listOf(to.host))
        else "couldn't reach ${to.name.lowercase()}: ${it.message?.lowercase()}"
    }

    private fun get(to: Computer, path: String, connect: Int = 8_000): String {
        val c = URL("http://${to.host}:${to.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = connect; c.readTimeout = 15_000
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
