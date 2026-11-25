package app.ink.music

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The songs you love: a server song's love is its star on the server (so the web player and every
 * other app agree, and a star made elsewhere shows here), and so is a phone's song the server has
 * too; a YouTube song's is its like there when you're signed in ([YouTubeAccount.liked]), else kept
 * on the phone with its words (it's in no library); a file's own is kept on the phone.
 */
object Loved {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids
    private val _tube = MutableStateFlow<List<Song>>(emptyList())
    /** The YouTube songs loved, to list and play though no search found them this run. */
    val tube: StateFlow<List<Song>> = _tube
    private var local: Set<String> = emptySet()
    private var server: Set<String> = emptySet()
    /** When each was loved (ms), where it's known: loved here, or a star's date on the server. */
    private var dates: Map<String, Long> = emptyMap()
    private val _recent = MutableStateFlow(true)
    /** The loved page's order: the latest loved first, or a to z. */
    val recent: StateFlow<Boolean> = _recent
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(c: Context) = c.getSharedPreferences("loved", Context.MODE_PRIVATE)

    fun start(c: Context) {
        if (app != null) return
        app = c.applicationContext
        local = prefs(c).getStringSet("ids", emptySet())!!.toSet()
        dates = runCatching { JSONObject(prefs(c).getString("when", null) ?: "{}").let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }
            .getOrDefault(emptyMap())
        _recent.value = prefs(c).getBoolean("recent", true)
        _tube.value = runCatching {
            val a = JSONArray(prefs(c).getString("tube", null) ?: "[]")
            (0 until a.length()).map { songOf(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _tube.value.forEach(YouTubeMusic::remember)
        publish()
        scope.launch { YouTubeAccount.liked.collect { publish() } }
    }

    /** The server's stars, as it last said (a sync). */
    fun putServer(starred: Set<String>) { server = starred; publish() }

    /** The server's stars' dates (a sync): a star made in another app has its own. */
    fun putServerDates(at: Map<String, Long>) { dates = dates + at; keepDates() }

    /** When the song was loved, if known: its own date, else its server twin's. */
    fun lovedAt(s: Song): Long? = dates[s.id] ?: Library.twin(s.id)?.let { dates[it] }

    fun order(recent: Boolean) {
        _recent.value = recent
        app?.let { prefs(it).edit().putBoolean("recent", recent).apply() }
    }

    private fun keepDates() {
        app?.let { prefs(it).edit().putString("when", JSONObject(dates).toString()).apply() }
    }

    /** The library read again: which phone's songs are the server's too. */
    fun relink() = publish()

    @Synchronized private fun publish() {
        _ids.value = local + server + _tube.value.map { it.id } + YouTubeAccount.liked.value.map { it.id } + Library.songs.value.filter { Library.twin(it.id) in server }.map { it.id }
    }

    fun toggle(s: Song) {
        val on = s.id !in _ids.value
        // your signature steered by it, while it plays
        app?.let { YourSignature.loved(it, s, on) }
        val star = if (s.server) s.id else Library.twin(s.id)
        dates = if (on) dates + (s.id to System.currentTimeMillis()) else dates - s.id - (star ?: "")
        keepDates()
        if (star != null) {
            server = if (on) server + star else server - star
            scope.launch { Server.star(star, on) }
            if (!on) local = local - s.id   // loved here before the server had it
        } else if (s.tube && YouTubeAccount.who.value != null) {
            YouTubeAccount.like(s, on)
            _tube.value = _tube.value.filter { it.id != s.id }   // loved here before signing in: YouTube's now
        } else if (s.tube) {
            _tube.value = if (on) _tube.value + s else _tube.value.filter { it.id != s.id }
        } else {
            local = if (on) local + s.id else local - s.id
        }
        // unloved: let go of what the love kept (the server's copy, where it was that one kept)
        if (!on) { KeepLoved.unloved(s.id); star?.let(KeepLoved::unloved) }
        app?.let { prefs(it).edit().putStringSet("ids", local)
            .putString("tube", JSONArray().apply { _tube.value.forEach { put(it.json()) } }.toString()).apply() }
        publish()
        // and on Last.fm, beside the scrobbles (not a station's or an episode's)
        if (!s.station && !s.episode) app?.let { c -> scope.launch { LastFm.love(c, s, on) } }
        // told to a paired computer soon
        SyncPhone.loved(s, on)
    }
}

/** The desktop bar's heart (waybar/heart/make-hearts.py), 7 x 6 on Silkscreen's grid. */
val HEART = listOf(
    ".##.##.",
    "#######",
    "#######",
    ".#####.",
    "..###..",
    "...#...",
)

/**
 * The heart's red (the desktop's): a loved song's heart is red for good (2026-11-23, Bronson: "can the heart stay red?
 * and always show up red if liked"), the one colour on screen that isn't the art's, by his call.
 */
val HeartRed = Color(0xFFE8302A)

/**
 * A song's heart: dim (35%) while not loved, red while it is (gold on its hundredth play). Loving it, a moment (2026-11-23,
 * Bronson: "a nice animation"): it swells and settles, a soft red glow behind it, and a few small pixel hearts float up
 * from it and fade (only the swell, with less motion). A tap loves or unloves, felt as a toggle.
 */
@Composable
fun Heart(song: Song?, cell: Dp = 3.dp, modifier: Modifier = Modifier) {
    val loved by Loved.ids.collectAsState()
    val on = song != null && song.id in loved
    var lovedAt by remember { mutableLongStateOf(0L) }
    val less by Moves.less.collectAsState()
    val colour by animateColorAsState(
        when { song != null && song.id in EggsNow.golden.collectAsState().value -> GOLD; on -> HeartRed; else -> Ink.dim },
        tween(Motion.ENTER, easing = Motion.expo), label = "heart",
    )
    // the swell: up past itself and back, as a press springs
    val swell = remember { androidx.compose.animation.core.Animatable(1f) }
    // the rising hearts' way up (0 to 1) and the glow
    val rise = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(lovedAt) {
        if (lovedAt == 0L) return@LaunchedEffect
        // ink's one curve, no bounce: up in 140 ms, back in 260 (MOTION's enter and exit)
        launch { swell.snapTo(.85f); swell.animateTo(1.3f, tween(140, easing = Motion.expo)); swell.animateTo(1f, tween(260, easing = Motion.expo)) }
        // the rising hearts a little longer, as the made-by-ink heart's (EggsUi)
        if (!less) { rise.snapTo(0f); rise.animateTo(1f, tween(600, easing = Motion.expo)) }
    }
    val glow = if (rise.value < 1f) (1f - rise.value) * .55f else 0f
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
            val s = song ?: return@clickable
            haptic.performHapticFeedback(if (s.id in loved) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            if (s.id !in loved) lovedAt = System.currentTimeMillis()
            Loved.toggle(s)
        }.padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // the glow: the heart again, red and blurred, under it (Android 12's blur)
        if (glow > 0f && Build.VERSION.SDK_INT >= 31)
            Box(Modifier.graphicsLayer { alpha = glow; scaleX = swell.value; scaleY = swell.value }.blur(cell, BlurredEdgeTreatment.Unbounded)) { Sprite(HEART, HeartRed, cell) }
        // the small hearts rising: five, each its own way up and a little to the side, smaller, fading as they go
        if (rise.value < 1f) {
            val p = rise.value
            for (i in 0 until 5) {
                val side = listOf(-1.6f, -.7f, .1f, .9f, 1.7f)[i]
                val up = (.7f + i % 3 * .25f) * p
                Box(Modifier.graphicsLayer {
                    translationX = side * cell.toPx() * 4f * p
                    translationY = -up * cell.toPx() * 16f
                    alpha = (1f - p) * (if (i % 2 == 0) 1f else .75f)
                    scaleX = .45f + i % 2 * .12f; scaleY = scaleX
                }) { Sprite(HEART, HeartRed, cell) }
            }
        }
        Box(Modifier.graphicsLayer { scaleX = swell.value; scaleY = swell.value }) { Sprite(HEART, colour, cell) }
    }
}
