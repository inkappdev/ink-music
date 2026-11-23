package app.ink.music

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A song brought down a whole step for a USB DAC that won't take its rate (2026-11-23, a listener: 384 kHz files silent
 * on a DAC that stops at 192, Android unable to take them either): every `factor`th sample of it after a low-pass at
 * 45% of the new rate (a windowed sinc, a Blackman-Harris window, 48 taps a step: past 120 dB down above the new
 * Nyquist), so nothing folds back. In the song's own encoding (16 bit or float) and channels; the DAC's own driver
 * makes it the DAC's bits after, as ever. 384 to 192, 352.8 to 176.4: the same family, no fractional resampling.
 */
class Down(private val factor: Int, private val channels: Int, private val encoding: Int) {
    private val taps = 48 * factor
    private val kernel: FloatArray = FloatArray(taps) { t ->
        val cutoff = .45 / factor                       // of the input rate
        val x = t - (taps - 1) / 2.0
        val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
        val n = 2 * PI * t / (taps - 1)
        val w = .35875 - .48829 * cos(n) + .14128 * cos(2 * n) - .01168 * cos(3 * n)
        (sinc * w).toFloat()
    }.also { k -> val s = k.sum(); for (i in k.indices) k[i] /= s }

    private val float = encoding == C.ENCODING_PCM_FLOAT
    // the last `taps` frames, interleaved, kept twice over (a ring written at `at` and `at + taps`), so the window is
    // always one unbroken run, `at + 1` to `at + taps`, the newest last: one write a frame, no shifting
    private val history = FloatArray(2 * taps * channels)
    private var at = 0
    private var phase = 0                               // input frames till the next output
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

    fun flush() { history.fill(0f); phase = 0; at = 0 }

    /** All of `input` taken, its output (a factor fewer frames) returned, ready to read. */
    fun process(input: ByteBuffer): ByteBuffer {
        val inp = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val size = if (float) 4 else 2
        val frames = inp.remaining() / (size * channels)
        input.position(input.position() + frames * size * channels)
        val outFrames = (frames + factor) / factor + 1
        if (out.capacity() < outFrames * size * channels) out = ByteBuffer.allocateDirect(outFrames * size * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.clear()
        val ch = channels
        val h = history
        for (f in 0 until frames) {
            // this frame in, at both its places; the window then runs from the frame after it, `taps` long
            at = (at + 1) % taps
            for (c in 0 until ch) {
                val v = if (float) inp.float else inp.short / 32768f
                h[at * ch + c] = v; h[(at + taps) * ch + c] = v
            }
            if (phase == 0) {
                val from = (at + 1) * ch
                for (c in 0 until ch) {
                    var acc = 0f
                    var k = from + c
                    for (t in 0 until taps) { acc += h[k] * kernel[t]; k += ch }
                    if (float) out.putFloat(acc) else out.putShort((acc * 32768).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            phase = (phase + 1) % factor
        }
        out.flip()
        return out
    }
}
