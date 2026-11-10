package app.ink.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.draw.drawBehind
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
 * The signal path (the phone's, 2026-10-30; on the computer since): what reaches your ears, step by step, from
 * where the song came to where it plays, each as mpv read it, a hairline between, the signal travelling down it as it
 * opens; what's yours to change a word on its step (even volume, the volume, alone or shared), the path and "exact?"
 * following at once. On a speaker, the path ends there, the receiver saying what it does where it says.
 */
/** The path worked out: its steps (a word and what it is), the words to change a step, "exact?", what's heard. */
class HeardPath(val steps: List<Pair<String, String>>, val acts: Map<String, Pair<String, () -> Unit>>, val verdict: String, val word: String, val lossless: Boolean)

@Composable
fun SignalPath(h: Heard?, onClose: () -> Unit) {
    val sg = rememberSignal(h)
    val steps = sg.steps
    val acts = sg.acts
    val verdict = sg.verdict
    Column(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Close(onClose)
        Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 16.dp)) {
            Column(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
                BasicText("WHAT YOU HEAR", style = headline(64.sp))
                Spacer(Modifier.height(8.dp))
                BasicText(sg.word, style = pixel(16.sp, if (sg.lossless) Ink.ink else Ink.mid))
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

/**
 * The signal path as a band across the foot of the player, on a wide window (2026-11-09: the page left most of a
 * desktop's window empty, and the player out of sight): the steps left to right as a rack, each its square and the
 * line on to the next, the signal travelling along it and lighting each in turn, the step's word over what it is, and
 * "exact?" where it arrives at the right. The cover and the controls stay above it.
 */
@Composable
fun SignalBand(h: Heard?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val sg = rememberSignal(h)
    val steps = sg.steps
    val per = 140
    val travel = remember { Animatable(0f) }
    LaunchedEffect(steps.size) {
        travel.animateTo(steps.size.toFloat(), tween((per * (steps.size - travel.value)).toInt().coerceAtLeast(per * 2), easing = FastOutSlowInEasing))
    }
    val arrived = (travel.value - steps.size + .5f).coerceIn(0f, 1f) * 2f
    // the dot the signal's at (the end's, exact?, once it's arrived): lit, with a soft glow breathing round it
    val head = travel.value.toInt().coerceIn(0, steps.size)
    // the lines a screen's pixel thick, the finest there is (they were 1 dp, a pixel and a half at 150%)
    val px1 = with(androidx.compose.ui.platform.LocalDensity.current) { 1.toDp() }
    val breath by androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse").animateFloat(
        .35f, .8f, androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "breath")
    Column(modifier.fillMaxWidth().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}
        .padding(start = SIDE, end = SIDE, top = 14.dp, bottom = 28.dp)) {
        Box(Modifier.fillMaxWidth().height(px1).background(Ink.line))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            BasicText("WHAT YOU HEAR", style = headline(26.sp))
            Spacer(Modifier.width(18.dp))
            BasicText(sg.word, style = pixel(13.sp, if (sg.lossless) Ink.ink else Ink.mid), modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            CloseArrow(onClick = onClose)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            steps.forEachIndexed { i, (label, value) ->
                key(label) {
                    val reached = (travel.value - i).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                    val lit = Ink.ink.copy(alpha = if (label == "asking") .35f else .35f + .65f * reached)
                    Column(Modifier.weight(1f).padding(end = 18.dp)) {
                        // its square, lit as the signal reaches it, and the line on to the next filling behind it
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Dot(androidx.compose.ui.graphics.lerp(Ink.dim, Ink.ink, reached), if (i == head) breath else 0f)
                            Box(Modifier.weight(1f).padding(start = 8.dp).height(px1).background(Ink.line)) {
                                Box(Modifier.fillMaxWidth((travel.value - i - .2f).coerceIn(0f, 1f)).fillMaxHeight().background(Ink.ink))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        BasicText(if (label == "asking") "" else label, style = pixel(11.sp, Ink.mid.copy(alpha = Ink.mid.alpha * (.5f + .5f * reached))))
                        Spacer(Modifier.height(6.dp))
                        Crossfade(value, animationSpec = tween(Motion.ENTER * 2), label = "value") { v ->
                            BasicText(v, style = pixel(13.sp, lit).copy(lineHeight = 19.sp))
                        }
                        sg.acts[label]?.let { (word, act) -> Word(word, pixel(12.sp, Ink.mid), Modifier.padding(top = 4.dp), touch = 6.dp, onClick = act) }
                    }
                }
            }
            // where it arrives: exact?
            Column(Modifier.width(300.dp).graphicsLayer { alpha = arrived }) {
                Dot(Ink.ink, if (head == steps.size) breath else 0f)
                Spacer(Modifier.height(10.dp))
                if (sg.verdict.isNotEmpty() && steps.none { it.first == "asking" }) {
                    BasicText("EXACT?", style = headline(20.sp))
                    Spacer(Modifier.height(6.dp))
                    Crossfade(sg.verdict, animationSpec = tween(Motion.ENTER * 2), label = "verdict") { v ->
                        BasicText(v, style = pixel(13.sp).copy(lineHeight = 19.sp))
                    }
                }
            }
        }
    }
}

/**
 * A step's dot, a small square; `glow` (0 none, to 1) a soft light round it, drawn past its edges, the one the signal's
 * reached breathing with it (2026-11-09).
 */
@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color, glow: Float) {
    Box(Modifier.width(5.dp).height(5.dp).drawBehind {
        if (glow > 0f) drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(listOf(Ink.ink.copy(alpha = .45f * glow), Ink.ink.copy(alpha = 0f)), center, 9.dp.toPx()),
            radius = 9.dp.toPx(),
        )
    }.background(color))
}

/** What's heard worked out as the path's steps, read again as they change (mpv's output each second, a receiver's). */
@Composable
fun rememberSignal(h: Heard?): HeardPath {
    val sound by Player.sound.collectAsState()
    val volume by Player.volume.collectAsState()
    val muted by Player.muted.collectAsState()
    val speaker by Casting.on.collectAsState()
    val quality by Casting.quality.collectAsState()
    val now by Player.now.collectAsState()
    val eq by Eq.state.collectAsState()
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
    // said as little as says it (2026-11-09: the band's steps read as sentences; "all the info, saying less")
    val ch = { n: Int -> when (n) { 1 -> "mono"; 2 -> "stereo"; else -> "$n channels" } }
    if (h != null) {
        steps += "from" to when (h.from) {
            "server" -> "your server"
            "youtube" -> "youtube music"
            "station" -> "the station"
            else -> "this $computer"
        }
        val first = listOfNotNull(
            h.codec,
            if (h.bits > 0 && h.rate > 0) "${h.bits}/${Heard.khz(h.rate)}" else h.rate.takeIf { it > 0 }?.let(k),
            h.kbps.takeIf { it > 0 }?.let { "%,d kbps".format(java.util.Locale.ROOT, it) },
        ).joinToString(" · ")
        val second = listOfNotNull(h.channels.takeIf { it > 0 }?.let(ch), if (h.hires) "hi-res lossless" else if (h.lossless) "lossless" else "lossy").joinToString(" · ")
        steps += (if (h.from == "youtube" || h.from == "station") "the stream" else "the file") to "$first\n$second"
    } else if (speaker != null && song != null) {
        steps += "from" to when { song.server -> "your server"; song.tube -> "youtube music"; song.station -> "the station"; else -> "this $computer" }
        song.quality.takeIf { it.isNotBlank() }?.let { steps += "the file" to it }
    }
    val o = out
    val alone = sound.exclusive && o != null && (o.ao.startsWith("coreaudio") || o.ao == "wasapi" || o.ao == "alsa")
    if (speaker != null) {
        val sp = speaker!!
        steps += "sent" to sp.name.lowercase() + (quality.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + when {
            song?.server == true -> "\nfrom your server"
            song?.tube == true -> "\nyoutube, by this $computer"
            song?.station == true -> ""
            else -> "\nfrom this $computer"
        }
        val r = said
        steps += "the receiver" to sp.model.ifBlank { sp.name }.lowercase() + (r?.takes?.takeIf { it.isNotEmpty() }?.let { "\ntakes ${it.joinToString(", ")}" } ?: "")
        if (r == null && sp.kind == "dlna") steps += "asking" to "asking the ${sp.maker.ifBlank { "receiver" }.lowercase().substringBefore(',')}…"
        if (sp.kind == "cast") {
            r?.lines?.forEach { steps += it }
            castVolume?.let { steps += "volume" to "${Math.round(it * 100)}%" + if (it < .995f) " · digital" else "" }
            steps += "the speaker" to "its own decoder and dac"
        } else if (r?.live == true) r.lines.forEach { steps += it }
        else steps += "the speaker" to "its own decoder and dac"
    } else if (h != null) {
        if (o != null && o.decoder.isNotEmpty())
            steps += "decoded" to "ffmpeg ${o.decoder.substringBefore(' ').lowercase()}" + (o.decoded.takeIf { it.isNotEmpty() }?.let { " · ${bitsOf(it)}" } ?: "")
        steps += "even volume" to (if (!sound.even) "off" else when (val g = h.gain) {
            null -> "no tags"
            Player.UNTAGGED -> "%+.1f db · a guess, no tags".format(java.util.Locale.ROOT, g)
            0f -> "0 db"
            else -> "%+.1f db".format(java.util.Locale.ROOT, g)
        })
        acts["even volume"] = (if (sound.even) "turn off" else "turn on") to { Player.sound(sound.copy(even = !sound.even)) }
        if (eq.on) {
            steps += "equalizer" to eq.label + if (eq.shaping) "" else " · flat"
            acts["equalizer"] = "turn off" to { Eq.set(eq.copy(on = false)) }
        }
        steps += "volume" to when {
            muted -> "muted"
            volume >= 100 -> "100"
            else -> "$volume · digital"
        }
        if (muted || volume < 100) acts["volume"] = "to 100" to { if (muted) Player.mute(false); Player.volume(100) }
        if (o != null && o.rate > 0) steps += "output" to listOfNotNull(bitsOf(o.format), k(o.rate),
            o.channels.takeIf { it > 2 }?.let(ch)).joinToString(" · ")
        if (o != null && o.ao.isNotEmpty()) {
            val sys = system(o.ao)
            steps += sys to when {
                o.ao == "null" -> "silent"
                alone -> "ink's alone · ${k(o.rate)}"
                sound.exclusive && (o.ao == "pipewire" || o.ao == "pulse") -> "shared · it can't be alone"
                o.ao.startsWith("coreaudio") || o.ao == "wasapi" -> "shared · at its own rate"
                else -> "shared · resampled where it differs"
            }
            // the word only where the system can give the output to one app (pipewire and pulse can't), or to undo it
            if (sound.exclusive || o.ao.startsWith("coreaudio") || o.ao == "wasapi" || o.ao == "alsa")
                acts[sys] = (if (sound.exclusive) "share it" else "have it alone") to { Player.sound(sound.copy(exclusive = !sound.exclusive)) }
        }
        steps += "your ears" to (o?.device?.takeIf { it.isNotEmpty() }?.lowercase() ?: "the $computer's output")
    }
    // exact? plainly: yes only when every step left it be, else what touched it, in a word each
    val verdict = when {
        speaker?.kind == "cast" -> {
            val file = sent(quality, "")
            val asIs = file?.lossless == true && song?.let { it.quality.isBlank() || it.quality.substringBefore(' ').equals(file.codec, true) } != false
            val v = castVolume
            when {
                file?.lossless != true -> "lossy at the source"
                !asIs -> "no: made smaller on the way"
                v != null && v < .995f -> "no: the speaker's volume, ${Math.round(v * 100)}%"
                else -> "close: as it is, at full volume; inside, google doesn't say"
            }
        }
        speaker != null && said?.live == true && !said!!.tells ->
            if (sent(quality, "")?.lossless == true) "close: it arrives as it is; inside, it doesn't say"
            else "lossy at the source"
        speaker != null && said?.live == true -> said!!.touching.let { t ->
            val file = sent(quality, "")
            val asIs = file?.lossless == true &&
                (file.rate == 0 || said!!.lines.none { it.first == "it's getting" } || said!!.lines.first { it.first == "it's getting" }.second.contains(Heard.khz(file.rate)))
            if (t.isEmpty() && asIs && "direct" in said!!.mode) "yes: as it is, ${said!!.mode}"
            else if (t.isEmpty()) "close: played as it comes" + (said!!.mode.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "")
            else "no: " + t.joinToString(" · ")
        }
        speaker != null -> "handed on: the rest is the speaker's"
        h == null || o == null -> ""
        else -> {
            val touched = listOfNotNull(
                "even volume".takeIf { sound.even && h.gain != null && h.gain != 0f },
                "equalizer".takeIf { eq.on },
                "volume".takeIf { muted || volume < 100 },
                "${system(o.ao)}'s mix".takeIf { !alone && o.ao != "null" },
                "${Heard.khz(h.rate)} → ${k(o.rate)}".takeIf { h.rate > 0 && o.rate > 0 && h.rate != o.rate },
                "${h.bits} → ${depth(o.format)} bit".takeIf { h.bits > 0 && depth(o.format) in 1 until h.bits },
            )
            when {
                touched.isNotEmpty() -> "no: " + touched.joinToString(" · ") + (if (!h.lossless) "\nlossy at the source" else "")
                !h.lossless -> "lossy at the source"
                else -> "yes: untouched"
            }
        }
    }
    return HeardPath(steps, acts, verdict, h?.word ?: quality, h?.lossless == true || sent(quality, "")?.lossless == true)
}
