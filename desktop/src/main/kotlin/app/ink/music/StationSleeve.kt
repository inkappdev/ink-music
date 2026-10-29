package app.ink.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A station's sleeve, where a record's cover would be and the station gives none: its name as
 * ink's own sleeve says a record's (Archivo at the plate's foot), and over it the name sending,
 * rings widening from just above its first letter and thinning toward the far corner, 1-bit in
 * the dither of the player's look (Bayer 4 x 4, 3 dp dots, 56 across at the least so a small one
 * still reads), in ink at 55% on black. The rings' spacing and start differ a little a station.
 * While it plays on the player the rings travel outward, a step at a time like an iPod screen.
 */
object StationSleeve {
    /** Frames a ring's travel takes: the rings are periodic, so these are all there are. */
    const val FRAMES = 8
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    // ink at 55% over black, opaque: the dots are drawn up unfiltered, nothing to blend
    private val DOT = Ink.ink.toArgb().let { c ->
        fun k(v: Int) = (v * .55f).toInt()
        (255 shl 24) or (k(c shr 16 and 255) shl 16) or (k(c shr 8 and 255) shl 8) or k(c and 255)
    }

    /** Dots a side for a sleeve `px` across: 3 dp each, 56 to 160. */
    fun cells(px: Float, density: Float) = (px / (3 * density)).roundToInt().coerceIn(56, 160)

    /**
     * The rings, `cells` px a side (drawn up unfiltered). `clear` is how far down (0 to 1) the
     * name's strip starts: it's left black, the rings starting just above it.
     */
    fun rings(id: String, cells: Int, clear: Float, frame: Int = 0): ImageBitmap {
        val s = id.hashCode()
        fun r(k: Int) = ((s ushr (k * 4)) and 15) / 15f
        val ox = .09f + .08f * r(0)
        val oy = clear - .02f
        val lambda = .085f + .035f * r(1)
        val phase = frame.toFloat() / FRAMES
        val out = IntArray(cells * cells)
        for (j in 0 until cells) {
            val y = (j + .5f) / cells
            val open = 1 - smooth(clear - .1f, clear, y)
            if (open <= 0f) { out.fill(0xFF000000.toInt(), j * cells, (j + 1) * cells); continue }
            for (i in 0 until cells) {
                val x = (i + .5f) / cells
                val d = hypot(x - ox, y - oy)
                val band = (.5f + .5f * cos(2 * PI * (d / lambda - phase)).toFloat()).pow(1.6f)
                val fall = (1 - d / 1.15f).coerceAtLeast(0f).pow(.8f)
                val v = band * fall * open * smooth(.03f, .09f, d) * 1.25f
                out[j * cells + i] = if (v > (BAYER[(j and 3) * 4 + (i and 3)] + .5f) / 16) DOT else 0xFF000000.toInt()
            }
        }
        // ARGB ints as Skia's BGRA bytes (little-endian: the same memory)
        val bytes = java.nio.ByteBuffer.allocate(out.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(out) }.array()
        return org.jetbrains.skia.Image.makeRaster(
            org.jetbrains.skia.ImageInfo(cells, cells, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE), bytes, cells * 4,
        ).toComposeImageBitmap()
    }

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
}

/** A station's sleeve on screen; `moving`, its rings travel (the player's, while it plays). */
@Composable
fun StationCover(station: Song, modifier: Modifier = Modifier, moving: Boolean = false) {
    // a white frame round it, the rings filling it all, the name big on a black tab at its lower left, over the rings
    // where they begin (Bronson, 2026-10-28: "a white box around the radio dither", the name "spilling into" it)
    BoxWithConstraints(modifier.background(Ink.black).border(1.dp, Ink.ink)) {
        val side = maxWidth
        val density = LocalDensity.current.density
        val px = side.value * density
        val frames by produceState<List<ImageBitmap>?>(null, station.id, px, moving) {
            val n = StationSleeve.cells(px, density)
            value = withContext(Dispatchers.Default) {
                (0 until if (moving) StationSleeve.FRAMES else 1).map { StationSleeve.rings(station.id, n, .96f, it) }
            }
        }
        var frame by remember { mutableIntStateOf(0) }
        LaunchedEffect(moving, frames) {
            val count = frames?.size ?: return@LaunchedEffect
            while (moving && count > 1) { delay(220); frame = (frame + 1) % count }
        }
        frames?.let { f ->
            Image(f[frame % f.size], null, Modifier.fillMaxSize().padding(1.dp), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
        }
        Box(Modifier.fillMaxSize().padding(start = 1.dp, bottom = side / 12), contentAlignment = Alignment.BottomStart) {
            // as big as the sleeve allows, but its longest word whole on a line (CASHMERE broke as CASHME / RE)
            val longest = station.title.split(' ').maxOfOrNull { it.length } ?: 1
            val size = minOf(side.value / 5.6f, side.value * .84f / (longest * .74f))
            BasicText(
                station.title.uppercase(), style = headline(size.sp).copy(lineHeight = (size * 1.02f).sp),
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(Ink.black).padding(start = side / 14, end = side / 20, top = side / 40),
            )
        }
    }
}
