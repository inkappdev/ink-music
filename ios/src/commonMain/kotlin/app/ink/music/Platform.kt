package app.ink.music

import androidx.compose.ui.graphics.ImageBitmap

/** What the iPhone does its own way: the network, keeping a few words, a sum, a picture, a font. */
expect suspend fun fetch(url: String): ByteArray
expect object Store {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}
expect fun md5(s: String): String
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
expect fun fontBytes(name: String): ByteArray
