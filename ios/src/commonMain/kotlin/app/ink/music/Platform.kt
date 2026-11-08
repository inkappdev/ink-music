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

/** The app's own folder for its files (Application Support). */
expect fun appFolder(): String
/** A file shipped in the app (ink-radio.json). */
expect fun bundleText(name: String, ext: String): String?
/** Services of a type on this wifi (Bonjour), each as (its name, "host:port"); the returned function stops looking. */
expect fun browse(type: String, found: (String, String) -> Unit): () -> Unit
/** This iPhone's name, as it calls itself ("Bronson's iPhone"). */
expect fun deviceName(): String
/** A web page opened in the browser (ink, made by). */
expect fun openLink(url: String)
