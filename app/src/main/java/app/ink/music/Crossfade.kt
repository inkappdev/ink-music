package app.ink.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Automix's first half, the smart crossfade (settings, sound; Bronson, 2026-11-01, after BitChord's Automix): a song
 * ending goes into the next, the next coming in under it as it goes. Not at a fixed time: each song's end is
 * measured as it starts (its last 25 seconds decoded on their own), so a song fading out hands over as it fades,
 * and one ending in silence hands over before the silence; the next one's own silence at its start is skipped.
 *
 * Media3 mixes nothing, so a second player, quiet and apart (no audio focus, no session), plays the old song's last
 * seconds from where it is while the player itself goes on into the next song: the queue, the lock screen and the
 * player page are the next song's from the first moment. Equal-power curves, so the loudness neither dips nor bumps.
 *
 * Never within a record played in order (its songs run into each other as made), never an episode or a station,
 * never with exact on (it changes the sound) or on a speaker, never on repeat one, never a skip of yours.
 *
 * Automix's second half, the beats matched (Bronson: "start"): each song's beat found in the same decode (an onset
 * curve from its low end, the kick; its period by autocorrelation, 60 to 180 bpm; its grid laid on), and where both
 * are sure and their tempos within 8% (or double, or half), the handover lands on one of the old song's beats, the
 * new one comes in on its own beat at the old one's tempo (stretched, its pitch kept), held there by a loop that
 * nudges it back onto the old one's beats, the bass handed across at the middle so two kicks never fight; after,
 * the new one eases back to its own tempo. Not sure of either beat: the plain crossfade.
 */
class Crossfade(
    private val c: Context, private val exo: ExoPlayer, private val sources: MediaSource.Factory,
    private val levelling: () -> Levelling?, private val casting: () -> Boolean,
    private val blend: Blend? = null, private val inPlace: () -> Unit = {},
) {
    /** A song's beat over a stretch: its period (ms), one beat's time (ms into the song), and how sure (peak over mean). */
    data class Beats(val period: Double, val first: Double, val sure: Double) {
        val bpm get() = 60_000 / period
        fun nearest(t: Double) = first + Math.round((t - first) / period) * period
        fun after(t: Double) = first + Math.ceil((t - first) / period) * period
    }

    /** Where a song's sound ends (ms), where its handover begins, where its sound begins; its beat at its end and its start. */
    data class Ends(
        val lastSound: Long, val handover: Long, val firstSound: Long, val tail: Beats? = null, val head: Beats? = null,
        internal val end: Any? = null,   // its end decoded, every channel (a Pcm; none for a file deeper than 16 bit)
        val duration: Long = 0,
    )

    /** A beat-matched handover: the old song's beat it lands on, the new one's beat it comes in on, its tempo there. */
    private data class Mix(val outBeat: Double, val inBeat: Double, val speed: Float, val inPeriod: Double, val outPeriod: Double)

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val ends = ConcurrentHashMap<String, Ends>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private var tail: ExoPlayer? = null
    private var tailFor: String? = null
    private var fading = false
    private var ours = false   // the song change in hand is the crossfade's own

    companion object {
        const val FADE = 6_000L
        /** How far ahead of the handover the tail's started (it's heard a moment after it says it plays). */
        const val LEAD = 120L
        /** Beats matched: off till the mix is made in the player's own sound (sample by sample). */
        const val MATCH = false
        /** How sure a beat must be (its period's peak over the mean) to be matched. */
        const val SURE = 1.6
        fun on(c: Context) = File(c.filesDir, "crossfade").exists()
        fun set(c: Context, on: Boolean) { runCatching { File(c.filesDir, "crossfade").let { if (on) it.writeText("on") else it.delete() } } }
    }

    private val tick = object : Runnable {
        override fun run() { runCatching { look() }; main.postDelayed(this, 100) }
    }

    fun start() {
        main.post(tick)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!ours && fading) end()   // a skip of yours, mid-fade: the old one goes at once
                ours = false
                measure()
                // songs cut short and played (or passed): whole again, so going back plays them as they are
                val now = exo.currentMediaItem?.mediaId
                val next = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { exo.getMediaItemAt(it).mediaId }
                cutSongs.keys.filter { it != now && it != next }.forEach(::uncut)
                cut()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) end() }
        })
    }

    fun release() { main.removeCallbacks(tick); end(); work.shutdown() }

    /** This song and the next: may one go into the other? */
    private fun allowed(): Boolean {
        if (!on(c) || Exact.on(c) || casting() || exo.repeatMode == Player.REPEAT_MODE_ONE) return false
        val i = exo.currentMediaItemIndex
        val n = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return false
        val a = exo.getMediaItemAt(i); val b = exo.getMediaItemAt(n)
        if (listOf(a, b).any { it.mediaId.startsWith("e") || it.mediaId.startsWith("s") }) return false
        // a record in order: its songs run into each other as they were made
        val ra = a.mediaMetadata.extras?.getString(Levelling.RECORD).orEmpty()
        if (ra.isNotEmpty() && ra == b.mediaMetadata.extras?.getString(Levelling.RECORD) && n == i + 1) return false
        return exo.duration != C.TIME_UNSET && exo.duration > FADE * 3
    }

    private fun look() {
        // a song cut short for the blend: its end is mixed in the player's own sound (Blend), not the two-player way
        if (exo.currentMediaItem?.mediaId?.let { it in cutSongs } == true) return
        if (on(c) && exo.currentMediaItem?.mediaId?.let { it !in asked } == true) measure()   // switched on mid-song, or the first
        if (fading || !exo.isPlaying || !allowed()) { if (!fading) dropTail(); return }
        val item = exo.currentMediaItem ?: return
        val dur = exo.duration
        val e = ends[item.mediaId]
        val plain = (e?.handover ?: (dur - FADE)).coerceIn(dur / 2, dur - 1_000)
        // beats matched: the handover on the old song's beat nearest it
        val at = mix(e)?.outBeat?.toLong()?.coerceIn(dur / 2, dur - 1_000) ?: plain
        val pos = exo.currentPosition
        // the old song's last seconds made ready a little ahead, at the very place they'll be needed
        if (pos >= at - 3_000 && pos < at && tailFor != item.mediaId) readyTail(item, at)
        if (tailFor != item.mediaId || scheduled) return
        val ready = tail?.playbackState == Player.STATE_READY
        // started at the handover itself, to the millisecond, from where it waits: never moved at the last moment (a
        // YouTube song moved then went to the network again, and the next song came in over nothing: a gap, 2026-11-01)
        if (ready && pos >= at - 150) {
            scheduled = true
            // started a little ahead: a player just started is heard a moment after it says it's playing
            main.postDelayed({ scheduled = false; if (!fading && exo.currentMediaItem?.mediaId == item.mediaId) go(e, at) }, (at - pos).coerceAtLeast(0))
        } else if (!ready && pos > at + 1_500) {
            // not ready in time: no crossfade this once, the song ends as it would
            android.util.Log.d("ink-crossfade", "${item.mediaId}: its tail not ready in time, ends as it would")
            dropTail(); tailFor = item.mediaId
        }
    }
    private var scheduled = false

    private fun readyTail(item: MediaItem, at: Long) {
        dropTail()
        tail = ExoPlayer.Builder(c).setMediaSourceFactory(sources)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build().apply {
                setMediaItem(item, at)
                volume = levelling()?.level ?: 1f
                prepare()
            }
        tailFor = item.mediaId
    }

    private fun dropTail() { tail?.release(); tail = null; tailFor = null; scheduled = false }

    /** The handover: the old song's tail sounding, the player on into the next song from its first sound, faded across. */
    private fun go(e: Ends?, at: Long) {
        val t = tail?.takeIf { it.playbackState == Player.STATE_READY } ?: return
        fading = true
        t.play()
        // the player goes on into the next song only once the tail sounds (else a moment of nothing between); if it
        // doesn't sound at once, no crossfade this time
        val asked = SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                if (!fading || tail !== t) return
                // heard, not just started: its place moving on past where it began (else the next song came in over
                // a moment of nothing, 2026-11-01)
                // as the first version (Bronson: smoother): over as soon as it plays; waiting till it was heard played
                // the old song twice for a moment (a flam, a bump), and two players can't be lined up closer than that
                if (!t.isPlaying) {
                    if (SystemClock.uptimeMillis() - asked < 600) { main.postDelayed(this, 5); return }
                    android.util.Log.d("ink-crossfade", "its tail didn't sound: the song ends as it would")
                    fading = false; dropTail(); tailFor = exo.currentMediaItem?.mediaId
                    return
                }
                android.util.Log.d("ink-crossfade", "tail sounding after ${SystemClock.uptimeMillis() - asked} ms")
                handOver(t, e)
            }
        })
    }

    private fun handOver(t: ExoPlayer, e: Ends?) {
        val from = exo.currentPosition
        ours = true
        val l = levelling()
        l?.fade = 0f
        val next = exo.nextMediaItemIndex
        val nextId = exo.getMediaItemAt(next).mediaId
        val m = mix(e, ends[nextId])
        // matched: the new one where its beat lines up with the old one's, at the old one's tempo; else from its first sound
        val skip = m?.let { (it.inBeat + (from - it.outBeat) * it.speed).toLong().coerceAtLeast(0) } ?: (ends[nextId]?.firstSound ?: 0L)
        exo.seekTo(next, skip)
        if (m != null) { owns = true; exo.setPlaybackSpeed(m.speed) }
        val tailLevel = t.volume
        val length = if (m != null) (m.outPeriod * 16).toLong().coerceAtMost((e?.lastSound ?: Long.MAX_VALUE) - from).coerceIn(m.outPeriod.toLong() * 8, 14_000)
            else (e?.let { (it.lastSound - from).coerceIn(2_000, FADE * 2) } ?: FADE)
        // the bass handed across: not by Android's equalizer (on Samsung, switched on, it moved the level, and again
        // when let go; 2026-11-01): to come, in ink music's own path
        val bass: Bass? = null
        android.util.Log.d("ink-crossfade", "into $nextId at ${from / 1000.0}s over ${length / 1000.0}s, its start ${skip}ms in" +
            (m?.let { ", beats matched: ${"%.1f".format(60_000 / it.outPeriod)} bpm, the new one at ${"%.3f".format(it.speed)}x" } ?: ", not beat-matched"))
        val began = SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                if (!fading || tail !== t) { bass?.release(); return }
                val f = ((SystemClock.uptimeMillis() - began) / length.toFloat()).coerceIn(0f, 1f)
                // equal power: the new one rising as a sine, the old one falling as a cosine
                levelling()?.fade = kotlin.math.sin(f * Math.PI / 2).toFloat()
                t.volume = tailLevel * kotlin.math.cos(f * Math.PI / 2).toFloat()
                if (m != null) { lock(t, m); bass?.at(f) }
                if (f < 1f) main.postDelayed(this, 30) else { bass?.release(); end(calledOff = false); if (m != null) easeBack() }
            }
        })
    }

    /** The speed the crossfade has set the player to (the player leaves it be: PlayerService's episode speed). */
    @Volatile var owns = false
        private set

    /**
     * The new song held on the old one's beats: where it should be by the old one's place, and where it is; its speed
     * nudged by up to 4% to close the gap (a beat's difference is no difference: the gap is taken within one beat).
     */
    private fun lock(t: ExoPlayer, m: Mix) {
        val expected = m.inBeat + (t.currentPosition - m.outBeat) * m.speed
        var err = exo.currentPosition - expected
        err = ((err % m.inPeriod) + m.inPeriod * 1.5) % m.inPeriod - m.inPeriod / 2
        val want = (m.speed * (1 - (err / 1000.0).coerceIn(-.04, .04))).toFloat()
        if (kotlin.math.abs(exo.playbackParameters.speed - want) > .002f) exo.setPlaybackSpeed(want)
    }

    /** After the blend: the new song back to its own tempo, over four seconds, its pitch kept all the while. */
    private fun easeBack() {
        val from = exo.playbackParameters.speed
        if (kotlin.math.abs(from - 1f) < .002f) { exo.setPlaybackSpeed(1f); owns = false; return }
        val began = SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                if (fading) return
                val f = ((SystemClock.uptimeMillis() - began) / 4_000f).coerceIn(0f, 1f)
                exo.setPlaybackSpeed(from + (1f - from) * f)
                if (f < 1f) main.postDelayed(this, 150) else owns = false
            }
        })
    }

    /** Two songs' beats, matchable? The old one's beat nearest its handover, the new one's first beat, the speed between. */
    private fun mix(e: Ends?, next: Ends? = nextEnds()): Mix? {
        // paused (2026-11-01): two players can't hold beats together; back once the mix is made in the player's own sound
        if (!MATCH) return null
        val a = e?.tail ?: return null
        val b = next?.head ?: return null
        if (a.sure < SURE || b.sure < SURE) return null
        // the new one's tempo to the old one's; a song in half or double time counts (its beats every other one)
        var inPeriod = b.period
        val speed = listOf(1.0, 2.0, .5).map { k -> k to (b.period * k) / a.period }.firstOrNull { kotlin.math.abs(it.second - 1) <= .08 } ?: return null
        inPeriod = b.period * speed.first
        // a DJ's blend: 16 beats before the old one's sound ends (or where its outro falls away, if sooner), on its beat,
        // within the stretch its beat was read from
        val outBeat = a.nearest(kotlin.math.min(e.handover.toDouble(), e.lastSound - 16 * a.period)).coerceAtLeast(a.first)
        val inBeat = b.after(next.firstSound.toDouble())
        return Mix(outBeat, inBeat, speed.second.toFloat(), inPeriod, a.period)
    }

    private fun nextEnds(): Ends? = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { ends[exo.getMediaItemAt(it).mediaId] }

    /**
     * The bass handed across at the middle: the new song's lows held down till then, the old one's let go after
     * (Android's equalizer on each player's sound, its lowest band; nothing left on after the blend).
     */
    private class Bass(old: Int, new: Int) {
        private val out = runCatching { android.media.audiofx.Equalizer(0, old).apply { enabled = true } }.getOrNull()
        private val inn = runCatching { android.media.audiofx.Equalizer(0, new).apply { enabled = true } }.getOrNull()
        private fun low(eq: android.media.audiofx.Equalizer?) = eq?.runCatching { getBand(80_000) }?.getOrNull()
        private val bandOut = low(out); private val bandIn = low(inn)
        private val floor = out?.runCatching { bandLevelRange[0] }?.getOrNull() ?: (-1500).toShort()

        /** At `f` of the blend: the swap over the middle tenth of it. */
        fun at(f: Float) {
            val k = ((f - .45f) / .1f).coerceIn(0f, 1f)   // 0 before the middle, 1 after
            runCatching { bandIn?.let { inn?.setBandLevel(it, (floor * (1 - k)).toInt().toShort()) } }
            runCatching { bandOut?.let { out?.setBandLevel(it, (floor * k).toInt().toShort()) } }
        }

        fun release() { runCatching { out?.release() }; runCatching { inn?.release() } }
    }

    /** The fade over (or called off): the old song's tail let go, the new one at its full level. */
    private fun end(calledOff: Boolean = true) {
        if (calledOff && fading && owns) { exo.setPlaybackSpeed(1f); owns = false }   // called off mid-blend: its own tempo at once
        fading = false
        levelling()?.fade = 1f
        dropTail()
    }

    // ---- where each song's sound ends and begins, measured once (its own decode, apart from the playing) ----

    // ---- the blend in the player's own sound (Blend): the next song cut where its handover is ----

    private val cutSongs = HashMap<String, MediaItem>()   // songs cut short, as they were

    /**
     * The next song cut short where its handover is and its end handed to Blend, while it's still next (a playing song
     * can't be changed). Only a song decoded to its end, 16 bit, with a song after it that isn't an episode, a station,
     * or its own record's next song in order.
     */
    private fun cut() {
        val b = blend ?: return
        if (!on(c) || Exact.on(c)) { cutSongs.keys.toList().forEach(::uncut); return }
        val n = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val item = exo.getMediaItemAt(n)
        val id = item.mediaId
        if (id in cutSongs || id.startsWith("e") || id.startsWith("s")) return
        val e = ends[id] ?: return
        val pcm = e.end as? Pcm ?: return
        val all = pcm.all ?: return
        val f = exo.currentTimeline.getNextWindowIndex(n, exo.repeatMode, exo.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET } ?: return
        val following = exo.getMediaItemAt(f)
        if (following.mediaId.startsWith("e") || following.mediaId.startsWith("s")) return
        val record = item.mediaMetadata.extras?.getString(Levelling.RECORD).orEmpty()
        if (record.isNotEmpty() && record == following.mediaMetadata.extras?.getString(Levelling.RECORD) && f == n + 1) return
        val at = e.handover
        val fade = (e.lastSound - at).coerceIn(2_000, 12_000)
        if (pcm.start > at || pcm.start + all.size / pcm.channels * 1000L / pcm.rate < at + fade) return
        b.tails[id] = Blend.Tail(pcm.start, pcm.rate, pcm.channels, all, at, fade)
        cutSongs[id] = item
        swap(n, item.buildUpon().setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(at).build()).build())
        android.util.Log.d("ink-crossfade", "$id cut at ${at / 1000.0}s of ${e.duration / 1000.0}s, its end to be mixed in over ${fade / 1000.0}s")
    }

    /** A song cut short whole again (not the one playing: that one can't be changed), its end let go. */
    private fun uncut(id: String) {
        val was = cutSongs[id] ?: return
        if (exo.currentMediaItem?.mediaId == id) return
        cutSongs.remove(id)
        blend?.tails?.remove(id)
        val i = (0 until exo.mediaItemCount).firstOrNull { exo.getMediaItemAt(it).mediaId == id } ?: return
        swap(i, was)
    }

    /**
     * A queue's song put back as another: taken out and put in again, so the player makes it anew (replaced, a song at
     * the same address is only updated, and its cut never took: 2026-11-01); with shuffle on it stays where it was in
     * the order, next if it was next (else it'd go last).
     */
    private fun swap(i: Int, item: MediaItem) {
        if (i == exo.nextMediaItemIndex) inPlace()
        exo.removeMediaItem(i)
        exo.addMediaItem(i.coerceAtMost(exo.mediaItemCount), item)
    }

    /** This song's end and the next one's start measured, if they aren't yet. */
    private fun measure() {
        if (!on(c)) return
        val items = listOfNotNull(exo.currentMediaItem, exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { exo.getMediaItemAt(it) })
        for (item in items) {
            val id = item.mediaId
            if (id.startsWith("e") || id.startsWith("s") || !asked.add(id)) continue
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: continue
            work.execute {
                runCatching { ends(uri) }.onFailure { android.util.Log.d("ink-crossfade", "$id: $it") }.getOrNull()?.let { ends[id] = it; main.post { cut() } }
            }
        }
    }

    /** Where to read a song from, as the player would (kept on the phone first; YouTube's stream found). */
    private fun source(uri: android.net.Uri): Any? = when {
        uri.scheme == "ytm" -> Offline.file(c, "y" + uri.host) ?: YouTubeMusic.stream("y" + uri.host, false)?.first
        uri.path?.endsWith("/rest/stream") == true -> uri.getQueryParameter("id")?.let { Offline.file(c, "n$it") } ?: uri.toString()
        uri.scheme == "content" -> uri
        else -> uri.toString()
    }

    private fun ends(uri: android.net.Uri): Ends? {
        val src = source(uri) ?: return null
        fun open(): MediaExtractor? = MediaExtractor().runCatching {
            when (src) { is android.net.Uri -> setDataSource(c, src, null); is File -> setDataSource(src.path); else -> setDataSource(src.toString(), emptyMap()) }
            this
        }.getOrNull()
        val x = open() ?: return null
        val track = (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: run { x.release(); return null }
        val format = x.getTrackFormat(track)
        val dur = format.getLong(MediaFormat.KEY_DURATION) / 1000
        x.release()
        if (dur < FADE * 3) return null
        // the last 25 s: where the sound ends, where an outro falls away, its beat; the first 20 s: its sound, its beat
        val endPcm = decode(open() ?: return null, track, (dur - 25_000).coerceAtLeast(0), dur, keep = true) ?: return null
        val deep = format.containsKey("bits-per-sample") && format.getInteger("bits-per-sample") > 16
        val headPcm = decode(open() ?: return null, track, 0, 20_000)
        val end = levels(endPcm)
        val window = 50L
        val loud = end.maxOrNull() ?: return null
        val lastIdx = end.indexOfLast { it > -50f }.takeIf { it >= 0 } ?: return null
        val lastSound = endPcm.start + (lastIdx + 1) * window
        // an outro falling away: the first moment from which it stays 15 dB under the end's loudest
        val quietIdx = (0..lastIdx).firstOrNull { i -> (i..lastIdx).all { end[it] < loud - 15f } }
        val quiet = quietIdx?.let { endPcm.start + it * window }
        val handover = listOfNotNull(lastSound - FADE, quiet?.minus(1_000)).minOrNull()!!.coerceAtLeast(dur - 25_000)
        val firstSound = headPcm?.let { levels(it) }?.indexOfFirst { it > -50f }?.takeIf { it > 0 }?.let { it * window }?.takeIf { it >= 400 } ?: 0L
        val tail = beats(endPcm); val head = headPcm?.let { beats(it) }
        android.util.Log.d("ink-crossfade", "${uri.lastPathSegment}: ${dur / 1000}s, sound to ${lastSound / 1000.0}s, quiet from ${quiet?.div(1000.0)}, hand over at ${handover / 1000.0}s, starts ${firstSound}ms in; " +
            "beat at its end ${tail?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}, at its start ${head?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}")
        return Ends(lastSound, handover, firstSound, tail, head, endPcm.takeIf { !deep }, dur)
    }

    /** A stretch of a song decoded: where it begins (ms), its samples made mono, their rate. */
    private class Pcm(val start: Long, val mono: FloatArray, val rate: Int, val channels: Int = 1, val all: FloatArray? = null)

    /** The level (dBFS) of each 50 ms. */
    private fun levels(p: Pcm): List<Float> {
        val per = p.rate / 20
        return (0 until p.mono.size / per).map { w -> var sum = 0.0; for (k in w * per until (w + 1) * per) sum += p.mono[k] * p.mono[k]; db(sum, per) }
    }

    /**
     * The beat: an onset curve from the low end (a kick's rise; 10 ms steps), its period the lag it most repeats at
     * (with its double, so a half-tempo doesn't win), 60 to 180 bpm, refined between steps; the grid laid where the
     * onsets fall most. How sure: the period's repeat over the average repeat.
     */
    private fun beats(p: Pcm): Beats? {
        val hop = p.rate / 100
        val n = p.mono.size / hop
        if (n < 600) return null
        // the low end: a one-pole low-pass at 150 Hz
        val a = (1 - Math.exp(-2 * Math.PI * 150 / p.rate)).toFloat()
        var y = 0f
        val energy = DoubleArray(n)
        for (k in 0 until n) { var e = 0.0; for (i in k * hop until (k + 1) * hop) { y += a * (p.mono[i] - y); e += y * y }; energy[k] = e / hop }
        val onset = DoubleArray(n)
        for (k in 1 until n) onset[k] = kotlin.math.max(0.0, Math.log(energy[k] + 1e-10) - Math.log(energy[k - 1] + 1e-10))
        // less what's always there: the onset over its local average
        val avg = DoubleArray(n); var run = 0.0
        for (k in 0 until n) { run += onset[k]; if (k >= 50) run -= onset[k - 50]; avg[k] = run / kotlin.math.min(k + 1, 50) }
        for (k in 0 until n) onset[k] = kotlin.math.max(0.0, onset[k] - avg[k])
        fun ac(lag: Int): Double { var s = 0.0; for (k in 0 until n - lag) s += onset[k] * onset[k + lag]; return s / (n - lag) }
        // 80 to 160 bpm: below it a song's half-time won (most read 64 to 66; 2026-11-01), double or half is matched anyway
        val lags = (37..75)
        val acs = lags.associateWith { ac(it) }
        fun score(l: Int) = acs.getValue(l) + .5 * (if (2 * l <= n / 2) ac(2 * l) else 0.0)
        val best = lags.maxByOrNull(::score) ?: return null
        val mean = acs.values.average().takeIf { it > 0 } ?: return null
        val sure = acs.getValue(best) / mean
        // between steps: a parabola through the peak and its neighbours
        val l0 = acs[best - 1] ?: acs.getValue(best); val l1 = acs.getValue(best); val l2 = acs[best + 1] ?: acs.getValue(best)
        val shift = (l0 - l2) / (2 * (l0 - 2 * l1 + l2)).let { if (it == 0.0) 1e9 else it }
        val lag = best + shift.coerceIn(-.5, .5)
        // the grid: where onsets fall most, a beat apart
        var bestO = 0; var bestS = -1.0
        for (o in 0 until lag.toInt() + 1) {
            var s = 0.0; var k = o.toDouble()
            while (k < n) { s += onset[k.toInt()]; k += lag }
            if (s > bestS) { bestS = s; bestO = o }
        }
        return Beats(lag * 10, p.start + bestO * 10.0, sure)
    }

    /** A stretch decoded, its channels averaged to one (blocking; 8 s at most). */
    private fun decode(x: MediaExtractor, track: Int, fromMs: Long, toMs: Long, keep: Boolean = false): Pcm? {
        try {
            x.selectTrack(track)
            val format = x.getTrackFormat(track)
            x.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = FloatArray(((toMs - fromMs + 2_000) * rate / 1000).toInt())
            // every channel too, when kept for the blend (the old song's end mixed back in: Blend)
            val all = if (keep) FloatArray(out.size * channels) else null
            var allSize = 0
            var size = 0; var ch = 0; var acc = 0f
            var first = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.uptimeMillis() + 8_000
            loop@ while (SystemClock.uptimeMillis() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = x.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || x.sampleTime / 1000 > toMs) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, x.sampleTime, 0); x.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val at = info.presentationTimeUs / 1000
                    val b: ByteBuffer = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val float = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
                    if (at >= fromMs) {
                        if (first < 0) first = at
                        fun put(v: Float) {
                            if (all != null && allSize < all.size) all[allSize++] = v
                            acc += v; if (++ch == channels) { if (size < out.size) out[size++] = acc / channels; acc = 0f; ch = 0 }
                        }
                        if (float) { val f = b.asFloatBuffer(); while (f.hasRemaining()) put(f.get()) }
                        else { val s = b.asShortBuffer(); while (s.hasRemaining()) put(s.get() / 32768f) }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
            codec.stop(); codec.release()
            return if (first < 0 || size == 0) null else Pcm(first, out.copyOf(size), rate, channels, all?.copyOf(allSize))
        } catch (e: Exception) {
            return null
        } finally { x.release() }
    }

    private fun db(sum: Double, n: Int) = (20 * log10(sqrt(sum / n).coerceAtLeast(1e-9))).toFloat()
}
