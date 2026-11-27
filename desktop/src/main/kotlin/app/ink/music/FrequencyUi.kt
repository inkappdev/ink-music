package app.ink.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A dot not lit. */
private val OFF = Color(0xFF262626)

/**
 * FREQUENCY on listen, the phone's card in ink's own hand (2026-11-27; the phone's since 2026-11-25, #16): your mix as a
 * field of pixels that is also the dial. The needle, a column of the playing cover's colour, is dragged or clicked
 * anywhere along it, tuning between yours, near and new; it reads as a frequency, 20 Hz (all yours) to 20 kHz (all new).
 * Under it: where it's tuned in words and what that means, each share as a figure and a ten-segment bar, the mood if one's
 * on (picked on the moods page), and PLAY. Half the page wide at most, as the computer's pages are.
 */
@Composable
fun FrequencyCard() {
    val d by YourFrequency.dials.collectAsState()
    val starting by YourFrequency.starting.collectAsState()
    val first by YourFrequency.first.collectAsState()
    val queue by Player.queue.collectAsState()
    val now by Player.now.collectAsState()
    val on = first != null && queue.firstOrNull()?.id == first
    val playing = on && now?.playing == true
    // the needle while it's moved (kept to the dials once let go: each turn finds its songs again)
    var tuned by remember(d.tuned) { mutableFloatStateOf(d.tuned.toFloat()) }
    val cover = if (on) now?.song?.let { Covers.accentOf(it) } else null
    fun let(t: Float) = YourFrequency.turn(d.copy(tuned = t.toDouble().coerceIn(0.0, 1.0)))
    val t = tuned.toDouble()
    val (hz, unit) = FrequencyMix.hz(t)

    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText("frequency", style = pixel(12.sp, Ink.mid))
            BasicText(when { playing -> "playing"; starting -> "finding"; on -> "paused"; else -> "your music, never ends" },
                style = pixel(12.sp, if (playing) Ink.ink else Ink.dim))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(hz, style = headline(64.sp))
            BasicText(unit.lowercase(), style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
        }
        Pixels(tuned, playing, if (on && cover != null) cover else Ink.ink, cover ?: Ink.dim, Modifier.fillMaxWidth().padding(top = 12.dp).height(150.dp)
            .pointerInput(d) { detectTapGestures { tuned = (it.x / size.width).coerceIn(0f, 1f); let(tuned) } }
            .pointerInput(d) {
                detectHorizontalDragGestures(onDragEnd = { let(tuned) }) { change, _ ->
                    change.consume()
                    tuned = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            })
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("yours", "near", "new").forEach { BasicText(it, style = pixel(12.sp, Ink.dim)) }
        }
        BasicText(FrequencyMix.said(t).uppercase(), style = headline(24.sp), modifier = Modifier.padding(top = 18.dp))
        BasicText(FrequencyMix.meaning(t), style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
        // each share: its figure and a ten-segment bar, live while the needle's dragged
        val mix = remember(tuned) { FrequencyMix.shares(FrequencyMix.Dials(t)) }
        val y = Math.round(mix.getValue(FrequencyMix.YOURS) * 100).toInt(); val n = Math.round(mix.getValue(FrequencyMix.NEAR) * 100).toInt()
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("yours", y, Ink.ink), Triple("near", n, Ink.mid), Triple("new", 100 - y - n, if (on && cover != null) cover else Ink.mid)).forEach { (word, pct, colour) ->
                Column(Modifier.weight(1f)) {
                    BasicText(word, style = pixel(12.sp, Ink.mid))
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                        BasicText("$pct", style = headline(28.sp))
                        BasicText("%", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        val lit = Math.round(pct / 10.0).toInt()
                        repeat(10) { k -> Box(Modifier.weight(1f).height(6.dp).background(if (k < lit) colour else OFF)) }
                    }
                }
            }
        }
        // a mood's picked on the moods page (ink's twelve, each playing this in it): here only said, and put away in a click
        InkMoods.ALL.firstOrNull { it.key == d.mood }?.let { m ->
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BasicText("in ${m.name.lowercase()}", style = pixel(12.sp, Ink.mid))
                BasicText("off", style = pixel(12.sp), modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { YourFrequency.turn(d.copy(mood = "")) }))
            }
        }
        // play: a big word, as ink's buttons are
        Word(when { starting -> "FINDING"; playing -> "PAUSE"; else -> "PLAY" }, headline(40.sp, if (starting) Ink.dim else Ink.ink),
            Modifier.padding(top = 20.dp), touch = 8.dp) {
            if (!starting) { if (!on) YourFrequency.start() else Player.toggle() }
        }
    }
}

/**
 * The pixels: 26 columns of 13, lit by the mix the needle makes (as many columns white as its share is yours, then 70% for
 * near, then the cover's colour for new), the needle a whole column of the cover's colour. While playing the columns rise
 * and fall seven times a second; at rest they hold a quiet shape.
 */
@Composable
private fun Pixels(tuned: Float, moving: Boolean, needle: Color, newColour: Color, modifier: Modifier) {
    val cols = 26; val rows = 13
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(moving) { if (moving) while (true) { kotlinx.coroutines.delay(140); tick++ } }
    Canvas(modifier) {
        val sx = size.width / cols; val sy = size.height / rows
        val mix = FrequencyMix.shares(FrequencyMix.Dials(tuned.toDouble()))
        val yours = mix.getValue(FrequencyMix.YOURS).toFloat(); val near = mix.getValue(FrequencyMix.NEAR).toFloat()
        val at = (tuned * cols).toInt().coerceIn(0, cols - 1)
        val rnd = java.util.Random(tick * 7919L)
        for (i in 0 until cols) {
            val x = (i + .5f) / cols
            val colour = when { x < yours -> Ink.ink; x < yours + near -> Ink.mid; else -> newColour }
            val lit = (4 + kotlin.math.sin(i / 3.2 + tick * .15) * 3 + (if (i < cols / 4) 3 else 0) + (if (moving) rnd.nextInt(6) else 2)).toInt().coerceIn(1, rows)
            for (k in 0 until rows) {
                val c = when { i == at -> needle; k < lit -> colour; else -> OFF }
                drawRect(c, Offset(i * sx + sx * .12f, size.height - (k + 1) * sy + sy * .12f), androidx.compose.ui.geometry.Size(sx * .76f, sy * .76f))
            }
        }
    }
}
