package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/**
 * Settings, the look (2026-10-30): the player in
 * small pinned at the top (always the same song, one with every moving thing), the choices scrolling under it, each
 * showing there at once. In the picture's order: the cover, what's behind it, and whether they move.
 */
@Composable
fun LookPage(pages: Pages) {
    val ctx = LocalContext.current
    val look by Looks.on.collectAsState()
    val cover by Moving.cover.collectAsState()
    val whenOn by Moving.whenOn.collectAsState()
    val canvasOn by Moving.canvasOn.collectAsState()
    val spotifyAt by Spotify.at.collectAsState()
    val signed = remember(spotifyAt) { Spotify.signedIn() }
    val tall = LocalConfiguration.current.screenHeightDp.dp
    FrostedPage { frost, top ->
        val tv = onTv()
        val many = YouTubeMusic.available && Showcase.picks.size > 1
        val pager = androidx.compose.foundation.pager.rememberPagerState { if (many) Showcase.picks.size else 1 }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        fun turn(to: Int) { if (many && pager.currentPage != to) scope.launch { pager.animateScrollToPage(to, animationSpec = androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo)) } }
        // the player in small: what's chosen shows here at once
        val preview: @Composable ColumnScope.(Modifier) -> Unit = { size ->
            // now playing and its artist's page side by side (2026-11-25, Bronson): each look shows where it's used
            Row(size, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f).fillMaxHeight(), beyondViewportPageCount = 0) { page ->
                    PlayerPreview(page, Modifier.fillMaxSize())
                }
                ArtistPreview(Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val at = pager.currentPage
                if (many) Word("<", pixel(14.sp, if (at > 0) Ink.ink else Ink.dim), Modifier.padding(end = 14.dp), touch = 10.dp) { turn(at - 1) }
                BasicText("what plays now, or the last song you played", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                if (many) Word(">", pixel(14.sp, if (at < Showcase.picks.size - 1) Ink.ink else Ink.dim), Modifier.padding(start = 14.dp), touch = 10.dp) { turn(at + 1) }
            }
        }
        // the choices, each layer its own: a heading, its line, its words
        val opened = rememberOpened()
        val sections: @Composable ColumnScope.() -> Unit = if (tv) ({ TvLookChoices(signed, canvasOn) }) else ({
            // PICTURES (2026-11-25, Bronson: the page "starting to get really crazy"): one grid for the four places a picture
            // is drawn, the looks down its side, a lit square a column (LooksGrid); then the rest in four short groups
            Group("PICTURES", "one look for each place a picture is drawn")
            LooksGrid()
            Group("THE PLAYER")
            // the giant names running across: moving, or still where they are
            val marquee by Marquees.on.collectAsState()
            val nameSize by PlayerRoom.name.collectAsState()
            SettingRow("THE NAME", "${if (marquee) "moving" else "still"} · $nameSize", opened, "the artist's name along the player's foot") {
                Pick(listOf("moving" to marquee, "still" to !marquee)) { Marquees.set(ctx, it == 0) }
                PickLabel("its size")
                Pick(PlayerRoom.SIZES.map { it to (it == nameSize) }) { PlayerRoom.setName(ctx, PlayerRoom.SIZES[it]) }
            }
            // the player over the status and navigation bars, more room for the lyrics (#11); a swipe from the edge brings them
            val full by PlayerRoom.full.collectAsState()
            SettingRow("FULL SCREEN", if (full) "on" else "off", opened, "the player over the status and navigation bars; swipe from the edge for them") {
                Pick(listOf("on" to full, "off" to !full)) { PlayerRoom.setFull(ctx, it == 0) }
            }
            // what moves: covers and canvases (video, so on wifi unless you say), spotify's canvas, an artist's video
            if (Moving.available || Spotify.available) Group("MOVING")
            if (Moving.available) SettingRow("MOVING COVERS", whenOn, opened, "moving covers and canvases, silent; they settle when you pause") {
                Pick(listOf(Moving.WIFI, Moving.ALWAYS, Moving.OFF).map { it to (whenOn == it) }) { Moving.set(ctx, whenOn = listOf(Moving.WIFI, Moving.ALWAYS, Moving.OFF)[it]) }
            }
            if (Spotify.available) SettingRow("SPOTIFY CANVAS", if (!signed) "sign in" else if (canvasOn) "on" else "off", opened,
                "a song's own looping clip behind the player, where it has one") {
                if (!signed) Word("SIGN IN", headline(40.sp), touch = 8.dp) { Spotify.signIn(ctx) }
                else Pick(listOf("on" to canvasOn, "off" to !canvasOn)) { Moving.set(ctx, canvasOn = it == 0) }
            }
            if (Moving.available) {
                val video by Moving.artistVideo.collectAsState()
                SettingRow("ARTIST PAGES", if (video) "video" else "photo", opened, "their photo, or their moving one where apple music has one") {
                    Pick(listOf("video" to video, "photo" to !video)) { Moving.set(ctx, artistVideo = it == 0) }
                }
            }
            Group("LYRICS")
            // how big the sung lines are (#11)
            val lyricsSize by PlayerRoom.lyrics.collectAsState()
            SettingRow("SIZE", lyricsSize, opened, "how big the lines are; a tap on them folds the controls away") {
                Pick(PlayerRoom.SIZES.map { it to (it == lyricsSize) }) { PlayerRoom.setLyrics(ctx, PlayerRoom.SIZES[it]) }
            }
            // under each sung line: its English, or how it sounds (Better Lyrics', for a song in another language)
            val under by Lyrics.under.collectAsState()
            SettingRow("UNDER THEM", under, opened, "for a song in another language: its english, or how it sounds, under each line") {
                val all = listOf(Lyrics.UNDER_NOTHING, Lyrics.UNDER_ENGLISH, Lyrics.UNDER_SOUND)
                Pick(all.map { it to (it == under) }) { Lyrics.under(ctx, all[it]) }
            }
            Group("THE WHOLE APP")
            // DOT MATRIX, once found (FREQUENCY at 440 Hz): the whole app in a dot-matrix hand, or ink's
            val dotFound by DotMatrix.found.collectAsState()
            val dotOn by DotMatrix.on.collectAsState()
            if (dotFound) SettingRow("TYPE", if (dotOn) "dot matrix" else "ink", opened, "you found it: the whole app in a dot-matrix hand, or ink's own") {
                Pick(listOf("ink" to !dotOn, "dot matrix" to dotOn)) { DotMatrix.set(ctx, it == 1) }
            }
            // how much moves: full, less, or as the phone says (Moves; here since 2026-11-11, out of listening)
            val chosen by Moves.chosen.collectAsState()
            SettingRow("MOTION", chosen, opened, "pages word by word, covers developing, the blur by the bars" +
                if (Moves.phoneSaysLess(ctx)) "; this phone says less" else "") {
                Pick(listOf(Moves.AUTO, Moves.FULL, Moves.LESS).map { it to (chosen == it) }) { Moves.set(ctx, listOf(Moves.AUTO, Moves.FULL, Moves.LESS)[it]) }
            }
            // the app's own icon on the home screen: the words, or an ink drop (AppIcon); not on a TV (its row has the banner)
            if (!onTv()) {
                var icon by remember { mutableStateOf(AppIcon.now(ctx)) }
                SettingRow("ICON", AppIcon.CHOICES[icon].first, opened, "ink music on your home screen; it may take your launcher a moment") {
                    // each as it looks: its picture on black in a hairline square (lit when chosen), its word under it
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp)) {
                        AppIcon.CHOICES.forEachIndexed { i, (word, _) ->
                            if (i == AppIcon.DOTS && !DotMatrix.found.value) return@forEachIndexed
                            val on = icon == i
                            androidx.compose.foundation.layout.Column(Modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                                onClick = felt { icon = i; AppIcon.choose(ctx, i) })) {
                                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(AppIcon.PICTURES[i]), word,
                                    Modifier.size(64.dp).background(Ink.black).border(1.dp, if (on) Ink.ink else Ink.line))
                                androidx.compose.foundation.text.BasicText(word, style = pixel(12.sp, if (on) Ink.ink else Ink.mid), modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
            RowsEnd()
        })
        val title: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth()) {
                BasicText("THE LOOK", style = headline(52.sp), modifier = Modifier.weight(1f).carried("settings-look"))
                CloseArrow { pages.close() }
            }
        }
        // a TV: the preview held still on the left, as big as the height allows, and every layer's choices on the right,
        // moved through with the remote, the preview changing beside them (2026-11-03: the phone's preview pinned across
        // the top had each heading scrolled away under it before its choices came up)
        // a TV: the choices alone (2026-11-10: the player in small started its moving covers anew with each choice, a
        // second's stall on the onn each time); what's chosen shows in the player itself
        if (tv) Column(Modifier.fillMaxSize().hazeSource(frost).padding(start = 40.dp, end = 40.dp, top = top + 14.dp)) {
            title()
            Spacer(Modifier.height(18.dp))
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxWidth().weight(1f).feather(scroll, 28.dp, 44.dp).verticalScroll(scroll).padding(bottom = 40.dp)) {
                sections()
            }
        } else Column(Modifier.fillMaxSize().hazeSource(frost).padding(start = 20.dp, end = 20.dp, top = top + 14.dp)) {
            title()
            Spacer(Modifier.height(14.dp))
            // short (2026-11-25, Bronson: tall for no reason): the cover and what's behind it are all it needs to show
            preview(Modifier.fillMaxWidth().height(tall * .3f))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = LocalFoot.current + 40.dp)) {
                sections()
            }
        }
    }
}

/**
 * The look on a TV (2026-11-10), fewer words: what's behind the player, the cover, whether covers move (Apple Music's
 * moving ones), Spotify's canvas. No artist pages or
 * name; each shows in the player beside it at once. Moving starts off on a TV: a fast box can, a slow one stutters.
 */
@Composable
private fun ColumnScope.TvLookChoices(signed: Boolean, canvasOn: Boolean) {
    val ctx = LocalContext.current
    val look by Looks.on.collectAsState()
    val cover by Moving.cover.collectAsState()
    val whenOn by Moving.whenOn.collectAsState()
    @Composable
    fun Part(head: String, said: String, words: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
        BasicText(head, style = headline(28.sp))
        Spacer(Modifier.height(4.dp))
        BasicText(said, style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        FlowRow {
            words.forEachIndexed { i, (w, lit) ->
                Word(w, pixel(16.sp, if (lit) Ink.ink else Ink.dim), Modifier.padding(end = 28.dp, bottom = 4.dp), touch = 10.dp) { pick(i) }
            }
        }
        Spacer(Modifier.height(34.dp))
    }
    val behind = Look.entries.toList()
    Part("BEHIND IT", "the cover across the whole player, in this effect", behind.map { it.word to (it == look) }) { Looks.set(ctx, behind[it]) }
    val covers = listOf<Look?>(null) + Moving.coverLooks
    Part("THE COVER", "the record's cover beside the words", covers.map { (it?.word ?: "clear") to (it == cover) }) { Moving.set(ctx, cover = covers[it]) }
    val marquee by Marquees.on.collectAsState()
    Part("THE NAME", "the artist's name running along the foot, or big and still over the cover's corner",
        listOf("moving" to marquee, "on the cover" to !marquee)) { Marquees.set(ctx, it == 0) }
    if (Spotify.available) {
        val ready = signed || Spotify.ready()
        if (!ready) Part("SPOTIFY CANVAS", "a song's looping clip behind the player. sign in to spotify on your phone, then it's here", emptyList()) {}
        // the TV's one moving thing (Apple Music's covers cut there, 2026-11-10): on is canvas and moving both
        else { val on = canvasOn && whenOn != Moving.OFF
            Part("SPOTIFY CANVAS", "a song's looping clip behind the player, in the effect above. smooth on a fast tv; on a slow one, leave it off",
                listOf("on" to on, "off" to !on)) { Moving.set(ctx, canvasOn = it == 0, whenOn = if (it == 0) Moving.ALWAYS else Moving.OFF) } }
    }
}

/** A group's head on the look page: its name, and a line where it needs one. */
@Composable
private fun Group(name: String, line: String? = null) {
    Spacer(Modifier.height(26.dp))
    BasicText(name, style = headline(22.sp))
    if (line != null) BasicText(line, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(6.dp))
}

/**
 * PICTURES (2026-11-25): the four places a picture is drawn across the top (the player's cover, behind it, mixes and
 * lists, artist pages), one set of looks down the side, a lit square for each place's (a round dot in DOT MATRIX); every
 * look for every place but black, behind the player alone (a faint point elsewhere). A tap chooses, the preview shows it.
 */
@Composable
private fun LooksGrid() {
    val ctx = LocalContext.current
    val behind by Looks.on.collectAsState()
    val cover by Moving.cover.collectAsState()
    val print by CoverPrint.on.collectAsState()
    val dithered by Moving.artistDither.collectAsState()
    val grain by Moving.artistGrain.collectAsState()
    val xray by Moving.artistXray.collectAsState()
    val looks = listOf("as it is", "dither", "tiles", "dots", "blur", "x-ray", "black")
    // each place: which look it's in, and what each look it can take does
    class Place(val head: String, val on: String, val can: Map<String, () -> Unit>)
    val artistExtra by ArtistLook.extra.collectAsState()
    // every look for every place (2026-11-25, Bronson), but black: only behind the player
    fun lookWord(l: Look?) = when (l) { null, Look.AS_IS -> "as it is"; Look.DITHER -> "dither"; Look.PIXELS -> "tiles"; Look.DOTS -> "dots"; Look.BLUR -> "blur"; Look.XRAY -> "x-ray"; Look.PLAIN -> "black" }
    val asLook = mapOf("dither" to Look.DITHER, "tiles" to Look.PIXELS, "dots" to Look.DOTS, "blur" to Look.BLUR, "x-ray" to Look.XRAY)
    val asPrint = mapOf("as it is" to Print.CLEAR, "dither" to Print.DITHER, "tiles" to Print.TILES, "dots" to Print.DOTS, "blur" to Print.GRAIN, "x-ray" to Print.XRAY)
    fun artist(word: String) {
        ArtistLook.set(ctx, if (word == "tiles" || word == "dots") word else "")
        Moving.set(ctx, artistDither = word == "dither", artistGrain = word == "blur", artistXray = word == "x-ray")
    }
    val places = listOf(
        Place("the\ncover", lookWord(cover),
            (listOf("as it is" to { Moving.set(ctx, cover = null) }) + asLook.map { (w, l) -> w to { Moving.set(ctx, cover = l) } }).toMap()),
        Place("behind\nit", lookWord(behind),
            (listOf("as it is" to { Looks.set(ctx, Look.AS_IS) }) + asLook.map { (w, l) -> w to { Looks.set(ctx, l) } } + listOf("black" to { Looks.set(ctx, Look.PLAIN) })).toMap()),
        Place("mixes,\nlists", asPrint.entries.firstOrNull { it.value == print }?.key ?: "tiles", asPrint.mapValues { (_, p) -> { CoverPrint.set(ctx, p) } }),
        Place("artist\npages", if (artistExtra.isNotEmpty()) artistExtra else if (xray) "x-ray" else if (grain) "blur" else if (dithered) "dither" else "as it is",
            listOf("as it is", "dither", "tiles", "dots", "blur", "x-ray").associateWith { w -> { artist(w) } }),
    )
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Spacer(Modifier.width(72.dp))
            places.forEach { p -> BasicText(p.head, style = pixel(12.sp, Ink.mid).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), modifier = Modifier.weight(1f)) }
        }
        looks.forEach { look ->
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                BasicText(look, style = pixel(13.sp, if (places.any { it.on == look }) Ink.ink else Ink.mid), modifier = Modifier.width(72.dp))
                places.forEach { p ->
                    val pick = p.can[look]
                    val lit = p.on == look
                    Box(Modifier.weight(1f).fillMaxSize().then(if (pick != null) Modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = felt { pick() }) else Modifier)
                        .semantics { contentDescription = "${p.head.replace("\n", " ")}: $look" + if (lit) ", chosen" else "" },
                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                        val shape = if (Hand.dots) androidx.compose.foundation.shape.CircleShape else androidx.compose.ui.graphics.RectangleShape
                        when {
                            pick == null -> Box(Modifier.size(2.dp).background(Ink.line, shape))
                            lit -> Box(Modifier.size(12.dp).background(Ink.ink, shape))
                            else -> Box(Modifier.size(12.dp).border(1.dp, Ink.dim, shape))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The artist page in small, beside the player in the look's preview (2026-11-25): the playing (or last) song's artist,
 * their photo in the artist pages' look, their name over its foot as their page has it, a phone's shape in a hairline.
 */
@Composable
private fun ArtistPreview(modifier: Modifier) {
    val now by Playback.now.collectAsState()
    val ctx = LocalContext.current
    val artist by androidx.compose.runtime.produceState(now?.artist?.substringBefore(", ").orEmpty(), now?.artist) {
        if (value.isEmpty()) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            History.read(ctx).lastOrNull()?.artist?.substringBefore(", ").orEmpty()
        }
    }
    val info by androidx.compose.runtime.remember(artist) { if (artist.isEmpty()) kotlinx.coroutines.flow.flowOf(null) else YouTubeAccount.artist(artist) }.collectAsState(null)
    val photo = info?.photo
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        val full = ctx.resources.displayMetrics.let { (it.widthPixels / it.density).dp to (it.heightPixels / it.density).dp }
        val k = minOf(maxWidth / full.first, maxHeight / full.second, 1f)
        val w = full.first * k
        Column(Modifier.size(w, full.second * k).border(1.dp, Ink.line).background(Ink.black)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = androidx.compose.ui.Alignment.BottomStart) {
                if (photo != null) ArtistPhoto(remember(photo) { photoSong(artist, photo) }, Modifier.fillMaxSize())
                BasicText(artist.uppercase(), style = headline((w.value / 7).sp), maxLines = 2, modifier = Modifier.padding(w / 20))
            }
            // their top songs under it, as their page has them
            Column(Modifier.padding(w / 20)) {
                BasicText(if (artist.isEmpty()) "play something" else "top songs", style = pixel(12.sp, Ink.mid))
                info?.top?.take(5)?.forEach { t ->
                    BasicText(t.title.uppercase(), style = headline((w.value / 16).sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = w / 28))
                }
            }
        }
    }
}
