package app.ink.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket

/** What this device is called in what's shown: "phone", or "tv" on a TV ("kept on this tv", "this tv, its own sound"). */
object Device {
    @Volatile var tv = false
    val word: String get() = if (tv) "tv" else "phone"
}

/** On a TV (Android TV / Google TV): always sideways, steered by a remote. */
fun isTv(c: Context) = c.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

@Composable
fun onTv() = isTv(LocalContext.current)

/**
 * On a TV, a screen that lights its own controls ([tvLit]): the player, whose look behind showed the screen-wide dim's
 * lit place as a lighter square over the controls (2026-11-03: harsh, and out of place). While one is up the dim is off.
 */
val LocalTvOwnLight = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Selection as brightness on the control itself, inside a screen that lights its own ([LocalTvOwnLight]): at 55% till
 * the remote is on it, then whole, on ink's curve. No square, nothing drawn round it. Before the control's own
 * clickable, so it hears that one's focus.
 */
@Composable
fun Modifier.tvLit(): Modifier {
    if (!LocalTvOwnLight.current) return this
    var on by remember { mutableStateOf(false) }
    val a by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0.55f, tween(Motion.ENTER, easing = Motion.expo), label = "lit")
    return this.graphicsLayer { alpha = a }.onFocusChanged { on = it.hasFocus }
}

/** The remote with nothing focused (a page just opened, focus let go): the first thing on screen takes it. */
object TvKeys {
    /** Screens lighting their own controls, up now ([LocalTvOwnLight]): the screen-wide dim waits. */
    var ownLight by mutableStateOf(0)
    var wake: (() -> Boolean)? = null
    /** Whether anything has the remote now. */
    @Volatile var lit = false
    /**
     * The library's top row on a TV, in order (the place's word, "now playing", find): the remote steps along it by
     * these, left and right, and stays on it going up. Compose's own search went from the place's word down to a cover
     * peeking in at the screen's left edge, and up from the right of the page past "now playing" (2026-11-03).
     */
    val topRow = arrayOfNulls<androidx.compose.ui.focus.FocusRequester>(3)
    /** Which of the top row has the remote (-1: none). */
    var topAt by mutableStateOf(-1)
}

/**
 * Where the remote is, on a TV: selection is brightness, as in ink tv (its list's lit word, the reel at 55% till the
 * cursor lands there). What has focus stays as it is; everything else steps down to 55%, and the lit place glides to
 * the next on ink's curve. One treatment for every screen (each word and cover is focusable already, being
 * clickable; what was missing was seeing which), drawn over everything and taking no touches. Elsewhere the content
 * alone. A line under it was tried first (2026-11-03) and turned down: a rule, and a giant one under the place's word.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocus(content: @Composable () -> Unit) {
    if (!onTv()) { content(); return }
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var at by remember { mutableStateOf<Rect?>(null) }
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    DisposableEffect(fm) {
        TvKeys.wake = { fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) || fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }
        onDispose { TvKeys.wake = null }
    }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { root = it }
            // up or down finding nothing goes on in order instead: Compose's search never crossed from a bar drawn
            // over a page (the place's word, the welcome's DONE) into the page under it (the onn, 2026-11-03)
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val at = TvKeys.topAt
                fun step(by: Int): Boolean {
                    var i = at + by
                    while (i in TvKeys.topRow.indices) {
                        TvKeys.topRow[i]?.let { r -> if (runCatching { r.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false)) return true }
                        i += by
                    }
                    return true   // the row's end: stays
                }
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> if (at >= 0) step(-1) else false
                    androidx.compose.ui.input.key.Key.DirectionRight -> if (at >= 0) step(1) else false
                    androidx.compose.ui.input.key.Key.DirectionDown ->
                        fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) || fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
                    // on the top row, up stays; up from the first row of covers found nothing (measured): the place's
                    // word; a page with no top row of its own goes on to the previous in order
                    androidx.compose.ui.input.key.Key.DirectionUp -> at >= 0 || fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Up) ||
                        TvKeys.topRow[0]?.let { runCatching { it.requestFocus(androidx.compose.ui.focus.FocusDirection.Up) }.getOrDefault(false) } == true ||
                        fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Previous)
                    else -> false
                }
            }
            .onFocusedBoundsChanged { c ->
                at = c?.takeIf { it.isAttached }?.let { f -> root?.takeIf { it.isAttached }?.localBoundingBoxOf(f, false) }
                TvKeys.lit = at != null
            },
    ) {
        content()
        val r = at
        val dim by androidx.compose.animation.core.animateFloatAsState(if (r != null && r.width > 0f && TvKeys.ownLight == 0) 0.45f else 0f,
            tween(Motion.ENTER, easing = Motion.expo), label = "dim")
        if (r != null && r.width > 0f || dim > 0f) {
            val moving by animateRectAsState(r ?: Rect.Zero, if (Moves.less.value) snap() else tween(Motion.ENTER, easing = Motion.expo), label = "focus")
            Canvas(Modifier.fillMaxSize()) {
                // the screen less the lit place (a little room round it, in the gaps between things), at 55%
                val lit = moving.inflate(6.dp.toPx())
                val rest = androidx.compose.ui.graphics.Path().apply {
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size)); addRect(lit)
                }
                drawPath(rest, androidx.compose.ui.graphics.Color.Black.copy(alpha = dim))
            }
        }
    }
}

/**
 * On a TV, something opened over what's here (a page, the menu, the queue) or closed from over it: focus let go,
 * then the first thing in front takes it once it has come in. Without it the remote stayed on what was covered.
 */
@Composable
fun TvRefocus(vararg keys: Any?, after: Long = Motion.ENTER + 80L, into: (() -> TvStart?)? = null) {
    if (!onTv()) return
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(*keys) {
        fm.clearFocus(force = true)
        kotlinx.coroutines.delay(after)
        // something in what came in took the remote already (a page's own start, the player's play): left there
        if (TvKeys.lit) return@LaunchedEffect
        // what came in, by name (its first word or cover takes it), else the first thing found from the top; a
        // search from nothing found the mini player first over a page that runs under it (settings, 2026-11-03)
        if (into == null) TvKeys.wake?.invoke()
        else into()?.enter(fm)
    }
}

/**
 * Where the remote starts in something that has just come in (a page, the menu, the library again): the thing in it
 * meant to be first ([tvFirst]: a page's play, the menu's place, settings' first part) claims it. Compose's own ways in
 * (a group asked for focus, Enter, Next, Down from nothing) all missed, landing on the mini player or nowhere (the
 * onn, 2026-11-03); with nothing claimed, the search from nothing is what's left.
 */
class TvStart {
    internal val asked = androidx.compose.ui.focus.FocusRequester()
    internal var open by mutableStateOf(false)
    internal var first: androidx.compose.ui.focus.FocusRequester? = null

    suspend fun enter(fm: androidx.compose.ui.focus.FocusManager) {
        val claimed = first?.let { runCatching { it.requestFocus(); true }.getOrDefault(false) } == true
        if (!claimed) TvKeys.wake?.invoke()
    }
}

/** The [TvStart] what's composed here belongs to, for [tvFirst] to claim. */
val LocalTvStart = androidx.compose.runtime.staticCompositionLocalOf<TvStart?> { null }

/** On a TV, the first thing the remote is on when what it's in comes in (the first one composed claims it). */
@Composable
fun Modifier.tvFirst(): Modifier {
    val start = LocalTvStart.current ?: return this
    if (!Device.tv) return this
    val r = remember { androidx.compose.ui.focus.FocusRequester() }
    DisposableEffect(start) {
        if (start.first == null) start.first = r
        onDispose { if (start.first === r) start.first = null }
    }
    return focusRequester(r)
}

@Composable
fun rememberTvStart() = remember { TvStart() }

/** Where a [TvStart] begins: a group, focusable only while it passes focus in. */
fun Modifier.tvStart(start: TvStart): Modifier =
    focusRequester(start.asked).focusProperties { canFocus = start.open }.focusTarget()

/**
 * A tap catcher (a page's "nothing under it takes a tap", "a tap off them folds the options"): never the remote's
 * focus. One across a whole page took focus as one big lit thing, and its words under it went unreachable.
 */
fun Modifier.noFocus(): Modifier = focusProperties { canFocus = false }

/** One of the library's top row ([TvKeys.topRow]): its place in the row, and whether it has the remote. */
@Composable
fun Modifier.topRow(i: Int): Modifier {
    if (!Device.tv) return this
    val r = remember { androidx.compose.ui.focus.FocusRequester() }
    DisposableEffect(r) {
        TvKeys.topRow[i] = r
        onDispose { if (TvKeys.topRow[i] === r) TvKeys.topRow[i] = null; if (TvKeys.topAt == i) TvKeys.topAt = -1 }
    }
    return focusRequester(r).onFocusChanged { f -> if (f.isFocused) TvKeys.topAt = i else if (TvKeys.topAt == i) TvKeys.topAt = -1 }
}

/** On a TV, what's under a page (the library) takes no focus while covered: the remote stays on what's in front. */
fun Modifier.covered(covered: Boolean, start: TvStart? = null): Modifier =
    (if (start != null) focusRequester(start.asked) else this)
        .focusProperties { canFocus = start?.open == true; onEnter = { if (covered) cancelFocusChange() } }.focusTarget()

/**
 * The TV as the phone's "computer" ([Sync]): the phone pairs with it from "sync with a computer" (the TV shows the
 * code, as the computer does: [Handover]), and from then on calls it on the wifi and hands it what it has: your server,
 * YouTube Music, ListenBrainz and Last.fm signed in, what you've heard and love, what you were playing. Nothing typed
 * with a remote. Phones from before the TV need nothing new: to them it's a computer.
 */
object TvHub {
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** While waiting for a phone: the code to type there; null when not. */
    val code = MutableStateFlow<String?>(null)
    /** Where the phone can reach it by hand, if the wifi doesn't list it ("192.168.1.128:41234"). */
    val at = MutableStateFlow<String?>(null)
    /** What happened, said ("paired with ..."). */
    val said = MutableStateFlow<String?>(null)
    private var pairing: ServerSocket? = null
    private var pairAd: NsdManager.RegistrationListener? = null
    private var syncing: ServerSocket? = null
    private var syncAd: NsdManager.RegistrationListener? = null
    /** Woken when the TV carries on with what a phone plays, so the phone's waiting call answers at once. */
    private val handing = Object()

    /**
     * The TV as the phone lists it: "ink music on" its own name (settings, device). Not the name alone: the TV's own
     * services are announced by it, and Android 14 refuses a second under the same name (the onn, 2026-11-03).
     */
    val name: String get() = "ink music on " + (app?.let { SyncPhone.deviceName(it) } ?: "this tv").lowercase()

    /** Opened on a TV: paired phones listened for. */
    fun start(c: Context) {
        if (app == null) { app = c.applicationContext; SyncPhone.start(c); Everywhere.start(c) }
        if (SyncPhone.kept.peers().isNotEmpty()) listen()
    }

    /** The TV carried on with a phone's playing ([SyncPhone.take]): its waiting call told now. */
    fun nudge() = synchronized(handing) { handing.notifyAll() }

    /** A press made here for a phone's player ("play", "pause", "next", "previous", "seek"), given on its waiting call. */
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** A phone's playing steered from here ([Everywhere.steer]): told it at once, on the call it's waiting on. */
    fun ask(peer: String, what: String, ms: Long) { asked[peer] = what to ms; nudge() }

    /** Ready for a phone: a key and its code shown, announced on the wifi, till [stopAwait]. */
    @Synchronized fun await() {
        val c = app ?: return
        if (pairing != null) return
        said.value = null
        val keys = Handover.keys()
        code.value = Handover.code(keys.public)
        val s = runCatching { ServerSocket(0) }.getOrNull() ?: return
        pairing = s
        Thread({
            while (!s.isClosed) {
                val k = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { serve(k, 64 * 1024) { method, path, body -> pair(method, path, body, keys) } }
                runCatching { k.close() }
            }
        }, "tv-pair").apply { isDaemon = true }.start()
        at.value = address(c)?.let { "$it:${s.localPort}" }
        pairAd = announce(c, Handover.SERVICE, s.localPort)
    }

    @Synchronized fun stopAwait() {
        runCatching { pairing?.close() }; pairing = null
        unannounce(pairAd); pairAd = null
        code.value = null; at.value = null
    }

    fun forget(id: String) = SyncPhone.forget(id)

    /** The pairing's two asks: GET /hello (the key and the name), POST /pair (who the phone is, sealed). */
    private fun pair(method: String, path: String, body: String, keys: java.security.KeyPair): Pair<Int, String> = when (method to path) {
        "GET" to "/hello" -> 200 to JSONObject().put("key", Handover.encode(keys.public)).put("name", name).put("app", "ink music").toString()
        "POST" to "/pair" -> runCatching {
            val o = JSONObject(body)
            val theirs = Handover.decode(o.getString("key"))
            val hello = JSONObject(runCatching { Handover.open(keys, theirs, o.getString("iv"), o.getString("data")) }
                .getOrElse { error("it couldn't be opened here, so it wasn't for this tv") })
            val phone = hello.optString("name", "your phone")
            SyncPhone.paired(Sync.Peer(hello.getString("id"), phone, Handover.kept(keys, theirs), seen = System.currentTimeMillis(), at = "paired"))
            // listening before the phone's first call, which comes the moment it has the answer
            listen()
            hello.optJSONObject("youtube")?.let { a -> scope.launch { app?.let { YouTubeAccount.take(it, a) } } }
            said.value = "paired with ${phone.lowercase()}. signing in to what it is"
            scope.launch { kotlinx.coroutines.delay(500); stopAwait() }
            200 to JSONObject().put("ok", true).put("name", name).put("id", SyncPhone.kept.id()).toString()
        }.getOrElse { e -> said.value = "that didn't work: ${e.message?.lowercase()}"; 400 to JSONObject().put("ok", false).put("why", e.message.orEmpty()).toString() }
        else -> 404 to "{}"
    }

    /** Listening for paired phones' calls ([Sync.PORT], announced as [Sync.SERVICE]). */
    @Synchronized private fun listen() {
        val c = app ?: return
        if (syncing != null) return
        val s = runCatching { ServerSocket(Sync.PORT) }.getOrNull() ?: runCatching { ServerSocket(0) }.getOrNull() ?: return
        syncing = s
        Thread({
            while (!s.isClosed) {
                val k = runCatching { s.accept() }.getOrNull() ?: break
                scope.launch {
                    runCatching { serve(k, 64 shl 20) { method, path, body -> sync(method, path, body, k) } }
                        .onFailure { android.util.Log.d("ink-sync", "tv: $it") }
                    runCatching { k.close() }
                }
            }
        }, "tv-sync").apply { isDaemon = true }.start()
        syncAd = announce(c, Sync.SERVICE, s.localPort)
    }

    /** A phone's call: in step (`/sync`), what it plays now (`/now`), or waiting to be told the TV carried on with it (`/wait`). */
    private fun sync(method: String, path: String, body: String, k: Socket): Pair<Int, String> {
        val kept = SyncPhone.kept
        if (method != "POST") return 404 to "{}"
        return when (path) {
            "/sync" -> runCatching {
                val side = SyncPhone.hubSide() ?: return 503 to "{}"
                val r = Sync.answer(side, kept, body, k.inetAddress.hostAddress + ":" + (syncing?.localPort ?: Sync.PORT))
                SyncPhone.peers.value = kept.peers()
                android.util.Log.d("ink-sync", "tv: in step with ${k.inetAddress.hostAddress}: " +
                    Sync.SIGNINS.entries.joinToString { (what, f) -> what + if (java.io.File(side.dir, f).exists()) " in" else " out" })
                if (r == null) 403 to "{}" else 200 to r
            }.getOrElse { android.util.Log.d("ink-sync", "tv sync: $it"); 400 to "{}" }
            // what another plays now (a phone's player, a computer), as it changes: shown here, and kept for a
            // phone's /watch
            "/now" -> runCatching {
                val (peer, m) = Sync.unsealed(kept, body) ?: return 403 to "{}"
                // (the mini player's place shows it, live: no "continue here" card on listen as well)
                Everywhere.household.put(peer.id, peer.name.removePrefix("ink music on "), m.optJSONObject("now"))
                200 to Sync.reply(peer, JSONObject().put("handed", kept.handedAt(peer.id)))
            }.getOrElse { 400 to "{}" }
            // a phone waiting for the next change anywhere: answered the moment one comes (or in 25 s, unchanged)
            "/watch" -> runCatching {
                val (peer, m) = Sync.unsealed(kept, body) ?: return 403 to "{}"
                val v = Everywhere.household.await(m.optLong("since"), 25_000)
                200 to Sync.reply(peer, JSONObject().put("version", v).put("devices", Everywhere.household.snapshot()))
            }.getOrElse { 400 to "{}" }
            // steered from another: done here
            "/do" -> runCatching {
                val (peer, m) = Sync.unsealed(kept, body) ?: return 403 to "{}"
                val what = m.optString("do"); val ms = m.optLong("ms")
                kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                    val on = Playback.now.value?.playing == true
                    when (what) {
                        "play" -> if (!on) Playback.toggle()
                        "pause" -> if (on) Playback.toggle()
                        "next" -> Playback.next()
                        "previous" -> Playback.previous()
                        "seek" -> Playback.seek(ms)
                    }
                }
                Everywhere.publish()
                200 to Sync.reply(peer, JSONObject().put("ok", true))
            }.getOrElse { 400 to "{}" }
            "/wait" -> runCatching {
                val (peer, m) = Sync.unsealed(kept, body) ?: return 403 to "{}"
                val since = m.optLong("since")
                val until = System.currentTimeMillis() + 25_000
                synchronized(handing) {
                    while (kept.handedAt(peer.id) <= since && asked[peer.id] == null && System.currentTimeMillis() < until) handing.wait(maxOf(1L, until - System.currentTimeMillis()))
                }
                val d = asked.remove(peer.id)
                200 to Sync.reply(peer, JSONObject().put("handed", kept.handedAt(peer.id)).put("do", d?.first ?: "").put("ms", d?.second ?: 0L))
            }.getOrElse { 400 to "{}" }
            else -> 404 to "{}"
        }
    }

    /** One plain HTTP request read off the socket, answered with what `answer` gives (status, JSON). */
    private fun serve(k: Socket, most: Int, answer: (String, String, String) -> Pair<Int, String>) {
        k.soTimeout = 30_000
        val input = k.getInputStream().buffered()
        fun line(): String { val b = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
        val first = line().split(" ")
        var length = 0
        while (true) { val h = line(); if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(":").trim().toIntOrNull() ?: 0 }
        val body = String(input.readNBytes(length.coerceIn(0, most)))
        val (status, reply) = answer(first.getOrNull(0).orEmpty(), first.getOrNull(1).orEmpty(), body)
        val out = reply.toByteArray()
        k.getOutputStream().apply {
            write("HTTP/1.1 $status ${if (status == 200) "OK" else "No"}\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(out); flush()
        }
    }

    private fun announce(c: Context, type: String, port: Int): NsdManager.RegistrationListener? {
        val m = c.getSystemService(NsdManager::class.java) ?: return null
        val info = NsdServiceInfo().apply { serviceName = name; serviceType = type; this.port = port }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { android.util.Log.d("ink-sync", "tv: $type not announced ($code)") }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        return runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); l }.getOrNull()
    }

    private fun unannounce(l: NsdManager.RegistrationListener?) {
        val c = app ?: return
        l ?: return
        runCatching { c.getSystemService(NsdManager::class.java)?.unregisterService(l) }
    }

    /** The TV's address on the wifi (IPv4), for a phone that doesn't see it listed. */
    private fun address(c: Context): String? {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}

/**
 * Signing a TV in from the phone: what to do there, and the code big enough to read across the room; waiting (and
 * announced on the wifi) while it's shown.
 */
@Composable
fun PairPhone() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) { TvHub.start(ctx); TvHub.await(); onDispose { TvHub.stopAwait() } }
    val code by TvHub.code.collectAsState()
    val at by TvHub.at.collectAsState()
    val said by TvHub.said.collectAsState()
    Column {
        BasicText("on your phone: ink music, settings, your music, sync with a computer. pick ${TvHub.name.lowercase()}, then type this code there",
            style = pixel(13.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(code ?: "", style = headline(72.sp))
            Spacer(Modifier.width(28.dp))
            at?.let { BasicText("not listed there? this tv's address: $it", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(bottom = 12.dp)) }
        }
        said?.let { Spacer(Modifier.height(8.dp)); BasicText(it, style = pixel(13.sp)) }
    }
}

/** Settings on a TV: the phones it's paired with, how lately in step, and pairing another. */
@Composable
fun TvPhoneSetting() {
    val ctx = LocalContext.current
    SyncPhone.start(ctx)
    val peers by SyncPhone.peers.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    BasicText("YOUR PHONE", style = headline(32.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("ink music on your phone signs this tv in to everything it is (your server, youtube music, listenbrainz, last.fm), " +
        "and the two keep in step on your wifi: what you've heard, what you love, what you were playing", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(10.dp))
    val tick by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
    peers.forEach { p ->
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText(p.name.uppercase(), style = headline(22.sp))
                BasicText(if (p.seen == 0L) "paired, not in step yet" else "in step " + ago(tick - p.seen), style = pixel(12.sp, Ink.mid))
            }
            Word("forget", pixel(13.sp, Ink.mid), touch = 8.dp) { TvHub.forget(p.id) }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (pairing) PairPhone()
    else Word(if (peers.isEmpty()) "SIGN IN FROM YOUR PHONE" else "PAIR ANOTHER PHONE", headline(28.sp), touch = 10.dp) { pairing = true }
}
