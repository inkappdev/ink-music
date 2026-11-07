package app.ink.music

import org.junit.Assert.assertNotNull
import org.junit.Test

/** The shared stream finder against YouTube, node standing in for the iPhone's JavaScriptCore (no BotGuard here). */
class TubeStreamTest {
    private val ejs = java.io.File("src/full/assets/ejs")

    private val node = object : TubeJs {
        override fun solve(playerUrl: String, playerCode: String, sig: String?, n: String?): Pair<String?, String?>? {
            val dir = java.nio.file.Files.createTempDirectory("solve").toFile()
            java.io.File(dir, "player.js").writeText(playerCode)
            val requests = org.json.JSONArray().apply {
                if (sig != null) put(org.json.JSONObject().put("type", "sig").put("challenges", org.json.JSONArray().put(sig)))
                if (n != null) put(org.json.JSONObject().put("type", "n").put("challenges", org.json.JSONArray().put(n)))
            }
            java.io.File(dir, "run.js").writeText("""
                const fs = require('fs'), vm = require('vm');
                vm.runInThisContext(fs.readFileSync('${ejs.absolutePath}/yt.solver.lib.min.js', 'utf8') + '\nvar meriyah = lib.meriyah, astring = lib.astring;\n' + fs.readFileSync('${ejs.absolutePath}/yt.solver.core.min.js', 'utf8'));
                const out = jsc({ type: 'player', player: fs.readFileSync('${dir.absolutePath}/player.js', 'utf8'), requests: $requests });
                console.log(JSON.stringify(out.responses));
            """.trimIndent())
            val p = ProcessBuilder("node", java.io.File(dir, "run.js").absolutePath).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText(); p.waitFor()
            dir.deleteRecursively()
            val r = runCatching { org.json.JSONArray(text.trim().lines().last()) }.getOrElse { println("node: $text"); return null }
            var i = 0
            val s = if (sig != null) r.getJSONObject(i++).optJSONObject("data")?.optString(sig) else null
            val nn = if (n != null) r.getJSONObject(i).optJSONObject("data")?.optString(n) else null
            println("solved sig=${s != null} n=$n -> $nn")
            return s to nn
        }
        override fun poToken(id: String): String? = null
    }

    @Test fun findsAStream() {
        val dir = java.nio.file.Files.createTempDirectory("tube").toFile()
        val t = TubeStream(node, dir).apply { log = { println("tube: $it") } }
        for (id in listOf("m1suappFvag", "vSb8_sKl-Do", "dQw4w9WgXcQ")) {
            val s = t.stream(id)
            println("$id -> ${s?.how} ${s?.mime} ${s?.kbps}")
        }
        // a song (not a video): the one YouTube turns away without what it wants
        assertNotNull(t.stream("m1suappFvag"))
        dir.deleteRecursively()
    }
}
