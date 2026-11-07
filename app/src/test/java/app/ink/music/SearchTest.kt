package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    @Test fun folds() {
        assertEquals("lila ike and friends dont", Search.fold("Lila Iké & Friends: Don't"))
    }
}
