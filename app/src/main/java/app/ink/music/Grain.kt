package app.ink.music

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The grain look (settings, the look: COVERS and ARTIST PAGES; 2026-11-11, Bronson: "a soft bokeh with a nice film like
 * grain texture over it"): the picture out of focus as a lens has it ([Prints.bokeh], made small and drawn up smoothly),
 * faded in, and film's grain over it a screen pixel a grain ([FilmGrain]).
 */
object Grain {
    private const val SMALL = 120
    // the blurred pictures made, by picture: a list seen again is there at once
    private val made = android.util.LruCache<String, ImageBitmap>(48)

    fun kept(key: String): ImageBitmap? = made.get(key)

    /** The picture out of focus, drawn up to 480 px smoothly (it's all soft shapes: no more is needed), kept, or null. */
    fun bokeh(key: String, art: Bitmap): ImageBitmap? = runCatching { lens(art).asImageBitmap().also { made.put(key, it) } }.getOrNull()

    /** The picture out of focus as a lens has it, 480 px (the player's blur too, behind it, since 2026-11-11). */
    fun lens(art: Bitmap): Bitmap {
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val px = IntArray(SMALL * SMALL)
        Bitmap.createScaledBitmap(src, SMALL, SMALL, true).getPixels(px, 0, SMALL, 0, 0, SMALL, SMALL)
        val small = Bitmap.createBitmap(Prints.bokeh(px, SMALL), SMALL, SMALL, Bitmap.Config.ARGB_8888)
        // up in two steps, each smooth, so no edge of the small one shows
        val mid = Bitmap.createScaledBitmap(small, SMALL * 2, SMALL * 2, true)
        return Bitmap.createScaledBitmap(mid, SMALL * 4, SMALL * 4, true)
    }

    /** Film's grain, one tile for every picture, repeated. */
    val tile: ImageBitmap by lazy {
        val side = 256
        Bitmap.createBitmap(Prints.grainTile(side), side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/** A picture in the grain look: out of focus, faded in as it's made, film's grain over it (still). */
@Composable
fun Grainy(photo: Song, modifier: Modifier, px: Int = 1080) {
    val cover = rememberCover(photo, minOf(px, 400))
    val key = "${photo.id}|${photo.albumId}"
    val had = remember(key) { Grain.kept(key) }
    val pic by produceState(had, key, cover) {
        val c = cover ?: return@produceState
        if (value == null) value = withContext(Dispatchers.Default) { Grain.bokeh(key, c.image.asAndroidBitmap()) }
    }
    val shown = remember(key) { Animatable(if (had != null) 1f else 0f) }
    LaunchedEffect(pic) { if (pic != null && shown.value < 1f) { if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, tween(600, easing = Motion.expo)) } }
    Box(modifier.background(Color.Black).clipToBounds()) {
        pic?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High) }
        FilmGrain(Modifier.fillMaxSize())
    }
}

/**
 * Film's grain over whatever is under it, a screen pixel a grain; `moving`, it dances as film's does (a new place in
 * the tile 24 times a second; still when the phone moves less).
 */
@Composable
fun FilmGrain(modifier: Modifier, moving: Boolean = false) {
    val brush = remember { ShaderBrush(ImageShader(Grain.tile, TileMode.Repeated, TileMode.Repeated)) }
    val less by Moves.less.collectAsState()
    var at by remember { mutableStateOf(Offset.Zero) }
    if (moving && !less) LaunchedEffect(Unit) {
        val rnd = java.util.Random()
        var last = 0L
        while (true) withFrameNanos { t ->
            if (t - last > 41_000_000L) { last = t; at = Offset(rnd.nextInt(256).toFloat(), rnd.nextInt(256).toFloat()) }
        }
    }
    Canvas(modifier.clipToBounds()) {
        translate(-at.x, -at.y) { drawRect(brush, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width + 256f, size.height + 256f)) }
    }
}
