@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.ink.music

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA256
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/** One request, waited for (core's calls are made off the main thread); NSURLSession follows redirects itself. */
actual fun http(url: String, method: String, headers: Map<String, String>, body: ByteArray?, connectMs: Int, readMs: Int, follow: Int): Reply {
    val u = NSURL.URLWithString(url) ?: throw IOException("not an address: $url")
    val req = NSMutableURLRequest.requestWithURL(u)
    req.setHTTPMethod(method)
    req.setTimeoutInterval((connectMs + readMs) / 1000.0)
    headers.forEach { (k, v) -> req.setValue(v, forHTTPHeaderField = k) }
    if (body != null) req.setHTTPBody(body.data())
    val done = dispatch_semaphore_create(0)
    var reply: Reply? = null
    var failed: String? = null
    NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
        if (error != null) failed = error.localizedDescription
        else {
            val r = response as? NSHTTPURLResponse
            val h = r?.allHeaderFields?.entries?.associate { (k, v) -> k.toString().lowercase() to v.toString() }.orEmpty()
            reply = Reply(r?.statusCode?.toInt() ?: 0, data?.toBytes() ?: ByteArray(0), h)
        }
        dispatch_semaphore_signal(done)
    }.resume()
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
    return reply ?: throw IOException(failed ?: "no answer from $url")
}

actual open class IOException actual constructor(message: String?) : Exception(message)

private fun digest(b: ByteArray, size: Int, f: (kotlinx.cinterop.CPointer<*>?, UInt, kotlinx.cinterop.CPointer<kotlinx.cinterop.UByteVar>) -> Unit): ByteArray {
    val out = UByteArray(size)
    b.usePinned { i -> out.usePinned { o -> f(if (b.isEmpty()) null else i.addressOf(0), b.size.toUInt(), o.addressOf(0)) } }
    return out.toByteArray()
}
actual fun sha1(b: ByteArray): ByteArray = digest(b, 20) { i, n, o -> CC_SHA1(i, n, o) }
actual fun sha256(b: ByteArray): ByteArray = digest(b, 32) { i, n, o -> CC_SHA256(i, n, o) }
actual fun md5(b: ByteArray): ByteArray = digest(b, 16) { i, n, o -> CC_MD5(i, n, o) }
actual fun randomBytes(n: Int): ByteArray = ByteArray(n).also { out -> out.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) } }
