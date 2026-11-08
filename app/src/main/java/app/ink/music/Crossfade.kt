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
 * Automix's first half, the smart crossfade (settings, sound; 2026-11-01, after BitChord's Automix): a song
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
 * Automix's second half, the beats matched: each song's beat found in the same decode (an onset
 * curve from its low end, the kick; its period by autocorrelation, 60 to 180 bpm; its grid laid on), and where both
 * are sure and their tempos within 8% (or double, or half), the handover lands on one of the old song's beats, the
 * new one comes in on its own beat at the old one's tempo (stretched, its pitch kept), held there by a loop that
 * nudges it back onto the old one's beats, the bass handed across at the middle so two kicks never fight; after,
 * the new one eases back to its own tempo. Not sure of either beat: the plain crossfade.
 */
class Crossfade(
    private val c: Context, private val exo: ExoPlayer, private val sources: MediaSource.Factory,
    private val levelling: () -> Levelling?, private val casting: () -> Boolean,
    private val blend: Blend? = null, private val inPlace: () -> Unit = {}, private val reorder: (IntArray) -> Unit = {},
) {
    /** A song's beat over a stretch: its period (ms), one beat's time (ms into the song), and how sure (peak over mean). */
    data class Beats(val period: Double, val first: Double, val sure: Double, val downbeat: Double = first, val bars: List<Beat.Bar> = emptyList()) {
        val bpm get() = 60_000 / period
        fun nearest(t: Double) = first + Math.round((t - first) / period) * period
        fun after(t: Double) = first + Math.ceil((t - first) / period) * period
        /** The bar start (bar one) nearest `t`. */
        fun barNear(t: Double) = downbeat + Math.round((t - downbeat) / (4 * period)) * 4 * period
    }

    /** Where a song's sound ends (ms), where its handover begins, where its sound begins; its beat at its end and its start. */
    data class Ends(
        val lastSound: Long, val handover: Long, val firstSound: Long, val tail: Beats? = null, val head: Beats? = null,
        internal val end: Any? = null,   // its end decoded, every channel (a Pcm; none for a file deeper than 16 bit)
        val duration: Long = 0,
        val tailKey: Key.Found? = null, val headKey: Key.Found? = null,   // its key at its end and at its start
        val headLv: FloatArray = FloatArray(0),   // its start's level, dBFS each 50 ms from 0 (before its gain)
        val tailLv: FloatArray = FloatArray(0), val tailFrom: Long = 0,   // its end's, each 50 ms from tailFrom
        val fullAt: Long = 0,       // when its intro's over: its level first near its usual (ms; 0 a song that starts full)
        val fading: Long? = null,   // where a fade-out in its own recording begins
        val peak: Double = 0.0,     // its loudest sample heard (of its start and end decoded), 0..1
        val lufs: Double? = null,   // its loudness, measured from its start and end (gated as BS.1770 gates; no K-weighting)
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

    // ---- what's said to the screens (Mix): this song's mix point, the blend in hand ----
    /** Told when what's said changes (a blend begins or ends, a song's cut). */
    var onChange: () -> Unit = {}
    private var lastId: String? = null
    private var mixing: app.ink.music.Mix? = null

    /** This song's mix point and the blend in hand, for the player page. */
    fun said(): app.ink.music.Mix? {
        val id = exo.currentMediaItem?.mediaId
        val t = id?.let { blend?.tails?.get(it) }
        val m = mixing?.takeIf { SystemClock.elapsedRealtime() < it.start + it.length }
        // the song's own beat and key, for the beat counter (automix, sure of the beat)
        val e = id?.let { ends[it] }?.takeIf { on(c) && mode(c) == AUTOMIX }
        val head = e?.head?.takeIf { it.sure >= SURE }; val tail = e?.tail?.takeIf { it.sure >= SURE }
        if (t == null && m == null && head == null && tail == null) return null
        return (m ?: app.ink.music.Mix()).copy(cutId = if (t != null) id!! else "", cutAt = t?.cutMs ?: 0, full = id?.let { ends[it]?.duration } ?: 0,
            auto = t?.stretched != null, songId = id.orEmpty(),
            headDown = head?.downbeat ?: 0.0, headPeriod = head?.period ?: 0.0, tailDown = tail?.downbeat ?: 0.0, tailPeriod = tail?.period ?: 0.0,
            tailFrom = (e?.end as? Pcm)?.start ?: (e?.lastSound?.minus(25_000) ?: 0), headKey = e?.headKey?.camelot.orEmpty(), tailKey = e?.tailKey?.camelot.orEmpty())
    }

    /** A song come in by a blend (the one before it cut for it): the blend said, and cleared once it's over. */
    private fun blended(from: String, into: String) {
        val t = blend?.tails?.get(from) ?: return
        val st = t.stretched?.takeIf { it.into == into }
        val head = ends[into]?.head
        tell("${titleOf(from)} into ${titleOf(into)}: mixed" + if (st != null) ", beats matched" else "")
        mixing = app.ink.music.Mix(from = from, start = SystemClock.elapsedRealtime(), length = t.fadeMs,
            bpmFrom = if (st != null) ends[from]?.tail?.bpm ?: 0.0 else 0.0, bpmTo = if (st != null) head?.bpm ?: 0.0 else 0.0,
            beatFirst = if (st != null) head?.first ?: 0.0 else 0.0, beatPeriod = if (st != null) head?.period ?: 0.0 else 0.0)
        onChange()
        main.postDelayed({ mixing = null; onChange() }, t.fadeMs + 100)
    }

    companion object {
        const val FADE = 6_000L
        /** How long play waits for a never-measured song's end, begun on (ms). */
        const val FIRST_WAIT = 1_200L
        /** How far ahead of the handover the tail's started (it's heard a moment after it says it plays). */
        const val LEAD = 120L
        /** Beats matched: off till the mix is made in the player's own sound (sample by sample). */
        const val MATCH = true
        /** The old two-player crossfade, for a song the blend couldn't be made for: off (a skip's sound, a hitch). */
        const val TWO_PLAYER = false
        const val OFF = "off"; const val CROSSFADE = "crossfade"; const val AUTOMIX = "automix"
        /** Off, the crossfade, or automix (the crossfade with beats matched and the bass handed across). */
        fun mode(c: Context): String = runCatching { File(c.filesDir, "crossfade").readText().trim() }.getOrNull()
            ?.let { if (it == AUTOMIX) AUTOMIX else CROSSFADE } ?: OFF
        fun set(c: Context, mode: String) { runCatching { File(c.filesDir, "crossfade").let { if (mode == OFF) it.delete() else it.writeText(mode) } } }
        /** How sure a beat must be to be matched (Beat's z-score). */
        const val SURE = Beat.SURE
        fun on(c: Context) = File(c.filesDir, "crossfade").exists()
        const val SAID = "crossfade-said"
        /** The last song mixed into the next, or why the last one wasn't (for settings). */
        fun said(c: Context): String? = runCatching { File(c.filesDir, SAID).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

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
                // come in by a blend: the old song had been cut for it, and it ended there on its own
                val was = lastId; lastId = mediaItem?.mediaId
                mixing = null
                if (was != null && mediaItem != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && was in cutSongs) blended(was, mediaItem.mediaId)
                else onChange()
                measure()
                // songs cut short and played (or passed): whole again, so going back plays them as they are
                val now = exo.currentMediaItem?.mediaId
                val next = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { exo.getMediaItemAt(it).mediaId }
                cutSongs.keys.filter { it != now && it != next }.forEach(::uncut)
                letGo(now, next)
                cut()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) end() }
            // the queue changed under the song playing (songs added after it, moved, shuffle turned): another song may be
            // next now, planned anew (2026-11-08: a genre's shelf added behind its first song with shuffle on, and the
            // song that came next had been measured but never cut; only a song change planned)
            // (never its own swaps: a cut swaps the song in, with shuffle on that moved the order, and planning anew
            // cut another, and another, every few seconds, 2026-11-08: no song kept its cut)
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !swapping) replanSoon()
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { replanSoon() }
        })
    }

    // once a burst of changes has settled (a swap is a removal and an addition; a shelf comes in pieces)
    private val replan = Runnable {
        if (!on(c)) return@Runnable
        val now = exo.currentMediaItem?.mediaId
        val next = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { exo.getMediaItemAt(it).mediaId }
        letGo(now, next)
        measure()
        cutSongs.keys.filter { it != now && it != next }.forEach(::uncut)
        cut()
    }

    /**
     * Each song's end decoded is ~10 MB: kept only for this song, the next and the one after (measured again if they
     * come round again). Let go at a song change and at a queue change too (2026-11-08: only the song change did, a
     * shuffled shelf measured song after song, and the player ran out of memory measuring an 11-minute one).
     */
    private fun letGo(now: String?, next: String?) {
        val keep = setOfNotNull(now, next, exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
            ?.let { exo.currentTimeline.getNextWindowIndex(it, exo.repeatMode, exo.shuffleModeEnabled) }?.takeIf { it != C.INDEX_UNSET }
            ?.let { exo.getMediaItemAt(it).mediaId })
        ends.keys.filter { it !in keep }.forEach { ends.remove(it); asked.remove(it) }
    }
    private fun replanSoon() { main.removeCallbacks(replan); main.postDelayed(replan, 300) }

    fun release() { main.removeCallbacks(tick); end(); work.shutdown() }

    /** This song and the next: may one go into the other? */
    private fun allowed(): Boolean {
        if (!on(c) || Exact.inUse(c) || casting() || exo.repeatMode == Player.REPEAT_MODE_ONE) return false
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
        // the two-player way no more (it sounded like a skip; 2026-11-01): a song not cut for a blend ends as it
        // would; only the blend in the player's own sound (Blend)
        if (!TWO_PLAYER) return
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
                // as the first version (smoother): over as soon as it plays; waiting till it was heard played
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
        // never the two-player way: two players can't hold beats together (2026-11-01); beats are matched in Blend
        if (!MATCH || true) return null
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

    // ---- the song you press play on (2026-11-07) ----
    // Only the next song is cut ahead (the one playing can't be, without a stop), so the song begun on never blended:
    // "crossfading doesn't work at all", tried on two songs. Each song's handover is kept once measured
    // (files/crossfade-cuts.json); a song heard before is cut where it was as it's put on, its end decoded while it plays.
    private val cutsFile = File(c.filesDir, "crossfade-cuts.json")
    private val cuts: MutableMap<String, LongArray> by lazy {
        java.util.Collections.synchronizedMap(LinkedHashMap<String, LongArray>().apply {
            runCatching { val o = org.json.JSONObject(cutsFile.readText()); o.keys().forEach { k -> val a = o.getJSONArray(k); put(k, longArrayOf(a.getLong(0), a.getLong(1), a.getLong(2))) } }
        })
    }
    /** Songs cut as they were put on: where, and over how long their end is mixed in (the tail made once measured). */
    private val precut = ConcurrentHashMap<String, LongArray>()

    private fun keepCut(id: String, e: Ends) {
        if (e.duration <= 0) return
        cuts[id] = longArrayOf(e.handover, e.lastSound, e.duration)
        // the newest 3000
        val o = org.json.JSONObject()
        synchronized(cuts) { cuts.entries.toList().takeLast(3000).forEach { (k, v) -> o.put(k, org.json.JSONArray().put(v[0]).put(v[1]).put(v[2])) } }
        runCatching { Keep.write(cutsFile, o.toString()) }
    }

    /**
     * A new queue (the player's own thread, as it's set): the song begun on cut at its kept handover, if it's been
     * measured before, it goes into another song (not a record in order) and it's begun before its handover.
     */
    fun precut(items: List<MediaItem>, start: Int, at: Long): List<MediaItem> {
        if (!on(c) || Exact.inUse(c) || casting()) return items
        val item = items.getOrNull(start) ?: return items
        val next = items.getOrNull(start + 1) ?: return items
        val id = item.mediaId
        if (listOf(id, next.mediaId).any { it.startsWith("e") || it.startsWith("s") }) return items
        val record = item.mediaMetadata.extras?.getString(Levelling.RECORD).orEmpty()
        if (record.isNotEmpty() && record == next.mediaMetadata.extras?.getString(Levelling.RECORD)) return items
        val k = cuts[id] ?: return items
        val handover = k[0]; val fade = (k[1] - handover).coerceIn(2_000, 12_000)
        if (at >= handover - 5_000) return items
        precut[id] = longArrayOf(handover, fade)
        // its end already decoded this run: handed over at once (else once it's measured)
        main.post { cutSongs[id] = item; tailOfPrecut(id) }
        android.util.Log.d("ink-crossfade", "$id begun on: cut at ${handover / 1000.0}s as it's put on (kept from before), its end to be mixed in over ${fade / 1000.0}s")
        return items.toMutableList().also { it[start] = item.buildUpon().setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(handover).build()).build() }
    }

    /**
     * A song begun on that's never been measured (it ended as it would: "the first song never mixes", the likeliest
     * try of it, 2026-11-08): its end measured before it starts, a moment at most ([FIRST_WAIT]), so [precut] can cut
     * it as any other. Only a song on the phone (a file, one kept offline): a stream's end takes seconds to read, and
     * play would wait. Late, it's kept all the same (measured as it plays, the next time it's begun on).
     */
    fun measureFirst(items: List<MediaItem>, start: Int) {
        if (!on(c) || Exact.inUse(c) || casting()) return
        val item = items.getOrNull(start) ?: return
        val next = items.getOrNull(start + 1) ?: return
        // a record in order runs into itself as made: nothing to wait for
        val record = item.mediaMetadata.extras?.getString(Levelling.RECORD).orEmpty()
        if (record.isNotEmpty() && record == next.mediaMetadata.extras?.getString(Levelling.RECORD)) return
        val id = item.mediaId
        if (cuts.containsKey(id) || id.startsWith("e") || id.startsWith("s")) return
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: return
        val onPhone = uri.scheme == "content" || uri.scheme == "file" ||
            (uri.scheme == "ytm" && Offline.file(c, "y" + uri.host) != null) ||
            (uri.path?.endsWith("/rest/stream") == true && uri.getQueryParameter("id")?.let { Offline.file(c, "n$it") } != null)
        if (!onPhone) return
        titles[id] = item.mediaMetadata.title?.toString().orEmpty()
        val t0 = SystemClock.uptimeMillis()
        val f = java.util.concurrent.CompletableFuture.supplyAsync { runCatching { ends(uri, id) }.getOrNull() }
        val e = runCatching { f.get(FIRST_WAIT, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        if (e != null) {
            ends[id] = e; asked += id; keepCut(id, e)
            android.util.Log.d("ink-crossfade", "$id: measured before it began, in ${SystemClock.uptimeMillis() - t0} ms")
        } else {
            android.util.Log.d("ink-crossfade", "$id: not measured in ${FIRST_WAIT} ms, begins uncut")
            f.thenAccept { late -> if (late != null) keepCut(id, late) }
        }
    }

    /** A song cut as it was put on, its end now decoded: handed to the blend (or said, if it couldn't be). */
    private fun tailOfPrecut(id: String) {
        val p = precut[id] ?: return
        val b = blend ?: return
        if (b.tails.containsKey(id)) return
        val e = ends[id] ?: return   // not measured yet: handed over once it is
        val pcm = e.end as? Pcm
        val all = pcm?.all
        if (pcm == null || all == null) return why(id, "begun on, its end couldn't be kept: it ends at its cut")
        val pcmEnd = pcm.start + all.size / pcm.channels * 1000L / pcm.rate
        if (pcm.start > p[0] || pcmEnd < p[0] + p[1]) return why(id, "begun on, its end decoded doesn't hold the cut")
        b.tails[id] = Blend.Tail(pcm.start, pcm.rate, pcm.channels, all, p[0], p[1], filter = mode(c) == AUTOMIX)
        android.util.Log.d("ink-crossfade", "$id: its end ready to mix in at ${p[0] / 1000.0}s")
    }

    /**
     * The next song cut short where its handover is and its end handed to Blend, while it's still next (a playing song
     * can't be changed). Only a song decoded to its end, 16 bit, with a song after it that isn't an episode, a station,
     * or its own record's next song in order.
     */
    private fun cut() {
        val b = blend ?: return
        if (!on(c) || Exact.inUse(c)) { if (on(c)) why("-", "exact to a usb dac is on, and the dac is plugged in"); cutSongs.keys.toList().forEach(::uncut); return }
        val n = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        var item = exo.getMediaItemAt(n)
        val id = item.mediaId
        if (id.startsWith("e") || id.startsWith("s")) return
        // cut already: done, unless it was cut plainly while the one after it was still being measured, and that one is
        // measured now (its beats to match): whole again, and planned anew
        if (id in cutSongs) {
            val after = exo.currentTimeline.getNextWindowIndex(n, exo.repeatMode, exo.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET }
                ?.let { exo.getMediaItemAt(it).mediaId }
            if (id !in provisional || after == null || !ends.containsKey(after) || id in planning) return
            provisional -= id
            val whole = cutSongs[id] ?: return
            uncut(id)
            item = whole
        }
        val e = ends[id] ?: return
        val pcm = e.end as? Pcm ?: return why(id, "its end wasn't kept (deeper than 16 bit)")
        val all = pcm.all ?: return why(id, "its end has no samples kept")
        val f = exo.currentTimeline.getNextWindowIndex(n, exo.repeatMode, exo.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET } ?: return why(id, "nothing after it")
        val following = exo.getMediaItemAt(f)
        if (following.mediaId.startsWith("e") || following.mediaId.startsWith("s")) return why(id, "a station or an episode after it")
        val record = item.mediaMetadata.extras?.getString(Levelling.RECORD).orEmpty()
        if (record.isNotEmpty() && record == following.mediaMetadata.extras?.getString(Levelling.RECORD) && f == n + 1) return why(id, "a record in order")
        // the beats matched, where both are sure and their tempos near: worked out (and the end stretched) apart
        val pcmEnd = pcm.start + all.size / pcm.channels * 1000L / pcm.rate
        if (id in planning) return
        // the one after it not measured yet (asked, on its way): cut plainly now, planned again once it is (2026-11-08: a
        // song waited for the one after it, a few seconds, and one skipped to meanwhile had no cut at all; Brave waited
        // on Cloudy Day, which ran out of memory twice, till it played)
        val waiting = !ends.containsKey(following.mediaId) && following.mediaId in asked && following.mediaId !in unread
        planning += id
        work.execute {
            // the one after it as measured by now (measured on this same thread, so done if it was asked first)
            val m = (if (waiting) null else runCatching { matched(e, ends[following.mediaId], pcm, all, pcmEnd) }.onFailure { android.util.Log.d("ink-crossfade", "$id: beats: $it") }.getOrNull())
                // a beats-matched plan running past the decoded end: the plain one in its place, not none (2026-11-08)
                ?.takeIf { pcm.start <= it.cut && pcmEnd >= it.cut + it.fade }
            main.post {
                planning -= id
                if (exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { exo.getMediaItemAt(it).mediaId } != id || id in cutSongs) return@post
                val at = m?.cut ?: e.handover
                // not beats matched: the filter fade (Blend), and a short one when their keys clash (both sure)
                val clash = Key.clash(e.tailKey, ends[following.mediaId]?.headKey)
                val fade = m?.fade ?: (e.lastSound - at).coerceIn(2_000, 12_000).let { if (clash) it.coerceAtMost(5_000) else it }
                if (pcm.start > at || pcmEnd < at + fade) return@post why(id, "its end decoded (${pcm.start / 1000.0}s to ${pcmEnd / 1000.0}s) doesn't hold the cut at ${at / 1000.0}s and its ${fade / 1000.0}s fade")
                val hold = m?.hold ?: ((ends[following.mediaId]?.fullAt ?: 0L).toDouble() / fade).coerceIn(0.0, .7)
                val ride = ride(e, ends[following.mediaId], id, following.mediaId, at, fade)
                b.tails[id] = Blend.Tail(pcm.start, pcm.rate, pcm.channels, all, at, fade,
                    m?.let { Blend.Stretched(following.mediaId, it.stretched, it.from, it.speed, it.period) }, filter = (m == null || m.clash) && mode(c) == AUTOMIX,
                    hold = hold, rideDb = ride)
                cutSongs[id] = item
                // cut for now: and if the one after it was measured meanwhile (the planning waited behind its measuring on
                // the same thread, so it nearly always is), planned again at once (2026-11-08: none ever was, no beats matched)
                if (waiting) { provisional += id; if (ends.containsKey(following.mediaId)) main.post { cut() } }
                swap(n, item.buildUpon().setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(at).build()).build())
                android.util.Log.d("ink-crossfade", "$id cut at ${at / 1000.0}s of ${e.duration / 1000.0}s, its end to be mixed in over ${fade / 1000.0}s" +
                    (if (waiting) " (for now: the one after it still being measured)" else "") +
                    (if (hold > .05) ", the new one's intro quiet: the old one held over ${"%.0f".format(hold * 100)}%" else "") +
                    (if (ride < -.5) ", the new one ridden in ${"%.1f".format(ride)} dB" else "") +
                    (m?.let { ", beats matched into ${following.mediaId}: its end at ${"%.3f".format(it.speed)}x (${"%.1f".format(60_000 / it.period)} bpm), the bass handed across${if (it.clash) ", the old one filtered (keys clash)" else ""}" }
                        ?: if (mode(c) == AUTOMIX) ", the filter fade${if (clash) " (keys clash: short)" else ""}" else ""))
            }
        }
    }
    private val planning = HashSet<String>()
    private val tries = ConcurrentHashMap<String, Int>()
    private val provisional = HashSet<String>()   // cut plainly while the song after it was being measured
    private val unread = ConcurrentHashMap.newKeySet<String>()   // songs whose end couldn't be read (for now)

    /** Why a song won't blend into the next, said once a song (ink-crossfade): what a listener's log can tell us. */
    private val said = ConcurrentHashMap.newKeySet<String>()   // told from the measuring thread too
    private fun why(id: String, reason: String) {
        if (!said.add("$id $reason")) return
        android.util.Log.d("ink-crossfade", "$id: no blend: $reason")
        tell("${titleOf(id)}: not mixed, $reason")
    }

    // what settings says under transitions (2026-11-08: "not working on any of my phones", and nothing to tell why):
    // the last song mixed, or the last reason one wasn't, in files/crossfade-said (the screens read it)
    private val titles = ConcurrentHashMap<String, String>()
    private fun titleOf(id: String) = titles[id]?.takeIf { it.isNotBlank() } ?: "a song"
    private fun tell(line: String) { runCatching { Keep.write(File(c.filesDir, SAID), line.lowercase()) } }

    /** A beat-matched blend's plan: where to cut, over how long, the old song's end stretched to the new one's tempo. */
    private class Matched(val cut: Long, val fade: Long, val stretched: FloatArray, val from: Double, val speed: Double, val period: Double, val clash: Boolean = false, val hold: Double = 0.0)

    /**
     * The old song's end conformed to the new one, which comes in untouched: its end stretched (Media3's Sonic, its
     * pitch kept) to the new one's tempo, and cut where, so stretched, its beats fall on the new one's from its start
     * (the new one's first beat after its start, the old one's a beat on from 16 of the new one's before its sound ends).
     * Over up to 16 of the new one's beats, as long as the old one's sound lasts. Not sure of either beat, or tempos
     * more than 8% apart (double and half counting): none, the plain blend.
     */
    private fun matched(e: Ends, next: Ends?, pcm: Pcm, all: FloatArray, pcmEnd: Long): Matched? {
        if (!MATCH || mode(c) != AUTOMIX) return null
        val a = e.tail ?: return null
        val b = next?.head ?: return null
        // the old one's beat in the new one's terms (its double or half if that's nearer)
        val mult = listOf(1.0, 2.0, .5).minByOrNull { kotlin.math.abs(a.period * it / b.period - 1) }!!
        val pa = a.period * mult
        val speed = pa / b.period   // the old one's end played this much faster (or slower) to keep the new one's tempo
        if (kotlin.math.abs(speed - 1) > .08) return null
        // sure of both beats; where the two tempos agree within 2%, a little less sure will do (148.7 into 150 was a
        // crossfade at 3.4, 2026-11-08: the tempos agreeing is evidence too)
        val needed = if (kotlin.math.abs(speed - 1) <= .02) Tempos.LOOSE else SURE
        if (a.sure < needed || b.sure < needed) return null
        // in on the new one's bar one (2026-11-08, after FireTheDJ: a handover on any beat could fall mid-phrase): its
        // first bar start once its sound begins
        val bar = 4 * b.period
        var b0 = b.downbeat
        while (b0 < next.firstSound - 60) b0 += bar
        while (b0 - bar >= next.firstSound - 60) b0 -= bar
        // out on one of the old one's bar starts: where its outro begins (a bar a third quieter than the four before it)
        // in its last eight bars or so, else the bar four from where its sound ends
        val oldBar = 4 * a.period
        val latest = e.lastSound - 8 * pa
        val outro = a.bars.withIndex().filter { (i, br) ->
            i >= 4 && br.at in (e.lastSound - 36 * pa)..latest && br.loud < .67f * (i - 4 until i).map { a.bars[it].loud }.average()
        }.lastOrNull()?.value?.at
        val a0 = (outro ?: a.barNear(e.lastSound - 16 * pa).let { if (it > latest) it - oldBar else it }).let { o ->
            // fading out on its own: out on the bar before the fade begins, so the new one comes in over the music
            val f = e.fading?.toDouble() ?: return@let o
            if (o <= f - 500) o else a.barNear(f - 500).let { if (it > f - 500) it - oldBar else it }.coerceAtLeast(e.lastSound - 25_000.0)
        }
        val cut = (a0 - speed * b0).toLong()   // so that a0 sounds as b0 does
        // up to four bars over each other (as long as the old one's sound lasts); keys clashing (both sure), the same
        // four, the old one sinking under the filter fade's low-pass (cut to two bars it rushed: the bass swapped in a
        // third of a second, heard as a hitch, Obsidian into Think of You, 2026-11-08)
        val clash = Key.clash(e.tailKey, next.headKey)
        val fade = kotlin.math.min(16 * b.period, (e.lastSound - cut) / speed).toLong().coerceIn(4_000, 14_000)
        android.util.Log.d("ink-crossfade", "plan: out at ${"%.2f".format(a0 / 1000)}s (${if (outro != null) "its outro" else "a bar start"}), " +
            "in on bar one at ${"%.2f".format(b0 / 1000)}s, keys ${e.tailKey?.camelot} into ${next.headKey?.camelot}${if (clash) ", clashing: the old one filtered" else ""}")
        if (cut < pcm.start + 500 || cut < a.first) return null
        // its end stretched, from a little before the cut to past the fade's end
        val from = (cut - 300).coerceAtLeast(pcm.start).toDouble()
        val to = kotlin.math.min(pcmEnd.toDouble(), cut + fade * speed + 1_000)
        val i0 = ((from - pcm.start) * pcm.rate / 1000).toInt() * pcm.channels
        val i1 = ((to - pcm.start) * pcm.rate / 1000).toInt().coerceAtMost(all.size / pcm.channels) * pcm.channels
        val stretched = stretch(all.copyOfRange(i0, i1), pcm.channels, pcm.rate, speed) ?: return null
        // the new one's intro quiet past where it comes in: the old one held up over it (a DJ rides the old track till
        // the new one arrives; faded on the usual curve it sagged, then swelled: "got louder", 2026-11-08)
        val hold = ((next.fullAt - b0) / fade).coerceIn(0.0, .7)
        return Matched(cut, fade, stretched, from, speed, b.period, clash, hold)
    }

    /** Samples played `speed` times as fast, their pitch kept (Media3's Sonic, offline). */
    private fun stretch(pcm: FloatArray, channels: Int, rate: Int, speed: Double): FloatArray? {
        // within half a percent: as it is (the drift over a blend a few ms; Sonic's own phase at its start had made the
        // join to it skip, 2026-11-08)
        if (kotlin.math.abs(speed - 1) < .005) return pcm.copyOf()
        val s = androidx.media3.common.audio.SonicAudioProcessor()
        s.setSpeed(speed.toFloat()); s.setPitch(1f)
        s.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        s.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
        val out = java.io.ByteArrayOutputStream()
        fun drain() { val o = s.output; val b = ByteArray(o.remaining()); o.get(b); out.write(b) }
        val chunk = 4096 * channels
        var i = 0
        while (i < pcm.size) {
            val n = kotlin.math.min(chunk, pcm.size - i)
            val buf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            buf.asFloatBuffer().put(pcm, i, n)
            s.queueInput(buf)
            drain()
            i += n
        }
        s.queueEndOfStream(); drain()
        val bytes = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(bytes.remaining()).also { bytes.get(it) }
    }

    /** A song cut short whole again (not the one playing: that one can't be changed), its end let go. */
    private fun uncut(id: String) {
        val was = cutSongs[id] ?: return
        if (exo.currentMediaItem?.mediaId == id) return
        cutSongs.remove(id)
        provisional.remove(id)
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
        swapping = true
        try {
            // shuffled: the order as it was, put back after (a song put in again went after its neighbour in the list,
            // not where it was in the shuffle: the song cut stopped being next, another came next uncut, 2026-11-08)
            val order = if (exo.shuffleModeEnabled) shuffled() else null
            exo.removeMediaItem(i)
            exo.addMediaItem(i.coerceAtMost(exo.mediaItemCount), item)
            if (order != null && order.size == exo.mediaItemCount) reorder(order)
        } finally { swapping = false }
    }

    /** The queue's songs in the order shuffle plays them (their places in the list). */
    private fun shuffled(): IntArray {
        val tl = exo.currentTimeline
        val out = ArrayList<Int>(tl.windowCount)
        var w = tl.getFirstWindowIndex(true)
        while (w != C.INDEX_UNSET && out.size < tl.windowCount) { out += w; w = tl.getNextWindowIndex(w, Player.REPEAT_MODE_OFF, true) }
        return out.toIntArray()
    }
    private var swapping = false

    /** This song's end and the next one's start measured, if they aren't yet. */
    private fun measure() {
        if (!on(c)) return
        // this one, the next, and the one after (the next one's cut is matched to the one after it)
        val n = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
        val after = n?.let { exo.currentTimeline.getNextWindowIndex(it, exo.repeatMode, exo.shuffleModeEnabled) }?.takeIf { it != C.INDEX_UNSET }
        val items = listOfNotNull(exo.currentMediaItem, n?.let { exo.getMediaItemAt(it) }, after?.let { exo.getMediaItemAt(it) })
        for (item in items) {
            val id = item.mediaId
            if (id.startsWith("e") || id.startsWith("s") || !asked.add(id)) continue
            titles[id] = item.mediaMetadata.title?.toString().orEmpty()
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: continue
            work.execute {
                val e = runCatching { ends(uri, id) }.onFailure { android.util.Log.d("ink-crossfade", "$id: $it"); why(id, "its end couldn't be read ($it)") }.getOrNull()
                if (e != null) main.post {
                    ends[id] = e; keepCut(id, e); cut(); tailOfPrecut(id)
                    // its beat, for the counter; and its loudness, for even volume: the next song's too, so its gain's set
                    // ahead with what's measured (set before, it took the unknown song's, and corrected 4 s into the blend:
                    // "got louder for a second", 2026-11-08)
                    val next = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { exo.getMediaItemAt(it).mediaId }
                    if (id == exo.currentMediaItem?.mediaId || id == next) onChange()
                    // the song begun on, heard for the first time: it can't be cut now it plays (from the next one on, they run together)
                    if (id == exo.currentMediaItem?.mediaId && id !in cutSongs && !precut.containsKey(id) && exo.nextMediaItemIndex != C.INDEX_UNSET)
                        why(id, "the first song is measured as it plays and ends as it would. the ones after it run together")
                }
                // not read (a stream that wouldn't open, a decode too slow): asked again a little later, twice at most
                // (it was asked once and never again: that song never blended)
                else {
                    // the song before it planned without it meanwhile; asked again a little later
                    unread += id
                    main.post { cut() }
                    if ((tries.merge(id, 1, Int::plus) ?: 1) < 3) main.postDelayed({ asked.remove(id); unread.remove(id); measure() }, 15_000)
                }
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

    private fun ends(uri: android.net.Uri, id: String): Ends? {
        val src = source(uri) ?: return null.also { why(id, "its stream wasn't found") }
        // a stream read through ink music's own requests, only the parts the decoder asks for (2026-11-08: YouTube's
        // streams wouldn't open in Android's own reader, "Failed to instantiate extractor", so no YouTube song blended;
        // a server's took 11 s): one reader for all three opens, so what's read once is read once
        val ranged = (src as? String)?.takeIf { it.startsWith("http") }?.let { Ranged(it) }
        fun open(): MediaExtractor? = MediaExtractor().runCatching {
            when {
                ranged != null -> setDataSource(ranged)
                src is android.net.Uri -> setDataSource(c, src, null)
                src is File -> setDataSource(src.path)
                else -> setDataSource(src.toString(), emptyMap())
            }
            this
        }.onFailure { android.util.Log.d("ink-crossfade", "$id: won't open: $it") }.getOrNull()
        val x = open() ?: return null.also { why(id, "its file wouldn't open to be measured") }
        val track = (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: run { x.release(); why(id, "no sound found in it"); return null }
        val format = x.getTrackFormat(track)
        // a stream that doesn't say its length (a server's transcode): the player's own, if this is the song playing
        val dur = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L)
        x.release()
        if (dur <= 0) return null.also { why(id, "it doesn't say how long it is") }
        if (dur < FADE * 3) return null.also { why(id, "too short") }
        // read over the network: given longer (a slower phone or line took more than 8 s for 25 s of a stream, and the
        // end came back short: never mixed)
        val wait = if (src is String) 30_000L else 12_000L
        val t0 = SystemClock.uptimeMillis()
        // the last 25 s: where the sound ends, where an outro falls away, its beat; the first 20 s: its sound, its beat
        val endPcm = decode(open() ?: return null, track, (dur - 25_000).coerceAtLeast(0), dur, keep = true, wait = wait)
            ?: return null.also { why(id, "its end couldn't be decoded") }
        val endsAt = endPcm.start + endPcm.mono.size * 1000L / endPcm.rate
        if (endsAt < dur - 3_000) android.util.Log.d("ink-crossfade", "$id: its end read short, to ${endsAt / 1000.0}s of ${dur / 1000.0}s, in ${SystemClock.uptimeMillis() - t0} ms")
        val deep = format.containsKey("bits-per-sample") && format.getInteger("bits-per-sample") > 16
        val headPcm = decode(open() ?: return null, track, 0, 20_000, wait = wait)
        val end = levels(endPcm)
        val window = 50L
        val loud = end.maxOrNull() ?: return null
        val lastIdx = end.indexOfLast { it > -50f }.takeIf { it >= 0 } ?: return null
        val lastSound = endPcm.start + (lastIdx + 1) * window
        // an outro falling away: the first moment from which it stays 15 dB under the end's loudest
        val quietIdx = (0..lastIdx).firstOrNull { i -> (i..lastIdx).all { end[it] < loud - 15f } }
        val quiet = quietIdx?.let { endPcm.start + it * window }
        // a fade-out in its own recording (2026-11-08: one nearly gone when the next came in over it, ~25 dB up): each
        // second's level, its usual (the loud fifth of the end's first 15 s), and the first second from which it stays
        // 4 dB under that to the end, falling at least 10: the mix begun half a second before, over the music
        val fading = run {
            val pw = end.map { Math.pow(10.0, it / 10.0) }
            if (lastIdx < 200) return@run null
            val sec = (0..lastIdx - 20).map { i -> 10 * log10((i until i + 20).sumOf { pw[it] } / 20 + 1e-12) }
            val body = sec.take(300).sorted().let { it[(it.size * .8).toInt().coerceAtMost(it.size - 1)] }
            val from = (sec.indices).firstOrNull { i -> (i until sec.size).all { sec[it] < body - 4 } } ?: return@run null
            if (sec.last() > body - 10 || sec.size - from < 60) null else endPcm.start + from * window
        }
        val handover = listOfNotNull(lastSound - FADE, quiet?.minus(1_000), fading?.minus(500)).minOrNull()!!.coerceAtLeast(dur - 25_000)
        val firstSound = headPcm?.let { levels(it) }?.indexOfFirst { it > -50f }?.takeIf { it > 0 }?.let { it * window }?.takeIf { it >= 400 } ?: 0L
        val tail = beats(endPcm); val head = headPcm?.let { beats(it) }
        val tailKey = Key.find(endPcm.mono, endPcm.rate); val headKey = headPcm?.let { Key.find(it.mono, it.rate) }
        val lufs = loudness(listOfNotNull(headPcm, endPcm))
        // its intro: a second's level (in 50 ms steps) first within 6 dB of its usual (the loud tenth of its first 20 s)
        val fullAt = headPcm?.let { h ->
            val lv = levels(h).map { Math.pow(10.0, it / 10.0) }
            if (lv.size < 40) return@let 0L
            val sec = (0..lv.size - 20).map { i -> 10 * log10((i until i + 20).sumOf { lv[it] } / 20) }
            val usual = sec.sorted()[(sec.size * .9).toInt().coerceAtMost(sec.size - 1)]
            (sec.indexOfFirst { it >= usual - 6 }.coerceAtLeast(0) * 50L)
        } ?: 0L
        android.util.Log.d("ink-crossfade", "$id: ${dur / 1000}s, measured in ${SystemClock.uptimeMillis() - t0} ms, sound to ${lastSound / 1000.0}s, quiet from ${quiet?.div(1000.0)}, ${fading?.let { "fading out from ${it / 1000.0}s, " } ?: ""}hand over at ${handover / 1000.0}s, starts ${firstSound}ms in; " +
            "beat at its end ${tail?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}, at its start ${head?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}")
        android.util.Log.d("ink-crossfade", "$id: key at its end ${tailKey?.let { "${it.camelot} (%.2f sure)".format(it.sure) }}, at its start ${headKey?.let { "${it.camelot} (%.2f sure)".format(it.sure) }}" +
            "; bar one at ${tail?.downbeat?.let { "%.2fs".format(it / 1000) }}, the last bars ${tail?.bars?.takeLast(8)?.joinToString(" ") { "%.1f".format(it.loud) }}")
        // only its channels kept for the blend: its one-channel copy was for measuring (5 MB a song let go)
        val kept = Pcm(endPcm.start, FloatArray(0), endPcm.rate, endPcm.channels, endPcm.all)
        return Ends(lastSound, handover, firstSound, tail, head, kept.takeIf { !deep || !Exact.inUse(c) }, dur, tailKey, headKey,
            headPcm?.let { levels(it).toFloatArray() } ?: FloatArray(0), end.toFloatArray(), endPcm.start, fullAt, fading,
            listOfNotNull(headPcm, endPcm).maxOf { p -> (p.all ?: p.mono).maxOf { kotlin.math.abs(it) }.toDouble() }, lufs)
    }

    /** A stretch of a song decoded: where it begins (ms), its samples made mono, their rate. */
    private class Pcm(val start: Long, val mono: FloatArray, val rate: Int, val channels: Int = 1, val all: FloatArray? = null)

    /**
     * A song's loudness (LUFS, roughly) from its decoded stretches: 400 ms blocks, the silent ones out (-70), then the
     * ones 10 dB under the rest out (an intro, a fade), the mean of what's left; 3 dB on for the channels summed as the
     * standard sums them (these are one channel, averaged). For even volume where a song has no gain of its own (Levelling).
     */
    private fun loudness(parts: List<Pcm>): Double? {
        val blocks = ArrayList<Double>()
        for (p in parts) {
            val n = (p.rate * .4).toInt()
            if (n <= 0) continue
            var i = 0
            while (i + n <= p.mono.size) { var e = 0.0; for (k in i until i + n) e += p.mono[k] * p.mono[k]; blocks += e / n; i += n }
        }
        fun lufs(e: Double) = -0.691 + 10 * log10(e.coerceAtLeast(1e-12))
        val heard = blocks.filter { lufs(it) > -70 }
        if (heard.size < 10) return null
        val rel = lufs(heard.average()) - 10
        val kept = heard.filter { lufs(it) > rel }
        return lufs(kept.average()) + 3
    }

    /**
     * The fader ridden (2026-11-08, "its still getting louder during mix"): each song levelled as a whole, an old one's
     * outro still often sits under a new one's start, and the blend rose. The two as they'll sound over the blend (each
     * stretch's mean power, with its gain), and the new one turned down by the difference where it's louder (6 dB at
     * most), coming up to its own over the blend and a little after (Blend). 0: none.
     */
    private fun ride(old: Ends, new: Ends?, oldId: String, newId: String, at: Long, fade: Long): Double {
        val n = new ?: return 0.0
        fun mean(lv: FloatArray, from: Long, to: Long, start: Long): Double? {
            val a = ((from - start) / 50).toInt().coerceAtLeast(0); val b = ((to - start) / 50).toInt().coerceAtMost(lv.size)
            if (b - a < 10) return null
            return 10 * log10((a until b).sumOf { Math.pow(10.0, lv[it] / 10.0) } / (b - a))
        }
        val o = mean(old.tailLv, at, at + fade, old.tailFrom) ?: return 0.0
        val w = mean(n.headLv, 0, fade, 0) ?: return 0.0
        fun gainDb(id: String) = blend?.gains?.get(id)?.let { 20 * log10(it.toDouble()) } ?: 0.0
        return ((o + gainDb(oldId)) - (w + gainDb(newId))).coerceIn(-6.0, 0.0)
    }

    /** A song's measured loudness (LUFS), while it's this song, the next or the one after. */
    fun lufs(id: String): Double? = ends[id]?.lufs
    /** A song's loudest sample as measured (0..1), while it's this song, the next or the one after. */
    fun peak(id: String): Double? = ends[id]?.peak?.takeIf { it > 0 }

    /** The level (dBFS) of each 50 ms. */
    private fun levels(p: Pcm): List<Float> {
        val per = p.rate / 20
        return (0 until p.mono.size / per).map { w -> var sum = 0.0; for (k in w * per until (w + 1) * per) sum += p.mono[k] * p.mono[k]; db(sum, per) }
    }

    /** The beat over a stretch (Beat: Prism's way, 2026-11-08), as automix keeps it. */
    private fun beats(p: Pcm): Beats? = Beat.find(p.mono, p.rate, p.start)?.let { Beats(it.period, it.first, it.sure, it.downbeat, it.bars) }

    /** A stretch decoded, its channels averaged to one (blocking; `wait` at most). */
    private fun decode(x: MediaExtractor, track: Int, fromMs: Long, toMs: Long, keep: Boolean = false, wait: Long = 12_000): Pcm? {
        try {
            x.selectTrack(track)
            val format = x.getTrackFormat(track)
            x.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            // the decoder's own rate and channels once it says them: phones' decoders differ from the file (an HE-AAC
            // stream decoded at twice its stated rate, a mono file given as two channels), and a blend read at the
            // wrong rate or channels matched nothing
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            runCatching { codec.outputFormat }.getOrNull()?.let { f ->
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            var out = FloatArray(((toMs - fromMs + 2_000) * rate / 1000).toInt())
            // every channel too, when kept for the blend (the old song's end mixed back in: Blend)
            var all = if (keep) FloatArray(out.size * channels) else null
            var allSize = 0
            var size = 0; var ch = 0; var acc = 0f
            var first = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.uptimeMillis() + wait
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
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && size == 0 && allSize == 0) {
                    val f = codec.outputFormat
                    val r = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else rate
                    val n = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels
                    if (r != rate || n != channels) {
                        rate = r; channels = n
                        out = FloatArray(((toMs - fromMs + 2_000) * rate / 1000).toInt())
                        all = if (keep) FloatArray(out.size * channels) else null
                    }
                }
                if (o >= 0) {
                    val at = info.presentationTimeUs / 1000
                    val b: ByteBuffer = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val float = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
                    if (at >= fromMs) {
                        if (first < 0) first = at
                        val all = all
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
