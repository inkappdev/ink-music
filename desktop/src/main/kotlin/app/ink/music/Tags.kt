package app.ink.music

import java.io.File
import java.io.RandomAccessFile

/**
 * Tags read by hand where jaudiotagger can't (2026-11-10, someone on Reddit: "it doesn't recognize the Opus and FLAC files
 * I have"): Opus it doesn't read at all, and a FLAC with an ID3 tag stuck in front it calls not a FLAC. Both keep
 * Vorbis comments (TITLE=, ARTIST=...): an Ogg file's in its second packet, a FLAC's in a metadata block after "fLaC".
 */
object Tags {
    class Read(val tags: Map<String, String>, val durationMs: Long, val rate: Int, val bits: Int, val picture: ByteArray?) {
        fun of(vararg keys: String) = keys.firstNotNullOfOrNull { tags[it]?.takeIf { v -> v.isNotBlank() } }?.trim().orEmpty()
    }

    fun read(f: File): Read? = runCatching {
        when (f.extension.lowercase()) {
            "opus", "ogg", "oga" -> ogg(f)
            "flac" -> flac(f)
            else -> null
        }
    }.getOrNull()

    /** The Vorbis comments in a packet, after its `skip` bytes: a vendor string, then KEY=value each, by length. */
    private fun comments(p: ByteArray, skip: Int): Pair<Map<String, String>, ByteArray?> {
        var i = skip
        fun u32(): Int { val v = (p[i].toInt() and 255) or ((p[i + 1].toInt() and 255) shl 8) or ((p[i + 2].toInt() and 255) shl 16) or ((p[i + 3].toInt() and 255) shl 24); i += 4; return v }
        val tags = LinkedHashMap<String, String>()
        var picture: ByteArray? = null
        val vendor = u32(); i += vendor   // the vendor
        val n = u32()
        repeat(n) {
            if (i + 4 > p.size) return tags to picture
            val len = u32()
            if (len < 0 || i + len > p.size) return tags to picture
            val s = String(p, i, len, Charsets.UTF_8); i += len
            val k = s.substringBefore('=').uppercase(); val v = s.substringAfter('=', "")
            if (k == "METADATA_BLOCK_PICTURE") { if (picture == null) picture = runCatching { pictureBlock(java.util.Base64.getDecoder().decode(v)) }.getOrNull() }
            else if (k !in tags) tags[k] = v else tags[k] = tags[k] + "; " + v
        }
        return tags to picture
    }

    /** A FLAC picture block (as in FLAC, and base64'd in Ogg): its type, mime, description, size, then the image. */
    private fun pictureBlock(b: ByteArray): ByteArray? {
        var i = 0
        fun u32(): Int { val v = ((b[i].toInt() and 255) shl 24) or ((b[i + 1].toInt() and 255) shl 16) or ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255); i += 4; return v }
        u32(); val mime = u32(); i += mime; val about = u32(); i += about; i += 16
        val len = u32()
        return if (len > 0 && i + len <= b.size) b.copyOfRange(i, i + len) else null
    }

    /** An Ogg file (Opus or Vorbis): its first two packets, and the last page's place for its length. */
    private fun ogg(f: File): Read? = RandomAccessFile(f, "r").use { r ->
        val packets = ArrayList<ByteArray>()
        val cur = java.io.ByteArrayOutputStream()
        val head = ByteArray(27)
        // pages till the second packet's whole (a big cover in it can run over many pages: 16 MB at most)
        while (packets.size < 2 && r.filePointer < r.length() && cur.size() < 16 shl 20) {
            r.readFully(head)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "OggS") return null
            val segs = ByteArray(head[26].toInt() and 255).also { r.readFully(it) }
            for (s in segs) {
                val n = s.toInt() and 255
                if (n > 0) { val b = ByteArray(n); r.readFully(b); cur.write(b) }
                if (n < 255) { packets += cur.toByteArray(); cur.reset(); if (packets.size == 2) break }
            }
        }
        if (packets.size < 2) return null
        val first = packets[0]; val second = packets[1]
        val opus = String(first, 0, 8, Charsets.ISO_8859_1) == "OpusHead"
        val vorbis = first.size > 7 && String(first, 1, 6, Charsets.ISO_8859_1) == "vorbis"
        if (!opus && !vorbis) return null
        fun le(b: ByteArray, at: Int, n: Int) = (0 until n).fold(0L) { a, k -> a or ((b[at + k].toLong() and 255) shl (8 * k)) }
        val rate = if (opus) 48_000 else le(first, 12, 4).toInt()
        val preSkip = if (opus) le(first, 10, 2) else 0L
        val (tags, picture) = comments(second, if (opus) 8 else 7)
        // the length: the last page's granule (samples at 48 kHz for Opus), found in the file's last 64 kB
        val tail = minOf(65_536L, r.length()).toInt()
        val end = ByteArray(tail).also { r.seek(r.length() - tail); r.readFully(it) }
        var at = -1
        for (k in tail - 27 downTo 0) if (end[k] == 'O'.code.toByte() && end[k + 1] == 'g'.code.toByte() && end[k + 2] == 'g'.code.toByte() && end[k + 3] == 'S'.code.toByte()) { at = k; break }
        val granule = if (at >= 0) le(end, at + 6, 8) else 0L
        val ms = if (granule > preSkip && rate > 0) (granule - preSkip) * 1000 / rate else 0L
        Read(tags, ms, if (opus) (le(first, 12, 4).toInt().takeIf { it > 0 } ?: 48_000) else rate, 0, picture)
    }

    /** A FLAC, an ID3 tag in front of it passed over: STREAMINFO's rate, depth and length, its comments and picture. */
    private fun flac(f: File): Read? = RandomAccessFile(f, "r").use { r ->
        val h = ByteArray(10).also { r.readFully(it) }
        var start = 0L
        if (String(h, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            val size = ((h[6].toInt() and 127) shl 21) or ((h[7].toInt() and 127) shl 14) or ((h[8].toInt() and 127) shl 7) or (h[9].toInt() and 127)
            start = 10L + size + (if (h[5].toInt() and 0x10 != 0) 10 else 0)
        }
        r.seek(start)
        val magic = ByteArray(4).also { r.readFully(it) }
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        var rate = 0; var bits = 0; var ms = 0L
        var tags: Map<String, String> = emptyMap(); var picture: ByteArray? = null
        while (true) {
            val bh = r.read(); if (bh < 0) break
            val len = (r.read() shl 16) or (r.read() shl 8) or r.read()
            val type = bh and 127
            when (type) {
                0 -> {
                    val b = ByteArray(len).also { r.readFully(it) }
                    rate = ((b[10].toInt() and 255) shl 12) or ((b[11].toInt() and 255) shl 4) or ((b[12].toInt() and 255) shr 4)
                    bits = (((b[12].toInt() and 1) shl 4) or ((b[13].toInt() and 255) shr 4)) + 1
                    val total = ((b[13].toLong() and 15) shl 32) or ((b[14].toLong() and 255) shl 24) or ((b[15].toLong() and 255) shl 16) or ((b[16].toLong() and 255) shl 8) or (b[17].toLong() and 255)
                    if (rate > 0) ms = total * 1000 / rate
                }
                4 -> tags = comments(ByteArray(len).also { r.readFully(it) }, 0).first
                6 -> { val b = ByteArray(len).also { r.readFully(it) }; if (picture == null) picture = runCatching { pictureBlock(b) }.getOrNull() }
                else -> r.seek(r.filePointer + len)
            }
            if (bh and 128 != 0) break
        }
        Read(tags, ms, rate, bits, picture)
    }
}
