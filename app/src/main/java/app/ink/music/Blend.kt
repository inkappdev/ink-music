package app.ink.music

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The crossfade made in the player's own sound, sample by sample (Bronson, 2026-11-01: two players couldn't be held
 * together: gaps, a doubled moment, "like hitting next song"). A song is cut short where its handover is (Crossfade
 * sets that while it's still next), and the next one follows on at once, gapless; here, as the next one's samples
 * pass, the old one's last seconds are mixed back in from Crossfade's own decode of them, from the very sample its
 * stream stopped at (found by matching the samples just played), the old one falling away as the new one rises, on
 * equal-power curves. One stream: no gap, no doubling, the equalizer and the volume on both alike.
 *
 * 16 bit only (Media3 runs processors there; a deeper file goes the two-player way).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Blend : BaseAudioProcessor() {
    /** A song's end as decoded apart: its samples (interleaved floats) from `startMs`, where its stream is cut, the fade's length. */
    class Tail(val startMs: Long, val rate: Int, val channels: Int, val pcm: FloatArray, val cutMs: Long, val fadeMs: Long)

    /** The ends to mix back in, by song (Crossfade sets them as it cuts a song short). */
    val tails = ConcurrentHashMap<String, Tail>()

    // the stream now: its song, where it is (frames into the song), its last samples (to find where the old one stopped)
    private var song: String? = null
    private var frame = 0L
    private var rate = 0
    private var channels = 0
    private val recent = FloatArray(2048 * 2)
    private var recentFrames = 0
    private val reached = HashMap<String, Long>()   // each song's furthest point (ms), this stream and the last

    // the mix in hand
    private var tail: Tail? = null
    private var at = 0.0          // where in the tail's samples (its frames)
    private var step = 1.0        // the tail's frames a frame here (its rate against this one)
    private var mixed = 0L
    private var fadeFrames = 1L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun isActive() = true

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        val id = idOf(streamMetadata)
        val was = song
        // the furthest it reached (a format change flushes the old song once more at nothing, before the next comes)
        val endedMs = was?.let { reached[it] } ?: -1L
        tail = null
        // the next song come on at its start, the one before cut where its handover was: its end mixed back in
        val t = was?.let { tails[it] }
        if (t != null && id != null && id != was && streamMetadata.positionOffsetUs < 2_000_000 &&
            kotlin.math.abs(endedMs - t.cutMs) < 150 && t.channels == format.channelCount) {
            val expected = (endedMs - t.startMs) * t.rate / 1000.0
            at = align(t, expected)
            step = t.rate.toDouble() / format.sampleRate
            mixed = 0
            fadeFrames = (t.fadeMs * format.sampleRate / 1000).coerceAtLeast(1)
            tail = t
            android.util.Log.d("ink-crossfade", "blend: $was into $id at ${endedMs / 1000.0}s, its end from frame ${at.toLong()} (expected ${expected.toLong()}), over ${t.fadeMs / 1000.0}s")
        } else if (t != null && id != was) {
            android.util.Log.d("ink-crossfade", "blend: $was ended at ${endedMs / 1000.0}s, not where it was cut (${t.cutMs / 1000.0}s): not mixed")
        }
        if (id != null && id != was) { reached.keys.retainAll(setOf(id, was)); reached[id] = streamMetadata.positionOffsetUs / 1000 }
        song = id
        rate = format.sampleRate
        channels = format.channelCount
        frame = streamMetadata.positionOffsetUs * rate / 1_000_000
        recentFrames = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = channels.coerceAtLeast(1)
        val frames = inputBuffer.remaining() / (2 * ch)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * ch * 2)
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val t = tail
        for (f in 0 until frames) {
            if (t == null) {
                for (c in 0 until ch) {
                    val s = inp.short
                    out.putShort(s)
                    if (c < 2) recent[(recentFrames % 2048) * 2 + c] = s / 32768f
                }
                recentFrames++
            } else {
                val k = mixed.toDouble() / fadeFrames
                val gNew = if (k >= 1) 1.0 else sin(k * PI / 2)
                val gOld = if (k >= 1) 0.0 else cos(k * PI / 2)
                val i = at.toInt(); val frac = at - i
                for (c in 0 until ch) {
                    val s = inp.short / 32768.0
                    val a0 = t.pcm.getOrElse(i * t.channels + c) { 0f }; val a1 = t.pcm.getOrElse((i + 1) * t.channels + c) { 0f }
                    val old = a0 + (a1 - a0) * frac
                    val v = s * gNew + old * gOld
                    out.putShort((v * 32768).toInt().coerceIn(-32768, 32767).toShort())
                }
                at += step
                if (++mixed >= fadeFrames) tail = null
            }
        }
        frame += frames
        song?.let { s -> if (rate > 0) reached[s] = frame * 1000 / rate }
        out.flip()
    }

    // the ends kept: Crossfade lets them go (a reset here, with a song still cut short, would leave it ending on nothing)
    override fun onReset() { tail = null; song = null }

    /**
     * Where in the tail's samples the old stream stopped: near `expected`, the place whose samples before it match
     * the samples last played (a decoder's start, an encoder's delay, a cut on a frame's edge: off by up to a frame).
     */
    private fun align(t: Tail, expected: Double): Double {
        val n = minOf(recentFrames, 512)
        if (n < 256 || t.channels < 1) return expected
        val c = t.channels
        // the last n frames played, oldest first, as one channel
        val last = FloatArray(n) { j -> val r = ((recentFrames - n + j) % 2048) * 2; (recent[r] + recent[r + 1]) / 2 }
        var best = expected.toInt(); var bestScore = Double.NEGATIVE_INFINITY
        val from = (expected - 3000).toInt().coerceAtLeast(n); val to = (expected + 3000).toInt().coerceAtMost(t.pcm.size / c - 1)
        var p = from
        while (p <= to) {
            var dot = 0.0; var e = 0.0
            for (j in 0 until n) {
                val q = (p - n + j) * c
                val v = if (c >= 2) (t.pcm[q] + t.pcm[q + 1]) / 2 else t.pcm[q]
                dot += v * last[j]; e += v * v
            }
            val score = if (e > 0) dot / Math.sqrt(e) else 0.0
            if (score > bestScore) { bestScore = score; best = p }
            p++
        }
        return best.toDouble()
    }

    private fun idOf(m: AudioProcessor.StreamMetadata): String? {
        val uid = m.periodUid ?: return null
        val tl: Timeline = m.timeline
        if (tl.isEmpty) return null
        return runCatching {
            val period = tl.getPeriodByUid(uid, Timeline.Period())
            tl.getWindow(period.windowIndex, Timeline.Window()).mediaItem.mediaId
        }.getOrNull()
    }
}
