package app.ink.music

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.Format
import java.io.File

/**
 * Exact to a USB DAC (settings, sound; Android 14 and up; Bronson, 2026-10-30): the song's own bits at its own rate,
 * past Android's mixer (which takes everything to 48 kHz and works the volume in), by Android's bit-perfect path
 * (AudioManager.setPreferredMixerAttributes, MIXER_BEHAVIOR_BIT_PERFECT). Asked, in the player's process, for the very
 * format the player sends (16 bit at the song's rate), each time a song's format is read; only where the DAC says it
 * takes that format so. The equalizer and even volume rest while it's on (both change the bits). Whether the phone
 * honours it is the maker's: what Android granted is read back and said on the signal path, never assumed.
 * The switch is a file the screens write (each process reads it fresh), the player nudged with the equalizer's command.
 */
object Exact {
    val available = Build.VERSION.SDK_INT >= 34

    private fun file(c: Context) = File(c.filesDir, "exact")
    fun on(c: Context) = available && file(c).exists()
    fun set(c: Context, on: Boolean) { runCatching { if (on) file(c).writeText("on") else file(c).delete() } }

    private val media get() = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private val USB = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)

    private var asked: AudioDeviceInfo? = null

    /**
     * Asks for the song's `format` exactly on a USB DAC (or lets go of it): what came of it, for the signal path:
     * "" (off, or no DAC), "yes 16/44.1", or why not ("the dac won't take 16/44.1 exactly", "android said no").
     */
    fun request(c: Context, format: Format): String {
        if (Build.VERSION.SDK_INT < 34) return ""
        val am = c.getSystemService(AudioManager::class.java) ?: return ""
        val dac = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in USB }
        if (!on(c) || dac == null) { release(am); return "" }
        // what the player sends: its decoders give 16 bit (a 24 bit file, made 16: said on the path)
        val rate = format.sampleRate
        val channels = format.channelCount.takeIf { it > 0 } ?: 2
        val word = "16/${Heard.khz(rate)}"
        val pick = runCatching {
            am.getSupportedMixerAttributes(dac).firstOrNull { m ->
                m.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    m.format.encoding == AudioFormat.ENCODING_PCM_16BIT && m.format.sampleRate == rate && m.format.channelCount == channels
            }
        }.getOrNull()
        if (pick == null) { release(am); android.util.Log.i("ink-exact", "no bit-perfect $word on ${dac.productName}"); return "the dac won't take $word exactly" }
        val ok = runCatching { am.setPreferredMixerAttributes(media, dac, pick) }.getOrDefault(false)
        asked = dac
        // read back: what Android holds now is what's said
        val held = runCatching { am.getPreferredMixerAttributes(media, dac) }.getOrNull()
        val granted = ok && held?.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT && held.format.sampleRate == rate
        android.util.Log.i("ink-exact", "$word on ${dac.productName}: asked $ok, granted $granted")
        return if (granted) "yes $word" else "android said no"
    }

    /** Lets go of the exact path (off, the DAC gone): Android's mixer again. */
    fun release(am: AudioManager) {
        val d = asked ?: return
        if (Build.VERSION.SDK_INT >= 34) runCatching { am.clearPreferredMixerAttributes(media, d) }
        asked = null
    }

    /** Where media goes now: its name and kind ("usb", "bluetooth", "wired", "speaker"). */
    fun output(c: Context): Pair<String, String> {
        val am = c.getSystemService(AudioManager::class.java) ?: return "" to ""
        val d = if (Build.VERSION.SDK_INT >= 33) runCatching { am.getAudioDevicesForAttributes(media).firstOrNull() }.getOrNull()
            else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).sortedBy { order.indexOf(kind(it.type)).let { i -> if (i < 0) 9 else i } }.firstOrNull()
        d ?: return "" to ""
        val k = kind(d.type)
        val name = d.productName?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() && k != "speaker" && it != Build.MODEL }
        return (name ?: when (k) { "speaker" -> "the phone's speaker"; "wired" -> "wired headphones"; "usb" -> "a usb dac"; "bluetooth" -> "bluetooth"; else -> "" }) to k
    }

    private val order = listOf("usb", "wired", "bluetooth", "speaker")
    private fun kind(type: Int) = when (type) {
        in USB -> "usb"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "speaker"
        else -> "other"
    }

    /** The rate Android's mixer runs at (48 kHz on nearly every phone). */
    fun mixer(c: Context): Int = runCatching {
        c.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt()
    }.getOrNull() ?: 0
}
