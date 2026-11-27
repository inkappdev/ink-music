package app.ink.music

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What your frequency remembers across sittings, and songs the sources agree on. */
class FrequencyMemoryTest {
    private val day = 86_400_000L

    @Test fun anArtistLeftTwiceStaysAwayAMonth() {
        val m = FrequencyMemory.empty()
        m.went("Wishy", "related", FrequencyMix.How.LEFT, 0)
        assertFalse(m.away("Wishy", day))
        m.went("Wishy", "related", FrequencyMix.How.LEFT, day)
        assertTrue(m.away("Wishy", 2 * day))
        assertFalse(m.away("Wishy", 40 * day))
        // taken once: not away, whatever's been left
        m.went("Wishy", "related", FrequencyMix.How.THROUGH, 3 * day)
        assertFalse(m.away("Wishy", 4 * day))
    }

    @Test fun aSourceEarnsItsShare() {
        val m = FrequencyMemory.empty()
        repeat(6) { m.went("A$it", "lastfm", FrequencyMix.How.THROUGH, 0) }
        repeat(6) { m.went("B$it", "related", FrequencyMix.How.LEFT, 0) }
        assertTrue(m.worth("lastfm") > 1.4)
        assertTrue(m.worth("related") < .7)
        assertEquals(1.0, m.worth("never"))
    }

    @Test fun whatStuckIsWhatYouCameBackTo() {
        val m = FrequencyMemory.empty()
        m.sitting(0, 2 * 3_600_000L)
        m.brought("Dijon", "lastfm", 3_600_000L)
        m.brought("Slow Pulp", "related", 3_600_000L)
        m.brought("Wishy", "related", 3_600_000L)
        // Dijon played through twice away from it; Slow Pulp loved; Wishy only played in the sitting
        val heard = listOf(Triple("Dijon", 5 * day, 200_000L), Triple("Dijon", 6 * day, 180_000L), Triple("Wishy", 3_700_000L, 200_000L))
        val stuck = m.stuck(heard, mapOf("Slow Pulp" to 2 * day))
        assertEquals(setOf("Dijon", "Slow Pulp"), stuck.toSet())
        assertEquals(3, m.broughtCount)
    }

    @Test fun keptAndReadBack() {
        val m = FrequencyMemory.empty()
        m.went("Dijon", "lastfm", FrequencyMix.How.LOVED, 10)
        m.brought("Dijon", "lastfm", 5)
        m.sitting(1, 2)
        val back = FrequencyMemory.of(m.json())
        assertEquals(m.json(), back.json())
        assertEquals(FrequencyMemory.empty().json(), FrequencyMemory.of("not json").json())
    }

    @Test fun songsTheSourcesAgreeOnComeFirst() {
        val found = listOf(
            FrequencyMix.Found("a", "Alpha", FrequencyMix.FAR, .9, source = "related"),
            FrequencyMix.Found("b", "Beta", FrequencyMix.FAR, .5, source = "related"),
            FrequencyMix.Found("b", "Beta", FrequencyMix.FAR, .4, source = "lastfm"),
        )
        val agreed = FrequencyMix.agreed(found, setOf("beta"))
        assertEquals(3, agreed.first { it.key == "b" }.ears)
        assertEquals(1, agreed.first { it.key == "a" }.ears)
        val picked = FrequencyMix.pick(agreed, FrequencyMix.Dials(1.0), { 0.0 }, FrequencyMix.Sitting(emptyList()), FrequencyMix.Learned(),
            emptyList(), 1, random = Random(1))
        assertEquals("b", picked.single().key)
    }

    @Test fun howASongWentByHowMuchPlayed() {
        assertEquals(FrequencyMix.How.THROUGH to 1.0, FrequencyMix.how(190_000, 200_000))
        assertEquals(FrequencyMix.How.THROUGH to .5, FrequencyMix.how(120_000, 200_000))
        assertEquals(FrequencyMix.How.LEFT to 1.0, FrequencyMix.how(4_000, 200_000))
        assertEquals(FrequencyMix.How.LEFT to .7, FrequencyMix.how(20_000, 200_000))
        assertEquals(FrequencyMix.How.LEFT to .3, FrequencyMix.how(60_000, 200_000))
        assertEquals(null, FrequencyMix.how(0, 200_000))
        // a late skip doesn't send an artist away; quick ones do
        val m = FrequencyMemory.empty()
        repeat(3) { m.went("Late", "related", FrequencyMix.How.LEFT, 0, .3) }
        assertFalse(m.away("Late", 1))
    }

    @Test fun everyEighthIsALongShot() {
        val found = (0 until 20).map { FrequencyMix.Found("k$it", "Artist$it", FrequencyMix.FAR, if (it == 0) 1.0 else .01, source = "related") }
        val picks = FrequencyMix.pick(found, FrequencyMix.Dials(1.0), { 0.0 }, FrequencyMix.Sitting(emptyList()), FrequencyMix.Learned(),
            emptyList(), 1, random = Random(3), given0 = mapOf(FrequencyMix.FAR to FrequencyMix.LONG_SHOT - 1))
        // the best (k0) passed over for one chosen at random
        assertTrue(picks.single().key != "k0")
        // at all yours there are none
        val yours = FrequencyMix.pick(found, FrequencyMix.Dials(0.0), { 0.0 }, FrequencyMix.Sitting(emptyList()), FrequencyMix.Learned(),
            emptyList(), 1, random = Random(3), given0 = mapOf(FrequencyMix.FAR to FrequencyMix.LONG_SHOT - 1))
        assertTrue(yours.isEmpty() || yours.single().key == "k0")
    }

    @Test fun flowAndAim() {
        val now = FrequencyFlow.Sound(120.0, gain = -8.0)
        assertTrue(FrequencyFlow.follows(now, FrequencyFlow.Sound(121.0, gain = -9.0)) > FrequencyFlow.follows(now, FrequencyFlow.Sound(150.0, gain = -2.0)))
        // half and double counted
        assertTrue(FrequencyFlow.follows(now, FrequencyFlow.Sound(60.5)) >= .2)
        assertTrue(FrequencyFlow.aimed(50_000, 4.7) > FrequencyFlow.aimed(3_000_000, 4.7))
        assertEquals(0.0, FrequencyFlow.aimed(null, 4.7))
    }
}
