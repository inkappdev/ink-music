package app.ink.music

/**
 * How mixes and lists are drawn from their covers (settings, the look, COVERS; 2026-11-11): the dither, or one of three
 * more, each in dots the dither's size, so each app draws them as it draws the dither (a whole number of pixels a dot)
 * and they develop the same way, frame by frame. Artists' photos, the player and 1-bit mode stay in the dither.
 */
enum class Print(val word: String) {
    DITHER("dither"),
    // the cover in square blocks of its own six colours, black between: the only one in full colour
    TILES("tiles"),
    // the cover read as a map: a line wherever its brightness crosses a level, in its colour on black
    CONTOURS("contours"),
    // the list's name typed over and over in Silkscreen, each letter one of ink's brightness steps
    LETTERS("letters"),
}

/** The three looks' pixels, from a cover drawn `n` x `n` (ARGB, smoothly scaled), one dot a pixel; `frames` steps of it developing. */
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

    /** Lines a dot wide where the softened brightness crosses one of [LEVELS] levels, brighter for the brighter levels; the brightest come first. */
    fun contours(px: IntArray, n: Int, accent: Int, frames: Int = 12): List<IntArray> {
        // smoothed a lot (about a fortieth of the side): a map of the cover's big shapes, not its every speck
        val lum = soft(lum(px), n, 3, maxOf(1, n / 36))
        val lo = lum.min(); val span = (lum.max() - lo).coerceAtLeast(.01f)
        val q = IntArray(n * n) { (((lum[it] - lo) / span) * LEVELS).toInt().coerceAtMost(LEVELS - 1) }
        // each dot on a line: the higher of the two levels it parts (-1: no line)
        val line = IntArray(n * n) { i ->
            val x = i % n; val y = i / n
            val a = q[i]
            val r = if (x + 1 < n) q[i + 1] else a
            val d = if (y + 1 < n) q[i + n] else a
            if (a != r || a != d) maxOf(a, r, d) else -1
        }
        val steps = intArrayOf(shade(accent, .4f), shade(accent, .7f), accent)
        return (1..frames).map { f ->
            val from = LEVELS - (f.toFloat() / frames * LEVELS).toInt().coerceAtLeast(1)
            IntArray(n * n) { i ->
                val k = line[i]
                if (k < from || k < 0) BLACK else steps[when { k > LEVELS * .6f -> 2; k > LEVELS * .3f -> 1; else -> 0 }]
            }
        }
    }

    /**
     * `word` typed over and over in Silkscreen's 5 x 5 letters, a letter and a dot of black each way to a cell, each lit
     * by its cell's brightness at ink's steps (all of it, 70%, 35%) or left out where it's dark; typed in, in reading order.
     */
    fun letters(px: IntArray, n: Int, accent: Int, word: String, frames: Int = 12): List<IntArray> {
        val text = word.lowercase().filter { it in GLYPHS }.ifEmpty { "ink" }
        val cells = (n + CELL - 1) / CELL
        val lum = lum(px)
        val cell = FloatArray(cells * cells) { i ->
            val cx = i % cells; val cy = i / cells
            var s = 0f; var k = 0
            for (y in cy * CELL until minOf(n, cy * CELL + CELL)) for (x in cx * CELL until minOf(n, cx * CELL + CELL)) { s += lum[y * n + x]; k++ }
            if (k == 0) 0f else s / k
        }
        val lo = cell.min(); val span = (cell.max() - lo).coerceAtLeast(.01f)
        val colour = IntArray(cell.size) { i ->
            val v = (cell[i] - lo) / span
            when { v > .55f -> accent; v > .3f -> shade(accent, .7f); v > .1f -> shade(accent, .35f); else -> 0 }
        }
        return (1..frames).map { f ->
            val typed = (f.toFloat() / frames * cell.size).toInt()
            IntArray(n * n) { i ->
                val x = i % n; val y = i / n
                val cx = x / CELL; val cy = y / CELL; val c = cy * cells + cx
                val gx = x % CELL; val gy = y % CELL
                if (c >= typed || gx >= 5 || gy >= 5 || colour[c] == 0) BLACK
                else {
                    val g = GLYPHS.getValue(text[c % text.length])
                    if (g shr (24 - (gy * 5 + gx)) and 1 == 1) colour[c] else BLACK
                }
            }
        }
    }

    private const val TILE = 6
    private const val CELL = 6
    private const val LEVELS = 8

    private fun rgb(r: Int, g: Int, b: Int) = BLACK or (r shl 16) or (g shl 8) or b
    private fun shade(c: Int, k: Float) = rgb(((c shr 16 and 255) * k).toInt(), ((c shr 8 and 255) * k).toInt(), ((c and 255) * k).toInt())
    private fun lum(px: IntArray) = FloatArray(px.size) { i -> val p = px[i]; (.3f * (p shr 16 and 255) + .59f * (p shr 8 and 255) + .11f * (p and 255)) / 255 }

    /** Box-blurred `passes` times each way, `r` dots each side, edges held. */
    private fun soft(v: FloatArray, n: Int, passes: Int, r: Int): FloatArray {
        val a = v.copyOf(); val b = FloatArray(a.size); val d = 2 * r + 1
        repeat(passes) {
            for (y in 0 until n) for (x in 0 until n) { var s = 0f; for (k in -r..r) s += a[y * n + (x + k).coerceIn(0, n - 1)]; b[y * n + x] = s / d }
            for (y in 0 until n) for (x in 0 until n) { var s = 0f; for (k in -r..r) s += b[(y + k).coerceIn(0, n - 1) * n + x]; a[y * n + x] = s / d }
        }
        return a
    }

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

    /** Silkscreen's letters and digits, 5 x 5, a bit a dot from the top left (q without its tail). */
    private val GLYPHS = mapOf(
        'a' to 0x0C97A52, 'b' to 0x1C97A5C, 'c' to 0x0C9424C, 'd' to 0x1C94A5C, 'e' to 0x0E4390E, 'f' to 0x0E43908,
        'g' to 0x0E85A4C, 'h' to 0x1297A52, 'i' to 0x0421084, 'j' to 0x0210A4C, 'k' to 0x12A6292, 'l' to 0x084210E,
        'm' to 0x11DD631, 'n' to 0x11CD671, 'o' to 0x0C94A4C, 'p' to 0x1C97210, 'q' to 0x0C94A4C, 'r' to 0x1C97292,
        's' to 0x0E8305C, 't' to 0x0E21084, 'u' to 0x1294A4C, 'v' to 0x118A944, 'w' to 0x11AD6AA, 'x' to 0x1151151,
        'y' to 0x1151084, 'z' to 0x0E1110E, '0' to 0x0C94A4C, '1' to 0x0C2108E, '2' to 0x1C1321E, '3' to 0x1C1305C,
        '4' to 0x14A7884, '5' to 0x1E8705C, '6' to 0x0C8724C, '7' to 0x1E11108, '8' to 0x0C9324C, '9' to 0x0C9384C,
    )
}
