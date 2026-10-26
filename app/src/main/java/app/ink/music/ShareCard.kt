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
