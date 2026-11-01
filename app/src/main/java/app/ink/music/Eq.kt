package app.ink.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ink music's own equalizer: ten bands (31 Hz to 16 kHz, a graphic equalizer's, and AutoEq's fixed-band one) on
 * the player's sound through Android's DynamicsProcessing, set here and kept as a file both processes read (the
 * player applies it, with the app closed too). Levels in millibels. A preset is a shape in decibels laid over the
 * bands; a headphone's is AutoEq's correction for it; a band dragged makes it "yours".
 */
object Eq {
    /** The bands' centres (Hz), and how far each goes either way (mB). */
    val TEN = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val RANGE = 1200

    /**
     * On or not; two layers (2026-11-01: a shape chosen lost the headphones' correction, found again only by
     * searching): your headphones' correction (AutoEq's, for the model named in [phones]; kept, on or off), and a
     * shape on top ([preset]: flat, bass..., or "yours" once a band's been moved, [shape] its levels); [levels] the two
     * together, as the player applies them (mB a band).
     */
    data class State(
        val on: Boolean, val preset: String, val levels: List<Int>, val phones: String = "",
        val correction: List<Int> = emptyList(), val corrected: Boolean = false, val shape: List<Int> = emptyList(),
    ) {
        /** What it's called where it's said (the signal path): "nothing ear (1) + bass", or the one. */
        val label get() = listOfNotNull(phones.substringBefore(" · ").takeIf { corrected && it.isNotEmpty() }, preset.takeIf { it != "flat" })
            .joinToString(" + ").ifEmpty { "flat" }
    }

    /** The layers put together: the correction (if on) and the shape, a band at a time, inside the bands' range. */
    fun combined(correction: List<Int>, corrected: Boolean, shape: List<Int>, n: Int, b: Bands): List<Int> =
        List(n) { i -> ((if (corrected) correction.getOrElse(i) { 0 } else 0) + shape.getOrElse(i) { 0 }).coerceIn(b.min, b.max) }

    /** The phone's bands: each one's centre (Hz), and how far a band goes (mB) either way. */
    data class Bands(val hz: List<Int>, val min: Int, val max: Int)

    /** Shapes in dB from the lowest band to the highest, stretched over the phone's bands. */
    val presets = linkedMapOf(
        "flat" to listOf(0f, 0f, 0f, 0f, 0f),
        "bass" to listOf(6f, 4f, 0f, 0f, 0f),
        "warm" to listOf(3f, 2f, 0f, -1f, -2f),
        "vocal" to listOf(-2f, 0f, 3f, 3f, 0f),
        "bright" to listOf(0f, 0f, 0f, 3f, 5f),
        "loudness" to listOf(5f, 2f, -1f, 2f, 4f),
    )

    private fun file(c: Context) = File(c.filesDir, "equalizer.json")

    fun read(c: Context): State = runCatching {
        val o = JSONObject(file(c).readText())
        val a = o.getJSONArray("levels")
        fun ints(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getInt(it) } }.orEmpty()
        val levels = (0 until a.length()).map { a.getInt(it) }
        val preset = o.optString("preset", "flat")
        // kept before the layers: a headphones' correction was the whole of it
        if (preset == "phones" && !o.has("correction")) State(o.optBoolean("on"), "flat", levels, o.optString("phones"), levels, true, emptyList())
        else State(o.optBoolean("on"), preset, levels, o.optString("phones"), ints("correction"), o.optBoolean("corrected"), ints("shape"))
    }.getOrDefault(State(false, "flat", emptyList()))

    fun write(c: Context, s: State) {
        runCatching { file(c).writeText(JSONObject().put("on", s.on).put("preset", s.preset).put("levels", JSONArray(s.levels)).put("phones", s.phones)
            .put("correction", JSONArray(s.correction)).put("corrected", s.corrected).put("shape", JSONArray(s.shape)).toString()) }
    }

    /** A preset's levels on [n] bands, kept inside [b]'s range. */
    fun levels(preset: String, n: Int, b: Bands): List<Int> {
        val shape = presets[preset] ?: return List(n) { 0 }
        return List(n) { i ->
            val at = if (n == 1) 0f else i * (shape.size - 1f) / (n - 1)
            val lo = at.toInt().coerceAtMost(shape.size - 1); val hi = (lo + 1).coerceAtMost(shape.size - 1)
            val db = shape[lo] + (shape[hi] - shape[lo]) * (at - lo)
            (db * 100).toInt().coerceIn(b.min, b.max)
        }
    }

    fun bandsJson(b: Bands): String = JSONObject().put("hz", JSONArray(b.hz)).put("min", b.min).put("max", b.max).toString()

    fun bandsOf(text: String?): Bands? = runCatching {
        val o = JSONObject(text ?: return null)
        val a = o.getJSONArray("hz")
        Bands((0 until a.length()).map { a.getInt(it) }, o.getInt("min"), o.getInt("max"))
    }.getOrNull()

    // headphones, from AutoEq (github.com/jaakkopasanen/AutoEq, MIT): each model's correction toward a neutral sound,
    // worked out from published measurements (oratory1990, crinacle, Rtings...). Its list is read once (asked for when
    // someone first looks for theirs) and kept; a model's ten bands read as it's picked

    private const val AUTOEQ = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"

    /** A headphone as AutoEq lists it: its name, whose measurement, and where its files are. */
    data class Phones(val name: String, val by: String, val path: String)

    @Volatile private var index: List<Phones>? = null

    /** Every headphone AutoEq has (blocking: read once, kept on the phone). */
    fun phones(c: Context): List<Phones> {
        index?.let { return it }
        val f = File(c.filesDir, "autoeq-index.md")
        val text = runCatching { f.readText() }.getOrNull()?.takeIf { it.length > 10_000 }
            ?: IndieChart.get(AUTOEQ + "INDEX.md").also { runCatching { f.writeText(it) } }
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
        val name = android.net.Uri.decode(p.path.substringAfterLast('/'))
        val text = IndieChart.get(AUTOEQ + folder + "/" + android.net.Uri.encode("$name FixedBandEQ.txt"))
        val gains = Regex("""Fc (\d+) Hz Gain (-?[\d.]+) dB""").findAll(text).associate { it.groupValues[1].toInt() to (it.groupValues[2].toFloat() * 100).toInt() }
        TEN.map { hz -> (gains[hz] ?: 0).coerceIn(-RANGE, RANGE) }.takeIf { gains.size >= 8 }
    }.onFailure { android.util.Log.d("ink-eq", "correction ${p.name}: $it") }.getOrNull()

    /** "60", "910", "3.6k", "14k": a band's centre as a word. */
    fun said(hz: Int): String = when {
        hz >= 10_000 -> "${hz / 1000}k"
        hz >= 1000 -> "%.1fk".format(java.util.Locale.US, hz / 1000f).removeSuffix(".0k").let { if (it.endsWith("k")) it else it + "k" }
        else -> "$hz"
    }
}
