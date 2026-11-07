@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.ink.music

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreCrypto.CC_MD5
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataTaskWithURL
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { out ->
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
}

fun ByteArray.toNSData(): NSData = usePinned { NSData.dataWithBytes(if (isEmpty()) null else it.addressOf(0), size.convert()) }

actual suspend fun fetch(url: String): ByteArray = suspendCancellableCoroutine { cont ->
    val u = NSURL.URLWithString(url)
    if (u == null) { cont.resumeWithException(IllegalArgumentException("not an address")); return@suspendCancellableCoroutine }
    val task = NSURLSession.sharedSession.dataTaskWithURL(u) { data, response, error ->
        val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 200
        when {
            error != null -> cont.resumeWithException(Exception(error.localizedDescription))
            data == null -> cont.resumeWithException(Exception("nothing came back"))
            code >= 400 -> cont.resumeWithException(Exception("the server said $code"))
            else -> cont.resume(data.toByteArray())
        }
    }
    cont.invokeOnCancellation { task.cancel() }
    task.resume()
}

actual object Store {
    private val d get() = NSUserDefaults.standardUserDefaults
    actual fun get(key: String): String? = d.stringForKey(key)
    actual fun set(key: String, value: String?) { if (value == null) d.removeObjectForKey(key) else d.setObject(value, key) }
}

actual fun md5(s: String): String {
    val data = s.encodeToByteArray()
    val out = UByteArray(16)
    data.usePinned { d -> out.usePinned { o -> CC_MD5(if (data.isEmpty()) null else d.addressOf(0), data.size.convert(), o.addressOf(0)) } }
    return out.joinToString("") { it.toString(16).padStart(2, '0') }
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun fontBytes(name: String): ByteArray =
    NSBundle.mainBundle.pathForResource(name, "ttf")?.let { NSData.dataWithContentsOfFile(it)?.toByteArray() } ?: ByteArray(0)
