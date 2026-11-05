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
    private const val SURE = 1.6
    /** The new deck started this far ahead of the handover: it's heard a moment after it's told to play. */
    private const val LEAD = 90L

    /** A song's beat over a stretch: its period (ms), one beat's time (ms into the song), how sure (peak over mean). */
    data class Beats(val period: Double, val first: Double, val sure: Double) {
        val bpm get() = 60_000 / period
        fun after(t: Double) = first + Math.ceil((t - first) / period) * period
    }

    /** Where a song's sound ends (ms), where its handover begins, where its sound begins; its beat at its end and start. */
    data class Ends(val lastSound: Long, val handover: Long, val firstSound: Long, val tail: Beats?, val head: Beats?, val duration: Long)

    /** A handover planned: the old song's time it begins at, the new one's start, how long, the old one's tempo then. */
    private data class Plan(
        val from: String, val into: String, val cut: Long, val start: Long, val fade: Long,
        val speed: Double = 1.0, val a0: Double = 0.0, val b0: Double = 0.0,
    ) { val matched get() = speed != 1.0 || a0 != 0.0 }

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
        if (pos > p.cut + 1_500) return   // past it (a seek): ends as it would
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
        if (readyFor == n.id && loaded && pos >= p.cut - LEAD && !Player.isPaused()) go(a, spare, p, nextAt)
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
        return Plan(s.id, n.id, cut, en.firstSound, (es.lastSound - cut).coerceIn(2_000, 12_000))
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
        if (a.sure < SURE || b.sure < SURE) return null
        val pa = listOf(1.0, 2.0, .5).map { a.period * it }.minByOrNull { abs(it / b.period - 1) }!!
        val speed = pa / b.period
        if (abs(speed - 1) > .08) return null
        val b0 = b.after(0.0)
        val a0 = a.after(e.lastSound - 16 * pa)
        val cut = (a0 - speed * b0).toLong()
        val fade = kotlin.math.min(16 * b.period, (e.lastSound - cut) / speed).toLong().coerceIn(4_000, 14_000)
        if (cut < e.duration - 25_000 + 500 || cut < a.first) return null
        Log.d("crossfade: ${s.title} into ${n.title}: beats matched, %.1f into %.1f bpm, at %.3fx".format(a.bpm, b.bpm, speed))
        return Plan(s.id, n.id, cut, 0, fade, speed, a0, b0)
    }

    /** The handover: the new deck sounding, rising; the old one on to its end, falling away; the player the new one's. */
    private fun go(a: Mpv, b: Mpv, p: Plan, nextAt: Int) {
        if (Player.queue.value.getOrNull(nextAt)?.id != p.into) return drop()
        fadingOut = a; fadingIn = b
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
            if (t >= 1.0) break
            Thread.sleep(25)
        }
        finish(a, b)
    }

    @Synchronized private fun finish(a: Mpv, b: Mpv) {
        if (fadingOut !== a) return
        fadingOut = null; fadingIn = null
        a.command("stop"); a.set("pause", "yes"); a.set("speed", "1")
        Player.chainOn(a)
        b.command("af-command", "ink", "f", "10", "highpass@bass")
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
        Log.d("crossfade: ${s.title}: ${dur / 1000}s, sound to ${lastSound / 1000.0}s, quiet from ${quiet?.div(1000.0)}, hand over at ${handover / 1000.0}s, " +
            "starts ${firstSound}ms in; beat at its end ${tail?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}, at its start ${top?.let { "%.1f bpm (%.1f sure)".format(it.bpm, it.sure) }}")
        return Ends(lastSound, handover, firstSound, tail, top, dur)
    }

    private const val RATE = 22_050

    /** A stretch decoded to mono floats at [RATE] (blocking; 20 s at most), and the song's length (ms). */
    private fun decode(url: String, from: Double, length: Double): Pair<FloatArray, Long>? {
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

    /**
     * The beat (the phone's): an onset curve from the low end (a kick's rise; 10 ms steps), its period the lag it most
     * repeats at (with its double, so a half-tempo doesn't win), 80 to 160 bpm, refined between steps; the grid laid
     * where the onsets fall most. How sure: the period's repeat over the average repeat.
     */
    private fun beats(p: FloatArray, startMs: Long): Beats? {
        val hop = RATE / 100
        val n = p.size / hop
        if (n < 600) return null
        val a = (1 - Math.exp(-2 * PI * 150 / RATE)).toFloat()
        var y = 0f
        val energy = DoubleArray(n)
        for (k in 0 until n) { var e = 0.0; for (i in k * hop until (k + 1) * hop) { y += a * (p[i] - y); e += y * y }; energy[k] = e / hop }
        val onset = DoubleArray(n)
        for (k in 1 until n) onset[k] = kotlin.math.max(0.0, Math.log(energy[k] + 1e-10) - Math.log(energy[k - 1] + 1e-10))
        val avg = DoubleArray(n); var run = 0.0
        for (k in 0 until n) { run += onset[k]; if (k >= 50) run -= onset[k - 50]; avg[k] = run / kotlin.math.min(k + 1, 50) }
        for (k in 0 until n) onset[k] = kotlin.math.max(0.0, onset[k] - avg[k])
        fun ac(lag: Int): Double { var s = 0.0; for (k in 0 until n - lag) s += onset[k] * onset[k + lag]; return s / (n - lag) }
        val lags = (37..75)
        val acs = lags.associateWith { ac(it) }
        fun score(l: Int) = acs.getValue(l) + .5 * (if (2 * l <= n / 2) ac(2 * l) else 0.0)
        val best = lags.maxByOrNull(::score) ?: return null
        val mean = acs.values.average().takeIf { it > 0 } ?: return null
        val sure = acs.getValue(best) / mean
        val l0 = acs[best - 1] ?: acs.getValue(best); val l1 = acs.getValue(best); val l2 = acs[best + 1] ?: acs.getValue(best)
        val shift = (l0 - l2) / (2 * (l0 - 2 * l1 + l2)).let { if (it == 0.0) 1e9 else it }
        val lag = best + shift.coerceIn(-.5, .5)
        var bestO = 0; var bestS = -1.0
        for (o in 0 until lag.toInt() + 1) {
            var s = 0.0; var k = o.toDouble()
            while (k < n) { s += onset[k.toInt()]; k += lag }
            if (s > bestS) { bestS = s; bestO = o }
        }
        return Beats(lag * 10, startMs + bestO * 10.0, sure)
    }
}
