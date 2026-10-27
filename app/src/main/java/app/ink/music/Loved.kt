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
        app?.let { prefs(it).edit().putStringSet("ids", local)
            .putString("tube", JSONArray().apply { _tube.value.forEach { put(it.json()) } }.toString()).apply() }
        publish()
    }
}

/** The desktop bar's heart (waybar/heart/make-hearts.py), 7 x 6 on Silkscreen's grid. */
private val HEART = listOf(
    ".##.##.",
    "#######",
    "#######",
    ".#####.",
    "..###..",
    "...#...",
)

/** The heart's red, the desktop's: shown only a moment after loving, the one colour that isn't the art. */
private val RED = Color(0xFFE8302A)

/**
 * A song's heart, as on the desktop: 17% while not loved; loving it, red with a soft red glow for
 * two seconds, then ink (loved, the rest of the time). A tap loves or unloves, felt as a toggle.
 */
@Composable
fun Heart(song: Song?, cell: Dp = 3.dp, modifier: Modifier = Modifier) {
    val loved by Loved.ids.collectAsState()
    val on = song != null && song.id in loved
    var lovedAt by remember { mutableLongStateOf(0L) }
    var red by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(lovedAt) { if (lovedAt > 0) { red = true; delay(2_000); red = false } }
    LaunchedEffect(on) { if (!on) red = false }
    val colour by animateColorAsState(
        when { red -> RED; on -> Ink.ink; else -> Ink.dim },
        tween(if (red) Motion.FADE else Motion.ENTER, easing = Motion.expo), label = "heart",
    )
    val glow by animateFloatAsState(if (red) .55f else 0f, tween(if (red) Motion.FADE else 600), label = "glow")
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
            Box(Modifier.graphicsLayer { alpha = glow }.blur(cell, BlurredEdgeTreatment.Unbounded)) { Sprite(HEART, RED, cell) }
        Sprite(HEART, colour, cell)
    }
}
