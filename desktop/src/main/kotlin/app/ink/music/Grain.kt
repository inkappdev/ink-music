package app.ink.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * The grain look (settings, the look: COVERS and ARTIST PAGES; the phone's Grain.kt, 2026-11-11): the picture out of
 * focus as a lens has it ([Prints.bokeh], made small and drawn up smoothly), faded in, and film's grain over it a
 * screen pixel a grain ([FilmGrain]).
 */
object Grain {
    private const val SMALL = 120
    // the blurred pictures made, by picture: the last 24 MB of them, a list seen again there at once
    private val made = Lru<String, ImageBitmap>(24 * 1024 * 1024) { it.width * it.height * 4 }

    fun kept(key: String): ImageBitmap? = made.get(key)

    /** The picture out of focus, drawn up to 480 px smoothly (it's all soft shapes: no more is needed), or null. */
    fun bokeh(key: String, art: ImageBitmap): ImageBitmap? = runCatching {
        val img = Image.makeFromBitmap(art.asSkiaBitmap())
        val small = Dither.image(Prints.bokeh(Dither.pixels(img, SMALL), SMALL), SMALL)
        val side = SMALL * 4
        val s = Surface.makeRaster(ImageInfo(side, side, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        s.canvas.drawImageRect(small, Rect.makeWH(SMALL.toFloat(), SMALL.toFloat()), Rect.makeWH(side.toFloat(), side.toFloat()),
            SamplingMode.MITCHELL, org.jetbrains.skia.Paint(), true)
        s.makeImageSnapshot().toComposeImageBitmap().also { made.put(key, it) }
    }.getOrNull()

    /** Film's grain, one tile for every picture, repeated. */
    val tile: ImageBitmap by lazy {
        val side = 256
        val px = Prints.grainTile(side)
        val bytes = java.nio.ByteBuffer.allocate(px.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(px) }.array()
        Image.makeRaster(ImageInfo(side, side, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, side * 4).toComposeImageBitmap()
    }

    /** A heavier grain for a photo in black and white ([Mono]): it reads there as film's, where the blur's would be lost. */
    val heavy: ImageBitmap by lazy {
        val side = 256
        val px = Prints.grainTile(side, seed = 11, strength = .13f)
        val bytes = java.nio.ByteBuffer.allocate(px.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(px) }.array()
        Image.makeRaster(ImageInfo(side, side, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, side * 4).toComposeImageBitmap()
    }

    private const val MONO = 480

    /**
     * A photo in black and white ([Prints.mono]), square from its middle (a little above it: a face sits high in a wide
     * photo), 480 px, kept, or null.
     */
    fun mono(key: String, art: ImageBitmap, words: Boolean): ImageBitmap? = runCatching {
        val img = Image.makeFromBitmap(art.asSkiaBitmap())
        val side = minOf(img.width, img.height).toFloat()
        val left = (img.width - side) / 2; val top = ((img.height - side) / 2 - side * .06f).coerceAtLeast(0f)
        val s = Surface.makeRaster(ImageInfo(MONO, MONO, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        s.canvas.drawImageRect(img, Rect.makeXYWH(left, top, side, side), Rect.makeWH(MONO.toFloat(), MONO.toFloat()),
            SamplingMode.MITCHELL, org.jetbrains.skia.Paint(), true)
        val px = Dither.pixels(s.makeImageSnapshot(), MONO)
        Dither.image(Prints.mono(px, MONO, words), MONO).toComposeImageBitmap().also { made.put(key, it) }
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
        value = Grain.kept(key) ?: withContext(Dispatchers.Default) { Grain.mono(key, c.image, words) }
    }
    val shown = remember(key) { Animatable(if (had != null) 1f else 0f) }
    LaunchedEffect(key, pic) { if (pic != null && shown.value < 1f) if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, tween(600, easing = Motion.expo)) }
    Box(modifier.background(Ink.black).clipToBounds()) {
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
        value = Grain.kept(key) ?: withContext(Dispatchers.Default) { Grain.bokeh(key, c.image) }
    }
    val shown = remember(key) { Animatable(if (had != null) 1f else 0f) }
    LaunchedEffect(key, pic) { if (pic != null && shown.value < 1f) if (Moves.less.value) shown.snapTo(1f) else shown.animateTo(1f, tween(600, easing = Motion.expo)) }
    Box(modifier.background(Ink.black).clipToBounds()) {
        pic?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High) }
        FilmGrain(Modifier.fillMaxSize())
    }
}

/** Film's grain over whatever is under it, a screen pixel a grain; `moving`, it dances as film's does (24 times a second). */
@Composable
fun FilmGrain(modifier: Modifier, moving: Boolean = false, tile: ImageBitmap = Grain.tile) {
    val brush = remember(tile) { ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)) }
    var at by remember { mutableStateOf(Offset.Zero) }
    if (moving) LaunchedEffect(Unit) {
        val rnd = java.util.Random()
        var last = 0L
        while (true) withFrameNanos { t ->
            if (t - last > 41_000_000L) { last = t; at = Offset(rnd.nextInt(256).toFloat(), rnd.nextInt(256).toFloat()) }
        }
    }
    Canvas(modifier.clipToBounds()) {
        translate(-at.x, -at.y) { drawRect(brush, topLeft = Offset.Zero, size = Size(size.width + 256f, size.height + 256f)) }
    }
}
