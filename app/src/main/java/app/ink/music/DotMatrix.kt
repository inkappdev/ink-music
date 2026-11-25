package app.ink.music

import android.app.Activity
import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp

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
        if (on.value) android.util.Log.i("ink-moving", "dot matrix: moving dots ${if (dotsBuild()) "build" else "won't build here"}")
    }

    private fun apply() {
        Hand.headline = if (on.value) GroteskBold else null
        Hand.small = if (on.value) Mono else null
        // the player's giant name in Doto's dots: DOT MATRIX's one moment of surprise
        Hand.display = if (on.value) Doto else null
        Hand.dots = on.value
    }

    /**
     * Turned on or off with the full screen flip (Flip), from `from` (window px; null, the middle): kept, and the screens
     * drawn again in the new hand once the flip has covered them.
     */
    fun flip(c: Context, value: Boolean, from: androidx.compose.ui.geometry.Offset? = null) {
        Flip.start(from, value) { set(c, value) }
    }

    /** Turned on or off: kept, and the screens drawn again in the new hand. */
    fun set(c: Context, value: Boolean) {
        runCatching { if (value) file(c).createNewFile() else file(c).delete() }
        runCatching { foundFile(c).createNewFile() }
        on.value = value; found.value = true
        apply()
        // the screens drawn again in the new hand: the activity found through any wrapper round it (from the look page
        // the context is wrapped, and nothing was redrawn: the giant name stayed in Doto, 2026-11-25)
        var a: Context? = c
        while (a is android.content.ContextWrapper && a !is Activity) a = a.baseContext
        (a as? Activity)?.recreate()
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

/**
 * The dots print (Print.DOTS): the picture as a halftone, a grid of round dots (about 26 across, 3 to 7 dp apart), each its patch's average
 * colour (lifted a little, so a dark cover still shows its colour) and as wide as the patch is light, on black. It
 * develops as the other prints do, the brightest dots first, over 900 ms (at once with less motion).
 */
@androidx.compose.runtime.Composable
fun DotPrint(photo: Song, modifier: androidx.compose.ui.Modifier, px: Int = 1080, soft: Boolean = false) {
    val cover = rememberCover(photo, px)
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(androidx.compose.ui.graphics.Color.Black)) {
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        // about 26 dots across, 3 to 7 dp apart: a small tile still reads, a big one keeps big dots
        val step = (minOf(w, h) / 26f).coerceIn(with(density) { 3.dp.toPx() }, with(density) { 7.dp.toPx() })
        val cols = (w / step).toInt().coerceAtLeast(1); val rows = (h / step).toInt().coerceAtLeast(1)
        // each dot's colour and lightness, read off the cover drawn a pixel a dot
        val grid by androidx.compose.runtime.produceState<Pair<IntArray, FloatArray>?>(null, cover, cols, rows, soft) {
            val c = cover ?: return@produceState
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val src = c.image.asAndroidBitmap().let { if (it.config == android.graphics.Bitmap.Config.HARDWARE) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it }
                // cropped to fill, as the cover would be
                val side = minOf(src.width, src.height)
                val cut0 = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                // soft dots: the picture through the lens's blur first
                val cut = if (soft) Grain.lens(cut0) else cut0
                val s = maxOf(cols, rows)
                val small = android.graphics.Bitmap.createScaledBitmap(cut, s, s, true)
                val colour = IntArray(cols * rows); val light = FloatArray(cols * rows)
                val ox = (s - cols) / 2; val oy = (s - rows) / 2
                for (y in 0 until rows) for (x in 0 until cols) {
                    val p = small.getPixel(x + ox, y + oy)
                    val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
                    val l = (r * 299 + g * 587 + b * 114) / 255_000f
                    val k = if (l < .35f) 1.35f else 1f
                    colour[y * cols + x] = (255 shl 24) or ((r * k).toInt().coerceAtMost(255) shl 16) or ((g * k).toInt().coerceAtMost(255) shl 8) or (b * k).toInt().coerceAtMost(255)
                    light[y * cols + x] = l
                }
                colour to light
            }
        }
        val shown = androidx.compose.runtime.remember(grid) { androidx.compose.animation.core.Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(grid) {
            if (grid != null) { if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, androidx.compose.animation.core.tween(900, easing = Motion.expo)) }
        }
        grid?.let { (colour, light) ->
            androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
                val dx = size.width / cols; val dy = size.height / rows
                val max = minOf(dx, dy) * .48f
                val t = shown.value
                for (y in 0 until rows) for (x in 0 until cols) {
                    val i = y * cols + x
                    val l = light[i]
                    // the brightest first: a dot appears once the developing has passed its darkness
                    if (1f - l > t * 1.1f) continue
                    val r = max * (.18f + .82f * kotlin.math.sqrt(l))
                    drawCircle(androidx.compose.ui.graphics.Color(colour[i]), r, androidx.compose.ui.geometry.Offset((x + .5f) * dx, (y + .5f) * dy))
                }
            }
        }
    }
}

/**
 * A list's cover in DOT MATRIX (2026-11-25): a field of round dots over its top, in the colours of its first four
 * records (each cover's accent, one a ring; none, ink), their sizes rippling out from a corner, the pattern and corner
 * seeded by the list's name so a list always looks itself; under it, on black, its name in Doto.
 */
@androidx.compose.runtime.Composable
fun DotListCover(songs: List<Song>, name: String) {
    val four = androidx.compose.runtime.remember(songs) { songs.distinctBy { it.albumId }.take(4) }
    // each record's colour (the same four calls each time, so the composition holds still)
    val accents = (0 until 4).mapNotNull { i -> four.getOrNull(i)?.let { rememberCover(it, 120)?.accent } }
    val colours = accents.ifEmpty { listOf(Ink.ink) }
    val seed = name.hashCode()
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize().background(Ink.black)) {
        val side = maxWidth
        androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
            val cols = 14; val rows = 9
            val step = size.width / cols
            val rnd = java.util.Random(seed.toLong())
            // the ripple's centre: one of the top corners, or the middle of the top, by the name
            val cx = listOf(0f, cols / 2f, cols.toFloat())[Math.floorMod(seed, 3)]; val cy = 0f
            val waves = 2.2f + rnd.nextFloat() * 1.6f
            for (y in 0 until rows) for (x in 0 until cols) {
                val d = kotlin.math.hypot(x + .5f - cx, y + .5f - cy) / cols
                val wave = (kotlin.math.sin(d * waves * 6.283f) + 1f) / 2f
                val fade = 1f - y.toFloat() / rows * .55f
                val r = step * .44f * (.25f + .75f * wave) * fade
                // one colour a ring, so the ripple reads
                val c = colours[Math.floorMod((d * waves * 2f).toInt(), colours.size)]
                drawCircle(c, r, androidx.compose.ui.geometry.Offset((x + .5f) * step, (y + .5f) * step))
            }
        }
        val size = with(androidx.compose.ui.platform.LocalDensity.current) { (side * .16f).toSp() }
        androidx.compose.foundation.text.BasicText(name.uppercase(), style = androidx.compose.ui.text.TextStyle(fontFamily = DotMatrix.Doto, fontSize = size, lineHeight = size * 1.05f, color = Ink.ink),
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(10.dp))
    }
}
