package app.ink.music

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit

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
    // a picture there's a link to that didn't come (the server turning a crowd away, a timeout): when, asked again a
    // minute on (2026-11-09: marked as none, The Marías' Clueless stayed ink's sleeve till the app was restarted)
    private val missed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // the server's asked a few at a time, as the computer does: an artist's page asked for all its records at once
    private val asking = kotlinx.coroutines.sync.Semaphore(4)
    private val largest = java.util.concurrent.ConcurrentHashMap<String, Int>()   // a record whose picture is smaller than asked: as big as it gets

    fun kept(album: String): Cover? = kept.get(album)
    /** Whether a picture with a link didn't come, so it's to be asked again. */
    fun waiting(album: String) = missed.containsKey(album)

    suspend fun of(ctx: Context, song: Song, px: Int = 640): Cover? {
        // the kept one, unless it's smaller than asked (a share card's cover is bigger than the screens')
        kept.get(song.albumId)?.takeIf { it.image.width >= px * 9 / 10 || it.image.width >= (largest[song.albumId] ?: Int.MAX_VALUE) }?.let { return it }
        if (song.albumId in none) return null
        missed[song.albumId]?.let { if (System.currentTimeMillis() - it < 60_000) return kept.get(song.albumId) }
        if (song.server && song.art != null) return asking.withPermit { read(ctx, song, px) }
        return read(ctx, song, px)
    }

    private suspend fun read(ctx: Context, song: Song, px: Int): Cover? {
        return withContext(Dispatchers.IO) {
            // one from the network (YouTube's, the server's, a station's) is kept on the phone too: the next start has it at once
            val remote = (song.server || song.tube || song.station || song.episode) && song.art != null
            val t0 = System.currentTimeMillis()
            val bmp = try {
                (if (remote) fromDisk(ctx, song, px)?.also { android.util.Log.d("ink-covers", "${song.title}: disk ${System.currentTimeMillis() - t0} ms") } else null) ?: (
                    if (song.station) song.art?.let { fetch(it, px) }   // the cover it gave for its song, else its sleeve
                    else if (song.server) song.art?.let { Server.cover(it, px) }
                    else if (song.tube || song.episode) song.art?.let { fetch(it, px) }
                    // a file with no picture of its own: the record's from the catalogs (FoundCovers)
                    else runCatching { ctx.contentResolver.loadThumbnail(song.uri, Size(px, px), null) }.getOrNull() ?: found(ctx, song, px)
                    )?.also { if (remote) { android.util.Log.d("ink-covers", "${song.title}: fetched ${System.currentTimeMillis() - t0} ms"); toDisk(ctx, song, px, it) } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                // a cover always comes (2026-11-10, as the computer's): a server's or YouTube's song with no link, or
                // one that didn't come, from the catalogs by its record's name; else a YouTube song's own video picture
                // (an artist's face or photo, a song only in shape, is never looked for so)
                ?: (if ((song.server || song.tube) && !face(song)) runCatching { found(ctx, song, px) }.getOrNull() else null)
                ?: (if (song.tube && !face(song)) runCatching { frame(ctx, song, px) }.getOrNull() else null)
            // moved past before it came (a cover scrolled off, the remote along a shelf): not a miss, asked again when
            // it's back (2026-11-10: marked missed, it stayed blank a minute on the TV)
            ensureActive()
            if (bmp == null) {
                // a link that didn't come: asked again a minute on; none only where there's nothing to ask
                if (remote || song.server || song.tube) missed[song.albumId] = System.currentTimeMillis()
                // a bigger one failing keeps the smaller
                kept.get(song.albumId)?.let { return@withContext it }
                if (!remote && !song.server && !song.tube) none += song.albumId
                return@withContext null
            }
            missed -= song.albumId
            if (bmp.width < px * 9 / 10) largest[song.albumId] = bmp.width
            bmp.prepareToDraw()
            Cover(bmp.asImageBitmap(), accentOf(bmp)).also { kept.put(song.albumId, it) }
        }
    }

    /**
     * A record with no picture of its own (a file's, or a song with no link to one): found by name in the catalogs,
     * kept on the phone as the network's are; a song with no record named, by its own name.
     */
    private fun found(ctx: Context, song: Song, px: Int): Bitmap? {
        val record = song.album.ifBlank { song.title }
        val whose = song.albumArtist.ifBlank { song.artist }.substringBefore(", ")
        if (record.isBlank()) return null
        val url = FoundCovers.link(java.io.File(ctx.cacheDir, "covers"), record, whose) { a, by ->
            // none in the catalogs: YouTube Music's record of that name (the full build; the play build has none)
            kotlinx.coroutines.runBlocking { YouTubeMusic.findRecord(a, by)?.art }
        } ?: return null
        val found = song.copy(art = url)
        return fromDisk(ctx, found, px) ?: fetch(url, px)?.also { toDisk(ctx, found, px, it) }
    }

    private fun face(song: Song) = song.id.startsWith("yface:") || song.id.startsWith("yphoto:")

    /** A YouTube song's own video picture (its largest, its centre square): there's one for every video. */
    private fun frame(ctx: Context, song: Song, px: Int): Bitmap? {
        val framed = song.copy(art = "https://i.ytimg.com/vi/${song.id.removePrefix("y")}/hqdefault.jpg")
        return fromDisk(ctx, framed, px) ?: fetch(framed.art!!, px)?.also { toDisk(ctx, framed, px, it) }
    }

    // covers from the network, as JPEGs in the cache (the system may clear it; trimmed to 150 MB, oldest first)
    private fun diskFile(ctx: Context, song: Song, px: Int): java.io.File {
        val key = java.security.MessageDigest.getInstance("SHA-1").digest("${song.art}|$px".toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(java.io.File(ctx.cacheDir, "covers"), "$key.jpg")
    }

    /**
     * The songs either side of the one playing, their covers read into memory as each song starts, at the player's
     * size: a song change finds its cover there and shows it at once, never the name cover a moment (2026-11-09, as the
     * computer's). From the screens (Main), while they're open: the player's process draws no covers.
     */
    suspend fun ahead(ctx: Context) {
        val px = ctx.resources.displayMetrics.widthPixels.coerceIn(640, 1440)
        kotlinx.coroutines.flow.combine(Playback.now, Playback.queue) { n, q -> n?.index?.let { i -> listOfNotNull(q.getOrNull(i + 1)?.song, q.getOrNull(i - 1)?.song) } ?: emptyList() }
            .distinctUntilChanged { a, b -> a.map { it.albumId } == b.map { it.albumId } }
            .collectLatest { near -> near.filter { !it.station }.forEach { s -> runCatching { of(ctx, s, px) } } }
    }

    /** A network picture fetched and kept on the phone only (read ahead: not held in memory, where it'd push out what's on screen). */
    fun keepOnDisk(ctx: Context, song: Song, px: Int) {
        cleared(ctx)
        if (diskFile(ctx, song, px).exists()) return
        song.art?.let { runCatching { fetch(it, px) }.getOrNull() }?.let { toDisk(ctx, song, px, it) }
    }

    private fun fromDisk(ctx: Context, song: Song, px: Int): Bitmap? {
        cleared(ctx)
        return diskFile(ctx, song, px).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    }

    @Volatile private var clean = false

    /** The covers kept before pictures were read whole (some only half there) cleared once, to be fetched again. */
    private fun cleared(ctx: Context) {
        if (clean) return
        synchronized(this) {
            if (clean) return
            val dir = java.io.File(ctx.cacheDir, "covers")
            val mark = java.io.File(dir, ".whole")
            if (!mark.exists()) {
                dir.listFiles { f -> f.name.endsWith(".jpg") }?.forEach { it.delete() }
                dir.mkdirs(); mark.createNewFile()
            }
            clean = true
        }
    }

    @Volatile private var trimmed = false

    private fun toDisk(ctx: Context, song: Song, px: Int, bmp: Bitmap) = runCatching {
        val f = diskFile(ctx, song, px)
        f.parentFile?.mkdirs()
        val tmp = java.io.File(f.path + ".part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(f)
        if (!trimmed) { trimmed = true; trim(f.parentFile!!, 150L * 1024 * 1024) }
    }

    private fun trim(dir: java.io.File, max: Long) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) { total += f.length(); if (total > max) f.delete() }
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
        return try { if (c.responseCode != 200) null else whole(c) }
        catch (e: java.io.IOException) { null } finally { c.disconnect() }
    }

    /**
     * A picture read to its last byte before it's drawn. Decoding the stream as it came let a read that
     * timed out halfway become half a cover (the rest grey), kept on disk so; now a short one is none.
     */
    fun whole(c: java.net.HttpURLConnection): Bitmap? {
        val bytes = c.inputStream.use { it.readBytes() }
        val length = c.contentLengthLong
        if (bytes.isEmpty() || (length > 0 && bytes.size.toLong() != length)) return null
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
        // one that didn't come (the server busy, a timeout): asked again each minute while it's on screen
        while (song != null && (value?.image?.width ?: 0) < px * 9 / 10) {
            value = Covers.of(ctx, song, px) ?: value
            if (!Covers.waiting(song.albumId)) break
            kotlinx.coroutines.delay(61_000)
        }
    }.value
}
