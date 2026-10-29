package app.ink.music

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * The order shuffle plays in. A list played is shuffled as Media3's own does it; songs added to it aren't scattered:
 * Media3's put each at a random place in the shuffle, so with shuffle on "play next" came whenever and "add to queue"
 * anywhere (2026-10-29). Here songs put in after one (play next: after the one playing) come right after it, and
 * songs added at the list's end (add to queue, radio) at the shuffle's end, in the order they came. The song playing
 * last in the list, play next adds at the end too: [next] says (once, for the songs being put in) it was play next.
 */
class Shuffle(
    private val order: IntArray,
    private val random: java.util.Random = java.util.Random(),
    private val next: () -> Boolean = { false },
) : ShuffleOrder {
    /** Where each song of the list is in [order]. */
    private val place = IntArray(order.size).also { p -> order.forEachIndexed { at, i -> p[i] = at } }

    override fun getLength() = order.size
    override fun getNextIndex(index: Int) = place[index].let { if (it + 1 < order.size) order[it + 1] else C.INDEX_UNSET }
    override fun getPreviousIndex(index: Int) = place[index].let { if (it > 0) order[it - 1] else C.INDEX_UNSET }
    override fun getLastIndex() = order.lastOrNull() ?: C.INDEX_UNSET
    override fun getFirstIndex() = order.firstOrNull() ?: C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val asked = next()
        // a new list: shuffled
        if (order.isEmpty()) return Shuffle((0 until insertionCount).shuffled(random).toIntArray(), random, next)
        val added = (insertionIndex until insertionIndex + insertionCount).toList()
        val at = when {
            insertionIndex >= order.size && !asked -> order.size
            insertionIndex == 0 -> 0
            else -> place[insertionIndex - 1] + 1
        }
        val moved = order.map { if (it >= insertionIndex) it + insertionCount else it }
        return Shuffle((moved.take(at) + added + moved.drop(at)).toIntArray(), random, next)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val gone = indexToExclusive - indexFrom
        return Shuffle(order.filter { it !in indexFrom until indexToExclusive }.map { if (it >= indexToExclusive) it - gone else it }.toIntArray(), random, next)
    }

    override fun cloneAndClear(): ShuffleOrder = Shuffle(IntArray(0), random, next)
}
