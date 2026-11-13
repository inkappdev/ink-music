package app.ink.music

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay

/** The window, for the player's whole screen. */
object Win {
    var state: WindowState? = null
    /** The window's size and place before full screen, put back after (the Mac sometimes left it its smallest). */
    var before: DpSize? = null
    var beforeAt: WindowPosition? = null
    private var was: WindowPlacement = WindowPlacement.Floating
    /** The main window itself (Main sets it), for Windows' full screen ([WinFull]). */
    var window: java.awt.Window? = null
    val full get() = covering || state?.placement == WindowPlacement.Fullscreen

    /** Windows' full screen as ink makes it ([full]): the window over the whole screen, still an ordinary window. */
    private var covering by androidx.compose.runtime.mutableStateOf(false)

    fun full(on: Boolean) {
        val s = state ?: return
        // Windows: not Java's full screen, which is exclusive there, and Windows minimizes an exclusive window as soon as
        // another takes the focus (2026-11-12, a tester: opening a folder to drag a song in, ink music minimized itself;
        // brought back, it was full screen again, "maximizing itself"; and minimize fought it). Its frame taken off and
        // laid over the whole monitor instead, as a browser does (WinFull), and put back as it was
        if (Home.windows) {
            if (on == covering) return
            val w = window
            if (w != null) {
                // ink's own bar's minimise, maximise and close gone while it covers the screen (InkTitle.full): taken
                // before the frame comes off, put back after it's on again
                if (on) InkTitle.full(w, true)
                if (if (on) WinFull.enter(w) else WinFull.leave(w)) { covering = on; if (!on) InkTitle.full(w, false); return }
                if (on) InkTitle.full(w, false)
            }
            covering = on
            if (on) {
                was = s.placement; before = s.size; beforeAt = s.position
                val b = java.awt.Frame.getFrames().firstOrNull { it.title == "ink music" && it.isDisplayable }?.graphicsConfiguration?.bounds
                    ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                s.placement = WindowPlacement.Floating
                s.position = WindowPosition(b.x.dp, b.y.dp)
                s.size = DpSize(b.width.dp, b.height.dp)
            } else {
                s.placement = was
                if (was == WindowPlacement.Floating) { before?.let { s.size = it }; beforeAt?.takeIf { it.isSpecified }?.let { s.position = it } }
            }
            return
        }
        if (on == full) return   // closing the player when it wasn't full screen: the window left as it is (zoomed stays zoomed)
        if (on) {
            was = s.placement
            if (s.placement == WindowPlacement.Floating) { before = s.size; beforeAt = s.position }
            s.placement = WindowPlacement.Fullscreen
        } else {
            s.placement = was
            // once the Mac's done leaving full screen, the size it had (it can come back at its smallest)
            if (was == WindowPlacement.Floating) javax.swing.Timer(700) {
                before?.takeIf { it.width >= Placed.LEAST.width }?.let { s.size = it }
                beforeAt?.takeIf { it.isSpecified }?.let { s.position = it }
            }.apply { isRepeats = false; start() }
        }
    }
}

/**
 * The player, the whole window (the phone's, laid sideways): the cover as big as the window lets it, over a print
 * of itself in the dither set a little down and right; the line along the cover's top edge with the times over it
 * and what's heard between them (a lossless song's moment typed across the cover as it starts); the song beside it,
 * the pixel controls, up next, lyrics, stop, the volume, the lyrics as they're sung; the artist as the giant marquee
 * along the foot. Full screen from the corners at the top.
 */
@Composable
fun PlayerPage(open: (Page) -> Unit, onClose: () -> Unit) {
    val now by Player.now.collectAsState()
    val n = now ?: return Close(onClose)
    val cover = rememberCover(n.song, 1200)
    val accent by animateColorAsState(cover?.accent ?: Ink.ink, tween(Motion.ENTER), label = "accent")
    val heard by Player.heard.collectAsState()
    val on by Casting.on.collectAsState()
    // a lossless song's moment across the cover, while it types (the word between the times waits for it)
    var typing by remember { mutableStateOf(false) }
    var heardUp by remember { mutableStateOf(false) }
    val artist = n.song.takeUnless { it.station || it.episode }?.let { it.albumArtist.ifBlank { it.artist.substringBefore(", ") } }
    // an episode's words open its show, where a song's open its artist and record
    val show = remember(n.song.id) { if (n.song.episode) Pods.page(n.song) else null }
    // where it plays, dropped down from its speaker at the top (it was the whole window: 2026-11-10, Bronson)
    var speakersDown by remember { mutableStateOf(false) }
    // which way the last change went, for the push: on (a later song in the queue, from the right) or back (from the left)
    val was = remember { intArrayOf(n.index) }
    val dir = remember(n.song.id) { (if (n.index >= was[0]) 1 else -1).also { was[0] = n.index } }
    val behind by Looks.on.collectAsState()
    val coverLook by Moving.cover.collectAsState()
    val names by Marquees.on.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        // a window taller than it's wide and not wide (an upright screen): the name along the foot smaller, its room
        // the page's (at 150 it sat over the record / lyrics / up next words)
        val tall = maxHeight > maxWidth * 1.1f && maxWidth < 1000.dp
        // the background (settings, the look): the cover across the whole window in its effect
        if (!n.song.station && behind != Look.PLAIN) Backdrop(cover, behind, n.song, n.playing)
        // the artist's name, big, along the foot: a way to their page too
        Marquee(said(n).second, n.playing && names, Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null) { show?.let(open) ?: artist?.let { open(Page.Artist(it)) } }, size = if (tall) 84.sp else 150.sp)
        Column(Modifier.fillMaxSize()) {
            // where it plays (the pixel speaker, lit on one), the mini player, the whole screen; the way out at the top
            // right, as every page's (2026-11-10: it was top left here, top right there). No "queue" word: up next is
            // on this page already (2026-11-10, Bronson)
            Row(Modifier.fillMaxWidth().padding(start = SIDE - 8.dp, end = SIDE - 8.dp, top = TOP), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Pixel(SPEAKER, if (on != null) "playing on ${on!!.name}" else "play on", if (on != null || speakersDown) Ink.ink else Ink.mid) { speakersDown = !speakersDown }
                // the mini player from here too: out of the whole screen first, then the window away to the dock
                Pixel(SHRINK, "mini player", Ink.mid) { Win.full(false); Mini.open() }
                Pixel(if (Win.full) UNFULL else FULL, if (Win.full) "leave full screen" else "full screen", Ink.mid) { Win.full(!Win.full) }
                Spacer(Modifier.width(14.dp))
                // opened out of the now column, it goes back right into it (→); else back down (↓)
                if (PlayerSide.right) Pixel(BACK_RIGHT, "back into the column", Ink.ink, onClick = onClose)
                else CloseArrow(onClick = onClose)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = SIDE, end = SIDE, top = 8.dp, bottom = if (tall) 110.dp else 190.dp)) {
                // the cover over the words, not beside them, where beside it the words had a third of the width and the
                // song's name broke mid-word (2026-11-09)
                val upright = tall
                // the print's offset, a twentieth of the cover; the cover as big as that leaves room for
                val side: Dp = (if (upright) minOf(maxWidth * .6f, (maxHeight - 35.dp) * .44f)
                    else minOf((maxHeight - 17.dp - 18.dp) / 1.05f, maxWidth * .5f)).coerceAtLeast(120.dp)
                val off = side * .05f
                // a track change is the iPod push, as the phone's (2026-11-10, Bronson: "match the phone's animations"):
                // the cover and the words together, the next song in from the right, back from the left
                val push = { s: AnimatedContentTransitionScope<*>, dir: Int ->
                    with(s) {
                        (slideInHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { it * dir / 3 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))) togetherWith
                            (slideOutHorizontally(tween(Motion.EXIT, easing = Motion.expo)) { -it * dir / 3 } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)))
                    }
                }
                val coverPart: @Composable () -> Unit = {
                    Column(Modifier.width(side + off)) {
                        Scrubber(n, accent, Modifier.width(side)) {
                            // on a speaker, what's sent there: a click to its path too
                            val castQuality by Casting.quality.collectAsState()
                            HeardWord(if (on != null) sent(castQuality, n.song.id) else heard?.takeIf { it.id == n.song.id }, typing) { heardUp = !heardUp }
                        }
                        // each side of the push its own song's cover (the leaving one isn't drawn as the arriving one)
                        AnimatedContent(n.song, transitionSpec = { push(this, dir) }, contentKey = { it.id }, label = "push") { s ->
                            Box(Modifier.size(side + off)) {
                                // its print beside it, while there's no background (the background takes its place)
                                if (!s.station && behind == Look.PLAIN) Dithered(s, Modifier.offset(off, off).size(side), px = 1200)
                                var menuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
                                Box(Modifier.size(side).clickable(remember { MutableInteractionSource() }, indication = null) { Player.toggle() }
                                    .rightClick { menuAt = it }) {
                                    menuAt?.let { SongMenu(s, null, null, it, playing = s.id == n.song.id) { menuAt = null } }
                                    val density = androidx.compose.ui.platform.LocalDensity.current.density
                                    CoverOf(s, s.album.ifEmpty { s.title }, Modifier.fillMaxSize().graphicsLayer {
                                        // a DJ set's frame blurred inside its square only: unclipped, the blur spread a
                                        // smear past the cover over the page and the name along the foot (2026-11-12, Bronson)
                                        clip = SetHeard.isSet(s)
                                        renderEffect = (if (SetHeard.isSet(s)) Look.BLUR else coverLook)?.takeIf { !s.station }?.let { Effects.of(it, accent, density, 1f, size.width) }
                                    }, px = 1200, moving = n.playing && s.id == n.song.id)
                                    // the record's moving cover over it, where it has one (settings, the look)
                                    if (!s.station && !s.episode) MovingCover(s.albumArtist.ifBlank { s.artist.substringBefore(", ") }, s.album, s.title,
                                        accent, n.playing && s.id == n.song.id, Modifier.fillMaxSize())
                                    if (s.id == n.song.id) Moment(n, heard?.takeIf { it.id == n.song.id }) { typing = it }
                                    // a DJ set: each track as it's heard, typed across the cover (SetHeard)
                                    if (SetHeard.isSet(s)) SetSleeve(s.artist, s.title, open)
                                }
                            }
                        }
                    }
                }
                val wordsPart: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        val (title, by) = said(n)
                        // a station's song, said or heard (OnAirSong): its title opens find with it typed, its artist their
                        // page, the heart loves the song found (2026-11-12, Bronson: clickable wherever a station's song shows;
                        // the bar's and the column's were, the player's weren't, its artist not even shown)
                        val aired = rememberAired(n)
                        val airedSong = rememberAiredSong(n)
                        // pushed with the cover on a track change; a station's next song (the same station) rising in
                        AnimatedContent(
                            Triple(n.song, title, by),
                            transitionSpec = {
                                if (initialState.first.id != targetState.first.id) push(this, dir)
                                else (slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 4 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))) togetherWith
                                    (slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { -it / 4 } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)))
                            },
                            contentKey = { it.first.id + it.second },
                            label = "song",
                        ) { (s, title, by) ->
                            Column {
                                Row(verticalAlignment = Alignment.Top) {
                                    WholeWords(title.uppercase(), headline(44.sp), 3, Modifier.weight(1f, fill = false).then(
                                        if (s.station && aired != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                                            onClick = felt { OnAirSong.find(aired.title, aired.artist!!) }) else Modifier))
                                    Heart(if (s.station) airedSong ?: s else s, 3.dp, Modifier.padding(start = 8.dp))
                                }
                                Spacer(Modifier.height(12.dp))
                                if (s.episode) Word(by.lowercase(), pixel(14.sp)) { show?.let(open) }
                                else if (s.station && aired != null) Word(by.lowercase(), pixel(14.sp)) { open(Page.Artist(aired.artist!!.substringBefore(", "))) }
                                else if (!s.station) Word(by.lowercase(), pixel(14.sp)) { open(Page.Artist(s.albumArtist.ifBlank { s.artist.substringBefore(", ") })) }
                                if (s.album.isNotEmpty() && !s.episode) Word(s.album.lowercase(), pixel(14.sp, Ink.mid)) { open(Page.Record(s.albumId)) }
                                Spacer(Modifier.height(4.dp))
                                // an episode's day out ("oct 31"), a song's year
                                val day = if (s.episode && s.modified > 0) java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(s.modified)).lowercase() else null
                                BasicText(listOfNotNull(day ?: s.year.takeIf { it > 0 }?.toString(), on?.let { "on ${it.name.lowercase()}" }).joinToString("  ·  "),
                                    style = pixel(12.sp, Ink.mid))
                                // an easter egg's word, when one's due (on repeat, a 100th time, a promise kept)
                                EggsNow.line(s)?.let { BasicText(it, style = pixel(12.sp, Ink.ink), modifier = Modifier.padding(top = 6.dp)) }
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        // the controls, the volume beside them
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (n.song.episode) EpisodeControls(n, Modifier.width(320.dp)) else Controls(n, Modifier.width(320.dp))
                            Spacer(Modifier.width(32.dp))
                            Volume(line = 140.dp)
                        }
                        // the rule under the controls the song's line too, its times at its ends (2026-11-12, Bronson: a
                        // second line "somewhere on the right", in place of a band along the cover's foot he didn't like);
                        // a station's, nothing to go through, the plain rule
                        if (!n.song.station && n.duration > 0) {
                            Spacer(Modifier.height(4.dp))
                            SeekLine(n, accent, beat = false, ends = true)
                        } else {
                            Spacer(Modifier.height(18.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
                        }
                        Spacer(Modifier.height(14.dp))
                        // the inner sleeve (2026-10-29: the room beside the cover):
                        // the record, its lyrics, up next, its notes, chosen by a word; stop at the row's end
                        val lines = rememberLyrics(n.song)
                        var shows by remember(n.song.albumId) { mutableStateOf<String?>(null) }
                        // up next first (2026-11-09: it was the lyrics where found, else the record); an episode its notes
                        val panel = shows ?: if (n.song.episode) "episode" else "up next"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // an episode: its own notes where a song's record and lyrics are
                            val words = if (n.song.episode) listOf("episode", "up next", "notes")
                                else listOfNotNull("record".takeUnless { n.song.station }, "lyrics", "up next", "notes")
                            for (w in words)
                                Word(w, pixel(14.sp, if (w == panel) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 10.dp) { shows = w }
                            Spacer(Modifier.weight(1f))
                            // into one of your playlists (the phone's player's word), its page over the player
                            // a station's: the song it's playing, found
                            val listed = if (n.song.station) airedSong else n.song.takeIf { canAddToList(it) }
                            if (listed != null) Word("playlist", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 10.dp) { open(Page.AddTo(listed)) }
                            Word("stop", pixel(14.sp, if (n.playing) Ink.mid else Ink.dim), touch = 10.dp) { Player.stop(); onClose() }
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (panel) {
                                "record" -> RecordSide(n, open)
                                "episode" -> EpisodeNotes(n.song.id, Modifier.fillMaxSize())
                                "lyrics" -> {
                                    val pos by Player.position.collectAsState()
                                    val all = lines
                                    if (all.isNullOrEmpty()) BasicText(lyricsSaid(all), style = pixel(13.sp, Ink.mid))
                                    else LyricsBlock(all, all.indexOfLast { it.at <= pos + Lyrics.LEAD }, Modifier.fillMaxSize(), big = true, onSeek = Player::seek, position = pos)
                                }
                                "up next" -> Column {
                                    RadioWord()
                                    QueueList(open, rememberLazyListState(), PaddingValues(bottom = 16.dp), Modifier.fillMaxSize(), follow = true)
                                }
                                else -> Notes(n, heard?.takeIf { it.id == n.song.id }, on?.name)
                            }
                        }
                    }
                }
                if (upright) Column { coverPart(); Spacer(Modifier.height(20.dp)); wordsPart(Modifier.weight(1f)) }
                else Row { coverPart(); Spacer(Modifier.width(48.dp)); wordsPart(Modifier.weight(1f).padding(top = 17.dp)) }
            }
        }
        // what's heard: on a wide window a band rising over the player's foot, the path across it (SignalBand); else the
        // page of its own over the player, as on the phone
        val band = !tall && maxWidth >= 1100.dp
        AnimatedVisibility(
            heardUp && (heard != null || on != null),
            modifier = if (band) Modifier.align(Alignment.BottomStart) else Modifier,
            enter = if (band) slideInVertically(tween(Motion.ENTER * 2, easing = Motion.expo)) { it } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))
                else slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = if (band) slideOutVertically(tween(Motion.ENTER, easing = Motion.expo)) { it } + fadeOut(tween(Motion.EXIT, easing = Motion.expo))
                else slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) {
            val hh = heard?.takeIf { on == null && it.id == n.song.id }
            if (band) SignalBand(hh, { heardUp = false }) else SignalPath(hh) { heardUp = false }
        }
        // opened out of the now column: its right edge, the way back into it
        ColumnEdge(onClose)
        // where it plays: a small panel dropped down from the speaker at the top right, this computer and each speaker
        // found, the one playing lit; a click outside it, the speaker again, or one chosen rolls it back up
        if (speakersDown) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { speakersDown = false })
        AnimatedVisibility(
            speakersDown, Modifier.align(Alignment.TopEnd).padding(top = TOP + 40.dp, end = SIDE - 8.dp),
            enter = androidx.compose.animation.expandVertically(tween(Motion.ENTER, easing = Motion.expo), expandFrom = Alignment.Top) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = androidx.compose.animation.shrinkVertically(tween(Motion.EXIT, easing = Motion.expo), shrinkTowards = Alignment.Top) + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) {
            // as tall as its speakers; scrolled only where they'd run past half the window and a bit
            Column(
                Modifier.width(340.dp).heightIn(max = maxHeight * .55f).background(Ink.black).border(1.dp, Ink.line)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                BasicText("PLAY ON", style = headline(20.sp))
                Spacer(Modifier.height(10.dp))
                PlayOnList(onPicked = { speakersDown = false }, small = true)
            }
        }
    }
}

/** The cover across the whole window behind the player, in the look's effect, faded in at the look's own strength. */
@Composable
fun Backdrop(cover: Cover?, look: Look, song: Song? = null, playing: Boolean = false) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // a canvas playing over it: the still one not drawn under it (one background)
    var canvasUp by remember(song?.title, song?.artist) { mutableStateOf(false) }
    val canvasOn by Moving.canvasOn.collectAsState()
    val c = cover
    if (c != null && !(canvasUp && canvasOn)) {
        val fade = remember(c.image) { Animatable(0f) }
        LaunchedEffect(c.image, look) { fade.animateTo(look.alpha, tween(600)) }
        if (look == Look.BLUR) {
            // a lens's blur, the covers' and artist pages' (2026-11-11: one blur), its film grain under the same fade
            val key = "backdrop|${System.identityHashCode(c.image)}"
            val lens by androidx.compose.runtime.produceState(Grain.kept(key), key) {
                if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Grain.bokeh(key, c.image) }
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
                lens?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High) }
                FilmGrain(Modifier.fillMaxSize())
            }
        } else androidx.compose.foundation.Image(c.image, null, Modifier.fillMaxSize().graphicsLayer {
            alpha = fade.value
            renderEffect = Effects.of(look, c.accent, density, 1f, size.width, big = true)
        }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
    // the song's spotify canvas in its place, moving, in the same look
    if (song != null && !song.station && !song.episode) CanvasBehind(song.title, song.artist.substringBefore(", "), c?.accent ?: Ink.ink, playing, Modifier.fillMaxSize()) { canvasUp = true }
    // a canvas in the blur: film's grain over it, moving with it, as faint as it is
    if (look == Look.BLUR && canvasUp && canvasOn) FilmGrain(Modifier.fillMaxSize().graphicsLayer { alpha = look.alpha }, moving = playing)
}

/**
 * The times, then the line filling with the song in the cover's colour, ruled straight onto the cover's top edge, what's
 * heard dead centre between the times. The line is the [SeekLine]: the times light while it's held.
 */
@Composable
fun Scrubber(n: Player.Now, accent: Color, modifier: Modifier = Modifier, middle: @Composable () -> Unit = {}) {
    val pos by Player.position.collectAsState()
    val held = remember { mutableStateOf<Long?>(null) }
    val at = held.value
    val live = !n.song.station && n.duration > 0
    val shown = at ?: pos
    val times by animateColorAsState(if (at != null) Ink.ink else Ink.mid, tween(Motion.FADE), label = "times")
    // a handover into this song (transitions): the tempos, or said it's the crossfade, and MIXING, where the times are
    val mixing by Crossfade.mixing.collectAsState()
    val mix = mixing?.takeIf { it.into == n.song.id }
    val k = rememberMixing(mix)
    Column(modifier) {
        Box(Modifier.fillMaxWidth()) {
            if (k != null && mix != null) {
                BasicText(if (mix.bpmTo > 0) "${Math.round(mix.bpmFrom)} → ${Math.round(mix.bpmTo)} BPM" else "CROSSFADE", style = pixel(12.sp, Ink.ink),
                    modifier = Modifier.align(Alignment.CenterStart))
                BasicText("MIXING", style = pixel(12.sp, Ink.ink), modifier = Modifier.align(Alignment.CenterEnd))
            } else {
                BasicText(if (n.song.station) "live" else clock(shown), style = pixel(12.sp, times), modifier = Modifier.align(Alignment.CenterStart))
                Box(Modifier.align(Alignment.Center)) { middle() }
                if (live) BasicText("-" + clock((n.duration - shown).coerceAtLeast(0)), style = pixel(12.sp, times), modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        SeekLine(n, accent, held = held)
    }
}

/**
 * A song's line to go anywhere in it (2026-11-07: "an easier way to scrub songs on desktop"): under the mouse it thickens
 * and says the time a click would go to, the hand over it; a click goes there, a drag goes along (the song jumps on
 * letting go), the wheel or a trackpad's swipe moves five seconds a notch; ← and → ten (Main's keys). Its reach is
 * 24 dp over the line, the line itself on its foot (on the cover's top edge, in the player).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SeekLine(n: Player.Now, accent: Color, modifier: Modifier = Modifier, held: MutableState<Long?> = remember { mutableStateOf(null) }, beat: Boolean = true, ends: Boolean = false) {
    val pos by Player.position.collectAsState()
    val live = !n.song.station && n.duration > 0
    var hover by remember { mutableStateOf<Float?>(null) }   // where the mouse is along it, 0 to 1
    var width by remember { mutableStateOf(1) }
    /** The wheel's last aim along the line and when, so quick notches add up. */
    val wheeled = remember(n.song.id) { longArrayOf(0L, 0L) }
    val at = held.value
    val frac = if (live) ((at ?: pos).toFloat() / n.duration).coerceIn(0f, 1f) else 0f
    val thick by animateDpAsState(if (live && (hover != null || at != null)) 3.dp else 1.dp, tween(Motion.FADE), label = "thick")
    fun where(x: Float) = ((x / width).coerceIn(0f, 1f) * n.duration).toLong()
    // transitions on the line, the phone's: blending into this song, a crossfader (the old song's accent giving way to
    // this one's, a knob across, automix's beats ticking); before that, this song's mix point, past it dotted
    val mixing by Crossfade.mixing.collectAsState()
    val mix = mixing?.takeIf { it.into == n.song.id }
    val k = rememberMixing(mix)
    val old = rememberCover(mix?.from, 300)?.accent ?: Ink.mid
    val point by Crossfade.point.collectAsState()
    val cut = point?.takeIf { k == null && live && it.first == n.song.id && it.second in 1 until n.duration }?.second
    Box(
        modifier.fillMaxWidth().height(24.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .then(if (live) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .onPointerEvent(PointerEventType.Move) { hover = (it.changes.first().position.x / width).coerceIn(0f, 1f) }
            .onPointerEvent(PointerEventType.Exit) { hover = null }
            // the wheel over the line scrubs, 5 s a notch: up or right later, as the volume's up is louder (2026-11-10).
            // Each notch from where the last one aimed while they come quickly (the position lags a seek, so notches
            // from it went nowhere), and taken, so the page under the line doesn't scroll too
            .onPointerEvent(PointerEventType.Scroll) { e ->
                if (!live) return@onPointerEvent
                val d = e.changes.first().scrollDelta
                val step = if (kotlin.math.abs(d.x) > kotlin.math.abs(d.y)) d.x else -d.y
                if (step == 0f) return@onPointerEvent
                val now = System.currentTimeMillis()
                val from = if (now - wheeled[1] < 700) wheeled[0] else Player.position.value
                val to = (from + (step * 5_000).toLong()).coerceIn(0, (n.duration - 1_000).coerceAtLeast(0))
                wheeled[0] = to; wheeled[1] = now
                Player.seek(to)
                e.changes.forEach { it.consume() }
            }
            .pointerInput(n.duration, n.song.id) { detectTapGestures { o -> if (live) Player.seek(where(o.x)) } }
            .pointerInput(n.duration, n.song.id) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (live) held.value = where(o.x) },
                    onDragEnd = { held.value?.let(Player::seek); held.value = null },
                    onDragCancel = { held.value = null },
                ) { c, _ -> c.consume(); if (live) { held.value = where(c.position.x); hover = (c.position.x / width).coerceIn(0f, 1f) } }
            },
    ) {
        // where a click would go (or where it's held), its time over the line
        val h = if (at != null) at.toFloat() / n.duration else hover
        if (live && h != null) {
            var label by remember { mutableStateOf(0) }
            BasicText(clock(if (at != null) at else (h * n.duration).toLong()), style = pixel(12.sp, Ink.ink),
                modifier = Modifier.align(Alignment.TopStart).onSizeChanged { label = it.width }
                    .offset { IntOffset((h * width - label / 2f).toInt().coerceIn(0, (width - label).coerceAtLeast(0)), 0) })
        }
        // `ends` (the bar along the foot): its times over the line at its two ends, gone, the time a click would go to,
        // while the mouse is on it; a station's only says it's live
        if (ends && h == null) {
            BasicText(if (live) clock(pos) else if (n.song.station) "live" else "", style = pixel(12.sp, Ink.mid), modifier = Modifier.align(Alignment.TopStart))
            if (live) BasicText("-" + clock((n.duration - pos).coerceAtLeast(0)), style = pixel(12.sp, Ink.mid), modifier = Modifier.align(Alignment.TopEnd))
        }
        // automix: the song's beat over the line (BeatCounter), not while the mouse is on it or in a blend; `beat` off
        // where it has a place of its own (the mini player's, beside its controls)
        if (beat && live && h == null && k == null) BeatCounter(n.song.id, n.playing, Modifier.align(Alignment.TopCenter))
        if (k != null && mix != null) MixBar(mix, k, old, accent, thick, Modifier.align(Alignment.BottomStart))
        else if (cut != null) MixPointBar(frac, cut.toFloat() / n.duration, accent, thick, Modifier.align(Alignment.BottomStart))
        else Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(thick).background(Ink.line)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(accent))
        }
        // where it's playing, a square of the accent on the line while the mouse is on it (2026-11-10, Reddit: the line
        // "way too minimal ... difficult to interact with"): square, as everything of ink's is, and gone when it's not
        val knob by animateFloatAsState(if (live && k == null && (hover != null || at != null)) 1f else 0f, tween(Motion.FADE), label = "knob")
        if (knob > 0f) {
            val side = 9.dp
            Box(Modifier.align(Alignment.BottomStart)
                .offset { IntOffset((frac * width - side.toPx() / 2).toInt().coerceIn(0, (width - side.roundToPx()).coerceAtLeast(0)), ((side - thick) / 2).roundToPx()) }
                .graphicsLayer { scaleX = knob; scaleY = knob; alpha = knob }
                .size(side).background(accent))
        }
    }
}

/**
 * What's heard, between the song's times ("flac 24/96", lit when lossless; at 70% when lossy); a click tells all of
 * it. After a lossless song's moment it fades in slowly. Not on a speaker.
 */
@Composable
fun HeardWord(h: Heard?, typing: Boolean, onOpen: () -> Unit) {
    var last by remember { mutableStateOf(h) }
    if (h != null) last = h
    AnimatedVisibility(h != null && !typing, enter = fadeIn(tween(SETTLE, easing = SETTLING)), exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
        last?.let { Word(it.word, pixel(12.sp, if (it.lossless) Ink.ink else Ink.mid), onClick = onOpen) }
    }
}

/**
 * A lossless song's moment (the phone's): as it starts, "HI-RES" or "LOSSLESS" types itself across the cover, its
 * depth and rate under it, holds a moment, and goes; the word between the times says it after. Once a song, only at
 * its start, never for lossy (the word alone says that).
 */
@Composable
private fun Moment(n: Player.Now, h: Heard?, onTyping: (Boolean) -> Unit) {
    val key = n.song.id
    var big by remember(key) { mutableStateOf("") }
    var small by remember(key) { mutableStateOf("") }
    // 0 while it's typed and held; to 1 as it fades where it is, the dim lifting with it
    val leave = remember(key) { Animatable(1f) }
    DisposableEffect(key) { onDispose { onTyping(false) } }
    LaunchedEffect(key, h?.lossless) {
        if (h?.lossless != true || Player.position.value > 8_000) return@LaunchedEffect
        val a = if (h.hires) "HI-RES" else "LOSSLESS"
        val b = listOfNotNull(h.bits.takeIf { it > 0 }?.let { "$it bit" }, h.rate.takeIf { it > 0 }?.let { "${Heard.khz(it)} khz" }).joinToString(" · ")
        onTyping(true)
        leave.animateTo(0f, tween(Motion.ENTER, easing = Motion.expo))
        delay(100)
        for (i in 1..a.length) { big = a.take(i); delay(70) }
        for (i in 1..b.length) { small = b.take(i); delay(30) }
        delay(1_400)
        // it fades where it is; once it's gone, the word between the times fades in
        leave.animateTo(1f, tween(SETTLE, easing = SETTLING))
        big = ""; small = ""
        onTyping(false)
    }
    Box(Modifier.fillMaxSize().background(Ink.black.copy(alpha = .55f * (1f - leave.value))), contentAlignment = Alignment.Center) {
        Column(Modifier.graphicsLayer { alpha = 1f - leave.value }, horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(big, style = headline(56.sp))
            Spacer(Modifier.height(12.dp))
            BasicText(small, style = pixel(16.sp))
        }
    }
}

/**
 * A DJ set's sleeve (2026-11-12, Bronson: "blurred ... when a track is recognized it types it in and then adds it to a
 * list on the record that stays there. Essentially building a designed record", "and make them clickable"): over the
 * set's frame in the blur, the set's name, and its tracklist as it's heard (SetHeard), numbered, the newest typed in; a
 * track tapped opens its artist's page, held it's found and put next.
 */
@Composable
private fun SetSleeve(channel: String, name: String, onOpen: (Page) -> Unit) {
    val heard by SetHeard.heard.collectAsState()
    val added by SetHeard.added.collectAsState()
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(said) { if (said != null) { delay(1_600); said = null } }
    val scroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(heard.size) { scroll.animateScrollTo(scroll.maxValue) }
    Box(Modifier.fillMaxSize().background(Ink.black.copy(alpha = .38f))) {
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            // the set's name over its tracklist, as the phone's sleeve has it (2026-11-12, Bronson: with the name gone it was
            // only a list; "looks nice" with it, though the words beside say it too)
            BasicText("live dj set  ·  ${channel.lowercase()}", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(6.dp))
            BasicText(name.uppercase(), style = headline(40.sp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            Spacer(Modifier.height(10.dp))
            if (heard.isEmpty()) BasicText("listening for the first track", style = pixel(12.sp, Ink.mid))
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                heard.forEachIndexed { i, t ->
                    // a tap, their page (their songs, records, more like them); held, the track put next
                    Row(Modifier.fillMaxWidth().combinedClickable(remember { MutableInteractionSource() }, indication = null,
                        onClick = { onOpen(Page.Artist(t.artist.substringBefore(", ").substringBefore(" & ").trim())) },
                        onLongClick = {
                            scope.launch {
                                val song = withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.find(t.title, t.artist) }
                                if (song != null) { Player.playNext(song); said = i to "next" } else said = i to "not found"
                            }
                        }).padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        BasicText("%02d".format(i + 1), style = pixel(12.sp, Ink.mid), modifier = Modifier.width(32.dp).padding(top = 3.dp))
                        Column(Modifier.weight(1f)) {
                            if (t == added) TypedLine(t.title.uppercase(), headline(18.sp))
                            else BasicText(t.title.uppercase(), style = headline(18.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            BasicText(t.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        said?.takeIf { it.first == i }?.let { BasicText(it.second, style = pixel(12.sp), modifier = Modifier.padding(start = 8.dp, top = 3.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * A DJ set's tracklist as it's heard (SetHeard): each track and where in the set it came, the newest lit; a click finds it
 * on YouTube Music and puts it next; the lot kept as a list of yours.
 */
@Composable
private fun SetTracklist() {
    val heard by SetHeard.heard.collectAsState()
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(said) { if (said != null) { delay(1_600); said = null } }
    Column {
        BasicText("HEARD IN THIS SET", style = headline(22.sp))
        BasicText(if (heard.isEmpty()) "listening: each track's name comes as it's heard" else "a click puts it next", style = pixel(12.sp, Ink.mid),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        heard.asReversed().forEachIndexed { i, t ->
            val n = heard.size - 1 - i
            Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {
                scope.launch {
                    val song = withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.find(t.title, t.artist) }
                    if (song != null) { Player.playNext(song); said = n to "next" } else said = n to "not found"
                }
            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(clock(t.at), style = pixel(12.sp, Ink.mid), modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(t.title.uppercase(), style = headline(16.sp, if (i == 0) Ink.ink else Ink.mid), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    BasicText(t.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                said?.takeIf { it.first == n }?.let { BasicText(it.second, style = pixel(12.sp)) }
            }
        }
        if (heard.size > 1) {
            var kept by remember(heard.size) { mutableStateOf(false) }
            Word(if (kept) "kept as a list" else "keep them all as a list", pixel(13.sp, if (kept) Ink.ink else Ink.mid), Modifier.padding(top = 12.dp), touch = 8.dp) {
                if (kept) return@Word
                kept = true
                val name = "heard in " + (Player.now.value?.song?.title ?: "a set").lowercase()
                scope.launch {
                    val songs = withContext(kotlinx.coroutines.Dispatchers.IO) { heard.mapNotNull { YouTube.find(it.title, it.artist) } }
                    HereLists.make(name, songs)
                }
            }
        }
    }
}

/** The moment's fade, and the word's fade in after it: slow, gentle at both ends. */
private const val SETTLE = 1_400
private val SETTLING = CubicBezierEasing(.45f, 0f, .2f, 1f)

/** All of what reaches your ears: the stream's format, where it came from, the volume and even volume. */
/**
 * The record, on the inner sleeve: its songs in order, numbered, this one lit (and marked), each its length; a click
 * plays the record from there. A song whose record isn't here says so, and offers its page.
 */
@Composable
private fun RecordSide(n: Player.Now, open: (Page) -> Unit) {
    if (SetHeard.isSet(n.song)) return SetTracklist()
    val albums by Library.albums.collectAsState()
    val queue by Player.queue.collectAsState()
    val s0 = n.song
    // what you have of it: the record in your library, else the song playing where it's yours (not YouTube's)
    val mine = remember(albums, s0.albumId, s0.id) { albums.firstOrNull { it.id == s0.albumId }?.songs ?: listOfNotNull(s0.takeIf { !it.tube }) }
    // the record whole from YouTube Music, as its page has it (2026-11-12, Bronson: "are we able to fill the record in?");
    // a YouTube record played whole, its songs as the queue has them
    val played = remember(queue, s0.albumId) { queue.filter { it.albumId == s0.albumId && it.album.isNotEmpty() }.takeIf { it.size > 1 } }
    val artist = s0.albumArtist.ifBlank { s0.artist.substringBefore(", ") }
    val whole by androidx.compose.runtime.produceState<List<Song>?>(played, s0.album, artist) {
        if (played == null && s0.album.isNotBlank() && !s0.station && !s0.episode) value = runCatching { YouTube.whole(s0.album, artist) }.getOrNull()
    }
    val record = remember(mine, whole) { filled(mine, whole) }
    val yours = remember(mine) { mine.mapTo(HashSet()) { it.id } }
    if (record.size <= 1) {
        Column {
            BasicText("just this song from ${s0.album.ifEmpty { "its record" }.lowercase()} is here", style = pixel(13.sp, Ink.mid))
            if (s0.album.isNotEmpty()) Word("its record", pixel(14.sp), Modifier.padding(top = 10.dp), touch = 8.dp) { open(Page.Record(s0.albumId)) }
        }
        return
    }
    // some yours, some YouTube Music's: said above it, and each of YouTube's dimmed and marked, as on the record's page
    val mixed = yours.isNotEmpty() && record.any { it.id !in yours }
    // the one playing: by id, else by name (a YouTube song's video can be another id than the record's track)
    val at = record.indexOfFirst { it.id == s0.id }.takeIf { it >= 0 } ?: record.indexOfFirst { trackKey(it.title) == trackKey(s0.title) }
    val list = rememberLazyListState()
    LaunchedEffect(s0.id, record.size) { if (at > 3) list.animateScrollToItem(at - 3) }
    Column {
        if (mixed) BasicText("${record.count { it.id in yours }} of ${record.size} here · the rest from youtube music", style = pixel(12.sp, Ink.mid),
            modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(record, key = { _, s -> s.id }) { i, s ->
                val on = i == at
                val theirs = mixed && s.id !in yours
                Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(record, i) })
                    .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText((if (whole == null && s.track > 0) s.track else i + 1).toString().padStart(2, '0'), style = headline(18.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.width(44.dp))
                    // the name and its mark together, taking the room; the length always at the right (a spacer sharing the
                    // room with the name had put each length somewhere else, 2026-11-12)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(s.title.uppercase(), style = headline(20.sp, if (on) Ink.ink else if (theirs) Ink.dim else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (theirs) BasicText("youtube music", style = pixel(12.sp, Ink.dim), maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                    }
                    if (s.duration > 0) BasicText(clock(s.duration), style = pixel(12.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

/**
 * The song's notes, on the inner sleeve, as a record's liner notes: what it is (format, its moment), its record and
 * year, where it plays, and you with it (how many times, first and last heard, when you loved it).
 */
@Composable
private fun Notes(n: Player.Now, heard: Heard?, speaker: String?) {
    val listened by Listened.now.collectAsState()
    val s = n.song
    val plays = listened.most(s.id)?.toInt() ?: 0
    val history = remember(s.id) { History.read().filter { it.id == s.id || (it.title.equals(s.title, true) && it.artist.equals(s.artist, true)) } }
    val day = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.ENGLISH)
    val rows = listOfNotNull(
        "what it is" to listOfNotNull(heard?.word ?: s.quality.ifEmpty { if (s.tube) "youtube music" else if (s.station) "radio" else null },
            heard?.from?.let { when (it) { "computer" -> "from this computer"; "server" -> "from your server"; "youtube" -> "from youtube music"; else -> "on the air" } }, heard?.let { if (it.hires) "hi-res" else if (it.lossless) "lossless" else null }).joinToString(" · ").ifEmpty { "a song" },
        s.album.takeIf { it.isNotEmpty() }?.let { "from" to (it + (s.year.takeIf { y -> y > 0 }?.let { y -> ", $y" } ?: "")) },
        speaker?.let { "playing on" to it },
        "you've played it" to when (plays) { 0 -> "not yet, till this one's done"; 1 -> "once"; else -> "$plays times" },
        history.firstOrNull()?.let { "first heard" to day.format(java.util.Date(it.at)) },
        history.lastOrNull()?.takeIf { history.size > 1 }?.let { "last heard" to day.format(java.util.Date(it.at)) },
        Loved.lovedAt(s)?.let { "loved" to day.format(java.util.Date(it)) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { (k, v) ->
            Column {
                BasicText(k, style = pixel(12.sp, Ink.dim))
                BasicText(v.uppercase(), style = headline(20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The phone's playing, the whole window (2026-10-29): as this computer's own player looks (the cover over its
 * dither print, the line and the times, the artist as the giant marquee), where it plays said ("playing on <phone> ·
 * on <speaker>"); back, play, next steering it there; continue here, big, taking it over; its lyrics as it sings them
 * there, or its notes.
 */
@Composable
fun RemotePlayerPage(o: Continue.Offer, open: (Page) -> Unit, onClose: () -> Unit) {
    val cover = rememberCover(o.song, 1200)
    val accent by animateColorAsState(cover?.accent ?: Ink.ink, tween(Motion.ENTER), label = "accent")
    val pos by androidx.compose.runtime.produceState(o.positionNow(), o) { while (true) { value = o.positionNow(); kotlinx.coroutines.delay(250) } }
    val artist = o.song.takeUnless { it.station }?.let { it.albumArtist.ifBlank { it.artist.substringBefore(", ") } }
    Box(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Marquee(o.song.artist, o.playing && Marquees.on.collectAsState().value, Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null) { artist?.let { open(Page.Artist(it)) } }, size = 150.sp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = SIDE - 8.dp, end = SIDE - 8.dp, top = TOP), verticalAlignment = Alignment.CenterVertically) {
                // the way out at the top right, as every page's (2026-11-10: it was top left here, top right there)
                Spacer(Modifier.weight(1f))
                Pixel(if (Win.full) UNFULL else FULL, if (Win.full) "leave full screen" else "full screen", Ink.mid) { Win.full(!Win.full) }
                Spacer(Modifier.width(14.dp))
                // opened out of the now column, it goes back right into it (→); else back down (↓)
                if (PlayerSide.right) Pixel(BACK_RIGHT, "back into the column", Ink.ink, onClick = onClose)
                else CloseArrow(onClick = onClose)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = SIDE, end = SIDE, top = 8.dp, bottom = 190.dp)) {
                val side: Dp = minOf((maxHeight - 17.dp - 18.dp) / 1.05f, maxWidth * .5f).coerceAtLeast(120.dp)
                val off = side * .05f
                Row {
                    Column(Modifier.width(side + off)) {
                        // the times, the line in the cover's colour, filling as it plays there
                        val length = o.length
                        Row(Modifier.width(side).padding(bottom = 6.dp)) {
                            BasicText(if (o.song.station) "live" else clock(pos), style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.weight(1f))
                            if (length > 0 && !o.song.station) BasicText("-" + clock((length - pos).coerceAtLeast(0L)), style = pixel(12.sp, Ink.mid))
                        }
                        Box(Modifier.width(side).height(1.dp).background(Ink.line)) {
                            if (length > 0 && !o.song.station) Box(Modifier.fillMaxWidth((pos.toFloat() / length).coerceIn(0f, 1f)).height(1.dp).background(accent))
                        }
                        Box(Modifier.size(side + off)) {
                            if (!o.song.station) Dithered(o.song, Modifier.offset(off, off).size(side), px = 1200)
                            CoverOf(o.song, o.song.album.ifEmpty { o.song.title }, Modifier.size(side), px = 1200, moving = o.playing)
                        }
                    }
                    Spacer(Modifier.width(48.dp))
                    Column(Modifier.weight(1f).padding(top = 17.dp)) {
                        BasicText((if (o.playing) "playing now on " else "paused on ") + o.from + (if (o.device.isNotEmpty()) " · on ${o.device.lowercase()}" else ""),
                            style = pixel(12.sp, Ink.ink))
                        Spacer(Modifier.height(10.dp))
                        BasicText(o.song.title.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        if (!o.song.station) Word(o.song.artist.lowercase(), pixel(14.sp)) { artist?.let { open(Page.Artist(it)) } }
                        if (o.song.album.isNotEmpty()) BasicText(o.song.album.lowercase(), style = pixel(14.sp, Ink.mid))
                        Spacer(Modifier.height(22.dp))
                        // steering it there, then taking it over
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Pixel(BACK, "back, there", Ink.mid, cell = 3.dp) { SyncHub.steer(o.peer, "previous") }
                            Pixel(if (o.playing) PAUSE else PLAY, if (o.playing) "pause, there" else "play, there", Ink.ink, cell = 4.dp) {
                                SyncHub.steer(o.peer, if (o.playing) "pause" else "play")
                            }
                            Pixel(NEXT, "next, there", Ink.mid, cell = 3.dp) { SyncHub.steer(o.peer, "next") }
                            Spacer(Modifier.width(12.dp))
                            Word("CONTINUE HERE", headline(26.sp), touch = 10.dp) { Continue.take(o) }
                        }
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
                        Spacer(Modifier.height(14.dp))
                        val lines = rememberLyrics(o.song)
                        var shows by remember(o.song.id) { mutableStateOf<String?>(null) }
                        val panel = shows ?: if (!lines.isNullOrEmpty()) "lyrics" else "notes"
                        Row {
                            for (w in listOf("lyrics", "notes"))
                                Word(w, pixel(14.sp, if (w == panel) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 10.dp) { shows = w }
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (panel == "lyrics") {
                                val all = lines
                                if (all.isNullOrEmpty()) BasicText(lyricsSaid(all), style = pixel(13.sp, Ink.mid))
                                else LyricsBlock(all, all.indexOfLast { it.at <= pos + Lyrics.LEAD }, Modifier.fillMaxSize(), big = true)
                            } else {
                                val n = remember(o.song.id) { Player.Now(o.song, 0, o.playing, o.length) }
                                Notes(n, null, o.device.ifEmpty { null } ?: o.from)
                            }
                        }
                    }
                }
            }
        }
        ColumnEdge(onClose)
    }
}

/**
 * Back into the now column, along the player's right edge where the column comes back (2026-11-10, Bronson: "a better
 * more intuitive way to put it back to the right"): a hairline down the edge, a › beside it, both lit under the mouse;
 * a click anywhere on it and the player goes back right. Only for a player opened out of the column ([PlayerSide]).
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ColumnEdge(onBack: () -> Unit) {
    if (!PlayerSide.right) return
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    val c by animateColorAsState(if (over) Ink.ink else Ink.dim, tween(Motion.FADE), label = "edge")
    Box(
        Modifier.align(Alignment.CenterEnd).width(SIDE).fillMaxHeight(.6f)
            .hoverable(src)
            .pointerHoverIcon(PointerIcon.Hand).clickable(src, indication = null, onClick = felt(then = onBack)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.padding(end = 6.dp).width(1.dp).fillMaxHeight().background(if (over) Ink.mid else Ink.line))
        Box(Modifier.padding(end = 14.dp)) { Chevron(BACK_RIGHT, 8.dp, 14.dp, c) }
    }
}


