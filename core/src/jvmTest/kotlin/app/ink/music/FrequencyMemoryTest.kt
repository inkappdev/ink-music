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
}
