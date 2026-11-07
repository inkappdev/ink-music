package app.ink.music

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import dev.chrisbanes.haze.hazeSource
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Mixtapes (2026-11-07): a playlist, the queue or your loves made a tape ([Tape], in core: the whole tape in its link),
 * shared as a card (a cassette, both sides' songs, a QR code of the link) and the link with it. Whoever gets it opens
 * the link: here, the tape's page, each song found in what they have; without ink music, inkhome.app/tape shows it.
 */
object Tapes {
    /** A tape's link opened (Main hands it here; the app opens its page). */
    val opened = MutableStateFlow<String?>(null)

    fun isTape(u: Uri?): Boolean = u != null && !u.fragment.isNullOrEmpty() &&
        ((u.scheme == "https" && u.host == "inkhome.app" && u.path.orEmpty().startsWith("/tape")) || (u.scheme == "inkmusic" && u.host == "tape"))

    fun take(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW && isTape(i.data)) opened.value = i.data.toString()
    }

    /** These songs as a tape (the first 24; a station or an episode isn't a song to give). */
    fun of(name: String, from: String, note: String, songs: List<Song>): Tape = Tape(name.trim().ifEmpty { "a tape" }, from.trim(), note.trim(),
        songs.filter { !it.station && !it.episode }.take(Tape.MAX).map {
            Tape.Track(it.title, it.artist, (it.duration / 1000).toInt(), if (it.tube) it.id.removePrefix("y") else "")
        })

    /** Who tapes are from, as last said. */
    fun from(c: Context): String = runCatching { File(c.filesDir, "tape-from").readText().trim() }.getOrDefault("")
    fun setFrom(c: Context, name: String) { runCatching { File(c.filesDir, "tape-from").writeText(name.trim()) } }

    /**
     * Each song as one that plays here, in order (null where none is): yours first (by title and artist); a YouTube
     * song by its own id (the full build); the rest looked for as an import is ([Import.match]).
     */
    suspend fun match(t: Tape, found: (Int) -> Unit = {}): List<Song?> = withContext(Dispatchers.IO) {
        val mine = Library.songs.value.associateBy { Radio.key(it.title, it.artist) }
        val out = MutableList<Song?>(t.tracks.size) { i ->
            val tr = t.tracks[i]
            mine[Radio.key(tr.title, tr.artist)]
                ?: tr.tube.takeIf { it.isNotEmpty() && YouTubeMusic.available }?.let { YouTubeMusic.byId(it, tr.title, tr.artist, tr.seconds * 1000L) }
        }
        val rest = out.indices.filter { out[it] == null }
        val done = out.size - rest.size
        found(done)
        Import.match(rest.map { Import.Said(t.tracks[it].title, t.tracks[it].artist, "") }) { found(done + it) }
            .forEachIndexed { k, s -> out[rest[k]] = s }
        out
    }

    fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    // ---- the card ----
    private const val W = 1080
    private const val H = 1920
    private const val M = 72f

    suspend fun share(ctx: Context, t: Tape, first: Song?) {
        val png = withContext(Dispatchers.Default) {
            val cover = first?.let { runCatching { Covers.of(ctx, it, 640) }.getOrNull() }
            val card = draw(ctx, t, cover)
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            File(dir, "ink-music-tape.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".shared", png)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "${t.name}, a tape for you\n${t.link()}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copy(ctx: Context, t: Tape) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(t.name, t.link()))
    }

    /**
     * The tape as a card for a story or a message (1080 x 1920): ink music small at the top; a cassette in ink's lines,
     * its label the tape's name, the first song's cover dithered beside it (the only colour), what it is (C60...);
     * both sides' songs; the link's QR code, black on ink (a camera reads it the right way round), the note beside it.
     */
    fun draw(ctx: Context, t: Tape, cover: Cover?): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Ink.black.toArgb())
        val archivo = ResourcesCompat.getFont(ctx, R.font.archivo_black)
        val silk = ResourcesCompat.getFont(ctx, R.font.silkscreen)
        fun pixel(size: Float, color: Color = Ink.mid) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = silk; textSize = size; this.color = color.toArgb() }
        fun headline(size: Float, color: Color = Ink.ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = archivo; textSize = size; this.color = color.toArgb(); letterSpacing = -0.02f
        }
        fun line(w: Float, color: Color = Ink.ink) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; this.color = color.toArgb() }
        fun at(l: StaticLayout, x: Float, y: Float) { c.save(); c.translate(x, y); l.draw(c); c.restore() }

        c.drawText("ink music", M, 250f, pixel(30f))

        // the cassette
        val L = M; val R = W - M; val T = 320f; val B = 780f
        c.drawRect(L, T, R, B, line(5f))
        for ((x, y) in listOf(L + 26 to T + 26, R - 26 to T + 26, L + 26 to B - 26, R - 26 to B - 26)) c.drawCircle(x, y, 8f, line(3f))
        // its label: the cover, the name, from whom, what tape
        val lt = T + 40; val lb = T + 250
        c.drawRect(L + 48, lt, R - 48, lb, line(3f))
        val art = cover?.image?.asAndroidBitmap()
        val box = RectF(L + 68, lt + 20, L + 68 + 170, lt + 190)
        val dithered = art?.let { runCatching { Looks.draw(it, Look.DITHER, cover.accent, 170f, 1.4f) }.getOrNull() }
        when {
            dithered != null -> c.drawBitmap(dithered, null, box, Paint())
            art != null -> {
                val s = minOf(art.width, art.height)
                c.drawBitmap(art, Rect((art.width - s) / 2, (art.height - s) / 2, (art.width + s) / 2, (art.height + s) / 2), box, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            else -> c.drawRect(box, line(3f, Ink.line))
        }
        val tx = box.right + 32; val tw = (R - 68 - tx).toInt()
        val name = layout(t.name.uppercase(), headline(52f), tw, 2)
        at(name, tx, lt + 26)
        if (t.from.isNotBlank()) at(layout("from ${t.from}", pixel(26f, Ink.ink), tw, 1), tx, lt + 30 + name.height + 14)
        pixel(26f).let { p -> c.drawText(t.cassette, R - 68 - p.measureText(t.cassette), lb - 22, p) }
        // its window, the two reels, the tape between
        val wt = lb + 30; val wb = wt + 110; val cx = W / 2f; val cy = (wt + wb) / 2
        c.drawRect(cx - 210, wt, cx + 210, wb, line(3f))
        for (rx in listOf(cx - 120, cx + 120)) {
            c.drawCircle(rx, cy, 40f, line(3f)); c.drawCircle(rx, cy, 16f, line(3f))
            for (k in 0 until 6) {
                val a = Math.PI * k / 3
                c.drawLine(rx + (16 * Math.cos(a)).toFloat(), cy + (16 * Math.sin(a)).toFloat(), rx + (24 * Math.cos(a)).toFloat(), cy + (24 * Math.sin(a)).toFloat(), line(3f))
            }
        }
        c.drawLine(cx - 120, cy + 40, cx + 120, cy + 40, line(2f, Ink.mid))
        // its foot, where the heads go
        val foot = Path().apply { moveTo(L + 200, B); lineTo(L + 240, B - 44); lineTo(R - 240, B - 44); lineTo(R - 200, B) }
        c.drawPath(foot, line(3f))
        for (hx in listOf(cx - 150, cx + 150)) c.drawRect(hx - 10, B - 30, hx + 10, B - 14, line(3f))

        // the songs: side a on the left, side b on the right
        val colW = ((W - 2 * M - 40) / 2).toInt()
        val (la, lb2) = t.lengths
        listOf(Triple("SIDE A", t.a, la), Triple("SIDE B", t.b, lb2)).forEachIndexed { k, (head, songs, len) ->
            val x = M + k * (colW + 40)
            var y = 860f
            c.drawText(head, x, y, headline(34f))
            if (len > 0) pixel(24f).let { p -> val s = clock(len); c.drawText(s, x + colW - p.measureText(s), y, p) }
            y += 20
            for ((i, s) in songs.withIndex()) {
                at(layout("${i + 1}  ${s.title}", pixel(24f, Ink.ink), colW, 1), x, y + 8)
                at(layout(s.artist, pixel(20f), colW, 1), x + 0f, y + 36)
                y += 48
            }
        }

        // the link's QR code, black on ink, its quiet border kept; the note beside it
        val q = runCatching { QrCode.encodeText(t.link(), QrCode.Ecc.LOW) }.getOrNull()
        val qs = 400f; val qx = M; val qy = H - 60 - qs
        if (q != null) {
            c.drawRect(qx, qy, qx + qs, qy + qs, Paint().apply { color = Ink.ink.toArgb() })
            val m = qs / (q.size + 8)
            val dark = Paint().apply { color = Ink.black.toArgb() }
            for (y in 0 until q.size) for (x in 0 until q.size) if (q.getModule(x, y))
                c.drawRect(qx + (x + 4) * m, qy + (y + 4) * m, qx + (x + 5) * m + .5f, qy + (y + 5) * m + .5f, dark)
        }
        val nx = qx + qs + 40; val nw = (W - M - nx).toInt()
        if (t.note.isNotBlank()) at(layout(t.note, pixel(28f, Ink.ink), nw, 7), nx, qy)
        at(layout("point a camera here to play it. ink music, or any browser", pixel(22f), nw, 3), nx, qy + qs - 84)
        return out
    }

    private fun layout(text: String, paint: TextPaint, width: Int, lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1f).setIncludePad(false).build()
}

/**
 * A tape being made (from a playlist, the queue, your loves): its name, who it's from, a line for them; the songs, a
 * tap leaving one off (the next one along takes its place); SHARE sends the card and the link.
 */
@Composable
internal fun TapeMakePage(songs: List<Song>, listName: String, pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val usable = remember(songs) { songs.filter { !it.station && !it.episode } }
    var name by remember { mutableStateOf(listName) }
    var from by remember { mutableStateOf(Tapes.from(ctx)) }
    var note by remember { mutableStateOf("") }
    val off = remember { mutableStateListOf<Int>() }
    var sharing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2_000); copied = false } }
    val kept = remember(usable, off.toList()) { usable.filterIndexed { i, _ -> i !in off }.take(Tape.MAX) }
    val tape = remember(kept, name, from, note) { Tapes.of(name, from, note.take(Tape.NOTE), kept) }
    // each song's place on the tape (a1, b3), or none
    val place = remember(usable, kept, tape) {
        val at = HashMap<Int, String>(); var k = 0
        usable.forEachIndexed { i, _ -> if (i !in off && k < Tape.MAX) { at[i] = if (k < tape.sideB) "a${k + 1}" else "b${k - tape.sideB + 1}"; k++ } }
        at
    }
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item { Column {
            BasicText("A TAPE", style = headline(44.sp))
            Spacer(Modifier.height(6.dp))
            BasicText("up to 24 songs on two sides, sent as a card with a code to scan. whoever gets it plays it from what they have: " +
                "their phone, their server" + (if (YouTubeMusic.available) ", youtube music" else "") + ". nothing goes through anyone else",
                style = pixel(12.sp, Ink.mid).copy(lineHeight = 20.sp))
            Spacer(Modifier.height(20.dp))
            Field("name", name, { name = it.take(Tape.NAME) }, "", KeyboardType.Text)
            Field("from", from, { from = it.take(Tape.FROM) }, "your name", KeyboardType.Text)
            Field("a line for them", note, { note = it.take(Tape.NOTE) }, "", KeyboardType.Text)
            val (a, b) = tape.lengths
            BasicText(listOfNotNull(tape.cassette, "side a ${Tapes.clock(a)}", "side b ${Tapes.clock(b)}").joinToString("   "), style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Word(if (sharing) "MAKING IT" else "SHARE", headline(40.sp, if (sharing) Ink.dim else Ink.ink), Modifier.padding(end = 26.dp), touch = 8.dp) {
                    if (sharing || kept.isEmpty()) return@Word
                    sharing = true
                    Tapes.setFrom(ctx, from)
                    scope.launch { runCatching { Tapes.share(ctx, tape, kept.firstOrNull()) }; sharing = false }
                }
                Word(if (copied) "copied" else "copy the link", pixel(12.sp, Ink.mid), Modifier.padding(bottom = 8.dp), touch = 8.dp) {
                    Tapes.setFrom(ctx, from); Tapes.copy(ctx, tape); copied = true
                }
            }
            Spacer(Modifier.height(18.dp))
            BasicText(if (usable.size > Tape.MAX) "the first ${Tape.MAX} fit. tap one to leave it off" else "tap one to leave it off", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(8.dp))
        }}
        itemsIndexed(usable) { i, s ->
            val on = place[i]
            Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt {
                if (i in off) off.remove(i) else off.add(i)
            }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(on ?: if (i in off) "off" else "", style = pixel(12.sp, if (on != null) Ink.mid else Ink.dim), modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(s.title.uppercase(), style = headline(18.sp, if (on != null) Ink.ink else Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(s.artist.lowercase(), style = pixel(12.sp, if (on != null) Ink.mid else Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } }
}

/** A tape someone sent: its name, from whom, their line; each song found in what you have; play it, keep it. */
@Composable
internal fun TapePage(link: String, pages: Pages) {
    val tape = remember(link) { Tape.read(link) }
    var found by remember(link) { mutableStateOf<List<Song?>?>(null) }
    var progress by remember(link) { mutableIntStateOf(0) }
    LaunchedEffect(link) { tape?.let { t -> found = Tapes.match(t) { progress = it } } }
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        if (tape == null) {
            item { Column {
                BasicText("NOT A TAPE", style = headline(44.sp))
                Spacer(Modifier.height(8.dp))
                BasicText("the link may have been cut short on its way. ask for it again", style = pixel(12.sp, Ink.mid))
            }}
            return@LazyColumn
        }
        val m = found
        item { Column {
            BasicText(if (tape.from.isNotBlank()) "a tape from ${tape.from}" else "a tape for you", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(6.dp))
            BasicText(tape.name.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (tape.note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                BasicText(tape.note, style = pixel(12.sp, Ink.ink).copy(lineHeight = 20.sp))
            }
            Spacer(Modifier.height(10.dp))
            val (a, b) = tape.lengths
            BasicText(listOfNotNull(tape.cassette, "${tape.tracks.size} songs", "side a ${Tapes.clock(a)}".takeIf { a > 0 }, "side b ${Tapes.clock(b)}".takeIf { b > 0 })
                .joinToString("   "), style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(14.dp))
            if (m == null) {
                // a line filling as they're found, as the player's fills with the song
                val filled by animateFloatAsState(progress / tape.tracks.size.toFloat(), tween(Motion.ENTER, easing = Motion.expo), label = "found")
                Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.line)) { Box(Modifier.fillMaxWidth(filled).height(2.dp).background(Ink.ink)) }
                Spacer(Modifier.height(8.dp))
                BasicText("finding $progress of ${tape.tracks.size}", style = pixel(12.sp, Ink.mid))
            } else {
                val all = m.filterNotNull()
                BasicText("${all.size} of ${tape.tracks.size} found   ${all.count { !it.tube }} yours", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(12.dp))
                if (all.isNotEmpty()) {
                    Word("PLAY", headline(40.sp), Modifier.tvFirst(), touch = 8.dp) { Playback.play(all, 0) }
                    KeepIt(tape.name, all, pages)
                }
            }
            Spacer(Modifier.height(10.dp))
        }}
        listOf("SIDE A" to tape.a, "SIDE B" to tape.b).forEachIndexed { side, (head, tracks) ->
            if (tracks.isEmpty()) return@forEachIndexed
            item { BasicText(head, style = headline(22.sp), modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)) }
            itemsIndexed(tracks) { j, tr ->
                val i = if (side == 0) j else tape.sideB + j
                val song = m?.getOrNull(i)
                // unnumbered: who it's by shows (a numbered row is a record's, its artist left out)
                if (song != null) SongRow(song, pages = pages) { m.filterNotNull().let { all -> Playback.play(all, all.indexOf(song).coerceAtLeast(0)) } }
                else Row(Modifier.padding(vertical = 8.dp)) {
                    Column {
                        BasicText(tr.title.uppercase(), style = headline(18.sp, Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(if (m == null) tr.artist.lowercase() else "${tr.artist.lowercase()}   not found", style = pixel(12.sp, Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    } }
}
