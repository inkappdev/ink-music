package app.ink.music

import java.io.DataInputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A song's beats and bar ones read by a trained model (2026-11-26): Beat This! (Foscarin, Schlüter and Widmer, ISMIR 2024;
 * github.com/CPJKU/beat_this, its code and weights MIT, Institute of Computational Perception, JKU Linz), its small
 * model (2.1M weights, kept as float16 in `beat_this_small.bin`, 4.2 MB), run here in plain Kotlin: no native library,
 * the same on the phone and the computer. Where Beat's own reading (onsets and an autocorrelation) rarely knows which beat
 * begins a bar and misreads 3:2, the model was trained on thousands of songs marked by hand, beats and downbeats both.
 *
 * Its input as its training's: mono at 22.05 kHz, a log-mel spectrogram (1024-point frames every 441 samples, 50 a
 * second; 128 slaney mels, 30 Hz to 11 kHz; log1p of 1000 times the magnitude). Out, each frame's beat and downbeat logit.
 */
object BeatNet {
    const val RATE = 22_050
    const val FPS = 50
    private const val NFFT = 1024
    private const val HOP = 441
    private const val MELS = 128
    /** The longest stretch read at once (frames; 30 s, as it was trained); longer ones in pieces, 6 frames over each other. */
    const val CHUNK = 1500
    private const val BORDER = 6

    /** Each frame's beat and downbeat logit (over 0: more likely than not), from `startMs`, 50 a second. */
    class Logits(val beat: FloatArray, val down: FloatArray)

    private val weights: MutableMap<String, FloatArray>? by lazy { runCatching { load() }.getOrNull() }

    /** Whether the model's here to read with (its weights found). */
    val ready get() = weights != null

    private fun load(): MutableMap<String, FloatArray> {
        val s = BeatNet::class.java.getResourceAsStream("/beat_this_small.bin") ?: error("no beat_this_small.bin")
        DataInputStream(s.buffered()).use { d ->
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic) == "BT01")
            fun int() = Integer.reverseBytes(d.readInt())
            val out = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
            repeat(int()) {
                val name = String(ByteArray(int()).also { d.readFully(it) })
                var n = 1; repeat(int()) { n *= int() }
                val raw = ByteArray(n * 2).also { d.readFully(it) }
                out[name] = FloatArray(n) { i -> half((raw[2 * i].toInt() and 0xff) or ((raw[2 * i + 1].toInt() and 0xff) shl 8)) }
            }
            return out
        }
    }

    /** IEEE half to float (Android's Half is API 26, Java's Float.float16ToFloat Java 20). */
    private fun half(h: Int): Float {
        val s = (h ushr 15) and 1; val e = (h ushr 10) and 0x1f; val m = h and 0x3ff
        val v = when (e) {
            0 -> m / 1024f * 6.1035156e-5f
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> java.lang.Float.intBitsToFloat(((e - 15 + 127) shl 23) or (m shl 13))
        }
        return if (s == 1) -v else v
    }

    private val threads = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))
    private val pool = Executors.newFixedThreadPool(threads, ThreadFactory { r ->
        Thread(r, "ink-beatnet").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    })

    /** `body(i)` for i in 0 until n, shared out among the model's threads. */
    private fun par(n: Int, body: (Int) -> Unit) {
        if (n <= 1 || threads == 1) { for (i in 0 until n) body(i); return }
        val per = (n + threads - 1) / threads
        val parts = (0 until threads).mapNotNull { k ->
            val a = k * per; val b = min(n, a + per)
            if (a >= b) null else Callable { for (i in a until b) body(i) }
        }
        pool.invokeAll(parts).forEach { it.get() }
    }

    // ---- the spectrogram ----

    /** `x` (mono at `rate`) at 22.05 kHz: a windowed-sinc low-pass and linear steps between, enough for a beat. */
    fun resample(x: FloatArray, rate: Int): FloatArray {
        if (rate == RATE) return x
        val step = rate.toDouble() / RATE
        val n = (x.size / step).toInt()
        // below the new Nyquist (a 33-tap sinc on the way down)
        val src = if (rate > RATE) {
            val fc = 0.5 / step * 0.95; val half = 16
            val h = FloatArray(2 * half + 1) { i ->
                val k = i - half
                val s = if (k == 0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
                (s * (0.54 - 0.46 * cos(2 * PI * i / (2 * half)))).toFloat()
            }
            val hs = h.sum(); for (i in h.indices) h[i] /= hs
            FloatArray(x.size) { i -> var a = 0f; for (k in h.indices) { val j = i + k - half; if (j in x.indices) a += x[j] * h[k] }; a }
        } else x
        return FloatArray(n) { i ->
            val t = i * step; val j = t.toInt(); val f = (t - j).toFloat()
            val a = src[min(j, src.size - 1)]; val b = src[min(j + 1, src.size - 1)]
            a + (b - a) * f
        }
    }

    private val melBank: Array<FloatArray> by lazy {
        // slaney's mel (torchaudio's melscale_fbanks, norm none)
        fun mel(f: Double) = if (f < 1000) 3 * f / 200 else 15 + ln(f / 1000) / (ln(6.4) / 27)
        fun hz(m: Double) = if (m < 15) 200 * m / 3 else 1000 * exp((m - 15) * (ln(6.4) / 27))
        val bins = NFFT / 2 + 1
        val lo = mel(30.0); val hi = mel(11_000.0)
        val f = DoubleArray(MELS + 2) { hz(lo + (hi - lo) * it / (MELS + 1)) }
        Array(MELS) { m ->
            FloatArray(bins) { b ->
                val fr = b * (RATE / 2.0) / (bins - 1)
                val down = (fr - f[m]) / (f[m + 1] - f[m]); val up = (f[m + 2] - fr) / (f[m + 2] - f[m + 1])
                max(0.0, min(down, up)).toFloat()
            }
        }
    }

    /** The log-mel spectrogram of `x` (at 22.05 kHz), [frame][mel], as torchaudio makes it (centred, reflected ends). */
    fun spect(x: FloatArray): Array<FloatArray> {
        val frames = 1 + x.size / HOP
        val win = FloatArray(NFFT) { (0.5 - 0.5 * cos(2 * PI * it / NFFT)).toFloat() }
        val norm = 1f / sqrt(NFFT.toFloat())
        val bank = melBank
        // each mel's bins where it's nonzero
        val from = IntArray(MELS) { m -> bank[m].indexOfFirst { it > 0 }.coerceAtLeast(0) }
        val to = IntArray(MELS) { m -> bank[m].indexOfLast { it > 0 } + 1 }
        val out = Array(frames) { FloatArray(MELS) }
        par(frames) { t ->
            val re = FloatArray(NFFT); val im = FloatArray(NFFT)
            val c = t * HOP - NFFT / 2
            for (i in 0 until NFFT) {
                var j = c + i
                if (x.size > 1) { while (j < 0 || j >= x.size) j = if (j < 0) -j else 2 * (x.size - 1) - j } else j = 0
                re[i] = (if (x.isEmpty()) 0f else x[j]) * win[i]
            }
            Beat.fft(re, im)
            val mag = FloatArray(NFFT / 2 + 1) { b -> sqrt(re[b] * re[b] + im[b] * im[b]) * norm }
            val row = out[t]
            for (m in 0 until MELS) {
                var s = 0f; val w = bank[m]
                for (b in from[m] until to[m]) s += mag[b] * w[b]
                row[m] = ln(1 + 1000 * s)
            }
        }
        return out
    }

    // ---- the model ----
    private fun w(name: String) = weights!![name] ?: error("no $name")

    /** Each frame's logits for `mono` at `rate`; null without the model or under a second of sound. */
    fun logits(mono: FloatArray, rate: Int, chunk: Int = CHUNK): Logits? {
        if (!ready) return null
        val x = resample(mono, rate)
        if (x.size < RATE) return null
        val s = spect(x)
        val total = s.size
        val beat = FloatArray(total) { -1000f }; val down = FloatArray(total) { -1000f }
        if (total <= chunk) {
            val (b, d) = forward(s, 0, total)
            b.copyInto(beat); d.copyInto(down)
            return Logits(beat, down)
        }
        // in pieces as Beat This! splits a song: each a chunk long, 6 frames over each other (their edges let go), the last
        // one ending at the end; where two cover a frame, the earlier's kept
        val starts = ArrayList<Int>()
        var st = -BORDER
        while (st < total - BORDER) { starts += st; st += chunk - 2 * BORDER }
        starts[starts.lastIndex] = total - (chunk - BORDER)
        for (start in starts.reversed()) {
            val a = max(start, 0); val e = min(start + chunk, total)
            val padL = max(0, -start); val padR = max(0, min(BORDER, start + chunk - total))
            val rows = Array(padL + (e - a) + padR) { i -> val j = i - padL + a; if (j in a until e) s[j] else FloatArray(MELS) }
            val (b, d) = forward(rows, 0, rows.size)
            for (i in BORDER until rows.size - BORDER) { val t = start + i; if (t in 0 until total) { beat[t] = b[i]; down[t] = d[i] } }
        }
        return Logits(beat, down)
    }

    /** The model over frames [a, a+n) of `s`: each frame's beat (its own logit plus the downbeat's: the sum head) and downbeat. */
    private fun forward(s: Array<FloatArray>, a: Int, n: Int): Pair<FloatArray, FloatArray> {
        val T = n
        // the stem: a batch norm over the mels, a 4x3 convolution down the mels by 4 (32 channels), a batch norm, gelu
        val bnM = w("frontend.stem.bn1d.running_mean"); val bnV = w("frontend.stem.bn1d.running_var")
        val bnW = w("frontend.stem.bn1d.weight"); val bnB = w("frontend.stem.bn1d.bias")
        val x0 = Array(MELS) { m -> val k = bnW[m] / sqrt(bnV[m] + 1e-5f); FloatArray(T) { t -> (s[a + t][m] - bnM[m]) * k + bnB[m] } }
        var C = 32; var F = MELS / 4
        var x = FloatArray(F * T * C)   // tokens [f][t][c]
        run {
            val cw = w("frontend.stem.conv2d.weight")   // [32][1][4][3]
            par(F) { f ->
                for (t in 0 until T) for (c in 0 until C) {
                    var acc = 0f
                    for (kf in 0 until 4) { val row = x0[f * 4 + kf]; for (kt in 0 until 3) { val tt = t + kt - 1; if (tt in 0 until T) acc += cw[(c * 4 + kf) * 3 + kt] * row[tt] } }
                    x[(f * T + t) * C + c] = acc
                }
            }
            bnGelu(x, C, "frontend.stem.bn2d")
        }
        // three blocks: across the frequencies and across time (partial transformers), then a 2x3 convolution halving them
        for (blk in 0 until 3) {
            val p = "frontend.blocks.$blk.partial."
            val aF = attnW(p + "attnF", C); val fF = ffW(p + "ffF", C)
            val aT = attnW(p + "attnT", C); val fT = ffW(p + "ffT", C)
            // across the frequencies: each frame its own short sequence
            par(T) { t ->
                val seq = FloatArray(F * C)
                for (f in 0 until F) System.arraycopy(x, (f * T + t) * C, seq, f * C, C)
                layer(seq, F, C, aF, fF, inner = false)
                for (f in 0 until F) System.arraycopy(seq, f * C, x, (f * T + t) * C, C)
            }
            // across time: each frequency its own sequence (in place)
            if (F >= threads) par(F) { f -> layer(x, T, C, aT, fT, inner = false, off = f * T * C) }
            else for (f in 0 until F) layer(x, T, C, aT, fT, inner = true, off = f * T * C)
            // the convolution: channels doubled, frequencies halved
            val cw = w("frontend.blocks.$blk.conv2d.weight")   // [2C][C][2][3]
            val Co = 2 * C; val Fo = F / 2
            // its weights as [kf][kt][ci][co]: each input in the patch adds a row to the outputs
            val wp = turned.computeIfAbsent("frontend.blocks.$blk.conv2d") {
                FloatArray(Co * 6 * C).also { t -> for (co in 0 until Co) for (ci in 0 until C) for (kf in 0 until 2) for (kt in 0 until 3)
                    t[((kf * 3 + kt) * C + ci) * Co + co] = cw[((co * C + ci) * 2 + kf) * 3 + kt] }
            }
            val y = FloatArray(Fo * T * Co)
            val xs = x; val Ci = C
            par(Fo * T) { ft ->
                val fo = ft / T; val t = ft % T
                val o = ft * Co
                for (kf in 0 until 2) {
                    val fi = fo * 2 + kf
                    for (kt in 0 until 3) {
                        val tt = t + kt - 1
                        if (tt < 0 || tt >= T) continue
                        val xo = (fi * T + tt) * Ci; val wo = (kf * 3 + kt) * Ci
                        for (ci in 0 until Ci) {
                            val xv = xs[xo + ci]; if (xv == 0f) continue
                            val r = (wo + ci) * Co
                            for (co in 0 until Co) y[o + co] += xv * wp[r + co]
                        }
                    }
                }
            }
            x = y; C = Co; F = Fo
            bnGelu(x, C, "frontend.blocks.$blk.norm")
        }
        // to the transformer's 128: each frame's channels by frequency (c * 4 + f), a linear layer
        val D = 128
        val lw = wt("frontend.linear.weight", D); val lb = w("frontend.linear.bias")
        val h = FloatArray(T * D)
        par(T) { t ->
            val v = FloatArray(C * F)
            for (c in 0 until C) for (f in 0 until F) v[c * F + f] = x[(f * T + t) * C + c]
            linear(v, 0, C * F, lw, D, lb, h, t * D)
        }
        for (l in 0 until 6) {
            val p = "transformer_blocks.layers.$l."
            layer(h, T, D, attnW(p + "0", D), ffW(p + "1", D), inner = true)
        }
        rmsNorm(h, T, D, w("transformer_blocks.norm.gamma"))
        val hw = w("task_heads.beat_downbeat_lin.weight"); val hb = w("task_heads.beat_downbeat_lin.bias")
        val beat = FloatArray(T); val down = FloatArray(T)
        for (t in 0 until T) {
            var b = hb[0]; var d = hb[1]
            for (i in 0 until D) { b += hw[i] * h[t * D + i]; d += hw[D + i] * h[t * D + i] }
            beat[t] = b + d; down[t] = d
        }
        return beat to down
    }

    // the weights as the loops want them: each linear layer's [out][in] turned [in][out], so each input adds a row to the
    // outputs (a loop the JIT turns into SIMD; a dot product's sum it can't reorder, so leaves one add at a time)
    private val turned = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    // (the weights as they came let go once turned: ~6 MB not kept twice in the player's heap)
    private fun wt(name: String, outs: Int): FloatArray = turned.computeIfAbsent(name) {
        val m = w(name); val ins = m.size / outs
        FloatArray(m.size).also { t -> for (o in 0 until outs) for (i in 0 until ins) t[i * outs + o] = m[o * ins + i] }
            .also { weights!!.remove(name) }
    }

    /** out[oo, oo+O) = bias + x[xo, xo+I) by `t` ([I][O]). */
    private fun linear(x: FloatArray, xo: Int, I: Int, t: FloatArray, O: Int, bias: FloatArray?, out: FloatArray, oo: Int) {
        if (bias != null) System.arraycopy(bias, 0, out, oo, O) else java.util.Arrays.fill(out, oo, oo + O, 0f)
        for (i in 0 until I) {
            val xi = x[xo + i]
            if (xi == 0f) continue
            val to = i * O
            for (o in 0 until O) out[oo + o] += xi * t[to + o]
        }
    }

    private class AttnW(val gamma: FloatArray, val qkv: FloatArray, val gates: FloatArray, val gb: FloatArray, val out: FloatArray, val freqs: FloatArray)
    private class FfW(val gamma: FloatArray, val w1: FloatArray, val b1: FloatArray, val w2: FloatArray, val b2: FloatArray)
    private fun attnW(p: String, C: Int) = AttnW(w("$p.norm.gamma"), wt("$p.to_qkv.weight", 3 * C), wt("$p.to_gates.weight", C / 32), w("$p.to_gates.bias"),
        wt("$p.to_out.0.weight", C), w("$p.rotary_embed.freqs"))
    private fun ffW(p: String, C: Int) = FfW(w("$p.net.0.gamma"), wt("$p.net.1.weight", 4 * C), w("$p.net.1.bias"), wt("$p.net.4.weight", C), w("$p.net.4.bias"))

    private fun bnGelu(x: FloatArray, C: Int, p: String) {
        val m = w("$p.running_mean"); val v = w("$p.running_var"); val g = w("$p.weight"); val b = w("$p.bias")
        val k = FloatArray(C) { g[it] / sqrt(v[it] + 1e-5f) }
        val n = x.size / C
        par(n) { i -> val o = i * C; for (c in 0 until C) x[o + c] = gelu((x[o + c] - m[c]) * k[c] + b[c]) }
    }

    /** x / |x| * sqrt(C) * gamma, each token (the roformer's RMSNorm). */
    private fun rmsNorm(x: FloatArray, L: Int, C: Int, gamma: FloatArray, off: Int = 0, into: FloatArray = x, intoOff: Int = off) {
        val sc = sqrt(C.toFloat())
        for (n in 0 until L) rmsRow(x, off + n * C, C, gamma, sc, into, intoOff + n * C)
    }

    private fun rmsRow(x: FloatArray, o: Int, C: Int, gamma: FloatArray, sc: Float, into: FloatArray, io: Int) {
        var ss = 0f; for (c in 0 until C) ss += x[o + c] * x[o + c]
        val k = sc / max(sqrt(ss), 1e-12f)
        for (c in 0 until C) into[io + c] = x[o + c] * k * gamma[c]
    }

    /** A layer's own buffers, one set a thread (a sequence of up to `L` tokens of `C`). */
    private class Scratch(L: Int, C: Int) {
        val H = C / 32
        val xn = FloatArray(L * C); val q = FloatArray(H * L * 32); val kt = FloatArray(H * 32 * L); val v = FloatArray(H * L * 32)
        val gates = FloatArray(L * H); val att = FloatArray(L * C); val row = FloatArray(4 * C); val hid = FloatArray(4 * C)
    }
    // kept a thread for the short sequences only (across the frequencies, one a frame); a long one's made each time,
    // so nothing the size of a stretch stays held between songs
    private val scratch = ThreadLocal<HashMap<Long, Scratch>>()
    private fun scratchFor(L: Int, C: Int): Scratch {
        if (L > 64) return Scratch(L, C)
        val m = scratch.get() ?: HashMap<Long, Scratch>().also { scratch.set(it) }
        return m.getOrPut(L.toLong() shl 32 or C.toLong()) { Scratch(L, C) }
    }
    private val rows = ThreadLocal<FloatArray>()

    /**
     * One transformer layer over a sequence of `L` tokens of `C` at `off` in `x`, in place: attention (rotary positions,
     * a gate on each head) then the feed-forward, each added back. `inner`: shared out among the threads within.
     */
    private fun layer(x: FloatArray, L: Int, C: Int, a: AttnW, f: FfW, inner: Boolean, off: Int = 0) {
        val H = C / 32; val D = 32
        val loop: (Int, (Int) -> Unit) -> Unit = if (inner) ::par else { n, body -> for (i in 0 until n) body(i) }
        // one sequence shared out: one set of buffers for it (each thread its own rows); else this thread's
        val s = if (inner) Scratch(L, C) else scratchFor(L, C)
        val xn = s.xn; val q = s.q; val kt = s.kt; val v = s.v; val gates = s.gates; val att = s.att
        val sc0 = sqrt(C.toFloat())
        // q and k by head [h][n][d] (rotated by position), v by head turned [h][d][n]
        loop(L) { n ->
            val xo = n * C
            rmsRow(x, off + xo, C, a.gamma, sc0, xn, xo)
            val qkv = if (inner) FloatArray(3 * C) else s.row
            linear(xn, xo, C, a.qkv, 3 * C, null, qkv, 0)
            for (hh in 0 until H) {
                var g = a.gb[hh]; for (i in 0 until C) g += xn[xo + i] * a.gates[i * H + hh]
                gates[n * H + hh] = 1f / (1f + exp(-g))
                val qo = (hh * L + n) * D; val b = hh * D
                for (i in 0 until D / 2) {
                    val th = n * a.freqs[i]; val cs = cos(th); val sn = sin(th)
                    val q0 = qkv[b + 2 * i]; val q1 = qkv[b + 2 * i + 1]
                    q[qo + 2 * i] = q0 * cs - q1 * sn; q[qo + 2 * i + 1] = q1 * cs + q0 * sn
                    val k0 = qkv[C + b + 2 * i]; val k1 = qkv[C + b + 2 * i + 1]
                    kt[qo + 2 * i] = k0 * cs - k1 * sn; kt[qo + 2 * i + 1] = k1 * cs + k0 * sn
                }
                for (d in 0 until D) v[(hh * D + d) * L + n] = qkv[2 * C + b + d]
            }
        }
        // softmax(q k / sqrt(d)) v, gated, each head; four queries at a time against four keys (or four of v's
        // dimensions) at a time: sixteen sums held at once, each value read once for four of them
        val scale = 1f / sqrt(D.toFloat())
        val groups = (L + 3) / 4
        loop(H * groups) { hg ->
            val hh = hg / groups; val i0 = (hg % groups) * 4
            val m = min(4, L - i0)
            val Lp = (L + 3) / 4 * 4
            val sc = rows.get()?.takeIf { it.size >= 4 * Lp } ?: FloatArray(4 * Lp).also { rows.set(it) }   // 24 KB a thread at most
            // the rows past the sequence's end: a query repeated, its sums thrown away
            val qa = (hh * L + i0) * D; val qb = (hh * L + i0 + min(1, m - 1)) * D
            val qc = (hh * L + i0 + min(2, m - 1)) * D; val qd = (hh * L + i0 + min(3, m - 1)) * D
            var j = 0
            while (j < L) {
                val ka = (hh * L + j) * D; val kb = (hh * L + min(j + 1, L - 1)) * D
                val kc = (hh * L + min(j + 2, L - 1)) * D; val kd = (hh * L + min(j + 3, L - 1)) * D
                var s00 = 0f; var s01 = 0f; var s02 = 0f; var s03 = 0f; var s10 = 0f; var s11 = 0f; var s12 = 0f; var s13 = 0f
                var s20 = 0f; var s21 = 0f; var s22 = 0f; var s23 = 0f; var s30 = 0f; var s31 = 0f; var s32 = 0f; var s33 = 0f
                for (d in 0 until D) {
                    val a0 = q[qa + d]; val a1 = q[qb + d]; val a2 = q[qc + d]; val a3 = q[qd + d]
                    val b0 = kt[ka + d]; val b1 = kt[kb + d]; val b2 = kt[kc + d]; val b3 = kt[kd + d]
                    s00 += a0 * b0; s01 += a0 * b1; s02 += a0 * b2; s03 += a0 * b3
                    s10 += a1 * b0; s11 += a1 * b1; s12 += a1 * b2; s13 += a1 * b3
                    s20 += a2 * b0; s21 += a2 * b1; s22 += a2 * b2; s23 += a2 * b3
                    s30 += a3 * b0; s31 += a3 * b1; s32 += a3 * b2; s33 += a3 * b3
                }
                sc[j] = s00 * scale; sc[j + 1] = s01 * scale; sc[j + 2] = s02 * scale; sc[j + 3] = s03 * scale
                sc[Lp + j] = s10 * scale; sc[Lp + j + 1] = s11 * scale; sc[Lp + j + 2] = s12 * scale; sc[Lp + j + 3] = s13 * scale
                sc[2 * Lp + j] = s20 * scale; sc[2 * Lp + j + 1] = s21 * scale; sc[2 * Lp + j + 2] = s22 * scale; sc[2 * Lp + j + 3] = s23 * scale
                sc[3 * Lp + j] = s30 * scale; sc[3 * Lp + j + 1] = s31 * scale; sc[3 * Lp + j + 2] = s32 * scale; sc[3 * Lp + j + 3] = s33 * scale
                j += 4
            }
            val g = FloatArray(4)
            for (r in 0 until 4) {
                val ro = r * Lp
                var mx = Float.NEGATIVE_INFINITY
                for (k in 0 until L) if (sc[ro + k] > mx) mx = sc[ro + k]
                var sum = 0f
                for (k in 0 until L) { val e = fexp(sc[ro + k] - mx); sc[ro + k] = e; sum += e }
                for (k in L until Lp) sc[ro + k] = 0f   // keys past the end weigh nothing
                g[r] = if (r < m) gates[(i0 + r) * H + hh] / sum else 0f
            }
            var d = 0
            while (d < D) {
                val va = (hh * D + d) * L; val vb = va + L; val vc = vb + L; val vd = vc + L
                var s00 = 0f; var s01 = 0f; var s02 = 0f; var s03 = 0f; var s10 = 0f; var s11 = 0f; var s12 = 0f; var s13 = 0f
                var s20 = 0f; var s21 = 0f; var s22 = 0f; var s23 = 0f; var s30 = 0f; var s31 = 0f; var s32 = 0f; var s33 = 0f
                for (k in 0 until L) {
                    val p0 = sc[k]; val p1 = sc[Lp + k]; val p2 = sc[2 * Lp + k]; val p3 = sc[3 * Lp + k]
                    val b0 = v[va + k]; val b1 = v[vb + k]; val b2 = v[vc + k]; val b3 = v[vd + k]
                    s00 += p0 * b0; s01 += p0 * b1; s02 += p0 * b2; s03 += p0 * b3
                    s10 += p1 * b0; s11 += p1 * b1; s12 += p1 * b2; s13 += p1 * b3
                    s20 += p2 * b0; s21 += p2 * b1; s22 += p2 * b2; s23 += p2 * b3
                    s30 += p3 * b0; s31 += p3 * b1; s32 += p3 * b2; s33 += p3 * b3
                }
                val o0 = i0 * C + hh * D + d
                att[o0] = s00 * g[0]; att[o0 + 1] = s01 * g[0]; att[o0 + 2] = s02 * g[0]; att[o0 + 3] = s03 * g[0]
                if (m > 1) { val o = o0 + C; att[o] = s10 * g[1]; att[o + 1] = s11 * g[1]; att[o + 2] = s12 * g[1]; att[o + 3] = s13 * g[1] }
                if (m > 2) { val o = o0 + 2 * C; att[o] = s20 * g[2]; att[o + 1] = s21 * g[2]; att[o + 2] = s22 * g[2]; att[o + 3] = s23 * g[2] }
                if (m > 3) { val o = o0 + 3 * C; att[o] = s30 * g[3]; att[o + 1] = s31 * g[3]; att[o + 2] = s32 * g[3]; att[o + 3] = s33 * g[3] }
                d += 4
            }
        }
        // out, added back; then the feed-forward: rms norm, 4C with gelu, back to C, added back
        loop(L) { n ->
            val o = off + n * C; val ao = n * C
            val y = if (inner) FloatArray(4 * C) else s.row
            linear(att, ao, C, a.out, C, null, y, 0)
            for (c in 0 until C) x[o + c] += y[c]
            rmsRow(x, o, C, f.gamma, sc0, xn, ao)
            val hid = if (inner) FloatArray(4 * C) else s.hid
            linear(xn, ao, C, f.w1, 4 * C, f.b1, hid, 0)
            for (i in 0 until 4 * C) hid[i] = gelu(hid[i])
            linear(hid, 0, 4 * C, f.w2, C, f.b2, y, 0)
            for (c in 0 until C) x[o + c] += y[c]
        }
    }

    /** exp for the softmax (its argument at most 0): 2^(x log2 e), the fraction by a fifth-order polynomial (~1e-7). */
    private fun fexp(x: Float): Float {
        if (x < -87f) return 0f
        val y = x * 1.442695f
        val i = kotlin.math.floor(y); val f = y - i
        val p = 1f + f * (0.6931472f + f * (0.2402265f + f * (0.05550411f + f * (0.009618129f + f * 0.0013333558f))))
        return java.lang.Float.intBitsToFloat((i.toInt() + 127) shl 23) * p
    }

    /** gelu, exact (erf by Abramowitz and Stegun 7.1.26, within 1.5e-7). */
    private fun gelu(x: Float): Float {
        val z = abs(x) / 1.4142135f
        val t = 1f / (1f + 0.3275911f * z)
        val e = 1f - (((((1.0614054f * t - 1.4531521f) * t) + 1.4214138f) * t - 0.28449672f) * t + 0.2548296f) * t * exp(-z * z)
        val erf = if (x < 0) -e else e
        return 0.5f * x * (1 + erf)
    }

    // ---- beats from the logits ----

    /** The beats found (seconds from the stretch's start) and the downbeats among them. */
    class Read(val beats: DoubleArray, val downs: DoubleArray)

    /** The stretch's beats and downbeats (each downbeat moved to its nearest beat, as Beat This! does). */
    fun read(mono: FloatArray, rate: Int): Read? {
        val l = logits(mono, rate) ?: return null
        val b = peaks(l.beat)
        val d = peaks(l.down).map { t -> b.minByOrNull { abs(it - t) } ?: t }.distinct().toDoubleArray()
        return Read(b, d)
    }

    /**
     * The stretch's beat as Beat gives it: a steady grid laid over the model's beats (least squares, beats more than a
     * tenth of a beat off it left out, twice over), bar one where most of its downbeats fall of the four places a bar
     * could begin. How sure on Beat's scale: [Beat.SURE] and up where 70% of the grid's beats were found on it and 70%
     * of the beats found are on the grid, a dozen at least (on the 50 test songs: 66 sure, 64 right); under [Beat.AGREED] (never matched) else.
     */
    fun find(mono: FloatArray, rate: Int, startMs: Long): Beat.Found? {
        val r = read(mono, rate) ?: return null
        return grid(r.beats, r.downs)?.let { g ->
            Beat.Found(g.period * 1000, startMs + g.first * 1000, g.sure, startMs + g.down * 1000,
                Beat.barsOf(mono, rate, startMs, startMs + g.down * 1000, g.period * 1000), g.coverage)
        }
    }

    class Grid(val period: Double, val first: Double, val down: Double, val sure: Double, val coverage: Double, val onGrid: Double)

    fun grid(b: DoubleArray, downs: DoubleArray): Grid? {
        if (b.size < 8) return null
        val iois = (1 until b.size).map { b[it] - b[it - 1] }.sorted()
        var p = iois[iois.size / 2]
        if (p <= 0.25 || p > 1.5) return null   // 40 to 240 bpm
        var a = b[0]
        var keep = b.indices.toList()
        repeat(3) {
            val ks = keep.map { Math.round((b[it] - a) / p).toDouble() }
            val ts = keep.map { b[it] }
            val n = ks.size
            val mk = ks.average(); val mt = ts.average()
            var num = 0.0; var den = 0.0
            for (i in 0 until n) { num += (ks[i] - mk) * (ts[i] - mt); den += (ks[i] - mk) * (ks[i] - mk) }
            if (den > 0) { p = num / den; a = mt - p * mk }
            keep = b.indices.filter { val k = Math.round((b[it] - a) / p); abs(b[it] - (a + k * p)) <= .1 * p }
            if (keep.size < 4) return null
        }
        val ks = keep.map { Math.round((b[it] - a) / p) }.distinct()
        val k0 = ks.min(); val k1 = ks.max()
        val onGrid = keep.size.toDouble() / b.size
        val coverage = ks.size.toDouble() / (k1 - k0 + 1)
        val q = min(onGrid, coverage)
        val sure = if (q >= .7 && ks.size >= 12) Beat.SURE + 3 * (q - .7) / .3 else Beat.AGREED * .95 * q / .7
        // bar one: the place (of four) most downbeats fall on
        val votes = IntArray(4)
        for (d in downs) { val k = Math.round((d - a) / p); if (abs(d - (a + k * p)) <= .15 * p) votes[(((k - k0) % 4) + 4).toInt() % 4]++ }
        val phase = votes.indices.maxByOrNull { votes[it] } ?: 0
        return Grid(p, a + k0 * p, a + (k0 + phase) * p, sure, coverage, onGrid)
    }

    /** Peaks over 0 within 70 ms of nothing higher, side-by-side ones as one (Beat This!'s minimal postprocessing); seconds from the stretch's start. */
    fun peaks(l: FloatArray): DoubleArray {
        val idx = ArrayList<Int>()
        for (t in l.indices) {
            if (l[t] <= 0f) continue
            var top = true
            for (j in max(0, t - 3)..min(l.size - 1, t + 3)) if (l[j] > l[t]) { top = false; break }
            if (top) idx += t
        }
        val out = ArrayList<Double>()
        var i = 0
        while (i < idx.size) {
            var p = idx[i].toDouble(); var c = 1; var last = idx[i]
            while (i + 1 < idx.size && idx[i + 1] - last <= 1) { i++; c++; p += (idx[i] - p) / c; last = idx[i] }
            out += p / FPS; i++
        }
        return out.toDoubleArray()
    }
}
