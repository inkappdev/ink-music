package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer ink music for this computer: its version, what's new in it (the changelog's lines), its download. */
data class Update(val version: String, val notes: List<String>, val url: String, val name: String, val sums: String?, val size: Long)

/** Where taking an update is: offered, coming down (how much of it), going in, or failed (why). */
sealed interface Updating {
    data object Offered : Updating
    data class Down(val part: Float) : Updating
    data object Installing : Updating
    data class Failed(val why: String) : Updating
}

/**
 * ink music keeping itself up to date on the computer, as the phone's full build does (Bronson, 2026-10-29): GitHub's
 * latest release looked at every six hours; one newer than this offered at the top of listen. Taken, this computer's
 * download comes down (the Mac's dmg, Windows' msi, Linux's deb or rpm, as this one was installed), is checked against
 * the release's SHA256SUMS, and goes in its own system's way, then ink music opens again:
 *  - a Mac: the new app copied out of the dmg beside this one, swapped in once this one has quit (downloaded by ink
 *    music, it carries no quarantine, so no Open Anyway again); where it can't write, the dmg is opened to drag over;
 *  - Windows: once this one has quit, the msi run with its progress bar alone (installed just for you, as the first);
 *  - Linux: the system asks for the password (pkexec), apt or dnf puts it in.
 * Only an installed ink music looks (not one run from the source).
 */
object Updates {
    // ink.updates and ink.self only for trying it against a release of one's own (tools/try-update)
    private val LATEST = System.getProperty("ink.updates") ?: "https://api.github.com/repos/inkappdev/ink-music/releases/latest"
    private const val EVERY = 6 * 3_600_000L
    val offer = MutableStateFlow<Update?>(null)
    val step = MutableStateFlow<Updating>(Updating.Offered)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // who asked for it, "(#12, #15)": on GitHub, not on the card
    private val asked = Regex("""\s*\(#\d+(,\s*#\d+)*\)""")

    /** Where this ink music runs from: the launcher inside its app (null run from the source: nothing to update). */
    private val self: String? by lazy {
        (System.getProperty("ink.self") ?: ProcessHandle.current().info().command().orElse(null))?.takeIf {
            when {
                Home.mac -> ".app/Contents/MacOS/" in it
                Home.windows -> it.lowercase().endsWith("ink music.exe")
                else -> it.startsWith("/opt/ink-music/")
            }
        }
    }

    /** Which download is this computer's: the Mac's dmg, Windows' msi, Linux's rpm where rpm put it here, else the deb. */
    private fun mine(name: String): Boolean = when {
        Home.mac -> name.endsWith("-mac.dmg")
        Home.windows -> name.endsWith(".msi")
        rpm -> name.endsWith(".rpm")
        else -> name.endsWith(".deb")
    }
    private val rpm by lazy { File("/usr/bin/rpm").exists() && self != null && runCatching { ProcessBuilder("rpm", "-qf", self).start().waitFor() == 0 }.getOrDefault(false) }

    // when it last looked, and the version put aside with "not now" (the next one's offered again)
    private val file = File(Home.dir, "update.json")
    private fun kept() = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    fun check() {
        if (self == null || Keys.version.isEmpty()) return
        scope.launch {
            val k = kept()
            val seen = k.optJSONObject("seen")
            val fresh = System.currentTimeMillis() - k.optLong("at") < EVERY
            val r = (if (fresh) seen else null) ?: runCatching { JSONObject(get(LATEST)) }.getOrNull() ?: return@launch
            if (!fresh) runCatching { file.writeText(k.put("at", System.currentTimeMillis()).put("seen", r).toString()) }
            val version = r.optString("tag_name").removePrefix("v")
            if (!newer(version, Keys.version) || version == k.optString("aside")) return@launch
            val assets = r.optJSONArray("assets") ?: return@launch
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }
            val it = all.firstOrNull { mine(it.optString("name")) } ?: return@launch
            val notes = r.optString("body").lines().map { it.trim().removePrefix("-").trim().replace(asked, "") }.filter { it.isNotEmpty() }
            offer.value = Update(version, notes, it.getString("browser_download_url"), it.getString("name"),
                all.firstOrNull { a -> a.optString("name") == "SHA256SUMS" }?.getString("browser_download_url"), it.optLong("size"))
        }
    }

    fun notNow() {
        val u = offer.value ?: return
        offer.value = null
        scope.launch { runCatching { file.writeText(kept().put("aside", u.version).toString()) } }
    }

    fun take() {
        val u = offer.value ?: return
        if (step.value is Updating.Down || step.value == Updating.Installing) return
        step.value = Updating.Down(0f)
        scope.launch {
            runCatching {
                val dir = File(Home.cache, "update").apply { deleteRecursively(); mkdirs() }
                val got = File(dir, u.name)
                download(u, got)
                // the sum the release says, or nothing is opened: a cut-short or changed download stops here
                val s = u.sums ?: error("the release has no sums to check it by")
                val want = get(s).lines().firstOrNull { it.trim().endsWith(u.name) }?.substringBefore(' ')?.trim()
                if (want == null || !want.equals(sha256(got), ignoreCase = true)) error("the download doesn't match")
                step.value = Updating.Installing
                when {
                    Home.mac -> mac(got)
                    Home.windows -> windows(got)
                    else -> linux(got)
                }
            }.onFailure {
                step.value = Updating.Failed(if (it is IllegalStateException) it.message.orEmpty() else "couldn't download it")
            }
        }
    }

    /** The new app out of the dmg, beside this one; swapped in after this one quits, and opened. */
    private fun mac(dmg: File) {
        val bundle = File(self!!.substringBefore(".app/Contents/MacOS/") + ".app")
        val at = File(dmg.parentFile, "mounted").apply { mkdirs() }
        run("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", at.path, dmg.path) || error("couldn't open the download")
        try {
            val app = at.listFiles()?.firstOrNull { it.name.endsWith(".app") } ?: error("the download has no app in it")
            // not ours to change (another user's /Applications): the dmg opened, to drag over as the first time
            if (!bundle.parentFile.canWrite()) { run("open", dmg.path); step.value = Updating.Failed("drag ink music to applications to finish"); return }
            val next = File(bundle.path + ".new")
            next.deleteRecursively()
            run("ditto", app.path, next.path) || error("couldn't copy it")
        } finally { run("hdiutil", "detach", at.path, "-quiet") }
        after("""
            mv ${q(bundle.path)} ${q(bundle.path + ".old")} && mv ${q(bundle.path + ".new")} ${q(bundle.path)} && rm -rf ${q(bundle.path + ".old")}
            xattr -dr com.apple.quarantine ${q(bundle.path)} 2>/dev/null
            open ${q(bundle.path)}
        """)
    }

    /** Once this one has quit (its files in use till then), the msi with its progress bar alone, then ink music again. */
    private fun windows(msi: File) {
        fun ps(s: String) = "'" + s.replace("'", "''") + "'"
        val script = """
            Wait-Process -Id ${ProcessHandle.current().pid()} -ErrorAction SilentlyContinue
            Start-Process msiexec -ArgumentList '/i', ${ps("\"" + msi.path + "\"")}, '/passive', '/norestart' -Wait
            Start-Process ${ps(self!!)}
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
        quit()
    }

    /** The system asks for the password, and its own package manager puts it in (libmpv and all); then ink music again. */
    private fun linux(pkg: File) {
        val tool = when {
            pkg.name.endsWith(".deb") && File("/usr/bin/apt-get").exists() -> listOf("apt-get", "install", "-y", pkg.path)
            pkg.name.endsWith(".rpm") && File("/usr/bin/dnf").exists() -> listOf("dnf", "install", "-y", pkg.path)
            pkg.name.endsWith(".rpm") && File("/usr/bin/zypper").exists() -> listOf("zypper", "--non-interactive", "install", "--allow-unsigned-rpm", pkg.path)
            else -> null
        }
        if (tool == null || !File("/usr/bin/pkexec").exists()) {
            run("xdg-open", pkg.parentFile.path)
            step.value = Updating.Failed("install ${pkg.name} from the folder that opened"); return
        }
        if (!run("pkexec", *tool.toTypedArray())) error("it wasn't installed")
        after("exec ${q(self!!)}")
    }

    /** A script run once this ink music has quit (so nothing's in use), then quit. */
    private fun after(then: String) {
        val pid = ProcessHandle.current().pid()
        val sh = "while kill -0 $pid 2>/dev/null; do sleep 0.3; done\n" + then.trimIndent()
        ProcessBuilder("/bin/sh", "-c", sh).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
        quit()
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun run(vararg cmd: String): Boolean = runCatching {
        ProcessBuilder(*cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun download(u: Update, to: File) {
        val conn = URL(u.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "ink music/" + Keys.version)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: u.size
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (total > 0) step.value = Updating.Down((got.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } finally { conn.disconnect() }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "ink music/" + Keys.version)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** 2.0.1 is newer than 2.0.0: each part as a number. */
    internal fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
