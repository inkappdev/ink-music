package app.ink.music

import android.app.Activity
import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * DOT MATRIX (2026-11-25, Bronson: an easter egg turning the whole app to the dot matrix FREQUENCY is drawn in, "best of
 * both worlds"): ink's bones kept (black, hairlines, words for buttons, album art the only colour, one curve) and its
 * surface swapped: headlines in Space Grotesk, the small words in Space Mono, pixels drawn as round dots ([Hand]), the
 * hero numbers in Doto. Found by tuning FREQUENCY to 440 Hz (concert A) and letting go there; once found, settings,
 * the look, has it to turn on and off. Kept as files ("dot-matrix" on, "dot-matrix-found"), so both processes read it.
 */
object DotMatrix {
    // Doto's dots made round (its ROND axis, 0 square to 100 round), as every other dot here is
    val Doto = FontFamily(Font(R.font.doto, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("ROND", 100f))))
    val Grotesk = FontFamily(Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))))
    val Mono = FontFamily(Font(R.font.space_mono))
    /** The headlines' weight: Bold, to stand where Archivo Black did (Medium read thin and small, 2026-11-25). */
    val GroteskBold = FontFamily(Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))))

    /** On, and found (shown in settings once it's been found). */
    val on = MutableStateFlow(false)
    val found = MutableStateFlow(false)

    private fun file(c: Context) = File(c.applicationContext.filesDir, "dot-matrix")
    private fun foundFile(c: Context) = File(c.applicationContext.filesDir, "dot-matrix-found")

    /** Read as the screens start, before anything's drawn: the hand set to match. */
    fun start(c: Context) {
        on.value = file(c).exists()
        found.value = on.value || foundFile(c).exists()
        apply()
    }

    private fun apply() {
        Hand.headline = if (on.value) GroteskBold else null
        Hand.small = if (on.value) Mono else null
        // the player's giant name in Doto's dots: DOT MATRIX's one moment of surprise
        Hand.display = if (on.value) Doto else null
        Hand.dots = on.value
    }

    /** Turned on or off: kept, and the screens drawn again in the new hand. */
    fun set(c: Context, value: Boolean) {
        runCatching { if (value) file(c).createNewFile() else file(c).delete() }
        runCatching { foundFile(c).createNewFile() }
        on.value = value; found.value = true
        apply()
        (c as? Activity)?.recreate()
    }

    /** Concert A: the needle let go within a few hertz of 440. */
    fun isA(hz: Double) = hz in 436.0..444.0
}

/** A pixel cell: a round dot in DOT MATRIX, ink's square otherwise (each pixel piece draws its cells with it). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.cell(color: androidx.compose.ui.graphics.Color, topLeft: androidx.compose.ui.geometry.Offset, size: androidx.compose.ui.geometry.Size) {
    if (Hand.dots) drawCircle(color, minOf(size.width, size.height) * .45f, androidx.compose.ui.geometry.Offset(topLeft.x + size.width / 2, topLeft.y + size.height / 2))
    else drawRect(color, topLeft, size)
}

/** A bar `w` wide rising `h` from `bottom`: dots stacked in DOT MATRIX, one block otherwise. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.cellBar(color: androidx.compose.ui.graphics.Color, x: Float, bottom: Float, w: Float, h: Float) {
    if (!Hand.dots) { drawRect(color, androidx.compose.ui.geometry.Offset(x, bottom - h), androidx.compose.ui.geometry.Size(w, h)); return }
    var y = bottom - w / 2
    while (y >= bottom - h) { drawCircle(color, w * .45f, androidx.compose.ui.geometry.Offset(x + w / 2, y)); y -= w * 1.35f }
}

/** A number shown big: in Doto's dots in DOT MATRIX, as it was otherwise. */
fun numeral(style: androidx.compose.ui.text.TextStyle) =
    if (Hand.dots) style.copy(fontFamily = DotMatrix.Doto, letterSpacing = androidx.compose.ui.unit.TextUnit(-.02f, androidx.compose.ui.unit.TextUnitType.Em)) else style
