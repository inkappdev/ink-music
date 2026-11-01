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
    private var matched = 0.0
    private var recentSong: String? = null
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

    // a mix made ready at the song change (the old song's last samples matched then, while they're here), begun at
    // the new song's flush at its start: Media3 flushes it first where the old one was, then at its start (2026-11-01)
    private class Pending(val into: String, val tail: Tail, val at: Double)
    private var pending: Pending? = null

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        val id = idOf(streamMetadata)
        val startFrame = streamMetadata.positionOffsetUs * format.sampleRate / 1_000_000
        // the same song set up again where it is (its settings applied anew at a song change): not a seek, the mix goes on
        if (id != null && id == song && format.sampleRate == rate && kotlin.math.abs(startFrame - frame) <= rate / 20) return
        val was = song
        if (id != was) {
            pending = null
            // the next song come, the one before cut where its handover was: its end made ready to mix back in
            val t = was?.let { tails[it] }
            val endedMs = was?.let { reached[it] } ?: -1L
            if (t != null && id != null && kotlin.math.abs(endedMs - t.cutMs) < 150 && t.channels == format.channelCount) {
                val expected = (endedMs - t.startMs) * t.rate / 1000.0
                val found = align(t, expected)
                pending = Pending(id, t, found)
                android.util.Log.d("ink-crossfade", "blend: $was ended at ${endedMs / 1000.0}s, its end from frame ${found.toLong()} (expected ${expected.toLong()}, match ${"%.2f".format(matched)})")
            } else if (t != null) {
                android.util.Log.d("ink-crossfade", "blend: $was ended at ${endedMs / 1000.0}s, not where it was cut (${t.cutMs / 1000.0}s): not mixed")
            }
            if (id != null) { reached.keys.retainAll(setOf(id, was)); reached[id] = streamMetadata.positionOffsetUs / 1000 }
        }
        tail = null
        // the new song at its start: the mix begun
        pending?.takeIf { it.into == id && streamMetadata.positionOffsetUs < 2_000_000 }?.let { p ->
            tail = p.tail
            at = p.at
            step = p.tail.rate.toDouble() / format.sampleRate
            mixed = 0
            fadeFrames = (p.tail.fadeMs * format.sampleRate / 1000).coerceAtLeast(1)
            pending = null
            val i0 = at.toInt() * p.tail.channels; val i1 = (i0 + (fadeFrames * step).toInt() * p.tail.channels).coerceAtMost(p.tail.pcm.size)
            var e = 0.0; for (i in i0 until i1) e += p.tail.pcm[i] * p.tail.pcm[i]
            android.util.Log.d("ink-crossfade", "blend: into $id over ${p.tail.fadeMs / 1000.0}s, the end mixed in at ${"%.1f".format(if (i1 > i0) 10 * Math.log10(e / (i1 - i0) + 1e-12) else -999.0)} dBFS")
        }
        song = id
        rate = format.sampleRate
        channels = format.channelCount
        frame = startFrame
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = channels.coerceAtLeast(1)
        val frames = inputBuffer.remaining() / (2 * ch)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * ch * 2)
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val t = tail
        // the samples last played are this song's: begun afresh only when a new song's samples come (a flush of the
        // old one at nothing, as the next comes, had emptied them before they were matched: 2026-11-01)
        if (recentSong != song) { recentSong = song; recentFrames = 0 }
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
        // how alike: 1 the same samples
        var e2 = 0.0; for (v in last) e2 += v * v
        matched = if (e2 > 0) bestScore / Math.sqrt(e2) else 0.0
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
