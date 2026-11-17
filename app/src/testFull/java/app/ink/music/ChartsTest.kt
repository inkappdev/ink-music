package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube Music's charts read (real network), and their titles and moves told ink's way. */
class ChartsTest {
    @Test fun titles() {
        val g = Charts.parse("VLa", "Top 50 Country & Americana Music Videos United States")!!
        assertEquals(Charts.Kind.GENRE, g.kind); assertEquals(50, g.size); assertEquals("country & americana", g.genre); assertEquals("United States", g.place)
        assertEquals(Charts.Kind.TOP, Charts.parse("VLb", "Top 100 Music Videos Global")!!.kind)
        assertEquals(Charts.Kind.TODAY, Charts.parse("VLc", "Daily Top Music Videos - United Kingdom")!!.kind)
        assertEquals(20, Charts.parse("VLd", "Trending 20 United States")!!.size)
        assertEquals(Charts.Kind.LIVE, Charts.parse("VLe", "Top 100 Live Performances - United States")!!.kind)
    }

    @Test fun tidied() {
        assertEquals("TU PIELE ALBA, EU MARO", Charts.tidy("JADOR - TU PIELE ALBA, EU MARO (OFFICIAL VIDEO)", "Jador"))
        assertEquals("TAF - THE AURA FARMER ft. LARPER LARRY", Charts.tidy("TAF - THE AURA FARMER ft. LARPER LARRY (OFFICIAL MUSIC VIDEO)", "Horror Skunx"))
        assertEquals("Hong Kong (feat. 王菲)", Charts.tidy("Hong Kong (feat. 王菲)", "The Weeknd"))
        assertEquals("Golden", Charts.tidy("Golden [Official Lyric Video]", "HUNTR/X"))
    }

    @Test fun moves() {
        assertEquals(listOf("+1", "-1", "new"), Charts.moves(listOf("b", "a", "z"), listOf("a", "b", "c")))
        val first = Charts.look(null, listOf("a", "b"), at = 0)
        val sameDay = Charts.look(first, listOf("b", "a"), at = 3_600_000)
        assertEquals(null, sameDay.before)
        val nextDay = Charts.look(sameDay, listOf("b", "a"), at = Charts.DAY + 1)
        assertEquals(listOf("a", "b"), nextDay.before)
    }

    @Test fun read() {
        val us = Charts.of("US") { InnerTube().chartLists(it) }
        println("us: " + us.joinToString { "${it.kind} ${it.number} ${it.genre}" })
        assertTrue(us.count { it.kind == Charts.Kind.GENRE } >= 5)
        assertEquals(Charts.Kind.TOP, us.first().kind)
        val world = Charts.of("ZZ") { InnerTube().chartLists(it) }
        println("world: " + world.joinToString { "${it.kind} ${it.number}" })
        assertTrue(world.isNotEmpty())
    }
}
