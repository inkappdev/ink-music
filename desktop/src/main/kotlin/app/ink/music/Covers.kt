package app.ink.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File

/** A record's cover, made ready to draw, and its colour (the only colour on screen). */
class Cover(val image: ImageBitmap, val accent: Color)

/**
 * Covers, one a record: the picture in the file's tags, else one beside it in its folder
 * (cover.jpg, folder.jpg...). Read and scaled off the main thread, the last few dozen kept.
 */
object Covers {
    private val kept = Lru<String, Cover>(256 * 1024 * 1024) { it.image.width * it.image.height * 4 }
    private val none = java.util.Collections.synchronizedSet(HashSet<String>())   // records with no picture, not asked again
    private val whole = java.util.Collections.synchronizedSet(HashSet<String>())  // records whose picture is smaller than asked: as big as it gets
    private val beside = listOf("cover", "folder", "front", "album", "artwork")

    fun kept(album: String): Cover? = kept.get(album)

    suspend fun of(song: Song, px: Int = 640): Cover? {
        kept.get(song.albumId)?.takeIf { it.image.width >= px * 9 / 10 || song.albumId in whole }?.let { return it }
        if (song.albumId in none) return null
        return withContext(Dispatchers.IO) {
            val bytes = if (song.server) song.art?.let { art -> fromDisk(art, px) ?: Server.cover(art, px)?.also { toDisk(art, px, it) } }
                else picture(File(song.path))
            val img = bytes?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() }
            if (img == null) {
                kept.get(song.albumId)?.let { return@withContext it }
                none += song.albumId; return@withContext null
            }
            val sq = square(img, px)
            if (sq.width < px * 9 / 10) whole += song.albumId
            Cover(sq.toComposeImageBitmap(), accentOf(sq)).also { kept.put(song.albumId, it) }
        }
    }

    // the server's covers kept on the computer too, so the next start has them at once
    private fun diskFile(art: String, px: Int): File {
        val key = java.security.MessageDigest.getInstance("SHA-1").digest("$art|$px".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(Home.cache, "covers"), key)
    }
    private fun fromDisk(art: String, px: Int): ByteArray? = diskFile(art, px).takeIf { it.exists() }?.readBytes()
    private fun toDisk(art: String, px: Int, bytes: ByteArray) = runCatching {
        val f = diskFile(art, px)
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
    }

    /** A record's colour from a small copy of its cover (the color order; blocking); null with no cover. */
    fun accentOf(song: Song): Color? {
        kept.get(song.albumId)?.let { return it.accent }
        val bytes = if (song.server) song.art?.let { art -> fromDisk(art, 64) ?: Server.cover(art, 64)?.also { toDisk(art, 64, it) } }
            else picture(File(song.path))
        val img = bytes?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() } ?: return null
        return accentOf(square(img, 64))
    }

    private fun picture(f: File): ByteArray? =
        runCatching { AudioFileIO.read(f).tag?.firstArtwork?.binaryData }.getOrNull()
            ?: f.parentFile?.listFiles()?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                ?.minByOrNull { p -> beside.indexOf(p.nameWithoutExtension.lowercase()).let { if (it < 0) 99 else it } }
                ?.readBytes()

    /** Its centre square, no bigger than `px`. */
    private fun square(img: Image, px: Int): Image {
        val side = minOf(img.width, img.height)
        val out = minOf(side, px)
        val s = Surface.makeRasterN32Premul(out, out)
        val from = Rect.makeXYWH((img.width - side) / 2f, (img.height - side) / 2f, side.toFloat(), side.toFloat())
        s.canvas.drawImageRect(img, from, Rect.makeWH(out.toFloat(), out.toFloat()), SamplingMode.CATMULL_ROM, Paint(), true)
        return s.makeImageSnapshot()
    }

    /**
     * The cover's colour, as ink finds it (the phone uses Palette's vibrant swatch): the most of a
     * lively hue, kept light enough to read; a grey cover stays ink.
     */
    fun accentOf(img: Image): Color {
        val n = 48
        val small = Bitmap().apply { allocPixels(ImageInfo(n, n, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)) }
        val s = Surface.makeRaster(small.imageInfo)
        s.canvas.drawImageRect(img, Rect.makeWH(n.toFloat(), n.toFloat()))
        s.readPixels(small, 0, 0)
        val px = small.readPixels() ?: return Ink.ink
        // twelve hues, each weighed by how lively its pixels are
        val weight = FloatArray(12); val sumH = FloatArray(12); val sumS = FloatArray(12); val sumL = FloatArray(12)
        for (i in 0 until n * n) {
            val r = (px[i * 4].toInt() and 255) / 255f; val g = (px[i * 4 + 1].toInt() and 255) / 255f; val b = (px[i * 4 + 2].toInt() and 255) / 255f
            val (h, sat, l) = hsl(r, g, b)
            if (sat < .25f || l < .15f || l > .88f) continue
            val k = ((h / 30f).toInt()).coerceIn(0, 11)
            val w = sat * (1f - kotlin.math.abs(l - .5f))
            weight[k] += w; sumH[k] += h * w; sumS[k] += sat * w; sumL[k] += l * w
        }
        val k = weight.indices.maxBy { weight[it] }
        if (weight[k] < n * n * .02f) return Ink.ink
        val sat = sumS[k] / weight[k]
        if (sat < .12f) return Ink.ink
        return Color.hsl(sumH[k] / weight[k], sat.coerceIn(0f, 1f), (sumL[k] / weight[k]).coerceIn(.52f, .72f))
    }

    private fun hsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val l = (max + min) / 2
        if (max == min) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > .5f) d / (2 - max - min) else d / (max + min)
        val h = when (max) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60
        return Triple(h, s, l)
    }
}

/** A song's record's cover, about `px` across: the kept one at once, else read (null while reading, or none). */
@Composable
fun rememberCover(song: Song?, px: Int = 640): Cover? =
    produceState(song?.let { Covers.kept(it.albumId) }, song?.albumId, px) {
        value = song?.let { Covers.kept(it.albumId) }
        if (song != null && (value?.image?.width ?: 0) < px * 9 / 10) value = Covers.of(song, px) ?: value
    }.value
