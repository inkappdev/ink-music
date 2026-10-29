package app.ink.music

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Your YouTube Music sign-in handed from the phone to a computer on the same network (the computer has no browser of
 * its own to sign in with, and Google turns embedded ones away). The computer makes a key pair and shows a six-digit
 * code made from its public key; the phone, given the key, makes the same code from it and asks for the one on the
 * computer's screen: if they match, it's that computer. Then the phone makes its own pair, and the two agree a
 * secret (ECDH, P-256) no one listening on the network can work out; the sign-in goes sealed under it (AES-GCM).
 * Plain Java, so the phone and the desktop share it. Announced as `_inkmusic._tcp` (mDNS); `GET /hello` gives the key,
 * `POST /hand` takes {key, iv, data}.
 */
object Handover {
    const val SERVICE = "_inkmusic._tcp"

    fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun encode(k: PublicKey): String = Base64.getEncoder().encodeToString(k.encoded)
    fun decode(s: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(s)))

    /** The six digits shown on the computer, from its public key ("123 456"). */
    fun code(key: PublicKey): String {
        val h = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val n = ((h[0].toLong() and 0xFF) shl 24 or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)) % 1_000_000
        return "%06d".format(n).let { it.take(3) + " " + it.drop(3) }
    }

    /** The two keys' shared secret as an AES key. */
    private fun secret(mine: KeyPair, theirs: PublicKey): SecretKeySpec {
        val raw = KeyAgreement.getInstance("ECDH").apply { init(mine.private); doPhase(theirs, true) }.generateSecret()
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(raw + "ink music handover".toByteArray()), "AES")
    }

    /**
     * A key the two keep after pairing ([Sync]): the same secret the pair agreed, under its own label, base64. Both work
     * it out; it never crosses the network.
     */
    fun kept(mine: KeyPair, theirs: PublicKey): String {
        val raw = KeyAgreement.getInstance("ECDH").apply { init(mine.private); doPhase(theirs, true) }.generateSecret()
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw + "ink music sync".toByteArray()))
    }

    /** Sealed for `theirs`: (iv, data), both base64. */
    fun seal(mine: KeyPair, theirs: PublicKey, text: String): Pair<String, String> {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret(mine, theirs), GCMParameterSpec(128, iv)) }
        val b = Base64.getEncoder()
        return b.encodeToString(iv) to b.encodeToString(c.doFinal(text.toByteArray()))
    }

    /** Opened from `theirs`; throws if it was sealed for someone else or touched on the way. */
    fun open(mine: KeyPair, theirs: PublicKey, iv: String, data: String): String {
        val d = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(mine, theirs), GCMParameterSpec(128, d.decode(iv))) }
        return String(c.doFinal(d.decode(data)))
    }
}
