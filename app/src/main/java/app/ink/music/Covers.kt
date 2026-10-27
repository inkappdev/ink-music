package app.ink.music

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A record's cover, made ready to draw, and its colour (the only colour on screen). */
class Cover(val image: ImageBitmap, val accent: Color)

/**
 * Covers, one a record: a file's own picture (through the media index), or the server's at the
 * size it's shown. Read and made ready off the main thread, the last few dozen kept.
 */
object Covers {
    // an eighth of what the app may hold: a player-sized cover is ~6 MB
    private val kept = object : LruCache<String, Cover>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(48 * 1024 * 1024)) {
        override fun sizeOf(key: String, c: Cover) = c.image.width * c.image.height * 4
    }
    private val none = java.util.Collections.synchronizedSet(HashSet<String>())   // records with no picture, not asked again
    private val largest = java.util.concurrent.ConcurrentHashMap<String, Int>()   // a record whose picture is smaller than asked: as big as it gets

    fun kept(album: String): Cover? = kept.get(album)

    suspend fun of(ctx: Context, song: Song, px: Int = 640): Cover? {
        // the kept one, unless it's smaller than asked (a share card's cover is bigger than the screens')
        kept.get(song.albumId)?.takeIf { it.image.width >= px * 9 / 10 || it.image.width >= (largest[song.albumId] ?: Int.MAX_VALUE) }?.let { return it }
        if (song.albumId in none) return null
        return withContext(Dispatchers.IO) {
            val bmp = try {
                if (song.station) song.art?.let { fetch(it, px) }   // the cover it gave for its song, else its sleeve
                else if (song.server) song.art?.let { Server.cover(it, px) }
                else if (song.tube) song.art?.let { fetch(it, px) }
                else ctx.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
            } catch (e: Exception) { null }
            if (bmp == null) {
                // none at all only when there was none before; a bigger one failing keeps the smaller
                kept.get(song.albumId)?.let { return@withContext it }
                none += song.albumId; return@withContext null
            }
            if (bmp.width < px * 9 / 10) largest[song.albumId] = bmp.width
            bmp.prepareToDraw()
            Cover(bmp.asImageBitmap(), accentOf(bmp)).also { kept.put(song.albumId, it) }
        }
    }

    /**
     * A picture at a link (YouTube Music's covers, a station's), about `px` across and square. YouTube
     * Music's square art is asked for at that size (Google's image server makes any size from the
     * original); a video's frame (a mix's or radio's songs come only with one, 336 x 188) at its largest,
     * 1280 x 720, then its centre: for a "- Topic" song that's the record's cover, 720 px where it was 188.
     */
    fun fetch(url: String, px: Int = 640): Bitmap? {
        for (u in sized(url, px)) get(u)?.let { return square(it, px) }
        return null
    }

    private fun sized(url: String, px: Int): List<String> {
        Regex("""^https?://i\d?\.ytimg\.com/vi(?:_webp)?/([^/]+)/""").find(url)?.let {
            val id = it.groupValues[1]
            return listOf("https://i.ytimg.com/vi/$id/maxresdefault.jpg", "https://i.ytimg.com/vi/$id/hq720.jpg", url)
        }
        if ("googleusercontent.com" in url && Regex("=w\\d+-h\\d+").containsMatchIn(url))
            return listOf(url.replace(Regex("=w\\d+-h\\d+"), "=w$px-h$px"), url)
        return listOf(url)
    }

    private fun get(url: String): Bitmap? {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        return try { if (c.responseCode != 200) null else c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } }
        catch (e: java.io.IOException) { null } finally { c.disconnect() }
    }

    /** Its centre square, no bigger than `px`. */
    private fun square(b: Bitmap, px: Int): Bitmap {
        val side = minOf(b.width, b.height)
        val sq = if (b.width == b.height) b else Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side > px) Bitmap.createScaledBitmap(sq, px, px, true) else sq
    }

    /** The cover's colour, as ink finds it: its vibrant swatch, kept light enough to read; a grey cover stays ink. */
    fun accentOf(bmp: Bitmap): Color {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch ?: return Ink.ink
        val hsl = sw.hsl.copyOf()
        if (hsl[1] < .12f) return Ink.ink
        hsl[2] = hsl[2].coerceIn(.52f, .72f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}

/**
 * A song's record's cover, about `px` across: the kept one at once, else read (null while reading, or
 * none); a kept one smaller than asked shows while the bigger is read (the player's is screen-wide).
 */
@Composable
fun rememberCover(song: Song?, px: Int = 640): Cover? {
    val ctx = LocalContext.current
    return produceState(song?.let { Covers.kept(it.albumId) }, song?.albumId, px) {
        // a new record: its own cover (kept, or none while it's read), never the last one's
        value = song?.let { Covers.kept(it.albumId) }
        if (song != null && (value?.image?.width ?: 0) < px * 9 / 10) value = Covers.of(ctx, song, px) ?: value
    }.value
}
