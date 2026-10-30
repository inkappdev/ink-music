package app.ink.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.roundToInt

/**
 * A silent looping clip (a record's moving cover), played by an mpv of its own and drawn by its software renderer into
 * pictures the window shows: no window of mpv's, the same on the Mac, Windows and Linux, and a shader can go over each
 * frame as over any picture. `side` is the frames' size, square (the clip scaled into it, as a cover is).
 */
class Video(url: String, private val side: Int) {
    private val mpv = Mpv(mapOf(
        "vo" to "libmpv", "aid" to "no", "mute" to "yes", "loop-file" to "inf", "hwdec" to "no",
        "terminal" to "no", "config" to "no", "load-scripts" to "no", "ytdl" to "no", "osc" to "no",
        "input-default-bindings" to "no", "keep-open" to "yes",
    ))
    private val ctx: Pointer
    // three frames in turn: one drawn into while the window shows another; mpv's "rgb0" leaves the fourth byte 0, read
    // as see-through by any effect over it (all of them came out black): Skia's 888X, that byte not read
    private val frames = List(3) { Bitmap().apply { allocPixels(ImageInfo(side, side, ColorType.RGB_888X, ColorAlphaType.OPAQUE)) } }
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    /** The latest frame, null till the first. */
    val frame: StateFlow<ImageBitmap?> = _frame
    @Volatile private var alive = true
    private val thread: Thread

    init {
        // mpv_render_param { int type; void *data } (16 bytes on a 64-bit machine), ended by type 0
        val api = Memory(8).apply { setString(0, "sw") }
        val params = Memory(48).apply { clear(); setInt(0, API_TYPE); setPointer(8, api) }
        val out = PointerByReference()
        check(mpv.api.mpv_render_context_create(out, mpv.h, params) >= 0) { "mpv's renderer wouldn't start" }
        ctx = out.value
        mpv.commandAsync("loadfile", url)
        thread = Thread({ run() }, "ink-video").apply { isDaemon = true; start() }
    }

    private fun run() {
        val size = Memory(8).apply { setInt(0, side); setInt(4, side) }
        val format = Memory(8).apply { setString(0, "rgb0") }
        val stride = Memory(8)
        val params = Memory(16L * 5)
        var n = 0
        while (alive) {
            // its events let go of (no one waits on them), then a frame drawn if one's due
            while (mpv.next(0.0) != null) Unit
            if (mpv.api.mpv_render_context_update(ctx) and 1L == 0L) { Thread.sleep(8); continue }
            val b = frames[n % frames.size]
            val pixels = b.peekPixels() ?: break
            stride.setLong(0, b.rowBytes.toLong())
            params.clear()
            params.setInt(0, SW_SIZE); params.setPointer(8, size)
            params.setInt(16, SW_FORMAT); params.setPointer(24, format)
            params.setInt(32, SW_STRIDE); params.setPointer(40, stride)
            params.setInt(48, SW_POINTER); params.setPointer(56, Pointer(pixels.addr))
            if (mpv.api.mpv_render_context_render(ctx, params) >= 0) {
                b.notifyPixelsChanged()
                _frame.value = b.asComposeImageBitmap()
                n++
            }
        }
    }

    /** Its speed (1 as made); nothing, held still. */
    fun speed(v: Float) {
        if (v < .05f) mpv.set("pause", "yes") else { mpv.set("speed", "%.3f".format(java.util.Locale.ROOT, v)); mpv.set("pause", "no") }
    }

    fun close() {
        alive = false
        thread.join(500)
        mpv.api.mpv_render_context_free(ctx)
        // its frames left to the collector: one may still be on screen a moment (freed here, it crashed the JVM)
        mpv.close()
    }

    companion object {
        /**
         * One of Apple's versions of the clip: H.264 (every computer decodes it), the smallest at least `side` a side,
         * else the largest. ffmpeg given the whole list opens every version first (five seconds before a frame).
         */
        fun pick(master: String, side: Int): String = runCatching {
            val lines = java.net.URL(master).readText().lines()
            val made = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF") && "avc1" in it.value }.mapNotNull { (i, l) ->
                val wide = Regex("RESOLUTION=(\\d+)x").find(l)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
                val uri = lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapNotNull null
                wide to java.net.URI(master).resolve(uri).toString()
            }.sortedBy { it.first }
            (made.firstOrNull { it.first >= side } ?: made.lastOrNull())?.second
        }.getOrNull() ?: master

        private const val API_TYPE = 1; private const val SW_SIZE = 17; private const val SW_FORMAT = 18; private const val SW_STRIDE = 19; private const val SW_POINTER = 20
    }
}

/**
 * The record's moving cover over its still one, once its first frame is drawn (till then, and where there's none, the
 * still one shows), in the cover's own effect (the look: dither, pixels, fluted glass), else as it is.
 */
@Composable
fun MovingCover(artist: String, album: String, title: String, accent: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val on by Moving.on.collectAsState()
    val shape by Moving.cover.collectAsState()
    val url by produceState<String?>(null, artist, album, title, on) {
        value = if (on) withContext(Dispatchers.IO) { if (album.isNotBlank()) MotionCover.of(artist, album) else MotionCover.ofSong(artist, title) } else null
        Log.d("moving cover for $artist / $album: ${if (value != null) "found" else "none"}")
    }
    val u = url ?: return
    Loop(u, playing, shape, accent, modifier)
}

/**
 * A clip looping in the cover's place: it comes in as a print develops (dithered: the brightest dots first; else
 * faded in), and settles with the music: paused, it slows to a stop over a second and holds; played, it picks up.
 */
@Composable
private fun Loop(url: String, playing: Boolean, shape: Look?, accent: Color, modifier: Modifier) {
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.clipToBounds()) {
        val px = (constraints.maxWidth.coerceAtMost(constraints.maxHeight)).coerceIn(64, 1024)
        val one by produceState<String?>(null, url, px) { value = withContext(Dispatchers.IO) { Video.pick(url, px) } }
        val u = one ?: return@BoxWithConstraints
        val video = remember(u, px) { runCatching { Video(u, px) }.onFailure { Log.d("moving cover: $it") }.getOrNull() } ?: return@BoxWithConstraints
        DisposableEffect(video) { onDispose { video.close() } }
        val frame by video.frame.collectAsState()
        val speed by animateFloatAsState(if (playing) 1f else 0f, tween(1_000, easing = LinearEasing), label = "settle")
        LaunchedEffect(video) { snapshotFlow { speed }.collect { video.speed(it) } }
        val shown = frame != null
        val come by animateFloatAsState(if (shown) 1f else 0f, if (shown) tween(if (shape == Look.DITHER) 1_400 else 600) else snap(), label = "come")
        val f = frame ?: return@BoxWithConstraints
        Image(f, null, Modifier.fillMaxSize().graphicsLayer {
            alpha = if (shape == Look.DITHER) 1f else come
            renderEffect = shape?.let { Effects.of(it, accent, density, come, size.width) }
        }, contentScale = ContentScale.Crop)
    }
}

/**
 * The looks as shaders over whatever's drawn (a still cover, a moving one's frames, the background): Skia's own
 * language, the phone's shaders as they are. Past the picture's edge each gives nothing.
 */
object Effects {
    // the dither: cells 3 dp a side, each lit in the cover's colour or black by a 4 x 4 ordered (Bayer) threshold; the
    // light lifted through a soft curve; `gain` of it shown, so it develops in from black
    private const val DITHER = """
        uniform shader content;
        uniform float cell;
        uniform float gain;
        uniform half4 ink;
        float b2(float2 a) { a = floor(a); return fract(a.x / 2.0 + a.y * a.y * 0.75); }
        half4 main(float2 p) {
            float2 c = floor(p / cell);
            half4 s = content.eval((c + 0.5) * cell);
            if (s.a < 0.5) return half4(0.0);
            float l = smoothstep(0.04, 0.85, dot(float3(s.rgb), float3(0.3, 0.59, 0.11))) * gain;
            float t = b2(0.5 * c) * 0.25 + b2(c);
            return l > t ? ink : half4(0.0, 0.0, 0.0, 1.0);
        }
    """

    // pixels: squares about 18 dp, each the average of four points in it, a 1/16 black gutter
    private const val PIXELS = """
        uniform shader content;
        uniform float cell;
        half4 main(float2 p) {
            float2 c = floor(p / cell);
            float2 o = c * cell;
            if (content.eval(o + 0.5 * cell).a < 0.5) return half4(0.0);
            float2 f = p - o;
            if (f.x < cell / 16.0 || f.y < cell / 16.0) return half4(0.0, 0.0, 0.0, 1.0);
            half4 s = (content.eval(o + float2(0.25, 0.25) * cell) + content.eval(o + float2(0.75, 0.25) * cell)
                + content.eval(o + float2(0.25, 0.75) * cell) + content.eval(o + float2(0.75, 0.75) * cell)) * 0.25;
            return half4(s.rgb, 1.0);
        }
    """

    // fluted glass: ribs `rib` px wide, each a lens showing a wider slice flipped and squeezed in, darker toward its
    // seams, a fine highlight just past the left one; a little soft, as through real glass
    private const val FLUTED = """
        uniform shader content;
        uniform float rib;
        uniform float wide;
        half4 main(float2 p) {
            if (content.eval(p).a < 0.5) return half4(0.0);
            float u = mod(p.x, rib) / rib;
            float x = clamp(p.x - u * rib + rib * 0.5 + (u - 0.5) * rib * -1.7, 1.0, wide - 1.0);
            float2 q = float2(x, p.y);
            half4 s = (content.eval(q) * 2.0 + content.eval(q + float2(0.0, 3.0)) + content.eval(q - float2(0.0, 3.0))) * 0.25;
            float lens = 0.55 + 0.45 * pow(sin(3.14159 * u), 0.6);
            float shine = 0.75 * exp(-pow((u - 0.14) / 0.035, 2.0));
            float lift = shine / 3.0 * (s.r + s.g + s.b);
            return half4(min(s.rgb * lens + lift, half3(1.0)), 1.0);
        }
    """

    private val made = HashMap<String, RuntimeEffect>()
    private fun effect(sksl: String) = synchronized(made) { made.getOrPut(sksl) { RuntimeEffect.makeForShader(sksl) } }

    /**
     * `look` over a picture `wide` px across; `gain` how far a dither has developed. `big` is a background's: blur
     * deeper, ribs and squares larger, as the phone's are behind its player.
     */
    fun of(look: Look, accent: Color, density: Float, gain: Float, wide: Float, big: Boolean = false): RenderEffect? =
        filter(look, accent, density, gain, wide, big)?.asComposeRenderEffect()

    fun filter(look: Look, accent: Color, density: Float, gain: Float, wide: Float, big: Boolean = false): ImageFilter? = runCatching {
        val k = if (big) 2f else 1f
        when (look) {
            Look.BLUR -> ImageFilter.makeBlur(40 * density * k, 40 * density * k, FilterTileMode.CLAMP)
            Look.DITHER -> ImageFilter.makeRuntimeShader(RuntimeShaderBuilder(effect(DITHER)).apply {
                uniform("cell", 3 * density * k); uniform("gain", gain)
                uniform("ink", accent.red, accent.green, accent.blue, 1f)
            }, "content", null)
            // a whole number of squares across, about 18 dp each (else a strip at the right and foot where none fit)
            Look.PIXELS -> ImageFilter.makeRuntimeShader(RuntimeShaderBuilder(effect(PIXELS)).apply {
                val cell = 18 * density * k
                uniform("cell", if (wide > 0) wide / (wide / cell).roundToInt().coerceAtLeast(4) else cell)
            }, "content", null)
            Look.FLUTED -> ImageFilter.makeRuntimeShader(RuntimeShaderBuilder(effect(FLUTED)).apply {
                uniform("rib", 20 * density * k); uniform("wide", wide)
            }, "content", null)
            else -> null
        }
    }.onFailure { Log.d("effect $look: $it") }.getOrNull()
}
