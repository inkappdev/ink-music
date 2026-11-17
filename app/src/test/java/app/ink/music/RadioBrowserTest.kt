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
        val jazz = RadioBrowser.browse(RadioBrowser.Ask(tag = RadioBrowser.tag("jazz"))).stations
        println("jazz ${jazz.size}: " + jazz.take(5).joinToString { "${it.name} (${it.said})" })
        assertTrue(jazz.size >= 20)
        // a page more: the first page's as they were, more after, and more still to come
        val two = RadioBrowser.browse(RadioBrowser.Ask(tag = RadioBrowser.tag("jazz")), pages = 2)
        assertTrue(two.more && two.stations.size > jazz.size && two.stations.map { it.id }.containsAll(jazz.map { it.id }.take(10)))
        // a genre of three spellings: each paged, merged; a page more keeps every station the first showed
        val hiphop = RadioBrowser.Ask(tag = RadioBrowser.GENRES.first { it.variants.size > 1 })
        val one = RadioBrowser.browse(hiphop).stations
        val more = RadioBrowser.browse(hiphop, pages = 2).stations
        println("${hiphop.tag?.word}: ${one.size} then ${more.size}")
        assertTrue(more.size > one.size && more.map { it.id }.containsAll(one.map { it.id }))
        // a country of thousands: more after three pages, whatever was left out of them
        assertTrue(RadioBrowser.browse(RadioBrowser.Ask(code = "US"), pages = 3).more)
        val countries = RadioBrowser.countries()
        println("countries ${countries.size}: " + countries.take(8).joinToString { "${it.name} ${it.code} ${it.count}" })
        val states = RadioBrowser.states("PT")
        println("portugal's states ${states.size}: " + states.take(8).joinToString { "${it.name} ${it.count}" })
        assertTrue(states.isNotEmpty())
        val lisbon = RadioBrowser.browse(RadioBrowser.Ask(code = "PT", state = states.first().name)).stations
        println("in ${states.first().name}: " + lisbon.take(5).joinToString { it.name })
        assertTrue(lisbon.isNotEmpty())
        val trending = RadioBrowser.browse(RadioBrowser.Ask(code = "US", order = RadioBrowser.Order.TRENDING)).stations
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
