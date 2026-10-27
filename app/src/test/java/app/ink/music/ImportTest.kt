package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reading Spotify's playlists from the files people have: Exportify's CSV and Spotify's own download. */
class ImportTest {
    private fun csv(text: String, name: String) =
        Import::class.java.getDeclaredMethod("csv", String::class.java, String::class.java).apply { isAccessible = true }.invoke(Import, text, name) as Import.Listed?

    @Suppress("UNCHECKED_CAST")
    private fun spotify(text: String) =
        Import::class.java.getDeclaredMethod("spotify", String::class.java).apply { isAccessible = true }.invoke(Import, text) as List<Import.Listed>

    @Test fun exportify() {
        val l = csv(
            "\"Track URI\",\"Track Name\",\"Album Name\",\"Artist Name(s)\",\"Release Date\",\"Duration (ms)\"\n" +
                "\"spotify:track:1\",\"Joker Lips\",\"Manning Fireworks\",\"MJ Lenderman\",\"2024-09-06\",\"182000\"\n" +
                "\"spotify:track:2\",\"Kool Faith, Pt. 2\",\"Kool Faith / Loosies II\",\"They Are Gutting a Body of Water;DUPONTPAVILION\",\"2024\",\"200000\"\r\n" +
                "\"spotify:track:3\",\"Say \"\"Hi\"\"\",\"Rec\",\"Someone\",\"2020\",\"1000\"\n",
            "my mix",
        )!!
        assertEquals("my mix", l.name)
        assertEquals(3, l.songs.size)
        assertEquals(Import.Said("Kool Faith, Pt. 2", "They Are Gutting a Body of Water", "Kool Faith / Loosies II"), l.songs[1])
        assertEquals("Say \"Hi\"", l.songs[2].title)
    }

    @Test fun spotifyDownload() {
        val all = spotify("""{"playlists":[{"name":"road","items":[{"track":{"trackName":"Pyre","artistName":"Witches Exist","albumName":"pyre"}},{"episode":null}]},{"name":"empty","items":[]}]}""")
        assertEquals(1, all.size)
        assertEquals("road", all[0].name)
        assertEquals(Import.Said("Pyre", "Witches Exist", "pyre"), all[0].songs.single())
    }
}
