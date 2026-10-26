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
    private val kept = object : LruCache<String, Cover>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, c: Cover) = c.image.width * c.image.height * 4
    }
    private val none = java.util.Collections.synchronizedSet(HashSet<String>())   // records with no picture, not asked again

    fun kept(album: String): Cover? = kept.get(album)

    suspend fun of(ctx: Context, song: Song, px: Int = 640): Cover? {
        // the kept one, unless it's smaller than asked (a share card's cover is bigger than the screens')
        kept.get(song.albumId)?.takeIf { it.image.width >= px * 9 / 10 }?.let { return it }
        if (song.albumId in none) return null
        return withContext(Dispatchers.IO) {
            val bmp = try {
                if (song.station) song.art?.let(::fetch)   // the cover it gave for its song, else its sleeve
                else if (song.server) song.art?.let { Server.cover(it, px) }
                else if (song.tube) song.art?.let(::fetch)
                else ctx.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
            } catch (e: Exception) { null }
            if (bmp == null) { none += song.albumId; return@withContext null }
            bmp.prepareToDraw()
            Cover(bmp.asImageBitmap(), accentOf(bmp)).also { kept.put(song.albumId, it) }
        }
    }

    /** A picture at a link (YouTube Music's covers, a station's). */
    fun fetch(url: String): Bitmap? {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        return try { if (c.responseCode != 200) null else c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
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

/** A song's record's cover: the kept one at once, else read (null while reading, or none). */
@Composable
fun rememberCover(song: Song?): Cover? {
    val ctx = LocalContext.current
    return produceState(song?.let { Covers.kept(it.albumId) }, song?.albumId) {
        // a new record: its own cover (kept, or none while it's read), never the last one's
        value = song?.let { Covers.kept(it.albumId) }
        if (song != null && value == null) value = Covers.of(ctx, song)
    }.value
}
