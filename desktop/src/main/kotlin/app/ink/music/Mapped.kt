package app.ink.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.exp

/**
 * Ink mapped on the computer (2026-11-12, Bronson: the phone's ink mapped on the desktop, then "interactive, beautiful
 * and fun"): your listening as land, the whole window of it. Drawn live on the graphics card (the hills as uniforms, the
 * contour lines worked out per pixel), so it can move: it rises ring by ring as it opens and breathes slowly after; a
 * summit hovered lights its own rings and shows who; clicked opens them, clicked twice plays them; land clicked plays
 * whoever's nearest there; what's playing stands on it (you are here); the land leans toward the pointer; and a line
 * along the foot drags it back through the months.
 */
object Mapped {
    /** At most this many hills (the shader's array). */
    const val SLOTS = 24

    class Read(
        val names: List<String>,
        /** Each month's nearness of each of [names] (oldest first, the last now), and when each month was. */
        val months: List<FloatArray>, val at: List<Long>,
        val appetite: Double, val rotation: List<String>, val hours: String?, val record: Song?,
        val plays: Map<String, Int>, val since: Map<String, Long>,
        /** Who you are lately (InkMap.persona). */
        val persona: InkMap.Persona,
    )

    fun read(): Read {
        val t = Fresh.taste.value
        val heard = History.read()
        val now = System.currentTimeMillis()
        val bare = { a: String -> Taste.bare(a.substringBefore(", ").trim()) }
        // the artists: the nearest now, then any that were near in the months before (they rise and sink as you drag)
        val written = LinkedHashMap<String, String>()
        heard.forEach { h -> h.artist.substringBefore(", ").trim().takeIf { it.isNotEmpty() }?.let { written[bare(it)] = it } }
        Fresh.brought.value.forEach { b -> b.artists.keys.forEach { a -> a.substringBefore(", ").trim().takeIf { it.isNotEmpty() }?.let { written.putIfAbsent(bare(it), it) } } }
        val first = heard.minOfOrNull { it.at } ?: now
        val span = minOf((now - first).coerceAtLeast(1L), 365L * 86_400_000)
        // up to a year back, a point a month (fewer for a shorter history)
        val count = ((span / (30L * 86_400_000)).toInt() + 1).coerceIn(1, 12)
        val at = (0 until count).map { i -> if (count == 1) now else now - (count - 1 - i) * span / (count - 1) }
        val plays = heard.map { Taste.Play(it.artist, it.at, it.ms) }
        val tastes = at.map { m -> if (m == now) t else Taste.of(plays.filter { it.at <= m }, now = m) }
        val names = written.values.distinctBy { it.lowercase() }
            .map { n -> n to tastes.maxOf { it.of(n) } }.filter { it.second > 0 }
            .sortedByDescending { t.of(it.first) * 2 + it.second }.take(SLOTS).map { it.first }
        val months = tastes.map { tt -> FloatArray(names.size) { tt.of(names[it]).toFloat() } }
        val byId = heard.associateBy { it.id }
        val rotation = LoveKeeping.mostPlayed(heard.map { it.id to it.at }, now, n = 60)
            .mapNotNull { byId[it]?.artist?.substringBefore(", ")?.trim() }.distinctBy { it.lowercase() }.take(4)
        val zone = java.time.ZoneId.systemDefault()
        val hours = InkMap.hours(heard.filter { now - it.at < 60L * 86_400_000 }.map { java.time.Instant.ofEpochMilli(it.at).atZone(zone).hour })
        val record = heard.filter { now - it.at < 30L * 86_400_000 && it.album.isNotBlank() }.groupBy { it.album to it.artist.substringBefore(", ") }
            .maxByOrNull { it.value.size }?.value?.lastOrNull()?.let { Library.song(it.id) }
        val counts = heard.groupingBy { bare(it.artist) }.eachCount()
        val since = heard.groupBy { bare(it.artist) }.mapValues { e -> e.value.minOf { it.at } }
        val listens = heard.map { h ->
            val z = java.time.Instant.ofEpochMilli(h.at).atZone(zone)
            InkMap.Listen(h.artist, h.album, h.id, h.at, h.ms, z.hour, z.dayOfWeek.value >= 6)
        }
        return Read(names, months, at, t.appetite, rotation, hours, record,
            names.associateWith { counts[bare(it)] ?: 0 }, names.associateWith { since[bare(it)] ?: 0L },
            InkMap.persona(listens, t.appetite, now) { Genres.tags(it) })
    }

    /** Where an artist stands, in the map's own units (its height 1, its width `aspect`): the phone's place, spread wide. */
    fun place(name: String, aspect: Float): Offset {
        val (x, y) = InkMap.place(name, Genres.tags(name))
        return spread(x, y, aspect)
    }

    /**
     * Where each of `names` stands on a map `aspect` wide (2026-11-12, Bronson: so many artists in one area): placed by
     * sound as the phone places them, then spread within their genre by their finer tags (each tag pulls its own way, so
     * shoegaze and bedroom pop part while kin stay near), then the whole fitted to the window: the map frames your land,
     * not the whole wheel of genres, so an indie listener's indie fills the screen.
     */
    fun layout(names: List<String>, aspect: Float): List<Offset> {
        val raw = names.map { n ->
            val tags = Genres.tags(n)
            val (x, y) = InkMap.place(n, tags)
            var dx = 0.0; var dy = 0.0; var sum = 0.0
            tags?.take(5)?.forEachIndexed { i, t ->
                var h = 0x811c9dc5.toInt(); for (ch in t) { h = h xor ch.code; h *= 0x01000193 }
                val a = (h ushr 8 and 0xFFFF) / 65535.0 * 2 * Math.PI; val w = 1.0 / (i + 1)
                dx += kotlin.math.cos(a) * w; dy += kotlin.math.sin(a) * w; sum += w
            }
            if (sum > 0) Offset((x + dx / sum * .09).toFloat(), (y + dy / sum * .09).toFloat()) else Offset(x.toFloat(), y.toFloat())
        }
        if (raw.size == 1) return listOf(Offset(aspect / 2f, .45f))
        val x0 = raw.minOf { it.x }; val x1 = raw.maxOf { it.x }; val y0 = raw.minOf { it.y }; val y1 = raw.maxOf { it.y }
        // the land between these, the foot left to the words and the months
        val left = .14f; val right = aspect - .14f; val upper = .2f; val lower = .7f
        return raw.map { p ->
            Offset(left + (p.x - x0) / (x1 - x0).coerceAtLeast(.05f) * (right - left),
                upper + (p.y - y0) / (y1 - y0).coerceAtLeast(.05f) * (lower - upper))
        }
    }

    /** The square map's 0..1 (its hills between .12 and .88) spread over a wide one, a margin kept at each edge. */
    fun spread(x: Double, y: Double, aspect: Float): Offset =
        Offset((aspect / 2f + ((x - .5) / .38).toFloat() * (aspect / 2f - .16f)), (.16f + ((y - .12) / .76).toFloat() * .62f))

    /** A hill's width: the nearer, the broader (in the map's units). */
    fun radius(h: Float) = .028f + .036f * h

    /** Hill `i`'s summit: the highest ground within its own width of where it stands. */
    fun summit(i: Int, xy: List<Offset>, h: FloatArray): Offset {
        val r = radius(h[i]); var best = xy[i]; var high = -1f
        for (a in -8..8) for (b in -8..8) {
            val p = xy[i] + Offset(a * r / 8f, b * r / 8f)
            val v = field(p, xy, h).first
            if (v > high) { high = v; best = p }
        }
        return best
    }

    /** The land's height at `p` (its hills `xy`, their heights `h`), and each hill's part of it. */
    fun field(p: Offset, xy: List<Offset>, h: FloatArray): Pair<Float, FloatArray> {
        val parts = FloatArray(xy.size)
        var v = 0f
        for (i in xy.indices) {
            val r = radius(h[i]); val d = p - xy[i]
            val e = h[i] * exp(-(d.x * d.x + d.y * d.y) / (2 * r * r))
            parts[i] = e; v += e
        }
        return v to parts
    }

    /** The contour lines, worked out for each pixel from the hills (a sum of bells; its slope for an even line). */
    private const val LAND = """
        uniform float2 size;
        uniform float4 hills[24];
        uniform float top;
        uniform float reveal;
        uniform float time;
        uniform float breathe;
        uniform float hover;
        uniform float4 ink;
        uniform float halfw;
        half4 main(float2 pos) {
            float2 p = pos / size.y;
            float v = 0.0;
            float2 g = float2(0.0);
            float mine = 0.0;
            for (int i = 0; i < 24; i++) {
                float4 h = hills[i];
                float hh = h.z * (1.0 + breathe * sin(time * 0.5 + float(i) * 1.7));
                float r = 0.028 + 0.036 * h.z;
                float2 d = p - h.xy;
                float k = 1.0 / (2.0 * r * r);
                float e = hh * exp(-dot(d, d) * k);
                v += e;
                g += -2.0 * k * d * e;
                if (float(i) == hover) { mine = e; }
            }
            float L = 14.0;
            float f = v / top * L;
            float b = floor(f + 0.5);
            if (b < 1.0 || b > L - 1.0) { return half4(0.0); }
            float slope = length(g) / top * L / size.y;
            float dist = abs(f - b) / max(slope, 0.000001);
            float a = clamp(halfw + 0.5 - dist, 0.0, 1.0) * clamp(reveal - b + 1.0, 0.0, 1.0);
            float t = b / (L - 1.0);
            float shade = t > 0.6 ? 1.0 : (t > 0.3 ? 0.7 : 0.4);
            if (hover >= 0.0) {
                float own = mine / max(v, 0.000001);
                shade = mix(shade * 0.3, 1.0, smoothstep(0.3, 0.75, own));
            }
            float k2 = a * shade;
            return half4(half3(ink.rgb * k2), half(k2));
        }
    """
    val effect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(LAND) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MappedPage(onClose: () -> Unit) {
    val taste by Fresh.taste.collectAsState()
    val tagged by GenrePicks.tagged.collectAsState()
    val read by produceState<Mapped.Read?>(null, taste, tagged) { value = withContext(Dispatchers.IO) { Mapped.read() } }
    LaunchedEffect(read?.names) { read?.let { r -> GenrePicks.tag(r.names) } }
    val r = read
    val accent = r?.record?.let { rememberCover(it, 240)?.accent } ?: Ink.ink
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // the land rising ring by ring as it opens, then breathing
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(r != null) { if (r != null) reveal.animateTo(14f, tween(1_600, easing = Motion.expo)) }
    val clock = rememberInfiniteTransition(label = "tide")
    val time by clock.animateFloat(0f, 1_000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing), RepeatMode.Restart), label = "time")

    // through the months: 1 is now; dragging moves it, let go it settles back
    val month = remember { Animatable(1f) }
    var dragging by remember { mutableStateOf(false) }

    var pointer by remember { mutableStateOf<Offset?>(null) }
    val ripples = remember { mutableStateListOf<Pair<Offset, Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>>() }

    Box(Modifier.fillMaxSize().background(Ink.black)) {
        if (r == null) return@Box
        if (r.names.isEmpty()) {
            Column(Modifier.padding(start = SIDE, top = 8.dp)) {
                Close(onClose)
                BasicText("INK MAPPED", style = headline(40.sp))
                BasicText("play a while, and your land rises here", style = pixel(14.sp), modifier = Modifier.padding(top = 12.dp))
            }
            return@Box
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
            val aspect = wPx / hPx
            // what's playing always has its hill (a newcomer to you stands on its own, not in the sea)
            val nowPlaying by Player.now.collectAsState()
            val playing = nowPlaying?.song?.artist?.substringBefore(", ")?.trim()?.takeIf { it.isNotEmpty() }
            val names = remember(r, playing) {
                if (playing == null || r.names.any { Taste.bare(it) == Taste.bare(playing) }) r.names
                else r.names.take(Mapped.SLOTS - 1) + playing
            }
            val xy = remember(names, aspect, tagged) { Mapped.layout(names, aspect) }
            // the heights at the month dragged to (between two months, between their heights)
            val m = month.value * (r.months.size - 1)
            val lo = m.toInt().coerceIn(0, r.months.size - 1); val hi = (lo + 1).coerceAtMost(r.months.size - 1); val f = m - lo
            val heights = FloatArray(names.size) { i -> if (i < r.names.size) r.months[lo][i] * (1 - f) + r.months[hi][i] * f else .6f }
            val top = xy.indices.maxOfOrNull { Mapped.field(xy[it], xy, heights).first }?.coerceAtLeast(.3f) ?: 1f
            // each hill's summit: the highest ground near where it stands (a neighbour pushes the top aside)
            val tops = remember(xy, month.value) { xy.indices.map { Mapped.summit(it, xy, heights) } }
            val world = pointer?.let { Offset(it.x / hPx, it.y / hPx) }
            // the summit nearest the pointer, within reach (crowded hills share their land, so "most of it" never picked one)
            val reach = with(density) { 56.dp.toPx() } / hPx
            val hovered = world?.let { summitNear(it, tops, heights, reach) }
            // the land leans a little toward the pointer
            val lean by animateOffsetAsState(
                pointer?.let { Offset((it.x / wPx - .5f) * 14f, (it.y / hPx - .5f) * 10f) } ?: Offset.Zero, tween(900, easing = Motion.expo), label = "lean")

            Box(Modifier.fillMaxSize().graphicsLayer { translationX = lean.x * density.density; translationY = lean.y * density.density }
                .onPointerEvent(PointerEventType.Move) { pointer = it.changes.first().position }
                .onPointerEvent(PointerEventType.Exit) { pointer = null }
                .pointerHoverIcon(if (hovered != null) PointerIcon.Hand else PointerIcon.Default)
                .pointerInput(tops, reach) {
                    detectTapGestures(
                        onDoubleTap = { p -> summitNear(Offset(p.x / hPx, p.y / hPx), tops, heights, reach)?.let { i -> playArtists(listOf(names[i])) } },
                        onTap = { p ->
                            val i = summitNear(Offset(p.x / hPx, p.y / hPx), tops, heights, reach)
                            if (i != null) Nav.open(Page.Artist(names[i]))
                            else {
                                // land, not a summit (or the sea): whoever's most of it there, mixed
                                val (_, parts) = Mapped.field(Offset(p.x / hPx, p.y / hPx), xy, heights)
                                val who = parts.withIndex().sortedByDescending { it.value }.take(3).filter { it.value > .02f }.map { names[it.index] }
                                if (who.isNotEmpty()) {
                                    val ring = Animatable(0f); ripples += p to ring
                                    scope.launch { ring.animateTo(1f, tween(1_100, easing = Motion.expo)); ripples.removeAll { it.second === ring } }
                                    playArtists(who)
                                }
                            }
                        })
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    val b = RuntimeShaderBuilder(Mapped.effect)
                    b.uniform("size", size.width, size.height)
                    val u = FloatArray(Mapped.SLOTS * 4)
                    for (i in xy.indices) { u[i * 4] = xy[i].x; u[i * 4 + 1] = xy[i].y; u[i * 4 + 2] = heights[i] }
                    b.uniform("hills", u)
                    b.uniform("top", top)
                    b.uniform("reveal", reveal.value)
                    b.uniform("time", time)
                    b.uniform("breathe", if (dragging) 0f else .05f)
                    b.uniform("hover", (hovered ?: -1).toFloat())
                    b.uniform("ink", accent.red, accent.green, accent.blue, 1f)
                    b.uniform("halfw", .75f * density.density)
                    val shader = b.makeShader()
                    drawIntoCanvas { c -> c.nativeCanvas.drawRect(org.jetbrains.skia.Rect.makeWH(size.width, size.height), org.jetbrains.skia.Paint().apply { this.shader = shader }) }
                    // a click on the land: rings going out from it, as the contours do
                    ripples.forEach { (at, ring) ->
                        val k = ring.value
                        for (j in 0..2) {
                            val s = (k - j * .15f).coerceIn(0f, 1f)
                            if (s > 0f) drawCircle(accent.copy(alpha = (1f - s) * .8f), radius = 140.dp.toPx() * s, center = at, style = Stroke(1.dp.toPx()))
                        }
                    }
                }
                // the summits named: a dot on each, a hairline to its name on a black tab. Each name measured and given a
                // place of its own (2026-11-12, Bronson: names never on top of each other): the hovered first, then the
                // highest, each trying the corners round its dot, then further out on a longer line; touching another
                // name, the dots or the window's edge, the next is tried; nowhere free, it waits for a hover
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                val nameStyle = pixel(13.sp)
                val order = (listOfNotNull(hovered) + heights.withIndex().sortedByDescending { it.value }.filter { it.value > .15f }.map { it.index })
                    .distinct().take(14)
                val dot = with(density) { 6.dp.toPx() }
                val padX = with(density) { 4.dp.toPx() }; val padY = with(density) { 1.dp.toPx() }; val gap = with(density) { 4.dp.toPx() }
                val placed = remember(order, tops, wPx, hPx) {
                    val taken = ArrayList<androidx.compose.ui.geometry.Rect>()
                    // the dots themselves kept clear too
                    order.forEach { i -> val c = Offset(tops[i].x * hPx, tops[i].y * hPx); taken += androidx.compose.ui.geometry.Rect(c, dot) }
                    val out = LinkedHashMap<Int, Pair<androidx.compose.ui.geometry.Rect, Offset>>()
                    for (i in order) {
                        val c = Offset(tops[i].x * hPx, tops[i].y * hPx)
                        val m = measurer.measure(names[i].lowercase(), nameStyle, softWrap = false, maxLines = 1)
                        val w = m.size.width + padX * 2; val h = m.size.height + padY * 2
                        var spot: Pair<androidx.compose.ui.geometry.Rect, Offset>? = null
                        search@ for (reach in listOf(18f, 34f, 52f, 72f)) {
                            val d = with(density) { reach.dp.toPx() }
                            // up-right, up-left, down-right, down-left, then straight right and left
                            val tries = listOf(
                                Offset(c.x + d, c.y - d - h) to Offset(c.x + d, c.y - d),
                                Offset(c.x - d - w, c.y - d - h) to Offset(c.x - d, c.y - d),
                                Offset(c.x + d, c.y + d) to Offset(c.x + d, c.y + d),
                                Offset(c.x - d - w, c.y + d) to Offset(c.x - d, c.y + d),
                                Offset(c.x + d, c.y - h / 2) to Offset(c.x + d, c.y),
                                Offset(c.x - d - w, c.y - h / 2) to Offset(c.x - d, c.y),
                            )
                            for ((tl, end) in tries) {
                                val rect = androidx.compose.ui.geometry.Rect(tl, androidx.compose.ui.geometry.Size(w, h))
                                if (rect.left < 0 || rect.top < 0 || rect.right > wPx || rect.bottom > hPx) continue
                                val grown = rect.inflate(gap)
                                if (taken.any { it.overlaps(grown) }) continue
                                spot = rect to end; break@search
                            }
                        }
                        spot?.let { taken += it.first; out[i] = it }
                    }
                    out
                }
                Canvas(Modifier.fillMaxSize()) {
                    placed.forEach { (i, at) ->
                        val c = Offset(tops[i].x * hPx, tops[i].y * hPx)
                        val lit = hovered == null || hovered == i
                        drawRect(if (lit) Ink.ink else Ink.dim, Offset(c.x - dot / 2, c.y - dot / 2), androidx.compose.ui.geometry.Size(dot, dot))
                        drawLine(if (lit) Ink.mid else Ink.line, c, at.second, strokeWidth = 1f)
                    }
                }
                placed.forEach { (i, at) ->
                    val lit = hovered == null || hovered == i
                    Box(Modifier.offset { IntOffset(at.first.left.toInt(), at.first.top.toInt()) }
                        .background(Ink.black).padding(horizontal = 4.dp, vertical = 1.dp)) {
                        BasicText(names[i].lowercase(), style = pixel(13.sp, if (lit) Ink.ink else Ink.dim), maxLines = 1, softWrap = false)
                    }
                }
                // you are here: what's playing, standing on the land where its artist is (walking there when it changes)
                YouAreHere(names, tops, hPx, aspect)
            }

            // who's under the pointer: their picture in the dither, how much and since when
            hovered?.let { i -> pointer?.let { p -> HoverCard(names[i], r.plays[names[i]] ?: 0, r.since[names[i]] ?: 0L, p, wPx, hPx) } }

            // the words, in the land's quiet corners
            Column(Modifier.padding(start = SIDE, top = 8.dp)) {
                Close(onClose)
                BasicText("INK MAPPED", style = headline(40.sp))
            }
            Row(Modifier.align(Alignment.BottomStart).padding(start = SIDE, end = SIDE, bottom = 28.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.width(340.dp)) {
                    BasicText("lately", style = pixel(12.sp, Ink.mid))
                    BasicText(r.persona.name, style = headline(24.sp))
                    BasicText(r.persona.line, style = pixel(12.sp), modifier = Modifier.padding(top = 2.dp))
                    if (r.rotation.isNotEmpty()) BasicText(r.rotation.joinToString(", ") { it.lowercase() }, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                // the months: drag along the line, the land as it was; let go, back to now
                if (r.at.size > 1) Column(Modifier.width(360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val whenAt = r.at[(month.value * (r.at.size - 1)).toInt().coerceIn(0, r.at.size - 1)]
                    BasicText(if (month.value > .995f) "now" else java.time.Instant.ofEpochMilli(whenAt).atZone(java.time.ZoneId.systemDefault())
                        .let { "${it.month.name.lowercase()} ${it.year}" }, style = pixel(12.sp, if (dragging) Ink.ink else Ink.mid))
                    Spacer(Modifier.height(8.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp).pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> dragging = true; scope.launch { month.snapTo((o.x / size.width).coerceIn(0f, 1f)) } },
                                onDragEnd = { dragging = false; scope.launch { month.animateTo(1f, tween(1_400, easing = Motion.expo)) } },
                                onDragCancel = { dragging = false; scope.launch { month.animateTo(1f, tween(1_400, easing = Motion.expo)) } },
                            ) { change, _ -> scope.launch { month.snapTo((change.position.x / size.width).coerceIn(0f, 1f)) } }
                        }, contentAlignment = Alignment.CenterStart) {
                        val w = maxWidth
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
                        Box(Modifier.offset(x = w * month.value - 4.dp).size(8.dp).background(if (dragging) Ink.ink else Ink.mid))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        BasicText("a year ago", style = pixel(12.sp, Ink.dim), modifier = Modifier.weight(1f))
                        BasicText("now", style = pixel(12.sp, Ink.dim))
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.width(260.dp), horizontalAlignment = Alignment.End) {
                    r.hours?.let { BasicText("when", style = pixel(12.sp, Ink.mid)); BasicText(it, style = pixel(14.sp)) }
                }
            }
        }
    }
}

/** The summit nearest `p` (the map's units) within `reach` or its own hill's width, whichever's more; null for none. */
private fun summitNear(p: Offset, xy: List<Offset>, h: FloatArray, reach: Float): Int? =
    xy.indices.filter { h[it] > .05f }.map { it to (xy[it] - p).getDistance() }
        .filter { (i, d) -> d <= maxOf(reach, Mapped.radius(h[i]) * .7f) }.minByOrNull { it.second }?.first

/** Their songs played: yours first, then what YouTube Music knows best of theirs; several artists' taken in turn. */
private fun playArtists(names: List<String>) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        val songs = Library.songs.value
        val each = names.map { n ->
            val mine = songs.filter { s -> s.artist.split(", ").any { it.equals(n, true) } }.shuffled().take(12)
            mine.ifEmpty { kotlinx.coroutines.withTimeoutOrNull(6_000) { Artists.of(n).firstOrNull { it != null } }?.top.orEmpty().take(8) }
        }
        val mixed = buildList { for (k in 0 until (each.maxOfOrNull { it.size } ?: 0)) each.forEach { l -> l.getOrNull(k)?.let { add(it) } } }
        if (mixed.isNotEmpty()) withContext(Dispatchers.Main) { Player.play(mixed, 0) }
    }
}

/** Who's under the pointer: their picture, plays and since when, beside the pointer (kept inside the window). */
@Composable
private fun HoverCard(name: String, plays: Int, since: Long, p: Offset, wPx: Float, hPx: Float) {
    val density = LocalDensity.current
    val info by produceState<ArtistInfo?>(null, name) { Artists.of(name).collect { if (it != null) value = it } }
    val gap = with(density) { 24.dp.toPx() }
    val cardW = with(density) { 300.dp.toPx() }; val cardH = with(density) { 112.dp.toPx() }
    val x = (p.x + gap).let { if (it + cardW > wPx) p.x - cardW - gap else it }
    val y = (p.y + gap).coerceAtMost(hPx - cardH - 8)
    Row(Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.width(300.dp).background(Ink.black).border(1.dp, Ink.line).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val photo = info?.photo
        if (photo != null) Dithered(remember(photo) { faceSong(name, photo, photo = true) }, Modifier.size(92.dp), px = 240)
        else Box(Modifier.size(92.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            BasicText(name.uppercase(), style = headline(18.sp), maxLines = 2)
            if (plays > 0) BasicText("$plays plays", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
            if (since > 0) BasicText("since " + java.time.Instant.ofEpochMilli(since).atZone(java.time.ZoneId.systemDefault()).let { "${it.month.name.lowercase()} ${it.year}" },
                style = pixel(12.sp, Ink.mid))
        }
    }
}

/** What's playing, on the land: a blinking square round its artist's summit (their own name says who), walking over when the song changes. */
@Composable
private fun YouAreHere(names: List<String>, xy: List<Offset>, hPx: Float, aspect: Float) {
    val now by Player.now.collectAsState()
    val artist = now?.song?.artist?.substringBefore(", ")?.trim() ?: return
    val i = names.indexOfFirst { Taste.bare(it) == Taste.bare(artist) }
    val target = if (i >= 0) xy[i] else Mapped.place(artist, aspect)
    val at = remember { Animatable(target, Offset.VectorConverter) }
    LaunchedEffect(target) { at.animateTo(target, tween(1_400, easing = Motion.expo)) }
    val blink = rememberInfiniteTransition(label = "here")
    val a by blink.animateFloat(1f, .25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blink")
    val density = LocalDensity.current
    val px = at.value.x * hPx; val py = at.value.y * hPx
    val s = with(density) { 16.dp.toPx() }
    Box(Modifier.offset { IntOffset((px - s / 2).toInt(), (py - s / 2).toInt()) }.size(16.dp).graphicsLayer { alpha = a }.border(2.dp, Ink.ink))
}
