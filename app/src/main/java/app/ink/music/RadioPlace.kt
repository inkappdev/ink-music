package app.ink.music

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Radio, a place of its own (the Mac's, here; Bronson, 2026-10-29: "bring this to the app as well"): the tower sending
 * across the top ([RadioTower], its rings in the colour of the station on), what's on and tune in anywhere; then ink
 * radio's stations, or a genre's, chosen in a row of words, each its sleeve in its own colour.
 */
@Composable
fun RadioPlace(pages: Pages, pad: PaddingValues, grid: LazyGridState) {
    val ctx = LocalContext.current
    val ours = remember { Stations.curated(ctx).sortedByDescending { it.star } }
    var genre by remember { mutableStateOf<String?>(null) }
    val theirs by produceState<List<Song>?>(null, genre) {
        value = null
        genre?.let { g -> Stations.genres.firstOrNull { it.first == g }?.let { (_, tag) -> value = Stations.top(ctx, tag) } }
    }
    val shown = if (genre == null) ours.map { it.song to it.city } else theirs.orEmpty().map { it to it.artist }
    val now by Playback.now.collectAsState()
    LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, contentPadding = pad,
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") { RadioHead(ours) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "kinds") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                item { Word("ink radio", pixel(13.sp, if (genre == null) Ink.ink else Ink.dim), touch = 8.dp) { genre = null } }
                items(Stations.genres, key = { it.first }) { (word, _) -> Word(word, pixel(13.sp, if (genre == word) Ink.ink else Ink.dim), touch = 8.dp) { genre = word } }
            }
        }
        if (genre != null && theirs == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("finding stations", style = pixel(12.sp, Ink.mid)) }
        itemsIndexed(shown, key = { _, (s, _) -> s.id }) { i, (s, where) ->
            val on = now?.song?.id == s.id
            StationTile(s, on, now?.playing == true, said = if (on) "on now" else where) { Playback.play(shown.drop(i).map { it.first }, 0) }
        }
    }
}

/** The tower sending (in the colour of the station on), and under it what's on, or how many stations ink keeps; tune in anywhere. */
@Composable
private fun RadioHead(ours: List<Stations.Curated>) {
    val now by Playback.now.collectAsState()
    val station = now?.song?.takeIf { it.station }
    val moving = station != null && now?.playing == true && !Moves.less.collectAsState().value
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(240.dp)) {
            val density = LocalDensity.current.density
            val w = (maxWidth.value / 3).toInt().coerceAtLeast(40); val h = 80
            val dot = station?.let { StationSleeve.colorOf(it.id) } ?: QUIET
            val frames by produceState<List<ImageBitmap>?>(null, w, h, moving, dot) {
                value = withContext(Dispatchers.Default) {
                    (0 until if (moving) RadioTower.FRAMES else 1).map {
                        Bitmap.createBitmap(RadioTower.pixels(w, h, .5f, it, Ink.ink.toArgb(), dot), w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                }
            }
            var frame by remember { mutableIntStateOf(0) }
            LaunchedEffect(moving, frames) {
                val count = frames?.size ?: return@LaunchedEffect
                while (moving && count > 1) { delay(220); frame = (frame + 1) % count }
            }
            // three taps on the tower: lightning off its mast, and a station by chance (an easter egg)
            var taps by remember { mutableIntStateOf(0) }
            var lastTap by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
            var struck by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
            var flash by remember { mutableStateOf(false) }
            val bolt by produceState<ImageBitmap?>(null, w, h, dot) {
                value = withContext(Dispatchers.Default) { Bitmap.createBitmap(RadioTower.pixels(w, h, .5f, 0, Ink.ink.toArgb(), dot, bolts = true), w, h, Bitmap.Config.ARGB_8888).asImageBitmap() }
            }
            LaunchedEffect(struck) {
                if (struck == 0L) return@LaunchedEffect
                repeat(5) { flash = true; delay(90); flash = false; delay(70) }
                ours.filter { it.song.id != station?.id }.randomOrNull()?.let { Playback.play(listOf(it.song), 0) }
            }
            val tower = Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                val t = System.currentTimeMillis()
                taps = if (t - lastTap < 500) taps + 1 else 1
                lastTap = t
                if (taps >= 3) { taps = 0; struck = t }
            }
            (if (flash) bolt else frames?.let { f -> f[frame % f.size] })?.let { Image(it, null, tower, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
        }
        Spacer(Modifier.height(14.dp))
        if (station != null) {
            BasicText(if (now?.playing == true) "ON THE AIR" else "TUNED TO", style = headline(14.sp, Ink.mid))
            BasicText(station.title.uppercase(), style = headline(40.sp).copy(lineHeight = 40.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val n = now
            val playing = n?.takeIf { it.title.isNotEmpty() && !it.title.equals(station.title, true) }?.let { listOf(it.title, it.artist).filter { s -> s.isNotEmpty() }.joinToString(" · ") }
            BasicText((playing ?: station.artist).lowercase(), style = pixel(13.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            BasicText("${ours.size} stations ink keeps, real djs from seattle to accra, and every genre's", style = pixel(13.sp, Ink.mid))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Word("TUNE IN ANYWHERE", headline(20.sp), Modifier.padding(end = 24.dp), touch = 8.dp) {
                ours.filter { it.song.id != station?.id }.randomOrNull()?.let { Playback.play(listOf(it.song), 0) }
            }
            if (station != null) Word(if (now?.playing == true) "pause" else "play", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.toggle() }
        }
    }
}

/** Ink at 55% over black, opaque: the tower's rings with no station on. */
private val QUIET: Int = Ink.ink.toArgb().let { c ->
    fun k(v: Int) = (v * .55f).toInt()
    (255 shl 24) or (k(c shr 16 and 255) shl 16) or (k(c shr 8 and 255) shl 8) or k(c and 255)
}
