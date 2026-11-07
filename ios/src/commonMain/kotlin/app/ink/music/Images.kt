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
        val img = runCatching { decodeImage(fetch(url)) }.getOrNull() ?: return null
        kept["$id@$size"] = img
        while (kept.size > 300) kept.remove(kept.keys.first())
        return img
    }
    /** The cover's file as it came (the lock screen's artwork). */
    suspend fun bytes(id: String, size: Int): ByteArray? = Server.cover(id, size)?.let { runCatching { fetch(it) }.getOrNull() }
}
