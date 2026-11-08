package app.ink.music

import org.json.JSONObject
import java.io.File

/**
 * Each song's tempo and key at its start and its end, read once and kept (the phone's Tempos, 2026-11-08): its first
 * and last 25 s decoded (Crossfade.decode, a third mpv: a file, a server's stream or YouTube's by its seeking) and read
 * (Beat, Key). Kept in ~/.ink-music/tempos.json, as the phone keeps its own.
 */
object Tempos {
    data class Read(val bpm: Double, val sure: Double, val key: Key.Found?, val outBpm: Double = bpm, val outSure: Double = sure, val outKey: Key.Found? = key) {
        val usable get() = bpm > 0 && outBpm > 0 && sure >= LOOSE && outSure >= LOOSE
    }
    const val LOOSE = 2.8

    private val kept = HashMap<String, Read>()
    private var loaded = false

    /** Read on wifi only: the computer's always on a line it doesn't pay by the megabyte, so there's no such setting here. */
    fun kept(id: String): Read? { load(); return synchronized(kept) { kept[id] } }

    /** A song's tempo and key: kept, else read now (blocking, a few seconds). */
    fun of(song: Song): Read? {
        kept(song.id)?.let { return it }
        val url = if (song.tube && Offline.file(song.id) == null) YouTube.stream(song.id, small = true) ?: return null else Player.where(song)
        if (url.isEmpty()) return null
        val head = Crossfade.decode(url, 2.0, 25.0)
        val tail = Crossfade.decode(url, -27.0, 25.0)
        val r = both(head?.first, 2_000, tail?.first, ((head?.second ?: tail?.second ?: song.duration) - 27_000).coerceAtLeast(0)) ?: return null
        synchronized(kept) { kept[song.id] = r }
        return r
    }

    fun fits(bpm: Double, want: Double, near: Double = .04) = bpm > 0 && listOf(bpm, bpm * 2, bpm / 2).any { kotlin.math.abs(it / want - 1) <= near }
    fun apart(a: Double, b: Double) = listOf(a, a * 2, a / 2).minOf { kotlin.math.abs(it / b - 1) }

    private fun both(head: FloatArray?, headAt: Long, tail: FloatArray?, tailAt: Long): Read? {
        if (head == null && tail == null) return null
        val r = Crossfade.RATE
        val hb = head?.let { Beat.find(it, r, headAt) }; val hk = head?.let { Key.find(it, r) }
        val tb = tail?.let { Beat.find(it, r, tailAt) }; val tk = tail?.let { Key.find(it, r) }
        val inB = hb ?: tb; val outB = tb ?: hb
        if (inB == null || outB == null) return Read(0.0, 0.0, null)
        return Read(inB.bpm, inB.sure, hk ?: tk, outB.bpm, outB.sure, tk ?: hk)
    }

    private val file get() = File(Home.dir, "tempos.json")

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file.readText())
            synchronized(kept) {
                for (k in o.keys()) o.optJSONObject(k)?.takeIf { it.optInt("v") == 2 }?.let { e ->
                    fun key(p: String) = if (e.has(p + "pc")) Key.Found(e.getInt(p + "pc"), e.getBoolean(p + "minor"), e.getDouble(p + "ksure").toFloat()) else null
                    kept[k] = Read(e.getDouble("bpm"), e.getDouble("sure"), key(""), e.optDouble("obpm", e.getDouble("bpm")), e.optDouble("osure", e.getDouble("sure")), key("o"))
                }
            }
        }
    }

    @Synchronized fun save() {
        val o = JSONObject()
        synchronized(kept) {
            kept.entries.toList().takeLast(8000).forEach { (k, r) ->
                o.put(k, JSONObject().put("v", 2).put("bpm", r.bpm).put("sure", r.sure).put("obpm", r.outBpm).put("osure", r.outSure).apply {
                    r.key?.let { put("pc", it.pc).put("minor", it.minor).put("ksure", it.sure.toDouble()) }
                    r.outKey?.let { put("opc", it.pc).put("ominor", it.minor).put("oksure", it.sure.toDouble()) }
                })
            }
        }
        runCatching { Keep.write(file, o.toString()) }
    }
}
