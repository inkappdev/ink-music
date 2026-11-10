package app.ink.music

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * The audio output fed one rate, 48 kHz, while songs run into each other (2026-11-10). Resampled inside the output (as
 * `Resample` was), a jump in a 48 kHz song with a 44.1 kHz one next had Media3 count the 48 kHz song's sound at the next
 * one's rate, 8% out: by the handover its clock had run seconds from the sound, caught up there, heard as a hitch, and
 * the countdown went back up. Made 48 kHz before the output sees it, the output's rate never changes. Only 16 bit below
 * 48 kHz (transitions on makes every song 16 bit); anything else goes through as it is.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class OneRate(sink: AudioSink, private val wanted: () -> Boolean) : ForwardingAudioSink(sink) {
    private val resample = Resample { true }
    private var on = false
    private var from = 0                 // the rate being made 48 kHz
    private var pending: ByteBuffer? = null   // resampled, not yet all taken by the output

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val f = config.format
        val pcm16 = f.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW && f.pcmEncoding == C.ENCODING_PCM_16BIT
        if (!pcm16 || !wanted() || f.sampleRate <= 0 || f.sampleRate >= Resample.RATE) {
            on = false; pending = null
            return super.configure(config)
        }
        // a new rate begins afresh; the same one runs on (a song into the next, gapless)
        if (!on || from != f.sampleRate) {
            resample.configure(AudioProcessor.AudioFormat(f.sampleRate, f.channelCount, C.ENCODING_PCM_16BIT))
            resample.flush(AudioProcessor.StreamMetadata.DEFAULT)
            pending = null
        }
        on = true; from = f.sampleRate
        // what's trimmed off its ends (in frames) at the rate it now comes at
        fun scaled(frames: Int) = (frames.toLong() * Resample.RATE / f.sampleRate).toInt()
        val made = f.buildUpon().setSampleRate(Resample.RATE).setEncoderDelay(scaled(f.encoderDelay)).setEncoderPadding(scaled(f.encoderPadding)).build()
        val b = AudioSink.AudioSinkConfig.Builder(made)
            .setPreferredBufferSizeOverride(if (config.preferredBufferSizeOverride > 0) scaled(config.preferredBufferSizeOverride) else config.preferredBufferSizeOverride)
            .setOutputChannelMapping(config.outputChannelMapping)
        config.timeline?.let { b.setTimeline(it) }
        config.mediaPeriodId?.let { b.setMediaPeriodId(it) }
        super.configure(b.build())
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!on) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // the same piece offered again (the output was full): what's left of it as made before
        val out = pending ?: run {
            resample.queueInput(buffer)
            buffer.position(buffer.limit())
            resample.output.also { pending = it }
        }
        if (!out.hasRemaining()) { pending = null; return true }
        val taken = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (taken) pending = null
        return taken
    }

    // diagnostic (2026-11-10, the countdown going back up seconds before a blend): the clock heard stepping back
    private var lastPos = Long.MIN_VALUE
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p != AudioSink.CURRENT_POSITION_NOT_SET) {
            if (lastPos != Long.MIN_VALUE && p < lastPos - 20_000) android.util.Log.d("ink-seek", "clock back ${(lastPos - p) / 1000} ms: ${lastPos / 1000} -> ${p / 1000} ms")
            lastPos = p
        }
        return p
    }

    override fun flush() {
        lastPos = Long.MIN_VALUE
        pending = null
        if (on) resample.flush(AudioProcessor.StreamMetadata.DEFAULT)
        super.flush()
    }

    override fun reset() {
        pending = null; on = false
        resample.reset()
        super.reset()
    }
}
