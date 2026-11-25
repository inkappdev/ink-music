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
    // a new install (nothing heard yet) begins in the crossfade (2026-11-15, Bronson; automix had been since 2026-11-11, a
    // tester's "add your songs, hit shuffle", and surprised people: it lets long endings go early); automix a word away,
    // beside the volume. One who turned it off keeps it off
    val mode = MutableStateFlow(runCatching { file.readText().trim() }.getOrNull()?.let { if (it == AUTOMIX) AUTOMIX else CROSSFADE }
        ?: if (!File(Home.dir, "heard.jsonl").exists()) CROSSFADE.also { runCatching { file.writeText(it) } } else OFF)

    /** Chosen by you: kept (a mix's lend forgotten). */
    fun set(m: String) { lent.delete(); use(m) }

    /**
     * A record played in order mixed too (2026-11-25, #14: a listener's albums never blended, only lists and mixes did);
     * off, as till then, its songs run into each other as made.
     */
    private val recordsFile = File(Home.dir, "crossfade-records")
    val records = MutableStateFlow(recordsFile.exists())
    fun records(on: Boolean) { runCatching { if (on) recordsFile.createNewFile() else recordsFile.delete() }; records.value = on }

    /**
     * A mix's automix, lent (2026-11-17, a tester: a mix had turned theirs to automix for good): what was chosen kept
     * aside, and given back when something else is played ([giveBack]), or chosen again by hand. Kept as a file, so a
     * mix the app was quit in still gives it back.
     */
    private val lent = File(Home.dir, "crossfade-lent")
    @Volatile var inMix = false
    fun lend() {
        if (mode.value == AUTOMIX) return
        runCatching { lent.writeText(mode.value) }
        use(AUTOMIX)
    }
    fun giveBack() {
        if (inMix || !lent.exists()) return
        val was = runCatching { lent.readText().trim() }.getOrNull()
        lent.delete()
        if (was != null && was != mode.value) use(was)
    }

    private fun use(m: String) {
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
    /** A cold ending's blend; the next one starting cold too, half a second (the phone's, after LastWave's). */
    private const val COLD = 2_000L
    /**
     * Each song's level at `k` (0 to 1) through a blend, new then old (the phone's Blend.gains); the vocal check reads
     * the same. Beats matched: both up, then the swap, then out (the new one full by the middle, the old one full till
     * it, falling after on a quarter cosine; the keeper holds their sum within 1 dB). Cold: the old one held nearly to
     * its end (1 - k^4), the new one rising under it, so its last hit isn't faded. Else equal power. A quiet intro
     * coming in (`hold`): the old one held up longer, the new one rising sooner.
     */
    internal fun gains(k: Double, hold: Double, matched: Boolean, cold: Boolean): Pair<Double, Double> {
        if (k >= 1) return 1.0 to 0.0
        if (cold) return sin(k * PI / 2) to (1 - k * k * k * k)
        val kOld = if (hold > 0) ((k - .8 * hold) / (1 - .8 * hold)).coerceIn(0.0, 1.0) else k
        val kNew = if (hold > 0) (k / (1 - .5 * hold)).coerceIn(0.0, 1.0) else k
        if (matched) return sin((2 * kNew).coerceAtMost(1.0) * PI / 2) to (if (kOld < .5) 1.0 else cos((2 * kOld - 1) * PI / 2))
        return sin(kNew * PI / 2) to cos(kOld * PI / 2)
    }
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
                    val fullAt: Long = 0, val fading: Long? = null,
                    /** It ends cold (its last second within 6 dB of its usual, no fade of its own); it starts cold (full within half a second of its first sound). */
                    val cold: Boolean = false, val startsCold: Boolean = false)

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
        /** The old one ends cold: held at full nearly to its end, the new one under it, no filter fade. */
        val cold: Boolean = false,
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
    /** The song readied for the next transition, if one is. */
    internal fun readied(): String? = readyFor
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
        // beats matched: in on its mids, its top held under 2.5 kHz too (opened by the middle: fade)
        if (p?.matched == true) { m.command("af-command", "ink", "f", "200", "highpass@bass"); m.command("af-command", "ink", "f", "2500", "lowpass@top") }
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
        // a record played in order (the queue that record alone): its songs run into each other as they were made. Not
        // a list that only happens to hold a record's songs in turn (2026-11-15, a tester: no fades after shuffle was
        // turned off mid-song, more so with files: the queue's own order back, your library's songs a record at a time)
        if (!records.value && s.albumId.isNotEmpty() && s.albumId == n.albumId && nextAt == i + 1 && n.track > s.track &&
            Player.queue.value.all { it.albumId == s.albumId }) return false
        val d = Player.length().takeIf { it > 0 } ?: s.duration
        return d > FADE * 3 && (n.duration == 0L || n.duration > FADE * 3)
    }

    private fun plan(s: Song, n: Song, es: Ends, en: Ends): Plan? {
        // previewing (Preview): handed over at this taste's end, a short fade, the next from its own taste (2026-11-17, a
        // tester: from one taste to the next was abrupt). Automix's filter fade, beats not matched mid-song
        Preview.endOf(s, es.duration)?.let { end ->
            val fade = 3_000L
            return Plan(s.id, n.id, (end - fade / 2).coerceAtLeast(0), Preview.startOf(n.id, en.duration), fade, filter = mode.value == AUTOMIX)
        }
        if (mode.value == AUTOMIX) matched(s, n, es, en)?.let { return it }
        // ending cold (the phone's, after LastWave's): the old one held to its last 2 s (half a second when the new one
        // starts cold too), its last hit heard whole, no filter fade
        if (es.cold) {
            val fade = if (en.startsCold) 500L else COLD
            return Plan(s.id, n.id, (es.lastSound - fade).coerceAtLeast(0), en.firstSound, fade, cold = true)
        }
        var cut = es.handover
        // not beats matched, in automix: the filter fade, short when their keys clash (both sure)
        val clash = Key.clash(es.tailKey, en.headKey)
        var fade = (es.lastSound - cut).coerceIn(2_000, 12_000).let { if (clash) it.coerceAtMost(5_000) else it }
        // no voice over another in a plain blend either (automix; the phone's): of its handover and the end of a line it's
        // in the middle of (3 s of fade kept), and the fade shortened a second at a time (2 s at least), the plan that
        // clashes least; both singing over it still, 4 s at most. The old one heard from the cut, the new one from its
        // first sound (where its deck was sought to)
        val vo = vocals[s.id]; val vn = vocals[n.id]
        if (mode.value == AUTOMIX && !vo.isNullOrEmpty() && !vn.isNullOrEmpty()) {
            val hold0 = ((en.fullAt - en.firstSound).toDouble() / fade).coerceIn(0.0, .7)
            fun check(a: Long, f: Long) = Vocals.check(vo, a.toDouble(), 1.0, vn, en.firstSound.toDouble(), f) { k -> gains(k, hold0, false, false) }
            val was = check(cut, fade)
            if (!was.clean) {
                val lineEnd = vo.firstOrNull { cut in it }?.last?.plus(100)?.takeIf { it <= es.lastSound - 3_000 }
                val tries = listOfNotNull(cut, lineEnd).flatMap { a ->
                    val most = (es.lastSound - a).coerceIn(2_000, fade)
                    generateSequence(most) { it - 1_000 }.takeWhile { it >= 2_000 }.map { a to it }.toList()
                }.filter { (a, _) -> a >= es.tailFrom }
                val best = tries.minByOrNull { (a, f) -> check(a, f).cost + (fade - f) / 4_000.0 }
                if (best != null) {
                    val bc = check(best.first, best.second)
                    cut = best.first; fade = if (bc.clash > Vocals.MOST) best.second.coerceAtMost(4_000) else best.second
                    Log.d("crossfade: vocals (plain): ${was.clash} ms both singing${if (was.cutsLine) ", a line cut" else ""}${if (was.hidesEntry) ", its first line hidden" else ""}; " +
                        "out at ${cut / 1000.0}s over ${fade / 1000.0}s instead (${bc.clash} ms)")
                }
            }
        }
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
        // the new one's drop landing as the old one leaves (the phone's, after LastWave's): a bar of its start where it
        // rises (its next two bars 2 dB or more over the two before), two bars or more after bar one, the blend's end put
        // there when it falls within the blend's bounds (the new deck plays from its start: its time is the blend's)
        val bounds = 4_000.0..kotlin.math.min(if (fit) 20_000.0 else 14_000.0, (e.lastSound - cut) / speed)
        val drop = b.bars.withIndex().firstOrNull { (j, br) ->
            j >= 2 && j + 1 < b.bars.size && br.at >= b0 + 2 * bar && br.at in bounds &&
                (b.bars[j].loud + b.bars[j + 1].loud) >= 1.26f * (b.bars[j - 1].loud + b.bars[j - 2].loud)
        }?.value?.at
        if (drop != null && b.sure >= LOOSE) {
            Log.d("crossfade: the new one rises at %.2fs: the blend ends there (%.1fs as it was)".format(drop / 1000, fade / 1000.0))
            fade = drop.toLong()
        }
        // no voice over another (the phone's, after LastWave's automix): through the blend's own gains (both up, each at
        // 0.35 or more), a line cut or one hidden counted too; the blend shorter a bar at a time (4 s at least), else out
        // a bar or two earlier or later; the first plan that's clean, else the one that clashes least. The blend runs
        // from the cut and the new one's start: out at `o`, the old one's time there is o - speed * b0, the new one's 0
        val vo = vocals[s.id]; val vn = vocals[n.id]
        if (!vo.isNullOrEmpty() && !vn.isNullOrEmpty()) {
            val hold0 = ((next.fullAt - b0) / fade).coerceIn(0.0, .7)
            fun check(o: Double, f: Long) = Vocals.check(vo, o - speed * b0, speed, vn, 0.0, f) { k -> gains(k, hold0, true, false) }
            val wasCheck = check(out, fade)
            val was = wasCheck.clash
            if (!wasCheck.clean) {
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
                val safe = tries.firstOrNull { (o, f) -> check(o, f).clean }
                    ?: tries.minByOrNull { (o, f) -> check(o, f).cost }?.takeIf { (o, f) -> check(o, f).cost < wasCheck.cost }
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
        Log.d("crossfade: into ${p.into} at ${p.cut / 1000.0}s over ${p.fade / 1000.0}s, from ${p.start} ms in" + (if (p.matched) ", beats matched" else "") + (if (p.cold) ", ending cold" else "") +
            (if (p.hold > .05) ", the old one held over ${"%.0f".format(p.hold * 100)}%" else "") + (if (p.rideDb < -.5) ", the new one ridden in ${"%.1f".format(p.rideDb)} dB" else ""))
        Thread({ fade(a, b, p) }, "crossfade-fade").apply { isDaemon = true; start() }
    }

    private fun fade(a: Mpv, b: Mpv, p: Plan) {
        val began = System.nanoTime()
        var bassAcross = false
        var topOpen = false
        // the new deck's top at its fullest: 20 kHz, kept under its sample rate's Nyquist
        val top = b.get("audio-params/samplerate")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { kotlin.math.min(20_000.0, it * .45) } ?: 20_000.0
        val eo = ends[p.from]; val en = ends[p.into]
        val ride0 = Math.pow(10.0, p.rideDb / 20)
        while (fadingOut === a) {
            val ms = (System.nanoTime() - began) / 1e6
            val t = (ms / p.fade).coerceIn(0.0, 1.0)
            val v = if (Player.muted.value) 0.0 else Player.volume.value.toDouble()
            // equal power (beats matched: both up, then the swap, then out; cold: the old one held nearly to its end); a
            // quiet intro coming in, the old one held at full over most of it, the new one rising sooner
            val (g0New, gOld) = gains(t, p.hold, p.matched, p.cold)
            // the new one ridden up from the old end's level, over the fade and 4 s after
            val ride = ride0 + (1 - ride0) * (ms / (p.fade + 4_000)).coerceIn(0.0, 1.0)
            val gNew = g0New * ride
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
                // the new one's top opening from 2.5 kHz to all of it by the middle (under its Nyquist), then left open
                if (!topOpen) {
                    val f = (2_500.0 * Math.pow(8.0, (t / .5).coerceIn(0.0, 1.0))).coerceAtMost(top)
                    b.command("af-command", "ink", "f", "%.0f".format(java.util.Locale.ROOT, f), "lowpass@top")
                    if (t >= .5) topOpen = true
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

    /** The songs playing and next: the only ones worth measuring now, and never let go to make room. */
    private fun near(): Set<String> {
        val q = Player.queue.value; val i = Player.index()
        return setOfNotNull(q.getOrNull(i)?.id, q.getOrNull(i + 1)?.id, q.getOrNull(0)?.id.takeIf { Player.repeat.value == 1 })
    }

    /** A song whose measuring came to nothing: how many times; tried again a little later, three times in all. */
    private val missed = ConcurrentHashMap<String, Int>()

    private fun measure(s: Song) {
        // room made from songs gone by, never the one playing or the next (2026-11-24, a tester: now and then no transition,
        // the next one fine: twenty were let go as they came out of the map, the ones about to mix among them)
        if (ends.size > 40) { val keep = near(); ends.keys.filter { it !in keep }.take(20).forEach { ends.remove(it); asked.remove(it) } }
        if (!asked.add(s.id)) return
        work.execute {
            // skipped past while it waited its turn: not measured now (a run of skips had the next song measured too late)
            if (s.id !in near()) { asked.remove(s.id); return@execute }
            val found = runCatching { ends(s) }.onFailure { Log.d("crossfade: ${s.title}: $it") }.getOrNull()
            if (found != null) { ends[s.id] = found; missed.remove(s.id) }
            else {
                // a stream not found in time, a decode cut off: tried again in 15 s, up to three times (it had been asked once for
                // good, so that song never mixed in or out)
                val n = (missed[s.id] ?: 0) + 1
                missed[s.id] = n
                if (n < 3) Thread { Thread.sleep(15_000); asked.remove(s.id) }.apply { isDaemon = true }.start()
            }
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
        // a cold ending (the phone's, after LastWave's): its last second within 6 dB of its usual (the loud tenth of the
        // end's seconds), so it stops on a full hit, a chord or a shout; handed over 2 s before its sound ends, its last
        // hit heard whole (a 6 s fade had sunk it under the next song)
        val cold = lastIdx >= 200 && fading == null && run {
            val pw = end.map { Math.pow(10.0, it / 10.0) }
            val sec = (0..lastIdx - 19).map { i -> 10 * log10((i until i + 20).sumOf { pw[it] } / 20 + 1e-12) }
            val usual = sec.sorted()[(sec.size * .9).toInt().coerceAtMost(sec.size - 1)]
            sec.last() >= usual - 6
        }
        val handover = (if (cold) lastSound - COLD else listOfNotNull(lastSound - FADE, quiet?.minus(1_000), fading?.minus(500)).minOrNull()!!)
            .coerceAtLeast(start)
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
        // it starts cold: its first sound within half a second, full within half a second after
        val startsCold = head != null && firstSound < 500 && fullAt <= firstSound + 500
        if (cold || startsCold) Log.d("crossfade: ${s.title}: ${if (cold) "ends cold" else ""}${if (cold && startsCold) ", " else ""}${if (startsCold) "starts cold" else ""}")
        return Ends(lastSound, handover, firstSound, tail, top, dur, tailKey, headKey, headLv.toFloatArray(), end.toFloatArray(), start, fullAt, fading, cold, startsCold)
    }

    internal const val RATE = 22_050

    /** A stretch decoded to mono floats at [RATE] (blocking; 20 s at most), and the song's length (ms). */
    internal fun decode(url: String, from: Double, length: Double, waitMs: Long = 20_000, rate: Int = RATE): Pair<FloatArray, Long>? {
        val out = File.createTempFile("ink-ends", ".raw")
        try {
            val m = Mpv(mapOf(
                "ao" to "pcm", "ao-pcm-file" to out.path, "ao-pcm-waveheader" to "no", "untimed" to "yes",
                "vid" to "no", "audio-display" to "no", "terminal" to "no", "idle" to "no", "ytdl" to "no",
                "replaygain" to "no", "hr-seek" to "yes", "cache" to "yes",
                "start" to "%.3f".format(java.util.Locale.ROOT, from), "length" to "%.3f".format(java.util.Locale.ROOT, length),
                "af" to "lavfi=[aformat=sample_fmts=flt:channel_layouts=mono:sample_rates=$rate]",
            ))
            var dur = 0L
            try {
                m.observe("duration", Mpv.DOUBLE)
                m.command("loadfile", url)
                val until = System.currentTimeMillis() + waitMs
                loop@ while (System.currentTimeMillis() < until) {
                    when (val e = m.next(1.0)) {
                        is Mpv.Changed -> if (e.name == "duration") dur = ((e.value as? Double) ?: 0.0).times(1000).toLong().coerceAtLeast(dur)
                        is Mpv.Ended, is Mpv.Shutdown -> break@loop
                        else -> {}
                    }
                }
            } finally { m.close() }
            val bytes = out.readBytes()
            if (bytes.size < rate * 4) return null
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
