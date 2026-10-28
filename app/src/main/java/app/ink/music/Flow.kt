package app.ink.music

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * The records turned sideways: their covers in a row to flick through, on black, the whole screen (the bars hidden
 * till a swipe brings them). The one in the middle flat and facing you; the rest turned and stacked close either
 * side. In the order chosen upright (by color, a spectrum). A tap on the middle one plays it; on another, brings it
 * to the middle. No reflections or shadows: covers flat on black. (2026-10-28: a first try had the covers going to
 * dither and the middle one's backdrop behind: heavy, and slow to flick.)
 */
@Composable
fun RecordFlow(records: List<Album>, onOpen: (Album) -> Unit) {
    if (records.isEmpty()) return
    val pager = rememberPagerState { records.size }
    val scope = rememberCoroutineScope()
    val middle = records[pager.currentPage.coerceIn(records.indices)]
    val density = LocalDensity.current
    // the middle cover turned over to its tracklist (held), back again when another comes to the middle
    var flipped by remember { mutableStateOf<String?>(null) }
    // a tick under the thumb as each cover passes the middle (the clickwheel's)
    val view = LocalView.current
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.drop(1).collect { flipped = null; view.performHapticFeedback(FLOW_TICK) }
    }
    // the whole screen: the status and navigation bars away while it's up
    val window = (LocalContext.current as? android.app.Activity)?.window
    DisposableEffect(window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        val side = minOf(maxHeight * .6f, maxWidth * .34f)
        val sides = (maxWidth - side) / 2
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            HorizontalPager(
                pager, Modifier.fillMaxWidth().height(side),
                pageSize = PageSize.Fixed(side),
                contentPadding = PaddingValues(horizontal = sides),
                beyondViewportPageCount = 6,
                key = { records[it].id },
                // a flick coasts on, past as many covers as it's fast (a page a flick felt stuck), then settles softly
                flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                    pager,
                    pagerSnapDistance = androidx.compose.foundation.pager.PagerSnapDistance.atMost(50),
                    decayAnimationSpec = androidx.compose.animation.rememberSplineBasedDecay(),
                    snapAnimationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessLow),
                ),
            ) { page ->
                val a = records[page]
                FlowCover(
                    a, side, middle = page == pager.currentPage, flipped = flipped == a.id && page == pager.currentPage,
                    onFlip = { flipped = if (flipped == a.id) null else a.id },
                    toMiddle = { scope.launch { pager.animateScrollToPage(page, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) } },
                    modifier = Modifier
                        .zIndex(-abs(page - pager.currentPage - pager.currentPageOffsetFraction))
                        .graphicsLayer {
                            // how far from the middle, in covers (- left, + right)
                            val d = page - pager.currentPage - pager.currentPageOffsetFraction
                            val far = abs(d).coerceAtMost(1f)
                            // where it's drawn: the first beside the middle well clear of it, the rest stacked close
                            val first = size.width * .62f
                            val then = size.width * .2f
                            val x = if (abs(d) <= 1f) d * first else sign(d) * (first + (abs(d) - 1f) * then)
                            translationX = x - d * size.width
                            // turned to face the middle
                            rotationY = -d.coerceIn(-1f, 1f) * 65f
                            cameraDistance = 10f * density.density
                            scaleX = 1f - far * .08f; scaleY = scaleX
                            alpha = (7f - abs(d)).coerceIn(0f, 1f)
                        },
                )
            }
            Spacer(Modifier.height(16.dp))
            // the middle record's words, under it
            AnimatedContent(
                middle, Modifier.fillMaxWidth(),
                transitionSpec = { fadeIn(tween(Motion.ENTER, easing = Motion.expo)) togetherWith fadeOut(tween(Motion.EXIT)) },
                label = "flow words",
            ) { r ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(r.title.uppercase(), style = headline(20.sp).copy(textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(r.artist.lowercase() + if (r.year > 0) "   ${r.year}" else "", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.padding(start = 24.dp))
                        Word("open", pixel(12.sp, Ink.mid), touch = 8.dp) { onOpen(r) }
                    }
                }
            }
        }
    }
}

/**
 * One cover in the flow. In the middle: a tap plays it; held, it turns over to its tracklist (a song tapped plays from
 * there, the sleeve tapped turns it back); pulled down, the needle's dropped: it goes down and away as it plays, and
 * comes back up. The record playing now wears the progress line along its top edge, as covers do in ink.
 */
@Composable
private fun FlowCover(
    a: Album, side: Dp, middle: Boolean, flipped: Boolean, modifier: Modifier,
    onFlip: () -> Unit, toMiddle: () -> Unit,
) {
    val flip by animateFloatAsState(if (flipped) 180f else 0f, tween(Motion.ENTER + 120, easing = Motion.expo), label = "flip")
    val drop = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val away = with(density) { side.toPx() }
    Box(
        modifier.size(side)
            .graphicsLayer {
                rotationY = flip
                cameraDistance = 10f * density.density
                translationY = drop.value
                alpha = 1f - (drop.value / (away * 1.2f)).coerceIn(0f, 1f)
            }
            .pointerInput(middle, flipped) {
                if (!middle || flipped) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (drop.value > away * .25f) {
                                // the needle dropped: it plays, the cover goes down and away, then rises back in
                                Playback.play(a.songs, 0)
                                drop.animateTo(away * 1.3f, tween(Motion.EXIT + 80, easing = Motion.expo))
                                drop.snapTo(away * .2f)
                                drop.animateTo(0f, tween(Motion.ENTER, easing = Motion.expo))
                            } else drop.animateTo(0f, spring())
                        }
                    },
                    onDragCancel = { scope.launch { drop.animateTo(0f, spring()) } },
                ) { change, dy -> change.consume(); scope.launch { drop.snapTo((drop.value + dy).coerceAtLeast(0f)) } }
            }
            .combinedClickable(
                remember { MutableInteractionSource() }, indication = null,
                onLongClick = { if (middle) onFlip() },
                onClick = felt { if (!middle) toMiddle() else if (flipped) onFlip() else Playback.play(a.songs, 0) },
            ),
    ) {
        if (flip <= 90f) {
            CoverOf(a.songs.firstOrNull(), a.title, Modifier.fillMaxSize())
            PlayingLine(a, Modifier.align(Alignment.TopStart))
        } else Tracklist(a, Modifier.fillMaxSize().graphicsLayer { rotationY = 180f })
    }
}

/** The record's back: black, its songs in a list, one tapped plays from there. */
@Composable
private fun Tracklist(a: Album, modifier: Modifier) {
    Column(modifier.background(Ink.black).border(1.dp, Ink.line).padding(14.dp)) {
        BasicText(a.title.uppercase(), style = headline(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            itemsIndexed(a.songs, key = { _, s -> s.id }) { i, s ->
                Row(
                    Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Playback.play(a.songs, i) })
                        .padding(vertical = 5.dp),
                ) {
                    BasicText("${if (s.track > 0) s.track else i + 1}", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(26.dp))
                    BasicText(s.title.lowercase(), style = pixel(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The record playing now: the line along its cover's top edge, filling in the cover's color. */
@Composable
private fun PlayingLine(a: Album, modifier: Modifier) {
    val now by Playback.now.collectAsState()
    val n = now ?: return
    if (n.song?.albumId != a.id || n.stopped) return
    val accent = rememberCover(a.songs.firstOrNull())?.accent ?: Ink.ink
    val pos by produceState(n.positionNow(), n) {
        while (true) { value = n.positionNow(); if (!n.moving) break; delay(500) }
    }
    val frac = if (n.duration > 0) (pos.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(2.dp).background(Ink.line)) {
        Box(Modifier.fillMaxWidth(frac).height(2.dp).background(accent))
    }
}

private val FLOW_TICK = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
else HapticFeedbackConstants.CLOCK_TICK
