package app.ink.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A spectrum in the cover (2026-11-12, a tester, after foobar2000's spectrum analyzer; Bronson: yes): the song's
 * frequencies as columns of pixel blocks along the cover's foot, in the cover's own colour, moving with the music. The
 * song decoded once as it starts (a second mpv, as automix and tempos read it: mono at 22 kHz), its spectrum worked out
 * for every thirtieth of a second, then drawn in step with where the song is. Off at first (settings, the look).
 */
object Spectrum {
    private val file = File(Home.dir, "spectrum")
    val on = MutableStateFlow(file.exists())
    fun set(v: Boolean) { on.value = v; runCatching { if (v) file.writeText("") else file.delete() } }

    const val BANDS = 24
    const val FPS = 30
    private const val N = 1024
    private val kept = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, FloatArray>(4, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, FloatArray>) = size > 3
    })

    /** Each frame's bands, 0 to 1 (frames x [BANDS]); null when the song can't be read. Blocking: decoded and worked out. */
    fun of(song: Song): FloatArray? {
        kept[song.id]?.let { return it }
        if (song.station) return null
        val url = if (song.tube && Offline.file(song.id) == null) YouTube.stream(song.id, small = true) ?: return null else Player.where(song)
        if (url.isEmpty()) return null
        // in a minute's pieces, as far as the song goes
        val pcm = ArrayList<FloatArray>()
        var from = 0.0
        while (true) {
            val (part, dur) = Crossfade.decode(url, from, 60.0, 30_000) ?: break
            pcm += part
            from += 60.0
            if (part.size < Crossfade.RATE * 59 || (dur > 0 && from * 1000 >= dur)) break
            if (from > 1_200) break
        }
        if (pcm.isEmpty()) return null
        val all = FloatArray(pcm.sumOf { it.size }).also { var at = 0; pcm.forEach { p -> p.copyInto(it, at); at += p.size } }
        return bands(all).also { kept[song.id] = it }
    }

    /** The bands of every frame: a Hann-windowed FFT of 1024 samples, its bins gathered into [BANDS] bands from 40 Hz to 10 kHz on a log scale. */
    private fun bands(p: FloatArray): FloatArray {
        val hop = Crossfade.RATE / FPS
        val frames = (p.size - N).coerceAtLeast(0) / hop + 1
        val out = FloatArray(frames * BANDS)
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
        val edges = IntArray(BANDS + 1) { b ->
            val hz = 40.0 * Math.pow(10_000.0 / 40.0, b.toDouble() / BANDS)
            (hz * N / Crossfade.RATE).toInt().coerceIn(1, N / 2 - 1)
        }
        val re = DoubleArray(N); val im = DoubleArray(N)
        for (f in 0 until frames) {
            val start = f * hop
            for (i in 0 until N) { re[i] = (p.getOrElse(start + i) { 0f } * window[i]).toDouble(); im[i] = 0.0 }
            fft(re, im)
            for (b in 0 until BANDS) {
                val lo = edges[b]; val hi = maxOf(edges[b + 1], lo + 1)
                var sum = 0.0
                for (k in lo until hi) sum += re[k] * re[k] + im[k] * im[k]
                val db = 10 * log10(sum / (hi - lo) + 1e-12)
                // -10 dB to +40 dB here (the window's own scale), as 0 to 1; the highs lifted a little, as an analyzer does
                out[f * BANDS + b] = (((db + 10 + b * .6) / 50).coerceIn(0.0, 1.0)).toFloat()
            }
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * The spectrum drawn over the cover's foot: [Spectrum.BANDS] columns of square blocks, each band's level now (smoothed
 * between frames, falling slower than it rises, as a meter does), in `colour`, a little see-through.
 */
@Composable
fun SpectrumOver(song: Song, playing: Boolean, colour: Color, modifier: Modifier) {
    val on by Spectrum.on.collectAsState()
    if (!on || song.station) return
    val frames by produceState<FloatArray?>(null, song.id) { value = withContext(Dispatchers.IO) { runCatching { Spectrum.of(song) }.getOrNull() } }
    val f = frames ?: return
    val position by Player.position.collectAsState()
    // the position as it runs between the player's reports (a few a second), so the columns move every frame
    var clock by remember { mutableLongStateOf(0L) }
    val since = remember(position) { longArrayOf(System.currentTimeMillis()) }
    LaunchedEffect(playing) { while (playing) withFrameMillis { clock = it } }
    val shown = remember { FloatArray(Spectrum.BANDS) }
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val tick = clock
        val now = position + if (playing) (System.currentTimeMillis() - since[0]).coerceAtMost(500) else 0
        val frame = ((now * Spectrum.FPS) / 1000).toInt().coerceIn(0, f.size / Spectrum.BANDS - 1)
        val cols = Spectrum.BANDS
        val gap = size.width * .006f
        val cell = (size.width - gap * (cols + 1)) / cols
        val rows = ((size.height * .32f) / (cell + gap)).toInt().coerceAtLeast(4)
        for (b in 0 until cols) {
            val v = f[frame * cols + b]
            shown[b] = if (v > shown[b]) v else shown[b] * .9f + v * .1f
            val lit = (shown[b] * rows).toInt()
            val x = gap + b * (cell + gap)
            for (r in 0 until lit) {
                val y = size.height - gap - (r + 1) * (cell + gap) - size.height * .03f
                drawRect(colour.copy(alpha = .78f), Offset(x, y), Size(cell, cell))
            }
        }
    }
}
