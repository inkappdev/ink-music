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
    val Doto = FontFamily(Font(R.font.doto, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))))
    val Grotesk = FontFamily(Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))))
    val Mono = FontFamily(Font(R.font.space_mono))

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
        Hand.headline = if (on.value) Grotesk else null
        Hand.small = if (on.value) Mono else null
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
