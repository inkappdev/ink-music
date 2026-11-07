package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueTest {
    private data class T(val artist: String, val album: String, val art: String? = null)
    private fun keys(t: T) = Unique.record(t.art, t.artist, t.album)

    @Test fun oncePerPageAndSpread() {
        val taken = HashSet<String>()
        val first = Unique.shelf(listOf(
            T("Fontaines D.C.", "Romance", "https://x/a/1200x1200bb.jpg"), T("Fontaines D.C.", "Romance", "https://x/a/600x600bb.jpg"),
            T("Fontaines D.C.", "Skinty Fia"), T("Wet Leg", "Moisturizer"), T("Wet Leg", "Wet Leg"), T("DIIV", "ZIRP!"),
        ), taken, ::keys) { it.artist }
        // the same record (same cover at another size) once; no artist twice in a row
        assertEquals(setOf("Romance", "Moisturizer", "Skinty Fia", "ZIRP!", "Wet Leg"), first.map { it.album }.toSet())
        assertEquals(5, first.size)
        first.zipWithNext().forEach { (a, b) -> assert(a.artist != b.artist) { "$a then $b" } }
        // a lower shelf: what's above dropped (by name, accents and "the" aside)
        val second = Unique.shelf(listOf(T("the diiv", "Zirp!"), T("Lila Iké", "Treasure Self Love")), taken, ::keys) { it.artist }
        assertEquals(listOf("Treasure Self Love"), second.map { it.album })
    }
}
