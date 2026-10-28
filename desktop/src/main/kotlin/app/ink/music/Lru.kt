package app.ink.music

/** The last few things kept, up to `max` by `sizeOf` (Android's LruCache, which the desktop hasn't). */
class Lru<K, V>(private val max: Int, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<K, V>(16, .75f, true)
    private var size = 0

    @Synchronized fun get(key: K): V? = map[key]

    @Synchronized fun put(key: K, value: V) {
        map.put(key, value)?.let { size -= sizeOf(it) }
        size += sizeOf(value)
        val it = map.entries.iterator()
        while (size > max && it.hasNext()) { val e = it.next(); if (e.key == key) continue; size -= sizeOf(e.value); it.remove() }
    }
}
