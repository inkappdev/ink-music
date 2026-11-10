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
        val at = Regex("""(?:^|\n)\s*From (Wikipedia|Last\.fm)\b""").find(about) ?: return about.trim() to null
        return about.substring(0, at.range.first).trim() to at.groupValues[1].lowercase()
    }

    /** Too little of YouTube's own to be an about ("ZIRP!", nothing): one found elsewhere is wanted. */
    fun scant(about: String): Boolean = of(about).first.length < 80

    /**
     * A bio found for an artist YouTube has none (or a scrap) for (2026-11-09, Bronson: "find one, then facts"):
     * Wikipedia's opening, only from a page that says it's a musician's and whose name is theirs (never the wrong
     * Wednesday), else Last.fm's (not a page for several artists of one name). With its source line at the end, as
     * YouTube's come, so [of] credits it. Null: nothing found. Blocking: one or two reads.
     */
    fun find(name: String, lastFmKey: String): String? =
        runCatching { wikipedia(name) }.getOrNull()?.let { "$it\n\nFrom Wikipedia" }
            ?: lastFmKey.takeIf { it.isNotEmpty() }?.let { k -> runCatching { lastFm(name, k) }.getOrNull() }?.let { "$it\n\nFrom Last.fm" }

    private val MUSICIAN = Regex("\\b(band|musician|singer|songwriter|rapper|producer|dj|duo|trio|group|composer|musical|record producer|vocalist|guitarist|pianist|hip hop|rock|pop|electronic|collective|artist)\\b", RegexOption.IGNORE_CASE)

    private fun plain(s: String) = Curation.folded(s).replace(Regex("\\s*\\(.*?\\)"), "").filter { it.isLetterOrDigit() }

    private fun wikipedia(name: String): String? {
        val want = plain(name)
        // the name alone, then with what it'd be ("Wednesday" finds the day long before "Wednesday (band)")
        val page = listOf(name, "$name band", "$name musician").firstNotNullOfOrNull { q ->
            val found = JSONObject(Curation.get("https://en.wikipedia.org/w/rest.php/v1/search/page?q=${urlEncode(q)}&limit=8")).optJSONArray("pages") ?: return@firstNotNullOfOrNull null
            (0 until found.length()).map { found.getJSONObject(it) }.firstOrNull { p ->
                plain(p.optString("title")) == want && MUSICIAN.containsMatchIn(p.optString("description"))
            }
        } ?: return null
        val sum = JSONObject(Curation.get("https://en.wikipedia.org/api/rest_v1/page/summary/${urlEncode(page.getString("key"))}"))
        if (sum.optString("type") == "disambiguation") return null
        return sum.optString("extract").trim().takeIf { it.length >= 80 }
    }

    private fun lastFm(name: String, key: String): String? {
        val o = JSONObject(Curation.get("https://ws.audioscrobbler.com/2.0/?method=artist.getinfo&artist=${urlEncode(name)}&autocorrect=0&api_key=$key&format=json"))
        val text = o.optJSONObject("artist")?.optJSONObject("bio")?.optString("content").orEmpty()
            .substringBefore("<a href").substringBefore("User-contributed text").replace(Regex("<[^>]+>"), "").trim()
        // a page for several of one name ("There is more than one artist with this name", "Grumpy is the name of several
        // artists"): which is theirs can't be told
        if (Regex("more than one artist|multiple artists|several artists|name of (several|multiple)|there are (two|three|several|many)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text.take(160))) return null
        return text.takeIf { it.length >= 80 }
    }

    /**
     * With no bio found anywhere: a line ink writes from what it knows, nothing made up. Big: their records, and the
     * critics who picked them; small: who fans also like, and how many listen. Null with too little to say.
     */
    fun facts(records: Int, singles: Int, pickedBy: List<String>, like: List<String>, audience: String): String? {
        fun count(n: Int, one: String) = when (n) { 0 -> null; 1 -> "one $one"; else -> "${words(n)} ${one}s" }
        fun and(xs: List<String>) = if (xs.size <= 1) xs.joinToString() else xs.dropLast(1).joinToString(", ") + " and " + xs.last()
        val made = listOfNotNull(count(records, "record"), count(singles, "single")).joinToString(" and ")
        val big = when {
            made.isNotEmpty() && pickedBy.isNotEmpty() -> "${made.replaceFirstChar { it.uppercase() }}, picked by ${and(pickedBy.take(3))}."
            made.isNotEmpty() -> "${made.replaceFirstChar { it.uppercase() }} so far."
            pickedBy.isNotEmpty() -> "Picked by ${and(pickedBy.take(3))}."
            else -> null
        }
        val small = listOfNotNull(
            like.take(3).takeIf { it.isNotEmpty() }?.let { "Fans also like ${and(it)}." },
            audience.trim().takeIf { it.isNotEmpty() }?.let { a -> a.replace(Regex("(?i)monthly (audience|listeners)"), "listen a month").trim().let { "$it." } },
        ).joinToString(" ")
        if (big == null && small.isEmpty()) return null
        return listOfNotNull(big, small.takeIf { it.isNotEmpty() }).joinToString(" ")
    }

    private fun words(n: Int) = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").getOrNull(n) ?: "$n"
}
