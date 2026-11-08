package app.ink.music

/**
 * A stream read by range, for Android's reader (MediaDataSource): 256 KB at a time as it asks, kept while one song's
 * measured (its header, then the parts it seeks to: a few MB at most, never the whole of it). Its length from the
 * first answer's Content-Range.
 */
internal class Ranged(private val url: String) : android.media.MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 24
    }
    private var length = -1L
    private val BLOCK = 256 * 1024L

    private fun block(i: Long): ByteArray? {
        blocks[i]?.let { return it }
        val from = i * BLOCK
        if (length in 0..from) return null
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Range", "bytes=$from-${from + BLOCK - 1}")
        return try {
            if (c.responseCode !in listOf(200, 206)) return null
            c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { length = it }
                ?: if (c.responseCode == 200) c.contentLengthLong.takeIf { it > 0 }?.let { length = it } else Unit
            // a server that ignored the range sent it all: only this block kept
            val bytes = c.inputStream.use { inp ->
                if (c.responseCode == 200 && from > 0) { inp.skip(from); }
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024); var left = BLOCK
                while (left > 0) { val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
                out.toByteArray()
            }
            bytes.also { blocks[i] = it }
        } catch (e: java.io.IOException) { null } finally { c.disconnect() }
    }

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (length < 0) block(0)
        if (length in 0..position) return -1
        var done = 0
        while (done < size) {
            val p = position + done
            if (length in 0..p) break
            val b = block(p / BLOCK) ?: break
            val at = (p % BLOCK).toInt()
            if (at >= b.size) break
            val n = minOf(size - done, b.size - at)
            System.arraycopy(b, at, buffer, offset + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    @Synchronized override fun getSize(): Long { if (length < 0) block(0); return length }
    override fun close() {}
}
