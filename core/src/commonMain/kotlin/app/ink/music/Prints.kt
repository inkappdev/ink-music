package app.ink.music

/**
 * How mixes and lists are drawn from their covers (settings, the look, COVERS; 2026-11-11): the dither, tiles or grain,
 * each in dots the dither's size, so each app draws them as it draws the dither (a whole number of pixels a dot) and
 * they develop the same way, frame by frame. Artists' photos, the player and 1-bit mode stay in the dither. (Contours
 * and letters were tried on the phone the same day and left out.)
 */
enum class Print(val word: String) {
    DITHER("dither"),
    // the cover in square blocks of its own six colours, black between: the only one in full colour
    TILES("tiles"),
    // the cover far out of focus, in its own colours, with film's grain over it (Bronson's ask, 2026-11-11)
    GRAIN("grain"),
}

/** The looks' pixels, from a cover drawn `n` x `n` (ARGB, smoothly scaled), one dot a pixel; `frames` steps of it developing. */
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

    /**
     * The cover blurred to its colours' shapes (a box of a twelfth of the side, three passes), a dot's worth of grain
     * over each (the same grain every time for the same size: lighter or darker, all three colours alike); developing
     * as grain does, its dots arriving in a scatter.
     */
    fun grain(px: IntArray, n: Int, frames: Int = 12): List<IntArray> {
        val r = maxOf(1, n / 12)
        val ch = Array(3) { c -> FloatArray(n * n) { i -> (px[i] shr (16 - 8 * c) and 255).toFloat() } }
        val tmp = FloatArray(n * n); val d = 2 * r + 1
        for (a in ch) repeat(3) {
            for (y in 0 until n) for (x in 0 until n) { var s = 0f; for (k in -r..r) s += a[y * n + (x + k).coerceIn(0, n - 1)]; tmp[y * n + x] = s / d }
            for (y in 0 until n) for (x in 0 until n) { var s = 0f; for (k in -r..r) s += tmp[(y + k).coerceIn(0, n - 1) * n + x]; a[y * n + x] = s / d }
        }
        val rnd = kotlin.random.Random(n)
        val done = IntArray(n * n) { i ->
            val g = rnd.nextInt(-20, 21)
            rgb((ch[0][i] + g).toInt().coerceIn(0, 255), (ch[1][i] + g).toInt().coerceIn(0, 255), (ch[2][i] + g).toInt().coerceIn(0, 255))
        }
        val order = FloatArray(n * n) { rnd.nextFloat() }
        return (1..frames).map { f -> val k = f.toFloat() / frames; IntArray(n * n) { i -> if (order[i] < k) done[i] else BLACK } }
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
