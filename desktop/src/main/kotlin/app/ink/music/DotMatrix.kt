package app.ink.music

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * DOT MATRIX on the computer (2026-11-27; the phone's since 2026-11-25, Bronson: the whole app in the dot matrix FREQUENCY
 * is drawn in, "best of both worlds"): ink's bones kept (black, hairlines, words for buttons, album art the only colour,
 * one curve) and its surface swapped: headlines in Space Grotesk, the small words in Space Mono, pixels drawn as round
 * dots ([Hand]), the hero numbers in Doto. Found as the phone's is, by tuning FREQUENCY to 440 Hz (concert A) and letting
 * go there; once found, settings, the look, has it to turn on and off. Kept as files ("dot-matrix" on, "dot-matrix-found").
 */
object DotMatrix {
    private fun bytes(name: String) = DotMatrix::class.java.getResourceAsStream("/$name.ttf")!!.use { it.readBytes() }
    // Doto's dots made round (its ROND axis, 0 square to 100 round), as every other dot here is
    val Doto = FontFamily(androidx.compose.ui.text.platform.Font("doto-round", bytes("doto"), FontWeight.ExtraBold, FontStyle.Normal,
        FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("ROND", 100f))))
    val Grotesk = FontFamily(androidx.compose.ui.text.platform.Font("space-grotesk-500", bytes("space_grotesk"), FontWeight.Medium, FontStyle.Normal,
        FontVariation.Settings(FontVariation.weight(500))))
    /** The headlines' weight: Bold, to stand where Archivo Black did (Medium read thin and small). */
    val GroteskBold = FontFamily(androidx.compose.ui.text.platform.Font("space-grotesk-700", bytes("space_grotesk"), FontWeight.Bold, FontStyle.Normal,
        FontVariation.Settings(FontVariation.weight(700))))
    val Mono = FontFamily(androidx.compose.ui.text.platform.Font("space-mono", bytes("space_mono")))

    private val file = File(Home.dir, "dot-matrix")
    private val foundFile = File(Home.dir, "dot-matrix-found")
    /** On, and found (shown in settings once it's been found). */
    val on = MutableStateFlow(file.exists())
    val found = MutableStateFlow(file.exists() || foundFile.exists())

    init { apply() }

    /** Read before anything's drawn: the hand set to match. */
    fun start() = apply()

    private fun apply() {
        Hand.headline = if (on.value) GroteskBold else null
        Hand.small = if (on.value) Mono else null
        Hand.display = if (on.value) Doto else null
        Hand.dots = on.value
    }

    /** Turned on or off: kept, and the window drawn again in the new hand (the app's root is keyed on [on]). */
    fun set(value: Boolean) {
        runCatching { if (value) file.createNewFile() else file.delete() }
        runCatching { foundFile.createNewFile() }
        found.value = true
        apply()
        on.value = value
    }

    /** Concert A's notch: about a fingertip of the dial either side of 440 (3% of it), the needle snapping to it. */
    fun isA(hz: Double) = kotlin.math.abs(kotlin.math.ln(hz / 440.0)) < .03 * kotlin.math.ln(1000.0)
    /** The needle's place at exactly 440 Hz (FrequencyMix.hzOf's inverse). */
    val A_AT = (kotlin.math.ln(440.0 / 20.0) / kotlin.math.ln(1000.0)).toFloat()
    /** Where the needle sits for a pointer at `t`: in the notch, exactly 440. */
    fun detent(t: Float) = if (isA(FrequencyMix.hzOf(t.toDouble()))) A_AT else t
}

/** A pixel cell: a round dot in DOT MATRIX, ink's square otherwise. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.cell(color: androidx.compose.ui.graphics.Color, topLeft: androidx.compose.ui.geometry.Offset, size: androidx.compose.ui.geometry.Size) {
    if (Hand.dots) drawCircle(color, minOf(size.width, size.height) * .45f, androidx.compose.ui.geometry.Offset(topLeft.x + size.width / 2, topLeft.y + size.height / 2))
    else drawRect(color, topLeft, size)
}

/** A number shown big: in Doto's dots in DOT MATRIX, as it was otherwise. */
fun numeral(style: androidx.compose.ui.text.TextStyle) =
    if (Hand.dots) style.copy(fontFamily = DotMatrix.Doto, letterSpacing = androidx.compose.ui.unit.TextUnit(-.02f, androidx.compose.ui.unit.TextUnitType.Em)) else style

/** The progress line in DOT MATRIX (the phone's DotLine): a row of dots, the played ones in the cover's colour. */
@androidx.compose.runtime.Composable
fun DotLine(frac: Float, accent: androidx.compose.ui.graphics.Color, thick: androidx.compose.ui.unit.Dp, modifier: androidx.compose.ui.Modifier) {
    val d = maxOf(thick, 3.dp)
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(d)) {
        val r = d.toPx() / 2; val step = d.toPx() * 1.8f
        val n = (size.width / step).toInt().coerceAtLeast(1)
        for (i in 0..n) {
            val x = (i * step + r).coerceAtMost(size.width - r)
            drawCircle(if (x / size.width <= frac) accent else Ink.line, r, androidx.compose.ui.geometry.Offset(x, size.height / 2))
        }
    }
}
