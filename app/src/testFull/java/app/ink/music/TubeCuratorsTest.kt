package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Test

/** Curators' video titles made songs' (TubeCurators.clean): the ones triple j's list showed, and ones already right. */
class TubeCuratorsTest {
    private fun c(t: String, a: String) = TubeCurators.clean(t, a)

    @Test fun titles() {
        assertEquals("Crazy For You" to "Lana Del Rey", c("Lana Del Rey - Crazy For You (From Grand Theft Auto VI: The Album)", "Atlantic Records"))
        assertEquals("Gas (feat. Lauren Duffus & SOARR)" to "Bicep", c("BICEP | Gas (feat. Lauren Duffus & SOARR)", "Bicep"))
        assertEquals("CÉLINE DION (BACK TO SENDER) FEAT. DRIZZZ" to "DEVAURA", c("CÉLINE DION (BACK TO SENDER) FEAT. DRIZZZ VISUALISER", "DEVAURA"))
        assertEquals("SO COOL" to "Stand Atlantic", c("Stand Atlantic - SO COOL (Official Video)", "Stand Atlantic"))
        assertEquals("Year Of The Snake" to "Nick Ward", c("Year Of The Snake", "Nick Ward"))
        assertEquals("Fantasy" to "Black Belt Eagle Scout", c("Fantasy", "Black Belt Eagle Scout"))
        assertEquals("Streets of London" to "St. Catherine's Child", c("Streets of London", "St. Catherine's Child, TRO Essex Music Group"))
        // a song whose own name ends in a word like these stays whole
        assertEquals("Radio Video" to "System Of A Down", c("Radio Video", "System Of A Down").let { it })
    }
}
