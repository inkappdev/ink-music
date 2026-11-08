package app.ink.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Each song's tempo and key, measured once and kept (2026-11-08: for the mixes, a genre's, a list's, your music, and
 * radio keeping a mix's tempo). No catalog has tempos worth asking (Deezer's said 0 for six songs in seven): thirty
 * seconds from a song's middle are decoded and read (Beat, Key): a file on the phone as it is, a server's or YouTube's
 * stream by range (`Ranged`, well under a megabyte). Kept in files/tempos.json, shared by the screens and the player
 * (radio measures there): read again before each save and merged, so neither's are lost.
 */
object Tempos {
    /**
     * A song's tempo, how sure of it and its key at its start (`bpm`, `sure`, `key`) and at its end (`out*`): a song
     * comes in on its start and goes out on its end, and many change between (an indie song's half-time outro read 77
     * where its start read 116, 2026-11-08). 0 bpm: it couldn't be read.
     */
    data class Read(val bpm: Double, val sure: Double, val key: Key.Found?,
                    val outBpm: Double = bpm, val outSure: Double = sure, val outKey: Key.Found? = key) {
        /** Sure enough of both ends to mix by (the lower bar: two near tempos are matched at it, Crossfade). */
        val usable get() = bpm > 0 && outBpm > 0 && sure >= LOOSE && outSure >= LOOSE
    }

    /** How sure a reading must be to mix by: Beat.SURE's bar, lowered where two tempos agree within 2%. */
    const val LOOSE = 2.8

    private val kept = HashMap<String, Read>()
    private var loadedAt = 0L

    /** What's kept for a song, if it's been read. */
    fun kept(c: Context, id: String): Read? { load(c); return synchronized(kept) { kept[id] } }

    /**
     * Read on wifi only (settings, sound, READING TEMPOS; 2026-11-08, Bronson: "maybe just a setting for data conscious
     * people"): on mobile data, only songs already read count. A file in filesDir, as both processes read it.
     */
    fun wifiOnly(c: Context) = File(c.filesDir, "tempos-wifi-only").exists()
    fun wifiOnly(c: Context, on: Boolean) { runCatching { File(c.filesDir, "tempos-wifi-only").let { if (on) it.writeText("on") else it.delete() } } }

    /** A song's tempo and key: kept, else read now (blocking, a second or two; null when it can't be reached, or not now). */
    fun of(c: Context, song: Song): Read? {
        kept(c, song.id)?.let { return it }
        // a file on the phone costs nothing to read; anything over the network waits for wifi if that's asked
        val onPhone = Offline.file(c, song.id) != null || song.uri.scheme == "content" || song.uri.scheme == "file"
        if (!onPhone && wifiOnly(c) && Quality.metered(c)) return null
        val r = runCatching { read(c, song) }.onFailure { android.util.Log.d("ink-picks", "tempo: ${song.artist} - ${song.title}: $it") }.getOrNull()
            ?: return null
        synchronized(kept) { kept[song.id] = r }
        return r
    }

    /** Within `near` of a tempo, it or its half or double (a DJ counts those the same). */
    fun fits(bpm: Double, want: Double, near: Double = .04) = bpm > 0 && listOf(bpm, bpm * 2, bpm / 2).any { kotlin.math.abs(it / want - 1) <= near }

    /** How far apart two tempos are, half and double counted: 0 the same. */
    fun apart(a: Double, b: Double) = listOf(a, a * 2, a / 2).minOf { kotlin.math.abs(it / b - 1) }

    /** A song's start and end, 25 s each, decoded to one channel, read for their tempo and key. */
    private fun read(c: Context, song: Song): Read? {
        // a server's song: its two stretches asked for where they begin, made small mp3s by the server (Subsonic's
        // timeOffset), not reached by range through a whole FLAC (eight songs a minute, 2026-11-08)
        if (song.server && song.duration > 60_000 && Offline.file(c, song.id) == null) return fromServer(c, song)
        val src: Any = when {
            Offline.file(c, song.id) != null -> Offline.file(c, song.id)!!
            song.tube -> YouTubeMusic.stream(song.id, false, small = true)?.first ?: return null
            song.uri.scheme == "content" || song.uri.scheme == "file" -> song.uri
            else -> song.uri.toString().takeIf { it.startsWith("http") } ?: return null
        }
        val ranged = (src as? String)?.let { Ranged(it) }
        fun open() = MediaExtractor().apply {
            when (src) { is String -> setDataSource(ranged!!); is File -> setDataSource(src.path); is android.net.Uri -> setDataSource(c, src, null) }
        }
        val x = open()
        val track: Int; val format: MediaFormat; val dur: Long
        try {
            track = (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            format = x.getTrackFormat(track)
            dur = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else song.duration
        } finally { x.release() }
        if (dur < 60_000) return Read(0.0, 0.0, null)
        val head = open().let { e -> try { decode(e, track, format, 2_000, 27_000) } finally { e.release() } }
        val tail = open().let { e -> try { decode(e, track, format, dur - 27_000, dur - 2_000) } finally { e.release() } }
        return both(head, 2_000, tail, dur - 27_000)
    }

    /** The two stretches read: each end's beat and key (an end that couldn't be read: the other's). */
    private fun both(head: Pair<FloatArray, Int>?, headAt: Long, tail: Pair<FloatArray, Int>?, tailAt: Long): Read? {
        if (head == null && tail == null) return null
        val hb = head?.let { Beat.find(it.first, it.second, headAt) }; val hk = head?.let { Key.find(it.first, it.second) }
        val tb = tail?.let { Beat.find(it.first, it.second, tailAt) }; val tk = tail?.let { Key.find(it.first, it.second) }
        val inB = hb ?: tb; val outB = tb ?: hb
        if (inB == null || outB == null) return Read(0.0, 0.0, null)
        return Read(inB.bpm, inB.sure, hk ?: tk, outB.bpm, outB.sure, tk ?: hk)
    }

    /** A server's song: its first and last 25 s, each a 128k mp3 from where it begins (~400 KB each), read. */
    private fun fromServer(c: Context, song: Song): Read? {
        fun clip(fromMs: Long): Pair<FloatArray, Int>? {
            val tmp = File(c.cacheDir, "tempo-${song.id.hashCode()}-$fromMs.mp3")
            try {
                val conn = java.net.URL(song.uri.toString() + "&format=mp3&maxBitRate=128&timeOffset=${fromMs / 1000}").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                try {
                    if (conn.responseCode != 200) return null
                    conn.inputStream.use { inp -> tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024); var left = 27 * 16_000   // 27 s at 128 kbps
                        while (left > 0) { val n = inp.read(buf, 0, minOf(buf.size, left)); if (n < 0) break; out.write(buf, 0, n); left -= n }
                    } }
                } finally { conn.disconnect() }
                val x = MediaExtractor()
                try {
                    x.setDataSource(tmp.path)
                    val track = (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
                    return decode(x, track, x.getTrackFormat(track), 0, 25_000)
                } finally { x.release() }
            } finally { tmp.delete() }
        }
        val dur = song.duration
        return both(clip(2_000), 2_000, clip(dur - 27_000), dur - 27_000)
    }

    /** A stretch decoded to one channel at the decoder's own rate (blocking, 20 s at most). */
    private fun decode(x: MediaExtractor, track: Int, format: MediaFormat, fromMs: Long, toMs: Long): Pair<FloatArray, Int>? {
        x.selectTrack(track)
        x.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0); codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = FloatArray(((toMs - fromMs + 2_000) * 48_000 / 1000).toInt())
            var n = 0; var acc = 0f; var k = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = android.os.SystemClock.uptimeMillis() + 20_000
            loop@ while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val got = x.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (got < 0 || x.sampleTime / 1000 > toMs) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, got, x.sampleTime, 0); x.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) codec.outputFormat.let { f ->
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                if (o >= 0) {
                    val b: ByteBuffer = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val float = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
                    if (info.presentationTimeUs / 1000 >= fromMs) {
                        fun put(v: Float) { acc += v; if (++k == ch) { if (n < out.size) out[n++] = acc / ch; acc = 0f; k = 0 } }
                        if (float) { val f = b.asFloatBuffer(); while (f.hasRemaining()) put(f.get()) } else { val s = b.asShortBuffer(); while (s.hasRemaining()) put(s.get() / 32768f) }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
            return if (n < rate * 10) null else out.copyOf(n) to rate
        } finally { runCatching { codec.stop() }; codec.release() }
    }

    private fun file(c: Context) = File(c.filesDir, "tempos.json")

    private fun parse(text: String): Map<String, Read> = runCatching {
        val o = JSONObject(text)
        // only both ends' readings (v 2): the first day's were a song's middle, read again
        o.keys().asSequence().mapNotNull { k ->
            o.optJSONObject(k)?.takeIf { it.optInt("v") == 2 }?.let { e ->
                fun key(p: String) = if (e.has(p + "pc")) Key.Found(e.getInt(p + "pc"), e.getBoolean(p + "minor"), e.getDouble(p + "ksure").toFloat()) else null
                k to Read(e.getDouble("bpm"), e.getDouble("sure"), key(""), e.optDouble("obpm", e.getDouble("bpm")), e.optDouble("osure", e.getDouble("sure")), key("o"))
            }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The kept file read (again when the other process may have added to it: every half minute at most). */
    private fun load(c: Context) {
        val now = System.currentTimeMillis()
        if (now - loadedAt < 30_000) return
        loadedAt = now
        val f = file(c)
        if (!f.exists()) return
        val read = parse(runCatching { f.readText() }.getOrDefault(""))
        synchronized(kept) { read.forEach { (k, v) -> kept.putIfAbsent(k, v) } }
    }

    /** Written, merged with what the other process wrote meanwhile; the newest 8000. */
    @Synchronized fun save(c: Context) {
        val f = file(c)
        val theirs = if (f.exists()) parse(runCatching { f.readText() }.getOrDefault("")) else emptyMap()
        val o = JSONObject()
        synchronized(kept) {
            theirs.forEach { (k, v) -> kept.putIfAbsent(k, v) }
            kept.entries.toList().takeLast(8000).forEach { (k, r) ->
                o.put(k, JSONObject().put("v", 2).put("bpm", r.bpm).put("sure", r.sure).put("obpm", r.outBpm).put("osure", r.outSure).apply {
                    r.key?.let { put("pc", it.pc).put("minor", it.minor).put("ksure", it.sure.toDouble()) }
                    r.outKey?.let { put("opc", it.pc).put("ominor", it.minor).put("oksure", it.sure.toDouble()) }
                })
            }
        }
        runCatching { Keep.write(f, o.toString()) }
    }
}
