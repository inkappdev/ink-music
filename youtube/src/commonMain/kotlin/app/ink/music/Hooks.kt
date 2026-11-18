package app.ink.music

import kotlin.math.max

/**
 * Where a song's best part is, for TUNER's clips (2026-11-18): YouTube's "most replayed" (a video's watch page gives a
 * hundred marks along it, each how often that moment's played again; signed out, a plain web client), past its first
 * tenth (people start a song over: Rick Astley's peak is 0:00), the best few seconds' worth. Only videos watched enough
 * have it (9 of 30 of Fresh New Indie's, 2026-11-18, about 130,000 views and up); then the chorus, by its words: the
 * line sung most, where it's first sung.
 */
object Hooks {
    private val MARK = Regex(""""startMillis"\s*:\s*"(\d+)"\s*,\s*"durationMillis"\s*:\s*"(\d+)"\s*,\s*"intensityScoreNormalized"\s*:\s*([0-9.eE-]+)""")

    /** The most played-again moment (ms), or null when YouTube hasn't one for it (blocking: one read). */
    fun replayed(videoId: String): Long? = runCatching {
        val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20251110.01.00","hl":"en"}},"videoId":"$videoId"}"""
        val r = http("https://www.youtube.com/youtubei/v1/next?prettyPrint=false", "POST",
            mapOf("Content-Type" to "application/json", "Origin" to "https://www.youtube.com"), body.encodeToByteArray())
        if (r.code != 200) return null
        val marks = MARK.findAll(r.text).map { Triple(it.groupValues[1].toLong(), it.groupValues[2].toLong(), it.groupValues[3].toDouble()) }
            .distinctBy { it.first }.sortedBy { it.first }.toList()
        best(marks)
    }.getOrNull()

    /** The best moment of marks (start, length, how much, 0 to 1): three marks' worth together, past the first tenth. */
    fun best(marks: List<Triple<Long, Long, Double>>): Long? {
        if (marks.size < 10) return null
        val end = marks.last().let { it.first + it.second }
        val from = marks.indexOfFirst { it.first >= end / 10 }.takeIf { it >= 0 } ?: return null
        val at = (from until marks.size - 2).maxByOrNull { i -> marks[i].third + marks[i + 1].third + marks[i + 2].third } ?: return null
        // a little before the peak, so the clip comes into it
        return max(0L, marks[at].first - 2_000)
    }

    /** The chorus by its words (each line's ms and words): the line sung most (twice at least), first sung; null if none repeats. */
    fun chorus(lines: List<Pair<Long, String>>): Long? {
        val sung = lines.map { it.first to it.second.lowercase().filter { c -> c.isLetterOrDigit() || c == ' ' }.trim() }
            .filter { it.second.length >= 6 && it.first >= 10_000 }
        val line = sung.groupingBy { it.second }.eachCount().filter { it.value >= 2 }.maxByOrNull { it.value }?.key ?: return null
        return max(0L, sung.first { it.second == line }.first - 1_000)
    }
}
