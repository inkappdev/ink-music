package app.ink.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transitions, the phone's: off, crossfade, automix (Crossfade). One line; resting said plainly when the output's had
 * alone (two decks can't share it then).
 */
@Composable
fun TransitionsSetting() {
    val mode by Crossfade.mode.collectAsState()
    val sound by Player.sound.collectAsState()
    BasicText("TRANSITIONS", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("each song runs into the next where it fades. automix matches their beats too", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    if (mode != Crossfade.OFF && sound.exclusive) {
        BasicText("resting now: the output's had alone (sound, above)", style = pixel(12.sp, Ink.ink))
        Spacer(Modifier.height(8.dp))
    }
    Row {
        for (m in listOf(Crossfade.OFF, Crossfade.CROSSFADE, Crossfade.AUTOMIX))
            Word(m, pixel(14.sp, if (mode == m) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Crossfade.set(m) }
    }
}

/**
 * The equalizer, the phone's page here in the sound part: on and off; your headphones' correction (AutoEq's, for your
 * model, named; a tap turns it on or off, kept either way); a shape on top (the one chosen lit, "yours" once a band's
 * moved); the ten bands as columns of pixel dots, a dot a decibel, lit in ink from nought to the band's level, dragged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerSetting() {
    val state by Eq.state.collectAsState()
    var looking by remember { mutableStateOf(false) }
    fun set(s: Eq.State) = Eq.set(s)
    // the layers put together and applied: the correction (if on) under the shape
    fun put(corrected: Boolean = state.corrected, preset: String = state.preset, shape: List<Int> = state.shape,
            correction: List<Int> = state.correction, phones: String = state.phones) =
        set(Eq.State(true, preset, Eq.combined(correction, corrected, shape), phones, correction, corrected, shape))
    BasicText("EQUALIZER", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("shape the sound: correct it for your headphones, or pick a shape, or drag the bars", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    Row {
        Word("on", pixel(14.sp, if (state.on) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) {
            set(state.copy(on = true, levels = state.levels.ifEmpty { Eq.levels(state.preset) }))
        }
        Word("off", pixel(14.sp, if (!state.on) Ink.ink else Ink.dim), touch = 8.dp) { set(state.copy(on = false)) }
    }
    Spacer(Modifier.height(20.dp))
    BasicText("for your headphones", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(6.dp))
    Row {
        if (state.phones.isNotEmpty() && state.correction.isNotEmpty()) {
            Word(state.phones.substringBefore(" · ").lowercase(), pixel(14.sp, if (state.on && state.corrected) Ink.ink else Ink.dim),
                Modifier.padding(end = 22.dp), touch = 8.dp) { put(corrected = !(state.on && state.corrected)) }
            Word("change", pixel(14.sp, Ink.mid), touch = 8.dp) { looking = !looking }
        } else Word("choose your model", pixel(14.sp, Ink.mid), touch = 8.dp) { looking = !looking }
    }
    BasicText("a correction measured for your model, so it sounds as it should (autoeq)", style = pixel(12.sp, Ink.mid),
        modifier = Modifier.padding(top = 4.dp))
    if (looking) Headphones { name, levels -> looking = false; put(corrected = true, correction = levels, phones = name) }
    Spacer(Modifier.height(20.dp))
    BasicText("and a shape", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (Eq.presets.keys + if (state.preset == "yours") listOf("yours") else emptyList()).forEach { p ->
            Word(p, pixel(14.sp, if (state.on && state.preset == p) Ink.ink else Ink.dim), touch = 8.dp) {
                if (p != "yours") put(preset = p, shape = Eq.levels(p))
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    val b = Eq.bands
    val levels = state.levels.ifEmpty { List(b.hz.size) { 0 } }
    Row(Modifier.fillMaxWidth()) {
        b.hz.forEachIndexed { i, hz ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val db = kotlin.math.round(levels.getOrElse(i) { 0 } / 100f).toInt()
                BasicText((if (db > 0) "+" else "") + db, style = pixel(12.sp, if (state.on) Ink.ink else Ink.mid))
                Spacer(Modifier.height(8.dp))
                Band(levels.getOrElse(i) { 0 }, b, state.on, Modifier.fillMaxWidth().height(220.dp), order = i) { mb ->
                    if (mb != levels.getOrElse(i) { 0 }) {
                        // a band dragged: the shape on top is yours, the correction under it kept as it is
                        val under = if (state.corrected) state.correction.getOrElse(i) { 0 } else 0
                        val shape = List(b.hz.size) { j -> if (j == i) mb - under else (levels.getOrElse(j) { 0 } - if (state.corrected) state.correction.getOrElse(j) { 0 } else 0) }
                        set(Eq.State(true, "yours", levels.toMutableList().also { it[i] = mb }, state.phones, state.correction, state.corrected, shape))
                    }
                }
                Spacer(Modifier.height(8.dp))
                BasicText(Eq.said(hz), style = pixel(12.sp, Ink.mid).copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Your headphones: "headphones >" and what's typed; AutoEq's models with every word, the best measured first. */
@Composable
private fun Headphones(onPicked: (String, List<Int>) -> Unit) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    val all by produceState<List<Eq.Phones>?>(null) {
        value = runCatching { withContext(Dispatchers.IO) { Eq.phones() } }.getOrDefault(emptyList())
    }
    var said by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("headphones > ", style = pixel(14.sp, Ink.dim))
            BasicTextField(typed, { typed = it }, singleLine = true, textStyle = pixel(14.sp),
                cursorBrush = SolidColor(Ink.ink), modifier = Modifier.weight(1f).focusRequester(focus))
        }
        Spacer(Modifier.height(8.dp))
        val list = all
        BasicText(when {
            list == null -> "reading autoeq's list"
            list.isEmpty() -> "autoeq couldn't be reached"
            typed.isBlank() -> "${list.size} headphones measured, from autoeq"
            else -> said ?: ""
        }, style = pixel(12.sp, Ink.mid))
        if (list != null) Eq.find(list, typed).forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp).pointerInput(p) {
                detectTapGestures {
                    said = "reading ${p.name.lowercase()}"
                    scope.launch {
                        val levels = withContext(Dispatchers.IO) { Eq.correction(p) }
                        // credited as it's shown: whose measurement, and autoeq's work from it
                        if (levels != null) onPicked("${p.name} · ${p.by.substringBefore(" on ")}, autoeq", levels) else said = "autoeq has no correction for that one we can use"
                    }
                }
            }) {
                BasicText(p.name.uppercase(), style = headline(18.sp), maxLines = 2)
                BasicText("measured by ${p.by.lowercase()}", style = pixel(12.sp, Ink.mid))
            }
        }
    }
}

/**
 * One band: a column of dots, a decibel each, lit in ink from the middle (nought) to its level, the rest at the
 * hairline's brightness; off, the lit ones dimmer. A click or a drag sets it, a decibel (a dot) at a time.
 */
@Composable
private fun Band(level: Int, b: Eq.Bands, on: Boolean, modifier: Modifier, order: Int = 0, onLevel: (Int) -> Unit) {
    // as it opens, each band rises from nought to its level, one after another (a meter waking)
    val rise = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(order * 40L)
        rise.animateTo(1f, androidx.compose.animation.core.tween(600, easing = Motion.expo))
    }
    val dots = ((b.max - b.min) / 100 + 1).coerceIn(9, 41)
    val step = ((b.max - b.min) / (dots - 1)).coerceAtLeast(1)
    fun at(y: Float, h: Float): Int {
        val dot = ((1f - (y / h).coerceIn(0f, 1f)) * (dots - 1) + .5f).toInt()
        return (b.min + dot * step).coerceIn(b.min, b.max).let { (it / 100) * 100 }
    }
    Canvas(modifier.pointerInput(b) {
        detectTapGestures { onLevel(at(it.y, size.height.toFloat())) }
    }.pointerInput(b) {
        detectDragGestures { change, _ -> onLevel(at(change.position.y, size.height.toFloat())) }
    }) {
        val cell = size.height / dots
        val dot = minOf(cell * .62f, size.width * .5f)
        val zero = (0 - b.min) / step
        val lit = ((level * rise.value).toInt() - b.min) / step
        for (d in 0 until dots) {
            val y = size.height - (d + 1) * cell + (cell - dot) / 2
            val inRange = if (lit >= zero) d in zero..lit else d in lit..zero
            val color = when {
                inRange -> if (on) Ink.ink else Ink.mid
                d == zero -> Ink.mid
                else -> Ink.dim
            }
            drawRect(color, Offset((size.width - dot) / 2, y), Size(dot, dot))
        }
    }
}
