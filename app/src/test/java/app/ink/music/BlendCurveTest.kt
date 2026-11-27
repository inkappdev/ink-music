package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automix's gains through a blend, and the vocal check read through them. */
class BlendCurveTest {
    private fun g(k: Double, matched: Boolean = false, cold: Boolean = false) = Blend.gains(k, 0.0, matched, cold)

    @Test fun equalPowerKeepsPower() = listOf(.1, .3, .5, .7, .9).forEach { k ->
        val (n, o) = g(k); assertEquals(1.0, n * n + o * o, 1e-9)
    }

    @Test fun matchedBothUpThenOut() {
        assertEquals(1.0, g(.25, matched = true).second, 1e-9)   // the old one full till the middle
        assertEquals(1.0, g(.5, matched = true).first, 1e-9)     // the new one full by it
        assertTrue(g(.75, matched = true).second < .75)
        assertEquals(0.0, g(1.0, matched = true).second, 1e-9)
    }

    @Test fun coldHoldsTheOldOne() {
        assertTrue(g(.7, cold = true).second > .75)              // still up near the end: its last hit heard
        assertTrue(g(.7, cold = true).second > g(.7).second)
    }

    private val all = { k: Double -> g(k) }

    @Test fun bothSingingOnlyCountsWhileBothUp() {
        // the old one sings through the blend, the new one from its start: both up only in the middle of an equal-power fade
        val c = Vocals.check(listOf(0L..20_000L), 0.0, 1.0, listOf(0L..20_000L), 0.0, 8_000, all)
        assertTrue(c.clash in 3_000..6_500)
        assertTrue(c.clash < 8_000)
    }

    @Test fun apartIsClean() {
        val c = Vocals.check(listOf(0L..1_000L), 0.0, 1.0, listOf(7_500L..12_000L), 0.0, 6_000, all)
        assertTrue(c.clean)
    }

    @Test fun aLineCutIsSaid() {
        val c = Vocals.check(listOf(2_000L..9_000L), 0.0, 1.0, emptyList(), 0.0, 6_000, all)
        assertTrue(c.cutsLine)
        assertFalse(c.hidesEntry)
    }

    @Test fun aHiddenEntryIsSaid() {
        val c = Vocals.check(emptyList(), 0.0, 1.0, listOf(500L..3_000L), 0.0, 6_000, all)
        assertTrue(c.hidesEntry)
    }

    // automix v1's curve (as till 2.1.31): beats matched, the two meet at the middle a little over half each
    @Test fun v1MeetsAtTheMiddle() {
        val (n, o) = Blend.gainsV1(.5, 0.0, true)
        assertEquals(n, o, 1e-9)
        assertEquals(.6036, n, 1e-3)
        assertEquals(0.0 to 1.0, Blend.gainsV1(0.0, 0.0, true))
        assertEquals(1.0 to 0.0, Blend.gainsV1(1.0, 0.0, false))
    }
}
