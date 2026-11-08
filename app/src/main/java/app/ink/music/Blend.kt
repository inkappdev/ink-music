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
 * The crossfade made in the player's own sound, sample by sample (2026-11-01: two players couldn't be held
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
    class Tail(val startMs: Long, val rate: Int, val channels: Int, val pcm: FloatArray, val cutMs: Long, val fadeMs: Long, val stretched: Stretched? = null,
        /** Not beats matched, in automix: the filter fade (the old one sinking under a low-pass, the new one opening from a high-pass). */
        val filter: Boolean = false)

    /**
     * The end stretched to the next song's tempo (beats matched; Crossfade): for that song only, its samples from
     * `fromMs` (the old song's time) played `speed` times as fast, so its beats fall on the new one's; and the bass
     * handed across at the middle.
     */
    class Stretched(val into: String, val pcm: FloatArray, val fromMs: Double, val speed: Double, val periodMs: Double)

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
    private var stretched: Stretched? = null
    private var sAt = 0.0          // where in the stretched samples
    private val joinFrames get() = (rate * 30 / 1000).coerceAtLeast(1)   // the old song's own samples into its stretched ones, 30 ms
    // the bass handed across: each side's lows (a low-pass at 150 Hz), the new one's held out till the middle, the old one's after
    private val lowNew = DoubleArray(8 * 4); private val lowOld = DoubleArray(8 * 4)
    private var lp = DoubleArray(5)
    // the filter fade (2026-11-08, after FireTheDJ's, github.com/suryablaze02-byte/FireTheDJ, GPL-3.0): two songs not in
    // step laid over each other were a muddle; the old one's top closes over the whole fade (20 kHz down to 300 Hz), the
    // new one comes in thin (its lows held under 160 Hz) and opens fully by the middle, and the basses swap there, so
    // there's only ever one bass line and one bright top
    private var filtering = false
    private var lpOldK = DoubleArray(5); private var hpNewK = DoubleArray(5)
    private val lpOld = DoubleArray(8 * 4); private val hpNew = DoubleArray(8 * 4)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun isActive() = true

    // a mix made ready at the song change (the old song's last samples matched then, while they're here), begun at
    // the new song's flush at its start: Media3 flushes it first where the old one was, then at its start (2026-11-01)
    private class Pending(val into: String, val tail: Tail, val at: Double)

    // the join made early (2026-11-01: a hitch where the old song's own stream gave way to its decode, the two not quite
    // alike for a lossy song): a quarter second before its cut, its decode lined up and faded in over its own samples in
    // 30 ms, so by the time its stream stops the decode is what's heard, and it simply goes on
    private var early: Tail? = null
    private var earlyAt = 0.0          // where in the decode, this frame
    private var earlyFrames = 0L       // how far into the fade from its own samples to its decode
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
                // the decode already playing in place of the old song's own samples: on from just where it is
                val found = if (early === t) earlyAt else align(t, expected)
                pending = Pending(id, t, found)
                android.util.Log.d("ink-crossfade", "blend: $was ended at ${endedMs / 1000.0}s, its end from frame ${found.toLong()} (expected ${expected.toLong()}, match ${"%.2f".format(matched)})")
            } else if (t != null) {
                android.util.Log.d("ink-crossfade", "blend: $was ended at ${endedMs / 1000.0}s, not where it was cut (${t.cutMs / 1000.0}s): not mixed")
            }
            if (id != null) { reached.keys.retainAll(setOf(id, was)); reached[id] = streamMetadata.positionOffsetUs / 1000 }
        }
        tail = null
        early = null
        // the new song at its start: the mix begun
        pending?.takeIf { it.into == id && streamMetadata.positionOffsetUs < 2_000_000 }?.let { p ->
            tail = p.tail
            at = p.at
            step = p.tail.rate.toDouble() / format.sampleRate
            mixed = 0
            fadeFrames = (p.tail.fadeMs * format.sampleRate / 1000).coerceAtLeast(1)
            pending = null
            // beats matched for this song: its end stretched, from where the old one actually stopped
            stretched = p.tail.stretched?.takeIf { it.into == id }
            filtering = p.tail.filter
            if (filtering) {
                lp = lowPass(150.0, format.sampleRate)
                lowNew.fill(0.0); lowOld.fill(0.0); lpOld.fill(0.0); hpNew.fill(0.0)
                lpOldK = lowPass(20_000.0.coerceAtMost(format.sampleRate * .45), format.sampleRate); hpNewK = highPass(160.0, format.sampleRate)
            }
            stretched?.let { st ->
                val stopMs = p.tail.startMs + p.at * 1000.0 / p.tail.rate
                sAt = (stopMs - st.fromMs) / st.speed * p.tail.rate / 1000.0
                lp = lowPass(150.0, format.sampleRate)
                lowNew.fill(0.0); lowOld.fill(0.0)
            }
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
        // a quarter second before this song's cut: its decode lined up with what's been played, to take over from it
        val mine = song?.let { tails[it] }
        if (t == null && early == null && mine != null && mine.rate == rate && mine.channels == ch) {
            val cutFrame = mine.cutMs * rate / 1000
            if (frame >= cutFrame - rate / 4 && frame < cutFrame) {
                earlyAt = align(mine, (frame * 1000.0 / rate - mine.startMs) * mine.rate / 1000)
                earlyFrames = 0
                early = mine
            }
        }
        val e = early
        for (f in 0 until frames) {
            if (t == null && e != null) {
                // its own samples giving way to its decode over 30 ms, then the decode alone
                val r = (earlyFrames.toDouble() / joinFrames).coerceAtMost(1.0)
                for (c in 0 until ch) {
                    val s = inp.short / 32768.0
                    val v = s * (1 - r) + sample(e.pcm, e.channels, earlyAt, c) * r
                    out.putShort((v * 32768).toInt().coerceIn(-32768, 32767).toShort())
                    if (c < 2) recent[(recentFrames % 2048) * 2 + c] = s.toFloat()
                }
                recentFrames++
                earlyAt += 1.0
                earlyFrames++
            } else if (t == null) {
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
                val st = stretched
                // the bass across the middle tenth (beats matched, or the filter fade): the new one's lows in, the old one's out
                // over a tenth of the blend, half a second at least (a short blend's tenth was a sudden drop of the low end)
                val swapW = maxOf(.1, rate * .5 / fadeFrames)
                val swap = ((k - (.5 - swapW / 2)) / swapW).coerceIn(0.0, 1.0)
                // the filter fade's two filters moved along every 64 frames
                if (filtering && mixed % 64 == 0L) {
                    lpOldK = lowPass((20_000.0 * Math.pow(300.0 / 20_000.0, k.coerceIn(0.0, 1.0))).coerceAtMost(rate * .45), rate)
                    val open = (1 - k / .5).coerceIn(0.0, 1.0)   // the new one's high-pass, 160 Hz down to 20 by the middle
                    hpNewK = highPass(20.0 * Math.pow(8.0, open), rate)
                }
                val join = if (st != null) (mixed.toDouble() / joinFrames).coerceAtMost(1.0) else 0.0
                for (c in 0 until ch) {
                    var s = inp.short / 32768.0
                    var old = sample(t.pcm, t.channels, at, c)
                    if (st != null) {
                        // the old one's own samples into its stretched ones, so the joining makes no click
                        old = old * (1 - join) + sample(st.pcm, t.channels, sAt, c) * join
                        if (c < 8) {
                            val ln = filter(lowNew, c, s); s = (s - ln) + ln * swap
                            val lo = filter(lowOld, c, old); old = (old - lo) + lo * (1 - swap)
                        }
                        // keys clashing: the old one sinks under the low-pass as well
                        if (filtering && c < 8) old = biquad(lpOldK, lpOld, c, old)
                    } else if (filtering && c < 8) {
                        s = biquad(hpNewK, hpNew, c, s)
                        old = biquad(lpOldK, lpOld, c, old)
                        val ln = filter(lowNew, c, s); s = (s - ln) + ln * swap
                        val lo = filter(lowOld, c, old); old = (old - lo) + lo * (1 - swap)
                    }
                    val v = s * gNew + old * gOld
                    out.putShort((v * 32768).toInt().coerceIn(-32768, 32767).toShort())
                }
                at += step
                if (st != null) sAt += step
                if (++mixed >= fadeFrames) { tail = null; stretched = null; filtering = false }
            }
        }
        frame += frames
        song?.let { s -> if (rate > 0) reached[s] = frame * 1000 / rate }
        out.flip()
    }

    // the ends kept: Crossfade lets them go (a reset here, with a song still cut short, would leave it ending on nothing)
    override fun onReset() { tail = null; stretched = null; filtering = false; song = null }

    /** A sample between two (linear), of channel `c`, at a fractional frame; past the end, nothing. */
    private fun sample(pcm: FloatArray, channels: Int, at: Double, c: Int): Double {
        val i = at.toInt(); val f = at - i
        val a = pcm.getOrElse(i * channels + c) { 0f }; val b = pcm.getOrElse((i + 1) * channels + c) { 0f }
        return a + (b - a) * f
    }

    /** RBJ's low-pass (Q 0.707) at `hz`: b0, b1, b2, a1, a2. */
    private fun lowPass(hz: Double, rate: Int): DoubleArray {
        val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * 0.7071); val cw = cos(w); val a0 = 1 + alpha
        return doubleArrayOf((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
    }

    /** RBJ's high-pass (Q 0.707) at `hz`: b0, b1, b2, a1, a2. */
    private fun highPass(hz: Double, rate: Int): DoubleArray {
        val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * 0.7071); val cw = cos(w); val a0 = 1 + alpha
        return doubleArrayOf((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
    }

    /** One sample through a biquad `k`, channel `c`'s state (x1, x2, y1, y2) in `st`. */
    private fun biquad(k: DoubleArray, st: DoubleArray, c: Int, x: Double): Double {
        val o = c * 4
        val y = k[0] * x + k[1] * st[o] + k[2] * st[o + 1] - k[3] * st[o + 2] - k[4] * st[o + 3]
        st[o + 1] = st[o]; st[o] = x; st[o + 3] = st[o + 2]; st[o + 2] = y
        return y
    }

    /** One sample through the low-pass, channel `c`'s state (x1, x2, y1, y2) in `st`. */
    private fun filter(st: DoubleArray, c: Int, x: Double): Double {
        val o = c * 4
        val y = lp[0] * x + lp[1] * st[o] + lp[2] * st[o + 1] - lp[3] * st[o + 2] - lp[4] * st[o + 3]
        st[o + 1] = st[o]; st[o] = x; st[o + 3] = st[o + 2]; st[o + 2] = y
        return y
    }

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
        // a weak likeness (a quiet or noisy end): where the player says it is, not a jump to a poor match (0.29 moved
        // Say What's end 33 ms, heard as a skip, 2026-11-08)
        return if (matched < .5) expected else best.toDouble()
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
