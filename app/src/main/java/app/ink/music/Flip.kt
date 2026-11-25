package app.ink.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The switch between ink's hand and DOT MATRIX, full screen (2026-11-25, Bronson: "a full screen animation when you land
 * on and let go of 440hz"). From where it was let go, a ripple of cells crosses the screen, its front lit in ink and black
 * behind it, a tick as it goes; on black the new hand's name; the app is drawn again in it (the activity made anew) and
 * the black and the name fade away, the app fading in under them. Into DOT MATRIX it covers in ink's squares and
 * opens in dots; back to ink, the other way. With less motion, none of it: the switch at once.
 */
object Flip {
    /** A flip under way: where from (window px), into dots or not, and whether it's covering or opening. */
    data class Run(val from: Offset?, val toDots: Boolean, val opening: Boolean)
    val run = MutableStateFlow<Run?>(null)
    /** Called once covered: the switch made, the app drawn again. */
    @Volatile var then: (() -> Unit)? = null

    fun start(from: Offset?, toDots: Boolean, swap: () -> Unit) {
        if (Moves.less.value) { swap(); return }
        then = swap
        run.value = Run(from, toDots, opening = false)
    }
}

private const val COVER = 700
private const val HOLD = 420
private const val OPEN = 650

/** Over everything, at the app's root. */
@Composable
fun FlipLayer() {
    val r by Flip.run.collectAsState()
    val run = r ?: return
    val haptic = LocalHapticFeedback.current
    val p = remember(run) { Animatable(0f) }
    val word = remember(run) { Animatable(if (run.opening) 1f else 0f) }
    val fade = remember(run) { Animatable(1f) }
    LaunchedEffect(run) {
        if (!run.opening) {
            // covering: the ripple across, a tick at its start and as it closes; the name on black; then the switch
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            p.animateTo(1f, tween(COVER, easing = LinearEasing))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            word.animateTo(1f, tween(240, easing = Motion.expo))
            kotlinx.coroutines.delay(HOLD.toLong())
            // the switch: kept for the new activity, which opens it
            Flip.run.value = run.copy(opening = true)
            Flip.then?.invoke(); Flip.then = null
        } else {
            // the black and the name fading together, the app in its new hand fading in under them (2026-11-25, Bronson: a
            // second sweep of cells opening was one too many)
            kotlinx.coroutines.delay(120)
            kotlinx.coroutines.coroutineScope {
                launch { word.animateTo(0f, tween(360)) }
                fade.animateTo(0f, tween(OPEN, easing = Motion.expo))
            }
            Flip.run.value = null
        }
    }
    val d = LocalDensity.current
    // covering in the hand it leaves
    val dots = !run.toDots
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        Canvas(Modifier.fillMaxSize()) {
            val pitch = with(d) { 18.dp.toPx() }
            val cols = (size.width / pitch).toInt() + 2; val rows = (size.height / pitch).toInt() + 2
            val o = run.from ?: Offset(size.width / 2, size.height / 2)
            val far = maxOf(
                (o - Offset.Zero).getDistance(), (o - Offset(size.width, 0f)).getDistance(),
                (o - Offset(0f, size.height)).getDistance(), (o - Offset(size.width, size.height)).getDistance())
            for (y in 0 until rows) for (x in 0 until cols) {
                val c = Offset((x + .5f) * pitch, (y + .5f) * pitch)
                val dd = (c - o).getDistance() / far
                // how far this cell's come: 0 untouched, 1 whole (black, covering); opening runs it back out
                // opening: every cell whole (black) while it all fades
                val v = if (!run.opening) ((p.value * 1.25f - dd) / .25f).coerceIn(0f, 1f) else 1f
                if (v <= 0f) continue
                // the front lit in ink, black once a cell's whole
                val colour = if (v >= 1f) androidx.compose.ui.graphics.Color.Black else Ink.ink.copy(alpha = .35f + .65f * v)
                val s = pitch * (if (v >= 1f) 1.02f else v * .9f)
                if (dots && v < 1f) drawCircle(colour, s / 2, c)
                else drawRect(colour, Offset(c.x - s / 2, c.y - s / 2), Size(s, s))
            }
        }
        // the new hand's name on the black
        BasicText(if (run.toDots) "DOT MATRIX" else "INK",
            style = if (run.toDots) TextStyle(fontFamily = DotMatrix.Doto, fontSize = 52.sp, color = Ink.ink, letterSpacing = (-.02).em)
                    // Archivo itself, not the headline's hand: that turned from DOT MATRIX's to ink's under the word
                    else TextStyle(fontFamily = Archivo, fontSize = 64.sp, color = Ink.ink, letterSpacing = (-.02).em),
            modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = word.value })
    }
}
