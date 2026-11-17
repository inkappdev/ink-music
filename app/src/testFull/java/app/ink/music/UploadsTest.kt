package app.ink.music

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Your uploads on YouTube Music read signed in, against the real thing (2026-11-17), and a song's stream found as its web
 * player gets it signed in (the only way an upload plays). Signed in by a sign-in as the computer keeps it (its
 * youtube.json, or the file `INK_TUBE_AUTH` names); skipped where there's none. node stands in for the solver.
 * Measured 2026-11-17 on an account with no uploads: the three pages answer, "No songs uploaded yet"; signed in, the web
 * player's stream opened at its start and middle with no poToken at all, the solver alone (Premium's 257 kbps).
 */
class UploadsTest {
    private fun tube(): InnerTube {
        val f = java.io.File(System.getenv("INK_TUBE_AUTH") ?: (System.getenv("APPDATA").orEmpty() + "/ink music/youtube.json"))
        Assume.assumeTrue("no sign-in to read with", f.exists())
        val o = org.json.JSONObject(f.readText())
        return InnerTube(o.optString("cookie"), o.optString("visitor").ifEmpty { null }, o.optString("sync").ifEmpty { null },
            o.optString("version").ifEmpty { InnerTube.VERSION })
    }

    @Test fun uploads() {
        val t = tube()
        assertNotNull("the sign-in no longer works", t.who())
        val t0 = System.currentTimeMillis()
        // signed out, these would throw (InnerTube.SignedOut): an empty answer is truly none
        val songs = t.uploads()
        println("uploads: ${songs.size} in ${System.currentTimeMillis() - t0} ms"); songs.take(5).forEach(::println)
        val records = t.uploadedRecords(); println("records: ${records.size}"); records.take(3).forEach(::println)
        val artists = t.uploadedArtists(); println("artists: ${artists.size}"); artists.take(3).forEach(::println)
        assertTrue(songs.all { it.videoId.length == 11 && it.title.isNotEmpty() && it.title != "Shuffle all" })
        // an upload's stream, as the apps find it signed in
        songs.firstOrNull()?.let { first ->
            val s = stream(t, first.videoId)
            println("${first.title}: ${s?.how} ${s?.kbps} kbps")
            assertNotNull("an upload didn't stream signed in", s)
        }
    }

    /** A song (not an upload) through the signed-in web player alone: the way an upload plays, tried where there's none. */
    @Test fun signedWeb() {
        val s = stream(tube(), "m1suappFvag")
        println("signed in: ${s?.how} ${s?.kbps} kbps")
        assertTrue(s?.how?.startsWith("web") == true)
    }

    private fun stream(t: InnerTube, id: String): TubeStream.Stream? {
        Assume.assumeTrue("no node", runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val dir = java.nio.file.Files.createTempDirectory("tube").toFile()
        return try { TubeStream(node, dir, t).apply { log = { println("tube: $it") } }.stream(id) } finally { dir.deleteRecursively() }
    }

    // yt-dlp's solver in node (paths with forward slashes, which node reads on Windows too)
    private val node = object : TubeJs {
        private val ejs = java.io.File("src/full/assets/ejs").absolutePath.replace(java.io.File.separatorChar, '/')
        override fun solve(playerUrl: String, playerCode: String, sig: String?, n: String?): Pair<String?, String?>? {
            val dir = java.nio.file.Files.createTempDirectory("solve").toFile()
            val d = dir.absolutePath.replace(java.io.File.separatorChar, '/')
            java.io.File(dir, "player.js").writeText(playerCode)
            val requests = org.json.JSONArray().apply {
                if (sig != null) put(org.json.JSONObject().put("type", "sig").put("challenges", org.json.JSONArray().put(sig)))
                if (n != null) put(org.json.JSONObject().put("type", "n").put("challenges", org.json.JSONArray().put(n)))
            }
            java.io.File(dir, "run.js").writeText("""
                const fs = require('fs'), vm = require('vm');
                vm.runInThisContext(fs.readFileSync('$ejs/yt.solver.lib.min.js', 'utf8') + '\nvar meriyah = lib.meriyah, astring = lib.astring;\n' + fs.readFileSync('$ejs/yt.solver.core.min.js', 'utf8'));
                const out = jsc({ type: 'player', player: fs.readFileSync('$d/player.js', 'utf8'), requests: $requests });
                console.log(JSON.stringify(out.responses));
            """.trimIndent())
            val p = ProcessBuilder("node", java.io.File(dir, "run.js").absolutePath).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText(); p.waitFor()
            dir.deleteRecursively()
            val r = runCatching { org.json.JSONArray(text.trim().lines().last()) }.getOrElse { println("node: ${text.take(400)}"); return null }
            var i = 0
            val s = if (sig != null) r.getJSONObject(i++).optJSONObject("data")?.optString(sig) else null
            val nn = if (n != null) r.getJSONObject(i).optJSONObject("data")?.optString(n) else null
            return s to nn
        }
        override fun poToken(id: String): String? = null
    }
}
