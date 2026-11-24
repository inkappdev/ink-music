package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading setlist.fm's answers and what comes up most in them (Setlists), from answers shaped as its docs show. */
class SetlistsTest {
    // an artist's setlists as the API answers (its docs' example, The Beatles; the sets under "sets" as the API has them):
    // the newest an upcoming show with no songs yet, a tape and a cover among the songs
    private val answer = """
    {"type":"setlists","itemsPerPage":20,"page":1,"total":4,"setlist":[
      {"id":"1","eventDate":"30-01-2026","artist":{"mbid":"b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d","name":"The Beatles",
        "url":"https://www.setlist.fm/setlists/the-beatles-23d6a88b.html"},
        "venue":{"name":"Somewhere","city":{"name":"London"}},"sets":{"set":[]},"url":"https://www.setlist.fm/setlist/x.html"},
      {"id":"2","eventDate":"29-01-1969","artist":{"mbid":"b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d","name":"The Beatles",
        "url":"https://www.setlist.fm/setlists/the-beatles-23d6a88b.html"},
        "venue":{"name":"Apple Corps Rooftop","city":{"name":"London"}},
        "sets":{"set":[{"song":[{"name":"Intro","tape":true},{"name":"Get Back"},{"name":"Don't Let Me Down"},
          {"name":"Danny Boy","cover":{"mbid":"x","name":"Frederic Weatherly"}}]},
          {"encore":1,"song":[{"name":"Get Back","info":"again"},{"name":""}]}]}},
      {"id":"3","eventDate":"29-08-1966","artist":{"name":"The Beatles"},
        "sets":{"set":[{"song":[{"name":"Rock and Roll Music"},{"name":"Don't Let Me Down"},{"name":"get back"},{"name":"Help!"}]}]}},
      {"id":"4","eventDate":"28-08-1966","artist":{"name":"The Beatles"},
        "set":[{"song":[{"name":"Don't Let Me Down"},{"name":"Rock and Roll Music"}]}]}
    ]}
    """.trimIndent()

    @Test fun parse() {
        val p = Setlists.parse(answer)
        assertEquals("https://www.setlist.fm/setlists/the-beatles-23d6a88b.html", p.credit)
        // the show with no songs left out
        assertEquals(listOf("1969-01-29", "1966-08-29", "1966-08-28"), p.shows.map { it.day })
        // the tape and the nameless song not counted; the encore's song kept as played
        assertEquals(listOf("Get Back", "Don't Let Me Down", "Danny Boy", "Get Back"), p.shows[0].songs.map { it.title })
        assertEquals("Frederic Weatherly", p.shows[0].songs[2].cover)
        assertNull(p.shows[0].songs[0].cover)
    }

    @Test fun rank() {
        val h = Setlists.rank(Setlists.parse(answer))!!
        assertEquals(3, h.shows)
        assertEquals("1969-01-29", h.latest)
        // each counted once a show (Get Back twice in one is once), however it's written; a tie, the earlier in the set first
        assertEquals(listOf("Don't Let Me Down" to 3, "Get Back" to 2, "Rock and Roll Music" to 2, "Danny Boy" to 1, "Help!" to 1),
            h.songs.map { it.title to it.times })
        assertEquals("Frederic Weatherly", h.songs.first { it.title == "Danny Boy" }.cover)
        // only the latest shows asked for
        assertEquals(listOf("Get Back", "Don't Let Me Down", "Danny Boy"), Setlists.rank(Setlists.parse(answer), shows = 1)!!.songs.map { it.title })
    }

    @Test fun keptAndReadBack() {
        val h = Setlists.rank(Setlists.parse(answer))!!
        assertEquals(h, Setlists.Heard.of(h.json()))
    }

    @Test fun nothing() {
        assertNull(Setlists.rank(Setlists.parse("""{"setlist":[]}""")))
        assertEquals(Setlists.HOME, Setlists.parse("{}").credit)
        assertEquals("https://api.setlist.fm/rest/1.0/artist/b10bbbfc-cf9e/setlists?p=2", Setlists.request("b10bbbfc-cf9e", 2))
    }
}
