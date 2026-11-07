package app.ink.music

import kotlin.io.encoding.Base64

/**
 * The sealing a pair of devices does (2026-11-07, shared with the iPhone): AES-GCM under a key, and the P-256 key
 * agreement that makes one ([Handover]). Java's own on the phone and the computer (exactly as before); on the iPhone
 * Apple's Security framework agrees the key and [Gcm] below seals (CryptoKit is Swift's alone).
 */

/** `plain` sealed under `key` (16 or 32 bytes) with a 12-byte `iv`: the sealed bytes and their 16-byte tag after. */
expect fun gcmSeal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray

/** Sealed bytes opened; throws if the key's wrong or they were touched on the way. */
expect fun gcmOpen(key: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray

/** A P-256 key pair (java.security's on the phone and the computer). */
expect class KeyPair
/** A P-256 public key. */
expect interface PublicKey

expect fun ecKeys(): KeyPair
expect fun KeyPair.publicKey(): PublicKey
/** A public key as X.509 (SubjectPublicKeyInfo) bytes, Java's own `encoded`. */
expect fun PublicKey.spki(): ByteArray
expect fun publicKey(spki: ByteArray): PublicKey
/** The secret the two agree (ECDH): the shared point's x, 32 bytes. */
expect fun agree(mine: KeyPair, theirs: PublicKey): ByteArray

fun b64(b: ByteArray): String = Base64.Default.encode(b)
fun unb64(s: String): ByteArray = Base64.Default.decode(s)
fun b64url(b: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(b)

/**
 * AES-GCM in plain Kotlin (the iPhone's: Apple keeps GCM to CryptoKit). FIPS 197's AES and SP 800-38D's GCM, no
 * additional data; checked byte for byte against Java's Cipher (CryptoTest).
 */
object Gcm {
    fun seal(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val aes = Aes(key)
        val j0 = j0(iv)
        val c = ctr(aes, j0, plain)
        return c + tag(aes, j0, c)
    }

    fun open(key: ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= 16) { "too short" }
        val aes = Aes(key)
        val j0 = j0(iv)
        val c = sealed.copyOf(sealed.size - 16)
        val t = tag(aes, j0, c)
        var diff = 0
        for (i in 0 until 16) diff = diff or (t[i].toInt() xor sealed[sealed.size - 16 + i].toInt())
        if (diff != 0) throw IllegalStateException("tag mismatch")
        return ctr(aes, j0, c)
    }

    private fun j0(iv: ByteArray): ByteArray {
        require(iv.size == 12) { "iv must be 12 bytes" }
        return iv.copyOf(16).also { it[15] = 1 }
    }

    private fun inc(b: ByteArray) {
        for (i in 15 downTo 12) { b[i] = (b[i] + 1).toByte(); if (b[i].toInt() != 0) break }
    }

    private fun ctr(aes: Aes, j0: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        val counter = j0.copyOf()
        val block = ByteArray(16)
        var i = 0
        while (i < input.size) {
            inc(counter)
            aes.encrypt(counter, block)
            val n = minOf(16, input.size - i)
            for (k in 0 until n) out[i + k] = (input[i + k].toInt() xor block[k].toInt()).toByte()
            i += 16
        }
        return out
    }

    private fun tag(aes: Aes, j0: ByteArray, c: ByteArray): ByteArray {
        val h = ByteArray(16).also { aes.encrypt(ByteArray(16), it) }
        val hHi = long(h, 0); val hLo = long(h, 8)
        var yHi = 0L; var yLo = 0L
        var i = 0
        while (i < c.size) {
            val blk = ByteArray(16); c.copyInto(blk, 0, i, minOf(c.size, i + 16))
            yHi = yHi xor long(blk, 0); yLo = yLo xor long(blk, 8)
            mul(yHi, yLo, hHi, hLo).let { yHi = it.first; yLo = it.second }
            i += 16
        }
        // the lengths block: no additional data, then the sealed bytes' length in bits
        yLo = yLo xor (c.size.toLong() * 8)
        mul(yHi, yLo, hHi, hLo).let { yHi = it.first; yLo = it.second }
        val s = ByteArray(16).also { aes.encrypt(j0, it) }
        val out = ByteArray(16)
        for (k in 0 until 8) {
            out[k] = (s[k].toInt() xor (yHi ushr (56 - 8 * k)).toInt()).toByte()
            out[8 + k] = (s[8 + k].toInt() xor (yLo ushr (56 - 8 * k)).toInt()).toByte()
        }
        return out
    }

    private fun long(b: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
        return v
    }

    /** x times y in GCM's field (its bits reflected: the first bit the highest power's lowest). */
    private fun mul(xHi: Long, xLo: Long, yHi: Long, yLo: Long): Pair<Long, Long> {
        var zHi = 0L; var zLo = 0L
        var vHi = yHi; var vLo = yLo
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHi ushr (63 - i)) and 1L else (xLo ushr (127 - i)) and 1L
            if (bit == 1L) { zHi = zHi xor vHi; zLo = zLo xor vLo }
            val carry = vLo and 1L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = vHi ushr 1
            if (carry == 1L) vHi = vHi xor (0xE1L shl 56)
        }
        return zHi to zLo
    }

    /** AES's forward cipher, one block at a time. */
    private class Aes(key: ByteArray) {
        private val rounds = when (key.size) { 16 -> 10; 24 -> 12; 32 -> 14; else -> error("AES key must be 16, 24 or 32 bytes") }
        private val w = IntArray(4 * (rounds + 1))

        init {
            val nk = key.size / 4
            for (i in 0 until nk) w[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((key[4 * i + 2].toInt() and 0xFF) shl 8) or (key[4 * i + 3].toInt() and 0xFF)
            var rcon = 1
            for (i in nk until w.size) {
                var t = w[i - 1]
                if (i % nk == 0) {
                    t = sub((t shl 8) or (t ushr 24)) xor (rcon shl 24)
                    rcon = xt(rcon)
                } else if (nk > 6 && i % nk == 4) t = sub(t)
                w[i] = w[i - nk] xor t
            }
        }

        private fun sub(x: Int) = (S[(x ushr 24) and 0xFF] shl 24) or (S[(x ushr 16) and 0xFF] shl 16) or (S[(x ushr 8) and 0xFF] shl 8) or S[x and 0xFF]

        fun encrypt(input: ByteArray, out: ByteArray) {
            val s = IntArray(16) { input[it].toInt() and 0xFF }
            addKey(s, 0)
            for (r in 1 until rounds) { subShift(s); mix(s); addKey(s, r) }
            subShift(s); addKey(s, rounds)
            for (i in 0 until 16) out[i] = s[i].toByte()
        }

        private fun addKey(s: IntArray, r: Int) {
            for (c in 0 until 4) {
                val k = w[4 * r + c]
                s[4 * c] = s[4 * c] xor ((k ushr 24) and 0xFF); s[4 * c + 1] = s[4 * c + 1] xor ((k ushr 16) and 0xFF)
                s[4 * c + 2] = s[4 * c + 2] xor ((k ushr 8) and 0xFF); s[4 * c + 3] = s[4 * c + 3] xor (k and 0xFF)
            }
        }

        /** SubBytes and ShiftRows (the state column by column: byte r of column c at 4c + r). */
        private fun subShift(s: IntArray) {
            val t = IntArray(16)
            for (c in 0 until 4) for (r in 0 until 4) t[4 * c + r] = S[s[4 * ((c + r) % 4) + r]]
            t.copyInto(s)
        }

        private fun mix(s: IntArray) {
            for (c in 0 until 4) {
                val a0 = s[4 * c]; val a1 = s[4 * c + 1]; val a2 = s[4 * c + 2]; val a3 = s[4 * c + 3]
                s[4 * c] = xt(a0) xor (xt(a1) xor a1) xor a2 xor a3
                s[4 * c + 1] = a0 xor xt(a1) xor (xt(a2) xor a2) xor a3
                s[4 * c + 2] = a0 xor a1 xor xt(a2) xor (xt(a3) xor a3)
                s[4 * c + 3] = (xt(a0) xor a0) xor a1 xor a2 xor xt(a3)
            }
        }

        companion object {
            fun xt(x: Int) = ((x shl 1) xor (if (x and 0x80 != 0) 0x1B else 0)) and 0xFF

            /** The S-box, worked out once from its definition (the inverse in GF(2^8), then the affine map). */
            val S = IntArray(256).also { s ->
                var p = 1; var q = 1
                do {
                    p = p xor xt(p)                       // p times 3
                    q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xFF
                    if (q and 0x80 != 0) q = q xor 0x09    // q divided by 3
                    val x = q xor rotl(q, 1) xor rotl(q, 2) xor rotl(q, 3) xor rotl(q, 4)
                    s[p] = (x xor 0x63) and 0xFF
                } while (p != 1)
                s[0] = 0x63
            }

            private fun rotl(x: Int, n: Int) = ((x shl n) or (x ushr (8 - n))) and 0xFF
        }
    }
}
