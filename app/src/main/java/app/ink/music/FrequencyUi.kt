package app.ink.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FREQUENCY on listen (2026-11-25, #16; designed with Bronson: no radio props, it isn't a station): your mix drawn as a
 * pixel spectrum, and the spectrum is the dial. A needle cuts it; dragged or tapped anywhere along it, it tunes between
 * yours, near and new, the columns coloured by the mix it makes (ink for yours, 70% for near, the playing cover's colour
 * for new). The bars move while it plays. Under it the moods (the hour's three, "more" all twelve), and PLAY big.
 * Just your music, or the play build: no moods (their songs are found on YouTube Music).
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val newColour = rememberCover(if (on) now?.song else null, 200)?.accent ?: Ink.dim
    var all by remember { mutableStateOf(false) }
    val moods = remember(d.mood, all) { if (all) InkMoods.ALL else (InkMoods.now() + InkMoods.ALL.filter { it.key == d.mood }).distinctBy { it.key } }
    fun let(t: Float) { YourFrequency.turn(ctx, d.copy(tuned = t.toDouble().coerceIn(0.0, 1.0))) }

    Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 18.dp)) {
        BasicText("FREQUENCY", style = headline(40.sp))
        BasicText(when {
            starting -> "gathering your songs"
            playing -> "skip to steer it, love one for more like it"
            on -> "paused where you left it"
            else -> "your music and what's next to it. it never ends"
        }, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
        Spectrum(tuned, playing, newColour, Modifier.fillMaxWidth().padding(top = 18.dp).height(120.dp)
            .pointerInput(d) { detectTapGestures { tuned = (it.x / size.width).coerceIn(0f, 1f); let(tuned) } }
            .pointerInput(d) {
                detectHorizontalDragGestures(onDragEnd = { let(tuned) }) { change, _ ->
                    change.consume(); tuned = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            })
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("yours", style = pixel(12.sp, Ink.mid)); BasicText("near", style = pixel(12.sp, Ink.mid)); BasicText("new", style = pixel(12.sp, Ink.mid))
        }
        BasicText(FrequencyMix.said(tuned.toDouble()), style = pixel(14.sp, Ink.ink), modifier = Modifier.padding(top = 8.dp))
        if (!JustYours.on && YouTubeMusic.available) {
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Word("any mood", pixel(14.sp, if (d.mood.isEmpty()) Ink.ink else Ink.dim), touch = 8.dp) { YourFrequency.turn(ctx, d.copy(mood = "")) }
                moods.forEach { m ->
                    Word(m.name, pixel(14.sp, if (d.mood == m.key) Ink.ink else Ink.dim), touch = 8.dp) {
                        YourFrequency.turn(ctx, d.copy(mood = if (d.mood == m.key) "" else m.key))
                    }
                }
                Word(if (all) "fewer" else "more", pixel(14.sp, Ink.mid), touch = 8.dp) { all = !all }
            }
        }
        Spacer(Modifier.height(10.dp))
        Word(when { starting -> "FINDING"; playing -> "PAUSE"; else -> "PLAY" }, headline(40.sp, if (starting) Ink.dim else Ink.ink), touch = 8.dp) {
            if (starting) return@Word
            if (!on) YourFrequency.start(ctx) else Playback.toggle()
        }
    }
}

/**
 * The spectrum: 28 columns of pixel cells, coloured by the mix the needle makes (as many columns ink as its share is
 * yours, then 70% for near, then the cover's colour for new), the needle an ink line with a cap where it's tuned. While playing its bars rise and fall (seven times a second, a few cells each; still with
 * less motion); at rest they hold a quiet shape.
 */
@Composable
private fun Spectrum(tuned: Float, moving: Boolean, newColour: Color, modifier: Modifier) {
    val cols = 28; val rows = 16
    var tick by remember { mutableIntStateOf(0) }
    val less by Moves.less.collectAsState()
    LaunchedEffect(moving, less) { if (moving && !less) while (true) { kotlinx.coroutines.delay(140); tick++ } }
    Box(modifier) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (cols - 1)) / cols
            val h = (size.height - gap * (rows - 1)) / rows
            val r = java.util.Random(tick * 7919L)
            // the zones are the mix itself, yours then near then new, as wide as their shares where the needle sits
            val mix = FrequencyMix.shares(FrequencyMix.Dials(tuned.toDouble()))
            val yours = (mix.getValue(FrequencyMix.YOURS)).toFloat()
            val near = (mix.getValue(FrequencyMix.NEAR)).toFloat()
            for (i in 0 until cols) {
                val x = (i + .5f) / cols
                val colour = when { x < yours -> Ink.ink; x < yours + near -> Ink.mid; else -> newColour }
                val shape = 4 + kotlin.math.sin(i / 3.4 + tick * .15) * 3 + (if (i < cols / 4) 3 else 0)
                val lit = (shape + (if (moving) r.nextInt(7) else 3)).toInt().coerceIn(1, rows)
                for (k in 0 until rows) {
                    drawRect(if (k < lit) colour else Ink.line, Offset(i * (w + gap), size.height - (k + 1) * h - k * gap), Size(w, h))
                }
            }
            // the needle, an ink line through it and its cap
            val nx = tuned * size.width
            drawRect(Ink.ink, Offset(nx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height))
            drawRect(Ink.ink, Offset(nx - 6.dp.toPx(), 0f), Size(12.dp.toPx(), 6.dp.toPx()))
        }
    }
}
