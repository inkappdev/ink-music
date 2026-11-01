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
        return UsbAudioOutput(c, h, stream, config)
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
class UsbAudioOutput(private val c: Context, private val h: UsbDac.Held, private val stream: UsbDac.Held.Stream, private val config: AudioOutputProvider.OutputConfig) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var scratch: ByteBuffer? = null
    private var started = false
    private var released = false
    private var underruns = 0
    private var volume = 1f

    // an episode's speed (Media3 hands it here: it can't on float itself): its samples through Media3's own Sonic
    // first; at 1x, music, Sonic's left out and the samples go as they are. The position is the song's time:
    // what's played since the speed was set, at that speed, after where it was then.
    private var speed = 1f
    private var sonic: androidx.media3.common.audio.SonicAudioProcessor? = null
    private var pending: ByteBuffer? = null
    private var baseUs = 0L
    private var playedAtBase = 0L

    private fun put(b: ByteBuffer): Int = if (b.isDirect) stream.write(b, b.position(), b.remaining()) else {
        // a heap buffer: through a direct one, as much as fits
        val s = scratch?.takeIf { it.capacity() >= b.remaining() } ?: ByteBuffer.allocateDirect(b.remaining().coerceAtLeast(65536)).also { scratch = it }
        s.clear(); s.put(b.duplicate()); s.flip()
        stream.write(s, 0, s.remaining())
    }

    override fun play() {
        stream.play(true)
        Audible.on(c)
        if (!started) { started = true; val now = System.currentTimeMillis(); listeners.forEach { it.onPositionAdvancing(now) } }
    }
    override fun pause() { stream.play(false); Audible.off() }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) return false
        val so = sonic
        if (so != null) {
            // Sonic's output still waiting for room first; then this buffer through it
            pending?.let { p -> val n = put(p); if (n < 0) throw AudioOutput.WriteException(n, true); p.position(p.position() + n); if (p.hasRemaining()) return false }
            so.queueInput(buffer)
            val out = so.output
            val n = put(out)
            if (n < 0) throw AudioOutput.WriteException(n, true)
            out.position(out.position() + n)
            pending = out.takeIf { it.hasRemaining() }
            return !buffer.hasRemaining()
        }
        val n = put(buffer)
        if (n < 0) throw AudioOutput.WriteException(n, true)
        buffer.position(buffer.position() + n)
        stream.underruns().let { u -> if (u > underruns && started) { underruns = u; listeners.forEach { it.onUnderrun() } } }
        return !buffer.hasRemaining()
    }

    override fun flush() {
        stream.flush(); started = false
        pending = null; baseUs = 0; playedAtBase = 0
        sonic?.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
    }
    override fun stop() {}   // what's written plays out by itself
    override fun release() {
        if (released) return
        released = true
        Audible.off()
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
    override fun getPositionUs() = baseUs + ((stream.played() - playedAtBase) * 1_000_000L / config.sampleRate * speed).toLong()
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters(speed)
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) { listeners += listener }
    override fun removeListener(listener: AudioOutput.Listener) { listeners -= listener }
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        val want = playbackParams.speed.coerceIn(.5f, 3f)
        if (want == speed) return
        baseUs = getPositionUs(); playedAtBase = stream.played()
        speed = want
        pending = null
        sonic = if (want == 1f) null else androidx.media3.common.audio.SonicAudioProcessor().apply {
            setSpeed(want)
            configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(config.sampleRate, Integer.bitCount(config.channelMask), config.encoding))
            flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
        }
    }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}
}

/**
 * Silence to the phone's speaker while the DAC plays, so Android knows ink music is playing (2026-11-01:
 * "when using the phone the music goes in and out"). Samsung holds back every process it doesn't hear playing while
 * the phone animates (OLAF: unlocking, opening an app; up to a second), and the DAC's samples go past Android, so it
 * didn't hear ours: the stream's thread stood still, and the DAC with it. Zeros, so nothing's heard.
 */
object Audible {
    private var track: android.media.AudioTrack? = null

    @Synchronized fun on(c: Context) {
        if (track != null) return
        val rate = 48_000
        val min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(min * 2).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        }.getOrNull() ?: return
        // the speaker, not the DAC (ink music has it; Android can't open it)
        c.getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { t.setPreferredDevice(it) }
        runCatching { t.play() }.onFailure { t.release(); return }
        track = t
        Thread({
            val zeros = ShortArray(min / 2)
            while (track === t) if (t.write(zeros, 0, zeros.size) < 0) break
        }, "ink-audible").apply { isDaemon = true; start() }
        android.util.Log.i("ink-usb", "audible: silence to the speaker, so Android hears ink music playing")
    }

    @Synchronized fun off() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush(); t.release() }
    }
}
