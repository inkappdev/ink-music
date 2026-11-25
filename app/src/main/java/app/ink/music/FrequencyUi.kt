package app.ink.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * FREQUENCY's own faces (2026-11-25, Bronson: Nothing's dot matrix, "a deliberate exception" to ink's two): Doto for its
 * one hero number, Space Grotesk for its words, Space Mono in capitals for its labels and figures. Only this block.
 */
private val Doto = FontFamily(Font(R.font.doto, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))))
private val Grotesk = FontFamily(Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))))
private val Mono = FontFamily(Font(R.font.space_mono))
private fun label(color: Color = Ink.mid) = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = .08.em, color = color)
private fun grotesk(size: TextUnit, color: Color = Ink.ink) = TextStyle(fontFamily = Grotesk, fontSize = size, color = color, letterSpacing = (-.01).em)
/** A dot not lit, and Nothing's red for live (the one interrupt). */
private val OFF = Color(0xFF262626)
private val LIVE = Color(0xFFD71921)

/**
 * FREQUENCY on listen (2026-11-25, #16; designed with Bronson: no radio props, it isn't a station; then Nothing's dot
 * matrix): your mix as a field of dots that is also the dial. The needle, a column of the playing cover's colour, is
 * dragged or tapped anywhere along it, tuning between yours, near and new; it reads as a frequency, 20 Hz (all yours) to
 * 20 kHz (all new), so every place on it is its own. The dots are lit by the mix it makes (white yours, 70% near, the
 * cover's colour new) and rise and fall while it plays. Under it: where it's tuned in words (nine steps) and what that
 * means, each share as a figure and a segmented bar, the mood if one's on (picked on the moods page), and PLAY.
 */
@Composable
fun FrequencyCard() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { YourFrequency.load(ctx) }
    val d by YourFrequency.dials.collectAsState()
    val starting by YourFrequency.starting.collectAsState()
    val first by YourFrequency.first.collectAsState()
    val queue by Playback.queue.collectAsState()
    val now by Playback.now.collectAsState()
    val on = first != null && queue.firstOrNull()?.song?.id == first
    val playing = on && now?.playing == true
    // the needle while it's moved (kept to the dials once let go: each turn finds its songs again)
    var tuned by remember(d.tuned) { mutableFloatStateOf(d.tuned.toFloat()) }
    val cover = rememberCover(if (on) now?.song else null, 200)?.accent
    val newColour = cover ?: Ink.dim
    fun let(t: Float) { YourFrequency.turn(ctx, d.copy(tuned = t.toDouble().coerceIn(0.0, 1.0))) }
    val t = tuned.toDouble()
    val (hz, unit) = FrequencyMix.hz(t)

    Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText("FREQUENCY", style = label())
            if (playing) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(LIVE, CircleShape))
                BasicText("LIVE", style = label(), modifier = Modifier.padding(start = 6.dp))
            } else BasicText(if (starting) "[FINDING]" else if (on) "PAUSED" else "YOUR MUSIC, NEVER ENDS", style = label(Ink.dim))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(hz, style = TextStyle(fontFamily = Doto, fontSize = 64.sp, color = Ink.ink, letterSpacing = (-.02).em))
            BasicText(unit.uppercase(), style = label(), modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
        }
        DotSpectrum(tuned, playing, if (on && cover != null) cover else Ink.ink, newColour, Modifier.fillMaxWidth().padding(top = 12.dp).height(150.dp)
            .pointerInput(d) { detectTapGestures { tuned = (it.x / size.width).coerceIn(0f, 1f); let(tuned) } }
            .pointerInput(d) {
                detectHorizontalDragGestures(onDragEnd = { let(tuned) }) { change, _ ->
                    change.consume(); tuned = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            })
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("YOURS", style = label(Ink.dim)); BasicText("NEAR", style = label(Ink.dim)); BasicText("NEW", style = label(Ink.dim))
        }
        BasicText(FrequencyMix.said(t).replaceFirstChar { it.uppercase() }, style = grotesk(24.sp), modifier = Modifier.padding(top = 18.dp))
        BasicText(FrequencyMix.meaning(t), style = grotesk(14.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
        // each share: its figure and a ten-segment bar, live while the needle's dragged
        val mix = remember(tuned) { FrequencyMix.shares(FrequencyMix.Dials(t)) }
        val y = Math.round(mix.getValue(FrequencyMix.YOURS) * 100).toInt(); val n = Math.round(mix.getValue(FrequencyMix.NEAR) * 100).toInt()
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("YOURS", y, Ink.ink), Triple("NEAR", n, Ink.mid), Triple("NEW", 100 - y - n, if (on && cover != null) cover else Ink.mid)).forEach { (word, pct, colour) ->
                Column(Modifier.weight(1f)) {
                    BasicText(word, style = label())
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                        BasicText("$pct", style = TextStyle(fontFamily = Mono, fontSize = 28.sp, color = Ink.ink))
                        BasicText("%", style = label(), modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        val lit = Math.round(pct / 10.0).toInt()
                        repeat(10) { k -> Box(Modifier.weight(1f).height(6.dp).background(if (k < lit) colour else OFF)) }
                    }
                }
            }
        }
        // a mood's picked on the moods page (ink's twelve, each playing this in it): here only said, and put away in a tap
        InkMoods.ALL.firstOrNull { it.key == d.mood }?.let { m ->
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BasicText("IN ${m.name.uppercase()}", style = label())
                BasicText("OFF", style = label(Ink.ink), modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { YourFrequency.turn(ctx, d.copy(mood = "")) }))
            }
        }
        // play: Nothing's pill, the one round button here
        Row(Modifier.padding(top = 24.dp).border(1.dp, if (starting) Ink.dim else Ink.ink, RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt {
                if (starting) return@felt
                if (!on) YourFrequency.start(ctx) else Playback.toggle()
            }).padding(horizontal = 26.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(10.dp, 12.dp)) {
                if (playing) { drawRect(Ink.ink, size = androidx.compose.ui.geometry.Size(size.width * .35f, size.height)); drawRect(Ink.ink, Offset(size.width * .65f, 0f), androidx.compose.ui.geometry.Size(size.width * .35f, size.height)) }
                else drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }, if (starting) Ink.dim else Ink.ink)
            }
            Spacer(Modifier.width(10.dp))
            BasicText(when { starting -> "FINDING"; playing -> "PAUSE"; else -> "PLAY" }, style = label(if (starting) Ink.dim else Ink.ink).copy(fontSize = 13.sp, letterSpacing = .1.em))
        }
    }
}

/**
 * The dots: 26 columns of 13, lit by the mix the needle makes (as many columns white as its share is yours, then 70% for
 * near, then the cover's colour for new), the needle a whole column of the cover's colour. While playing the columns rise
 * and fall (seven times a second; still with less motion); at rest they hold a quiet shape.
 */
@Composable
private fun DotSpectrum(tuned: Float, moving: Boolean, needle: Color, newColour: Color, modifier: Modifier) {
    val cols = 26; val rows = 13
    var tick by remember { mutableIntStateOf(0) }
    val less by Moves.less.collectAsState()
    LaunchedEffect(moving, less) { if (moving && !less) while (true) { kotlinx.coroutines.delay(140); tick++ } }
    Canvas(modifier) {
        val sx = size.width / cols; val sy = size.height / rows
        val r = minOf(sx, sy) * .32f
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
                drawCircle(c, r, Offset((i + .5f) * sx, size.height - (k + .5f) * sy))
            }
        }
    }
}
