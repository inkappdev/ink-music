package app.ink.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A stretch of a song's beat, for automix (2026-11-08; the kick-only reading before it was rarely sure: most songs
 * 1.4 to 1.5 against the 1.6 it needed, so automix was mostly the plain crossfade). Adapted from Prism's MixTempo
 * (github.com/Yigder/Prism, GPL-3.0, Yigder): the onsets from 16 bands' rises (80 Hz to 5 kHz, not the low end alone:
 * a song without a kick has a beat too), the tempo the autocorrelation lag that stands out most with its two and four
 * beats on backing it, leaning toward 120 bpm so a half or double time doesn't win; a fast reading halved when every
 * other beat leads; the period refined by laying a comb of beats over the whole stretch; how sure, the peak's z-score.
 * Pure Kotlin, shared: the phone's automix and the computer's.
 */
object Beat {
    /**
     * Its period (ms), one beat's time (ms into the song), how sure (z-score: [SURE] and up is matched); where a bar
     * begins (ms: a downbeat, bar one), and each whole bar in the stretch, from there, with its loudness (0 to 1).
     */
    data class Found(val period: Double, val first: Double, val sure: Double, val downbeat: Double = first, val bars: List<Bar> = emptyList()) {
        val bpm get() = 60_000 / period
        /** The bar start (downbeat) nearest `t`, the bars' grid laid on. */
        fun barNear(t: Double) = downbeat + kotlin.math.round((t - downbeat) / (4 * period)).toLong() * 4 * period
    }

    /** A bar: where it begins (ms into the song), how loud against the stretch's loudest (0 to 1). */
    data class Bar(val at: Double, val loud: Float)

    /**
     * How sure a beat must be to be matched: on the 50 test songs' ends and starts (100 stretches, librosa's tempo for
     * the whole song as the reference), 3.5 and up was 70 of them, 60 right; Prism's own 3.0, 90 and 71. A wrong
     * match is worse than none (two beats fighting), so the stricter. The old kick-only reading at its 1.6: 47, 29 right.
     */
    const val SURE = 3.5

    private const val BANDS = 16
    private const val WINDOW = 512
    private const val HALVE_ABOVE_BPM = 152.0

    /** The beat of `mono` (samples at `rate`), which begins `startMs` into the song; null under 10 s or silent. */
    fun find(mono: FloatArray, rate: Int, startMs: Long): Found? {
        // about 11 kHz is plenty for onsets (and a 512 window then 46 ms, its bins 21 Hz: the low bands apart)
        val k = max(1, (rate / 11_025.0).roundToInt())
        val sr = rate / k
        val n = mono.size / k
        val x = FloatArray(n) { i -> var s = 0f; for (j in i * k until i * k + k) s += mono[j]; s / k }
        val bands = bands(x, sr)
        val frames = bands.firstOrNull()?.size ?: return null
        val fps = sr.toDouble() / (sr / 100)
        if (frames < 10 * fps) return null

        // onsets: the rises in each band's energy summed, less their local average (±0.2 s), kept positive
        val flux = FloatArray(frames)
        for (t in 1 until frames) { var s = 0f; for (b in bands) { val d = b[t] - b[t - 1]; if (d > 0) s += d }; flux[t] = s }
        val half = (0.2 * fps).toInt().coerceAtLeast(1)
        val pre = DoubleArray(frames + 1).also { for (i in 0 until frames) it[i + 1] = it[i] + flux[i] }
        val od = FloatArray(frames) { t ->
            val lo = max(0, t - half); val hi = min(frames, t + half + 1)
            max(0.0, flux[t] - (pre[hi] - pre[lo]) / (hi - lo)).toFloat()
        }
        // quiet at either end (a fade, a start): beats looked for where there's sound
        val energy = FloatArray(frames) { t -> var m = Float.NEGATIVE_INFINITY; for (b in bands) m = max(m, b[t]); m }
        val peak = energy.sortedArray().let { it[(it.size * .95).toInt().coerceAtMost(it.lastIndex)] }
        val first = energy.indexOfFirst { it > peak - 9.2f }.takeIf { it >= 0 } ?: return null
        val last = energy.indexOfLast { it > peak - 9.2f }
        val from = first; val to = last + 1
        val lagMin = (fps * 60 / 200).toInt()
        val lagMax = (fps * 60 / 60).toInt() + 1
        val acfMax = lagMax * 4
        if (to - from <= acfMax * 2) return null
        val acf = DoubleArray(acfMax + 1)
        for (lag in lagMin..acfMax) {
            var s = 0.0
            for (t in from until to - lag) s += od[t].toDouble() * od[t + lag]
            acf[lag] = s / (to - from - lag)
        }
        fun weighted(lag: Int): Double {
            val bpm = 60 * fps / lag
            val prior = exp(-0.5 * sq(ln(bpm / 120.0) / ln(2.0) / 0.9))
            // a beat lines up with itself two and four beats on (a bar); a triplet feel's 3:2 doesn't
            return (acf[lag] + 0.5 * acf[2 * lag] + 0.25 * acf[4 * lag]) * prior
        }
        val scores = (lagMin..lagMax).map(::weighted)
        val bestIdx = scores.indices.maxByOrNull { scores[it] } ?: return null
        val best = lagMin + bestIdx
        val mean = scores.average()
        val sd = sqrt(scores.sumOf { sq(it - mean) } / scores.size).takeIf { it > 1e-12 } ?: return null
        val sure = (scores[bestIdx] - mean) / sd
        var lag = if (best in lagMin + 1 until lagMax) {
            val a = acf[best - 1]; val b = acf[best]; val c = acf[best + 1]; val d = a - 2 * b + c
            best + (if (d < 0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0)
        } else best.toDouble()

        fun comb(start: Double, end: Int, period: Double): Double { var s = 0.0; var t = start; while (t < end - 1) { s += at(od, t); t += period }; return s }
        fun phase(a: Int, b: Int, period: Double): Double {
            var bestPhase = 0.0; var bestScore = -1.0; var ph = 0.0
            while (ph < period) { val s = comb(a + ph, b, period); if (s > bestScore) { bestScore = s; bestPhase = ph }; ph += 0.5 }
            return a + bestPhase
        }
        // a fast reading may be a song's eighth notes: halved when every other beat clearly leads
        if (60 * fps / lag > HALVE_ABOVE_BPM && 60 * fps / (2 * lag) >= 70) {
            val strong = phase(from, to, 2 * lag)
            if (comb(strong, to, 2 * lag) > 1.25 * comb(strong + lag, to, 2 * lag)) lag *= 2
        }
        // the exact period: the one whose comb lands on the most onsets over the whole stretch (±1.5%)
        run {
            var bestP = lag; var bestS = -1.0; var p = lag * .985
            while (p <= lag * 1.015) {
                var ph = 0.0
                while (ph < p) { val s = comb(from + ph, to, p); if (s > bestS) { bestS = s; bestP = p }; ph += 1.0 }
                p += lag * .0005
            }
            lag = bestP
        }
        val frameMs = 1000.0 / fps
        // a frame's energy is its window's: its time the window's middle
        val offset = WINDOW / 2 * 1000.0 / sr
        val beat = phase(from, to, lag)

        // bar one (FireTheDJ's way, github.com/suryablaze02-byte/FireTheDJ, GPL-3.0): a song's sections start and stop
        // on bar starts, so of the four places a bar could begin, the one where the loudness changes most often
        val perBeat = lag * hop(sr)
        fun rms(fromS: Double, toS: Double): Double {
            val a = fromS.toInt().coerceIn(0, n); val b = toS.toInt().coerceIn(0, n)
            if (b <= a) return 0.0
            var e = 0.0; for (i in a until b) e += x[i] * x[i]; return sqrt(e / (b - a))
        }
        val beat0 = beat * hop(sr)
        val beats = ((n - beat0) / perBeat).toInt()
        val beatRms = DoubleArray(max(0, beats)) { rms(beat0 + it * perBeat, beat0 + (it + 1) * perBeat) }
        val bar0 = if (beats >= 12) bestOffset(novelty(beatRms, 4), 4) else 0
        val downS = beat0 + bar0 * perBeat
        val barS = 4 * perBeat
        val barCount = ((n - downS) / barS).toInt()
        val raw = DoubleArray(max(0, barCount)) { rms(downS + it * barS, downS + (it + 1) * barS) }
        val top = raw.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val toMs = 1000.0 / sr
        val bars = raw.indices.map { Bar(startMs + (downS + it * barS) * toMs + offset, (raw[it] / top).toFloat()) }
        return Found(lag * frameMs, startMs + beat * frameMs + offset, sure, startMs + downS * toMs + offset, bars)
    }

    private fun hop(sr: Int) = (sr / 100).toDouble()

    /** At each place, how different the next `w` are from the last `w` (a section starting or stopping). */
    private fun novelty(x: DoubleArray, w: Int) = DoubleArray(x.size) { i ->
        if (i < w || i + w > x.size) 0.0 else kotlin.math.abs((i until i + w).sumOf { x[it] } - (i - w until i).sumOf { x[it] }) / w
    }

    /** The place (mod `period`) where novelty's peaks fall, only the peaks counted (a section's own wiggles can't outvote it). */
    private fun bestOffset(scores: DoubleArray, period: Int): Int {
        val totals = DoubleArray(period)
        for (i in scores.indices) {
            val l = if (i > 0) scores[i - 1] else 0.0; val r = if (i + 1 < scores.size) scores[i + 1] else 0.0
            if (scores[i] > l && scores[i] >= r) totals[i % period] += scores[i] * scores[i]
        }
        return totals.indices.maxByOrNull { totals[it] } ?: 0
    }

    /** Log energy in 16 log-spaced bands (80 Hz to 5 kHz) every 10 ms, [band][frame]. */
    private fun bands(pcm: FloatArray, sr: Int): Array<FloatArray> {
        val hop = sr / 100
        val frames = max(0, (pcm.size - WINDOW) / hop)
        val edges = IntArray(BANDS + 1) { b -> (80.0 * (5000.0 / 80.0).pow(b.toDouble() / BANDS) * WINDOW / sr).toInt().coerceIn(1, WINDOW / 2) }
        val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1))).toFloat() }
        val out = Array(BANDS) { FloatArray(frames) }
        val re = FloatArray(WINDOW); val im = FloatArray(WINDOW)
        for (t in 0 until frames) {
            val off = t * hop
            for (i in 0 until WINDOW) { re[i] = pcm[off + i] * hann[i]; im[i] = 0f }
            fft(re, im)
            for (b in 0 until BANDS) {
                var e = 0f
                for (j in edges[b] until max(edges[b] + 1, edges[b + 1])) e += re[j] * re[j] + im[j] * im[j]
                out[b][t] = ln(e + 1e-6f)
            }
        }
        return out
    }

    internal fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun sq(x: Double) = x * x
    private fun at(x: FloatArray, t: Double): Double {
        val i = floor(t).toInt()
        if (i < 0 || i + 1 >= x.size) return 0.0
        val f = t - i
        return x[i] * (1 - f) + x[i + 1] * f
    }
}
