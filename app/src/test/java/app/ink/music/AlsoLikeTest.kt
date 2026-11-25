package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Fans also like": YouTube Music's list put in order by Last.fm's and ListenBrainz's, and who they'd add (2026-11-24). */
class AlsoLikeTest {
    private val tube = listOf("Bon Iver", "Fleet Foxes", "Iron & Wine", "The National", "Phoebe Bridgers")
    private val kin = listOf("Phoebe Bridgers" to .9, "The Nátional" to .7, "Big Thief" to .65, "Sufjan Stevens" to .8, "Adrianne Lenker" to .4)

    @Test fun agreedFirstByHowNear() {
        val b = Similar.alsoLike(tube, kin, 3) { false }
        // Phoebe Bridgers (.9) and The National (.7, its accent aside) first, then YouTube's order
        assertEquals(listOf("Phoebe Bridgers", "The National", "Bon Iver", "Fleet Foxes", "Iron & Wine"), b.order.map { tube[it] })
        assertEquals(2, b.agreed)
        assertEquals(listOf("Sufjan Stevens", "Big Thief", "Adrianne Lenker"), b.extra)
    }

    @Test fun yoursAddedFirst() {
        val b = Similar.alsoLike(tube, kin, 2) { it == "Adrianne Lenker" }
        assertEquals(listOf("Adrianne Lenker", "Sufjan Stevens"), b.extra)
    }

    @Test fun noneAgreeingKeepsYouTubesOrder() {
        val b = Similar.alsoLike(tube, emptyList(), 3) { true }
        assertEquals(tube.indices.toList(), b.order)
        assertEquals(0, b.agreed)
        assertEquals(emptyList<String>(), b.extra)
    }
}
