package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transitions, the phone's (settings, sound): off, crossfade (each song runs into the next where it fades), or automix
 * (their beats matched too). Each song's end is measured as it starts (its last 25 seconds decoded on their own, by a
 * third mpv writing samples, not sound), so a song fading out hands over as it fades, one ending in silence before the
 * silence, and the next one's own silence at its start is skipped.
 *
 * Two decks ([Player]'s two mpvs), turn about: the next song readied, paused and silent, on the one that's free; at
 * the handover it starts from its first sound, rising, while the old one falls away on the deck it was on, each whole
 * song played as it is (no song cut, nothing played twice). From the first moment the queue, the bar and Now Playing
 * are the new song's; the old deck, its song faded out, is let go and free for the next. Equal-power curves (the volume
 * as mpv takes it: its cube is the level), so the loudness neither dips nor bumps.
 *
 * Automix: each song's beat found in the same decode (an onset curve from its low end, the kick; its period by
 * autocorrelation; its grid laid on); where both are sure and their tempos within 8% (or double, or half), the old
 * song's end is played at the new one's tempo (its pitch kept), started so its beats fall on the new one's (within
 * ~10 ms, measured off the output), the bass handed across at the middle so two kicks never fight. Not sure of either
 * beat: the plain crossfade.
 *
 * Never within a record played in order (its songs run into each other as made), never an episode or a station, never
 * with the output had alone (one deck has it), on a speaker, on repeat one, or a skip of yours.
 */
object Crossfade {
    const val OFF = "off"; const val CROSSFADE = "crossfade"; const val AUTOMIX = "automix"
    private val file = File(Home.dir, "crossfade")
    val mode = MutableStateFlow(runCatching { file.readText().trim() }.getOrNull()?.let { if (it == AUTOMIX) AUTOMIX else CROSSFADE } ?: OFF)

    fun set(m: String) {
        val was = mode.value
        mode.value = m
        runCatching { if (m == OFF) file.delete() else file.writeText(m) }
        if (m == OFF) settle()
        // automix's bass handover needs its filter in the chain
        if ((m == AUTOMIX) != (was == AUTOMIX)) Player.rechain()
    }

    /** Resting though it's on: the output had alone (two decks can't both have it). */
    val resting get() = mode.value != OFF && Player.sound.value.exclusive

    private const val FADE = 6_000L
    /** How sure a beat must be (its period's peak over the mean) to be matched. */
    private const val SURE = Beat.SURE
    /** The looser bar where two tempos agree within 2% (the phone's Tempos.LOOSE). */
    private const val LOOSE = 2.8
    /** The new deck started this far ahead of the handover: it's heard a moment after it's told to play. */
    private const val LEAD = 90L

    /** A song's beat over a stretch: its period (ms), one beat's time (ms into the song), how sure (peak over mean). */
    data class Beats(val period: Double, val first: Double, val sure: Double, val downbeat: Double = first, val bars: List<Beat.Bar> = emptyList()) {
        val bpm get() = 60_000 / period
        fun after(t: Double) = first + Math.ceil((t - first) / period) * period
        /** The bar start (bar one) nearest `t`. */
        fun barNear(t: Double) = downbeat + Math.round((t - downbeat) / (4 * period)) * 4 * period
    }

    /** Where a song's sound ends (ms), where its handover begins, where its sound begins; its beat at its end and start. */
    data class Ends(val lastSound: Long, val handover: Long, val firstSound: Long, val tail: Beats?, val head: Beats?, val duration: Long,
                    val tailKey: Key.Found? = null, val headKey: Key.Found? = null,
                    /** Its start's level (dBFS each 50 ms from 0), its end's (each 50 ms from tailFrom): the phone's. */
                    val headLv: FloatArray = FloatArray(0), val tailLv: FloatArray = FloatArray(0), val tailFrom: Long = 0,
                    /** When its intro's over (a second within 6 dB of its usual); where a fade-out in its recording begins. */
                    val fullAt: Long = 0, val fading: Long? = null)

    /** A handover planned: the old song's time it begins at, the new one's start, how long, the old one's tempo then. */
    private data class Plan(
        val from: String, val into: String, val cut: Long, val start: Long, val fade: Long,
        val speed: Double = 1.0, val a0: Double = 0.0, val b0: Double = 0.0,
        val period: Double = 0.0, val bpmFrom: Double = 0.0,
        /** The filter fade (the phone's, 2026-11-08): the old one's top closing, the new one opening from thin. */
        val filter: Boolean = false,
        /** The new one's intro quiet over this share of the fade: the old one held up longer, the new one in sooner. */
        val hold: Double = 0.0,
        /** The new one turned down this much (dB) to the old end's level, up to its own over the fade and 4 s. */
        val rideDb: Double = 0.0,
    ) { val matched get() = speed != 1.0 || a0 != 0.0 }

    /**
     * A handover under way, for the line (the phone's MixBar): the song leaving, when it began (wall clock), how long;
     * matched, the two tempos and the new song's beat (its first in the handover's time, its period).
     */
    data class Mixing(
        val from: Song, val into: String, val start: Long, val length: Long,
        val bpmFrom: Double = 0.0, val bpmTo: Double = 0.0, val beatFirst: Double = 0.0, val beatPeriod: Double = 0.0,
    ) {
        /** How far through, 0 to 1; null outside it. */
        fun through(now: Long = System.currentTimeMillis()): Float? =
            if (length <= 0 || now < start || now >= start + length) null else (now - start).toFloat() / length
    }
    val mixing = MutableStateFlow<Mixing?>(null)

    /** A song's measured ends (its beat and key at its start and end), in automix, for the beat counter over the line. */
    fun endsOf(id: String): Ends? = ends[id]?.takeIf { mode.value == AUTOMIX }
    /** The playing song's mix point, once planned: its id, where the next comes in (ms). Past it, heard under the next. */
    val point = MutableStateFlow<Pair<String, Long>?>(null)

    private val ends = ConcurrentHashMap<String, Ends>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val work = Executors.newSingleThreadExecutor { Thread(it, "crossfade-measure").apply { isDaemon = true } }

    // the deck readied for the next song, and the handover under way
    @Volatile private var plan: Plan? = null
    @Volatile private var readied: Mpv? = null
    @Volatile private var readyFor: String? = null
    @Volatile private var loaded = false
    @Volatile private var fadingOut: Mpv? = null
    @Volatile private var fadingIn: Mpv? = null
    /** A handover's in hand: the volume's the fade's, not the slider's alone. */
    val fading get() = fadingOut != null

    fun start() {
        Thread({ while (true) { runCatching { look() }.onFailure { Log.d("crossfade: $it") }; Thread.sleep(if (plan != null && readied != null) 4 else 40) } },
            "crossfade").apply { isDaemon = true; start() }
    }

    /** The readied deck has its song open: to where it starts, silent, the lows held back for automix. */
    internal fun loaded(m: Mpv) {
        if (m !== readied) return
        val p = plan
        if (p != null && p.start > 0) m.command("seek", "%.3f".format(java.util.Locale.ROOT, p.start / 1000.0), "absolute", "exact")
        if (p?.matched == true) m.command("af-command", "ink", "f", "200", "highpass@bass")
        else if (p?.filter == true) m.command("af-command", "ink", "f", "160", "highpass@bass")
        Thread { Thread.sleep(250); if (readied === m) loaded = true }.start()
    }

    private fun look() {
        if (fading) return
        val on = mode.value != OFF && !resting && Player.mixable()
        val q = Player.queue.value
        val i = Player.index()
        val a = Player.active()
        if (!on || a == null || i !in q.indices) return drop()
        val nextAt = when { i + 1 < q.size -> i + 1; Player.repeat.value == 1 && q.size > 1 -> 0; else -> return drop() }
        val s = q[i]; val n = q[nextAt]
        if (!allowed(s, n, i, nextAt)) return drop()
        measure(s); measure(n)
        val es = ends[s.id] ?: return
        val en = ends[n.id] ?: return
        val p = plan?.takeIf { it.from == s.id && it.into == n.id } ?: plan(s, n, es, en).also { drop(); plan = it } ?: return
        // where the old deck is, as heard, to the millisecond (the player's own clock moves in fifths of a second)
        val pos = a.get("time-pos")?.toDoubleOrNull()?.times(1000)?.toLong() ?: Player.position()
        if (pos > p.cut + 1_500) { point.value = null; return }   // past it (a seek): ends as it would
        if (point.value != (s.id to p.cut)) point.value = s.id to p.cut
        val spare = Player.spare() ?: return
        // readied twelve seconds ahead (a YouTube song's stream found meanwhile)
        if (pos >= p.cut - 12_000 && readyFor != n.id) {
            readied = spare; readyFor = n.id; loaded = false
            spare.command("stop")
            spare.set("pause", "yes"); spare.set("volume", "0"); spare.set("speed", "1")
            spare.set("loop-file", "no"); spare.set("loop-playlist", "no")
            Player.chainOn(spare)
            spare.command("loadfile", Player.where(n))
        }
        if (readyFor == n.id && loaded && pos >= p.cut - LEAD && !Player.isPaused()) go(a, spare, p, nextAt, s)
    }

    /** This song into that one: may it? */
    private fun allowed(s: Song, n: Song, i: Int, nextAt: Int): Boolean {
        if (Player.repeat.value == 2) return false
        if (listOf(s, n).any { it.station || it.episode }) return false
        // a record in order: its songs run into each other as they were made
        if (s.albumId.isNotEmpty() && s.albumId == n.albumId && nextAt == i + 1 && n.track > s.track) return false
        val d = Player.length().takeIf { it > 0 } ?: s.duration
        return d > FADE * 3 && (n.duration == 0L || n.duration > FADE * 3)
    }

    private fun plan(s: Song, n: Song, es: Ends, en: Ends): Plan? {
        if (mode.value == AUTOMIX) matched(s, n, es, en)?.let { return it }
        val cut = es.handover
        // not beats matched, in automix: the filter fade, short when their keys clash (both sure)
        val clash = Key.clash(es.tailKey, en.headKey)
        val fade = (es.lastSound - cut).coerceIn(2_000, 12_000).let { if (clash) it.coerceAtMost(5_000) else it }
        return Plan(s.id, n.id, cut, en.firstSound, fade, filter = mode.value == AUTOMIX,
            hold = ((en.fullAt - en.firstSound).toDouble() / fade).coerceIn(0.0, .7))
    }

    /**
     * The beats matched (the phone's): the old one's end played at the new one's tempo, started so its beats fall on
     * the new one's from its start (the new one's first beat after its start, the old one's a beat on from 16 before
     * its sound ends), over up to 16 of the new one's beats. Not sure of either beat, or tempos more than 8% apart
     * (double and half counting): none.
     */
    private fun matched(s: Song, n: Song, e: Ends, next: Ends): Plan? {
        val a = e.tail ?: return null
        val b = next.head ?: return null
        val pa = listOf(1.0, 2.0, .5).map { a.period * it }.minByOrNull { abs(it / b.period - 1) }!!
        val speed = pa / b.period
        if (abs(speed - 1) > .08) return null
        // sure of both beats; where the tempos agree within 2%, a little less sure will do (the phone's)
        val needed = if (abs(speed - 1) <= .02) LOOSE else SURE
        if (a.sure < needed || b.sure < needed) return null
        // in on the new one's bar one (its first bar start once its sound begins); out on one of the old one's bar
        // starts, where its outro begins (a bar a third quieter than the four before, in its last ~8 bars), else the bar
        // four from where its sound ends (the phone's, after FireTheDJ)
        val bar = 4 * b.period
        var b0 = b.downbeat
        while (b0 < next.firstSound - 60) b0 += bar
        while (b0 - bar >= next.firstSound - 60) b0 -= bar
        val latest = e.lastSound - 8 * pa
        val outro = a.bars.withIndex().filter { (i, br) ->
            i >= 4 && br.at in (e.lastSound - 36 * pa)..latest && br.loud < .67f * (i - 4 until i).map { a.bars[it].loud }.average()
        }.lastOrNull()?.value?.at
        val a0 = (outro ?: a.barNear(e.lastSound - 16 * pa).let { if (it > latest) it - 4 * a.period else it }).let { o ->
            // fading out in its own recording: out on the bar before the fade begins, the new one in over the music
            val f = e.fading?.toDouble() ?: return@let o
            if (o <= f - 500) o else a.barNear(f - 500).let { if (it > f - 500) it - 4 * a.period else it }
        }
        var out = a0
        var cut = (a0 - speed * b0).toLong()
        // keys that sit well together, a longer blend (24 beats, 20 s at most: the phone's, after LastWave's); never more
        // than two fifths of either song
        val fit = Key.fit(e.tailKey, next.headKey)
        var fade = kotlin.math.min((if (fit) 24 else 16) * b.period, (e.lastSound - cut) / speed).toLong().coerceIn(4_000, if (fit) 20_000 else 14_000)
            .let { f -> listOf(e.duration, next.duration).filter { it > 0 }.minOrNull()?.let { d -> f.coerceAtMost((d * 2 / 5).coerceAtLeast(4_000)) } ?: f }
        if (cut < e.duration - 25_000 + 500 || cut < a.first) return null
        // no voice over another (the phone's, after LastWave's automix): both singing for more than a third of a second,
        // the blend shorter a bar at a time (4 s at least), else out a bar or two earlier or later; the first plan that
        // keeps them apart, inside the old one's end as decoded
        val vo = vocals[s.id]; val vn = vocals[n.id]
        if (!vo.isNullOrEmpty() && !vn.isNullOrEmpty()) {
            val was = Vocals.overlap(vo, out, speed, vn, b0, fade)
            if (was > Vocals.MOST) {
                val step = (4 * b.period).toLong()
                val tries = buildList {
                    var f = fade - step
                    while (f >= 4_000) { add(out to f); f -= step }
                    for (k in listOf(-1, 1, -2)) add(out + k * 4 * a.period to fade)
                }.mapNotNull { (o, f) ->
                    val c2 = (o - speed * b0).toLong()
                    val f2 = kotlin.math.min(f.toDouble(), (e.lastSound - c2) / speed).toLong()
                    (o to f2).takeIf { o <= latest + 1 && c2 >= e.duration - 25_000 + 500 && c2 >= a.first && f2 >= 4_000 }
                }
                val safe = tries.firstOrNull { (o, f) -> Vocals.overlap(vo, o, speed, vn, b0, f) <= Vocals.MOST }
                Log.d("crossfade: vocals: $was ms of both singing as planned; " + (safe?.let { (o, f) -> "out at %.2fs over %.1fs instead".format(o / 1000, f / 1000.0) } ?: "no plan keeps them apart"))
                if (safe != null) { out = safe.first; cut = (out - speed * b0).toLong(); fade = safe.second }
            }
        }
        // keys clashing (both sure): the same four bars, the old one sinking under the filter fade's low-pass
        val clash = Key.clash(e.tailKey, next.headKey)
        Log.d("crossfade: ${s.title} into ${n.title}: beats matched, %.1f into %.1f bpm, at %.3fx, out at %.2fs (%s), in on bar one at %.2fs, keys %s into %s%s".format(
            a.bpm, b.bpm, speed, a0 / 1000, if (outro != null) "its outro" else "a bar start", b0 / 1000, e.tailKey?.camelot, next.headKey?.camelot, if (clash) ", clashing" else ""))
        return Plan(s.id, n.id, cut, 0, fade, speed, out, b0, b.period, a.bpm, filter = clash,
            hold = ((next.fullAt - b0) / fade).coerceIn(0.0, .7))
    }

    /**
     * The fader ridden (the phone's, 2026-11-08, "still getting louder during mix"): the old end and the new start as they'll
     * sound over the fade (each stretch's mean power, with its own even-volume gain), the new one turned down where it's
     * louder, 6 dB at most. 0: none.
     */
    private fun ride(old: Ends, new: Ends, cut: Long, start: Long, fade: Long, a: Mpv, b: Mpv): Double {
        fun mean(lv: FloatArray, from: Long, to: Long, at: Long): Double? {
            val a = ((from - at) / 50).toInt().coerceAtLeast(0); val b = ((to - at) / 50).toInt().coerceAtMost(lv.size)
            if (b - a < 10) return null
            return 10 * log10((a until b).sumOf { Math.pow(10.0, lv[it] / 10.0) } / (b - a))
        }
        val o = mean(old.tailLv, cut, cut + fade, old.tailFrom) ?: return 0.0
        val w = mean(new.headLv, start, start + fade, 0) ?: return 0.0
        return ((o + gainOf(a)) - (w + gainOf(b))).coerceIn(-6.0, 0.0)
    }

    /** A deck's even-volume gain (dB) as mpv applies it: its tags' (album's first), else the untagged level; off, 0. */
    private fun gainOf(m: Mpv): Double {
        if (!Player.sound.value.even) return 0.0
        return (m.get("current-tracks/audio/replaygain-album-gain") ?: m.get("current-tracks/audio/replaygain-track-gain"))?.toDoubleOrNull()
            ?: Player.UNTAGGED.toDouble()
    }

    /** The handover: the new deck sounding, rising; the old one on to its end, falling away; the player the new one's. */
    private fun go(a: Mpv, b: Mpv, planned: Plan, nextAt: Int, from: Song) {
        if (Player.queue.value.getOrNull(nextAt)?.id != planned.into) return drop()
        // the ride worked out now, both decks' songs open (their gains known)
        val p = planned.copy(rideDb = run {
            val eo = ends[planned.from]; val en = ends[planned.into]
            if (eo == null || en == null) 0.0 else ride(eo, en, planned.cut, planned.start, planned.fade, a, b)
        })
        fadingOut = a; fadingIn = b
        mixing.value = Mixing(from, p.into, System.currentTimeMillis(), p.fade,
            if (p.matched) p.bpmFrom else 0.0, if (p.matched && p.period > 0) 60_000 / p.period else 0.0,
            if (p.matched) p.b0 - p.start else 0.0, if (p.matched) p.period else 0.0)
        point.value = null
        b.set("pause", "no")
        // the old deck plays its song out and stops there: nothing after it, no repeat
        a.command("playlist-clear"); a.set("loop-file", "no"); a.set("loop-playlist", "no")
        if (p.matched) a.set("speed", "%.4f".format(java.util.Locale.ROOT, p.speed))
        Player.become(b, nextAt)
        readied = null; readyFor = null; plan = null
        Log.d("crossfade: into ${p.into} at ${p.cut / 1000.0}s over ${p.fade / 1000.0}s, from ${p.start} ms in" + (if (p.matched) ", beats matched" else "") +
            (if (p.hold > .05) ", the old one held over ${"%.0f".format(p.hold * 100)}%" else "") + (if (p.rideDb < -.5) ", the new one ridden in ${"%.1f".format(p.rideDb)} dB" else ""))
        Thread({ fade(a, b, p) }, "crossfade-fade").apply { isDaemon = true; start() }
    }

    private fun fade(a: Mpv, b: Mpv, p: Plan) {
        val began = System.nanoTime()
        var bassAcross = false
        val eo = ends[p.from]; val en = ends[p.into]
        val ride0 = Math.pow(10.0, p.rideDb / 20)
        while (fadingOut === a) {
            val ms = (System.nanoTime() - began) / 1e6
            val t = (ms / p.fade).coerceIn(0.0, 1.0)
            val v = if (Player.muted.value) 0.0 else Player.volume.value.toDouble()
            // equal power; a quiet intro coming in, the old one held at full over most of it, the new one rising sooner
            val h = p.hold
            val kOld = if (h > 0) ((t - .8 * h) / (1 - .8 * h)).coerceIn(0.0, 1.0) else t
            val kNew = if (h > 0) (t / (1 - .5 * h)).coerceIn(0.0, 1.0) else t
            val gOld = cos(kOld * PI / 2)
            // the new one ridden up from the old end's level, over the fade and 4 s after
            val ride = ride0 + (1 - ride0) * (ms / (p.fade + 4_000)).coerceIn(0.0, 1.0)
            val gNew = sin(kNew * PI / 2) * ride
            // kept level: the two as measured where each is now; where together they'd come out over the one handing to
            // the other (old's level to new's as it goes) by more than 1 dB, both down by the difference
            val keep = if (eo != null && en != null) {
                fun at(lv: FloatArray, ms: Double) = lv.getOrNull((ms / 50).toInt())?.let { Math.pow(10.0, it / 10.0) }
                val po = at(eo.tailLv, p.cut + ms * p.speed - eo.tailFrom); val pn = at(en.headLv, p.start + ms)
                if (po == null || pn == null) 1.0 else {
                    val sum = po * gOld * gOld + pn * gNew * gNew; val ref = po * (1 - t) + pn * ride * ride * t
                    if (sum > ref * 1.26 && sum > 1e-9) sqrt(ref * 1.26 / sum) else 1.0
                }
            } else 1.0
            // mpv's volume is the level's cube root
            a.set("volume", "%.2f".format(java.util.Locale.ROOT, v * cbrt(gOld * keep)))
            b.set("volume", "%.2f".format(java.util.Locale.ROOT, v * cbrt(gNew * keep)))
            if (p.matched) {
                // the bass across at the middle: the new one's lows let in, the old one's held back
                if (!bassAcross && t >= .5) {
                    bassAcross = true
                    b.command("af-command", "ink", "f", "10", "highpass@bass"); a.command("af-command", "ink", "f", "200", "highpass@bass")
                }
                // no loop holding it on the beat: mpv's clock on a deck played faster runs ~20 ms off what's heard,
                // and a speed set again and again makes its stretching jump (both measured off its output: held at one
                // speed the beats stayed within ~10 ms, nudged they swung by 30)
            }
            // the filter fade: the old one's top closing over the whole of it (20 kHz to 300 Hz); not matched, the new
            // one opening from 160 Hz to all of it by the middle, the basses swapped there
            if (p.filter) {
                a.command("af-command", "ink", "f", "%.0f".format(java.util.Locale.ROOT, 20_000.0 * Math.pow(300.0 / 20_000.0, t)), "lowpass@top")
                if (!p.matched) {
                    b.command("af-command", "ink", "f", "%.0f".format(java.util.Locale.ROOT, 20.0 * Math.pow(8.0, (1 - t / .5).coerceIn(0.0, 1.0))), "highpass@bass")
                    if (!bassAcross && t >= .5) { bassAcross = true; a.command("af-command", "ink", "f", "200", "highpass@bass") }
                }
            }
            if (t >= 1.0) break
            Thread.sleep(25)
        }
        finish(a, b, ride0 + (1 - ride0) * (p.fade.toDouble() / (p.fade + 4_000)))
        // the ride's last seconds, on the new deck alone (unless something else has the volume by then)
        if (p.rideDb < -.5) Thread({
            val began2 = System.nanoTime()
            while (!fading && Player.active() === b) {
                val ms = p.fade + (System.nanoTime() - began2) / 1e6
                val ride = ride0 + (1 - ride0) * (ms / (p.fade + 4_000)).coerceIn(0.0, 1.0)
                val v = if (Player.muted.value) 0.0 else Player.volume.value.toDouble()
                b.set("volume", "%.2f".format(java.util.Locale.ROOT, v * cbrt(ride)))
                if (ride >= 1.0) break
                Thread.sleep(25)
            }
        }, "crossfade-ride").apply { isDaemon = true; start() }
    }

    /** The handover done: the old deck let go, the new one at the slider's volume (times what's left of a ride). */
    @Synchronized private fun finish(a: Mpv, b: Mpv, level: Double = 1.0) {
        if (fadingOut !== a) return
        fadingOut = null; fadingIn = null
        mixing.value = null
        a.command("stop"); a.set("pause", "yes"); a.set("speed", "1")
        Player.chainOn(a)
        b.command("af-command", "ink", "f", "10", "highpass@bass"); b.command("af-command", "ink", "f", "20000", "lowpass@top")
        b.set("volume", if (level >= 1.0) Player.volume.value.toString()
            else "%.2f".format(java.util.Locale.ROOT, (if (Player.muted.value) 0.0 else Player.volume.value.toDouble()) * cbrt(level)))
    }

    /** At once: a handover under way finished (the old song gone), one readied let go. Before a skip, a pause, a seek. */
    fun settle() {
        val a = fadingOut; val b = fadingIn
        if (a != null && b != null) finish(a, b)
        drop()
    }

    private fun drop() {
        val r = readied
        readied = null; readyFor = null; loaded = false; plan = null
        if (point.value != null) point.value = null
        if (r != null && r !== Player.active()) runCatching { r.command("stop") }
    }

    // ---- each song's end measured: its last 25 s and first 20 s decoded apart (a third mpv, samples to a file) ----

    private fun measure(s: Song) {
        if (ends.size > 40) ends.keys.take(20).forEach { ends.remove(it); asked.remove(it) }
        if (!asked.add(s.id)) return
        work.execute {
            runCatching { ends(s) }.onFailure { Log.d("crossfade: ${s.title}: $it") }.getOrNull()?.let { ends[s.id] = it }
            // automix: when it's sung, from its timed lyrics (the lyrics' own cache and sources: Vocals)
            if (mode.value == AUTOMIX && !s.station && !vocals.containsKey(s.id)) runCatching {
                val lines = kotlinx.coroutines.runBlocking { Lyrics.of(s) }.orEmpty()
                vocals[s.id] = Vocals.spans(lines.map { Vocals.Sung(it.at, it.words, it.parts.lastOrNull()?.first) })
                Log.d("crossfade: ${s.title}: ${vocals[s.id]?.size ?: 0} stretches sung")
            }
        }
    }

    /** When each song's singing (ms), from its timed lyrics: automix lays no voice over another (Vocals). Empty: none known. */
    private val vocals = java.util.concurrent.ConcurrentHashMap<String, List<LongRange>>()

    private fun ends(s: Song): Ends? {
        val url = if (s.tube && Offline.file(s.id) == null) YouTube.stream(s.id) ?: return null else Player.where(s)
        if (url.isEmpty()) return null
        val (endPcm, dur) = decode(url, -25.0, 25.0) ?: return null
        if (dur < FADE * 3) return null
        val start = (dur - 25_000).coerceAtLeast(0)
        val head = decode(url, 0.0, 20.0)?.first
        val end = levels(endPcm)
        val window = 50L
        val loud = end.maxOrNull() ?: return null
        val lastIdx = end.indexOfLast { it > -50f }.takeIf { it >= 0 } ?: return null
        val lastSound = start + (lastIdx + 1) * window
        // an outro falling away: the first moment from which it stays 15 dB under the end's loudest
        val quietIdx = (0..lastIdx).firstOrNull { i -> (i..lastIdx).all { end[it] < loud - 15f } }
        val quiet = quietIdx?.let { start + it * window }
        // a fade-out in its own recording (the phone's): the first second from which it stays 4 dB under its usual (the loud
        // fifth of the end's first 15 s) to the end, falling 10 at least; the handover half a second before it
        val fading = run {
            val pw = end.map { Math.pow(10.0, it / 10.0) }
            if (lastIdx < 200) return@run null
            val sec = (0..lastIdx - 20).map { i -> 10 * log10((i until i + 20).sumOf { pw[it] } / 20 + 1e-12) }
            val body = sec.take(300).sorted().let { it[(it.size * .8).toInt().coerceAtMost(it.size - 1)] }
            val from = sec.indices.firstOrNull { i -> (i until sec.size).all { sec[it] < body - 4 } } ?: return@run null
            if (sec.last() > body - 10 || sec.size - from < 60) null else start + from * window
        }
        val handover = listOfNotNull(lastSound - FADE, quiet?.minus(1_000), fading?.minus(500)).minOrNull()!!.coerceAtLeast(start)
        val firstSound = head?.let { levels(it) }?.indexOfFirst { it > -50f }?.takeIf { it > 0 }?.let { it * window }?.takeIf { it >= 400 } ?: 0L
        val tail = beats(endPcm, start); val top = head?.let { beats(it, 0) }
        val tailKey = Key.find(endPcm, RATE); val headKey = head?.let { Key.find(it, RATE) }
        Log.d("crossfade: ${s.title}: ${dur / 1000}s, sound to ${lastSound / 1000.0}s, quiet from ${quiet?.div(1000.0)}, ${fading?.let { "fading out from ${it / 1000.0}s, " } ?: ""}hand over at ${handover / 1000.0}s, " +
            "starts ${firstSound}ms in; beat at its end ${tail?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}, at its start ${top?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}")
        val headLv = head?.let { levels(it) }.orEmpty()
        // its intro: a second's level first within 6 dB of its usual (the loud tenth of its first 20 s)
        val fullAt = if (headLv.size < 40) 0L else {
            val lv = headLv.map { Math.pow(10.0, it / 10.0) }
            val sec = (0..lv.size - 20).map { i -> 10 * log10((i until i + 20).sumOf { lv[it] } / 20 + 1e-12) }
            val usual = sec.sorted()[(sec.size * .9).toInt().coerceAtMost(sec.size - 1)]
            sec.indexOfFirst { it >= usual - 6 }.coerceAtLeast(0) * 50L
        }
        return Ends(lastSound, handover, firstSound, tail, top, dur, tailKey, headKey, headLv.toFloatArray(), end.toFloatArray(), start, fullAt, fading)
    }

    internal const val RATE = 22_050

    /** A stretch decoded to mono floats at [RATE] (blocking; 20 s at most), and the song's length (ms). */
    internal fun decode(url: String, from: Double, length: Double): Pair<FloatArray, Long>? {
        val out = File.createTempFile("ink-ends", ".raw")
        try {
            val m = Mpv(mapOf(
                "ao" to "pcm", "ao-pcm-file" to out.path, "ao-pcm-waveheader" to "no", "untimed" to "yes",
                "vid" to "no", "audio-display" to "no", "terminal" to "no", "idle" to "no", "ytdl" to "no",
                "replaygain" to "no", "hr-seek" to "yes", "cache" to "yes",
                "start" to "%.3f".format(java.util.Locale.ROOT, from), "length" to "%.3f".format(java.util.Locale.ROOT, length),
                "af" to "lavfi=[aformat=sample_fmts=flt:channel_layouts=mono:sample_rates=$RATE]",
            ))
            var dur = 0L
            try {
                m.observe("duration", Mpv.DOUBLE)
                m.command("loadfile", url)
                val until = System.currentTimeMillis() + 20_000
                loop@ while (System.currentTimeMillis() < until) {
                    when (val e = m.next(1.0)) {
                        is Mpv.Changed -> if (e.name == "duration") dur = ((e.value as? Double) ?: 0.0).times(1000).toLong().coerceAtLeast(dur)
                        is Mpv.Ended, is Mpv.Shutdown -> break@loop
                        else -> {}
                    }
                }
            } finally { m.close() }
            val bytes = out.readBytes()
            if (bytes.size < RATE * 4) return null
            val f = FloatArray(bytes.size / 4)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f)
            return f to dur
        } finally { out.delete() }
    }

    /** The level (dBFS) of each 50 ms. */
    private fun levels(p: FloatArray): List<Float> {
        val per = RATE / 20
        return (0 until p.size / per).map { w -> var sum = 0.0; for (k in w * per until (w + 1) * per) sum += p[k] * p[k]; (20 * log10(sqrt(sum / per).coerceAtLeast(1e-9))).toFloat() }
    }

    /** The beat over a stretch (Beat, shared with the phone: Prism's way, 2026-11-08). */
    private fun beats(p: FloatArray, startMs: Long): Beats? = Beat.find(p, RATE, startMs)?.let { Beats(it.period, it.first, it.sure, it.downbeat, it.bars) }
}
