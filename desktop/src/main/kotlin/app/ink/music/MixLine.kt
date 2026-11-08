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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
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


/**
 * The song's beat over the line (the phone's BeatCounter, 2026-11-08): four squares, a bar, the one lit stepping on with
 * each beat and fading over it, bar one's the brightest; the tempo and the key beside. Automix, a sure beat only; the
 * start's beat early on, the end's near the end. The position between the player's fifths of a second carried on by
 * the clock while it plays.
 */
@Composable
fun BeatCounter(songId: String, playing: Boolean, modifier: Modifier = Modifier) {
    val e by androidx.compose.runtime.produceState(Crossfade.endsOf(songId), songId) { while (true) { value = Crossfade.endsOf(songId); delay(1_000) } }
    val ends = e ?: return
    val head = ends.head?.takeIf { it.sure >= Beat.SURE }; val tail = ends.tail?.takeIf { it.sure >= Beat.SURE }
    if (head == null && tail == null) return
    val pos by Player.position.collectAsState()
    var base by remember { mutableLongStateOf(pos) }
    var baseAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pos) { base = pos; baseAt = System.currentTimeMillis() }
    var t by remember { mutableLongStateOf(pos) }
    LaunchedEffect(playing) {
        while (true) { androidx.compose.runtime.withFrameMillis { t = if (playing) base + (System.currentTimeMillis() - baseAt) else base }; if (!playing) break }
    }
    val late = tail != null && t >= ends.duration - 45_000
    val b = (if (late) tail else head) ?: tail ?: head!!
    val key = (if (late) ends.tailKey else ends.headKey) ?: ends.headKey ?: ends.tailKey
    val beats = (t - b.downbeat) / b.period
    val step = Math.floorMod(Math.floor(beats).toLong(), 4L).toInt()
    val within = (beats - Math.floor(beats)).toFloat()
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Canvas(Modifier.width(40.dp).height(6.dp)) {
            val sq = 6.dp.toPx(); val gap = (size.width - 4 * sq) / 3
            for (i in 0 until 4) {
                val lit = if (!playing) 0f else if (i == step) 1f - within * .7f else 0f
                drawRect(lerp(if (i == 0) Ink.mid else Ink.dim, Ink.ink, lit), Offset(i * (sq + gap), 0f), Size(sq, sq))
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicText("${Math.round(b.bpm)} BPM" + (key?.let { "  ·  ${it.camelot}" } ?: ""), style = pixel(12.sp, Ink.mid))
    }
}
