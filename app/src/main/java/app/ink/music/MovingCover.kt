package app.ink.music

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * always, or off; as it is, or dithered in the cover's colour as the dither look draws a still one (Android 13 and
 * up: a shader over each frame; before, as it is).
 */
object Moving {
    val available = BuildConfig.FLAVOR == "full"
    const val WIFI = "on wifi"; const val ALWAYS = "always"; const val OFF = "off"

    private val _when = MutableStateFlow(WIFI)
    val whenOn: StateFlow<String> = _when
    private val _dither = MutableStateFlow(false)
    val dither: StateFlow<Boolean> = _dither

    private fun file(c: Context) = File(c.filesDir, "moving-covers")

    fun start(c: Context) {
        MotionCover.dir = c.cacheDir
        val said = runCatching { file(c).readText().lines() }.getOrDefault(emptyList())
        _when.value = said.getOrNull(0)?.takeIf { it == WIFI || it == ALWAYS || it == OFF } ?: WIFI
        _dither.value = said.getOrNull(1) == "dithered"
    }

    fun set(c: Context, whenOn: String = _when.value, dither: Boolean = _dither.value) {
        _when.value = whenOn; _dither.value = dither
        runCatching { file(c).writeText(whenOn + "\n" + if (dither) "dithered" else "as it is") }
    }

    /** Whether to show them on the network the phone is on now. */
    fun wanted(c: Context) = available && when (_when.value) { ALWAYS -> true; WIFI -> !Quality.metered(c); else -> false }
}

/**
 * The record's moving cover over its still one, once its first frame is drawn (faded in; till then, and where there's
 * none, the still one shows). A TextureView, so a shader can draw over it (dithered).
 */
@OptIn(UnstableApi::class)
@Composable
fun MovingCover(artist: String, album: String, accent: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val whenOn by Moving.whenOn.collectAsState()
    val dither by Moving.dither.collectAsState()
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
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "moving")
    val density = LocalDensity.current.density
    AndroidView(
        factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
        update = { tv -> if (Build.VERSION.SDK_INT >= 33) tv.setRenderEffect(if (dither) Dither.effect(accent, 3 * density) else null) },
        modifier = modifier.clipToBounds().graphicsLayer { alpha = fade },
    )
}

/**
 * The dither look over a moving picture: the still one's (Looks.dither) as a shader, so each frame is drawn so. Cells
 * `cell` px a side, each lit in the cover's colour or black by a 4 x 4 ordered (Bayer) threshold; the light lifted
 * through a soft curve where the still one stretches the cover's own range (a frame's range isn't known ahead).
 */
private object Dither {
    private const val SHADER = """
        uniform shader content;
        uniform float cell;
        uniform half4 ink;
        float b2(float2 a) { a = floor(a); return fract(a.x / 2.0 + a.y * a.y * 0.75); }
        half4 main(float2 p) {
            float2 c = floor(p / cell);
            half4 s = content.eval((c + 0.5) * cell);
            float l = smoothstep(0.04, 0.85, dot(float3(s.rgb), float3(0.3, 0.59, 0.11)));
            float t = b2(0.5 * c) * 0.25 + b2(c);
            // past the picture's edge, nothing: an effect isn't held to its view, and black there hid the page
            if (s.a < 0.5) return half4(0.0);
            return l > t ? ink : half4(0.0, 0.0, 0.0, 1.0);
        }
    """

    fun effect(accent: Color, cell: Float): RenderEffect? = if (Build.VERSION.SDK_INT < 33) null else runCatching {
        val s = RuntimeShader(SHADER)
        s.setFloatUniform("cell", cell)
        s.setFloatUniform("ink", accent.red, accent.green, accent.blue, 1f)
        RenderEffect.createRuntimeShaderEffect(s, "content")
    }.getOrNull()
}
