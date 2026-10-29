package app.ink.music

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleTest {
    /** The list's indices in the order shuffle plays them. */
    private fun played(o: ShuffleOrder) = generateSequence(o.firstIndex.takeIf { it != C.INDEX_UNSET }) { o.getNextIndex(it).takeIf { n -> n != C.INDEX_UNSET } }.toList()

    @Test fun aNewListIsShuffled() {
        val o = Shuffle(IntArray(0), java.util.Random(1)).cloneAndInsert(0, 20)
        assertEquals((0 until 20).toSet(), played(o).toSet())
        assert(played(o) != (0 until 20).toList())
    }

    @Test fun playNextComesNext() {
        var asked = true
        var o: ShuffleOrder = Shuffle(intArrayOf(3, 0, 4, 1, 2), next = { asked.also { asked = false } })
        // playing list song 4 (third in the shuffle, last in the list): play next puts one in at 5, the list's end
        o = o.cloneAndInsert(5, 1)
        assertEquals(listOf(3, 0, 4, 5, 1, 2), played(o))
        // the same, not asked to play next (add to queue): the shuffle's end
        assertEquals(listOf(3, 0, 4, 1, 2, 5), played(Shuffle(intArrayOf(3, 0, 4, 1, 2)).cloneAndInsert(5, 1)))
        // playing 0: another after it, at 1, the rest moved up one
        o = o.cloneAndInsert(1, 1)
        assertEquals(listOf(4, 0, 1, 5, 6, 2, 3), played(o))
    }

    @Test fun addToQueueComesLast() {
        val o = Shuffle(intArrayOf(2, 0, 1)).cloneAndInsert(3, 1).cloneAndInsert(4, 2)
        assertEquals(listOf(2, 0, 1, 3, 4, 5), played(o))
    }

    @Test fun removed() {
        val o = Shuffle(intArrayOf(3, 0, 4, 1, 2)).cloneAndRemove(1, 3)
        assertEquals(listOf(1, 0, 2), played(o))
        assertEquals(C.INDEX_UNSET, o.getPreviousIndex(1))
    }
}
