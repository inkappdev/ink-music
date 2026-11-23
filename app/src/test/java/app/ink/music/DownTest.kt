package app.ink.music

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/** Down's filter: a tone well under the new rate's limit through whole, one above it (that would fold back) gone. */
class DownTest {
    private fun level(hz: Double, encoding: Int): Pair<Double, Int> {
        val rate = 384_000; val frames = rate / 2
        val size = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val b = ByteBuffer.allocateDirect(frames * 2 * size).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) { val v = .5 * sin(2 * PI * hz * i / rate); repeat(2) { if (size == 4) b.putFloat(v.toFloat()) else b.putShort((v * 32767).toInt().toShort()) } }
        b.flip()
        val d = Down(2, 2, encoding)
        // in pieces, as the player writes
        val outs = ArrayList<Double>()
        while (b.hasRemaining()) {
            val piece = b.duplicate().apply { limit(minOf(b.limit(), b.position() + 4096 * 2 * size)) }
            b.position(piece.limit())
            val o = d.process(piece)
            while (o.hasRemaining()) { val l = if (size == 4) o.float.toDouble() else o.short / 32768.0; if (size == 4) o.float else o.short; outs += l }
        }
        val settled = outs.drop(2000)
        val rms = sqrt(settled.sumOf { it * it } / settled.size)
        return rms to outs.size
    }

    @Test fun passesAndStops() {
        for (enc in listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT)) {
            val (pass, n) = level(1000.0, enc)
            val (stop, _) = level(150_000.0, enc)
            val ref = .5 / sqrt(2.0)
            println("enc $enc: frames out $n, 1 kHz ${"%.3f".format(20 * log10(pass / ref))} dB, 150 kHz ${"%.1f".format(20 * log10(stop / ref + 1e-12))} dB")
            assertTrue(n in 95_900..96_100)
            assertTrue(abs(20 * log10(pass / ref)) < .1)
            assertTrue(20 * log10(stop / ref + 1e-12) < if (enc == C.ENCODING_PCM_FLOAT) -100.0 else -80.0)
        }
    }
}
