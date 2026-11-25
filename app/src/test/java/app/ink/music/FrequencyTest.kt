package app.ink.music

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your frequency's choosing (FrequencyMix, core): the dial's shares, a sitting steering it, and its rules. */
class FrequencyTest {
    private fun found(lane: String, n: Int, through: String = "") =
        (1..n).map { FrequencyMix.Found("$lane$it/$lane$it", "$lane artist $it", lane, 1.0 - it / 100.0, through) }
    private val all = found(FrequencyMix.YOURS, 30) + found(FrequencyMix.NEAR, 30) + found(FrequencyMix.FAR, 30)
    private fun lanes(picked: List<FrequencyMix.Found>) = picked.groupingBy { it.lane }.eachCount()

    @Test fun theDialSetsTheMix() {
        val sitting = FrequencyMix.Sitting(emptyList())
        val yours = lanes(FrequencyMix.pick(all, FrequencyMix.Dials(0.0), { .5 }, sitting, FrequencyMix.Learned(), emptyList(), 20, random = Random(1)))
        val new = lanes(FrequencyMix.pick(all, FrequencyMix.Dials(1.0), { .5 }, sitting, FrequencyMix.Learned(), emptyList(), 20, random = Random(1)))
        assertEquals(14, yours[FrequencyMix.YOURS]); assertEquals(1, yours[FrequencyMix.FAR])
        assertEquals(null, new[FrequencyMix.YOURS]); assertEquals(16, new[FrequencyMix.FAR])
    }

    @Test fun aLaneThatsDoneBadlyGetsLess() {
        var l = FrequencyMix.Learned()
        repeat(10) { l = l.with(FrequencyMix.FAR, false) }
        val picked = lanes(FrequencyMix.pick(all, FrequencyMix.Dials(.5), { .5 }, FrequencyMix.Sitting(emptyList()), l, emptyList(), 20, random = Random(1)))
        assertTrue((picked[FrequencyMix.FAR] ?: 0) <= 4)
    }

    @Test fun leftTwiceIsOutAndLovedComesForward() {
        val went = listOf(
            FrequencyMix.Went("near artist 1", FrequencyMix.NEAR, "", FrequencyMix.How.LEFT, 1), FrequencyMix.Went("near artist 1", FrequencyMix.NEAR, "", FrequencyMix.How.LEFT, 2),
            FrequencyMix.Went("far artist 30", FrequencyMix.FAR, "", FrequencyMix.How.LOVED, 3),
        )
        val s = FrequencyMix.Sitting(went)
        val picked = FrequencyMix.pick(all, FrequencyMix.Dials(.5), { .5 }, s, FrequencyMix.Learned(), emptyList(), 20, random = Random(1))
        assertFalse(picked.any { it.artist == "near artist 1" })
        // the loved one's artist, though last in its lane by rank, comes first of its lane
        assertEquals("far artist 30", picked.first { it.lane == FrequencyMix.FAR }.artist)
        assertEquals(listOf("far artist 30"), s.seeds(2))
    }

    @Test fun noArtistTwiceCloseAndNoneRefused() {
        val same = (1..10).map { FrequencyMix.Found("s$it/x", "one artist", FrequencyMix.NEAR, 1.0) } + found(FrequencyMix.FAR, 10)
        val picked = FrequencyMix.pick(same, FrequencyMix.Dials(), { a -> if (a == "far artist 2") -1.0 else .5 }, FrequencyMix.Sitting(emptyList()), FrequencyMix.Learned(), emptyList(), 10, random = Random(1))
        // back only once eight others have played
        val at = picked.withIndex().filter { it.value.artist == "one artist" }.map { it.index }
        assertTrue(at.zipWithNext().all { (a, b) -> b - a > 8 })
        assertFalse(picked.any { it.artist == "far artist 2" })
    }

    @Test fun aMoodTakesAThird() {
        val withMood = all + found(FrequencyMix.MOOD, 30)
        val picked = lanes(FrequencyMix.pick(withMood, FrequencyMix.Dials(.5, "after"), { .5 }, FrequencyMix.Sitting(emptyList()), FrequencyMix.Learned(), emptyList(), 30, random = Random(1)))
        assertEquals(10, picked[FrequencyMix.MOOD])
    }

    @Test fun yoursLeavesOutWhatsJustBeenHeard() {
        val now = 100L * 86_400_000
        val heard = listOf(Triple("a/x", "x", now - 3_600_000), Triple("b/y", "y", now - 10L * 86_400_000), Triple("b/y", "y", now - 9L * 86_400_000))
        val r = FrequencyMix.yours(heard, emptySet(), { .5 }, now)
        assertEquals(listOf("b/y"), r.map { it.first })
    }

    @Test fun aNewBatchCarriesOnTheSittingsShares() {
        // a sitting that's had one of yours already: the next batch's first isn't yours again (each batch began with yours)
        val first = FrequencyMix.pick(all, FrequencyMix.Dials(.5), { .5 }, FrequencyMix.Sitting(emptyList()), FrequencyMix.Learned(), emptyList(), 1,
            random = Random(1), given0 = mapOf(FrequencyMix.YOURS to 1)).single()
        assertTrue(first.lane != FrequencyMix.YOURS)
        // all new: none of yours at all
        val allNew = FrequencyMix.pick(all, FrequencyMix.Dials(1.0), { .5 }, FrequencyMix.Sitting(emptyList()), FrequencyMix.Learned(), emptyList(), 30, random = Random(1))
        assertTrue(allNew.none { it.lane == FrequencyMix.YOURS })
    }
}
