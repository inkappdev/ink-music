package app.ink.music

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A stretch of a song's key, for automix (2026-11-08: two songs in keys that clash sound wrong laid over each other,
 * their beats matched or not). Adapted from FireTheDJ's HarmonyAnalyzer (github.com/suryablaze02-byte/FireTheDJ,
 * GPL-3.0): a chromagram from C3 to 2 kHz (below it the kick and sub follow the drums' tuning, not the key), each
 * frame counted alike so a loud drop doesn't outvote the rest, matched against Krumhansl's 24 key profiles; how sure,
 * how clearly it beats the keys that would clash (a relative or neighbouring key mixes fine anyway). Pure Kotlin.
 */
object Key {
    /** A key: its tonic's pitch class (0 C .. 11 B), minor or not, how sure (0 to 1). */
    data class Found(val pc: Int, val minor: Boolean, val sure: Float) {
        /** Its place on the Camelot wheel, DJs' shorthand ("8A"). */
        val camelot get() = "${number(pc, minor)}${if (minor) "A" else "B"}"
    }

    /** Sure enough to go by (FireTheDJ's call of a clear key, scaled 0 to 1). */
    const val SURE = 0.5f

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private const val N = 4096
    private const val HOP = 2048

    /** The key of `mono` (samples at `rate`); null when it's too short or silent. */
    fun find(mono: FloatArray, rate: Int): Found? {
        val k = max(1, (rate / 11_025.0).roundToInt())
        val sr = rate.toDouble() / k
        val n = mono.size / k
        if (n < N * 4) return null
        val x = FloatArray(n) { i -> var s = 0f; for (j in i * k until i * k + k) s += mono[j]; s / k }
        val binHz = sr / N
        val kLo = ceil(130 / binHz).toInt()
        val kHi = min(N / 2 - 1, floor(2000 / binHz).toInt())
        val pcOf = IntArray(N / 2) { b -> if (b < kLo || b > kHi) -1 else floorMod((12 * log2(b * binHz / 440.0) + 69).roundToInt(), 12) }
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
        val re = FloatArray(N); val im = FloatArray(N)
        val total = DoubleArray(12); val frame = DoubleArray(12)
        val frames = (n - N) / HOP + 1
        for (f in 0 until max(0, frames)) {
            val off = f * HOP
            for (i in 0 until N) { re[i] = x[off + i] * window[i]; im[i] = 0f }
            Beat.fft(re, im)
            frame.fill(0.0)
            for (b in kLo..kHi) frame[pcOf[b]] += sqrt((re[b] * re[b] + im[b] * im[b]).toDouble())
            val peak = frame.max()
            if (peak > 1e-9) for (pc in 0 until 12) total[pc] += frame[pc] / peak
        }
        if (total.sum() <= 0) return null
        val scores = HashMap<Pair<Int, Boolean>, Double>()
        for (tonic in 0 until 12) for (minor in listOf(false, true)) {
            val profile = if (minor) MINOR else MAJOR
            scores[tonic to minor] = pearson(total) { pc -> profile[floorMod(pc - tonic, 12)] }
        }
        val (best, score) = scores.maxByOrNull { it.value }!!.toPair()
        val clashing = scores.filterKeys { (pc, m) -> distance(best.first, best.second, pc, m) > 1 }.values.maxOrNull() ?: return null
        val sure = (((score - clashing) / 0.12).coerceIn(0.0, 1.0) * ((score - 0.3) / 0.4).coerceIn(0.0, 1.0)).toFloat()
        return Found(best.first, best.second, sure)
    }

    /** Two keys apart on the wheel: 0 the same, 1 a neighbour or the relative major/minor (mixes cleanly), 2 and up a clash. */
    fun distance(a: Found, b: Found) = distance(a.pc, a.minor, b.pc, b.minor)

    /** Whether two keys, both sure, clash. Unsure of either: not said to. */
    fun clash(a: Found?, b: Found?) = a != null && b != null && a.sure >= SURE && b.sure >= SURE && distance(a, b) >= 2

    private fun number(pc: Int, minor: Boolean) = floorMod(floorMod(pc * 7, 12) + if (minor) 4 else 7, 12) + 1
    private fun distance(pcA: Int, minorA: Boolean, pcB: Int, minorB: Boolean): Int {
        val d = floorMod(number(pcA, minorA) - number(pcB, minorB), 12)
        return min(d, 12 - d) + if (minorA != minorB) 1 else 0
    }
    private fun floorMod(a: Int, b: Int) = ((a % b) + b) % b
    private fun pearson(x: DoubleArray, y: (Int) -> Double): Double {
        val ys = DoubleArray(12) { y(it) }
        val mx = x.average(); val my = ys.average()
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until 12) { num += (x[i] - mx) * (ys[i] - my); dx += (x[i] - mx) * (x[i] - mx); dy += (ys[i] - my) * (ys[i] - my) }
        return if (dx <= 0 || dy <= 0) 0.0 else num / sqrt(dx * dy)
    }
}
