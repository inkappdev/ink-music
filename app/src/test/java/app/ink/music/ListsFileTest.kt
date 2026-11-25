package app.ink.music

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your own playlists as kept (ListsFile): the computer's HereLists' shape, and every change made to one. */
class ListsFileTest {
    private data class T(val id: String, val title: String = id)

    private val key: (T) -> String = { it.id }
    private fun json(t: T) = JSONObject().put("id", t.id).put("title", t.title)
    private fun of(o: JSONObject) = T(o.getString("id"), o.getString("title"))

    @Test fun writtenAndReadBack() {
        val (one, m) = ListsFile.make(emptyList(), "  rock  ", listOf(T("a"), T("b"), T("a")), 1_000L, key)
        val text = ListsFile.write(one, ::json)
        val back = ListsFile.read(text, ::of)
        assertEquals(listOf(m), back)
        assertEquals("rock", back[0].name)
        assertEquals(listOf("a", "b"), back[0].songs.map { it.id })
        // the computer's file: a list of {id, name, songs}, ids "here:" and a number
        val o = JSONArray(text).getJSONObject(0)
        assertEquals("here:1000", o.getString("id"))
        assertEquals(2, o.getJSONArray("songs").length())
    }

    @Test fun theComputersFileReads() {
        // as HereLists writes it: no "used"; a song that can't be read is left out, the rest kept
        val text = """[{"id":"here:5","name":"pop","songs":[{"id":"x","title":"one"},{"nope":1},{"id":"y","title":"two"}]}]"""
        val back = ListsFile.read(text, ::of)
        assertEquals(1, back.size)
        assertEquals(listOf("x", "y"), back[0].songs.map { it.id })
        assertEquals(0L, back[0].used)
        assertEquals(emptyList<ListsFile.Mine<T>>(), ListsFile.read("not json", ::of))
    }

    @Test fun madeTwiceInOneMomentApart() {
        val (one, a) = ListsFile.make(emptyList(), "a", emptyList(), 7L, key)
        val (_, b) = ListsFile.make(one, "b", emptyList(), 7L, key)
        assertNotEquals(a.id, b.id)
        assertEquals("playlist", ListsFile.make(emptyList(), " ", emptyList(), 1L, key).second.name)
    }

    @Test fun addEachOnceAtTheEnd() {
        val (one, m) = ListsFile.make(emptyList(), "l", listOf(T("a")), 1L, key)
        val two = ListsFile.add(one, m.id, listOf(T("b"), T("a"), T("c")), 9L, key)
        assertEquals(listOf("a", "b", "c"), two[0].songs.map { it.id })
        assertEquals(9L, two[0].used)
    }

    @Test fun removeByPlace() {
        val (one, m) = ListsFile.make(emptyList(), "l", listOf(T("a"), T("b"), T("c")), 1L, key)
        assertEquals(listOf("a", "c"), ListsFile.removeAt(one, m.id, 1)[0].songs.map { it.id })
        assertEquals(listOf("a", "b", "c"), ListsFile.removeAt(one, m.id, 9)[0].songs.map { it.id })
    }

    @Test fun moved() {
        val (one, m) = ListsFile.make(emptyList(), "l", listOf(T("a"), T("b"), T("c"), T("d")), 1L, key)
        assertEquals(listOf("b", "c", "a", "d"), ListsFile.move(one, m.id, 0, 2)[0].songs.map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), ListsFile.move(one, m.id, 3, 0)[0].songs.map { it.id })
        // out of its range: as it was
        assertEquals(listOf("a", "b", "c", "d"), ListsFile.move(one, m.id, 0, 4)[0].songs.map { it.id })
    }

    @Test fun renamedAndDeleted() {
        val (one, a) = ListsFile.make(emptyList(), "a", emptyList(), 1L, key)
        val (two, b) = ListsFile.make(one, "b", emptyList(), 2L, key)
        assertEquals("new", ListsFile.rename(two, a.id, " new ").first { it.id == a.id }.name)
        assertEquals("a", ListsFile.rename(two, a.id, "  ").first { it.id == a.id }.name)
        assertEquals(listOf(b.id), ListsFile.delete(two, a.id).map { it.id })
    }

    @Test fun latestAddedToFirst() {
        val (one, a) = ListsFile.make(emptyList(), "a", emptyList(), 1L, key)
        val (two, b) = ListsFile.make(one, "b", emptyList(), 2L, key)
        val (three, c) = ListsFile.make(two, "c", emptyList(), 3L, key)
        assertEquals(listOf(c.id, b.id, a.id), ListsFile.latest(three).map { it.id })
        val added = ListsFile.add(three, a.id, listOf(T("x")), 10L, key)
        assertEquals(a.id, ListsFile.latest(added).first().id)
        assertTrue(ListsFile.latest(added).size == 3)
    }
}
