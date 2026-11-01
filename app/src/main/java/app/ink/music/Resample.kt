package app.ink.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Every song at 48 kHz while songs run into each other (Bronson, 2026-11-01: "you can hear the song stop"): YouTube's
 * songs come at 44.1 (AAC) or 48 kHz (Opus), and each change of rate had Android's track made anew, a moment of
 * nothing at the very handover. At one rate the track goes on through. Android's mixer runs at 48 kHz and made 44.1
 * into 48 anyway, so this only does it here instead, as well: a windowed sinc, 64 taps, 256 phases between which it
 * goes smoothly (a Blackman window), as Android's own resampler. Only with transitions on and exact off (exact sends every sample as it is).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Resample(private val wanted: () -> Boolean) : BaseAudioProcessor() {
    companion object {
        const val RATE = 48_000
        private const val TAPS = 64
        private const val PHASES = 256
        /** The kernel: PHASES rows of TAPS weights, each row a sinc shifted by its phase, under a Blackman window. */
        private val kernel: Array<FloatArray> by lazy {
            Array(PHASES + 1) { p ->
                val frac = p.toDouble() / PHASES
                FloatArray(TAPS) { t ->
                    val x = t - (TAPS / 2 - 1) - frac          // the distance from the point wanted, in input samples
                    val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    val n = (t - frac + 1) / TAPS              // 0..1 across the window
                    val w = .42 - .5 * cos(2 * PI * n) + .08 * cos(4 * PI * n)
                    (sinc * w).toFloat()
                }.also { row -> val s = row.sum(); for (i in row.indices) row[i] /= s }   // unity gain
            }
        }
    }

    private var channels = 0
    private var step = 1.0           // input samples an output sample
    private var history = FloatArray(0)   // the input not yet used up, interleaved (TAPS frames of what came before, at least)
    private var have = 0             // frames in history
    private var pos = 0.0            // where the next output falls, in history's frames

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        // only up to 48 (44.1, 32...): a higher rate would want its highs cut first (and those go the float way anyway)
        if (!wanted() || inputAudioFormat.sampleRate >= RATE) return AudioProcessor.AudioFormat.NOT_SET   // left as it is
        return AudioProcessor.AudioFormat(RATE, inputAudioFormat.channelCount, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        channels = inputAudioFormat.channelCount
        step = inputAudioFormat.sampleRate.toDouble() / RATE
        history = FloatArray(0); have = 0
        pos = (TAPS / 2 - 1).toDouble()   // the first output centred once enough has come
    }

    override fun onReset() { history = FloatArray(0); have = 0 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = channels.coerceAtLeast(1)
        val inFrames = inputBuffer.remaining() / (2 * ch)
        if (inFrames == 0) return
        // what's come added to what's left
        val grown = FloatArray((have + inFrames) * ch)
        System.arraycopy(history, 0, grown, 0, have * ch)
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until inFrames * ch) grown[have * ch + i] = inp.short / 32768f
        history = grown; have += inFrames
        // as many outputs as there's input round them for
        val last = have - TAPS / 2 - 1
        val count = if (pos > last) 0 else (floor((last - pos) / step).toInt() + 1)
        val out = replaceOutputBuffer(count * ch * 2)
        for (o in 0 until count) {
            val i = floor(pos).toInt()
            // between the two nearest of the kernel's phases (rounded to one, its highs were 68 dB clean; between, far better)
            val ph = (pos - i) * PHASES
            val p0 = ph.toInt().coerceIn(0, PHASES - 1); val w = (ph - p0).toFloat()
            val r0 = kernel[p0]; val r1 = kernel[p0 + 1]
            val base = i - (TAPS / 2 - 1)
            for (c in 0 until ch) {
                var acc = 0f
                var k = (base * ch) + c
                for (t in 0 until TAPS) { if (k >= 0) acc += history[k] * (r0[t] + (r1[t] - r0[t]) * w); k += ch }
                out.putShort((acc * 32768).toInt().coerceIn(-32768, 32767).toShort())
            }
            pos += step
        }
        out.flip()
        // let go of what's behind (keeping TAPS frames before where it is)
        val drop = (floor(pos).toInt() - TAPS).coerceAtLeast(0)
        if (drop > 0) {
            history = history.copyOfRange(drop * ch, have * ch); have -= drop; pos -= drop
        }
    }
}
