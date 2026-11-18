package app.ink.music

/**
 * Live sessions (2026-11-18, Bronson: "start with live sessions"): bands filmed playing live in a room, from the channels
 * that make them: KEXP, NPR's Tiny Desk, COLORS, Audiotree, La Blogothèque, Paste, Jam in the Van, The Current, WFUV,
 * Vevo DSCVR and triple j's Like A Version. Read from each one's Videos tab ([TubeVideos.uploads]); each title read as
 * the channel writes it into who played and what ("full session" for a whole set). What isn't a performance (a short,
 * an interview, a trailer, an award) is left out. Sofar Sounds (interviews and covers now), OurVinyl (episodes) and
 * BalconyTV (nothing new in years) were tried and left out.
 */
object Sessions {
    /** A channel, its name, and how a session of theirs is said under it ("live on kexp"). */
    data class Channel(val id: String, val name: String, val says: String)

    /** A session: its video, who played, what (a song, or "full session"), how it's said, days ago, how long. */
    data class Session(val id: String, val artist: String, val what: String, val says: String, val days: Int, val seconds: Int)

    val CHANNELS = listOf(
        Channel("UC3I2GFN_F8WudD_2jUZbojA", "kexp", "live on kexp"),
        Channel("UC4eYXhJI4-7wSWc8UNRwD4A", "tiny desk", "a tiny desk concert"),
        Channel("UC2Qw1dzXDBAZPwS7zm37g8g", "colors", "a colors show"),
        Channel("UCWjmAUHmajb1-eo5WKk_22A", "audiotree", "live on audiotree"),
        Channel("UCFAKGci5lneha2x4XMbzYrQ", "la blogothèque", "a take away show"),
        Channel("UC_PscE_7n8SiMspieMGMK_A", "paste", "a paste session"),
        Channel("UCwfYU2M8TBAus0TIaNtjxlQ", "jam in the van", "jam in the van"),
        Channel("UCAKcSON6PSG_JkTbXh2WdIg", "the current", "live at the current"),
        Channel("UCCpd6qvE03uwMqoHwMfulSg", "wfuv", "live at wfuv"),
        Channel("UC-7BJPPk_oQGTED1XQA_DTw", "vevo dscvr", "vevo dscvr"),
        Channel("UCd2KNtfphz8HvYzM4pwtHmg", "triple j", "like a version"),
    )

    /** Each channel's newest page (about thirty videos), or `deep`, a few pages back; the channels in turn. */
    fun read(day: Long = 0, deep: Boolean = false): List<Session> {
        val each = CHANNELS.map { ch ->
            TubeVideos.uploads(ch.id, if (deep) 4 else 1).mapNotNull { parse(it.title, ch, it.id, it.days, it.seconds) }.sortedBy { it.days }
        }.filter { it.isNotEmpty() }
        // the channels in turn, each its newest first, in an order new each day
        val order = each.shuffled(kotlin.random.Random(day)).map { it.toMutableList() }.toMutableList()
        val out = ArrayList<Session>()
        while (order.isNotEmpty()) {
            val it = order.iterator()
            while (it.hasNext()) { val l = it.next(); out += l.removeAt(0); if (l.isEmpty()) it.remove() }
        }
        return out
    }

    private val NOT = Regex("""(?i)\b(interview|trailer|teaser|class introductions?|introducing|behind|wins|award|podcast|episode|announce|q&a|reacts?|in conversation|talks?|story behind|preview)\b""")
    private val FULL = Regex("""(?i)\b(full (performance|session|set|concert)|tiny desk concert)\b""")
    // how each channel dresses a title, taken off: "(Live on KEXP)", "| A COLORS SHOW", "| Audiotree Live", "| Paste",
    // "(live in The Current studio)", "(Live at WFUV)", "| Vevo DSCVR ...", "| Jam in the Van | Alt Indie Rock (Full Set 2026)"
    private val DRESS = listOf(
        Regex("""(?i)\s*\((live (on|at|in|for) [^)]*)\)"""), Regex("""(?i)\s*\|\s*a colors (show|encore)"""),
        Regex("""(?i)\s*\|\s*audiotree live.*$"""), Regex("""(?i)\s+on audiotree live.*$"""), Regex("""(?i)\s*\|\s*paste\s*$"""),
        Regex("""(?i)\s*\|\s*vevo dscvr.*$"""), Regex("""(?i)\s*\|\s*jam in the van.*$"""), Regex("""(?i)\s*\|\s*(a take away show|live session).*$"""),
        Regex("""(?i)\s*for like a version.*$"""),
    )

    /** A video as a session, or null when it isn't one (too short, an interview, a trailer...). */
    fun parse(title: String, ch: Channel, id: String, days: Int, seconds: Int): Session? {
        val t = title.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim()
        if (seconds in 1..89 || NOT.containsMatchIn(t)) return null
        val full = FULL.containsMatchIn(t) || seconds >= 12 * 60
        // Tiny Desk: "Tove Lo: Tiny Desk Concert" (the channel's other videos aren't sessions)
        if (ch.name == "tiny desk") {
            if (!t.contains("tiny desk", ignoreCase = true)) return null
            val who = t.substringBefore(":").trim()
            return if (who.isEmpty() || who.length > 60) null else Session(id, who, "tiny desk concert", ch.says, days, seconds)
        }
        // triple j: Like A Version and its live takes only: "Sly Withers cover Gotye’s ‘Somebody...’ for Like A Version"
        if (ch.name == "triple j" && !Regex("""(?i)like a version|live for triple j|live at""").containsMatchIn(t)) return null
        // a series' name before a colon ("NY Slice Sessions: Blanket Approval")
        var s = if (Regex("""(?i)^[^:]*sessions?:\s""").containsMatchIn(t)) t.substringAfter(":").trim() else t
        DRESS.forEach { s = s.replace(it, "") }
        s = s.replace(FULL, "").trim().trim('-', '–', '—', '|', ':', ' ')
        val cover = Regex("""(?i)^(.+?)\s+covers?\s+(.+)$""").find(s)
        val (who, what) = when {
            cover != null -> cover.groupValues[1] to "covering " + cover.groupValues[2]
            else -> {
                val parts = s.split(Regex("""\s+[-–—|]\s+""")).map { it.trim().trim('\'', '"', '‘', '’', '“', '”') }.filter { it.isNotEmpty() }
                (parts.getOrNull(0).orEmpty()) to (parts.getOrNull(1) ?: "")
            }
        }
        if (who.isBlank() || who.length > 60) return null
        // a series' name where a song would be ("Ye Vagabonds | The Sounds of Ireland"): a session, not a song
        val series = Regex("""(?i)\b(the sounds of|sessions?|series)\b""").containsMatchIn(what)
        return Session(id, who.trim(), if (full || series || what.isBlank()) "full session" else what.trim(), ch.says, days, seconds)
    }
}
