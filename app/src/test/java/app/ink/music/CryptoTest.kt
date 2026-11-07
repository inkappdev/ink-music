package app.ink.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The iPhone's AES-GCM ([Gcm], plain Kotlin) the same as Java's, byte for byte; a pairing the same both ways. */
class CryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun nistVector() {
        // SP 800-38D's test case 15 (AES-256, 96-bit iv, no additional data)
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv = hex("cafebabefacedbaddecaf888")
        val p = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255")
        val want = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad" +
            "b094dac5d93471bdec1a502270e3cc6c"
        assertEquals(want, Gcm.seal(key, iv, p).hex())
        assertArrayEquals(p, Gcm.open(key, iv, hex(want)))
    }

    @Test fun sameAsJava() {
        val r = Random(7)
        for (n in listOf(0, 1, 15, 16, 17, 31, 32, 33, 100, 1000, 70_000)) for (keyLen in listOf(16, 32)) {
            val key = r.nextBytes(keyLen); val iv = r.nextBytes(12); val p = r.nextBytes(n)
            val java = gcmSeal(key, iv, p)
            assertArrayEquals("n=$n key=$keyLen", java, Gcm.seal(key, iv, p))
            assertArrayEquals(p, Gcm.open(key, iv, java))
        }
        val key = r.nextBytes(32); val iv = r.nextBytes(12)
        val sealed = gcmSeal(key, iv, "hello".encodeToByteArray()).also { it[2] = (it[2] + 1).toByte() }
        assertTrue(runCatching { Gcm.open(key, iv, sealed) }.isFailure)
    }

    @Test fun pairing() {
        val a = Handover.keys(); val b = Handover.keys()
        val bPub = Handover.decode(Handover.encode(b.publicKey()))
        assertEquals(Handover.kept(a, bPub), Handover.kept(b, Handover.decode(Handover.encode(a.publicKey()))))
        val (iv, data) = Handover.seal(a, bPub, "sign-in ✓")
        assertEquals("sign-in ✓", Handover.open(b, a.publicKey(), iv, data))
        assertEquals(7, Handover.code(bPub).length)
        val key = Handover.kept(a, bPub)
        val (iv2, d2) = Sync.seal(key, "heard")
        assertEquals("heard", Sync.open(key, iv2, d2))
    }
}
