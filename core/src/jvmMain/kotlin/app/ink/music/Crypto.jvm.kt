package app.ink.music

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun gcmSeal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }.doFinal(plain)

actual fun gcmOpen(key: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }.doFinal(sealed)

actual typealias KeyPair = java.security.KeyPair
actual typealias PublicKey = java.security.PublicKey

actual fun ecKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
actual fun KeyPair.publicKey(): PublicKey = public
actual fun PublicKey.spki(): ByteArray = encoded
actual fun publicKey(spki: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
actual fun agree(mine: KeyPair, theirs: PublicKey): ByteArray =
    KeyAgreement.getInstance("ECDH").apply { init(mine.private); doPhase(theirs, true) }.generateSecret()

actual val JSON_NULL: Any = org.json.JSONObject.NULL

actual class Signal actual constructor() {
    private val lock = Object()
    actual fun <T> run(f: () -> T): T = synchronized(lock) { f() }
    actual fun await(ms: Long) { (lock as Object).wait(maxOf(1L, ms)) }
    actual fun wake() { (lock as Object).notifyAll() }
}
