package app.ink.music

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Kotlin port against Beat This!'s own (tools: a clip's spectrogram and logits from PyTorch), when BEATNET_REF names them. */
class BeatNetTest {
    private fun floats(f: File): FloatArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.remaining() / 4) { b.float } }

    @Test fun matchesPyTorch() {
        val ref = System.getenv("BEATNET_REF") ?: return
        val x = floats(File("$ref.f32"))
        val spect = floats(File("$ref.spect")); val beat = floats(File("$ref.beat")); val down = floats(File("$ref.down"))
        val t0 = System.nanoTime()
        val s = BeatNet.spect(x)
        val t1 = System.nanoTime()
        var se = 0f; for (t in s.indices) for (m in 0 until 128) se = maxOf(se, kotlin.math.abs(s[t][m] - spect[t * 128 + m]))
        val l = BeatNet.logits(x, BeatNet.RATE)!!
        val t2 = System.nanoTime()
        var be = 0f; var de = 0f
        for (t in beat.indices) { be = maxOf(be, kotlin.math.abs(l.beat[t] - beat[t])); de = maxOf(de, kotlin.math.abs(l.down[t] - down[t])) }
        println("frames ${s.size} / ${spect.size / 128}; spect max diff $se; beat $be; down $de; spect ${(t1 - t0) / 1e6} ms, all ${(t2 - t1) / 1e6} ms")
        println("beats ${BeatNet.peaks(l.beat).joinToString { "%.2f".format(it) }}")
        println("ref   ${BeatNet.peaks(beat).joinToString { "%.2f".format(it) }}")
        repeat(3) { val a = System.nanoTime(); BeatNet.logits(x, BeatNet.RATE); println("warm ${(System.nanoTime() - a) / 1e6} ms") }
        assertTrue(se < 1e-2 && be < 0.1 && de < 0.1)
    }
}
