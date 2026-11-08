package app.ink.music

/** The last few things kept, up to `max` by `sizeOf` (Android's LruCache, which the iPhone hasn't). Screens' thread only. */
class Lru<K, V>(private val max: Int, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<K, V>()
    private var size = 0

    // the one asked for moved to the newest end, so the oldest unasked go first
    fun get(key: K): V? = map.remove(key)?.also { map[key] = it }

    fun put(key: K, value: V) {
        map.remove(key)?.let { size -= sizeOf(it) }
        map[key] = value
        size += sizeOf(value)
        while (size > max && map.size > 1) {
            val oldest = map.keys.first()
            if (oldest == key) break
            size -= sizeOf(map.remove(oldest)!!)
        }
    }
}
