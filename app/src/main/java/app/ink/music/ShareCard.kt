package app.ink.music

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * The song as a poster, for a story (1080 x 1920): the player as it looks right now, the cover in
 * its look behind, the line along the cover's top filled to where the song is, the title, the
 * artist and record, the artist big along the foot; "ink music" small at the top, the only word
 * about us. Drawn by hand (no screenshot: no buttons, nothing but the song), shared as a PNG.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1920
    private const val M = 72f   // the side margin

    /**
     * The song as a link (2026-11-09): inkhome.app/song/<artist-title>, the page in ink's look with where to hear it
     * (Apple, Deezer and YouTube exact, the rest a search) and, in a chat, its dithered card. The link says the song and
     * the YouTube id where it is one; the site finds the rest. A station's song or an episode has none: the poster.
     */
    fun link(n: Now): String? {
        val s = n.song
        if (s?.station == true || s?.episode == true || n.title.isBlank() || n.artist.isBlank()) return null
        val artist = n.artist.substringBefore(", ")
        val words = java.text.Normalizer.normalize("$artist ${n.title}", java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80).ifEmpty { "song" }
        fun q(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val params = buildList {
            add("t=" + q(n.title)); add("a=" + q(artist))
            if (n.album.isNotBlank()) add("r=" + q(n.album))
            if (n.duration > 0) add("d=${n.duration / 1000}")
            if (s?.tube == true) add("y=" + q(s.id.removePrefix("y")))
        }
        return "https://inkhome.app/song/$words?" + params.joinToString("&")
    }

    /**
     * The song's short link (2026-11-09): inkhome.app/s/<its Apple id>.<its Deezer id> (found here by its artist and
     * title, at once, a few seconds at most; either alone where only one has it: /s/<apple>, /s/d/<deezer>), else
     * /s/y/<its YouTube id> for a YouTube song, else the long link. Two ids, so the site still finds the song when one
     * service turns it away (Apple did, from Cloudflare); the same song is always the same link.
     */
    suspend fun short(n: Now): String? = withContext(Dispatchers.IO) { coroutineScope {
        val long = link(n) ?: return@coroutineScope null
        val artist = n.artist.substringBefore(", ")
        fun fold(v: String) = java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
        fun same(a: String, b: String) = fold(a).let { x -> fold(b).let { y -> x.isNotEmpty() && (x == y || x.startsWith("$y ") || y.startsWith("$x ")) } }
        fun get(url: String): String? {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 2_500; c.readTimeout = 3_000
            return try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
        }
        val deezer = async {
            runCatching {
                val term = java.net.URLEncoder.encode("$artist ${n.title}", "UTF-8")
                val r = org.json.JSONObject(get("https://api.deezer.com/search?q=$term&limit=10") ?: return@runCatching null).optJSONArray("data") ?: return@runCatching null
                (0 until r.length()).map { r.getJSONObject(it) }
                    .firstOrNull { same(it.optJSONObject("artist")?.optString("name").orEmpty(), artist) && same(it.optString("title"), n.title) }?.optLong("id")?.takeIf { it > 0 }
            }.getOrNull()
        }
        val apple = runCatching {
            val shop = java.util.Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"
            val term = java.net.URLEncoder.encode("$artist ${n.title}", "UTF-8")
            val c = java.net.URL("https://itunes.apple.com/search?term=$term&entity=song&limit=10&country=$shop").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 2_500; c.readTimeout = 3_000
            val text = try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
            val r = org.json.JSONObject(text ?: return@runCatching null).optJSONArray("results") ?: return@runCatching null
            (0 until r.length()).map { r.getJSONObject(it) }
                .firstOrNull { same(it.optString("artistName"), artist) && same(it.optString("trackName"), n.title) }?.optLong("trackId")?.takeIf { it > 0 }
        }.getOrNull()
        val dz = deezer.await()
        when {
            // never Apple's id alone: Apple turns the site away (403 from Cloudflare, 2026-11-09), so a link with only
            // its id found nothing; Deezer's id always goes with it
            apple != null && dz != null -> "https://inkhome.app/s/$apple.$dz"
            dz != null -> "https://inkhome.app/s/d/$dz"
            n.song?.tube == true -> "https://inkhome.app/s/y/" + n.song.id.removePrefix("y")
            else -> long
        }
    } }

    /** Share the song's link (a tap on share), short where it can be; the poster where it has none. */
    suspend fun shareLink(ctx: Context, n: Now, look: Look) {
        val url = short(n) ?: return share(ctx, n, look)
        // the link alone: a message that's only a link is the one a phone shows as its card (iMessage left the song's
        // name and its link as plain text); the song's name goes as the share's title
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
            .putExtra(Intent.EXTRA_TITLE, "${n.title}, ${n.artist.substringBefore(", ")}")
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun share(ctx: Context, n: Now, look: Look) {
        val png = withContext(Dispatchers.Default) {
            val cover = n.song?.let { runCatching { Covers.of(ctx, it, 1080) }.getOrNull() }
            val card = draw(ctx, n, cover, look)
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            File(dir, "ink-music.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".shared", png)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "${n.title}, ${n.artist}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)   // the preview in the share sheet
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Lines of a song, picked in the lyrics, as a card ([drawLyric]). */
    suspend fun shareLyric(ctx: Context, n: Now, lines: List<String>) {
        if (lines.isEmpty()) return
        val png = withContext(Dispatchers.Default) {
            val cover = n.song?.let { runCatching { Covers.of(ctx, it, 640) }.getOrNull() }
            val card = drawLyric(ctx, n, cover, lines)
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            File(dir, "ink-music-lyric.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        send(ctx, png, "${n.title}, ${n.artist.substringBefore(", ")}")
    }

    /**
     * Lines of a song as a story (1080 x 1920; 2026-11-11, after SimpMusic's lyric images): the words big, as the
     * player sings them, set as large as all of them fit; under them the record small, the line along its top in its
     * colour (the line touches the cover), the song and artist beside it; "ink music" small at the top.
     */
    fun drawLyric(ctx: Context, n: Now, cover: Cover?, lines: List<String>): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Ink.black.toArgb())
        val archivo = ResourcesCompat.getFont(ctx, R.font.archivo_black)
        val silk = ResourcesCompat.getFont(ctx, R.font.silkscreen)
        fun pixel(size: Float, color: Color = Ink.mid) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = silk; textSize = size; this.color = color.toArgb() }
        fun headline(size: Float, color: Color = Ink.ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = archivo; textSize = size; this.color = color.toArgb(); letterSpacing = -0.02f
        }
        val side = (W - 2 * M).toInt()
        c.drawText("ink music", M, 250f, pixel(30f))

        // the words: as big as fits the room between the top and the record, from 112 down
        val room = 1080f
        val text = lines.joinToString("\n") { it.uppercase() }
        var size = 112f
        var words = layout(text, headline(size), side, 40)
        while (words.height > room && size > 44f) { size -= 6f; words = layout(text, headline(size), side, 40) }
        val wordsTop = 330f + (room - words.height).coerceAtLeast(0f) / 2
        c.save(); c.translate(M, wordsTop); words.draw(c); c.restore()

        // the record, small, the line on its top edge in its colour; the song and its artist beside it
        val box = 220f
        val top = H - 200f - box
        val accent = cover?.accent ?: Ink.ink
        c.drawRect(M, top - 4f, M + box, top, Paint().apply { color = accent.toArgb() })
        val art = cover?.image?.asAndroidBitmap()
        if (art != null) {
            val s = minOf(art.width, art.height)
            c.drawBitmap(art, Rect((art.width - s) / 2, (art.height - s) / 2, (art.width + s) / 2, (art.height + s) / 2),
                RectF(M, top, M + box, top + box), Paint(Paint.FILTER_BITMAP_FLAG))
        } else c.drawRect(M, top, M + box, top + box, Paint().apply { color = Ink.line.toArgb(); style = Paint.Style.STROKE; strokeWidth = 3f })
        val x = M + box + 40f
        val wide = (W - M - x).toInt()
        val title = layout(n.title.uppercase(), headline(52f), wide, 3)
        c.save(); c.translate(x, top + 4f); title.draw(c); c.restore()
        layout(n.artist.lowercase(), pixel(30f), wide, 2).let { c.save(); c.translate(x, top + 4f + title.height + 18f); it.draw(c); c.restore() }
        return out
    }

    /** Your year as a poster: the year, the minutes, the artist, song and record heard most. */
    suspend fun shareYear(ctx: Context, y: Year) {
        val png = withContext(Dispatchers.Default) {
            val top = y.records.firstOrNull()?.first
            val cover = top?.let { h -> Library.song(h.id)?.let { runCatching { Covers.of(ctx, it, 640) }.getOrNull() } }
            val card = drawYear(ctx, y, cover)
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            File(dir, "ink-music-year.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        send(ctx, png, "my ${y.year} in music")
    }

    private fun send(ctx: Context, png: File, text: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".shared", png)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun drawYear(ctx: Context, y: Year, cover: Cover?): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Ink.black.toArgb())
        val archivo = ResourcesCompat.getFont(ctx, R.font.archivo_black)
        val silk = ResourcesCompat.getFont(ctx, R.font.silkscreen)
        fun pixel(size: Float, color: Color = Ink.mid) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = silk; textSize = size; this.color = color.toArgb() }
        fun headline(size: Float, color: Color = Ink.ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = archivo; textSize = size; this.color = color.toArgb(); letterSpacing = -0.02f
        }
        val side = (W - 2 * M).toInt()
        c.drawText("ink music", M, 250f, pixel(30f))
        c.drawText("MY YEAR", M, 380f, headline(64f, Ink.mid))
        c.drawText(y.year.toString(), M, 640f, headline(300f))
        c.drawText("%,d".format(y.minutes), M, 820f, headline(140f))
        c.drawText("minutes", M, 880f, pixel(34f))
        var at = 1000f
        // the artist heard most, then the song
        y.artists.firstOrNull()?.let { (a, _) ->
            c.drawText("top artist", M, at, pixel(30f)); at += 20f
            layout(a.uppercase(), headline(86f), side, 2).let { c.save(); c.translate(M, at); it.draw(c); c.restore(); at += it.height + 50f }
        }
        y.songs.firstOrNull()?.let { (h, _) ->
            c.drawText("top song", M, at, pixel(30f)); at += 20f
            layout(h.title.uppercase(), headline(64f), side, 2).let { c.save(); c.translate(M, at); it.draw(c); c.restore(); at += it.height + 14f }
            layout(h.artist.lowercase(), pixel(30f), side, 1).let { c.save(); c.translate(M, at); it.draw(c); c.restore(); at += it.height + 50f }
        }
        // the record heard most, its cover beside its name
        y.records.firstOrNull()?.let { (h, _) ->
            val box = 300f
            val top = maxOf(at, H - 120f - box)
            val art = cover?.image?.asAndroidBitmap()
            if (art != null) {
                val s = minOf(art.width, art.height)
                c.drawBitmap(art, Rect((art.width - s) / 2, (art.height - s) / 2, (art.width + s) / 2, (art.height + s) / 2),
                    RectF(M, top, M + box, top + box), Paint(Paint.FILTER_BITMAP_FLAG))
            } else c.drawRect(M, top, M + box, top + box, Paint().apply { color = Ink.line.toArgb(); style = Paint.Style.STROKE; strokeWidth = 3f })
            val x = M + box + 40f
            c.drawText("top record", x, top + 30f, pixel(30f))
            layout(h.album.uppercase(), headline(56f), (W - M - x).toInt(), 3).let { c.save(); c.translate(x, top + 56f); it.draw(c); c.restore() }
        }
        return out
    }

    fun draw(ctx: Context, n: Now, cover: Cover?, look: Look): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Ink.black.toArgb())
        val archivo = ResourcesCompat.getFont(ctx, R.font.archivo_black)
        val silk = ResourcesCompat.getFont(ctx, R.font.silkscreen)
        val accent = cover?.accent ?: Ink.ink
        val art = cover?.image?.asAndroidBitmap()

        // behind: the cover in its look, across the card (square, cropped at the sides), as faint as the player has it
        if (art != null) Looks.draw(art, look, accent, H.toFloat(), 2.625f)?.let { back ->
            val p = Paint().apply { alpha = (look.alpha * 255).toInt(); isFilterBitmap = !look.crisp }
            c.drawBitmap(back, null, RectF((W - H) / 2f, 0f, (W + H) / 2f, H.toFloat()), p)
        }

        fun pixel(size: Float, color: Color) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = silk; textSize = size; this.color = color.toArgb() }
        fun headline(size: Float, color: Color = Ink.ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = archivo; textSize = size; this.color = color.toArgb(); letterSpacing = -0.02f
        }

        // the top: ink music, quietly (below where a story's own bar sits)
        c.drawText("ink music", M, 250f, pixel(30f, Ink.mid))

        // the cover, the line on its top edge, the times over it
        val side = W - 2 * M
        val top = 400f
        val pos = n.positionNow()
        pixel(30f, Ink.mid).let { p ->
            c.drawText(clock(pos), M, top - 26f, p)
            if (n.duration > 0) {
                val left = "-" + clock((n.duration - pos).coerceAtLeast(0))
                c.drawText(left, W - M - p.measureText(left), top - 26f, p)
            }
        }
        val frac = if (n.duration > 0) (pos.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
        c.drawRect(M, top - 3f, W - M, top, Paint().apply { color = Ink.line.toArgb() })
        c.drawRect(M, top - 3f, M + side * frac, top, Paint().apply { color = accent.toArgb() })
        val box = RectF(M, top, W - M, top + side)
        if (art != null) {
            // cropped to a square, as the player shows it
            val s = minOf(art.width, art.height)
            c.drawBitmap(art, Rect((art.width - s) / 2, (art.height - s) / 2, (art.width + s) / 2, (art.height + s) / 2), box, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            // ink's sleeve: the record's name on black, a hairline round it
            c.drawRect(box, Paint().apply { color = Ink.line.toArgb(); style = Paint.Style.STROKE; strokeWidth = 3f })
            layout(n.album.ifEmpty { n.title }.uppercase(), headline(110f), (side - 96).toInt(), 4).let {
                c.save(); c.translate(M + 48, top + 48); it.draw(c); c.restore()
            }
        }

        // the song: its title, then the artist and the record
        var y = top + side + 64f
        val title = layout(n.title.uppercase(), headline(92f), side.toInt(), 2)
        c.save(); c.translate(M, y); title.draw(c); c.restore()
        y += title.height + 30f
        val said = listOf(n.artist, n.album).filter { it.isNotBlank() }.joinToString("   ").lowercase()
        layout(said, pixel(34f, Ink.mid), side.toInt(), 1).let { c.save(); c.translate(M, y); it.draw(c); c.restore() }

        // the artist along the foot, as big as the player's, running off the edge
        val big = headline(300f)
        c.drawText(n.artist.uppercase(), M - 16f, H - 80f, big)
        return out
    }

    private fun layout(text: String, paint: TextPaint, width: Int, lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, .95f).setIncludePad(false).build()
}
