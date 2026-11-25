package app.ink.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesakeTest {
    @Test fun anotherArtistsSongOfTheSameName() {
        assertTrue(Similar.namesake("Creep", "Stone Temple Pilots", "Creep", "Radiohead"))
        assertTrue(Similar.namesake("Creep (Live)", "Postmodern Jukebox", "Creep", "Radiohead"))
        assertTrue(Similar.namesake("HALO", "Someone Else", "Halo - Remastered", "Beyoncé"))
    }

    @Test fun theSeedsOwnOrSomethingElse() {
        // the same artist (or among the credits), and another title, aren't namesakes
        assertFalse(Similar.namesake("Creep", "Radiohead", "Creep", "Radiohead"))
        assertFalse(Similar.namesake("Creep", "radiohead, Someone", "Creep", "Radiohead"))
        assertFalse(Similar.namesake("Too Sweet", "Hozier & Jessie Murph", "Too Sweet", "Hozier"))
        assertFalse(Similar.namesake("Karma Police", "Radiohead", "Creep", "Radiohead"))
        assertFalse(Similar.namesake("Creepin'", "Metro Boomin", "Creep", "Radiohead"))
        assertFalse(Similar.namesake("Beyoncé song", "Beyonce", "Halo", "Beyoncé"))
    }
}
