@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.ink.music

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.value

actual fun gcmSeal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray = Gcm.seal(key, iv, plain)
actual fun gcmOpen(key: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray = Gcm.open(key, iv, sealed)

/** A P-256 private key in the Security framework (kept for the app's run, never stored). */
actual class KeyPair(internal val private: SecKeyRef, internal val public: Point)
/** A P-256 public key: its point as X9.63 (04, x, y), Apple's own form. */
actual interface PublicKey { val point: ByteArray }
class Point(override val point: ByteArray) : PublicKey

/** X.509's wrapping of a P-256 point: what Java's `encoded` puts before it. */
private val SPKI_HEAD = "3059301306072a8648ce3d020106082a8648ce3d030107034200".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun attrs(public: Boolean): CFMutableDictionaryRef? {
    val d = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    CFDictionaryAddValue(d, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    val bits = memScoped { val n = alloc<IntVar>(); n.value = 256; CFNumberCreate(null, kCFNumberIntType, n.ptr) }
    CFDictionaryAddValue(d, kSecAttrKeySizeInBits, bits)
    CFRelease(bits)
    if (public) CFDictionaryAddValue(d, kSecAttrKeyClass, kSecAttrKeyClassPublic)
    return d
}

private fun CFDataRef.bytes(): ByteArray = CFDataGetBytePtr(this)!!.readBytes(CFDataGetLength(this).toInt())

private fun ByteArray.cfData(): CFDataRef = usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), size.convert())!! }

actual fun ecKeys(): KeyPair {
    val a = attrs(false)
    val private = SecKeyCreateRandomKey(a, null) ?: throw IOException("no key made")
    CFRelease(a)
    val pub = SecKeyCopyPublicKey(private)!!
    val data = SecKeyCopyExternalRepresentation(pub, null)!!
    val point = data.bytes()
    CFRelease(data); CFRelease(pub)
    return KeyPair(private, Point(point))
}

actual fun KeyPair.publicKey(): PublicKey = public
actual fun PublicKey.spki(): ByteArray = SPKI_HEAD + point
actual fun publicKey(spki: ByteArray): PublicKey {
    require(spki.size == SPKI_HEAD.size + 65 && spki[SPKI_HEAD.size].toInt() == 4) { "not a P-256 key" }
    return Point(spki.copyOfRange(SPKI_HEAD.size, spki.size))
}

actual fun agree(mine: KeyPair, theirs: PublicKey): ByteArray {
    val a = attrs(true)
    val data = theirs.point.cfData()
    val key = SecKeyCreateWithData(data, a, null)
    CFRelease(data); CFRelease(a)
    key ?: throw IOException("not a P-256 key")
    val params = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    val secret = SecKeyCopyKeyExchangeResult(mine.private, kSecKeyAlgorithmECDHKeyExchangeStandard, key, params, null)
    CFRelease(params); CFRelease(key)
    secret ?: throw IOException("no secret agreed")
    return secret.bytes().also { CFRelease(secret) }
}
