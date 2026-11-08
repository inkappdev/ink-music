package app.ink.music

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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How far through the handover now, ticking while there is one (null: none). The phone's rememberMixing. */
@Composable
fun rememberMixing(mix: Crossfade.Mixing?): Float? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mix?.start) {
        val m = mix ?: return@LaunchedEffect
        while (System.currentTimeMillis() < m.start + m.length + 50) { now = System.currentTimeMillis(); delay(16) }
        now = System.currentTimeMillis()
    }
    return mix?.through(now)
}

/**
 * The line as a crossfader while two songs blend (the phone's): a knob sliding across it over the handover, behind it
 * the new song's accent filling in, ahead of it what's left of the old one's; in automix each beat a tick on the line,
 * lit as it passes and fading, the two songs' beats one and the same.
 */
@Composable
fun MixBar(mix: Crossfade.Mixing, k: Float, old: Color, new: Color, line: Dp, modifier: Modifier = Modifier) {
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
 * The line with this song's mix point (the phone's): played in its accent up to it, the mix point a tick, and past it,
 * what'll be heard under the next song, dotted.
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
