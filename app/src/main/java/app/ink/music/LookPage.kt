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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
            androidx.compose.foundation.pager.HorizontalPager(pager, size, beyondViewportPageCount = 0) { page ->
                PlayerPreview(page, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val at = pager.currentPage
                if (many) Word("<", pixel(14.sp, if (at > 0) Ink.ink else Ink.dim), Modifier.padding(end = 14.dp), touch = 10.dp) { turn(at - 1) }
                BasicText(if (YouTubeMusic.available) Showcase.picks[at].said else "what plays now", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                if (many) Word(">", pixel(14.sp, if (at < Showcase.picks.size - 1) Ink.ink else Ink.dim), Modifier.padding(start = 14.dp), touch = 10.dp) { turn(at + 1) }
            }
        }
        // the choices, each layer its own: a heading, its line, its words
        val opened = rememberOpened()
        val sections: @Composable ColumnScope.() -> Unit = if (tv) ({ TvLookChoices(signed, canvasOn) }) else ({
            // each layer a row (SettingRows): its name and what it's set to, its words a tap away, showing above at once;
            // in the picture's order: the cover, what's behind it, the canvas over it, the name, then the rest
            val options = listOf<Look?>(null) + Moving.coverLooks
            SettingRow("THE COVER", cover?.word ?: "clear", opened, "the record's cover, moving where it has a moving one") {
                Pick(options.map { (it?.word ?: "clear") to (it == cover) }) { Moving.set(ctx, cover = options[it]) }
            }
            SettingRow("BEHIND IT", look.word, opened, "one background: a song's canvas where it has one, else its cover") {
                Pick(Look.entries.map { it.word to (it == look) }) { Looks.set(ctx, Look.entries[it]) }
            }
            // spotify's canvas over the background (signed in to spotify)
            if (Spotify.available) SettingRow("SPOTIFY'S CANVAS", if (!signed) "sign in" else if (canvasOn) "on" else "off", opened,
                "a song's own looping clip as the background, where it has one") {
                if (!signed) Word("SIGN IN", headline(40.sp), touch = 8.dp) { Spotify.signIn(ctx) }
                else Pick(listOf("on" to canvasOn, "off" to !canvasOn)) { Moving.set(ctx, canvasOn = it == 0) }
            }
            // the giant names running across: moving, or still where they are
            val marquee by Marquees.on.collectAsState()
            SettingRow("THE NAME", if (marquee) "moving" else "still", opened, "the artist's name along the player's foot") {
                Pick(listOf("moving" to marquee, "still" to !marquee)) { Marquees.set(ctx, it == 0) }
            }
            // how mixes and lists are drawn from their covers
            val print by CoverPrint.on.collectAsState()
            SettingRow("COVERS", print.word, opened, "how mixes and lists are drawn from their covers") {
                Pick(Print.entries.map { it.word to (it == print) }) { CoverPrint.set(ctx, Print.entries[it]) }
            }
            // an artist's page: their photo or their moving one (apple music's), dithered, blurred or clear (both alike)
            val video by Moving.artistVideo.collectAsState()
            val dithered by Moving.artistDither.collectAsState()
            val grain by Moving.artistGrain.collectAsState()
            val artistWord = if (grain) "blur" else if (dithered) "dithered" else "clear"
            SettingRow("ARTIST PAGES", if (Moving.available) "${if (video) "video" else "photo"} · $artistWord" else artistWord, opened,
                if (Moving.available) "their photo, or their moving one where apple music has one" else "their photo across their page") {
                if (Moving.available) Pick(listOf("video" to video, "photo" to !video)) { Moving.set(ctx, artistVideo = it == 0) }
                Pick(listOf("dithered" to (dithered && !grain), "blur" to grain, "clear" to (!dithered && !grain))) {
                    Moving.set(ctx, artistDither = it == 0, artistGrain = it == 1)
                }
            }
            // whether covers and canvases move: they're video, so on wifi unless you say
            if (Moving.available) SettingRow("MOVING", whenOn, opened, "moving covers and canvases, silent; they settle when you pause") {
                Pick(listOf(Moving.WIFI, Moving.ALWAYS, Moving.OFF).map { it to (whenOn == it) }) { Moving.set(ctx, whenOn = listOf(Moving.WIFI, Moving.ALWAYS, Moving.OFF)[it]) }
            }
            // the hi-res moment: a lossless song's format typed across the cover as it starts (here since 2026-11-11, out of sound)
            val moment by Moments.on.collectAsState()
            SettingRow("HI-RES MOMENT", if (moment) "on" else "off", opened, "a lossless song says so across its cover as it starts") {
                Pick(listOf("on" to moment, "off" to !moment)) { Moments.set(ctx, it == 0) }
            }
            // how much moves: full, less, or as the phone says (Moves; here since 2026-11-11, out of listening)
            val chosen by Moves.chosen.collectAsState()
            SettingRow("MOTION", chosen, opened, "pages word by word, covers developing, the blur by the bars" +
                if (Moves.phoneSaysLess(ctx)) "; this phone says less" else "") {
                Pick(listOf(Moves.AUTO, Moves.FULL, Moves.LESS).map { it to (chosen == it) }) { Moves.set(ctx, listOf(Moves.AUTO, Moves.FULL, Moves.LESS)[it]) }
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
            preview(Modifier.fillMaxWidth().height(tall * .38f))
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
