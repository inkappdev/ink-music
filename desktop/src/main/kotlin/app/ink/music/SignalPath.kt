package app.ink.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** What's sent to a speaker, as its quality line says it ("flac 24/96", "flac 16/44.1", "mp3 320"), as a [Heard]. */
fun sent(quality: String, id: String): Heard? {
    val m = Regex("^(\\w+)(?:\\s+(?:(\\d+)/)?([\\d.]+))?").find(quality.trim().lowercase()) ?: return null
    val codec = m.groupValues[1]
    val lossless = codec in Heard.LOSSLESS
    val rate = if (lossless) m.groupValues[3].toFloatOrNull()?.let { (it * 1000).toInt() } ?: 0 else 0
    return Heard(id, codec, m.groupValues[2].toIntOrNull() ?: 0, rate, 0, if (lossless) 0 else m.groupValues[3].toIntOrNull() ?: 0, "", null)
}

/** mpv's word for a sample format, as said: "s16" 16 bit, "s32" 32 bit, "floatp" 32 bit float. */
private fun bitsOf(format: String): String = format.removeSuffix("p").let { f ->
    when {
        f == "float" -> "32 bit float"
        f == "double" -> "64 bit float"
        f.startsWith("s") || f.startsWith("u") -> f.drop(1).toIntOrNull()?.let { "$it bit" } ?: f
        else -> f
    }
}
private fun depth(format: String): Int = format.removeSuffix("p").let { f -> if (f == "float") 32 else if (f == "double") 64 else f.drop(1).toIntOrNull() ?: 0 }

/** The system's sound, as mpv names it, as said: "macos", "windows", "pipewire". */
private fun system(ao: String): String = when {
    ao.startsWith("coreaudio") -> "macos"
    ao == "wasapi" -> "windows"
    ao == "pipewire" -> "pipewire"
    ao == "pulse" -> "pulseaudio"
    ao == "alsa" -> "alsa"
    ao == "null" -> "nothing"
    else -> ao
}

/**
 * The signal path (the phone's, 2026-10-30; on the computer since, Bronson): what reaches your ears, step by step, from
 * where the song came to where it plays, each as mpv read it, a hairline between, the signal travelling down it as it
 * opens; what's yours to change a word on its step (even volume, the volume, alone or shared), the path and "exact?"
 * following at once. On a speaker, the path ends there, the receiver saying what it does where it says.
 */
@Composable
fun SignalPath(h: Heard?, onClose: () -> Unit) {
    val sound by Player.sound.collectAsState()
    val volume by Player.volume.collectAsState()
    val muted by Player.muted.collectAsState()
    val speaker by Casting.on.collectAsState()
    val quality by Casting.quality.collectAsState()
    val now by Player.now.collectAsState()
    val computer = if (Home.mac) "mac" else "computer"
    // what mpv hands on, read again each second (a word below changes it: its reload is seen)
    val out by produceState<Player.Out?>(null, speaker) {
        while (speaker == null) { value = Player.out(); delay(1_000) }
    }
    // a receiver's own word, read again every few seconds (a Denon's sound mode changes under you)
    val said by produceState<Receiver.Said?>(null, speaker) {
        val sp = speaker ?: return@produceState
        while (true) { Receiver.read(with(Casting) { sp.device() })?.let { value = it }; delay(5_000) }
    }
    val castVolume by Casting.volume.collectAsState()
    val k = { hz: Int -> "${Heard.khz(hz)} khz" }
    val acts = HashMap<String, Pair<String, () -> Unit>>()
    val steps = mutableListOf<Pair<String, String>>()
    val song = now?.song
    if (h != null) {
        steps += "from" to when (h.from) {
            "server" -> "your server, the file as it is"
            "youtube" -> "youtube music, its best stream"
            "station" -> "the station's stream"
            else -> "this $computer, the file as it is"
        }
        steps += (if (h.from == "youtube" || h.from == "station") "the stream" else "the file") to listOfNotNull(
            h.codec, h.bits.takeIf { it > 0 }?.let { "$it bit" }, h.rate.takeIf { it > 0 }?.let(k),
            h.channels.takeIf { it > 0 }?.let { when (it) { 1 -> "mono"; 2 -> "stereo"; else -> "$it channels" } },
            h.kbps.takeIf { it > 0 }?.let { "%,d kbps".format(java.util.Locale.ROOT, it) },
        ).joinToString(" · ") + "\n" + (if (h.hires) "hi-res lossless" else if (h.lossless) "lossless" else "lossy")
    } else if (speaker != null && song != null) {
        steps += "from" to when { song.server -> "your server"; song.tube -> "youtube music"; song.station -> "the station"; else -> "this $computer" }
        song.quality.takeIf { it.isNotBlank() }?.let { steps += "the file" to it }
    }
    val o = out
    val alone = sound.exclusive && o != null && (o.ao.startsWith("coreaudio") || o.ao == "wasapi" || o.ao == "alsa")
    if (speaker != null) {
        val sp = speaker!!
        steps += "sent" to "to ${sp.name.lowercase()}" + (quality.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + when {
            song?.server == true -> "\nstreamed from your server"
            song?.tube == true -> "\nyoutube's stream, found by this $computer"
            song?.station == true -> ""
            else -> "\nserved by this $computer"
        }
        val r = said
        steps += "the receiver" to sp.model.ifBlank { sp.name }.lowercase() + (r?.takes?.takeIf { it.isNotEmpty() }?.let { "\ntakes ${it.joinToString(", ")}" } ?: "")
        if (r == null && sp.kind == "dlna") steps += "asking" to "asking the ${sp.maker.ifBlank { "receiver" }.lowercase().substringBefore(',')}…"
        if (sp.kind == "cast") {
            r?.lines?.forEach { steps += it }
            castVolume?.let { steps += "volume" to "${Math.round(it * 100)}%, the speaker's own" + if (it < .995f) " (digital)" else "" }
            steps += "the speaker" to "plays it itself: its own decoder, its own dac"
        } else if (r?.live == true) r.lines.forEach { steps += it }
        else steps += "the speaker" to "plays it itself: its own decoder, its own dac"
    } else if (h != null) {
        if (o != null && o.decoder.isNotEmpty())
            steps += "decoded" to "ffmpeg's ${o.decoder.substringBefore(' ').lowercase()} decoder" + (o.decoded.takeIf { it.isNotEmpty() }?.let { ", to ${bitsOf(it)}" } ?: "")
        steps += "even volume" to (if (!sound.even) "off, as recorded" else when (val g = h.gain) {
            null -> "none in the stream"
            Player.UNTAGGED -> "%+.1f db: the file has no replaygain tags, so a guess".format(java.util.Locale.ROOT, g)
            0f -> "0 db"
            else -> "%+.1f db, so it sits with the rest".format(java.util.Locale.ROOT, g)
        })
        acts["even volume"] = (if (sound.even) "turn off" else "turn on") to { Player.sound(sound.copy(even = !sound.even)) }
        steps += "volume" to when {
            muted -> "muted"
            volume >= 100 -> "100, left as it is"
            else -> "$volume: turned down digitally, in mpv"
        }
        if (muted || volume < 100) acts["volume"] = "to 100" to { if (muted) Player.mute(false); Player.volume(100) }
        if (o != null && o.rate > 0) steps += "to the $computer" to listOfNotNull(bitsOf(o.format), k(o.rate),
            o.channels.takeIf { it > 0 }?.let { when (it) { 1 -> "mono"; 2 -> "stereo"; else -> "$it channels" } }).joinToString(" · ")
        if (o != null && o.ao.isNotEmpty()) {
            val sys = system(o.ao)
            steps += sys to when {
                o.ao == "null" -> "no output: silent"
                alone -> "steps aside: ink music has the output alone, at ${k(o.rate)}"
                sound.exclusive && (o.ao == "pipewire" || o.ao == "pulse") -> "has no alone: it mixes it with everything else"
                o.ao.startsWith("coreaudio") -> "mixes it with everything else, at the output's own rate (audio midi setup)"
                o.ao == "wasapi" -> "mixes it with everything else, at its own rate (sound settings, the output's format)"
                else -> "mixes it with everything else, resampled to its rate where they differ"
            }
            // the word only where the system can give the output to one app (pipewire and pulse can't), or to undo it
            if (sound.exclusive || o.ao.startsWith("coreaudio") || o.ao == "wasapi" || o.ao == "alsa")
                acts[sys] = (if (sound.exclusive) "share it" else "have it alone") to { Player.sound(sound.copy(exclusive = !sound.exclusive)) }
        }
        steps += "your ears" to (o?.device?.takeIf { it.isNotEmpty() }?.lowercase() ?: "the $computer's own output")
    }
    // exact? plainly: yes only when every step left it be, else what touched it
    val verdict = when {
        speaker?.kind == "cast" -> {
            val file = sent(quality, "")
            val asIs = file?.lossless == true && song?.let { it.quality.isBlank() || it.quality.substringBefore(' ').equals(file.codec, true) } != false
            val v = castVolume
            when {
                file?.lossless != true -> "as good as its stream: lossy to begin with"
                !asIs -> "no: made smaller on the way"
                v != null && v < .995f -> "no: the speaker's own volume (${Math.round(v * 100)}%) turns it down digitally; at 100%, it'd be the file as it is"
                else -> "close: the file as it is, at the speaker's full volume; what it does inside, google doesn't say"
            }
        }
        speaker != null && said?.live == true && !said!!.tells ->
            if (sent(quality, "")?.lossless == true) "close: the file reaches the ${speaker!!.maker.ifBlank { "receiver" }.lowercase().substringBefore(',')} as it is; what it does inside, it doesn't say"
            else "as good as its stream: lossy to begin with"
        speaker != null && said?.live == true -> said!!.touching.let { t ->
            val who = speaker!!.maker.ifBlank { "receiver" }.lowercase().substringBefore(',')
            val file = sent(quality, "")
            val asIs = file?.lossless == true &&
                (file.rate == 0 || said!!.lines.none { it.first == "it's getting" } || said!!.lines.first { it.first == "it's getting" }.second.contains(Heard.khz(file.rate)))
            if (t.isEmpty() && asIs && "direct" in said!!.mode) "yes: the file as it is, the $who's ${said!!.mode}"
            else if (t.isEmpty()) "close: the $who plays it as it comes" + (said!!.mode.takeIf { it.isNotEmpty() }?.let { ", in $it" } ?: "")
            else "no: the $who's " + (if (t.size > 1) t.dropLast(1).joinToString(", ") + " and " + t.last() else t[0]) + " shape it"
        }
        speaker != null -> "the $computer hands it on; what happens next is the speaker's"
        h == null || o == null -> ""
        else -> {
            val touched = listOfNotNull(
                "even volume moves it".takeIf { sound.even && h.gain != null && h.gain != 0f },
                "the volume turns it down".takeIf { muted || volume < 100 },
                "${system(o.ao)} mixes it".takeIf { !alone && o.ao != "null" },
                "it's resampled ${Heard.khz(h.rate)} to ${k(o.rate)}".takeIf { h.rate > 0 && o.rate > 0 && h.rate != o.rate },
                "${h.bits} bit made ${depth(o.format)}".takeIf { h.bits > 0 && depth(o.format) in 1 until h.bits },
            )
            when {
                touched.isNotEmpty() -> "no: " + (if (touched.size > 1) touched.dropLast(1).joinToString(", ") + " and " + touched.last() else touched[0]) +
                    (if (!h.lossless) "\nand lossy to begin with" else "")
                !h.lossless -> "as good as its stream: lossy to begin with"
                else -> "yes: the file's samples, untouched, at its own rate"
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Close(onClose)
        Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 16.dp)) {
            Column(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
                BasicText("WHAT YOU HEAR", style = headline(64.sp))
                Spacer(Modifier.height(8.dp))
                val word = h?.word ?: quality
                BasicText(word, style = pixel(16.sp, if (h?.lossless == true || sent(quality, "")?.lossless == true) Ink.ink else Ink.mid))
                Spacer(Modifier.height(32.dp))
                // the signal travelling down it, once as it opens: each step lit in turn from dim, the hairline to the next
                // filling with ink as the progress line does; then "exact?"
                val per = 160
                val travel = remember { Animatable(0f) }
                // a receiver's own steps arrive a moment after it opens, and the signal carries on down to them
                LaunchedEffect(steps.size) {
                    travel.animateTo(steps.size.toFloat(), tween((per * (steps.size - travel.value)).toInt().coerceAtLeast(per * 2), easing = FastOutSlowInEasing))
                }
                Column(Modifier.animateContentSize(tween(Motion.ENTER * 2, easing = Motion.expo))) {
                    steps.forEachIndexed { i, (label, value) ->
                        key(label) {
                            val reached = (travel.value - i).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                            val appear = remember { Animatable(0f) }
                            LaunchedEffect(Unit) { appear.animateTo(1f, tween(Motion.ENTER * 2, easing = Motion.expo)) }
                            Column(Modifier.graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 6.dp.toPx() }) {
                                if (i > 0) Box(Modifier.padding(start = 3.dp).width(1.dp).height(16.dp).background(Ink.line)) {
                                    Box(Modifier.fillMaxWidth().fillMaxHeight(((travel.value - i + 1) * 2).coerceIn(0f, 1f)).background(Ink.ink))
                                }
                                val lit = Ink.ink.copy(alpha = if (label == "asking") .35f else .35f + .65f * reached)
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                    BasicText(if (label == "asking") "" else label, style = pixel(12.sp, Ink.mid.copy(alpha = Ink.mid.alpha * (.5f + .5f * reached))),
                                        modifier = Modifier.width(160.dp).padding(top = 2.dp))
                                    Column(Modifier.weight(1f)) {
                                        Crossfade(value, animationSpec = tween(Motion.ENTER * 2), label = "value") { v ->
                                            BasicText(v, style = pixel(14.sp, lit).copy(lineHeight = 20.sp))
                                        }
                                        acts[label]?.let { (word, act) -> Word(word, pixel(12.sp, Ink.mid), Modifier.padding(top = 3.dp), touch = 8.dp, onClick = act) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(64.dp))
            // exact?, beside the path: lit once the signal reaches the end
            Column(Modifier.width(380.dp).padding(top = 120.dp)) {
                val arrived = (remember { Animatable(0f) }).also { a ->
                    LaunchedEffect(steps.size) { delay(160L * steps.size); a.animateTo(1f, tween(Motion.ENTER * 2, easing = Motion.expo)) }
                }
                if (verdict.isNotEmpty() && steps.none { it.first == "asking" }) Column(Modifier.graphicsLayer { alpha = arrived.value }) {
                    BasicText("EXACT?", style = headline(36.sp))
                    Spacer(Modifier.height(10.dp))
                    Crossfade(verdict, animationSpec = tween(Motion.ENTER * 2), label = "verdict") { v ->
                        BasicText(v, style = pixel(14.sp).copy(lineHeight = 21.sp))
                    }
                }
            }
        }
    }
}
