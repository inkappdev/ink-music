package app.ink.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An update is offered only when GitHub's is newer, by each part as a number (not as text: 1.4.10 > 1.4.9). */
class UpdatesTest {
    @Test fun newer() {
        assertTrue(Updates.newer("1.4.10", "1.4.9"))
        assertTrue(Updates.newer("1.5", "1.4.9"))
        assertTrue(Updates.newer("2.0.0", "1.9.99"))
        assertFalse(Updates.newer("1.4.9", "1.4.9"))
        assertFalse(Updates.newer("1.4", "1.4.0"))
        assertFalse(Updates.newer("1.4.8", "1.4.9"))
        assertFalse(Updates.newer("", "1.4.9"))
    }
}
