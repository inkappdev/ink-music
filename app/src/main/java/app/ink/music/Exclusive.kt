package app.ink.music

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Exact's other way (exclusive.c): a music player with its own DAC inside (FiiO's, HiBy's), its headphone or line
 * output asked for by AAudio exclusively, at the song's own rate, with no mixer between. Only where there's no USB
 * DAC ink music holds and the sound goes to a headphone or line output; Android saying no (or another rate), the
 * shared way as always.
 */
object Exclusive {
    init { System.loadLibrary("inkusb") }
    @JvmStatic external fun open(rate: Int, channels: Int, inFloat: Boolean, device: Int): Long
    @JvmStatic external fun write(h: Long, buf: ByteBuffer, off: Int, len: Int): Int
    @JvmStatic external fun play(h: Long, on: Boolean)
    @JvmStatic external fun flush(h: Long)
    @JvmStatic external fun played(h: Long): Long
    @JvmStatic external fun capacity(h: Long): Int
    @JvmStatic external fun underruns(h: Long): Int
    @JvmStatic external fun gain(h: Long, g: Float)
    @JvmStatic external fun close(h: Long)

    /** The rate an exclusive stream plays at now (0: none), for the signal path. */
    @Volatile var rate = 0
    @Volatile var underrunsNow = 0

    private val OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE,
    )

    /**
     * The player's own output to ask for, or null: a headphone or line output that's where media goes now. (On a
     * phone with only USB and Bluetooth, never; `adb shell setprop debug.ink.exclusive speaker` tries it on the
     * speaker, to see the way work.)
     */
    fun output(c: Context): AudioDeviceInfo? {
        val am = c.getSystemService(AudioManager::class.java) ?: return null
        val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
        val d = runCatching { am.getAudioDevicesForAttributes(attrs).firstOrNull() }.getOrNull()
            ?.let { info -> am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == info.type && it.address == info.address } }
            ?: return null
        val trying = runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.ink.exclusive") as String }.getOrNull() == "speaker"
        return d.takeIf { it.type in OUTPUTS || (trying && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) }
    }
}

/** The player's samples to the exclusive stream, written as they come; its position the frames the output has taken. */
@androidx.annotation.OptIn(UnstableApi::class)
class ExclusiveOutput(private val h: Long, private val config: AudioOutputProvider.OutputConfig) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var scratch: ByteBuffer? = null
    private var started = false
    private var released = false
    private var underruns = 0

    init { Exclusive.rate = config.sampleRate }

    override fun play() {
        Exclusive.play(h, true)
        if (!started) { started = true; val now = System.currentTimeMillis(); listeners.forEach { it.onPositionAdvancing(now) } }
    }
    override fun pause() { Exclusive.play(h, false) }
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) return false
        val n = if (buffer.isDirect) Exclusive.write(h, buffer, buffer.position(), buffer.remaining()) else {
            val s = scratch?.takeIf { it.capacity() >= buffer.remaining() } ?: ByteBuffer.allocateDirect(buffer.remaining().coerceAtLeast(65536)).also { scratch = it }
            s.clear(); s.put(buffer.duplicate()); s.flip()
            Exclusive.write(h, s, 0, s.remaining())
        }
        if (n < 0) throw AudioOutput.WriteException(n, true)
        buffer.position(buffer.position() + n)
        Exclusive.underruns(h).let { u -> Exclusive.underrunsNow = u; if (u > underruns && started) { underruns = u; listeners.forEach { it.onUnderrun() } } }
        return !buffer.hasRemaining()
    }
    override fun flush() { Exclusive.play(h, false); Exclusive.flush(h); started = false }
    override fun stop() {}
    override fun release() {
        if (released) return
        released = true
        Exclusive.close(h)
        Exclusive.rate = 0
        listeners.forEach { it.onReleased() }
    }
    override fun setVolume(volume: Float) { Exclusive.gain(h, volume) }
    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = config.audioSessionId
    override fun getSampleRate() = config.sampleRate
    override fun getBufferSizeInFrames() = Exclusive.capacity(h).toLong()
    override fun getPositionUs() = Exclusive.played(h) * 1_000_000L / config.sampleRate
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) { listeners += listener }
    override fun removeListener(listener: AudioOutput.Listener) { listeners -= listener }
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {}
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}
}
