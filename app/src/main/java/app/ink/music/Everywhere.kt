package app.ink.music

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What plays on every ink device, seen from this one (2026-11-04): whatever plays on the phone, the TV or a computer
 * shows on the others at once, its line moving with it, steered from any of them, carried on with here at its very
 * second ("play here") while the other stops, as Spotify's devices are. No cloud: the home wifi, sealed as [Sync] is.
 *
 * Who tells whom: a device that listens (the TV, a computer) keeps what each one told it ([Sync.Household]); the
 * phone's player tells each of them as it changes ([Live]), the TV and a computer tell each other (the phone
 * introduced them, [Sync.Side.intros]), and the phone, while ink music is open, waits on each for the next change
 * (`/watch`), so it hears at once without listening itself (its battery).
 */
object Everywhere {
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** What each device plays, as heard here. */
    val household = Sync.Household()

    /** Another device's playing, to show here: its queue as songs here, where in it, how far, heard when (this clock). */
    data class Remote(
        val peer: String, val name: String, val device: String, val steerable: Boolean,
        val songs: List<Song>, val index: Int, val position: Long, val heard: Long, val playing: Boolean, val told: Long = 0L,
    ) {
        val song: Song get() = songs[index]
        /** The song's length: its own, else what the player playing it says (a YouTube record's songs come without). */
        val length: Long get() = song.duration.takeIf { it > 0 } ?: told
        /** Where it is now: where it was when heard, and on since while it plays (to the song's end at most). */
        fun positionNow(): Long =
            if (!playing) position else (position + System.currentTimeMillis() - heard).coerceAtMost(length.takeIf { it > 0 } ?: Long.MAX_VALUE)
        /** Where it plays, as said under it: the speaker it's on, else the device ("on living room tv"). */
        val where: String get() = (device.takeIf { it.isNotEmpty() } ?: name).lowercase()
    }

    /**
     * The playing elsewhere to show: the latest heard of those playing; else one paused there in the last half hour
     * (to start again from here, as Spotify's devices do); or null.
     */
    val remote = MutableStateFlow<Remote?>(null)

    fun start(c: Context) {
        if (app != null) return
        app = c.applicationContext
        SyncPhone.start(c)
        household.changed = { remote.value = pick() }
        if (isTv(c)) publishing()
    }

    private fun self(): String? = runCatching { SyncPhone.kept.id() }.getOrNull()

    private fun pick(): Remote? {
        val c = app ?: return null
        val me = self()
        val peers = SyncPhone.kept.peers().associateBy { it.id }
        val others = household.entries().filter { it.optString("id") != me && it.optJSONObject("now")?.optJSONArray("queue") != null }
        val e = others.filter { it.optJSONObject("now")?.optBoolean("playing") == true }.maxByOrNull { it.optLong("heard") }
            ?: others.filter { System.currentTimeMillis() - it.optLong("heard") < 30 * 60_000L }.maxByOrNull { it.optLong("heard") }
            ?: return null
        val now = e.getJSONObject("now")
        val q = now.optJSONArray("queue") ?: return null
        val index = now.optInt("index")
        val songs = ArrayList<Song>(); var at = -1
        for (i in 0 until q.length()) {
            val s = q.optJSONObject(i)?.let(SyncPhone::resolve) ?: continue
            if (i == index) at = songs.size
            songs += s
        }
        if (songs.isEmpty() || at < 0) return null
        val id = e.optString("id")
        // steered directly where it's a peer of this one (a phone's are all devices that listen; a TV's phones
        // through their waiting call)
        val steerable = peers[id] != null
        return Remote(id, e.optString("name"), now.optString("device"), steerable, songs, at, now.optLong("position"), e.optLong("heard"), now.optBoolean("playing"), now.optLong("length"))
    }

    // ---- a phone: waiting on each device that listens, while ink music is open ----

    private var watching: Job? = null

    /** ink music opened on a phone: in step with what plays elsewhere, each change heard as it happens. */
    fun watch() {
        val c = app ?: return
        if (isTv(c) || watching?.isActive == true) return
        watching = scope.launch {
            while (true) {
                val hubs = SyncPhone.kept.peers().filter { ':' in it.at }
                if (hubs.isEmpty()) { delay(30_000); continue }
                coroutineScope { hubs.forEach { p -> launch { watchOne(p) } } }
                delay(10_000)
            }
        }
    }

    /** ink music closed: no more waiting (its own player still tells the others, [Live]). */
    fun unwatch() { watching?.cancel(); watching = null }

    private suspend fun watchOne(p: Sync.Peer) {
        var since = 0L
        while (true) {
            val (code, r) = post(p.at, "/watch", Sync.sealed(SyncPhone.kept, p, JSONObject().put("since", since)), 35_000)
            // a computer from before this (no /watch): asked again much later, not every few seconds
            if (code == 404) { delay(10 * 60_000L); return }
            if (r == null) return
            val m = runCatching { Sync.replied(p, r) }.getOrNull() ?: return
            household.takeSnapshot(m.optJSONArray("devices") ?: JSONArray())
            since = m.optLong("version")
        }
    }

    // ---- a TV: what it plays, told to the others ----

    private fun publishing() {
        scope.launch {
            Playback.now.map { it?.song?.id to it?.playing }.distinctUntilChanged().collect { publish() }
        }
        // while it plays, told again now and then: a seek, a slow clock, all set right
        scope.launch {
            while (true) {
                delay(15_000)
                if (Playback.now.value?.playing == true) publish()
            }
        }
    }

    /** What plays here now, told to the household here and to every device that listens. */
    fun publish() {
        val c = app ?: return
        scope.launch {
            val me = self() ?: return@launch
            val h = withContext(Dispatchers.Main) { runCatching { Playback.handover() }.getOrNull() }
            val n = Playback.now.value
            val now = if (h == null || n == null) null else {
                val (songs, index, position) = h
                val from = (index - 50).coerceAtLeast(0)
                Sync.nowOf(JSONArray(songs.subList(from, minOf(songs.size, index + 250)).map(SyncPhone::wire)), index - from,
                    position, n.playing, "", System.currentTimeMillis()).put("sent", System.currentTimeMillis()).put("length", n.duration)
            }
            household.put(me, SyncPhone.deviceName(c), now)
            for (p in SyncPhone.kept.peers().filter { it.hub && ':' in it.at }) {
                post(p.at, "/now", Sync.sealed(SyncPhone.kept, p, JSONObject().put("now", now ?: JSONObject.NULL)), 4_000)
            }
        }
    }

    // ---- steering another, and carrying on with it here ----

    /** Played, paused, on, back, or to a place ("seek" and `ms`) there. */
    fun steer(r: Remote, what: String, ms: Long = 0L) {
        val c = app ?: return
        scope.launch {
            val p = SyncPhone.kept.peers().firstOrNull { it.id == r.peer } ?: return@launch
            if (!isTv(c) || p.hub) { if (':' in p.at) post(p.at, "/do", Sync.sealed(SyncPhone.kept, p, JSONObject().put("do", what).put("ms", ms)), 5_000) }
            else TvHub.ask(p.id, what, ms)
        }
    }

    /** Carried on with here at its very second; the other stops (told to, or at its waiting call, a phone). */
    fun take(r: Remote) {
        val c = app ?: return
        val at = r.positionNow().let { if (it > 5_000 && !r.song.station) it else 0L }
        Playback.play(r.songs, r.index, at)
        val p = SyncPhone.kept.peers().firstOrNull { it.id == r.peer } ?: return
        if (!isTv(c) || p.hub) steer(r, "pause")
        else { SyncPhone.kept.handed(p.id, System.currentTimeMillis()); TvHub.nudge() }
        remote.value = null
    }

    /** A sealed ask of another ink device: its status, and its answer (null: none). */
    private fun post(at: String, path: String, body: String, read: Int): Pair<Int, String?> = runCatching {
        val conn = URL("http://$at$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000; conn.readTimeout = read; conn.requestMethod = "POST"; conn.doOutput = true
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            code to (if (code == 200) conn.inputStream.bufferedReader().readText() else null)
        } finally { conn.disconnect() }
    }.getOrDefault(0 to null)
}

/**
 * The mini player's place, for what plays on another device (nothing plays here): its cover, the song, "playing on
 * living room tv", its line moving with it in the cover's colour; pause and on steering it there, "play here" carrying
 * on with it here at its second. Selection is brightness, as everywhere.
 */
@Composable
fun RemoteMini(frost: HazeState, r: Everywhere.Remote, onOpen: () -> Unit) {
    val pos by produceState(r.positionNow(), r) { while (true) { value = r.positionNow(); delay(500) } }
    val cover = rememberCover(r.song, 300)
    val length = r.length
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = true))
        // its own taps, every one: a tap on its cover fell through to the cover under the bar and played that here
        Column(Modifier.fillMaxWidth().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line)) {
                if (length > 0 && !r.song.station) Box(Modifier.fillMaxWidth((pos.toFloat() / length).coerceIn(0f, 1f)).height(1.dp).background(cover?.accent ?: Ink.ink))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp)) { CoverOf(r.song, r.song.album.ifEmpty { r.song.title }, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(r.song.title.uppercase(), style = headline(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText((if (r.playing) "playing on " else "paused on ") + r.where, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (r.steerable) Word(if (r.playing) "pause" else "play", pixel(14.sp, Ink.mid), Modifier.padding(start = 12.dp), touch = 10.dp) {
                    Everywhere.steer(r, if (r.playing) "pause" else "play")
                }
                Word("play here", pixel(14.sp), Modifier.padding(start = 6.dp), touch = 10.dp) { Everywhere.take(r) }
            }
        }
    }
}

/**
 * What plays on another device, opened from its bar (2026-11-04, its second look): the device big at the top (this
 * is somewhere else playing, not here), the cover and the song in the middle as the player has them (the line along
 * the cover's top in its colour, the times over it, moving with it); back, pause or play, next steering it there; up
 * next, its queue, as the player's own; play here carrying on with it on this one; and the artist's giant marquee along the
 * foot as on the player, drifting while it plays there. Closed by its arrow, back, or the other device stopping.
 */
@Composable
fun RemotePlayerPage(r: Everywhere.Remote, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val pos by produceState(r.positionNow(), r) { while (true) { value = r.positionNow(); delay(250) } }
    val cover = rememberCover(r.song, 900)
    val length = r.length
    val tv = onTv()
    var queueUp by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(bars).navigationBarsPadding()) {
            // where it plays, big: the device's own name
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    BasicText(if (r.playing) "playing on" else "paused on", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(2.dp))
                    BasicText(r.where.removePrefix("ink music on ").uppercase(), style = headline(if (tv) 40.sp else 34.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CloseArrow(onClick = onClose)
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)) {
                // the cover as big as fits with the song under it
                val words = 104.dp
                // (and a breath above and below it: it crowded the device's name)
                val side = minOf(maxWidth, maxHeight - words - 40.dp, if (tv) 520.dp else 560.dp).coerceAtLeast(120.dp)
                Column(Modifier.align(Alignment.Center).width(side)) {
                    // the times over the line, the line along the cover's top, in the cover's colour
                    Row(Modifier.fillMaxWidth()) {
                        BasicText(clock(pos), style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                        if (length > 0 && !r.song.station) BasicText("-" + clock((length - pos).coerceAtLeast(0)), style = pixel(12.sp, Ink.mid))
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line)) {
                        if (length > 0 && !r.song.station) Box(Modifier.fillMaxWidth((pos.toFloat() / length).coerceIn(0f, 1f)).height(1.dp).background(cover?.accent ?: Ink.ink))
                    }
                    CoverOf(r.song, r.song.album.ifEmpty { r.song.title }, Modifier.size(side))
                    Spacer(Modifier.height(16.dp))
                    BasicText(r.song.title.uppercase(), style = headline(28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    BasicText(r.song.artist.lowercase(), style = pixel(13.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (r.steerable) {
                    Pixel(BACK, "back, there", Ink.ink, cell = 3.dp) { Everywhere.steer(r, "previous") }
                    Pixel(if (r.playing) PAUSE else PLAY, if (r.playing) "pause, there" else "play, there", Ink.ink, cell = 3.dp) { Everywhere.steer(r, if (r.playing) "pause" else "play") }
                    Pixel(NEXT, "next, there", Ink.ink, cell = 3.dp) { Everywhere.steer(r, "next") }
                }
            }
            // the player's second row, as on this phone's own: what plays there after this
            Spacer(Modifier.height(10.dp))
            Word("up next", pixel(14.sp, Ink.ink.copy(alpha = .75f)), Modifier.align(Alignment.CenterHorizontally), touch = 10.dp) { queueUp = true }
            Spacer(Modifier.height(14.dp))
            Word("PLAY HERE", headline(26.sp), Modifier.align(Alignment.CenterHorizontally), touch = 10.dp) { Everywhere.take(r); onClose() }
            Spacer(Modifier.height(10.dp))
            // the artist, giant, along the foot as on the player: drifting while it plays there, still while paused
            Marquee(r.song.artist, r.playing && Marquees.on.collectAsState().value, Modifier.fillMaxWidth().bleedPastCutout(), size = if (tv) 100.sp else 110.sp)
        }
        androidx.compose.animation.AnimatedVisibility(
            queueUp,
            enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo)),
            exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.EXIT, easing = Motion.expo)),
        ) { RemoteQueuePage(r, onClose = { queueUp = false }) }
    }
}
