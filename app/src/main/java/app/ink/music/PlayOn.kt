package app.ink.music

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Where it plays, in ink's words: this phone (and the headphones or car it's connected to), then
 * every speaker and TV on the wifi and the groups of them, the one playing lit. A tap moves the
 * music there from the song and the second it's at. Switching between Bluetooth devices stays
 * Android's (apps may not choose the route): "bluetooth" opens its switcher.
 */
@Composable
fun PlayOnPage(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val speakers by Casting.speakers.collectAsState()
    val on by Casting.on.collectAsState()
    val connecting by Casting.connecting.collectAsState()
    // Android 17 asks before reaching the wifi's devices; looking starts once it's allowed
    var asked by remember { mutableIntStateOf(0) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { asked++ }
    val allowed = remember(asked) { Server.localAllowed(ctx) }
    DisposableEffect(allowed) {
        if (allowed) Casting.find(ctx) else Server.local?.let { ask.launch(it) }
        onDispose { Casting.stopFinding() }
    }
    val phone = remember { connectedTo(ctx) }

    Column(
        Modifier.fillMaxSize().background(Ink.black)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Column(Modifier.weight(1f)) {
                BasicText("PLAY ON", style = headline(40.sp))
                Spacer(Modifier.height(6.dp))
                BasicText("the music moves there from where it is", style = pixel(11.sp, Ink.mid))
            }
            Word("close", pixel(11.sp, Ink.mid), touch = 10.dp, onClick = onClose)
        }
        Spacer(Modifier.height(26.dp))
        LazyColumn(Modifier.weight(1f)) {
            item {
                Place("this phone", phone ?: "its own speaker", lit = on == null) { if (on != null) Casting.bringBack(); onClose() }
            }
            items(speakers, key = { it.key }) { s ->
                val here = on?.key == s.key
                // hi-res only where it's known (the maker's spec) or has played a hi-res file
                val hires = if (Casting.hiRes(s) == true) "hi-res" else null
                val state = when { here && connecting -> "connecting"; here -> "playing here"; s.group -> "a group"; s.kind == "dlna" -> "a receiver"; else -> null }
                Place(s.name, listOfNotNull(state, hires).joinToString("   "), lit = here) {
                    if (here) return@Place
                    if (on != null) Casting.moveTo(ctx, s)
                    else Playback.handover()?.let { (queue, index, at) -> Casting.play(ctx, s, queue, index, at) }
                    onClose()
                }
            }
            item {
                if (speakers.isEmpty()) BasicText(
                    if (allowed) "looking on your wifi" else "ink music needs to reach devices on your wifi to find speakers",
                    style = pixel(11.sp, Ink.mid), modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        Row(Modifier.padding(vertical = 16.dp)) {
            Word("bluetooth", pixel(12.sp, Ink.mid), touch = 10.dp) {
                if (android.os.Build.VERSION.SDK_INT >= 34) runCatching { android.media.MediaRouter2.getInstance(ctx).showSystemOutputSwitcher() }
            }
        }
    }
}

/** A place to play: its name big (lit where it plays), a word under it. */
@Composable
private fun Place(name: String, under: String, lit: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(vertical = 10.dp)) {
        BasicText(name.uppercase(), style = headline(28.sp, if (lit) Ink.ink else Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (under.isNotEmpty()) BasicText(under, style = pixel(11.sp, if (lit) Ink.mid else Ink.dim))
    }
}

/** The headphones, speaker or car the phone plays through, by name (null: its own speaker). */
private fun connectedTo(ctx: android.content.Context): String? {
    val am = ctx.getSystemService(AudioManager::class.java) ?: return null
    val kinds = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    )
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in kinds }
        ?.let { d -> d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) "bluetooth" else "headphones" }
}
