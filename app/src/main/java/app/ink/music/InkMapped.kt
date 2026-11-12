package app.ink.music

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ink mapped, the phone's (2026-11-11; [InkMap] draws it): your listening as land, its highest named, and under it
 * what's said of you in words: how you're listening lately, what's in rotation, when you listen, who you've turned
 * down. A tile in lists beside your year, a word in the menu, a card to share.
 */
object Mapped {
    /** What the map is made of, read from what's kept on the phone. */
    class Read(
        val artists: List<Pair<String, Double>>, val appetite: Double, val rotation: List<String>, val hours: String?,
        val notForYou: List<String>, val record: Song?,
    )

    // the last map drawn, by its makings and size: the tile and the page come back to it at once
    private val drawn = android.util.LruCache<String, Pair<ImageBitmap, InkMap.Drawn>>(4)

    suspend fun read(c: Context): Read = withContext(Dispatchers.IO) {
        val t = Fresh.taste.value
        val heard = History.read(c)
        // each artist as they're written (the latest heard), their nearness from your taste; and the ones brought in
        val names = LinkedHashMap<String, String>()
        heard.forEach { h -> h.artist.substringBefore(", ").trim().takeIf { it.isNotEmpty() }?.let { names[Taste.bare(it)] = it } }
        Fresh.brought.value.forEach { b -> b.artists.keys.forEach { names.putIfAbsent(Taste.bare(it), it) } }
        val artists = names.values.map { it to t.of(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(40)
        // in rotation: the songs most in it now (lately counting more), their artists
        val byId = heard.associateBy { it.id }
        val rotation = LoveKeeping.mostPlayed(heard.map { it.id to it.at }, System.currentTimeMillis(), n = 60)
            .mapNotNull { byId[it]?.artist?.substringBefore(", ")?.trim() }.distinctBy { it.lowercase() }.take(4)
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val hours = InkMap.hours(heard.filter { now - it.at < 60L * 86_400_000 }.map { java.time.Instant.ofEpochMilli(it.at).atZone(zone).hour })
        // the record you've played most this month, for the map's colour and the card's foot
        val record = heard.filter { now - it.at < 30L * 86_400_000 && it.album.isNotBlank() }.groupBy { it.album to it.artist.substringBefore(", ") }
            .maxByOrNull { it.value.size }?.value?.lastOrNull()?.let { Library.song(it.id) ?: YouTubeMusic.known(it.id) }
        Read(artists, t.appetite, rotation, hours, Fresh.turnedDownNames(c), record)
    }

    /** The map drawn at `side` px in the record's colour (blocking), kept. */
    suspend fun draw(c: Context, r: Read, side: Int): Pair<ImageBitmap, InkMap.Drawn> {
        val accent = r.record?.let { runCatching { Covers.of(c, it, 240) }.getOrNull()?.accent } ?: Ink.ink
        val key = "${r.artists.hashCode()}|${r.appetite}|${accent.value}|$side"
        drawn.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            val d = InkMap.draw(r.artists, r.appetite, accent.toArgb(), side)
            (Bitmap.createBitmap(d.pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap() to d).also { drawn.put(key, it) }
        }
    }
}

/** The map in a lists tile: the land alone, no names, in a hairline frame. */
@Composable
fun MappedArt() {
    val ctx = LocalContext.current
    val map by produceState<ImageBitmap?>(null) { val r = Mapped.read(ctx); if (r.artists.isNotEmpty()) value = Mapped.draw(ctx, r, 360).first }
    Box(Modifier.fillMaxSize().background(Ink.black).border(1.dp, Ink.line)) {
        map?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** The map, its highest named over it (each on a black tab beside its summit's dot). */
@Composable
private fun NamedMap(img: ImageBitmap, d: InkMap.Drawn) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).background(Ink.black)) {
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        d.peaks.forEach { p ->
            val x = maxWidth * p.x; val y = maxWidth * p.y
            Box(Modifier.offset(x - 3.dp, y - 3.dp).size(6.dp).background(Ink.ink))
            // the name to the summit's right, or its left near the right edge
            val right = p.x < .62f
            Box(Modifier.align(if (right) Alignment.TopStart else Alignment.TopEnd)
                .offset(if (right) x + 8.dp else -(maxWidth - x) - 8.dp, y - 10.dp)
                .background(Ink.black).padding(horizontal = 4.dp, vertical = 1.dp)) {
                BasicText(p.name.lowercase(), style = pixel(12.sp), maxLines = 1)
            }
        }
    }
}

/** Ink mapped, its page: the map, then what's said of you in words, and sharing it. */
@Composable
fun MappedPage(pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val read by produceState<Mapped.Read?>(null, refresh) { value = Mapped.read(ctx) }
    val drawn by produceState<Pair<ImageBitmap, InkMap.Drawn>?>(null, read) { read?.takeIf { it.artists.isNotEmpty() }?.let { value = Mapped.draw(ctx, it, 720) } }
    val list = pageList()
    FrostedPage { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 40.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                BasicText("INK MAPPED", style = headline(52.sp), modifier = Modifier.weight(1f))
                CloseArrow { pages.close() }
            }
            BasicText("your listening as land: a hill for each artist you're near, the nearer the higher", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(20.dp))
            val r = read
            val d = drawn
            when {
                r == null -> Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
                r.artists.isEmpty() -> BasicText("play a while, and your map rises here", style = pixel(14.sp))
                d != null -> NamedMap(d.first, d.second)
                else -> Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
            }
            if (r != null && r.artists.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                // how you're listening lately: the stretch picked for you, as a word and a share of ten
                Said("lately", InkMap.mood(r.appetite).uppercase(),
                    "${(r.appetite * 10).toInt()} in ten of what's picked for you is new to you")
                if (r.rotation.isNotEmpty()) Said("in rotation", null, r.rotation.joinToString(", ") { it.lowercase() })
                r.hours?.let { Said("when", null, it) }
                Column(Modifier.padding(bottom = 18.dp)) {
                    BasicText("not for you", style = pixel(12.sp, Ink.mid))
                    if (r.notForYou.isEmpty()) BasicText("none said", style = pixel(14.sp))
                    else {
                        BasicText("a tap lets one back", style = pixel(12.sp, Ink.dim))
                        r.notForYou.forEach { a -> Word(a.lowercase(), pixel(14.sp), touch = 6.dp) { Fresh.letBack(ctx, a); refresh++ } }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (d != null) Word("SHARE YOUR MAP", headline(28.sp), touch = 10.dp) {
                    scope.launch { ShareCard.shareMapped(ctx, d.second, r) }
                }
            }
        }
    } }
}

@Composable
private fun Said(label: String, big: String?, line: String) {
    Column(Modifier.padding(bottom = 18.dp)) {
        BasicText(label, style = pixel(12.sp, Ink.mid))
        big?.let { BasicText(it, style = headline(24.sp)) }
        BasicText(line, style = pixel(14.sp))
    }
}
