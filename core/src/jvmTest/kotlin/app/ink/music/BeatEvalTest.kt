package app.ink.music

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * Beat's readings, the model's and its own, of each song's first 20 s and last 25 s (as the phone reads them), when
 * BEATNET_EVAL names a list of files (ffmpeg decodes them); one line each to BEATNET_OUT, for tools to score.
 */
class BeatEvalTest {
    @Test fun readings() {
        val list = System.getenv("BEATNET_EVAL") ?: return
        val out = File(System.getenv("BEATNET_OUT") ?: return).printWriter()
        val rate = 44_100
        for (f in File(list).readLines().filter { it.isNotBlank() }) {
            val raw = ProcessBuilder("ffmpeg", "-v", "error", "-i", f, "-ac", "1", "-ar", "$rate", "-f", "f32le", "-").start().inputStream.readBytes()
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val x = FloatArray(bb.remaining() / 4) { bb.float }
            val stretches = listOf("head" to (0 to minOf(x.size, 20 * rate)), "tail" to (maxOf(0, x.size - 25 * rate) to x.size))
            for ((which, r) in stretches) {
                val part = x.copyOfRange(r.first, r.second)
                val startMs = r.first * 1000L / rate
                val t0 = System.nanoTime(); val m = Beat.find(part, rate, startMs, model = true)
                val t1 = System.nanoTime(); val d = Beat.find(part, rate, startMs, model = false)
                val t2 = System.nanoTime()
                fun js(b: Beat.Found?) = if (b == null) "null" else "{\"period\":${b.period},\"first\":${b.first},\"sure\":${b.sure},\"down\":${b.downbeat}}"
                out.println("{\"file\":${org.json.JSONObject.quote(f)},\"which\":\"$which\",\"start\":$startMs,\"len\":${part.size * 1000L / rate},\"model\":${js(m)},\"dsp\":${js(d)},\"modelMs\":${(t1 - t0) / 1_000_000},\"dspMs\":${(t2 - t1) / 1_000_000}}")
                out.flush()
            }
            println(f)
        }
        out.close()
    }
}
