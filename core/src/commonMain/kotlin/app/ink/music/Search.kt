package app.ink.music


/**
 * How find matches what's typed, both apps (2026-10-29): forgiving of
 * case, accents (beyonce finds Beyoncé), "&" for "and", punctuation (acdc finds AC/DC's words, "its" finds It's),
 * and of words in any order or spread over a song's title, artist and record ("demarco salad" finds Salad Days).
 */
object Search {
    private val apart = Regex("[^\\p{L}\\p{N}]+")

    /** Text as it's compared: lowercase, no accents, "&" said "and", apostrophes dropped, other punctuation a space. */
    fun fold(s: String): String =
        // the platform's fold (Platform.kt), named in full: a bare fold(s) here is this one, calling itself for ever
        app.ink.music.fold(s)
            .replace("&", " and ").replace("'", "").replace("’", "")
            .replace(apart, " ").trim()

    /**
     * How well `text` answers `q` (folded): 0 it starts so, 1 a word in it does, 2 it's inside, 3 each word typed
     * starts a word of it, in any order; null, not at all.
     */
    fun rank(text: String, q: String): Int? {
        if (q.isEmpty()) return null
        val t = fold(text)
        return when {
            t.startsWith(q) -> 0
            " $t".contains(" $q") -> 1
            q.length >= 3 && t.contains(q) -> 2
            ' ' in q && words(q).all { w -> " $t".contains(" $w") } -> 3
            // squashed together: "acdc" for "ac dc", "mgmt" as typed
            q.length >= 3 && t.replace(" ", "").startsWith(q.replace(" ", "")) -> 3
            else -> null
        }
    }

    /**
     * The best of several fields (a song's title, then its artist, then its record), each a step lower than the one
     * before; or, every word typed found somewhere among them, after all of those.
     */
    fun rank(q: String, vararg fields: String): Int? {
        if (q.isEmpty()) return null
        fields.withIndex().mapNotNull { (i, f) -> rank(f, q)?.plus(i) }.minOrNull()?.let { return it }
        if (' ' !in q) return null
        val all = " " + fields.joinToString(" ") { fold(it) }
        return if (words(q).all { w -> all.contains(" $w") }) 4 + fields.size else null
    }

    private fun words(q: String) = q.split(' ').filter { it.isNotEmpty() }

    /**
     * What's typed as it's asked of YouTube and the station list (2026-11-17, a tester): as typed, its spaces made one.
     * Not the folded text: folding makes a symbol a space ("ピーナツ ⧸ 可不" and "ピーナツ 可不" fold the same, so the second
     * was never asked) and drops one ("愛℃" folded is "愛", too short to ask).
     */
    fun online(typed: String): String = typed.trim().replace(Regex("\\s+"), " ")

    /** Long enough to ask online: two characters, or one of a script whose single character is a word (CJK, kana, hangul). */
    fun enough(typed: String): Boolean {
        val t = online(typed).replace(" ", "")
        return t.length >= 2 || t.any { it.code >= 0x2E80 }
    }

    /**
     * The words alone, symbols made spaces, case kept: asked as well when it differs from what's typed, as YouTube can
     * answer a title's symbols with nothing ("ピーナツ ⧸ 可不": no videos; "ピーナツ 可不": the song, first).
     */
    fun plain(typed: String): String = online(typed.replace(Regex("[^\\p{L}\\p{N}'’&]+"), " "))

    /**
     * A YouTube video's id in what's typed (2026-11-17, a tester: files named "Artist - Title [VDvX1hzGZN4]"): in square
     * brackets anywhere, or the whole of it alone when it looks like one (a digit, - or _, or both cases: not a word).
     */
    fun tubeId(typed: String): String? {
        val t = typed.trim()
        Regex("\\[([A-Za-z0-9_-]{11})]").find(t)?.let { return it.groupValues[1] }
        if (!Regex("[A-Za-z0-9_-]{11}").matches(t)) return null
        val looks = t.any { it.isDigit() || it == '-' || it == '_' } || (t.drop(1).any { it.isUpperCase() } && t.any { it.isLowerCase() })
        return t.takeIf { looks }
    }

    /** Things found online put in order of how well they answer what's typed (as given where none or all do equally). */
    fun <T> best(typed: String, found: List<T>, vararg fields: (T) -> String): List<T> {
        val q = fold(typed)
        if (q.isEmpty()) return found
        return found.withIndex().sortedBy { (i, x) -> (rank(q, *fields.map { f -> f(x) }.toTypedArray()) ?: 99) * 1000 + i }.map { it.value }
    }
}
