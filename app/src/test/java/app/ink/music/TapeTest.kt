package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapeTest {
    private fun full() = Tape(
        "Songs for the night bus home", "sam", "made this on the 14 at 2am, side b is for the walk from the stop",
        (1..24).map { Tape.Track("A Song Called Number $it (Remastered)", "Some Band and the Others", 180 + it * 7, if (it % 2 == 0) "dQw4w9WgXc$it".take(11) else "") },
    )

    @Test fun roundTrip() {
        val t = full()
        val back = Tape.read(t.link())!!
        assertEquals(t, back)
    }

    @Test fun linkFitsAQrCode() {
        val link = full().link()
        println("24 songs: ${link.length} characters")
        // a QR code at its lowest error correction holds 2,953 bytes; this should be well under, for a code you can scan off a screen
        assertTrue(link.length < 1_400)
    }

    @Test fun notATape() {
        assertNull(Tape.read("https://inkhome.app/tape#not-a-tape"))
        assertNull(Tape.read(""))
        assertNull(Tape.read("https://inkhome.app/"))
    }

    @Test fun readsTheOtherLinks() {
        val packed = full().pack()
        assertEquals(full(), Tape.read("inkmusic://tape#$packed"))
        assertEquals(full(), Tape.read(packed))
    }

    @Test fun sidesByTime() {
        // one long song then short ones: side b begins where the time's half gone, not at the count's middle
        val t = listOf(Tape.Track("long", "a", 1200)) + (1..6).map { Tape.Track("s$it", "a", 200) }
        assertEquals(1, Tape.sides(t))
        // lengths not known: by count
        assertEquals(3, Tape.sides((1..5).map { Tape.Track("s$it", "a") }))
        assertEquals("C46", Tape("x", tracks = (1..10).map { Tape.Track("s$it", "a", 240) }).cassette)
    }
}
