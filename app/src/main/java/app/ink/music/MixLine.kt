package app.ink.music

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * What the player says of its transitions, for the player page (2026-11-01): this song's mix point (where it's cut for
 * the next to come in, its whole length, automix or crossfade),
 * and the blend in hand (when it began, elapsedRealtime; how long; the two tempos; the new song's beat).
 */
data class Mix(
    val cutId: String = "", val cutAt: Long = 0, val full: Long = 0, val auto: Boolean = false,
    val from: String = "", val start: Long = 0, val length: Long = 0, val bpmFrom: Double = 0.0, val bpmTo: Double = 0.0,
    val beatFirst: Double = 0.0, val beatPeriod: Double = 0.0,
    // the song playing's own beat (automix, sure of it): bar one and a beat's length at its start, the same at its end
    // (from `tailFrom` ms), its key at its start and its end (2026-11-08: the beat counter over the line)
    val headDown: Double = 0.0, val headPeriod: Double = 0.0, val tailDown: Double = 0.0, val tailPeriod: Double = 0.0,
    val tailFrom: Long = 0, val headKey: String = "", val tailKey: String = "", val songId: String = "",
) {
    /** How far through the blend, 0 to 1; null when there's none now. */
    fun through(now: Long = SystemClock.elapsedRealtime()): Float? =
        if (start <= 0 || length <= 0 || now < start || now >= start + length) null else (now - start).toFloat() / length

    fun json(): String = JSONObject().put("cutId", cutId).put("cutAt", cutAt).put("full", full).put("auto", auto)
        .put("from", from).put("start", start).put("length", length).put("bpmFrom", bpmFrom).put("bpmTo", bpmTo)
        .put("beatFirst", beatFirst).put("beatPeriod", beatPeriod)
        .put("headDown", headDown).put("headPeriod", headPeriod).put("tailDown", tailDown).put("tailPeriod", tailPeriod)
        .put("tailFrom", tailFrom).put("headKey", headKey).put("tailKey", tailKey).put("songId", songId).toString()

    companion object {
        fun of(json: String?): Mix? = json?.takeIf { it.isNotEmpty() }?.let {
            runCatching {
                val o = JSONObject(it)
                Mix(o.optString("cutId"), o.optLong("cutAt"), o.optLong("full"), o.optBoolean("auto"),
                    o.optString("from"), o.optLong("start"), o.optLong("length"), o.optDouble("bpmFrom"), o.optDouble("bpmTo"),
                    o.optDouble("beatFirst"), o.optDouble("beatPeriod"),
                    o.optDouble("headDown", 0.0), o.optDouble("headPeriod", 0.0), o.optDouble("tailDown", 0.0), o.optDouble("tailPeriod", 0.0),
                    o.optLong("tailFrom"), o.optString("headKey"), o.optString("tailKey"), o.optString("songId"))
            }.getOrNull()
        }
    }
}

/** How far through the blend now, ticking while there is one (null: none). */
@Composable
fun rememberMixing(mix: Mix?): Float? {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(mix?.start) {
        val m = mix ?: return@LaunchedEffect
        while (SystemClock.elapsedRealtime() < m.start + m.length + 50) { now = SystemClock.elapsedRealtime(); delay(16) }
        now = SystemClock.elapsedRealtime()
    }
    return mix?.through(now)
}

/**
 * The progress line as a crossfader while two songs blend: a knob sliding across it over the blend, behind it the
 * new song's accent filling in, ahead of it what's left of the old one's; in automix each beat a tick on the line,
 * lit as it passes and fading, the two songs' beats one and the same.
 */
@Composable
fun MixBar(mix: Mix, k: Float, old: Color, new: Color, line: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(line + 8.dp)) {
        val y = size.height - line.toPx()
        val w = size.width
        val x = w * k
        drawRect(new, Offset(0f, y), Size(x, line.toPx()))
        drawRect(old, Offset(x, y), Size(w - x, line.toPx()))
        // the beats (automix): a tick at each, lit as the knob passes it and fading after
        if (mix.beatPeriod > 0) {
            val nowMs = k * mix.length
            var t = mix.beatFirst % mix.beatPeriod
            while (t < mix.length) {
                val tx = (t / mix.length * w).toFloat()
                val since = nowMs - t
                val lit = if (since < 0) .25f else (1f - (since / mix.beatPeriod).toFloat()).coerceIn(.25f, 1f)
                drawRect(Ink.ink.copy(alpha = lit), Offset(tx - 1.dp.toPx() / 2, y - 4.dp.toPx()), Size(1.dp.toPx(), 4.dp.toPx() + line.toPx()))
                t += mix.beatPeriod
            }
        }
        // the knob: a small block on the line
        val kw = 6.dp.toPx(); val kh = 6.dp.toPx() + line.toPx()
        drawRect(Ink.ink, Offset((x - kw / 2).coerceIn(0f, w - kw), y + line.toPx() / 2 - kh / 2), Size(kw, kh))
    }
}

/**
 * The line with this song's mix point (its own whole length shown): played in its accent, the mix point a tick, and
 * past it, what'll be heard under the next song, dotted.
 */
@Composable
fun MixPointBar(frac: Float, cutFrac: Float, accent: Color, line: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(line + 8.dp)) {
        val y = size.height - line.toPx()
        val w = size.width
        val cut = w * cutFrac
        drawRect(Ink.line, Offset(0f, y), Size(cut, line.toPx()))
        drawRect(accent, Offset(0f, y), Size(w * frac.coerceAtMost(cutFrac), line.toPx()))
        // past the mix point: dotted
        val dot = 2.dp.toPx(); var dx = cut + dot * 2
        while (dx < w) { drawRect(Ink.dim, Offset(dx, y), Size(dot, line.toPx())); dx += dot * 3 }
        // the mix point
        drawRect(lerp(Ink.mid, Ink.ink, .5f), Offset(cut - 1.dp.toPx() / 2, y - 5.dp.toPx()), Size(1.dp.toPx(), 5.dp.toPx() + line.toPx()))
    }
}


/**
 * The song's beat over the line (2026-11-08, Bronson: "an animation on the now playing screen above the progress
 * line"): four squares, a bar, the one lit stepping on with each beat and fading over it, bar one's the brightest;
 * the tempo and the key beside, as a DJ's deck says them. The start's beat early on, the end's near the end (a song's
 * tempo drifts a little). Less motion: still, the words alone lit.
 */
@Composable
fun BeatCounter(mix: Mix, n: Now, modifier: Modifier = Modifier) {
    val less by Moves.less.collectAsState()
    var t by remember { mutableLongStateOf(n.positionNow()) }
    LaunchedEffect(n, less) {
        t = n.positionNow()
        if (less) return@LaunchedEffect
        while (true) { androidx.compose.runtime.withFrameMillis { t = n.positionNow() }; if (!n.moving) break }
    }
    val late = mix.tailPeriod > 0 && t >= mix.tailFrom - 20_000
    val period = if (late) mix.tailPeriod else mix.headPeriod
    val down = if (late) mix.tailDown else mix.headDown
    if (period <= 0) return
    val key = (if (late) mix.tailKey else mix.headKey).ifEmpty { mix.headKey.ifEmpty { mix.tailKey } }
    val beats = (t - down) / period
    val step = Math.floorMod(Math.floor(beats).toLong(), 4L).toInt()
    val within = (beats - Math.floor(beats)).toFloat()
    androidx.compose.foundation.layout.Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Canvas(Modifier.width(52.dp).height(7.dp)) {
            val sq = 7.dp.toPx(); val gap = (size.width - 4 * sq) / 3
            for (i in 0 until 4) {
                val lit = if (less || !n.moving) 0f else if (i == step) 1f - within * .7f else 0f
                val base = if (i == 0) Ink.mid else Ink.dim
                drawRect(lerp(base, Ink.ink, lit), Offset(i * (sq + gap), 0f), Size(sq, sq))
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.text.BasicText("${Math.round(60_000 / period)} BPM" + if (key.isNotEmpty()) "  ·  $key" else "", style = pixel(12.sp, Ink.mid))
    }
}
