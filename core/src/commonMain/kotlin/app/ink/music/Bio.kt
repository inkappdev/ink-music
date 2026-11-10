package app.ink.music

/**
 * An artist's bio as YouTube Music gives it, its source line taken off (2026-11-08, Bronson: "get rid of or hide nicer
 * the from wikipedia"): "From Wikipedia (https://en.wikipedia.org/wiki/...) under Creative Commons Attribution CC-BY-SA
 * 3.0 (http://...)" is a paragraph at its end. The text, and where it's from ("wikipedia") for a quiet credit under it:
 * the licence asks for one.
 */
object Bio {
    /**
     * A bio's opening pulled out big, and the rest (both apps' artist pages): its first sentence; one too long for big
     * type (over 180) ends at its natural break instead, a colon, a dash, a semicolon, else a comma, the rest carrying on
     * small. 2026-11-09: a long first sentence (Grumpy's, 210) left the whole bio in the small font.
     */
    fun pullQuote(about: String): Pair<String?, String> {
        val text = about.trim()
        if (text.isEmpty()) return null to text
        val end = Regex("""[.!?](?=\s+[A-Z“"(])""").find(text)?.range?.last ?: (text.length - 1)
        val first = text.substring(0, end + 1).trim()
        // a short one all of it ("ZIRP!", "Dopamine Chamber. Out Now.": a record's promotion), big
        if (first.length < 20 && text.length <= 80) return text to ""
        if (first.length in 20..180) return first to text.substring(end + 1).trim()
        // the break nearest 140 in (30 at the least), the strongest kind first
        fun breakAt(chars: String): Int? = text.withIndex().filter { (i, c) -> c in chars && i in 30..140 }.map { it.index }.maxOrNull()
        val at = breakAt(":") ?: breakAt("—–;") ?: breakAt(",") ?: return null to text
        return text.substring(0, at + 1).trim() to text.substring(at + 1).trim()
    }

    fun of(about: String): Pair<String, String?> {
        val at = Regex("""(?:^|\n)\s*From Wikipedia\b""").find(about) ?: return about.trim() to null
        return about.substring(0, at.range.first).trim() to "wikipedia"
    }
}
