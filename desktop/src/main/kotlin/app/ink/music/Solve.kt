package app.ink.music

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A signed-in stream's challenges worked out on the computer (2026-11-17, for your uploads to YouTube Music, which play
 * only signed in): YouTube's web player is given each stream's signature and speed-limit ("n") scrambled, to be worked
 * out by its own player code; yt-dlp's solver (ejs, the phone's own copy, carried in the app) does it, run by a
 * JavaScript runtime already on the computer (deno, node or bun, the ones yt-dlp itself runs it with). The computer
 * carries none of its own: with none there, an upload can't play here, and settings says so. Measured 2026-11-17: signed
 * in, YouTube Music's web player needs no poToken (the phone's BotGuard), the solver alone.
 *
 * Each solve is one script, the solver, the player's code and the question together, so the runtime reads nothing and
 * reaches nothing (deno's is run with no permissions at all). Blocking.
 */
object Solve : TubeJs {
    /** The runtime found (its command, as the system finds it), once a run; null if there's none. */
    val runtime: String? by lazy {
        listOf("deno", "node", "bun").firstOrNull { cmd ->
            runCatching {
                ran(listOf(cmd, "--version"), 10) != null
            }.getOrDefault(false)
        }.also { Log.d("solve: " + (it?.let { r -> "runs in $r" } ?: "no javascript runtime (deno, node, bun): uploads can't play here")) }
    }

    private val solver: String? by lazy {
        runCatching {
            fun read(name: String) = Solve::class.java.getResourceAsStream("/ejs/$name")!!.bufferedReader().use { it.readText() }
            read("yt.solver.lib.min.js") + "\nvar meriyah = lib.meriyah, astring = lib.astring;\n" + read("yt.solver.core.min.js")
        }.onFailure { Log.d("solve: the solver isn't in the app: $it") }.getOrNull()
    }

    override fun solve(playerUrl: String, playerCode: String, sig: String?, n: String?): Pair<String?, String?>? = runCatching {
        val run = runtime ?: return null
        val code = solver ?: return null
        val requests = JSONArray().apply {
            if (sig != null) put(JSONObject().put("type", "sig").put("challenges", JSONArray().put(sig)))
            if (n != null) put(JSONObject().put("type", "n").put("challenges", JSONArray().put(n)))
        }
        // the player's code as a JSON string, so nothing in it reads as the script's own
        val player = JSONArray().put(playerCode).toString().removePrefix("[").removeSuffix("]")
        val script = File.createTempFile("ink-solve", ".js")
        try {
            script.writeText("$code\nconsole.log(JSON.stringify(jsc({ type: 'player', player: $player, requests: $requests }).responses));\n")
            val args = if (run == "deno") listOf(run, "run", "--quiet", script.absolutePath) else listOf(run, script.absolutePath)
            val text = ran(args, 30) ?: return null
            val r = JSONArray(text.trim().lines().last())
            var i = 0
            val s = if (sig != null) r.getJSONObject(i++).optJSONObject("data")?.optString(sig)?.ifEmpty { null } else null
            val nn = if (n != null) r.getJSONObject(i).optJSONObject("data")?.optString(n)?.ifEmpty { null } else null
            s to nn
        } finally { script.delete() }
    }.onFailure { Log.d("solve: $it") }.getOrNull()

    /**
     * What a command printed, or null if it failed or ran past its seconds (then it's ended). Its output goes to a file,
     * not a pipe read as it runs: reading a pipe to its end waits as long as the runtime does, so a hung one never timed out.
     */
    private fun ran(args: List<String>, seconds: Long): String? {
        val out = File.createTempFile("ink-solve", ".out")
        try {
            val p = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(out)
                .redirectInput(ProcessBuilder.Redirect.from(File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")))
                .start()
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); Log.d("solve: ${args.first()} ran past ${seconds}s, ended"); return null }
            return if (p.exitValue() == 0) out.readText() else null
        } finally { out.delete() }
    }

    /** Not here: BotGuard runs in a browser. YouTube Music's web player, signed in, asks none (measured 2026-11-17). */
    override fun poToken(id: String): String? = null
}
