package app.ink.music

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your signature's choosing (SignatureMix, core): the dial's shares, a sitting steering it, and its rules. */
class SignatureTest {
    private fun found(lane: String, n: Int, through: String = "") =
        (1..n).map { SignatureMix.Found("$lane$it/$lane$it", "$lane artist $it", lane, 1.0 - it / 100.0, through) }
    private val all = found(SignatureMix.YOURS, 30) + found(SignatureMix.NEAR, 30) + found(SignatureMix.FAR, 30)
    private fun lanes(picked: List<SignatureMix.Found>) = picked.groupingBy { it.lane }.eachCount()

    @Test fun theDialSetsTheMix() {
        val sitting = SignatureMix.Sitting(emptyList())
        val yours = lanes(SignatureMix.pick(all, SignatureMix.Dials(SignatureMix.Familiar.YOURS), { .5 }, sitting, SignatureMix.Learned(), emptyList(), 20, random = Random(1)))
        val new = lanes(SignatureMix.pick(all, SignatureMix.Dials(SignatureMix.Familiar.NEW), { .5 }, sitting, SignatureMix.Learned(), emptyList(), 20, random = Random(1)))
        assertEquals(12, yours[SignatureMix.YOURS]); assertEquals(2, yours[SignatureMix.FAR])
        assertEquals(1, new[SignatureMix.YOURS]); assertEquals(12, new[SignatureMix.FAR])
    }

    @Test fun aLaneThatsDoneBadlyGetsLess() {
        var l = SignatureMix.Learned()
        repeat(10) { l = l.with(SignatureMix.FAR, false) }
        val picked = lanes(SignatureMix.pick(all, SignatureMix.Dials(SignatureMix.Familiar.BOTH), { .5 }, SignatureMix.Sitting(emptyList()), l, emptyList(), 20, random = Random(1)))
        assertTrue((picked[SignatureMix.FAR] ?: 0) <= 4)
    }

    @Test fun leftTwiceIsOutAndLovedComesForward() {
        val went = listOf(
            SignatureMix.Went("near artist 1", SignatureMix.NEAR, "", SignatureMix.How.LEFT, 1), SignatureMix.Went("near artist 1", SignatureMix.NEAR, "", SignatureMix.How.LEFT, 2),
            SignatureMix.Went("far artist 30", SignatureMix.FAR, "", SignatureMix.How.LOVED, 3),
        )
        val s = SignatureMix.Sitting(went)
        val picked = SignatureMix.pick(all, SignatureMix.Dials(SignatureMix.Familiar.BOTH), { .5 }, s, SignatureMix.Learned(), emptyList(), 20, random = Random(1))
        assertFalse(picked.any { it.artist == "near artist 1" })
        // the loved one's artist, though last in its lane by rank, comes first of its lane
        assertEquals("far artist 30", picked.first { it.lane == SignatureMix.FAR }.artist)
        assertEquals(listOf("far artist 30"), s.seeds(2))
    }

    @Test fun noArtistTwiceCloseAndNoneRefused() {
        val same = (1..10).map { SignatureMix.Found("s$it/x", "one artist", SignatureMix.NEAR, 1.0) } + found(SignatureMix.FAR, 10)
        val picked = SignatureMix.pick(same, SignatureMix.Dials(), { a -> if (a == "far artist 2") -1.0 else .5 }, SignatureMix.Sitting(emptyList()), SignatureMix.Learned(), emptyList(), 10, random = Random(1))
        // back only once eight others have played
        val at = picked.withIndex().filter { it.value.artist == "one artist" }.map { it.index }
        assertTrue(at.zipWithNext().all { (a, b) -> b - a > 8 })
        assertFalse(picked.any { it.artist == "far artist 2" })
    }

    @Test fun aMoodTakesAThird() {
        val withMood = all + found(SignatureMix.MOOD, 30)
        val picked = lanes(SignatureMix.pick(withMood, SignatureMix.Dials(SignatureMix.Familiar.BOTH, "after"), { .5 }, SignatureMix.Sitting(emptyList()), SignatureMix.Learned(), emptyList(), 30, random = Random(1)))
        assertEquals(10, picked[SignatureMix.MOOD])
    }

    @Test fun yoursLeavesOutWhatsJustBeenHeard() {
        val now = 100L * 86_400_000
        val heard = listOf(Triple("a/x", "x", now - 3_600_000), Triple("b/y", "y", now - 10L * 86_400_000), Triple("b/y", "y", now - 9L * 86_400_000))
        val r = SignatureMix.yours(heard, emptySet(), { .5 }, now)
        assertEquals(listOf("b/y"), r.map { it.first })
    }
}
