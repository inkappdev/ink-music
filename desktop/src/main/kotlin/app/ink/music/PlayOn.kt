package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Where it plays (the phone's play on): this computer, or a speaker or TV found on the network, the one playing lit.
 * Picked, what's playing moves there from where it was; this computer picked, it comes back. A speaker's volume,
 * while it plays there, in steps.
 */
@Composable
fun PlayOnPage(onClose: () -> Unit) {
    LaunchedEffect(Unit) { Casting.find() }
    val speakers by Casting.speakers.collectAsState()
    val on by Casting.on.collectAsState()
    val connecting by Casting.connecting.collectAsState()
    val volume by Casting.volume.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Column(Modifier.padding(start = SIDE, end = SIDE, top = 16.dp)) {
            BasicText("PLAY ON", style = headline(64.sp))
            Spacer(Modifier.height(6.dp))
            BasicText(if (speakers.isEmpty()) "looking for speakers on your network" else "speakers and tvs on your network",
                style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(20.dp))
            Place(if (Home.mac) "THIS MAC" else "THIS COMPUTER", "here", on == null) {
                if (on != null) Casting.bringBack()
                onClose()
            }
            speakers.forEach { s ->
                val lit = on?.key == s.key
                Place(s.name.uppercase(), listOfNotNull(
                    if (s.group) "group" else null, s.model.lowercase().takeIf { it.isNotEmpty() }, s.maker.lowercase().takeIf { it.isNotEmpty() && s.kind == "dlna" },
                    if (lit && connecting) "connecting" else null,
                ).joinToString(" · "), lit) {
                    if (lit) return@Place
                    val n = Player.now.value
                    val q = Player.queue.value
                    if (on != null) Casting.moveTo(s)
                    else if (n != null && q.isNotEmpty()) Casting.play(s, q, n.index, Player.position.value)
                    onClose()
                }
                // its volume, while it plays there
                if (lit) volume?.let { v ->
                    Row(Modifier.padding(start = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Word("quieter", pixel(13.sp, Ink.mid), touch = 8.dp) { Casting.setVolume(v - .05f) }
                        Spacer(Modifier.width(18.dp))
                        BasicText("${Math.round(v * 100)}%", style = pixel(13.sp))
                        Spacer(Modifier.width(18.dp))
                        Word("louder", pixel(13.sp, Ink.mid), touch = 8.dp) { Casting.setVolume(v + .05f) }
                    }
                }
            }
        }
    }
}

/** A place to play: its name (lit where it plays), and what it is. */
@Composable
private fun Place(name: String, said: String, lit: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp)) {
        BasicText(name, style = headline(26.sp, if (lit) Ink.ink else Ink.mid))
        if (said.isNotEmpty()) BasicText(said, style = pixel(12.sp, Ink.mid))
    }
}
