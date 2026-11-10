package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Test

/** Smart playlists' rules (core SmartLists): every rule must hold; order and size; kept and read back. */
class SmartListsTest {
    private data class T(val id: String, val loved: Boolean = false, val plays: Int = 0, val last: Long = 0, val added: Long = 0,
                         val bpm: Double = 0.0, val ms: Long = 200_000, val from: String = "this phone", val kept: Boolean = false)

    private val f = object : SmartLists.Facts<T> {
        override fun id(s: T) = s.id
        override fun loved(s: T) = s.loved
        override fun plays(s: T) = s.plays
        override fun last(s: T) = s.last
        override fun added(s: T) = s.added
        override fun bpm(s: T) = s.bpm
        override fun length(s: T) = s.ms
        override fun from(s: T) = s.from
        override fun kept(s: T) = s.kept
        override fun genre(s: T, g: String) = false
    }
    private val now = 2_000_000_000_000L
    private val day = 86_400_000L

    @Test fun fastAndLoved() {
        val pool = listOf(T("a", loved = true, bpm = 128.0), T("b", loved = true, bpm = 90.0), T("c", bpm = 130.0),
            T("d", loved = true, bpm = 65.0), T("e", loved = true))
        val got = SmartLists.pick(pool, SmartLists.STARTERS[0], f, now).map { it.id }.toSet()
        // 65 is a 130's half time: fast; no tempo known: not fast
        assertEquals(setOf("a", "d"), got)
    }

    @Test fun forgottenMostFirst() {
        val pool = listOf(T("a", plays = 6, last = now - 200 * day), T("b", plays = 12, last = now - 300 * day),
            T("c", plays = 9, last = now - 2 * day), T("d", plays = 2, last = now - 400 * day))
        assertEquals(listOf("b", "a"), SmartLists.pick(pool, SmartLists.STARTERS[2], f, now).map { it.id })
    }

    @Test fun keptAndLong() {
        val l = SmartLists.SmartList("x", "x", mapOf(SmartLists.Kind.FROM to "kept offline", SmartLists.Kind.LENGTH to "long"), most = 0)
        val pool = listOf(T("a", kept = true, ms = 400_000), T("b", kept = true), T("c", ms = 500_000))
        assertEquals(listOf("a"), SmartLists.pick(pool, l, f, now).map { it.id })
    }

    @Test fun keptAndReadBack() {
        val lists = listOf(SmartLists.SmartList("1", "gym", mapOf(SmartLists.Kind.TEMPO to "fast", SmartLists.Kind.LOVED to "loved"), SmartLists.Order.MOST, 25))
        assertEquals(lists, SmartLists.load(SmartLists.save(lists)))
    }
}
