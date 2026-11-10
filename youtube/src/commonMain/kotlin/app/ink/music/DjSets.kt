package app.ink.music

/**
 * Live DJ sets (2026-11-10): the newest whole sets from four channels that film them, read from each one's Videos tab
 * ([TubeVideos.uploads]): Humano Studios (vinyl sessions), HÖR Berlin (the tiled room), The Lot Radio (New York) and
 * Boiler Room. A set is twenty minutes or more (a clip, a trailer or a single track isn't); its DJ read from how each
 * channel titles them, its channel said beside it. Newest first, never two of one channel side by side.
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
    )

    /** The sets, newest first, a few from each channel (four each at most), sixteen in all. */
    fun read(): List<Set> {
        val all = CHANNELS.flatMap { ch ->
            TubeVideos.uploads(ch.id).filter { it.seconds == 0 || it.seconds >= 20 * 60 }
                .filter { it.seconds >= 20 * 60 || looksLikeASet(it.title) }
                .sortedBy { it.days }.take(4)
                .map { Set(it.id, dj(it.title), ch.name, it.days, it.seconds) }
                .filter { it.dj.isNotBlank() }
        }
        return apart(all.sortedBy { it.days }.take(16))
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
        val cut = listOf(" | HÖR", " | HOR", " @TheLotRadio", " @ The Lot Radio", " at Boiler Room", " | Boiler Room", " | Humano", " @ ")
            .mapNotNull { c -> t.indexOf(c, ignoreCase = true).takeIf { it > 0 } }.minOrNull()
        return (if (cut != null) t.substring(0, cut) else t).trim().trim('|', '-', '–', ' ')
    }

    /** In its order, but never two of one channel side by side. */
    private fun apart(list: List<Set>): List<Set> {
        val left = list.toMutableList()
        val out = ArrayList<Set>(list.size)
        while (left.isNotEmpty()) {
            val before = out.lastOrNull()?.channel
            val i = left.indexOfFirst { it.channel != before }.takeIf { it >= 0 } ?: 0
            out += left.removeAt(i)
        }
        return out
    }
}
