package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test fun folds() {
        assertEquals("lila ike and friends dont", Search.fold("Lila Iké & Friends: Don't"))
    }

    // 2026-11-17, a tester's titles: each asked online as typed, never made the same as another or too short
    @Test fun askedAsTyped() {
        assertEquals("ピーナツ ⧸ 可不", Search.online("  ピーナツ  ⧸ 可不 "))
        assertTrue(Search.online("ピーナツ ⧸ 可不") != Search.online("ピーナツ 可不"))
        assertTrue(Search.enough("愛℃"))
        assertTrue(Search.enough("愛"))
        assertFalse(Search.enough("a"))
        assertTrue(Search.enough("CHOCOL人´∀｀ATE!!!"))
        assertEquals("ピーナツ 可不", Search.plain("ピーナツ ⧸ 可不"))
        assertEquals("CHOCOL人 ATE", Search.plain("CHOCOL人´∀｀ATE!!!"))
    }

    @Test fun videoIds() {
        assertEquals("VDvX1hzGZN4", Search.tubeId("[VDvX1hzGZN4]"))
        assertEquals("VDvX1hzGZN4", Search.tubeId("VDvX1hzGZN4"))
        assertEquals("VDvX1hzGZN4", Search.tubeId("Tom Grennan - Found What I've Been Looking For [VDvX1hzGZN4]"))
        assertEquals("m6F-bo17yC8", Search.tubeId("m6F-bo17yC8"))
        assertNull(Search.tubeId("beautifully"))
        assertNull(Search.tubeId("Beautifully"))
        assertNull(Search.tubeId("radiohead"))
    }

    @Test fun bestFirst() {
        val found = listOf("Chocolate (Choco Choco)", "CHOCOL人´∀｀ATE!!! - CHOCOLAAATE!!!", "巧克力爱意")
        assertEquals("CHOCOL人´∀｀ATE!!! - CHOCOLAAATE!!!", Search.best("CHOCOL人´∀｀ATE!!!", found, { it }).first())
    }
}
