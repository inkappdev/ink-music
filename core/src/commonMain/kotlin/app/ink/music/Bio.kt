package app.ink.music

/**
 * An artist's bio as YouTube Music gives it, its source line taken off (2026-11-08, Bronson: "get rid of or hide nicer
 * the from wikipedia"): "From Wikipedia (https://en.wikipedia.org/wiki/...) under Creative Commons Attribution CC-BY-SA
 * 3.0 (http://...)" is a paragraph at its end. The text, and where it's from ("wikipedia") for a quiet credit under it:
 * the licence asks for one.
 */
object Bio {
    fun of(about: String): Pair<String, String?> {
        val at = Regex("""(?:^|\n)\s*From Wikipedia\b""").find(about) ?: return about.trim() to null
        return about.substring(0, at.range.first).trim() to "wikipedia"
    }
}
