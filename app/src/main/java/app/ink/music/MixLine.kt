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
) {
    /** How far through the blend, 0 to 1; null when there's none now. */
    fun through(now: Long = SystemClock.elapsedRealtime()): Float? =
        if (start <= 0 || length <= 0 || now < start || now >= start + length) null else (now - start).toFloat() / length

    fun json(): String = JSONObject().put("cutId", cutId).put("cutAt", cutAt).put("full", full).put("auto", auto)
        .put("from", from).put("start", start).put("length", length).put("bpmFrom", bpmFrom).put("bpmTo", bpmTo)
        .put("beatFirst", beatFirst).put("beatPeriod", beatPeriod).toString()

    companion object {
        fun of(json: String?): Mix? = json?.takeIf { it.isNotEmpty() }?.let {
            runCatching {
                val o = JSONObject(it)
                Mix(o.optString("cutId"), o.optLong("cutAt"), o.optLong("full"), o.optBoolean("auto"),
                    o.optString("from"), o.optLong("start"), o.optLong("length"), o.optDouble("bpmFrom"), o.optDouble("bpmTo"),
                    o.optDouble("beatFirst"), o.optDouble("beatPeriod"))
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
