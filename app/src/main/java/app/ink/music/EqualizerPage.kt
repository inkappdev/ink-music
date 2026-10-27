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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.hazeSource


/**
 * ink music's own equalizer: the phone's bands as columns of pixel dots (the radio tile's equaliser, still), lit in
 * ink from the middle to the band's level; dragged up or down, a decibel a dot. The shapes as words above them, the
 * one chosen lit ("yours" once a band's been moved). On and off to hear the difference. Applied by the player
 * ([Eq]), so it holds with the app closed. The phone's own equalizer is a word at the foot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerPage(pages: Pages) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val bands by Playback.bands.collectAsState()
    var state by remember { mutableStateOf(Eq.read(ctx)) }
    var looking by remember { mutableStateOf(false) }
    fun set(s: Eq.State) { state = s; Playback.equalize(ctx, s) }
    FrostedPage(onClose = { pages.back() }) { frost, top ->
        Column(
            Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
        ) {
            BasicText("EQUALIZER", style = headline(44.sp))
            Spacer(Modifier.height(8.dp))
            val b = bands
            BasicText(if (b == null) "the player hasn't said its bands yet: play something, then come back"
                else "${b.hz.size} bands on ink music's sound, with the app closed too", style = pixel(12.sp, Ink.mid))
            if (state.on && state.phones.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                BasicText("tuned for ${state.phones.lowercase()}", style = pixel(12.sp))
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Word("on", pixel(14.sp, if (state.on) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) {
                    val b2 = bands ?: return@Word
                    set(state.copy(on = true, levels = state.levels.ifEmpty { Eq.levels(state.preset, b2.hz.size, b2) }))
                }
                Word("off", pixel(14.sp, if (!state.on) Ink.ink else Ink.dim), touch = 8.dp) { set(state.copy(on = false)) }
            }
            if (b != null) {
                Spacer(Modifier.height(18.dp))
                // the shapes: the one chosen lit (and "yours", once a band's been moved by hand)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (Eq.presets.keys + if (state.preset == "yours") listOf("yours") else emptyList()).forEach { p ->
                        Word(p, pixel(14.sp, if (state.on && state.preset == p) Ink.ink else Ink.dim), touch = 8.dp) {
                            if (p != "yours") set(Eq.State(true, p, Eq.levels(p, b.hz.size, b)))
                        }
                    }
                    Word("your headphones", pixel(14.sp, if (state.on && state.preset == "phones") Ink.ink else Ink.mid), touch = 8.dp) { looking = !looking }
                }
                if (looking) Headphones { name, levels -> looking = false; set(Eq.State(true, "phones", levels, name)) }
                Spacer(Modifier.height(26.dp))
                val levels = state.levels.ifEmpty { List(b.hz.size) { 0 } }
                Row(Modifier.fillMaxWidth()) {
                    b.hz.forEachIndexed { i, hz ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val db = kotlin.math.round(levels.getOrElse(i) { 0 } / 100f).toInt()
                            BasicText((if (db > 0) "+" else "") + db, style = pixel(12.sp, if (state.on) Ink.ink else Ink.mid))
                            Spacer(Modifier.height(8.dp))
                            Band(levels.getOrElse(i) { 0 }, b, state.on, Modifier.fillMaxWidth().height(300.dp), order = i) { mb ->
                                if (mb != levels.getOrElse(i) { 0 }) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    set(Eq.State(true, "yours", levels.toMutableList().also { it[i] = mb }, state.phones.takeIf { state.preset == "phones" }?.let { "$it, adjusted" }.orEmpty()))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            BasicText(Eq.said(hz), style = pixel(12.sp, Ink.mid).copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Spacer(Modifier.height(34.dp))
            var none by remember { mutableStateOf(false) }
            Word(if (none) "this phone has no equalizer of its own" else "the phone's own equalizer", pixel(12.sp, Ink.mid), touch = 8.dp) {
                none = !Playback.equalizer(ctx)
            }
        }
    }
}

/**
 * Your headphones: "headphones >" and what's typed; AutoEq's models with every word (its list read once, the first
 * time), the best measured first; one picked, its correction read and given as the bands.
 */
@Composable
private fun Headphones(onPicked: (String, List<Int>) -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    val all by androidx.compose.runtime.produceState<List<Eq.Phones>?>(null) {
        value = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Eq.phones(ctx) } }.getOrDefault(emptyList())
    }
    var said by remember { mutableStateOf<String?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("headphones > ", style = pixel(14.sp, Ink.dim))
            androidx.compose.foundation.text.BasicTextField(typed, { typed = it }, singleLine = true, textStyle = pixel(14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.ink), modifier = Modifier.weight(1f).focusRequester(focus))
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
                        val levels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Eq.correction(p) }
                        // credited as it's shown: whose measurement, and autoeq's work from it
                        if (levels != null) onPicked("${p.name} · ${p.by.substringBefore(" on ")}, autoeq", levels) else said = "autoeq has no ten-band tuning for that one"
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
 * One band: a column of dots, a decibel each, lit in ink from the middle (nought) to its level, the rest at the hairline's
 * brightness; off, the lit ones at half. A touch or a drag sets it, a decibel (a dot) at a time.
 */
@Composable
private fun Band(level: Int, b: Eq.Bands, on: Boolean, modifier: Modifier, order: Int = 0, onLevel: (Int) -> Unit) {
    // as the page opens, each band rises from nought to its level, one after another (a meter waking)
    val rise = remember { androidx.compose.animation.core.Animatable(if (Moves.less.value) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(order * 40L)
        rise.animateTo(1f, androidx.compose.animation.core.tween(600, easing = Motion.expo))
    }
    // a dot a decibel, the middle one nought (a phone's ±15 dB: 31 dots)
    val DOTS = ((b.max - b.min) / 100 + 1).coerceIn(9, 41)
    val step = ((b.max - b.min) / (DOTS - 1)).coerceAtLeast(1)
    fun at(y: Float, h: Float): Int {
        val dot = ((1f - (y / h).coerceIn(0f, 1f)) * (DOTS - 1) + .5f).toInt()   // 0 the bottom, DOTS - 1 the top
        return (b.min + dot * step).coerceIn(b.min, b.max).let { (it / 100) * 100 }
    }
    Canvas(modifier.pointerInput(b) {
        detectTapGestures { onLevel(at(it.y, size.height.toFloat())) }
    }.pointerInput(b) {
        detectDragGestures { change, _ -> onLevel(at(change.position.y, size.height.toFloat())) }
    }) {
        val cell = size.height / DOTS
        val dotSize = minOf(cell * .62f, size.width * .5f)
        val zero = (0 - b.min) / step                       // the middle dot's index
        val lit = ((level * rise.value).toInt() - b.min) / step
        for (d in 0 until DOTS) {
            val y = size.height - (d + 1) * cell + (cell - dotSize) / 2
            val inRange = if (lit >= zero) d in zero..lit else d in lit..zero
            val colour = when {
                inRange -> if (on) Ink.ink else Ink.mid
                d == zero -> Ink.mid
                else -> Ink.dim
            }
            drawRect(colour, Offset((size.width - dotSize) / 2, y), Size(dotSize, dotSize))
        }
    }
}
