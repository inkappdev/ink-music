package app.ink.music

import java.text.Normalizer

/**
 * How find matches what's typed, both apps (Bronson, 2026-10-29: "very fully featured everywhere"): forgiving of
 * case, accents (beyonce finds Beyoncé), "&" for "and", punctuation (acdc finds AC/DC's words, "its" finds It's),
 * and of words in any order or spread over a song's title, artist and record ("demarco salad" finds Salad Days).
 */
object Search {
    private val marks = Regex("\\p{Mn}+")
    private val apart = Regex("[^\\p{L}\\p{N}]+")

    /** Text as it's compared: lowercase, no accents, "&" said "and", apostrophes dropped, other punctuation a space. */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "")
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
}
