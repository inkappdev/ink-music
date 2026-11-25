package app.ink.music

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your own playlists kept in step between two devices (ListsSync): each list the one changed last, a deletion too. */
class ListsSyncTest {
    private fun song(id: String) = JSONObject().put("id", id).put("title", id).put("artist", "a")
    private fun list(id: String, name: String, at: Long, vararg songs: String) = ListsSync.Wired(id, name, songs.map(::song), at)
    private fun ids(m: ListsSync.Merged) = m.lists.map { it.list.id }

    @Test fun theNewerWins() {
        val m = ListsSync.merge(listOf(list("here:1", "rock", 10, "x")), emptyMap(), listOf(list("here:1", "rock and roll", 20, "y", "x")), emptyMap())
        assertTrue(m.changed)
        val k = m.lists.single()
        assertTrue(k.theirs)
        assertEquals("rock and roll", k.list.name)
        assertEquals(listOf("y", "x"), k.list.songs.map { it.getString("id") })
        // the other way round: mine stays, nothing to do here
        val n = ListsSync.merge(listOf(list("here:1", "rock", 30, "x")), emptyMap(), listOf(list("here:1", "old", 20)), emptyMap())
        assertFalse(n.changed)
        assertFalse(n.lists.single().theirs)
    }

    @Test fun aTieStaysAsItIs() {
        val m = ListsSync.merge(listOf(list("here:1", "mine", 5)), emptyMap(), listOf(list("here:1", "theirs", 5)), emptyMap())
        assertFalse(m.changed)
        assertEquals("mine", m.lists.single().list.name)
    }

    @Test fun onlyTheirsComesInAfterMine() {
        val m = ListsSync.merge(listOf(list("here:2", "b", 1), list("here:1", "a", 1)), emptyMap(),
            listOf(list("here:3", "c", 1), list("here:1", "a", 1)), emptyMap())
        assertEquals(listOf("here:2", "here:1", "here:3"), ids(m))
        assertTrue(m.changed)
        // they've none of mine yet: mine kept, nothing changed
        val n = ListsSync.merge(listOf(list("here:2", "b", 1)), emptyMap(), emptyList(), emptyMap())
        assertEquals(listOf("here:2"), ids(n))
        assertFalse(n.changed)
    }

    @Test fun deletedStaysDeleted() {
        // deleted there after it last changed here: gone here, and the mark kept
        val m = ListsSync.merge(listOf(list("here:1", "a", 10)), emptyMap(), emptyList(), mapOf("here:1" to 20L))
        assertEquals(emptyList<String>(), ids(m))
        assertEquals(20L, m.gone["here:1"])
        assertTrue(m.changed)
        // deleted here: theirs not taken back
        val n = ListsSync.merge(emptyList(), mapOf("here:1" to 20L), listOf(list("here:1", "a", 10)), emptyMap())
        assertEquals(emptyList<String>(), ids(n))
        assertFalse(n.changed)
        // an older file's list (no time) loses to any deletion
        val o = ListsSync.merge(listOf(list("here:1", "a", 0)), emptyMap(), emptyList(), mapOf("here:1" to 1L))
        assertEquals(emptyList<String>(), ids(o))
    }

    @Test fun changedAfterItWasDeletedComesBack() {
        val m = ListsSync.merge(emptyList(), mapOf("here:1" to 20L), listOf(list("here:1", "a", 30, "x")), emptyMap())
        assertEquals(listOf("here:1"), ids(m))
        assertTrue(m.lists.single().theirs)
    }

    @Test fun theMarksMeet() {
        val m = ListsSync.merge(emptyList(), mapOf("here:1" to 5L), emptyList(), mapOf("here:1" to 9L, "here:2" to 3L))
        assertEquals(mapOf("here:1" to 9L, "here:2" to 3L), m.gone)
        assertTrue(m.changed)
        assertFalse(ListsSync.merge(emptyList(), mapOf("here:1" to 9L), emptyList(), mapOf("here:1" to 5L)).changed)
    }

    @Test fun touchedSaysWhatChanged() {
        data class L(val id: String, val name: String, val songs: List<String>, val used: Long = 0)
        val same: (L, L) -> Boolean = { a, b -> a.name == b.name && a.songs == b.songs }
        val old = listOf(L("a", "a", listOf("1", "2")), L("b", "b", listOf("1")), L("c", "c", emptyList()))
        val new = listOf(L("a", "a", listOf("2", "1")), L("b", "b", listOf("1"), used = 9), L("d", "d", emptyList()))
        val (changed, gone) = ListsSync.touched(old, new, { it.id }, same)
        assertEquals(setOf("a", "d"), changed)   // reordered; new. b only used, not changed
        assertEquals(setOf("c"), gone)
    }

    @Test fun thePartGoesAcrossWhole() {
        val part = ListsSync.part(listOf(list("here:1", "a", 7, "x", "y")), mapOf("here:9" to 4L))
        val (lists, gone) = ListsSync.read(JSONObject(part.toString()))
        assertEquals(1, lists.size)
        assertEquals("here:1", lists[0].id); assertEquals(7L, lists[0].at)
        assertEquals(listOf("x", "y"), lists[0].songs.map { it.getString("id") })
        assertEquals(mapOf("here:9" to 4L), gone)
    }

    @Test fun theirsMadeIntoSongsHere() {
        // the phone's ListsFile: a list of theirs becomes songs here (one not found here is left to `song` to keep), each once
        data class S(val id: String)
        val mine = listOf(ListsFile.Mine("here:1", "a", listOf(S("x")), used = 3, at = 10))
        val part = ListsSync.part(listOf(list("here:1", "a b", 20, "x", "z", "x"), list("here:2", "new", 15, "q")), emptyMap())
        val (lists, gone) = ListsFile.merged(mine, emptyMap(), part, { JSONObject().put("id", it.id) }, { S(it.getString("id")) }) { it.id }!!
        assertEquals(listOf("here:1", "here:2"), lists.map { it.id })
        assertEquals(listOf("x", "z"), lists[0].songs.map { it.id })
        assertEquals("a b", lists[0].name)
        assertEquals(20L, lists[0].at)
        assertEquals(3L, lists[0].used)   // when a song last went in here, kept
        assertEquals(emptyMap<String, Long>(), gone)
        // nothing newer there: nothing to do
        assertNull(ListsFile.merged(lists, emptyMap(), part, { JSONObject().put("id", it.id) }, { S(it.getString("id")) }) { it.id })
    }

    @Test fun aChangeHereIsStamped() {
        val (one, m) = ListsFile.make(emptyList(), "l", listOf("a", "b"), 1L) { it }
        assertEquals(1L, m.at)
        val (two, gone) = ListsFile.stamped(one, ListsFile.rename(one, m.id, "m"), 50L) { it }
        assertEquals(50L, two[0].at)
        assertTrue(gone.isEmpty())
        val (three, _) = ListsFile.stamped(two, ListsFile.add(two, m.id, listOf("a"), 60L) { it }, 70L) { it }
        assertEquals(50L, three[0].at)   // nothing new in it: not changed
        assertEquals(setOf(m.id), ListsFile.stamped(three, ListsFile.delete(three, m.id), 80L) { it }.second)
        // the time kept in the file
        assertEquals(50L, ListsFile.read(ListsFile.write(three) { JSONObject().put("id", it) }) { it.getString("id") }[0].at)
    }
}
