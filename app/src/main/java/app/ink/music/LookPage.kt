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

/**
 * Settings, the look (Bronson, 2026-10-30: "an entire separate settings page ... with visual examples"): the player in
 * small pinned at the top (always the same song, one with every moving thing), the choices scrolling under it, each
 * showing there at once. In the picture's order: the cover, what's behind it, and whether they move.
 */
@Composable
fun LookPage(pages: Pages) {
    val ctx = LocalContext.current
    val look by Looks.on.collectAsState()
    val inLook by Moving.inLook.collectAsState()
    val whenOn by Moving.whenOn.collectAsState()
    val behind by Moving.canvas.collectAsState()
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
            Box(Modifier.fillMaxWidth().height(tall * .38f)) { PlayerPreview(Modifier.fillMaxSize()) }
            Spacer(Modifier.height(8.dp))
            BasicText(if (Moving.available) "the avalanches, vida: a moving cover, and spotify's canvas behind it" else "what plays now",
                style = pixel(12.sp, Ink.mid), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 28.dp, bottom = 40.dp)) {
                // the cover: as it is, or drawn in the look as the page behind it is
                Column(Modifier.cascade(1)) {
                    BasicText("THE COVER", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText(if (inLook) "drawn in the look, as behind it: dither, pixels or fluted glass (blur and plain leave it as it is)"
                        else "the record's own cover, the look only behind it", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Word("as it is", pixel(14.sp, if (!inLook) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Moving.set(ctx, inLook = false) }
                        Word("in the look", pixel(14.sp, if (inLook) Ink.ink else Ink.dim), touch = 8.dp) { Moving.set(ctx, inLook = true) }
                    }
                    Spacer(Modifier.height(40.dp))
                }
                // behind it: the looks, then spotify's canvas over the look (signed in to spotify)
                Column(Modifier.cascade(2)) {
                    BasicText("BEHIND IT", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("the cover across the page, in a look. the square in the player's top row turns through them too", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    FlowRow {
                        Look.entries.forEach { l ->
                            Word(l.word, pixel(14.sp, if (look == l) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp, bottom = 4.dp), touch = 8.dp) { Looks.set(ctx, l) }
                        }
                    }
                    if (Spotify.available) {
                        Spacer(Modifier.height(24.dp))
                        BasicText("SPOTIFY'S CANVAS", style = headline(18.sp))
                        Spacer(Modifier.height(6.dp))
                        if (!signed) {
                            BasicText("a song's own looping clip, over the look, where it has one. it asks as you: sign in to spotify " +
                                "(the same sign-in that brings your playlists in)", style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.height(8.dp))
                            Word("SIGN IN", headline(40.sp), touch = 8.dp) { Spotify.signIn(ctx) }
                        } else {
                            BasicText(when (behind) {
                                Moving.CANVAS_LOOK -> "a song's own looping clip, drawn in the look (plain: none)"
                                Moving.CANVAS_PLAIN -> "a song's own looping clip itself, no effect, whatever the look"
                                else -> "none: the look's still cover behind the player"
                            }, style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.height(10.dp))
                            Row {
                                listOf(Moving.CANVAS_LOOK, Moving.CANVAS_PLAIN, Moving.CANVAS_OFF).forEach { w ->
                                    Word(w, pixel(14.sp, if (behind == w) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Moving.set(ctx, canvas = w) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
                // whether covers and canvases move: they're video, so on wifi unless you say
                if (Moving.available) Column(Modifier.cascade(3)) {
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
