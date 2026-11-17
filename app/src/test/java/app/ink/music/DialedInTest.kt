package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialedInTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    // Tuesday 13:00 as a minute of the week
    private val tue1pm = 1 * 1440 + 13 * 60

    private fun l(daysAgo: Int, id: String, artist: String, record: String, minute: Int) =
        DialedIn.Listen(now - daysAgo * day, id, artist, record, minute)

    @Test fun thisHourFirst() {
        val listens = buildList {
            // a record heard at this hour, two songs of it
            repeat(4) { add(l(7 * (it + 1), "s1", "Rey Casino", "r1", tue1pm)); add(l(7 * (it + 1), "s2", "Rey Casino", "r1", tue1pm + 20)) }
            // something played more, but at night
            repeat(12) { add(l(2 + it, "s9", "Night Band", "r9", 1 * 1440 + 23 * 60)); add(l(2 + it, "s8", "Night Band", "r9", 1 * 1440 + 23 * 60)) }
            // a single, this hour
            repeat(3) { add(l(3 + it, "s5", "Single Person", "", tue1pm)) }
        }
        val slots = DialedIn.pick(listens, tue1pm, now, emptyList(), emptySet())
        println(slots)
        assertEquals("r:r1", slots[0].key)
        assertEquals(DialedIn.Kind.RECORD, slots[0].kind)
        assertTrue(slots.any { it.key == "s:s5" && it.kind == DialedIn.Kind.SONG })
    }

    @Test fun artistAcrossRecordsAndPins() {
        val listens = (1..3).flatMap { r -> listOf(l(r, "a$r", "Fine", "rec$r", tue1pm), l(r, "b$r", "Fine", "rec$r", tue1pm)) } +
            listOf(l(1, "x", "Other", "", tue1pm))
        val pin = DialedIn.Pin(5, "s:x", DialedIn.Kind.SONG, "x", "Other", "")
        val slots = DialedIn.pick(listens, tue1pm, now, listOf(pin), emptySet())
        println(slots)
        // three records by one artist: the artist, once
        assertEquals(1, slots.count { it.artist == "Fine" })
        assertEquals(DialedIn.Kind.ARTIST, slots.first { it.artist == "Fine" }.kind)
        // the pin at its number, not again elsewhere
        assertEquals("s:x", slots.first { it.n == 5 }.key)
        assertEquals(1, slots.count { it.key == "s:x" })
        // said not here: gone
        assertTrue(DialedIn.pick(listens, tue1pm, now, emptyList(), setOf("a:fine")).none { it.artist == "Fine" })
        // kept and read back
        val (pins, hidden) = DialedIn.read(DialedIn.json(listOf(pin), setOf("a:fine")))
        assertEquals(listOf(pin), pins); assertEquals(setOf("a:fine"), hidden)
    }

    @Test fun mixMarks() {
        // a mix's picture link as YouTube makes it: field 1 its kind (8), field 2 its number
        val bytes = byteArrayOf(0x10, 0xd8.toByte(), 0x04, 0x18, 0xd8.toByte(), 0x04, 0x32, 0x06, 0x08, 0x08, 0x10, 0x04, 0x1a, 0x00)
        val art = "https://music.youtube.com/image/mixart?r=" + b64url(bytes)
        assertEquals(4, TubeMixes.number(art))
        assertEquals("04", TubeMixes.mark("Atmospheric Electronic Pulse", art))
        assertEquals("SUPER", TubeMixes.mark("My Supermix", art))
        assertEquals("DISCOVER", TubeMixes.mark("Discover Mix", "https://music.youtube.com/image/mixart?r=" + b64url(byteArrayOf(0x08, 0x06))))
        assertEquals("The Japanese House", TubeMixes.lead("The Japanese House, hard life, After"))
        assertEquals("tuesday afternoon, what you play now", DialedIn.said(tue1pm))
    }
}
