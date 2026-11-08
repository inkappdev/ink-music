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
                    val tailKey: Key.Found? = null, val headKey: Key.Found? = null)

    /** A handover planned: the old song's time it begins at, the new one's start, how long, the old one's tempo then. */
    private data class Plan(
        val from: String, val into: String, val cut: Long, val start: Long, val fade: Long,
        val speed: Double = 1.0, val a0: Double = 0.0, val b0: Double = 0.0,
        val period: Double = 0.0, val bpmFrom: Double = 0.0,
        /** The filter fade (the phone's, 2026-11-08): the old one's top closing, the new one opening from thin. */
        val filter: Boolean = false,
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
        return Plan(s.id, n.id, cut, en.firstSound, fade, filter = mode.value == AUTOMIX)
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
        val a0 = outro ?: a.barNear(e.lastSound - 16 * pa).let { if (it > latest) it - 4 * a.period else it }
        val cut = (a0 - speed * b0).toLong()
        val fade = kotlin.math.min(16 * b.period, (e.lastSound - cut) / speed).toLong().coerceIn(4_000, 14_000)
        if (cut < e.duration - 25_000 + 500 || cut < a.first) return null
        // keys clashing (both sure): the same four bars, the old one sinking under the filter fade's low-pass
        val clash = Key.clash(e.tailKey, next.headKey)
        Log.d("crossfade: ${s.title} into ${n.title}: beats matched, %.1f into %.1f bpm, at %.3fx, out at %.2fs (%s), in on bar one at %.2fs, keys %s into %s%s".format(
            a.bpm, b.bpm, speed, a0 / 1000, if (outro != null) "its outro" else "a bar start", b0 / 1000, e.tailKey?.camelot, next.headKey?.camelot, if (clash) ", clashing" else ""))
        return Plan(s.id, n.id, cut, 0, fade, speed, a0, b0, b.period, a.bpm, filter = clash)
    }

    /** The handover: the new deck sounding, rising; the old one on to its end, falling away; the player the new one's. */
    private fun go(a: Mpv, b: Mpv, p: Plan, nextAt: Int, from: Song) {
        if (Player.queue.value.getOrNull(nextAt)?.id != p.into) return drop()
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
        Log.d("crossfade: into ${p.into} at ${p.cut / 1000.0}s over ${p.fade / 1000.0}s, from ${p.start} ms in" + if (p.matched) ", beats matched" else "")
        Thread({ fade(a, b, p) }, "crossfade-fade").apply { isDaemon = true; start() }
    }

    private fun fade(a: Mpv, b: Mpv, p: Plan) {
        val began = System.nanoTime()
        var bassAcross = false
        while (fadingOut === a) {
            val t = ((System.nanoTime() - began) / 1e6 / p.fade).coerceIn(0.0, 1.0)
            val v = if (Player.muted.value) 0.0 else Player.volume.value.toDouble()
            // equal power: the levels' squares sum to one; mpv's volume is the level's cube root
            a.set("volume", "%.2f".format(java.util.Locale.ROOT, v * cbrt(cos(t * PI / 2))))
            b.set("volume", "%.2f".format(java.util.Locale.ROOT, v * cbrt(sin(t * PI / 2))))
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
        finish(a, b)
    }

    @Synchronized private fun finish(a: Mpv, b: Mpv) {
        if (fadingOut !== a) return
        fadingOut = null; fadingIn = null
        mixing.value = null
        a.command("stop"); a.set("pause", "yes"); a.set("speed", "1")
        Player.chainOn(a)
        b.command("af-command", "ink", "f", "10", "highpass@bass"); b.command("af-command", "ink", "f", "20000", "lowpass@top")
        b.set("volume", Player.volume.value.toString())
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
        }
    }

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
        val handover = listOfNotNull(lastSound - FADE, quiet?.minus(1_000)).minOrNull()!!.coerceAtLeast(start)
        val firstSound = head?.let { levels(it) }?.indexOfFirst { it > -50f }?.takeIf { it > 0 }?.let { it * window }?.takeIf { it >= 400 } ?: 0L
        val tail = beats(endPcm, start); val top = head?.let { beats(it, 0) }
        val tailKey = Key.find(endPcm, RATE); val headKey = head?.let { Key.find(it, RATE) }
        Log.d("crossfade: ${s.title}: ${dur / 1000}s, sound to ${lastSound / 1000.0}s, quiet from ${quiet?.div(1000.0)}, hand over at ${handover / 1000.0}s, " +
            "starts ${firstSound}ms in; beat at its end ${tail?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}, at its start ${top?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}")
        return Ends(lastSound, handover, firstSound, tail, top, dur, tailKey, headKey)
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
