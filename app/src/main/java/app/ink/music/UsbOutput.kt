package app.ink.music

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Where the player's samples go: to the USB DAC ink music holds ([UsbDac]) when exact is on and one's plugged in and
 * allowed, else to Android's audio as always ([fallback]). Asked by Media3 for each format, so a song the DAC can't
 * take at its rate goes Android's way (the DAC let go of first, so Android has it).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class UsbOutputProvider(private val c: Context, private val fallback: AudioOutputProvider) : AudioOutputProvider {
    private val listeners = CopyOnWriteArraySet<AudioOutputProvider.Listener>()

    /** The DAC's for this format, if it is: held, and taking this rate and these channels. */
    private fun dac(rate: Int, channels: Int, encoding: Int): UsbDac.Held? {
        if (!Exact.on(c) || (encoding != C.ENCODING_PCM_FLOAT && encoding != C.ENCODING_PCM_16BIT)) return null
        val h = UsbDac.hold(c) ?: return null
        return h.takeIf { it.altFor(rate, channels) != null }
    }

    // only decoded samples: a flac file's own format says 16 bit too, and taken as the DAC's, Media3 sent it the
    // flac itself, undecoded (2026-11-01)
    private fun raw(f: androidx.media3.common.Format) = f.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val f = formatConfig.format
        if (raw(f) && dac(f.sampleRate, f.channelCount, f.pcmEncoding) != null)
            return AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        return fallback.getFormatSupport(formatConfig)
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val f = formatConfig.format
        if (!raw(f) || dac(f.sampleRate, f.channelCount, f.pcmEncoding) == null) {
            // Android's way: the DAC given back to it first, or Android would play it on the phone's speaker
            if (UsbDac.held != null) UsbDac.release()
            return fallback.getOutputConfig(formatConfig)
        }
        val frame = (if (f.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2) * f.channelCount
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(f.pcmEncoding).setSampleRate(f.sampleRate)
            .setChannelMask(androidx.media3.common.util.Util.getAudioTrackChannelConfig(f.channelCount))
            .setBufferSize(f.sampleRate / 4 * frame)
            .setAudioAttributes(formatConfig.audioAttributes).setAudioSessionId(formatConfig.audioSessionId)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val channels = Integer.bitCount(config.channelMask)
        val h = dac(config.sampleRate, channels, config.encoding) ?: return exclusive(config, channels) ?: fallback.getAudioOutput(config)
        val stream = h.begin(config.sampleRate, channels, config.encoding == C.ENCODING_PCM_FLOAT)
            ?: throw AudioOutputProvider.InitializationException()
        return UsbAudioOutput(h, stream, config)
    }

    /**
     * No USB DAC: a player's own headphone or line output asked for exclusively at the song's rate (Exclusive), where
     * exact's on; Android saying no, null (the shared way). The config is Android's own (an AudioTrack's), so either fits.
     */
    private fun exclusive(config: AudioOutputProvider.OutputConfig, channels: Int): AudioOutput? {
        if (!Exact.on(c) || UsbDac.held != null || config.isOffload) return null
        if (config.encoding != C.ENCODING_PCM_FLOAT && config.encoding != C.ENCODING_PCM_16BIT) return null
        val out = Exclusive.output(c) ?: return null
        val h = Exclusive.open(config.sampleRate, channels, config.encoding == C.ENCODING_PCM_FLOAT, out.id)
        if (h == 0L) return null
        return ExclusiveOutput(h, config)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) { listeners += listener; fallback.addListener(listener) }
    override fun removeListener(listener: AudioOutputProvider.Listener) { listeners -= listener; fallback.removeListener(listener) }
    override fun release() { fallback.release() }

    /** The DAC plugged in or out, or exact turned on or off: Media3 asks again which way each format goes. */
    fun changed() { listeners.forEach { it.onFormatSupportChanged() } }
}

/**
 * The player's samples to the DAC: written into usbaudio.c's ring, its position the frames the DAC has had. Its
 * stream gone (the DAC unplugged, exact off), a write fails as recoverable, and Media3 asks again where to play.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class UsbAudioOutput(private val h: UsbDac.Held, private val stream: UsbDac.Held.Stream, private val config: AudioOutputProvider.OutputConfig) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var scratch: ByteBuffer? = null
    private var started = false
    private var released = false
    private var underruns = 0
    private var volume = 1f

    override fun play() {
        stream.play(true)
        if (!started) { started = true; val now = System.currentTimeMillis(); listeners.forEach { it.onPositionAdvancing(now) } }
    }
    override fun pause() { stream.play(false) }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) return false
        val n = if (buffer.isDirect) stream.write(buffer, buffer.position(), buffer.remaining()) else {
            // a heap buffer: through a direct one, as much as fits
            val s = scratch?.takeIf { it.capacity() >= buffer.remaining() } ?: ByteBuffer.allocateDirect(buffer.remaining().coerceAtLeast(65536)).also { scratch = it }
            s.clear(); val dup = buffer.duplicate(); s.put(dup); s.flip()
            stream.write(s, 0, s.remaining())
        }
        if (n < 0) throw AudioOutput.WriteException(n, true)
        buffer.position(buffer.position() + n)
        stream.underruns().let { u -> if (u > underruns && started) { underruns = u; listeners.forEach { it.onUnderrun() } } }
        return !buffer.hasRemaining()
    }

    override fun flush() { stream.flush(); started = false }
    override fun stop() {}   // what's written plays out by itself
    override fun release() {
        if (released) return
        released = true
        stream.end()
        listeners.forEach { it.onReleased() }
    }

    // the player's own volume (a soft pause's fade), times the DAC's level where it has no volume of its own
    override fun setVolume(volume: Float) { this.volume = volume; stream.gain(volume * h.digital) }
    init { stream.gain(h.digital) }
    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = config.audioSessionId
    override fun getSampleRate() = config.sampleRate
    override fun getBufferSizeInFrames() = (config.sampleRate / 4).toLong()
    override fun getPositionUs() = stream.played() * 1_000_000L / config.sampleRate
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) { listeners += listener }
    override fun removeListener(listener: AudioOutput.Listener) { listeners -= listener }
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {}   // a speaker's speed: none (an episode plays at 1x on the DAC)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}
}
