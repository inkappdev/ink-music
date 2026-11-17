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
    // the blurred pictures made, by picture: a list seen again is there at once; the last 12 MB of them (each ~0.9 MB:
    // 48 by count had been up to 44 MB, 2026-11-11)
    private val made = object : android.util.LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

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

    /** A heavier grain for a photo in black and white ([Mono]): it reads there as film's, where the blur's would be lost. */
    val heavy: ImageBitmap by lazy {
        val side = 256
        Bitmap.createBitmap(Prints.grainTile(side, seed = 11, strength = .13f), side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private const val MONO = 420

    /**
     * A photo in black and white ([Prints.mono]), square from its middle (a little above it: a face sits high in a wide
     * photo), 420 px, kept, or null.
     */
    fun mono(key: String, art: Bitmap, words: Boolean): ImageBitmap? = runCatching {
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val side = minOf(src.width, src.height)
        val top = ((src.height - side) / 2 - side * 6 / 100).coerceAtLeast(0)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, top, side, side)
        val px = IntArray(MONO * MONO)
        Bitmap.createScaledBitmap(square, MONO, MONO, true).getPixels(px, 0, MONO, 0, 0, MONO, MONO)
        Bitmap.createBitmap(Prints.mono(px, MONO, words), MONO, MONO, Bitmap.Config.ARGB_8888).asImageBitmap().also { made.put(key, it) }
    }.getOrNull()
}

/**
 * A photo in high-contrast black and white, ink's warm white for its white, film's grain over it (2026-11-17: YouTube's
 * mixes and DIALED IN, where people's photos are big; dither stays ink's own: the artist pages, ink's mixes). [words]:
 * dimmed evenly for words over it. Faded in as it's made.
 */
@Composable
fun Mono(photo: Song, modifier: Modifier, words: Boolean = true) {
    val cover = rememberCover(photo, 600)
    val key = "mono|${photo.id}|${photo.albumId}|$words"
    val had = remember(key) { Grain.kept(key) }
    val pic by produceState(had, key, cover) {
        val c = cover ?: run { value = had; return@produceState }
        // made for this picture (the state outlives a change of picture: the old one's was kept, the new one never made,
        // and its fade, begun again for the new one, never ran: DIALED IN's squares black, 2026-11-17)
        value = Grain.kept(key) ?: withContext(Dispatchers.Default) { Grain.mono(key, c.image.asAndroidBitmap(), words) }
    }
    val shown = remember(key) { Animatable(if (had != null) 1f else 0f) }
    LaunchedEffect(key, pic) { if (pic != null && shown.value < 1f) { if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, tween(600, easing = Motion.expo)) } }
    Box(modifier.background(Color.Black).clipToBounds()) {
        pic?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High) }
        FilmGrain(Modifier.fillMaxSize(), tile = Grain.heavy)
    }
}

/** A picture in the grain look: out of focus, faded in as it's made, film's grain over it (still). */
@Composable
fun Grainy(photo: Song, modifier: Modifier, px: Int = 1080) {
    val cover = rememberCover(photo, minOf(px, 400))
    val key = "${photo.id}|${photo.albumId}"
    val had = remember(key) { Grain.kept(key) }
    val pic by produceState(had, key, cover) {
        val c = cover ?: run { value = had; return@produceState }
        // made for this picture (the state outlives a change of picture: the old one's was kept, the new one never made,
        // and its fade, begun again for the new one, never ran: DIALED IN's squares black, 2026-11-17)
        value = Grain.kept(key) ?: withContext(Dispatchers.Default) { Grain.bokeh(key, c.image.asAndroidBitmap()) }
    }
    val shown = remember(key) { Animatable(if (had != null) 1f else 0f) }
    LaunchedEffect(key, pic) { if (pic != null && shown.value < 1f) { if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, tween(600, easing = Motion.expo)) } }
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
fun FilmGrain(modifier: Modifier, moving: Boolean = false, tile: ImageBitmap = Grain.tile) {
    val brush = remember(tile) { ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)) }
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
