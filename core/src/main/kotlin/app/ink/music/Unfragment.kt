package app.ink.music

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * A fragmented MP4 (YouTube's audio: an empty sample table, then a fragment every couple of seconds, each saying its
 * own samples) put back as a plain one: one sample table for the whole song and its audio in one piece after it, as a
 * file off a record shop's site is. A Denon given YouTube's as it comes couldn't seek in it, and said "transitioning"
 * all through it (Bronson, 2026-10-30/31). The samples are copied as they are: nothing re-encoded. The phone has
 * Android's muxer for this; the computer hasn't, so it's done here by hand (ISO/IEC 14496-12: moof, traf, tfhd, trun).
 */
object Unfragment {
    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    private fun boxes(d: ByteArray, from: Int, to: Int): List<Box> {
        val b = ByteBuffer.wrap(d)
        val out = ArrayList<Box>()
        var o = from
        while (o + 8 <= to) {
            var size = b.getInt(o).toLong() and 0xffffffffL
            val type = String(d, o + 4, 4, Charsets.ISO_8859_1)
            var head = 8
            if (size == 1L) { size = b.getLong(o + 8); head = 16 }
            if (size == 0L) size = (to - o).toLong()
            if (size < head || o + size > to) break
            out += Box(type, o, o + head, (o + size).toInt())
            o += size.toInt()
        }
        return out
    }

    private fun List<Box>.of(type: String) = firstOrNull { it.type == type }

    /** `mp4` unfragmented, or null when it isn't a fragmented MP4 with one track. */
    fun plain(d: ByteArray): ByteArray? {
        val b = ByteBuffer.wrap(d)
        val top = boxes(d, 0, d.size)
        val moov = top.of("moov") ?: return null
        val inMoov = boxes(d, moov.body, moov.end)
        val trak = inMoov.filter { it.type == "trak" }.singleOrNull() ?: return null
        if (inMoov.of("mvex") == null) return null
        val trex = boxes(d, inMoov.of("mvex")!!.body, inMoov.of("mvex")!!.end).of("trex")
        // what a fragment's samples are when it doesn't say
        var trexDuration = 0; var trexSize = 0
        if (trex != null) { trexDuration = b.getInt(trex.body + 12); trexSize = b.getInt(trex.body + 16) }

        val durations = IntArrayList(); val sizes = IntArrayList(); val chunkCounts = IntArrayList()
        val audio = ByteArrayOutputStream(d.size)
        for (moof in top.filter { it.type == "moof" }) {
            for (traf in boxes(d, moof.body, moof.end).filter { it.type == "traf" }) {
                val parts = boxes(d, traf.body, traf.end)
                val tfhd = parts.of("tfhd") ?: continue
                val tf = b.getInt(tfhd.body) and 0xffffff
                var p = tfhd.body + 8
                var base = moof.start.toLong()
                if (tf and 0x1 != 0) { base = b.getLong(p); p += 8 }
                if (tf and 0x2 != 0) p += 4
                var defDuration = trexDuration; var defSize = trexSize
                if (tf and 0x8 != 0) { defDuration = b.getInt(p); p += 4 }
                if (tf and 0x10 != 0) { defSize = b.getInt(p); p += 4 }
                for (trun in parts.filter { it.type == "trun" }) {
                    val rf = b.getInt(trun.body) and 0xffffff
                    val count = b.getInt(trun.body + 4)
                    var q = trun.body + 8
                    var at = base
                    if (rf and 0x1 != 0) { at = base + b.getInt(q); q += 4 }
                    if (rf and 0x4 != 0) q += 4
                    for (i in 0 until count) {
                        var dur = defDuration; var size = defSize
                        if (rf and 0x100 != 0) { dur = b.getInt(q); q += 4 }
                        if (rf and 0x200 != 0) { size = b.getInt(q); q += 4 }
                        if (rf and 0x400 != 0) q += 4
                        if (rf and 0x800 != 0) q += 4   // composition offsets: none in audio
                        if (at + size > d.size) return null
                        audio.write(d, at.toInt(), size)
                        at += size
                        durations.add(dur); sizes.add(size)
                    }
                    if (count > 0) chunkCounts.add(count)
                }
            }
        }
        if (sizes.size == 0) return null
        val total = durations.sum()

        // the track as it was, its table filled in and its lengths said
        val mvhd = inMoov.of("mvhd") ?: return null
        val movieScale = b.getInt(mvhd.body + if (d[mvhd.body].toInt() == 1) 20 else 12).toLong()
        val inTrak = boxes(d, trak.body, trak.end)
        val mdia = inTrak.of("mdia") ?: return null
        val inMdia = boxes(d, mdia.body, mdia.end)
        val mdhd = inMdia.of("mdhd") ?: return null
        val mediaScale = b.getInt(mdhd.body + if (d[mdhd.body].toInt() == 1) 20 else 12).toLong()
        val movieLength = if (mediaScale > 0) total * movieScale / mediaScale else total
        val minf = inMdia.of("minf") ?: return null
        val inMinf = boxes(d, minf.body, minf.end)
        val stbl = inMinf.of("stbl") ?: return null
        val stsd = boxes(d, stbl.body, stbl.end).of("stsd") ?: return null

        fun build(audioAt: Long): ByteArray {
            val newStbl = box("stbl", copy(d, stsd), stts(durations), stsc(chunkCounts), stsz(sizes), stco(chunkCounts, sizes, audioAt))
            val newMinf = box("minf", *inMinf.map { if (it === stbl) newStbl else copy(d, it) }.toTypedArray())
            val newMdia = box("mdia", *inMdia.map {
                when { it === mdhd -> withLength(d, it, 16, 24, total); it === minf -> newMinf; else -> copy(d, it) }
            }.toTypedArray())
            val newTrak = box("trak", *inTrak.map {
                when { it.type == "tkhd" -> withLength(d, it, 20, 28, movieLength); it === mdia -> newMdia; else -> copy(d, it) }
            }.toTypedArray())
            return box("moov", *inMoov.filter { it.type != "mvex" }.map {
                when { it === mvhd -> withLength(d, it, 16, 24, movieLength); it === trak -> newTrak; else -> copy(d, it) }
            }.toTypedArray())
        }
        val ftyp = box("ftyp", "M4A ".toByteArray(), int(0), "M4A mp42isom".toByteArray())
        // the table says where the audio starts, after the table itself: its size doesn't change with the number in it
        val moovSize = build(0).size
        val audioAt = ftyp.size.toLong() + moovSize + 8
        val audioBytes = audio.toByteArray()
        val out = ByteArrayOutputStream(ftyp.size + moovSize + 8 + audioBytes.size)
        out.write(ftyp); out.write(build(audioAt)); out.write(int(8 + audioBytes.size)); out.write("mdat".toByteArray()); out.write(audioBytes)
        return out.toByteArray()
    }

    private class IntArrayList {
        var a = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
        fun sum(): Long { var s = 0L; for (i in 0 until size) s += a[i].toLong() and 0xffffffffL; return s }
    }

    private fun int(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
    private fun copy(d: ByteArray, x: Box) = d.copyOfRange(x.start, x.end)
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(int(8 + parts.sumOf { it.size })); o.write(type.toByteArray(Charsets.ISO_8859_1)); parts.forEach { o.write(it) }
        return o.toByteArray()
    }
    private fun full(type: String, body: ByteBuffer) = box(type, int(0), body.array())

    /** A header box with its duration set: at `v0` from the body when it's version 0 (32 bits), `v1` when 1 (64). */
    private fun withLength(d: ByteArray, x: Box, v0: Int, v1: Int, length: Long): ByteArray {
        val c = copy(d, x)
        val b = ByteBuffer.wrap(c)
        val head = x.body - x.start
        if (d[x.body].toInt() == 1) b.putLong(head + v1, length)
        else b.putInt(head + v0, length.coerceAtMost(0xffffffffL).toInt())
        return c
    }

    private fun stts(durations: IntArrayList): ByteArray {
        val runs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until durations.size) {
            val v = durations[i]
            if (runs.isNotEmpty() && runs.last().second == v) runs[runs.size - 1] = runs.last().first + 1 to v else runs += 1 to v
        }
        val b = ByteBuffer.allocate(4 + runs.size * 8).putInt(runs.size)
        runs.forEach { (n, v) -> b.putInt(n).putInt(v) }
        return full("stts", b)
    }

    /** A chunk a fragment: how many samples in each (runs of the same count said once). */
    private fun stsc(counts: IntArrayList): ByteArray {
        val runs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until counts.size) if (runs.isEmpty() || runs.last().second != counts[i]) runs += (i + 1) to counts[i]
        val b = ByteBuffer.allocate(4 + runs.size * 12).putInt(runs.size)
        runs.forEach { (first, n) -> b.putInt(first).putInt(n).putInt(1) }
        return full("stsc", b)
    }

    private fun stsz(sizes: IntArrayList): ByteArray {
        val b = ByteBuffer.allocate(8 + sizes.size * 4).putInt(0).putInt(sizes.size)
        for (i in 0 until sizes.size) b.putInt(sizes[i])
        return full("stsz", b)
    }

    private fun stco(counts: IntArrayList, sizes: IntArrayList, audioAt: Long): ByteArray {
        val b = ByteBuffer.allocate(4 + counts.size * 4).putInt(counts.size)
        var at = audioAt; var s = 0
        for (c in 0 until counts.size) {
            b.putInt(at.toInt())
            repeat(counts[c]) { at += sizes[s++].toLong() }
        }
        return full("stco", b)
    }
}
