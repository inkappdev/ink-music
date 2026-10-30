package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
 * Settings, the look (Bronson, 2026-10-30: "an entire separate settings page ... with visual examples"): the player in
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
    val canvas by Moving.canvas.collectAsState()
    val spotifyAt by Spotify.at.collectAsState()
    val signed = remember(spotifyAt) { Spotify.signedIn() }
    val tall = LocalConfiguration.current.screenHeightDp.dp
    FrostedPage { frost, top ->
        Column(Modifier.fillMaxSize().hazeSource(frost).padding(start = 20.dp, end = 20.dp, top = top + 14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                BasicText("THE LOOK", style = headline(52.sp), modifier = Modifier.weight(1f).carried("settings-look"))
                CloseArrow { pages.close() }
            }
            Spacer(Modifier.height(14.dp))
            // the player in small, pinned: what's chosen below shows here
            // two songs, swiped between: one with a canvas, one without (where the look behind it shows); a choice
            // tapped below turns to the one it shows on. Only the one in sight plays its clips
            val many = YouTubeMusic.available
            val pager = androidx.compose.foundation.pager.rememberPagerState { if (many) Showcase.picks.size else 1 }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            fun turn(to: Int) { if (many && pager.currentPage != to) scope.launch { pager.animateScrollToPage(to, animationSpec = androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo)) } }
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxWidth().height(tall * .38f), beyondViewportPageCount = 0) { page ->
                PlayerPreview(page, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val at = pager.currentPage
                if (many) Word("<", pixel(14.sp, if (at > 0) Ink.ink else Ink.dim), Modifier.padding(end = 14.dp), touch = 10.dp) { turn(at - 1) }
                BasicText(if (many) Showcase.picks[at].said else "what plays now", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                if (many) Word(">", pixel(14.sp, if (at < Showcase.picks.size - 1) Ink.ink else Ink.dim), Modifier.padding(start = 14.dp), touch = 10.dp) { turn(at + 1) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 28.dp, bottom = 40.dp)) {
                // three layers, each its own effect, none following another (Bronson, 2026-10-30): the cover, the
                // still background, spotify's canvas over it; each a row of words, the chosen one lit
                @Composable
                fun Choices(words: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
                    FlowRow {
                        words.forEachIndexed { i, (w, lit) ->
                            Word(w, pixel(14.sp, if (lit) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp, bottom = 4.dp), touch = 8.dp) { pick(i) }
                        }
                    }
                }
                Column(Modifier.cascade(1)) {
                    BasicText("THE COVER", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("the record's cover in the player, moving where it has a moving one", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    val options = listOf<Look?>(null) + Moving.coverLooks
                    Choices(options.map { (it?.word ?: "as it is") to (it == cover) }) { Moving.set(ctx, cover = options[it]); turn(0) }
                    Spacer(Modifier.height(40.dp))
                }
                Column(Modifier.cascade(2)) {
                    BasicText("BEHIND IT", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("the cover across the page, still" + if (Spotify.available && signed && canvasOn) ", where a song has no canvas" else "", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    Choices(Look.entries.map { it.word to (it == look) }) { Looks.set(ctx, Look.entries[it]); turn(if (signed && canvasOn) 1 else 0) }
                    Spacer(Modifier.height(40.dp))
                }
                // spotify's canvas over the background (signed in to spotify)
                if (Spotify.available) Column(Modifier.cascade(3)) {
                    BasicText("SPOTIFY'S CANVAS", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    if (!signed) {
                        BasicText("a song's own looping clip behind the player, where it has one. it asks as you: sign in to spotify " +
                            "(the same sign-in that brings your playlists in)", style = pixel(12.sp, Ink.mid))
                        Spacer(Modifier.height(8.dp))
                        Word("SIGN IN", headline(40.sp), touch = 8.dp) { Spotify.signIn(ctx) }
                    } else {
                        BasicText("a song's own looping clip behind the player, where it has one", style = pixel(12.sp, Ink.mid))
                        Spacer(Modifier.height(10.dp))
                        val options = listOf<Look?>(null) + Moving.canvasLooks
                        Choices(listOf("off" to !canvasOn) + options.map { (it?.word ?: "as it is") to (canvasOn && it == canvas) }) { i ->
                            if (i == 0) Moving.set(ctx, canvasOn = false) else Moving.set(ctx, canvasOn = true, canvas = options[i - 1])
                            turn(0)
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
                // the giant names running across: moving, or still where they are
                Column(Modifier.cascade(4)) {
                    val marquee by Marquees.on.collectAsState()
                    BasicText("THE NAME", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("the artist's name running across the foot of the player (and ink weekly's, a long one on an artist's page)", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    Choices(listOf("moving" to marquee, "still" to !marquee)) { Marquees.set(ctx, it == 0) }
                    Spacer(Modifier.height(40.dp))
                }
                // whether covers and canvases move: they're video, so on wifi unless you say
                if (Moving.available) Column(Modifier.cascade(5)) {
                    BasicText("MOVING", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("a record's moving cover (apple music's) and a song's canvas, where they have them. silent; " +
                        "they settle when you pause", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    Row {
                        listOf(Moving.WIFI, Moving.ALWAYS, Moving.OFF).forEach { w ->
                            Word(w, pixel(14.sp, if (whenOn == w) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Moving.set(ctx, whenOn = w) }
                        }
                    }
                }
            }
        }
    }
}
