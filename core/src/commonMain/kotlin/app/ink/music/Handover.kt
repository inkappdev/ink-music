package app.ink.music

/**
 * Your YouTube Music sign-in handed from the phone to a computer on the same network (the computer has no browser of
 * its own to sign in with, and Google turns embedded ones away). The computer makes a key pair and shows a six-digit
 * code made from its public key; the phone, given the key, makes the same code from it and asks for the one on the
 * computer's screen: if they match, it's that computer. Then the phone makes its own pair, and the two agree a
 * secret (ECDH, P-256) no one listening on the network can work out; the sign-in goes sealed under it (AES-GCM).
 * Shared: the phone, the computer and the iPhone. Announced as `_inkmusic._tcp` (mDNS); `GET /hello` gives the key,
 * `POST /hand` takes {key, iv, data}.
 */
object Handover {
    const val SERVICE = "_inkmusic._tcp"

    fun keys(): KeyPair = ecKeys()

    fun encode(k: PublicKey): String = b64(k.spki())
    fun decode(s: String): PublicKey = publicKey(unb64(s))

    /** The six digits shown on the computer, from its public key ("123 456"). */
    fun code(key: PublicKey): String {
        val h = sha256(key.spki())
        val n = ((h[0].toLong() and 0xFF) shl 24 or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)) % 1_000_000
        return n.toString().padStart(6, '0').let { it.take(3) + " " + it.drop(3) }
    }

    /**
     * What the computer's QR code says (2026-11-12, after a laptop the phone couldn't find nor reach by the address
     * shown): the code and the port, and every address the computer has on a network, its likeliest first, so the phone
     * tries each and none is typed. A link to inkhome.app, so a camera that isn't ink music's lands somewhere ink's
     * (`#` keeps it off the site's server): "https://inkhome.app/#pair/123456/52114/192.168.1.20,172.24.16.1".
     */
    data class Link(val code: String, val port: Int, val hosts: List<String>)

    /**
     * The QR code's link. inkhome.app/pair opens ink music on a phone that has it (an App Link: the phone's own camera
     * or Lens, not only ink music's), the site's page where it hasn't; what's after # never leaves the phone.
     */
    fun link(code: String, port: Int, hosts: List<String>): String =
        "https://inkhome.app/pair#${code.filter(Char::isDigit)}/$port/${hosts.joinToString(",")}"

    /** A scanned link read back; null when it isn't one. Also the first form, inkhome.app/#pair/... */
    fun read(text: String): Link? {
        val t = text.trim()
        val parts = (if ("/pair#" in t) t.substringAfter("/pair#") else t.substringAfter("#pair/", "")).split("/")
        val code = parts.getOrNull(0)?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
        val port = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val hosts = parts.getOrNull(2).orEmpty().split(",").filter { h -> h.isNotEmpty() && h.all { it.isDigit() || it == '.' } }
        return if (hosts.isEmpty()) null else Link(code, port, hosts)
    }

    /** The two keys' shared secret as an AES key. */
    private fun secret(mine: KeyPair, theirs: PublicKey): ByteArray = sha256(agree(mine, theirs) + "ink music handover".encodeToByteArray())

    /**
     * A key the two keep after pairing ([Sync]): the same secret the pair agreed, under its own label, base64. Both work
     * it out; it never crosses the network.
     */
    fun kept(mine: KeyPair, theirs: PublicKey): String = b64(sha256(agree(mine, theirs) + "ink music sync".encodeToByteArray()))

    /** Sealed for `theirs`: (iv, data), both base64. */
    fun seal(mine: KeyPair, theirs: PublicKey, text: String): Pair<String, String> {
        val iv = randomBytes(12)
        return b64(iv) to b64(gcmSeal(secret(mine, theirs), iv, text.encodeToByteArray()))
    }

    /** Opened from `theirs`; throws if it was sealed for someone else or touched on the way. */
    fun open(mine: KeyPair, theirs: PublicKey, iv: String, data: String): String =
        gcmOpen(secret(mine, theirs), unb64(iv), unb64(data)).decodeToString()
}
