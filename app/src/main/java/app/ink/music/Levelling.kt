package app.ink.music

import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlin.math.pow

/**
 * Volume levelling (ReplayGain), in the player's process: every song as loud as the next. The gain
 * is the song's own tag (FLAC and Ogg's comments, MP3's TXXX, M4A's iTunes atoms, Opus's R128),
 * else the server's (Navidrome reads the same tags), else [UNKNOWN]. Media3 drops an M4A's
 * iTunes atoms, so a phone's M4A is read here ([Mp4Gain]), off the main thread, the next song ahead. A record played in order
 * (the song beside it in the queue from the same record) takes the album's gain, so its quiet
 * songs stay quiet; anywhere else, the song's own. Down is the player's volume, up a
 * [LoudnessEnhancer] (its limiter keeps a quiet song lifted from clipping).
 */
class Levelling(private val player: Player, private val context: android.content.Context) {
    var on = true
        set(v) { field = v; apply() }
    /** What the player's volume is at, levelled (sleep's fade works down from it). */
    var level = 1f
        private set
    /** The song's gain as last applied (dB); told to the screens (Heard) once it's worked out. */
    var db = 0f
        private set
    var onApply: (() -> Unit)? = null
    private var boost: LoudnessEnhancer? = null
    private var session = C.AUDIO_SESSION_ID_UNSET

    /** The song's gain worked out again (a new song, its tags read, the queue round it changed). */
    fun apply() {
        val db = if (on) gain() else 0f
        this.db = db
        level = 10f.pow(db.coerceAtMost(0f) / 20f)
        player.volume = level
        val up = (db.coerceAtLeast(0f) * 100).toInt()   // millibels
        val e = enhancer()?.runCatching { setTargetGain(up); enabled = up > 0 }
        android.util.Log.d("ink-level", "${player.currentMediaItem?.mediaId} ${"%.1f".format(db)} dB  volume $level  boost $up mB ${if (e?.isSuccess == true) "" else "(no enhancer)"}")
        onApply?.invoke()
    }

    private fun enhancer(): LoudnessEnhancer? {
        val id = (player as? androidx.media3.exoplayer.ExoPlayer)?.audioSessionId ?: return null
        if (id == C.AUDIO_SESSION_ID_UNSET) return null
        if (id != session) {
            boost?.release()
            boost = runCatching { LoudnessEnhancer(id) }.getOrNull()
            session = id
        }
        return boost
    }

    fun release() { boost?.release(); boost = null; files.shutdown() }

    // a phone's M4A, read by hand: its gains by the item's address (null: read, none there)
    private val read = java.util.concurrent.ConcurrentHashMap<String, Gains>()
    private val reading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val files = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The M4A's own gains, if read; set reading it (and the next song) if not. */
    private fun mp4(item: MediaItem): Gains? {
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it) }
        listOfNotNull(item, next).forEach { i ->
            val uri = i.localConfiguration?.uri ?: return@forEach
            if (uri.scheme != "content" || read.containsKey(uri.toString()) || !reading.add(uri.toString())) return@forEach
            files.execute {
                val g = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { Mp4Gain.read(java.io.FileInputStream(it.fileDescriptor).channel) }
                }.getOrNull()
                read[uri.toString()] = g?.let { (t, a) -> Gains(t, a) } ?: Gains(null, null)
                if (i === item) main.post { apply() }
            }
        }
        return item.localConfiguration?.uri?.toString()?.let(read::get)
    }

    private fun gain(): Float {
        val item = player.currentMediaItem ?: return 0f
        val album = inOrder(item)
        val tags = tagged(player.currentTracks).let { if (it.track != null || it.album != null) it else mp4(item) ?: it }
        val extras = item.mediaMetadata.extras
        fun said(key: String) = extras?.getFloat(key, Float.NaN)?.takeIf { !it.isNaN() }
        return (if (album) tags.album ?: said(ALBUM_GAIN) else null)
            ?: tags.track ?: said(TRACK_GAIN) ?: tags.album ?: said(ALBUM_GAIN) ?: UNKNOWN
    }

    /** A record played in order: the song before or after it in the queue is off the same record. */
    private fun inOrder(item: MediaItem): Boolean {
        val record = item.mediaMetadata.extras?.getString(RECORD)?.takeIf { it.isNotEmpty() } ?: return false
        return listOf(player.previousMediaItemIndex, player.nextMediaItemIndex)
            .filter { it != C.INDEX_UNSET && it != player.currentMediaItemIndex }
            .any { player.getMediaItemAt(it).mediaMetadata.extras?.getString(RECORD) == record }
    }

    private class Gains(val track: Float?, val album: Float?)

    /** The playing file's own ReplayGain tags, as the player read them. */
    private fun tagged(tracks: Tracks): Gains {
        val said = HashMap<String, String>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
            for (i in 0 until group.length) {
                val md = group.getTrackFormat(i).metadata ?: continue
                for (j in 0 until md.length()) when (val e = md[j]) {
                    is VorbisComment -> said[e.key.uppercase()] = e.value
                    is TextInformationFrame -> if (e.id == "TXXX") e.description?.let { said[it.uppercase()] = e.values.firstOrNull().orEmpty() }
                    is InternalFrame -> said[e.description.uppercase()] = e.text
                }
            }
        }
        fun db(key: String) = said[key]?.trim()?.removeSuffix("dB")?.removeSuffix("db")?.trim()?.toFloatOrNull()
        // Opus: Q7.8 against -23 LUFS; ReplayGain's is -18, so 5 dB more
        fun r128(key: String) = said[key]?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
        return Gains(db("REPLAYGAIN_TRACK_GAIN") ?: r128("R128_TRACK_GAIN"), db("REPLAYGAIN_ALBUM_GAIN") ?: r128("R128_ALBUM_GAIN"))
    }

    companion object {
        /** A song with no gain anywhere: about where most of a library sits (ReplayGain's -18 LUFS, from today's masters). */
        const val UNKNOWN = -7f
        // what a queue item carries (its MediaMetadata's extras): the server's gains and its record
        const val TRACK_GAIN = "gain"
        const val ALBUM_GAIN = "albumGain"
        const val RECORD = "record"

        fun extras(s: Song) = Bundle().apply {
            s.gain?.let { putFloat(TRACK_GAIN, it) }
            s.albumGain?.let { putFloat(ALBUM_GAIN, it) }
            putString(RECORD, s.albumId)
        }
    }
}

/** An MP4's (M4A's) ReplayGain, from its iTunes atoms: moov > udta > meta > ilst > "----" (mean, name, data). */
object Mp4Gain {
    /** (track, album) gains in dB; null when the file isn't an MP4 or says none. */
    fun read(ch: java.nio.channels.FileChannel): Pair<Float?, Float?>? {
        val moov = find(ch, 0, ch.size(), "moov") ?: return null
        val udta = find(ch, moov.first, moov.second, "udta") ?: return null
        val meta = find(ch, udta.first, udta.second, "meta") ?: return null
        val ilst = find(ch, meta.first + 4, meta.second, "ilst") ?: return null   // meta has 4 bytes of version and flags
        val said = HashMap<String, String>()
        var at = ilst.first
        while (at + 8 <= ilst.second) {
            val (size, type) = header(ch, at) ?: break
            if (size < 8) break
            if (type == "----") {
                val name = find(ch, at + 8, at + size, "name")?.let { text(ch, it.first + 4, it.second) }
                val data = find(ch, at + 8, at + size, "data")?.let { text(ch, it.first + 8, it.second) }   // type and locale first
                if (name != null && data != null) said[name.uppercase()] = data
            }
            at += size
        }
        fun db(k: String) = said[k]?.trim()?.removeSuffix("dB")?.removeSuffix("db")?.trim()?.toFloatOrNull()
        return db("REPLAYGAIN_TRACK_GAIN") to db("REPLAYGAIN_ALBUM_GAIN")
    }

    /** The first box of `type` between `from` and `to`: where its insides start and end. */
    private fun find(ch: java.nio.channels.FileChannel, from: Long, to: Long, type: String): Pair<Long, Long>? {
        var at = from
        while (at + 8 <= to) {
            val (size, t) = header(ch, at) ?: return null
            if (size < 8) return null
            val inside = if (size == 1L) 16 else 8
            if (t == type) return at + inside to minOf(at + size, to)
            at += size
        }
        return null
    }

    /** A box's size (a 64-bit one read through) and type at `at`. */
    private fun header(ch: java.nio.channels.FileChannel, at: Long): Pair<Long, String>? {
        val b = java.nio.ByteBuffer.allocate(16)
        if (ch.read(b, at) < 8) return null
        b.flip()
        var size = b.int.toLong() and 0xffffffffL
        val type = String(ByteArray(4).also { b.get(it) }, Charsets.ISO_8859_1)
        if (size == 1L && b.remaining() >= 8) size = b.long
        else if (size == 0L) size = ch.size() - at
        return size to type
    }

    private fun text(ch: java.nio.channels.FileChannel, from: Long, to: Long): String? {
        val n = (to - from).toInt()
        if (n <= 0 || n > 1024) return null
        val b = java.nio.ByteBuffer.allocate(n)
        ch.read(b, from)
        return String(b.array(), Charsets.UTF_8)
    }
}
