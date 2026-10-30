package app.ink.music

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Moving covers (settings, your music: the full build's): a record's own looping sleeve, Apple Music's motion artwork
 * ([MotionCover]), in the player in the still cover's place, silent, round and round. On wifi only (the default),
 * always, or off; as it is, or in the player's look (Android 13 and up: a shader over each frame; before, as it is).
 */
object Moving {
    val available = BuildConfig.FLAVOR == "full"
    const val WIFI = "on wifi"; const val ALWAYS = "always"; const val OFF = "off"

    private val _when = MutableStateFlow(WIFI)
    val whenOn: StateFlow<String> = _when
    const val CANVAS_OFF = "off"; const val CANVAS_LOOK = "in the look"; const val CANVAS_PLAIN = "as it is"
    private val _canvas = MutableStateFlow(CANVAS_LOOK)
    /** A song's Spotify canvas behind the player (signed in to Spotify): off, in the look, or as it is (no effect). */
    val canvas: StateFlow<String> = _canvas
    private val _inLook = MutableStateFlow(false)
    /** The cover (still or moving) in the player's look (dither, pixels, fluted glass), or as it is. */
    val inLook: StateFlow<Boolean> = _inLook

    private fun file(c: Context) = File(c.filesDir, "moving-covers")

    fun start(c: Context) {
        MotionCover.dir = c.cacheDir
        val said = runCatching { file(c).readText().lines() }.getOrDefault(emptyList())
        _when.value = said.getOrNull(0)?.takeIf { it == WIFI || it == ALWAYS || it == OFF } ?: WIFI
        // "dithered" was the first word for it (2.0.3's first build)
        _inLook.value = said.getOrNull(1) == "in the look" || said.getOrNull(1) == "dithered"
        _canvas.value = when (said.getOrNull(2)) { "no canvas" -> CANVAS_OFF; "canvas as it is" -> CANVAS_PLAIN; else -> CANVAS_LOOK }
    }

    fun set(c: Context, whenOn: String = _when.value, inLook: Boolean = _inLook.value, canvas: String = _canvas.value) {
        _when.value = whenOn; _inLook.value = inLook; _canvas.value = canvas
        val said = when (canvas) { CANVAS_OFF -> "no canvas"; CANVAS_PLAIN -> "canvas as it is"; else -> "canvas" }
        runCatching { file(c).writeText(listOf(whenOn, if (inLook) "in the look" else "as it is", said).joinToString("\n")) }
    }

    /** Whether to show them on the network the phone is on now. */
    fun wanted(c: Context) = available && when (_when.value) { ALWAYS -> true; WIFI -> !Quality.metered(c); else -> false }
}

/**
 * The record's moving cover over its still one, once its first frame is drawn (till then, and where there's none, the
 * still one shows): in the player's look if chosen (dither, pixels, fluted glass; not blur, which would hide the
 * sleeve), else as it is.
 */
@Composable
fun MovingCover(artist: String, album: String, accent: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val whenOn by Moving.whenOn.collectAsState()
    val inLook by Moving.inLook.collectAsState()
    val look by Looks.on.collectAsState()
    val url by produceState<String?>(null, artist, album, whenOn) {
        value = if (Moving.wanted(ctx)) withContext(Dispatchers.IO) { MotionCover.of(artist, album) } else null
        android.util.Log.i("ink-moving", "cover for $artist / $album: ${if (value != null) "found" else "none"}")
    }
    val u = url ?: return
    val shape = if (inLook) look.takeIf { it == Look.DITHER || it == Look.PIXELS || it == Look.FLUTED } else null
    Loop(u, playing, shape, accent, 1f, crop = false, modifier)
}

/**
 * Behind the player, a song's Spotify canvas (its tall looping clip; signed in to Spotify, the full build's) across the
 * whole page: in the look, as the still cover is drawn there (dither, pixels, fluted glass, blur, at the look's own
 * strength; plain has no backdrop, so none), or as it is, no effect, whatever the look (Bronson, 2026-10-30).
 */
@Composable
fun CanvasBehind(title: String, artist: String, accent: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val whenOn by Moving.whenOn.collectAsState()
    val behind by Moving.canvas.collectAsState()
    val look by Looks.on.collectAsState()
    if (behind == Moving.CANVAS_OFF || (behind == Moving.CANVAS_LOOK && look == Look.PLAIN)) return
    val url by produceState<String?>(null, title, artist, whenOn) {
        value = if (Moving.wanted(ctx)) Spotify.canvas(ctx, title, artist) else null
        android.util.Log.i("ink-moving", "canvas for $artist / $title: ${if (value != null) "found" else "none"}")
    }
    val u = url ?: return
    // as it is: the clip itself, held to half over the black so the white words over it still read (at full, a light
    // canvas took the title; Spotify's own app darkens it too, by a gradient: here evenly, as ink's look has none)
    if (behind == Moving.CANVAS_PLAIN) Loop(u, playing, null, accent, .5f, crop = true, modifier)
    else Loop(u, playing, look, accent, look.alpha, crop = true, modifier)
}

/**
 * A silent looping clip in a TextureView, so a shader can draw over it (`shape`: a look, or null as it is). It comes
 * in as a print develops (dithered: the brightest dots first; else faded in, to `strength`), and settles with the
 * music: paused, it slows to a stop over a second and holds; played, it picks up again. `crop` fills its space as a
 * picture cropped to fill would (a canvas is tall, the page another shape).
 */
@OptIn(UnstableApi::class)
@Composable
private fun Loop(url: String, playing: Boolean, shape: Look?, accent: Color, strength: Float, crop: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val less by Moves.less.collectAsState()
    var shown by remember(url) { mutableStateOf(false) }
    var video by remember(url) { mutableStateOf(androidx.media3.common.VideoSize.UNKNOWN) }
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            // a picture, never a sound: no audio track, no focus taken from the music
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { shown = true; android.util.Log.i("ink-moving", "playing ${if (crop) "canvas" else "cover"}") }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { android.util.Log.w("ink-moving", "${if (crop) "canvas" else "cover"}: ${error.errorCodeName}") }
                override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) { video = size }
            })
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // settling: its speed eased down to nothing with a pause (held there), back up with play; at once moving less
    val speed by animateFloatAsState(if (playing) 1f else 0f, if (less) snap() else tween(1_000, easing = androidx.compose.animation.core.LinearEasing), label = "settle")
    LaunchedEffect(player) {
        snapshotFlow { speed }.collect { v ->
            if (v < .05f) player.pause() else { player.setPlaybackSpeed(v); player.play() }
        }
    }
    // the shaders are Android 13's: before it, as it is (and blur too: Android 12's own blur)
    val drawn = shape?.takeIf { Build.VERSION.SDK_INT >= 33 || (it == Look.BLUR && Build.VERSION.SDK_INT >= 31) }
    val come by animateFloatAsState(if (shown) 1f else 0f, if (less) snap() else tween(if (drawn == Look.DITHER) 1_400 else 600), label = "come")
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    AndroidView(
        factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
        update = { tv ->
            if (crop && size.width > 0 && video.width > 0) {
                // scaled up to fill, the overflow cut off evenly (TextureView stretches to fit by itself)
                val vw = video.width * video.pixelWidthHeightRatio; val vh = video.height.toFloat()
                val k = maxOf(size.width / vw, size.height / vh)
                tv.setTransform(android.graphics.Matrix().apply { setScale(vw * k / size.width, vh * k / size.height, size.width / 2f, size.height / 2f) })
            }
            if (Build.VERSION.SDK_INT >= 31) tv.setRenderEffect(when {
                drawn == Look.BLUR -> android.graphics.RenderEffect.createBlurEffect(40 * density, 40 * density, android.graphics.Shader.TileMode.CLAMP)
                drawn != null -> Shaders.effect(drawn, accent, density, come, size.width.toFloat())
                else -> null
            })
        },
        modifier = modifier.onSizeChanged { size = it }.clipToBounds().graphicsLayer {
            alpha = if (drawn == Look.DITHER) (if (shown) strength else 0f) else come * strength
        },
    )
}

/**
 * The still looks (Looks: dither, pixels, fluted glass) as shaders, so each frame of a moving cover is drawn so. Past
 * the picture's edge each gives nothing: an effect isn't held to its view, and black there hid the page.
 */
private object Shaders {
    // the dither: cells 3 dp a side, each lit in the cover's colour or black by a 4 x 4 ordered (Bayer) threshold; the
    // light lifted through a soft curve where the still one stretches the cover's own range (a frame's isn't known
    // ahead); `gain` of it shown, so it develops in from black
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

    // pixels: squares about 18 dp, each the average of four points in it, a 1/16 black gutter (Looks.pixels' 16 + 1)
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
    // seams, a fine highlight just past the left one; a little soft, as through real glass (Looks.fluted)
    private const val FLUTED = """
        uniform shader content;
        uniform float rib;
        uniform float wide;
        half4 main(float2 p) {
            if (content.eval(p).a < 0.5) return half4(0.0);
            float u = mod(p.x, rib) / rib;
            // a rib at the edge reaches past it: held to the picture (else it drew a dark strip there)
            float x = clamp(p.x - u * rib + rib * 0.5 + (u - 0.5) * rib * -1.7, 1.0, wide - 1.0);
            float2 q = float2(x, p.y);
            half4 s = (content.eval(q) * 2.0 + content.eval(q + float2(0.0, 3.0)) + content.eval(q - float2(0.0, 3.0))) * 0.25;
            float lens = 0.55 + 0.45 * pow(sin(3.14159 * u), 0.6);
            float shine = 0.75 * exp(-pow((u - 0.14) / 0.035, 2.0));
            float lift = shine / 3.0 * (s.r + s.g + s.b);
            return half4(min(s.rgb * lens + lift, half3(1.0)), 1.0);
        }
    """

    fun effect(look: Look, accent: Color, density: Float, gain: Float, wide: Float): RenderEffect? = if (Build.VERSION.SDK_INT < 33) null else runCatching {
        val s = when (look) {
            Look.DITHER -> RuntimeShader(DITHER).apply {
                setFloatUniform("cell", 3 * density); setFloatUniform("gain", gain)
                setFloatUniform("ink", accent.red, accent.green, accent.blue, 1f)
            }
            // a whole number of squares across the cover, about 18 dp each (an 18 dp square left a strip at the
            // right and foot where none fit, the still cover showing through)
            Look.PIXELS -> RuntimeShader(PIXELS).apply {
                setFloatUniform("cell", if (wide > 0) wide / (wide / (18 * density)).roundToInt().coerceAtLeast(4) else 18 * density)
            }
            Look.FLUTED -> RuntimeShader(FLUTED).apply { setFloatUniform("rib", 20 * density); setFloatUniform("wide", wide) }
            else -> return@runCatching null
        }
        RenderEffect.createRuntimeShaderEffect(s, "content")
    }.getOrNull()
}
