package app.ink.music

import androidx.compose.ui.graphics.ImageBitmap

/** Covers read from the server and kept for the run (the newest 300). */
object Images {
    private val kept = LinkedHashMap<String, ImageBitmap>()
    // the one asked for moved to the newest end, so the oldest unasked go first
    fun cached(id: String?, size: Int): ImageBitmap? = id?.let { k -> kept.remove("$k@$size")?.also { kept["$k@$size"] = it } }
    suspend fun load(id: String, size: Int): ImageBitmap? {
        cached(id, size)?.let { return it }
        val url = Server.cover(id, size) ?: return null
        val img = runCatching { decodeImage(fetch(url)) }.getOrNull()?.let { if ("i.ytimg.com/vi/" in url) Here.off { inner(it) } else it } ?: return null
        kept["$id@$size"] = img
        while (kept.size > 300) kept.remove(kept.keys.first())
        return img
    }
    /**
     * A video's thumbnail (4:3, a 16:9 frame in it, a song's square art in the middle of that): the art alone, so a
     * song with no cover of its own shows its art, not the grey around it.
     */
    private fun inner(img: ImageBitmap): ImageBitmap {
        val side = minOf(img.height, img.width * 9 / 16)
        if (side <= 0 || (img.width == img.height)) return img
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        val x0 = (img.width - side) / 2; val y0 = (img.height - side) / 2
        return imageOf(IntArray(side * side) { k -> px[(y0 + k / side) * img.width + x0 + k % side] }, side)
    }

    /** The cover's file as it came (the lock screen's artwork). */
    suspend fun bytes(id: String, size: Int): ByteArray? = Server.cover(id, size)?.let { runCatching { fetch(it) }.getOrNull() }
}
