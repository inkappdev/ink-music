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
    private val _inLook = MutableStateFlow(false)
    /** In the player's look (dither, pixels, fluted glass), or as it is. */
    val inLook: StateFlow<Boolean> = _inLook

    private fun file(c: Context) = File(c.filesDir, "moving-covers")

    fun start(c: Context) {
        MotionCover.dir = c.cacheDir
        val said = runCatching { file(c).readText().lines() }.getOrDefault(emptyList())
        _when.value = said.getOrNull(0)?.takeIf { it == WIFI || it == ALWAYS || it == OFF } ?: WIFI
        // "dithered" was the first word for it (2.0.3's first build)
        _inLook.value = said.getOrNull(1) == "in the look" || said.getOrNull(1) == "dithered"
    }

    fun set(c: Context, whenOn: String = _when.value, inLook: Boolean = _inLook.value) {
        _when.value = whenOn; _inLook.value = inLook
        runCatching { file(c).writeText(whenOn + "\n" + if (inLook) "in the look" else "as it is") }
    }

    /** Whether to show them on the network the phone is on now. */
    fun wanted(c: Context) = available && when (_when.value) { ALWAYS -> true; WIFI -> !Quality.metered(c); else -> false }
}

/**
 * The record's moving cover over its still one, once its first frame is drawn (till then, and where there's none, the
 * still one shows). A TextureView, so a shader can draw over it: the player's look (dither, pixels, fluted glass; not
 * blur, which would hide the sleeve). It comes in as a print develops (dithered: the brightest dots first; else faded
 * in), and settles with the music: paused, it slows to a stop over a second and holds; played, it picks up again.
 */
@OptIn(UnstableApi::class)
@Composable
fun MovingCover(artist: String, album: String, accent: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val whenOn by Moving.whenOn.collectAsState()
    val inLook by Moving.inLook.collectAsState()
    val look by Looks.on.collectAsState()
    val less by Moves.less.collectAsState()
    val url by produceState<String?>(null, artist, album, whenOn) {
        value = if (Moving.wanted(ctx)) withContext(Dispatchers.IO) { MotionCover.of(artist, album) } else null
    }
    val u = url ?: return
    var shown by remember(u) { mutableStateOf(false) }
    val player = remember(u) {
        ExoPlayer.Builder(ctx).build().apply {
            // a picture, never a sound: no audio track, no focus taken from the music
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener { override fun onRenderedFirstFrame() { shown = true } })
            setMediaItem(MediaItem.fromUri(u))
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
    // coming in: developing (its dots, brightest first, as the still dither's do), else a fade
    val shape = if (inLook && Build.VERSION.SDK_INT >= 33) look.takeIf { it == Look.DITHER || it == Look.PIXELS || it == Look.FLUTED } else null
    val come by animateFloatAsState(if (shown) 1f else 0f, if (less) snap() else tween(if (shape == Look.DITHER) 1_400 else 600), label = "come")
    val density = LocalDensity.current.density
    var wide by remember { mutableStateOf(0) }
    AndroidView(
        factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
        update = { tv -> if (Build.VERSION.SDK_INT >= 33) tv.setRenderEffect(shape?.let { Shaders.effect(it, accent, density, come, wide.toFloat()) }) },
        modifier = modifier.onSizeChanged { wide = it.width }.clipToBounds().graphicsLayer { alpha = if (shape == Look.DITHER) (if (shown) 1f else 0f) else come },
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
            Look.PIXELS -> RuntimeShader(PIXELS).apply { setFloatUniform("cell", 18 * density) }
            Look.FLUTED -> RuntimeShader(FLUTED).apply { setFloatUniform("rib", 20 * density); setFloatUniform("wide", wide) }
            else -> return@runCatching null
        }
        RenderEffect.createRuntimeShaderEffect(s, "content")
    }.getOrNull()
}
