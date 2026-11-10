package app.ink.music

/**
 * Live DJ sets (2026-11-10): the newest whole sets from four channels that film them, read from each one's Videos tab
 * ([TubeVideos.uploads]): Humano Studios (vinyl sessions), HÖR Berlin (the tiled room), The Lot Radio (New York),
 * Boiler Room and Kiosk Radio (a kiosk in a Brussels park, as eclectic as Humano). A set is twenty minutes or more (a
 * clip, a trailer or a single track isn't); its DJ read from how each channel titles them, its channel said beside it.
 * The channels take turns, each its newest first, in an order new each day: never two of one channel side by side.
 */
object DjSets {
    data class Channel(val id: String, val name: String)

    /** A set: its video, its DJ (or what it is), the channel's name, how many days ago, how long. */
    data class Set(val id: String, val dj: String, val channel: String, val days: Int, val seconds: Int)

    val CHANNELS = listOf(
        Channel("UCKKezBu7Y4onrE3E-xaVpfw", "humano studios"),
        Channel("UCmfF7JZv26UUKyRedViGIlw", "hör berlin"),
        Channel("UCJOtExbMu0RqIdiE4nMUPxQ", "the lot radio"),
        Channel("UCGBpxWJr9FNOcFYA5GkKrMg", "boiler room"),
        Channel("UCal_TyiLk79vN9-OzEzUM6A", "kiosk radio"),
    )

    /** The sets, the channels taking turns (four each at most), twenty in all. */
    fun read(day: Long = 0): List<Set> {
        val each = CHANNELS.map { ch ->
            TubeVideos.uploads(ch.id).filter { it.seconds == 0 || it.seconds >= 20 * 60 }
                .filter { it.seconds >= 20 * 60 || looksLikeASet(it.title) }
                .sortedBy { it.days }.take(4)
                .map { Set(it.id, dj(it.title), ch.name, it.days, it.seconds) }
                .filter { it.dj.isNotBlank() }
        }.filter { it.isNotEmpty() }
        return turns(each, day).take(20)
    }

    /**
     * Each channel's in turn (its newest first), the channels in an order new each `day` (2026-11-10: newest first, then
     * spread, left one channel's older sets together at the end). Where one runs out the rest go on taking turns; only
     * a channel left alone could follow itself.
     */
    fun turns(each: List<List<Set>>, day: Long): List<Set> {
        val order = each.shuffled(kotlin.random.Random(day)).map { it.toMutableList() }.toMutableList()
        val out = ArrayList<Set>()
        while (order.isNotEmpty()) {
            val it = order.iterator()
            while (it.hasNext()) {
                val l = it.next()
                // never the channel just shown, when a round begins where the last one ended
                if (out.lastOrNull()?.channel == l.first().channel && order.size > 1) continue
                out += l.removeAt(0)
                if (l.isEmpty()) it.remove()
            }
        }
        return out
    }

    /** Where YouTube gave no length: its title says it's a set. */
    private fun looksLikeASet(t: String) = Regex("""(?iu)(dj set|live set|session|@thelotradio|\| hör|\| hoer|boiler room)""").containsMatchIn(t)

    /**
     * The DJ, as each channel titles a set: "Johannes Albert | HÖR - November 10 / 2026", "Hewan Aman @TheLotRadio
     * 11-13-2026", "Hannah Laing at Boiler Room LA: Teletech", "Smooth City Pop, Nostalgic Funk [Vinyl Studio Session]
     * with Bobby Ghanou" (the DJ after "with"); else the title, tidied.
     */
    fun dj(title: String): String {
        val t = title.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim()
        Regex("""(?i)\bwith\s+(?:dj\s+)?(.+)$""").find(t)?.let { m -> if ("[" in t || "session" in t.lowercase()) return m.groupValues[1].trim() }
        val cut = listOf(" | HÖR", " | HOR", " @TheLotRadio", " @ The Lot Radio", " at Boiler Room", " | Boiler Room", " | Humano", " @ Kiosk Radio", " @ ")
            .mapNotNull { c -> t.indexOf(c, ignoreCase = true).takeIf { it > 0 } }.minOrNull()
        return (if (cut != null) t.substring(0, cut) else t).trim().trim('|', '-', '–', ' ')
    }
}
