package app.ink.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** radio-browser itself (real network): genres, places, a search every way, a page after the first. */
class RadioBrowserTest {
    @Test fun browses() {
        val tags = RadioBrowser.GENRES
        println("tags ${tags.size}: " + tags.take(40).joinToString { "${it.word}${it.variants.drop(1).takeIf { v -> v.isNotEmpty() }?.let { v -> "(" + v.joinToString("/") + ")" } ?: ""} ${it.count}" })
        assertTrue(tags.size >= 40)
        assertTrue(tags.none { it.word == "music" || it.word == "english" })
        val jazz = RadioBrowser.stations(RadioBrowser.Ask(tag = RadioBrowser.tag("jazz")))
        println("jazz ${jazz.size}: " + jazz.take(5).joinToString { "${it.name} (${it.said})" })
        assertTrue(jazz.size >= 20)
        val next = RadioBrowser.stations(RadioBrowser.Ask(tag = RadioBrowser.tag("jazz")), page = 1)
        assertTrue(next.isNotEmpty() && next.none { n -> jazz.any { it.id == n.id } })
        val countries = RadioBrowser.countries()
        println("countries ${countries.size}: " + countries.take(8).joinToString { "${it.name} ${it.code} ${it.count}" })
        val states = RadioBrowser.states("PT")
        println("portugal's states ${states.size}: " + states.take(8).joinToString { "${it.name} ${it.count}" })
        assertTrue(states.isNotEmpty())
        val lisbon = RadioBrowser.stations(RadioBrowser.Ask(code = "PT", state = states.first().name))
        println("in ${states.first().name}: " + lisbon.take(5).joinToString { it.name })
        assertTrue(lisbon.isNotEmpty())
        val trending = RadioBrowser.stations(RadioBrowser.Ask(code = "US", order = RadioBrowser.Order.TRENDING))
        println("trending in the us: " + trending.take(5).joinToString { it.name })
        val found = RadioBrowser.search("portugal")
        println("search portugal: name ${found.byName.size}, tagged ${found.tagged.size}, in ${found.place} ${found.placed.size}")
        assertTrue(found.placed.isNotEmpty())
        val ambient = RadioBrowser.search("ambient")
        println("search ambient: name ${ambient.byName.size}, tagged ${ambient.tagged.size}")
        assertTrue(ambient.tagged.isNotEmpty())
    }

    @Test fun streams() {
        assertTrue(RadioBrowser.isStream("https://stream.example.org/live.mp3"))
        assertTrue(RadioBrowser.isStream("http://123.45.67.8:8000/"))
        assertTrue(RadioBrowser.isStream("https://ice1.somafm.com/groovesalad-128-mp3"))
        assertFalse(RadioBrowser.isStream("https://youtu.be/VDvX1hzGZN4"))
        assertFalse(RadioBrowser.isStream("not a link"))
    }
}
