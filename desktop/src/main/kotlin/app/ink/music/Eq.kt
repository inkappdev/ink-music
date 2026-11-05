package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * ink music's own equalizer, the phone's (Eq.kt there): ten bands, 31 Hz to 16 kHz (a graphic equalizer's, and
 * AutoEq's fixed-band one), in two layers: your headphones' correction (AutoEq's, for the model named) and a shape on
 * top (flat, bass..., or "yours" once a band's been moved). Levels in millibels, kept in equalizer.json. Here it's
 * ffmpeg's own peaking filters in mpv's chain ([Player]), each band steered as it's dragged, no reload; the whole
 * turned down by its biggest boost, so a boost never clips.
 */
object Eq {
    /** The bands' centres (Hz), and how far each goes either way (mB). */
    val TEN = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val RANGE = 1200
    /** The bands as the page draws them (the phone's are the phone's own; here always the ten). */
    val bands = Bands(TEN, -RANGE, RANGE)

    data class State(
        val on: Boolean = false, val preset: String = "flat", val levels: List<Int> = emptyList(), val phones: String = "",
        val correction: List<Int> = emptyList(), val corrected: Boolean = false, val shape: List<Int> = emptyList(),
    ) {
        /** What it's called where it's said (the signal path): "sennheiser hd 600 + bass", or the one. */
        val label get() = listOfNotNull(phones.substringBefore(" · ").lowercase().takeIf { corrected && it.isNotEmpty() }, preset.takeIf { it != "flat" })
            .joinToString(" + ").ifEmpty { "flat" }
        /** Whether it does anything at all (on, and some band off nought). */
        val shaping get() = on && levels.any { it != 0 }
    }

    data class Bands(val hz: List<Int>, val min: Int, val max: Int)

    /** The layers put together: the correction (if on) and the shape, a band at a time, inside the bands' range. */
    fun combined(correction: List<Int>, corrected: Boolean, shape: List<Int>): List<Int> =
        List(TEN.size) { i -> ((if (corrected) correction.getOrElse(i) { 0 } else 0) + shape.getOrElse(i) { 0 }).coerceIn(-RANGE, RANGE) }

    /** Shapes in dB from the lowest band to the highest, stretched over the bands (the phone's). */
    val presets = linkedMapOf(
        "flat" to listOf(0f, 0f, 0f, 0f, 0f),
        "bass" to listOf(6f, 4f, 0f, 0f, 0f),
        "warm" to listOf(3f, 2f, 0f, -1f, -2f),
        "vocal" to listOf(-2f, 0f, 3f, 3f, 0f),
        "bright" to listOf(0f, 0f, 0f, 3f, 5f),
        "loudness" to listOf(5f, 2f, -1f, 2f, 4f),
    )

    /** A preset's levels on the ten bands. */
    fun levels(preset: String): List<Int> {
        val shape = presets[preset] ?: return List(TEN.size) { 0 }
        val n = TEN.size
        return List(n) { i ->
            val at = i * (shape.size - 1f) / (n - 1)
            val lo = at.toInt().coerceAtMost(shape.size - 1); val hi = (lo + 1).coerceAtMost(shape.size - 1)
            ((shape[lo] + (shape[hi] - shape[lo]) * (at - lo)) * 100).toInt().coerceIn(-RANGE, RANGE)
        }
    }

    private val file = File(Home.dir, "equalizer.json")

    val state = MutableStateFlow(read())

    private fun read(): State = runCatching {
        val o = JSONObject(file.readText())
        fun ints(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getInt(it) } }.orEmpty()
        State(o.optBoolean("on"), o.optString("preset", "flat"), ints("levels"), o.optString("phones"), ints("correction"), o.optBoolean("corrected"), ints("shape"))
    }.getOrDefault(State())

    /** Set, kept, and heard at once. */
    fun set(s: State) {
        state.value = s
        Player.equalize(s)
        runCatching { Home.write(file, JSONObject().put("on", s.on).put("preset", s.preset).put("levels", JSONArray(s.levels)).put("phones", s.phones)
            .put("correction", JSONArray(s.correction)).put("corrected", s.corrected).put("shape", JSONArray(s.shape)).toString()) }
    }

    // ---- in mpv's chain: ffmpeg's peaking filters, one a band, each named so it's steered alone ----

    /** The whole turned down by the biggest boost (dB), so nothing clips. */
    fun preamp(levels: List<Int>) = -(levels.maxOrNull() ?: 0).coerceAtLeast(0) / 100.0

    /** The filters for these levels (Q 1.41, AutoEq's fixed-band one), the turn-down first. */
    fun filters(levels: List<Int>): List<String> =
        listOf("volume@eqpre=%.2fdB".format(java.util.Locale.ROOT, preamp(levels))) +
            TEN.mapIndexed { i, hz -> "equalizer@eq$i=f=$hz:t=q:w=1.41:g=%.2f".format(java.util.Locale.ROOT, levels.getOrElse(i) { 0 } / 100.0) }

    // ---- headphones, from AutoEq (github.com/jaakkopasanen/AutoEq, MIT): each model's correction toward a neutral
    // sound, from published measurements. Its list read once (when someone first looks for theirs) and kept ----

    private const val AUTOEQ = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"

    data class Phones(val name: String, val by: String, val path: String)

    @Volatile private var index: List<Phones>? = null

    /** Every headphone AutoEq has (blocking: read once, kept). */
    fun phones(): List<Phones> {
        index?.let { return it }
        val f = File(Home.cache, "autoeq-index.md")
        val text = runCatching { f.readText() }.getOrNull()?.takeIf { it.length > 10_000 }
            ?: IndieChart.get(AUTOEQ + "INDEX.md").also { runCatching { Home.write(f, it) } }
        val line = Regex("""^- \[(.+)]\(\./(.+)\) by (.+)$""")
        return text.lineSequence().mapNotNull { l -> line.find(l)?.let { Phones(it.groupValues[1], it.groupValues[3], it.groupValues[2]) } }
            .toList().also { index = it }
    }

    /** The ones whose name has every word typed, the best measured first (oratory1990's, then the rest), twenty. */
    fun find(all: List<Phones>, typed: String): List<Phones> {
        val words = typed.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val rank = listOf("oratory1990", "crinacle", "rtings")
        return all.filter { p -> words.all { p.name.lowercase().contains(it) } }
            .sortedWith(compareBy<Phones> { p -> rank.indexOfFirst { p.by.lowercase().startsWith(it) }.let { if (it < 0) rank.size else it } }.thenBy { it.name.length })
            .distinctBy { it.name }.take(20)
    }

    /** A headphone's correction (blocking): its ten bands (mB), from AutoEq's fixed-band equalizer. */
    fun correction(p: Phones): List<Int>? = runCatching {
        // the folder as the index gives it (spaces as %20, a "+" as itself: sent as %2B); its files named as it is
        val folder = p.path.replace("+", "%2B")
        val name = URLDecoder.decode(p.path.substringAfterLast('/').replace("+", "%2B"), "UTF-8")
        val file = URLEncoder.encode("$name FixedBandEQ.txt", "UTF-8").replace("+", "%20")
        val text = IndieChart.get("$AUTOEQ$folder/$file")
        val gains = Regex("""Fc (\d+) Hz Gain (-?[\d.]+) dB""").findAll(text).associate { it.groupValues[1].toInt() to (it.groupValues[2].toFloat() * 100).toInt() }
        TEN.map { hz -> (gains[hz] ?: 0).coerceIn(-RANGE, RANGE) }.takeIf { gains.size >= 8 }
    }.onFailure { Log.d("eq: correction ${p.name}: $it") }.getOrNull()

    /** "60", "910", "3.6k", "14k": a band's centre as a word. */
    fun said(hz: Int): String = when {
        hz >= 10_000 -> "${hz / 1000}k"
        hz >= 1000 -> "%.1fk".format(java.util.Locale.US, hz / 1000f).removeSuffix(".0k").let { if (it.endsWith("k")) it else it + "k" }
        else -> "$hz"
    }
}
