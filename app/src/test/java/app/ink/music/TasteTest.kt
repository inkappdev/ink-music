package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun nearnessByListening() {
        val plays = (1..10).map { Taste.Play("Phoebe Bridgers", now - it * day, 200_000) } + Taste.Play("Air", now - 200 * day, 200_000)
        val t = Taste.of(plays, now = now)
        assertTrue(t.of("Phoebe Bridgers") > .9)
        assertTrue(t.of("Air") < .1)
        assertEquals(0.0, t.of("Air Supply"), 0.0)
        assertEquals(Taste.Why.Played(10), t.why("phoebe bridgers"))
        // shared names: the nearest of them
        assertTrue(t.of("Phoebe Bridgers & Julien Baker") > .9)
    }

    @Test fun fansAlsoLikeAndNotForYou() {
        val plays = (1..10).map { Taste.Play("Beach House", now - it * day, 200_000) }
        val t = Taste.of(plays, related = mapOf("Beach House" to listOf("Cocteau Twins", "Slowdive")), refused = listOf("Slowdive"), now = now)
        assertTrue(t.of("Cocteau Twins") > .3)
        assertEquals(Taste.Why.Like("Beach House"), t.why("Cocteau Twins"))
        assertTrue(t.of("Slowdive") < 0)
        assertTrue(t.refused("slowdive"))
    }

    @Test fun skipsPushAway() {
        val plays = (1..2).map { Taste.Play("Band", now - it * day, 200_000) }
        val skips = (1..5).map { Taste.Play("Band", now - it * day, 5_000) }
        assertTrue(Taste.of(plays, skips, now = now).of("Band") < Taste.of(plays, now = now).of("Band"))
    }

    @Test fun seventyThirty() {
        val items = (0 until 20).map { it }
        val placed = ForYou.order(items, taste = { if (it < 12) .8 else .0 }, picked = { .5 }, fresh = { 1.0 }, tired = { 1.0 }, day = 1)
        val firstTen = placed.take(10)
        assertEquals(3, firstTen.count { it.stretch })
        assertTrue(!placed.first().stretch)
        assertEquals(20, placed.size)
    }

    @Test fun seenRests() {
        val f = java.io.File.createTempFile("seen", ".json")
        val s = Seen(f)
        for (d in 0 until 5) s.shown("x", now + d * day)
        assertEquals(.05, s.fresh("x", now + 5 * day), 0.0)
        assertEquals(1.0, s.fresh("x", now + 13 * day), 0.0)
        s.tapped("y"); assertEquals(.8, s.fresh("y"), 0.0)
        s.save(); assertEquals(5, Seen(f).days("x"))
        f.delete()
    }

    @Test fun issueTurnsAtSix() {
        val at = java.time.LocalDateTime.of(2026, 10, 26, 7, 0)
        assertEquals(1, Moment.issue(at))
        assertEquals(1, Moment.issue(at.plusDays(1).withHour(5)))
        assertEquals(2, Moment.issue(at.plusDays(1).withHour(6)))
    }
}
