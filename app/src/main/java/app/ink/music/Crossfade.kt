package app.ink.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Automix's first half, the smart crossfade (settings, sound; Bronson, 2026-11-01, after BitChord's Automix): a song
 * ending goes into the next, the next coming in under it as it goes. Not at a fixed time: each song's end is
 * measured as it starts (its last 25 seconds decoded on their own), so a song fading out hands over as it fades,
 * and one ending in silence hands over before the silence; the next one's own silence at its start is skipped.
 *
 * Media3 mixes nothing, so a second player, quiet and apart (no audio focus, no session), plays the old song's last
 * seconds from where it is while the player itself goes on into the next song: the queue, the lock screen and the
 * player page are the next song's from the first moment. Equal-power curves, so the loudness neither dips nor bumps.
 *
 * Never within a record played in order (its songs run into each other as made), never an episode or a station,
 * never with exact on (it changes the sound) or on a speaker, never on repeat one, never a skip of yours.
 */
class Crossfade(
    private val c: Context, private val exo: ExoPlayer, private val sources: MediaSource.Factory,
    private val levelling: () -> Levelling?, private val casting: () -> Boolean,
) {
    /** Where a song's sound ends (ms), where its handover begins, and where its sound begins. */
    data class Ends(val lastSound: Long, val handover: Long, val firstSound: Long)

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val ends = ConcurrentHashMap<String, Ends>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private var tail: ExoPlayer? = null
    private var tailFor: String? = null
    private var fading = false
    private var ours = false   // the song change in hand is the crossfade's own

    companion object {
        const val FADE = 6_000L
        fun on(c: Context) = File(c.filesDir, "crossfade").exists()
        fun set(c: Context, on: Boolean) { runCatching { File(c.filesDir, "crossfade").let { if (on) it.writeText("on") else it.delete() } } }
    }

    private val tick = object : Runnable {
        override fun run() { runCatching { look() }; main.postDelayed(this, 100) }
    }

    fun start() {
        main.post(tick)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!ours && fading) end()   // a skip of yours, mid-fade: the old one goes at once
                ours = false
                measure()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) end() }
        })
    }

    fun release() { main.removeCallbacks(tick); end(); work.shutdown() }

    /** This song and the next: may one go into the other? */
    private fun allowed(): Boolean {
        if (!on(c) || Exact.on(c) || casting() || exo.repeatMode == Player.REPEAT_MODE_ONE) return false
        val i = exo.currentMediaItemIndex
        val n = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return false
        val a = exo.getMediaItemAt(i); val b = exo.getMediaItemAt(n)
        if (listOf(a, b).any { it.mediaId.startsWith("e") || it.mediaId.startsWith("s") }) return false
        // a record in order: its songs run into each other as they were made
        val ra = a.mediaMetadata.extras?.getString(Levelling.RECORD).orEmpty()
        if (ra.isNotEmpty() && ra == b.mediaMetadata.extras?.getString(Levelling.RECORD) && n == i + 1) return false
        return exo.duration != C.TIME_UNSET && exo.duration > FADE * 3
    }

    private fun look() {
        if (on(c) && exo.currentMediaItem?.mediaId?.let { it !in asked } == true) measure()   // switched on mid-song, or the first
        if (fading || !exo.isPlaying || !allowed()) { if (!fading) dropTail(); return }
        val item = exo.currentMediaItem ?: return
        val dur = exo.duration
        val e = ends[item.mediaId]
        val at = (e?.handover ?: (dur - FADE)).coerceIn(dur / 2, dur - 1_000)
        val pos = exo.currentPosition
        // the old song's last seconds made ready a little ahead, where they'll be needed
        if (pos >= at - 3_000 && tailFor != item.mediaId) readyTail(item, at)
        if (pos >= at && tailFor == item.mediaId && tail?.playbackState == Player.STATE_READY) go(e)
    }

    private fun readyTail(item: MediaItem, at: Long) {
        dropTail()
        tail = ExoPlayer.Builder(c).setMediaSourceFactory(sources)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build().apply {
                setMediaItem(item, at)
                volume = levelling()?.level ?: 1f
                prepare()
            }
        tailFor = item.mediaId
    }

    private fun dropTail() { tail?.release(); tail = null; tailFor = null }

    /** The handover: the old song's tail sounding, the player on into the next song from its first sound, faded across. */
    private fun go(e: Ends?) {
        val t = tail ?: return
        fading = true
        t.seekTo(exo.currentPosition + 120)   // where the player will be once the tail's sounding
        t.play()
        // the player goes on into the next song only once the tail sounds (else a moment of nothing between)
        val asked = SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                if (!fading || tail !== t) return
                if (!t.isPlaying && SystemClock.uptimeMillis() - asked < 400) { main.postDelayed(this, 10); return }
                handOver(t, e)
            }
        })
    }

    private fun handOver(t: ExoPlayer, e: Ends?) {
        val from = exo.currentPosition
        ours = true
        val l = levelling()
        l?.fade = 0f
        val next = exo.nextMediaItemIndex
        val nextId = exo.getMediaItemAt(next).mediaId
        val skip = ends[nextId]?.firstSound ?: 0L
        exo.seekTo(next, skip)
        val tailLevel = t.volume
        val length = (e?.let { (it.lastSound - from).coerceIn(2_000, FADE * 2) } ?: FADE)
        android.util.Log.d("ink-crossfade", "into $nextId at ${from / 1000}s over ${length / 1000.0}s, its start ${skip}ms in")
        val began = SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                if (!fading || tail !== t) return
                val f = ((SystemClock.uptimeMillis() - began) / length.toFloat()).coerceIn(0f, 1f)
                // equal power: the new one rising as a sine, the old one falling as a cosine
                levelling()?.fade = kotlin.math.sin(f * Math.PI / 2).toFloat()
                t.volume = tailLevel * kotlin.math.cos(f * Math.PI / 2).toFloat()
                if (f < 1f) main.postDelayed(this, 30) else end()
            }
        })
    }

    /** The fade over (or called off): the old song's tail let go, the new one at its full level. */
    private fun end() {
        fading = false
        levelling()?.fade = 1f
        dropTail()
    }

    // ---- where each song's sound ends and begins, measured once (its own decode, apart from the playing) ----

    /** This song's end and the next one's start measured, if they aren't yet. */
    private fun measure() {
        if (!on(c)) return
        val items = listOfNotNull(exo.currentMediaItem, exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { exo.getMediaItemAt(it) })
        for (item in items) {
            val id = item.mediaId
            if (id.startsWith("e") || id.startsWith("s") || !asked.add(id)) continue
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: continue
            work.execute { runCatching { ends(uri) }.onFailure { android.util.Log.d("ink-crossfade", "$id: $it") }.getOrNull()?.let { ends[id] = it } }
        }
    }

    /** Where to read a song from, as the player would (kept on the phone first; YouTube's stream found). */
    private fun source(uri: android.net.Uri): Any? = when {
        uri.scheme == "ytm" -> Offline.file(c, "y" + uri.host) ?: YouTubeMusic.stream("y" + uri.host, false)?.first
        uri.path?.endsWith("/rest/stream") == true -> uri.getQueryParameter("id")?.let { Offline.file(c, "n$it") } ?: uri.toString()
        uri.scheme == "content" -> uri
        else -> uri.toString()
    }

    private fun ends(uri: android.net.Uri): Ends? {
        val src = source(uri) ?: return null
        fun open(): MediaExtractor? = MediaExtractor().runCatching {
            when (src) { is android.net.Uri -> setDataSource(c, src, null); is File -> setDataSource(src.path); else -> setDataSource(src.toString(), emptyMap()) }
            this
        }.getOrNull()
        val x = open() ?: return null
        val track = (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: run { x.release(); return null }
        val format = x.getTrackFormat(track)
        val dur = format.getLong(MediaFormat.KEY_DURATION) / 1000
        x.release()
        if (dur < FADE * 3) return null
        // the last 25 s: where the sound ends, and where an outro falls away
        val end = levels(open() ?: return null, track, (dur - 25_000).coerceAtLeast(0), dur) ?: return null
        val head = levels(open() ?: return null, track, 0, 6_000)
        val window = 50L
        val loud = end.second.maxOrNull() ?: return null
        val lastIdx = end.second.indexOfLast { it > -50f }.takeIf { it >= 0 } ?: return null
        val lastSound = end.first + (lastIdx + 1) * window
        // an outro falling away: the first moment from which it stays 15 dB under the end's loudest
        val quietIdx = (0..lastIdx).firstOrNull { i -> (i..lastIdx).all { end.second[it] < loud - 15f } }
        val quiet = quietIdx?.let { end.first + it * window }
        val handover = listOfNotNull(lastSound - FADE, quiet?.minus(1_000)).minOrNull()!!.coerceAtLeast(dur - 25_000)
        val firstSound = head?.second?.indexOfFirst { it > -50f }?.takeIf { it > 0 }?.let { it * window }?.takeIf { it >= 400 } ?: 0L
        android.util.Log.d("ink-crossfade", "${uri.lastPathSegment}: ${dur / 1000}s, sound to ${lastSound / 1000.0}s, quiet from ${quiet?.div(1000.0)}, hand over at ${handover / 1000.0}s, starts ${firstSound}ms in")
        return Ends(lastSound, handover, firstSound)
    }

    /** The level (dBFS) of each 50 ms from `fromMs` to `toMs`, decoded on its own; and where it began. */
    private fun levels(x: MediaExtractor, track: Int, fromMs: Long, toMs: Long): Pair<Long, List<Float>>? {
        try {
            x.selectTrack(track)
            val format = x.getTrackFormat(track)
            x.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val per = (rate * 50 / 1000) * channels
            val out = ArrayList<Float>()
            var sum = 0.0; var n = 0
            var first = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.uptimeMillis() + 8_000
            loop@ while (SystemClock.uptimeMillis() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val size = x.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0 || x.sampleTime / 1000 > toMs) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, size, x.sampleTime, 0); x.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val at = info.presentationTimeUs / 1000
                    val b: ByteBuffer = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val float = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
                    if (at >= fromMs) {
                        if (first < 0) first = at
                        if (float) { val f = b.asFloatBuffer(); while (f.hasRemaining()) { val v = f.get().toDouble(); sum += v * v; if (++n == per) { out += db(sum, n); sum = 0.0; n = 0 } } }
                        else { val s = b.asShortBuffer(); while (s.hasRemaining()) { val v = s.get() / 32768.0; sum += v * v; if (++n == per) { out += db(sum, n); sum = 0.0; n = 0 } } }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
            codec.stop(); codec.release()
            return if (first < 0 || out.isEmpty()) null else first to out
        } catch (e: Exception) {
            return null
        } finally { x.release() }
    }

    private fun db(sum: Double, n: Int) = (20 * log10(sqrt(sum / n).coerceAtLeast(1e-9))).toFloat()
}
