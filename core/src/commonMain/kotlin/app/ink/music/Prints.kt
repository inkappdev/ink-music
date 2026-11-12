package app.ink.music

/**
 * How mixes and lists are drawn from their covers (settings, the look, COVERS; 2026-11-11): the dither or tiles, the
 * tiles in dots the dither's size, so each app draws them as it draws the dither (a whole number of pixels a dot) and
 * they develop the same way, frame by frame. Artists' photos, the player and 1-bit mode stay in the dither. (Contours
 * and letters were tried on the phone the same day and left out.)
 */
enum class Print(val word: String) {
    DITHER("dither"),
    // the cover in square blocks of its own six colours, black between: the only one in full colour
    TILES("tiles"),
}

/** The tiles' pixels, from a cover drawn `n` x `n` (ARGB, smoothly scaled), one dot a pixel; `frames` steps of it developing. */
object Prints {
    private const val BLACK = 0xFF000000.toInt()

    /** Blocks [TILE] dots square, one dot of black between, each its square's average snapped to the cover's six colours; lit in a sweep from the top left. */
    fun tiles(px: IntArray, n: Int, frames: Int = 12): List<IntArray> {
        val cells = (n + TILE - 1) / TILE
        val avg = IntArray(cells * cells) { i ->
            val cx = i % cells; val cy = i / cells
            var r = 0; var g = 0; var b = 0; var k = 0
            for (y in cy * TILE until minOf(n, cy * TILE + TILE)) for (x in cx * TILE until minOf(n, cx * TILE + TILE)) {
                val p = px[y * n + x]; r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255; k++
            }
            if (k == 0) BLACK else rgb(r / k, g / k, b / k)
        }
        val six = palette(avg, 6)
        val snapped = IntArray(avg.size) { nearest(avg[it], six) }
        val span = 2 * cells - 1
        return (1..frames).map { f ->
            val lit = f.toFloat() / frames * span
            IntArray(n * n) { i ->
                val x = i % n; val y = i / n
                val cx = x / TILE; val cy = y / TILE
                if (x % TILE == TILE - 1 || y % TILE == TILE - 1 || cx + cy >= lit) BLACK else snapped[cy * cells + cx]
            }
        }
    }

    private const val TILE = 6

    private fun rgb(r: Int, g: Int, b: Int) = BLACK or (r shl 16) or (g shl 8) or b

    private fun dist(a: Int, b: Int): Int {
        val r = (a shr 16 and 255) - (b shr 16 and 255); val g = (a shr 8 and 255) - (b shr 8 and 255); val bl = (a and 255) - (b and 255)
        return 2 * r * r + 4 * g * g + 3 * bl * bl
    }
    private fun nearest(c: Int, of: IntArray) = of.minBy { dist(c, it) }

    /** The colours' `k` most telling, by k-means from their brightness's spread (the same every time for the same cover). */
    private fun palette(cs: IntArray, k: Int): IntArray {
        val sorted = cs.sortedBy { (it shr 16 and 255) * 3 + (it shr 8 and 255) * 6 + (it and 255) }
        val centres = IntArray(k) { sorted[(it * 2 + 1) * sorted.size / (2 * k)] }
        repeat(8) {
            val sum = Array(k) { IntArray(4) }
            for (c in cs) {
                val j = centres.indices.minBy { dist(c, centres[it]) }
                sum[j][0] += c shr 16 and 255; sum[j][1] += c shr 8 and 255; sum[j][2] += c and 255; sum[j][3]++
            }
            for (j in 0 until k) if (sum[j][3] > 0) centres[j] = rgb(sum[j][0] / sum[j][3], sum[j][1] / sum[j][3], sum[j][2] / sum[j][3])
        }
        return centres
    }
}
