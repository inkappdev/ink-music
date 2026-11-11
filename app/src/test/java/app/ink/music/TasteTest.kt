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
        assertEquals(1, Moment.issue(kotlinx.datetime.LocalDateTime(2026, 10, 26, 7, 0)))
        assertEquals(1, Moment.issue(kotlinx.datetime.LocalDateTime(2026, 10, 27, 5, 0)))
        assertEquals(2, Moment.issue(kotlinx.datetime.LocalDateTime(2026, 10, 27, 6, 0)))
    }

    @Test fun broughtInCountsFromTheFirstDayAndFades() {
        val now = 1_800_000_000_000L
        val fresh = Taste.of(emptyList(), brought = listOf(Brought("last.fm", "me", now, mapOf("Big Thief" to 1.0, "Wednesday" to .4), emptyList())), now = now)
        assertTrue(fresh.of("Big Thief") > .85)
        assertTrue(fresh.of("Wednesday") in .35..0.45)
        assertTrue(fresh.why("Big Thief") is Taste.Why.Brought)
        val later = Taste.of(emptyList(), brought = listOf(Brought("last.fm", "me", now, mapOf("Big Thief" to 1.0), emptyList())), now = now + 180L * 86_400_000L)
        assertTrue(later.of("Big Thief") in .4..0.55)
        // played here, it's played: listening here leads
        val played = Taste.of(List(10) { Taste.Play("Big Thief", now, 200_000) }, brought = listOf(Brought("last.fm", "me", now, mapOf("Big Thief" to .3), emptyList())), now = now)
        assertTrue(played.why("Big Thief") is Taste.Why.Played)
    }

    @Test fun broughtWeighsPlaysAndLovesModestly() {
        // real listening falls off more gently than 1/rank (a real top 200: 43 near under this weighing)
        val counts = (1..200).map { "a$it" to 2000.0 / Math.sqrt(it.toDouble()) }
        val w = Brought.weigh(listOf(counts to .85), loved = listOf("someone loved once"))
        val near = w.count { it.value >= ForYou.NEAR }
        assertTrue("near: $near", near in 20..80)
        assertEquals(.15, w.getValue("someone loved once"), 1e-9)
        // two sources: the stronger says where it's from
        val t = Taste.of(emptyList(), brought = listOf(Brought("last.fm", "me", 0, mapOf("Xiu Xiu" to .4), emptyList()), Brought("listenbrainz", "me", 0, mapOf("Xiu Xiu" to .7), emptyList())), now = 0)
        assertEquals(Taste.Why.Brought("listenbrainz"), t.why("Xiu Xiu"))
    }
}
