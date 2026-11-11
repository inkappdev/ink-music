package app.ink.music

import org.json.JSONObject
import org.junit.Test
import java.io.File

/** One-off (2026-11-09, Bronson asked): follow on YouTube Music the artists with 3+ songs in Favorite Songs, slowly. */
class FollowProbe {
    @Test fun follow() {
        val a = JSONObject(File(System.getProperty("user.home"), ".local/share/ink-music/youtube.json").readText())
        val yt = InnerTube(a.optString("cookie"), a.optString("visitor").ifEmpty { null }, a.optString("sync").ifEmpty { null })
        val log = File(System.getProperty("user.home"), "ink-follow-log.txt")
        fun say(s: String) { println("PROBE $s"); log.appendText(s + "\n") }
        val t = yt.tracks("VLPLWvaRHtDFbf0", 4000)
        val wanted = t.flatMap { it.artists.take(1) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .filter { it.value >= 3 }.entries.sortedByDescending { it.value }.map { it.key }
        val followed = yt.items("FEmusic_library_corpus_artists", 1000).filter { it.kind == InnerTube.Kind.ARTIST }.map { Search.fold(it.title) }.toSet()
        say("to follow: ${wanted.size}, already: ${wanted.count { Search.fold(it) in followed }}")
        var done = 0
        for (name in wanted) {
            if (Search.fold(name) in followed) { say("already  $name"); continue }
            val hit = runCatching { yt.searchItems(name, InnerTube.Kind.ARTIST) }.getOrDefault(emptyList())
                .firstOrNull { it.id.startsWith("UC") && Search.fold(it.title) == Search.fold(name) }
            if (hit == null) { say("no match $name"); Thread.sleep(4_000); continue }
            val ok = yt.follow(hit.id, true)
            done += if (ok) 1 else 0
            say("${if (ok) "followed" else "FAILED  "} $name (${hit.id})")
            if (!ok) { say("stopping: a follow was refused"); break }
            Thread.sleep(20_000L + (Math.random() * 10_000).toLong())
        }
        say("done: $done followed")
    }
}
