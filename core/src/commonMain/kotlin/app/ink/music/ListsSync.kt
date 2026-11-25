package app.ink.music

/**
 * Your own playlists kept in step between the phone and the computer (2026-11-24), in the same exchange as loves
 * ([Sync]): each list goes across whole (its name, its songs in order as [Sync.song] tells them, when it last changed),
 * and each list is the one changed last, wherever that was. A list deleted leaves a mark (its id, when), kept in
 * lists-gone.json beside lists.json, so the other's copy isn't sent back: a deletion is a change like any other, the
 * latest winning (a list changed on the other after it was deleted here comes back, changed). Older files' lists have
 * no time (0): any change or deletion since beats them.
 * Here only what's the same on both: each app turns its own songs into these and back.
 */
object ListsSync {
    /** A list as it goes across: its songs as [Sync.song] gives them. */
    data class Wired(val id: String, val name: String, val songs: List<JSONObject>, val at: Long)

    /** A list as it is after a merge: whose it is now (theirs: to be made into this side's songs and kept). */
    data class Kept(val list: Wired, val theirs: Boolean)

    /** The merge: the lists in order, the deletions known to either, and whether anything here changed. */
    data class Merged(val lists: List<Kept>, val gone: Map<String, Long>, val changed: Boolean)

    /**
     * Mine and theirs as one. For each list the newest of mine, theirs and a deletion of it wins (a tie stays as it
     * is here; a deletion wins a tie with a list). Mine keep their order; ones only they had follow, in theirs.
     */
    fun merge(mine: List<Wired>, myGone: Map<String, Long>, theirs: List<Wired>, theirGone: Map<String, Long>): Merged {
        val gone = HashMap(myGone)
        theirGone.forEach { (id, at) -> if (at > (gone[id] ?: Long.MIN_VALUE)) gone[id] = at }
        val theirsById = theirs.associateBy { it.id }
        val out = ArrayList<Kept>()
        var changed = gone.keys != myGone.keys || gone.any { (id, at) -> myGone[id] != at }
        for (m in mine) {
            val t = theirsById[m.id]
            val best = if (t != null && t.at > m.at) Kept(t, true) else Kept(m, false)
            val deleted = gone[m.id]
            if (deleted != null && deleted >= best.list.at) { changed = true; continue }
            if (best.theirs) changed = true
            out += best
        }
        val have = mine.mapTo(HashSet()) { it.id }
        for (t in theirs) {
            if (t.id in have) continue
            val deleted = gone[t.id]
            if (deleted != null && deleted >= t.at) continue
            out += Kept(t, true); changed = true
        }
        return Merged(out, gone, changed)
    }

    /**
     * Which lists a change here touched: those new or not as they were (to be stamped with now), and those no longer
     * there (deleted: a mark each). `same` says whether a list is as it was (its name and songs; not when it was used).
     */
    fun <L> touched(old: List<L>, new: List<L>, id: (L) -> String, same: (L, L) -> Boolean): Pair<Set<String>, Set<String>> {
        val before = old.associateBy(id)
        val now = new.mapTo(HashSet(), id)
        val changed = new.filter { l -> before[id(l)]?.let { !same(it, l) } ?: true }.mapTo(HashSet(), id)
        val gone = before.keys.filterTo(HashSet()) { it !in now }
        return changed to gone
    }

    // ---- on the wire ----

    /** This side's part: {lists: [{id, name, songs, at}], gone: {id: at}}. */
    fun part(lists: List<Wired>, gone: Map<String, Long>): JSONObject {
        val g = JSONObject(); gone.forEach { (id, at) -> g.put(id, at) }
        return JSONObject().put("lists", JSONArray(lists.map { l ->
            JSONObject().put("id", l.id).put("name", l.name).put("songs", JSONArray(l.songs)).put("at", l.at)
        })).put("gone", g)
    }

    /** The other's part read back; a list without an id passed over. */
    fun read(part: JSONObject): Pair<List<Wired>, Map<String, Long>> {
        val a = part.optJSONArray("lists") ?: JSONArray()
        val lists = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            val s = o.optJSONArray("songs") ?: JSONArray()
            Wired(id, o.optString("name").ifBlank { "playlist" }, (0 until s.length()).mapNotNull { s.optJSONObject(it) }, o.optLong("at"))
        }
        return lists to gones(part.optJSONObject("gone"))
    }

    private fun gones(o: JSONObject?): Map<String, Long> {
        val out = HashMap<String, Long>()
        o?.keys()?.forEach { k -> out[k] = o.optLong(k) }
        return out
    }

    // ---- the deletions, kept beside the lists ----

    /** lists-gone.json in the app's folder: each deleted list's id and when. */
    class Gone(private val dir: File) {
        private val file get() = File(dir, "lists-gone.json")

        fun read(): Map<String, Long> = runCatching { gones(JSONObject(file.readText())) }.getOrDefault(emptyMap())

        fun write(gone: Map<String, Long>) {
            val o = JSONObject(); gone.forEach { (id, at) -> o.put(id, at) }
            runCatching { Keep.write(file, o.toString()) }
        }

        /** These lists deleted now. */
        fun add(ids: Collection<String>, at: Long) {
            if (ids.isEmpty()) return
            write(read() + ids.associateWith { at })
        }
    }
}
