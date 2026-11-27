package app.ink.music

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * FREQUENCY's dial heard as the mouse goes along it (2026-11-27, Bronson: "some sort of subtle audio effect or haptic"; a
 * computer has no haptics to speak of): a tick as quiet as a watch's at each column crossed, and at concert A's notch a
 * soft A itself, 440 Hz. Its own line out, beside mpv's, opened once and kept; a tick that can't be played is let go.
 */
object Tick {
    private const val RATE = 44_100
    private val run = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ink-tick").apply { isDaemon = true } }
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var failed = false
    private val tick = tone(2_400.0, 6, .05)
    private val a = tone(440.0, 220, .06)

    /** A column crossed. */
    fun tick() = play(tick)

    /** The needle in concert A's notch. */
    fun a() = play(a)

    private fun play(pcm: ByteArray) {
        if (failed) return
        run.execute {
            val l = line ?: runCatching {
                AudioSystem.getSourceDataLine(AudioFormat(RATE.toFloat(), 16, 1, true, false)).apply { open(format, RATE / 10 * 2); start() }
            }.getOrElse { failed = true; return@execute }.also { line = it }
            // a tick that would wait behind the last is let go: the mouse has moved on
            if (l.available() < pcm.size) return@execute
            l.write(pcm, 0, pcm.size)
        }
    }

    /** A sine of `hz` for `ms`, at `level` (0..1), its start eased in and its end dying away so it never clicks. */
    private fun tone(hz: Double, ms: Int, level: Double): ByteArray {
        val n = RATE * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val env = minOf(1.0, i / (RATE * .002)) * Math.pow(1 - t, if (ms < 20) 3.0 else 1.6)
            val v = (kotlin.math.sin(2 * Math.PI * hz * i / RATE) * env * level * 32767).toInt()
            out[i * 2] = (v and 0xff).toByte(); out[i * 2 + 1] = (v shr 8 and 0xff).toByte()
        }
        return out
    }
}
