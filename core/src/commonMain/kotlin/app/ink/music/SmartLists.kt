package app.ink.music

import kotlin.random.Random

/**
 * Smart playlists (2026-11-10, after Prism's): lists that fill themselves from rules, both apps. Each rule is one kind
 * (loved, tempo, played, last played, added, genre, where from, length) set to one of a few choices, as the sort words
 * are; every rule must hold. Then an order and how many. Read again each time a list is opened, so it keeps up with
 * what's played, loved and added. Songs whose tempo isn't measured yet (never mixed or read) don't meet a tempo rule.
 */
object SmartLists {
    /** A rule's kind and its choices, said as words: the kind, then each choice's word. */
    enum class Kind(val word: String, val choices: List<String>) {
        LOVED("loved", listOf("loved", "not loved")),
        TEMPO("tempo", listOf("slow", "medium", "fast")),
        PLAYED("played", listOf("never", "a few times", "a lot")),
        LAST("last played", listOf("this week", "not in a month", "not in six months")),
        ADDED("added", listOf("this month", "this year")),
        GENRE("genre", emptyList()),
        FROM("from", listOf("this phone", "your server", "youtube", "kept offline")),
        LENGTH("length", listOf("short", "long")),
    }

    enum class Order(val word: String) { SHUFFLED("shuffled"), MOST("most played"), NEWEST("newest in"), SLOWEST("slowest first") }

    /** A smart playlist: its name, its rules (a kind to its choice), its order and how many (0: all). */
    data class SmartList(val id: String, val name: String, val rules: Map<Kind, String>, val order: Order = Order.SHUFFLED, val most: Int = 50)

    /** What's known of a song, from the app: its id, loved, plays, last played and added (ms, 0 unknown), tempo (0 unknown), length (ms), where from. */
    interface Facts<S> {
        fun id(s: S): String
        fun loved(s: S): Boolean
        fun plays(s: S): Int
        fun last(s: S): Long
        fun added(s: S): Long
        fun bpm(s: S): Double
        fun length(s: S): Long
        /** "this phone" (or "this computer"), "your server", "youtube", with "kept offline" asked apart. */
        fun from(s: S): String
        fun kept(s: S): Boolean
        fun genre(s: S, g: String): Boolean
    }

    /** Ready to tap: a name and its rules, as Prism's starters. */
    val STARTERS = listOf(
        SmartList("", "fast and loved", mapOf(Kind.LOVED to "loved", Kind.TEMPO to "fast")),
        SmartList("", "slow and loved", mapOf(Kind.LOVED to "loved", Kind.TEMPO to "slow")),
        SmartList("", "forgotten favorites", mapOf(Kind.PLAYED to "a lot", Kind.LAST to "not in six months"), Order.MOST),
        SmartList("", "never played", mapOf(Kind.PLAYED to "never")),
        SmartList("", "new in", mapOf(Kind.ADDED to "this month"), Order.NEWEST),
        SmartList("", "long ones", mapOf(Kind.LENGTH to "long")),
    )

    private const val DAY = 86_400_000L

    /** A tempo as a DJ counts it: halved or doubled into 85 to 170. */
    private fun fold(bpm: Double): Double { var b = bpm; while (b < 85) b *= 2; while (b >= 170) b /= 2; return b }

    /** Whether a song meets one rule. */
    fun <S> meets(s: S, kind: Kind, choice: String, f: Facts<S>, now: Long): Boolean = when (kind) {
        Kind.LOVED -> f.loved(s) == (choice == "loved")
        Kind.TEMPO -> f.bpm(s).takeIf { it > 0 }?.let { fold(it) }?.let { b ->
            when (choice) { "slow" -> b < 100; "medium" -> b in 100.0..125.0; else -> b > 125 } } ?: false
        Kind.PLAYED -> f.plays(s).let { n -> when (choice) { "never" -> n == 0; "a few times" -> n in 1..4; else -> n >= 5 } }
        Kind.LAST -> f.last(s).let { l -> when (choice) {
            "this week" -> l > 0 && now - l < 7 * DAY
            "not in a month" -> l > 0 && now - l > 30 * DAY
            else -> l > 0 && now - l > 182 * DAY } }
        Kind.ADDED -> f.added(s).let { a -> a > 0 && now - a < (if (choice == "this month") 31 else 365) * DAY }
        Kind.GENRE -> f.genre(s, choice)
        Kind.FROM -> if (choice == "kept offline") f.kept(s) else f.from(s) == choice || (choice == "this phone" && f.from(s) == "this computer")
        Kind.LENGTH -> f.length(s).let { l -> l > 0 && (if (choice == "short") l < 3 * 60_000 else l > 6 * 60_000) }
    }

    /** The list's songs from `pool`: every rule held, in its order, as many as it keeps. A shuffle's order steady for a day. */
    fun <S> pick(pool: List<S>, list: SmartList, f: Facts<S>, now: Long): List<S> {
        val all = pool.filter { s -> list.rules.all { (k, c) -> meets(s, k, c, f, now) } }.distinctBy { f.id(it) }
        val ordered = when (list.order) {
            Order.SHUFFLED -> all.shuffled(Random(list.id.hashCode().toLong() * 31 + now / DAY))
            Order.MOST -> all.sortedByDescending { f.plays(it) }
            Order.NEWEST -> all.sortedByDescending { f.added(it) }
            Order.SLOWEST -> all.sortedBy { f.bpm(it).takeIf { b -> b > 0 }?.let { b -> fold(b) } ?: Double.MAX_VALUE }
        }
        return if (list.most > 0) ordered.take(list.most) else ordered
    }

    /** What its rules say, in words: "loved · fast · not in a month". */
    fun said(list: SmartList): String = list.rules.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { it.value }

    // ---- kept as JSON ----

    fun save(lists: List<SmartList>): String = JSONArray().apply {
        lists.forEach { l ->
            put(JSONObject().put("id", l.id).put("name", l.name).put("order", l.order.name).put("most", l.most)
                .put("rules", JSONObject().apply { l.rules.forEach { (k, v) -> put(k.name, v) } }))
        }
    }.toString()

    fun load(text: String): List<SmartList> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val r = o.optJSONObject("rules")
            val rules = r?.keys()?.asSequence()?.mapNotNull { k -> runCatching { Kind.valueOf(k) to r.getString(k) }.getOrNull() }?.toMap().orEmpty()
            SmartList(o.getString("id"), o.getString("name"), rules,
                runCatching { Order.valueOf(o.optString("order")) }.getOrDefault(Order.SHUFFLED), o.optInt("most", 50))
        }
    }.getOrDefault(emptyList())
}
