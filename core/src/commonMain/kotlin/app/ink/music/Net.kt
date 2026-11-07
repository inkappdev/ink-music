package app.ink.music

/**
 * The network, shared (2026-11-07): one blocking request, its answer whatever its status (an error's body too), redirects
 * followed up to `follow` steps (0: the redirect itself answered). Java's HttpURLConnection on the phone and the computer,
 * as each call made it before; NSURLSession on the iPhone. Call it off the main thread.
 */
class Reply(val code: Int, val body: ByteArray, private val headers: Map<String, String>) {
    val text: String get() = body.decodeToString()
    /** A header (its name in any case). */
    fun header(name: String): String? = headers[name.lowercase()]
}

expect fun http(
    url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
    connectMs: Int = 10_000, readMs: Int = 20_000, follow: Int = 6,
): Reply

/** The network failed (or a service said try later): what's kept is tried again. */
expect open class IOException(message: String?) : Exception

/** A form's or a query's value, encoded as Java's URLEncoder does it (spaces as +). */
fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = (b.toInt() and 0xff).toChar()
        when {
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '*' || c == '_' -> append(c)
            c == ' ' -> append('+')
            else -> append('%').append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
        }
    }
}
private const val HEX = "0123456789ABCDEF"

/** A form's or a query's value decoded, as Java's URLDecoder does it (+ as a space, %XX as UTF-8 bytes). */
fun urlDecode(s: String): String {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' -> { out.add(' '.code.toByte()); i++ }
            c == '%' && i + 2 < s.length -> { out.add(s.substring(i + 1, i + 3).toInt(16).toByte()); i += 3 }
            else -> { c.toString().encodeToByteArray().forEach { out.add(it) }; i++ }
        }
    }
    return out.toByteArray().decodeToString()
}

/** Bytes as lower-case hex. */
fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

expect fun sha1(b: ByteArray): ByteArray
expect fun sha256(b: ByteArray): ByteArray
expect fun md5(b: ByteArray): ByteArray
/** Bytes no one can guess (the system's secure random). */
expect fun randomBytes(n: Int): ByteArray
