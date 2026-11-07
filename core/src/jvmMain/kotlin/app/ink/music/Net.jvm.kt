package app.ink.music

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

actual fun http(url: String, method: String, headers: Map<String, String>, body: ByteArray?, connectMs: Int, readMs: Int, follow: Int): Reply {
    var at = URL(url)
    var left = follow
    while (true) {
        val c = at.openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = connectMs; c.readTimeout = readMs; c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            // followed by hand: Java's connection leaves some be (Stereogum's 308, http to https)
            if (code in 300..399 && left > 0) {
                val to = c.getHeaderField("Location") ?: error("redirect nowhere")
                at = URL(at, to); left--; continue
            }
            val bytes = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
            return Reply(code, bytes, h)
        } finally { c.disconnect() }
    }
}

actual typealias IOException = java.io.IOException

actual fun sha1(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(b)
actual fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
actual fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)
actual fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
