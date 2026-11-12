package app.ink.music

/**
 * How mixes and lists are drawn from their covers (settings, the look, COVERS; 2026-11-11): the dither, tiles or grain.
 * Tiles in dots the dither's size, so each app draws them as it draws the dither (a whole number of pixels a dot) and
 * they develop the same way, frame by frame; grain a lens's blur with film's grain over it, a screen pixel a grain.
 * The player and 1-bit mode stay in the dither. (Contours and letters were tried on the phone the same day and left out.)
 */
enum class Print(val word: String) {
    DITHER("dither"),
    // the cover in square blocks of its own six colours, black between: the only one in full colour
    TILES("tiles"),
    // the cover out of focus as a lens has it, film's grain over it (Bronson's ask, 2026-11-11: "a soft bokeh with a nice film like grain")
    // called blur where it's chosen (Bronson: "just call grain blur"); GRAIN by name, as kept
    GRAIN("blur"),
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
     * The cover out of focus as a lens has it (the computer's and the phone's grain look, 2026-11-11): each pixel the
     * average of a disc around it, a tenth of the side less a tenth across, the light counting more than the dark
     * (as light through a lens does), so bright spots open into soft discs. Small (`n` about 120): drawn up smoothly.
     */
    fun bokeh(px: IntArray, n: Int): IntArray {
        val r = maxOf(2, (n * .09f).toInt())
        val disc = ArrayList<Int>()
        for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r + r) { disc.add(dx); disc.add(dy) }
        // linear light (gamma 2.4), each pixel weighed up by how bright it is past the middle
        val lin = Array(3) { c -> FloatArray(n * n) { i -> pow24((px[i] shr (16 - 8 * c) and 255) / 255f) } }
        val w = FloatArray(n * n) { i -> val l = .3f * lin[0][i] + .59f * lin[1][i] + .11f * lin[2][i]; 1f + 18f * pow15(maxOf(0f, l - .55f)) }
        return IntArray(n * n) { i ->
            val x = i % n; val y = i / n
            var r0 = 0f; var g0 = 0f; var b0 = 0f; var sum = 0f
            var k = 0
            while (k < disc.size) {
                val j = (y + disc[k + 1]).coerceIn(0, n - 1) * n + (x + disc[k]).coerceIn(0, n - 1)
                val q = w[j]; r0 += lin[0][j] * q; g0 += lin[1][j] * q; b0 += lin[2][j] * q; sum += q
                k += 2
            }
            rgb(back(r0 / sum), back(g0 / sum), back(b0 / sum))
        }
    }

    /**
     * Film's grain, a `side` square tile to repeat a screen pixel a pixel over a picture: each pixel white or black at an
     * alpha of a bell curve's draw (a heavy grain, Bronson's pick: pushed film), so it lightens and darkens alike and
     * shows most in the midtones. The same tile for the same `seed`.
     */
    fun grainTile(side: Int = 256, seed: Int = 7, strength: Float = .075f): IntArray {
        val rnd = kotlin.random.Random(seed)
        return IntArray(side * side) {
            // a bell curve's draw from two uniform ones (Box-Muller)
            val g = kotlin.math.sqrt(-2f * kotlin.math.ln(rnd.nextFloat().coerceAtLeast(1e-6f))) * kotlin.math.cos(6.2831855f * rnd.nextFloat())
            val a = (kotlin.math.abs(g) * strength * 255).toInt().coerceIn(0, 255)
            (a shl 24) or (if (g > 0) 0xFFFFFF else 0)
        }
    }

    private fun pow24(v: Float) = kotlin.math.exp(2.4f * kotlin.math.ln(v.coerceAtLeast(1e-4f)))
    private fun pow15(v: Float) = v * kotlin.math.sqrt(v)
    private fun back(v: Float) = (255 * kotlin.math.exp(kotlin.math.ln(v.coerceIn(1e-5f, 1f)) / 2.4f)).toInt().coerceIn(0, 255)

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
