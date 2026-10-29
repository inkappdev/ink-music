package app.ink.music

import androidx.compose.foundation.Image
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/** The tower's dots as a picture (unfiltered, 3 dp a dot). */
private fun towerFrame(w: Int, h: Int, frame: Int, dot: Int, bolts: Boolean = false): ImageBitmap {
    val out = RadioTower.pixels(w, h, .2f, frame, Ink.ink.toArgb(), dot, bolts)
    // ARGB ints as Skia's BGRA bytes (little-endian: the same memory)
    val bytes = java.nio.ByteBuffer.allocate(out.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(out) }.array()
    return org.jetbrains.skia.Image.makeRaster(
        org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE), bytes, w * 4,
    ).toComposeImageBitmap()
}

/** Ink at 55% over black, opaque: the tower's rings with no station on. */
private val QUIET: Int = Ink.ink.toArgb().let { c ->
    fun k(v: Int) = (v * .55f).toInt()
    (255 shl 24) or (k(c shr 16 and 255) shl 16) or (k(c shr 8 and 255) shl 8) or k(c and 255)
}

/**
 * Radio's head: the tower sending across the page, and beside it what's on: the station you're tuned to and what
 * it's playing, else how many stations ink keeps and where; tune in anywhere (one of ours, by chance).
 */
@Composable
fun RadioHead() {
    val now by Player.now.collectAsState()
    val air by Player.onAir.collectAsState()
    val station = now?.song?.takeIf { it.station }
    val moving = station != null && now?.playing == true
    val ours = remember { Stations.curated() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(340.dp)) {
        val density = LocalDensity.current.density
        val wPx = maxWidth.value * density; val hPx = 340 * density
        // 3 dp dots
        val w = (wPx / (3 * density)).toInt().coerceAtLeast(60); val h = (hPx / (3 * density)).toInt().coerceAtLeast(40)
        // the rings in the station's own colour while one's on
        val dot = station?.let { StationSleeve.colorOf(it.id) } ?: QUIET
        val frames by produceState<List<ImageBitmap>?>(null, w, h, moving, dot) {
            value = withContext(Dispatchers.Default) { (0 until if (moving) RadioTower.FRAMES else 1).map { towerFrame(w, h, it, dot) } }
        }
        var frame by remember { mutableIntStateOf(0) }
        LaunchedEffect(moving, frames) {
            val count = frames?.size ?: return@LaunchedEffect
            while (moving && count > 1) { delay(220); frame = (frame + 1) % count }
        }
        // three clicks on the tower: lightning off its mast, and a station by chance (an easter egg)
        var taps by remember { mutableIntStateOf(0) }
        var lastTap by remember { mutableLongStateOf(0L) }
        var struck by remember { mutableLongStateOf(0L) }
        val bolt by produceState<ImageBitmap?>(null, w, h, dot) { value = withContext(Dispatchers.Default) { towerFrame(w, h, 0, dot, bolts = true) } }
        var flash by remember { mutableStateOf(false) }
        LaunchedEffect(struck) {
            if (struck == 0L) return@LaunchedEffect
            repeat(5) { flash = true; delay(90); flash = false; delay(70) }
            ours.filter { it.song.id != station?.id }.randomOrNull()?.let { Player.play(listOf(it.song)) }
        }
        val tower = Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {
            val t = System.currentTimeMillis()
            taps = if (t - lastTap < 500) taps + 1 else 1
            lastTap = t
            if (taps >= 3) { taps = 0; struck = t }
        }
        val shownFrame = if (flash) bolt else frames?.let { f -> f[frame % f.size] }
        shownFrame?.let { Image(it, null, tower, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
        // the words, on black, at the right
        // on a black tab, as a sleeve's name is: the rings run behind it, and type over the dots doesn't read
        Column(Modifier.fillMaxWidth(.5f).align(Alignment.BottomEnd).padding(bottom = 18.dp).background(Ink.black).padding(start = 18.dp, top = 12.dp, bottom = 10.dp)) {
            if (station != null) {
                BasicText(if (moving) "ON THE AIR" else "TUNED TO", style = headline(16.sp, Ink.mid))
                BasicText(station.title.uppercase(), style = headline(56.sp).copy(lineHeight = 54.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val playing = air?.let { listOfNotNull(it.title, it.artist).joinToString(" · ") } ?: station.artist
                BasicText(playing.lowercase(), style = pixel(13.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                BasicText("RADIO", style = headline(72.sp))
                BasicText("${ours.size} stations ink keeps, real djs from seattle to accra, and every genre's", style = pixel(13.sp, Ink.mid))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Word("TUNE IN ANYWHERE", headline(20.sp), touch = 8.dp) {
                    ours.filter { it.song.id != station?.id }.randomOrNull()?.let { Player.play(listOf(it.song)) }
                }
                if (station != null) Word(if (moving) "pause" else "play", pixel(14.sp, Ink.mid), touch = 8.dp) { Player.toggle() }
            }
        }
    }
}
