package app.ink.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.nio.ByteBuffer

/**
 * The very last of the sound before Android has it, kept around a blend to look at (tuning, 2026-11-08): Blend's own
 * keeping is before Resample, and a hitch only on blends into 44.1 kHz songs didn't show there. Passes everything on as
 * it is; while Blend's dump folder has "on", the last 2 s held, and from a blend's start (Blend says so) 2 s before to
 * 10 s after written as <name>-out-<rate>-<channels>.pcm beside Blend's.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Tap(private val blend: Blend) : BaseAudioProcessor() {
    private val preroll = ArrayDeque<ByteArray>(); private var prerollBytes = 0
    private var capture: java.io.ByteArrayOutputStream? = null; private var left = 0L; private var name = ""

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // anything but 16-bit (exact's float) passed by untouched
        return if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val dir = blend.dumpDir
        if (dir == null || !File(dir, "on").exists()) { out.put(inputBuffer); out.flip(); preroll.clear(); prerollBytes = 0; return }
        val b = ByteArray(n); inputBuffer.get(b); out.put(b); out.flip()
        val rate = inputAudioFormat.sampleRate; val bpf = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        preroll.addLast(b); prerollBytes += n
        while (prerollBytes - preroll.first().size > 2 * rate * bpf) prerollBytes -= preroll.removeFirst().size
        blend.tapStart?.let { s ->
            blend.tapStart = null
            capture = java.io.ByteArrayOutputStream().also { o -> preroll.forEach { o.write(it) } }
            left = 10L * rate * bpf
            name = "$s-out-$rate-${inputAudioFormat.channelCount}.pcm"
            return
        }
        val c = capture ?: return
        c.write(b); left -= n
        if (left <= 0) {
            capture = null
            val bytes = c.toByteArray(); val nm = name
            Thread { runCatching { File(dir, nm).writeBytes(bytes) } }.start()
        }
    }
}
