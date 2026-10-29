package app.ink.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Radio's header (Bronson, 2026-10-29: "a big cool pixel radio antenna"): a lattice tower drawn dot by dot, ink on
 * black, its signal going out from the tip as rings in the sleeves' dither (Bayer 4 x 4), in the colour of the station
 * playing (ink at 55% with none); the apps draw it 3 dp a dot, travelling outward a step at a time while a station
 * plays. Plain Kotlin, shared: the dots as ARGB, each app makes its own picture of them.
 */
object RadioTower {
    const val FRAMES = 8
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private const val BLACK = 0xFF000000.toInt()

    /** The banner `w` x `h` dots: the tower at `at` across (0 to 1), its tip a fifth down, the rings round the tip in `dot`. */
    fun pixels(w: Int, h: Int, at: Float, frame: Int, ink: Int, dot: Int): IntArray {
        val out = IntArray(w * h) { BLACK }
        val tipX = (w * at).toInt(); val tipY = (h * .2f).toInt()
        val base = h - 3
        val phase = frame.toFloat() / FRAMES
        val lambda = h * .16f
        // the signal: rings round the tip, fading with distance, the far side (where the words are) quieter
        for (j in 0 until h) for (i in 0 until w) {
            val d = hypot((i - tipX).toFloat(), (j - tipY).toFloat())
            if (d < h * .05f) continue
            val band = (.5f + .5f * cos(2 * PI * (d / lambda - phase)).toFloat()).pow(2.2f)
            val reach = (1 - d / (h * 1.25f)).coerceAtLeast(0f).pow(.9f)
            val v = band * reach * 1.15f
            if (v > (BAYER[(j and 3) * 4 + (i and 3)] + .5f) / 16) out[j * w + i] = dot
        }
        fun dot(x: Int, y: Int) { if (x in 0 until w && y in 0 until h) out[y * w + x] = ink }
        fun line(x0: Int, y0: Int, x1: Int, y1: Int) {
            var x = x0; var y = y0
            val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
            var err = dx + dy
            while (true) {
                dot(x, y)
                if (x == x1 && y == y1) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x += sx }
                if (e2 <= dx) { err += dx; y += sy }
            }
        }
        // the black round the tower, so the rings don't run through its lattice
        val top = tipY + (h * .1f).toInt()
        val half = { y: Int -> 1 + ((y - top).toFloat() / (base - top) * h * .14f).toInt() }
        for (y in tipY - 2..base) {
            val hw = if (y < top) 2 else half(y) + 2
            for (x in tipX - hw..tipX + hw) if (x in 0 until w && y in 0 until h) out[y * w + x] = BLACK
        }
        // the mast and its ball
        line(tipX, tipY + 2, tipX, top)
        for (y in -1..1) for (x in -1..1) if (abs(x) + abs(y) < 2) dot(tipX + x, tipY + y)
        // the legs, narrow at the top, wide at the foot
        line(tipX - 1, top, tipX - half(base), base); line(tipX + 1, top, tipX + half(base), base)
        // the lattice: a strut every step, an X across each bay, the bays taller toward the foot
        var y = top; var step = max(3, h / 30)
        var bays = 0
        while (y < base - 2 && bays < 40) {
            val next = minOf(base, y + step)
            line(tipX - half(y), y, tipX + half(y), y)
            line(tipX - half(y), y, tipX + half(next), next); line(tipX + half(y), y, tipX - half(next), next)
            y = next; step = (step * 1.12f).toInt().coerceAtLeast(step + 1); bays++
        }
        line(tipX - half(base), base, tipX + half(base), base)
        // the ground
        line((tipX - h * .32f).toInt(), base + 1, (tipX + h * .32f).toInt(), base + 1)
        return out
    }
}
