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
import kotlin.math.roundToLong
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
        val filter: Boolean = false,
        /** The new song's intro quiet over this share of the blend: the old one held up longer, the new one in sooner. */
        val hold: Double = 0.0,
        /** The new song ridden in this many dB under its own (louder than the old end it comes in over), up to it after. */
        val rideDb: Double = 0.0)

    /**
     * The end stretched to the next song's tempo (beats matched; Crossfade): for that song only, its samples from
     * `fromMs` (the old song's time) played `speed` times as fast, so its beats fall on the new one's; and the bass
     * handed across at the middle.
     */
    class Stretched(val into: String, val pcm: FloatArray, val fromMs: Double, val speed: Double, val periodMs: Double)

    /** The ends to mix back in, by song (Crossfade sets them as it cuts a song short). */
    val tails = ConcurrentHashMap<String, Tail>()

    /**
     * Each song's levelling gain (linear; Levelling sets it as a song's worked out), applied here, sample by sample, while
     * transitions are on (`levelled`): through the player's volume it changed for everything at once as a song began,
     * the old song's end mixed under the new one at the new one's gain, and the old one's last moments, already queued,
     * too (a step, heard as a gap; "volume levels are different", 2026-11-08). A song's own, the old end's its own.
     */
    val gains = ConcurrentHashMap<String, Float>()
    @Volatile var levelled = false

    /**
     * Each song's decode against its stream (frames): the decoder this reads with keeps an encoder's lead-in the player
     * trims (2112 samples of AAC, 44 ms), so where the player says a song is, read in its decode, is off by it. Learnt
     * while the song plays, in its decoded end before its cut, where it's loud enough to match well; used where the
     * match at the cut itself is weak (a quiet or noisy end: there the guess was 44 ms out, a stutter, 2026-11-08).
     */
    private val offsets = HashMap<String, Double>()

    // ---- each blend measured, for the log (2026-11-08: tuned by reading these, the music left playing) ----
    // the two songs' low ends, every 10 ms, through the blend: how far apart their kicks land; their loudness; the output's
    // largest step in its first 256 frames against the steps after (a click or a skip shows as a spike)
    private var hop = 480
    private var hopAt = 0
    private var eNew = 0.0; private var eOld = 0.0
    private var lpN = 0.0; private var lpO = 0.0
    private val envNew = ArrayList<Double>(); private val envOld = ArrayList<Double>()
    private var rmsNew = 0.0; private var rmsOld = 0.0; private var rmsN = 0
    private var lastOut = 0.0; private var jumpHead = 0.0; private var jumpSum = 0.0; private var jumpN = 0; private var jumpFirst = 0.0
    private var measuring: String? = null
    private var offsetTries = 0
    private var g = 1.0          // the gain now (gliding to the song's over ~20 ms)
    private var ride = 1.0       // the fader ridden on a new song come in by a blend, up to 1 over the blend and 4 s
    private var rideStep = 0.0
    // the blend's own loudness kept in hand (2026-11-08, "still gets loud"): each one's power and the sum's over ~400 ms;
    // where the two together come out louder than the one handing to the other would be alone (old's level to new's, as
    // the blend goes), both turned down by the difference; back to 1 after
    private var pOld = 0.0; private var pNew = 0.0; private var pSum = 0.0
    private var mg = 1.0
    private var mgLeast = 1.0     // how far it was turned down, for the log
    private var oldGain = 1.0    // the old song's, for its end mixed in

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

    /**
     * A blend as heard, kept to look at (tuning, 2026-11-08): with a file "on" in this folder, each blend's output from 2 s
     * before to 3 s after written there as 16-bit PCM (<id>-<rate>-<channels>.pcm), the last six kept. Off otherwise.
     */
    @Volatile var dumpDir: java.io.File? = null
    /** A blend begun, for Tap (the sound as it leaves, after Resample): the name its keeping goes by. */
    @Volatile var tapStart: String? = null
    private val preroll = ArrayDeque<ByteArray>(); private var prerollBytes = 0
    private var capture: java.io.ByteArrayOutputStream? = null; private var captureLeft = 0L; private var captureName = ""
    private var stretched: Stretched? = null
    private var sAt = 0.0          // where in the stretched samples
    // the old one pulled into step (2026-11-08: kicks 60 ms apart, sure 3.4): its kicks heard against the new one's over
    // the blend's first 2 s, and where they land apart (20-120 ms, surely), its stretched end read that much further on
    // (or back) over the next 2.5 s or more: a 2% nudge at most, under a fading song
    // the switch's step taken out (2026-11-08, "heard a tiny clip": the decode is the same music, not quite the same samples,
    // 0.97 alike, and the change to it a 6x step): each channel's last sample out, and the difference at a blend's first
    // sample added back, dying away over ~2 ms
    private val lastOutC = DoubleArray(8); private val declick = DoubleArray(8); private var declicked = 0.0
    private var nudgeLeft = 0.0; private var nudgeStep = 0.0; private var nudged = 0
    // the old song's own samples into its stretched ones: 30 ms, then 90 (2026-11-08: the join's step 7-11x the music's on
    // beats-matched blends against 2-4x on plain ones, "the tiniest hitch... little skip stutter")
    private val joinFrames get() = (rate * 90 / 1000).coerceAtLeast(1)
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
        // a new song set up first with where the old one ended as its place (Media3 sets it up twice at a change of
        // rate, the second at its start): its start, so the mix begins on its first sample (taken for a jump into it,
        // the new one's first 23 ms went out alone, its encoder's silence a 15 ms gap, "little skip", 2026-11-10)
        val endedThere = if (id != null && id != song) song?.let { reached[it] } else null
        val offsetUs = if (endedThere != null && kotlin.math.abs(streamMetadata.positionOffsetUs / 1000 - endedThere) < 500) 0L else streamMetadata.positionOffsetUs
        val startFrame = offsetUs * format.sampleRate / 1_000_000
        // the same song set up again where it is (its settings applied anew at a song change): not a seek, the mix goes on
        if (id != null && id == song && format.sampleRate == rate && kotlin.math.abs(startFrame - frame) <= rate / 20) return
        val was = song
        if (id != was) {
            pending = null
            // the old one's end keeps the gain it had; the new one's own from its first sample, where it's known
            oldGain = g
            if (levelled) id?.let { gains[it] }?.let { g = it.toDouble() }
            // the next song come, the one before cut where its handover was: its end made ready to mix back in
            val t = was?.let { tails[it] }
            val endedMs = was?.let { reached[it] } ?: -1L
            if (t != null && id != null && kotlin.math.abs(endedMs - t.cutMs) < 150 && t.channels == format.channelCount) {
                val expected = (endedMs - t.startMs) * t.rate / 1000.0
                // the decode already playing in place of the old song's own samples: on from just where it is
                val found = if (early === t) earlyAt else align(t, expected, offsets[was])
                pending = Pending(id, t, found)
                android.util.Log.d("ink-crossfade", "blend: $was ended at ${endedMs / 1000.0}s, its end from frame ${found.toLong()} " +
                    if (early === t) "(its decode already playing)" else "(expected ${expected.toLong()}, match ${"%.2f".format(matched)}${if (early != null) "; the decode playing was another plan's" else ""})")
            } else if (t != null) {
                android.util.Log.d("ink-crossfade", "blend: $was ended at ${endedMs / 1000.0}s, not where it was cut (${t.cutMs / 1000.0}s): not mixed")
            }
            if (id != null) { reached.keys.retainAll(setOf(id, was)); reached[id] = offsetUs / 1000 }
        }
        tail = null
        early = null
        ride = 1.0; rideStep = 0.0
        pOld = 0.0; pNew = 0.0; pSum = 0.0
        // the new song at its start: the mix begun
        pending?.takeIf { it.into == id && offsetUs < 2_000_000 }?.let { p ->
            tail = p.tail
            at = p.at
            step = p.tail.rate.toDouble() / format.sampleRate
            mixed = 0
            fadeFrames = (p.tail.fadeMs * format.sampleRate / 1000).coerceAtLeast(1)
            if (dumpDir?.let { java.io.File(it, "on").exists() } == true) {
                capture = java.io.ByteArrayOutputStream().also { o -> preroll.forEach { o.write(it) } }
                captureLeft = (fadeFrames + 3L * format.sampleRate) * format.bytesPerFrame
                captureName = "${System.currentTimeMillis() / 1000}-$id-${format.sampleRate}-${format.channelCount}.pcm"
                tapStart = "${System.currentTimeMillis() / 1000}-$id"
            }
            pending = null
            // beats matched for this song: its end stretched, from where the old one actually stopped
            stretched = p.tail.stretched?.takeIf { it.into == id }
            ride = Math.pow(10.0, p.tail.rideDb / 20); rideStep = (1 - ride) / (fadeFrames + 4L * format.sampleRate)
            hop = (format.sampleRate / 100).coerceAtLeast(1); hopAt = 0; eNew = 0.0; eOld = 0.0; lpN = 0.0; lpO = 0.0
            envNew.clear(); envOld.clear(); nudgeLeft = 0.0; nudgeStep = 0.0; nudged = 0; rmsNew = 0.0; rmsOld = 0.0; rmsN = 0; jumpHead = 0.0; jumpSum = 0.0; jumpN = 0; mgLeast = 1.0
            measuring = id + if (stretched != null) " (beats matched)" else ""
            filtering = p.tail.filter
            if (filtering) {
                lp = lowPass(150.0, format.sampleRate)
                lowNew.fill(0.0); lowOld.fill(0.0); lpOld.fill(0.0); hpNew.fill(0.0)
                lpOldK = lowPass(20_000.0.coerceAtMost(format.sampleRate * .45), format.sampleRate); hpNewK = highPass(160.0, format.sampleRate)
                // the old one's low-pass run over the 20 ms before, so it starts on the music and not on silence (from zero it
                // stepped on the first sample: the join's step 6x the music's on plain blends, "little skip stutter", 2026-11-08)
                for (c in 0 until minOf(p.tail.channels, 8)) {
                    var i = (p.at - format.sampleRate / 50 * step).coerceAtLeast(0.0)
                    while (i < p.at) { biquad(lpOldK, lpOld, c, sample(p.tail.pcm, p.tail.channels, i, c) * oldGain); i += step }
                }
            }
            stretched?.let { st ->
                val stopMs = p.tail.startMs + p.at * 1000.0 / p.tail.rate
                sAt = (stopMs - st.fromMs) / st.speed * p.tail.rate / 1000.0
                // lined up with the old one's own samples there, sample by sample: the stretcher's output begins a little
                // out of phase with its input, and the 30 ms join to it skipped (a glitch on beats-matched blends, 2026-11-08)
                sAt = lineUp(st.pcm, p.tail.pcm, p.tail.channels, p.at, sAt)
                lp = lowPass(150.0, format.sampleRate)
                lowNew.fill(0.0); lowOld.fill(0.0)
            }
            val i0 = at.toInt() * p.tail.channels; val i1 = (i0 + (fadeFrames * step).toInt() * p.tail.channels).coerceAtMost(p.tail.pcm.size)
            var e = 0.0; for (i in i0 until i1) e += p.tail.pcm[i] * p.tail.pcm[i]
            android.util.Log.d("ink-crossfade", "blend: into $id over ${p.tail.fadeMs / 1000.0}s, the end mixed in at ${"%.1f".format(if (i1 > i0) 10 * Math.log10(e / (i1 - i0) + 1e-12) else -999.0)} dBFS")
        }
        if (id != song) offsetTries = 0
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
                earlyAt = align(mine, (frame * 1000.0 / rate - mine.startMs) * mine.rate / 1000, song?.let { offsets[it] })
                earlyFrames = 0
                early = mine
            }
        }
        // the offset learnt: a second into its decoded end, again every second while it won't match (five tries)
        if (t == null && early == null && mine != null && mine.rate == rate && mine.channels == ch && song != null && !offsets.containsKey(song) && offsetTries < 5) {
            val endStart = mine.startMs * rate / 1000
            val cutFrame = mine.cutMs * rate / 1000
            if (frame > endStart + rate * (1 + offsetTries) && frame < cutFrame - rate / 2 && recentFrames >= 512) {
                offsetTries++
                val guess = (frame * 1000.0 / rate - mine.startMs) * mine.rate / 1000
                val found = align(mine, guess, null)
                if (matched >= .7) {
                    offsets[song!!] = found - guess
                    if (offsets.size > 50) offsets.keys.take(25).forEach { offsets.remove(it) }
                    android.util.Log.d("ink-crossfade", "blend: $song's decode is ${(found - guess).toLong()} frames from its stream (match ${"%.2f".format(matched)})")
                }
            }
        }
        val e = early
        val target = if (levelled) song?.let { gains[it] }?.toDouble() ?: g else 1.0
        // a song's gain worked out after it began (measured a moment in): over a second and a half, not 20 ms, so it
        // eases to its level rather than stepping
        val late = frame > rate && kotlin.math.abs(target - g) > .01
        val glide = 1 - Math.exp(-1.0 / ((if (late) 1.5 else .02) * rate.coerceAtLeast(1)))
        val env = 1 - Math.exp(-1.0 / (.4 * rate.coerceAtLeast(1)))
        val down = 1 - Math.exp(-1.0 / (.08 * rate.coerceAtLeast(1)))
        val up = 1 - Math.exp(-1.0 / (.5 * rate.coerceAtLeast(1)))
        for (f in 0 until frames) {
            g += (target - g) * glide
            if (ride < 1) ride = (ride + rideStep).coerceAtMost(1.0)
            if (t == null && mg != 1.0) { mg += (1 - mg) * up; if (kotlin.math.abs(1 - mg) < 1e-4) mg = 1.0 }
            if (t == null && e != null) {
                // its own samples giving way to its decode over 30 ms, then the decode alone
                val r = (earlyFrames.toDouble() / joinFrames).coerceAtMost(1.0)
                for (c in 0 until ch) {
                    val s = inp.short / 32768.0
                    val v = limit((s * (1 - r) + sample(e.pcm, e.channels, earlyAt, c) * r) * g * mg)
                    out.putShort((v * 32768).toInt().coerceIn(-32768, 32767).toShort())
                    if (c == 0) lastOut = v
                    if (c < 8) lastOutC[c] = v
                    if (c < 2) recent[(recentFrames % 2048) * 2 + c] = s.toFloat()
                }
                recentFrames++
                earlyAt += 1.0
                earlyFrames++
            } else if (t == null) {
                val plain = kotlin.math.abs(g * ride * mg - 1) < 1e-4
                for (c in 0 until ch) {
                    val s = inp.short
                    val o = if (plain) s else (limit(s / 32768.0 * g * ride * mg) * 32768).toInt().coerceIn(-32768, 32767).toShort()
                    out.putShort(o)
                    // the last sample out, so a blend's first step is measured against what was just heard (it was the
                    // previous blend's last, minutes before: the join's step read 6-31x on nothing, 2026-11-08)
                    if (c == 0) lastOut = o / 32768.0
                    if (c < 8) lastOutC[c] = o / 32768.0
                    if (c < 2) recent[(recentFrames % 2048) * 2 + c] = s / 32768f
                }
                recentFrames++
            } else {
                val k = mixed.toDouble() / fadeFrames
                val st = stretched
                // equal power for two songs that aren't in step (their sum stays as loud); beats matched, their kicks land
                // together and add up louder (3 dB at the middle, "noticeably louder during mix", 2026-11-08): half way to
                // equal gain, about level either way
                // a quiet intro coming in: the old one held at full over most of it, the new one rising sooner
                val h = t.hold
                val kOld = if (h > 0) ((k - .8 * h) / (1 - .8 * h)).coerceIn(0.0, 1.0) else k
                val kNew = if (h > 0) (k / (1 - .5 * h)).coerceIn(0.0, 1.0) else k
                val gNew = if (k >= 1) 1.0 else if (st != null) (sin(kNew * PI / 2) + kNew) / 2 else sin(kNew * PI / 2)
                val gOld = if (k >= 1) 0.0 else if (st != null) (cos(kOld * PI / 2) + 1 - kOld) / 2 else cos(kOld * PI / 2)
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
                var eo = 0.0; var en = 0.0; var es = 0.0
                for (c in 0 until ch) {
                    var s = inp.short / 32768.0 * g * ride
                    var old = sample(t.pcm, t.channels, at, c) * oldGain
                    if (st != null) {
                        // the old one's own samples into its stretched ones, so the joining makes no click
                        // (with its gain too: left out, the old one rose 5-8 dB through every beats-matched blend, 2026-11-08)
                        old = old * (1 - join) + sample(st.pcm, t.channels, sAt, c) * oldGain * join
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
                    val sum = s * gNew + old * gOld
                    if (c < 2) { eo += old * old; en += s * s; es += sum * sum }
                    var v = limit(sum * mg)
                    if (c < 8) {
                        if (mixed == 0L) { declick[c] = lastOutC[c] - v; if (c == 0) declicked = kotlin.math.abs(declick[c]) }
                        if (mixed < rate / 50) v += declick[c] * Math.exp(-mixed / (.002 * rate))
                        lastOutC[c] = v
                    }
                    out.putShort((v * 32768).toInt().coerceIn(-32768, 32767).toShort())
                    if (c == 0) {
                        // the low ends (150 Hz), their energy each 10 ms; the loudness; the output's steps
                        lpN += .02 * (s - lpN); lpO += .02 * (old - lpO)
                        eNew += lpN * lpN; eOld += lpO * lpO
                        if (mixed < rate) { rmsNew += s * s; rmsOld += old * old; rmsN++ }
                        val step = kotlin.math.abs(v - lastOut); lastOut = v
                        if (mixed == 0L) jumpFirst = step
                        if (mixed < 256) jumpHead = maxOf(jumpHead, step) else if (mixed < 4096) { jumpSum += step; jumpN++ }
                    }
                }
                pOld += (eo - pOld) * env; pNew += (en - pNew) * env; pSum += (es - pSum) * env
                val kk = k.coerceIn(0.0, 1.0)
                val ref = pOld * (1 - kk) + pNew * kk
                val want = if (pSum > ref * 1.26 && pSum > 1e-7) Math.sqrt(ref * 1.26 / pSum) else 1.0
                mg += (want - mg) * (if (want < mg) down else up)
                if (mg < mgLeast) mgLeast = mg
                if (++hopAt >= hop) { hopAt = 0; envNew += eNew; envOld += eOld; eNew = 0.0; eOld = 0.0 }
                at += step
                if (st != null) {
                    sAt += step
                    if (mixed == 2L * rate && fadeFrames > 4L * rate) {
                        val (lag, sure) = kicksApart(12)
                        if (sure >= 2.5 && kotlin.math.abs(lag) in 2..12) {
                            nudged = envNew.size + (maxOf(2.5, kotlin.math.abs(lag) * 10 / 20.0) * 100).toInt()
                            nudgeLeft = lag * hop * step; nudgeStep = nudgeLeft / (maxOf(2.5, kotlin.math.abs(lag) * 10 / 20.0) * rate)   // 2% at most
                            android.util.Log.d("ink-crossfade", "blend: kicks ${lag * 10} ms apart (sure ${"%.1f".format(sure)}): the old one pulled into step")
                        }
                    }
                    if (nudgeLeft != 0.0) {
                        val d = if (kotlin.math.abs(nudgeLeft) <= kotlin.math.abs(nudgeStep)) nudgeLeft else nudgeStep
                        sAt += d; nudgeLeft -= d
                    }
                }
                if (++mixed >= fadeFrames) { said(); tail = null; stretched = null; filtering = false }
            }
        }
        frame += frames
        song?.let { s -> if (rate > 0) reached[s] = frame * 1000 / rate }
        out.flip()
        keep(out)
    }

    /** The output kept: the last 2 s always (while dumping is on), a blend's as it plays, written once it's done. */
    private fun keep(out: ByteBuffer) {
        val dir = dumpDir ?: return
        if (!java.io.File(dir, "on").exists()) { preroll.clear(); prerollBytes = 0; return }
        val b = ByteArray(out.remaining()); out.duplicate().get(b)
        preroll.addLast(b); prerollBytes += b.size
        val bpf = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        while (prerollBytes - preroll.first().size > 2 * rate * bpf) prerollBytes -= preroll.removeFirst().size
        val c = capture ?: return
        c.write(b); captureLeft -= b.size
        if (captureLeft <= 0) {
            capture = null
            val bytes = c.toByteArray(); val name = captureName
            Thread {
                runCatching {
                    java.io.File(dir, name).writeBytes(bytes)
                    dir.listFiles { f -> f.name.endsWith(".pcm") }?.sortedByDescending { it.name }?.drop(6)?.forEach { it.delete() }
                }
            }.start()
        }
    }

    // the ends kept: Crossfade lets them go (a reset here, with a song still cut short, would leave it ending on nothing)
    override fun onReset() { tail = null; stretched = null; filtering = false; song = null }

    /** A blend's measures, logged as it ends: the kicks' offset, the loudness apart, the join's step. */
    /**
     * The kicks' offset so far: onsets (rises in the low end) of each, and the lag (in 10 ms hops, ±range) at which they
     * line up best; positive, the old one's land later. And how sure (the best against the mean of all lags).
     */
    private fun kicksApart(range: Int, fromHop: Int = 0): Pair<Int, Double> {
        fun rises(e: List<Double>) = e.indices.map { i -> if (i == 0) 0.0 else maxOf(0.0, Math.log(e[i] + 1e-9) - Math.log(e[i - 1] + 1e-9)) }
        val a = rises(envNew); val b = rises(envOld)
        val n = minOf(a.size, b.size)
        var best = 0; var bestS = Double.NEGATIVE_INFINITY; var sum = 0.0; var cnt = 0
        if (n - fromHop > 100) for (lag in -range..range) {
            var sc = 0.0
            for (i in maxOf(10, fromHop) until n - 10) { val j = i + lag; if (j in 0 until n) sc += a[i] * b[j] }
            sum += sc; cnt++
            if (sc > bestS) { bestS = sc; best = lag }
        }
        return best to (if (cnt > 0 && sum > 0) bestS / (sum / cnt) else 0.0)
    }

    private fun said() {
        val what = measuring ?: return
        measuring = null
        val (best, sure) = kicksApart(8)
        val after = if (nudged > 0) kicksApart(8, nudged) else null
        val apartDb = if (rmsN > 0 && rmsOld > 0 && rmsNew > 0) 10 * Math.log10(rmsOld / rmsNew) else 0.0
        val spike = if (jumpN > 0 && jumpSum > 0) jumpHead / (jumpSum / jumpN) else 0.0
        val first = if (jumpN > 0 && jumpSum > 0) declicked / (jumpSum / jumpN) else 0.0
        android.util.Log.d("ink-crossfade", "measured $what: kicks ${best * 10} ms apart (sure ${"%.1f".format(sure)}), the old end ${"%.1f".format(apartDb)} dB against the new, the join's step ${"%.1f".format(spike)}x the music's (the switch's ${"%.1f".format(first)}x, taken out)" +
            (after?.let { (l, sr) -> ", pulled into step: then ${l * 10} ms apart (sure ${"%.1f".format(sr)})" } ?: "") +
            (if (mgLeast < .97) ", kept level: turned down ${"%.1f".format(-20 * Math.log10(mgLeast))} dB at most" else ""))
    }

    /** A gentle ceiling: above 0.89 (-1 dBFS) eased toward 1, so a quiet song lifted by its gain doesn't clip. */
    private fun limit(v: Double): Double {
        val a = kotlin.math.abs(v)
        if (a <= .89) return v
        return Math.copySign(.89 + .11 * kotlin.math.tanh((a - .89) / .11), v)
    }

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
    private fun align(t: Tail, expected: Double, known: Double?): Double {
        val n = minOf(recentFrames, 512)
        if (n < 256 || t.channels < 1) return expected + (known ?: 0.0)
        val c = t.channels
        // the last n frames played, oldest first, as one channel
        val last = FloatArray(n) { j -> val r = ((recentFrames - n + j) % 2048) * 2; (recent[r] + recent[r + 1]) / 2 }
        var best = expected.toInt(); var bestScore = Double.NEGATIVE_INFINITY
        val knownAt = known?.let { (expected + it).roundToLong().toInt() }; var knownScore = Double.NEGATIVE_INFINITY
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
            if (p == knownAt) knownScore = score
            p++
        }
        // how alike: 1 the same samples
        var e2 = 0.0; for (v in last) e2 += v * v
        matched = if (e2 > 0) bestScore / Math.sqrt(e2) else 0.0
        // where the offset learnt as it played puts it, nearly as alike: there (a looser match elsewhere moved the old one
        // 64 ms against a 0.96 one learnt seconds before, heard as a hitch, 2026-11-08)
        if (knownAt != null && knownScore > Double.NEGATIVE_INFINITY && e2 > 0) {
            val km = knownScore / Math.sqrt(e2)
            if (km >= matched - .1 || km >= .7) { matched = km; return knownAt.toDouble() }
        }
        // a weak likeness (a quiet or noisy end): where the player says it is, with the song's offset learnt while it
        // played, not a jump to a poor match (0.29 moved Say What's end 33 ms, heard as a skip, 2026-11-08)
        return if (matched < .5) expected + (known ?: 0.0) else best.toDouble()
    }

    /**
     * Where in `stretched` the old song's own samples from frame `at` (of `own`) are: near `guess`, the place whose next
     * 768 frames are most like them (a stretch at near 1x keeps the waveform's shape over so short a stretch).
     */
    private fun lineUp(stretched: FloatArray, own: FloatArray, channels: Int, at: Double, guess: Double): Double {
        val n = 768; val c = channels.coerceAtLeast(1)
        val o0 = at.toInt()
        if ((o0 + n) * c > own.size || o0 < 0) return guess
        val ref = FloatArray(n) { j -> var v = 0f; for (k in 0 until minOf(c, 2)) v += own[(o0 + j) * c + k]; v }
        var e2 = 0.0; for (v in ref) e2 += v * v
        if (e2 <= 1e-9) return guess
        var best = guess.toInt(); var bestScore = Double.NEGATIVE_INFINITY
        val from = (guess - 1500).toInt().coerceAtLeast(0); val to = (guess + 1500).toInt().coerceAtMost(stretched.size / c - n - 1)
        for (p in from..to) {
            var dot = 0.0; var e = 0.0
            for (j in 0 until n) { var v = 0f; for (k in 0 until minOf(c, 2)) v += stretched[(p + j) * c + k]; dot += v * ref[j]; e += v * v }
            val score = if (e > 0) dot / Math.sqrt(e) else 0.0
            if (score > bestScore) { bestScore = score; best = p }
        }
        val like = bestScore / Math.sqrt(e2)
        android.util.Log.d("ink-crossfade", "blend: stretched end lined up at ${best} (guessed ${guess.toLong()}, likeness ${"%.2f".format(like)})")
        return if (like < .5) guess else best.toDouble()
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
