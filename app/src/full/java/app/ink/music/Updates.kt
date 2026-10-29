package app.ink.music

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
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

/**
 * ink music keeping itself up to date (the full build only: GitHub's is the only place it comes
 * from; the play build's store does this for it). Looks at GitHub's latest release at most every
 * six hours; one newer than this is offered on listen, its changelog under it. Taken, the APK comes
 * down to the cache, is checked against the release's SHA256SUMS, and goes to the phone's own
 * installer, which asks, and refuses anything not signed with ink music's key.
 * Most people never came back to GitHub: 619 took 1.4, a few dozen each release after it.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/inkappdev/ink-music/releases/latest"
    private const val EVERY = 6 * 3_600_000L
    val offer = MutableStateFlow<Update?>(null)
    val step = MutableStateFlow<Updating>(Updating.Offered)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // who asked for it, "(#12, #15)": on GitHub, not on the card
    private val asked = Regex("""\s*\(#\d+(,\s*#\d+)*\)""")

    // when it last looked, and the version put aside with "not now" (the next one's offered again)
    private fun file(c: Context) = File(c.filesDir, "update.json")
    private fun kept(c: Context) = runCatching { JSONObject(file(c).readText()) }.getOrDefault(JSONObject())

    fun check(c: Context) {
        if (BuildConfig.DEBUG) return  // a debug build has its own key: the installer would refuse it
        scope.launch {
            val k = kept(c)
            val seen = k.optJSONObject("seen")
            val fresh = System.currentTimeMillis() - k.optLong("at") < EVERY
            val r = (if (fresh) seen else null) ?: runCatching { JSONObject(get(LATEST)) }
                .onFailure { Log.w("ink-update", "couldn't look: $it") }.getOrNull() ?: return@launch
            if (!fresh) file(c).writeText(k.put("at", System.currentTimeMillis()).put("seen", r).toString())
            val version = r.optString("tag_name").removePrefix("v")
            if (!newer(version, BuildConfig.VERSION_NAME) || version == k.optString("aside")) return@launch
            val assets = r.optJSONArray("assets") ?: return@launch
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }
            // the full build's APK (a play build on GitHub would silence YouTube: release.sh keeps it off)
            val apk = all.firstOrNull { it.optString("name").let { n -> n.endsWith(".apk") && "play" !in n } } ?: return@launch
            val notes = r.optString("body").lines().map { it.trim().removePrefix("-").trim().replace(asked, "") }.filter { it.isNotEmpty() }
            offer.value = Update(version, notes, apk.getString("browser_download_url"), apk.getString("name"),
                all.firstOrNull { it.optString("name") == "SHA256SUMS" }?.getString("browser_download_url"), apk.optLong("size"))
        }
    }

    fun notNow(c: Context) {
        val u = offer.value ?: return
        offer.value = null
        scope.launch { runCatching { file(c).writeText(kept(c).put("aside", u.version).toString()) } }
    }

    fun take(c: Context) {
        val u = offer.value ?: return
        if (step.value is Updating.Down || step.value == Updating.Asking) return
        step.value = Updating.Down(0f)
        scope.launch {
            runCatching {
                val apk = File(c.cacheDir, "update.apk")
                download(u, apk)
                // the sum the release says, or no install: a cut-short download is caught here, not by the installer
                u.sums?.let { s ->
                    val want = get(s).lines().firstOrNull { it.trim().endsWith(u.name) }?.substringBefore(' ')?.trim()
                    if (want == null || !want.equals(sha256(apk), ignoreCase = true)) error("the download doesn't match")
                }
                step.value = Updating.Asking
                install(c, apk)
            }.onFailure {
                Log.w("ink-update", "update failed: $it")
                step.value = Updating.Failed(if (it is IllegalStateException) it.message.orEmpty() else "couldn't download it")
            }
        }
    }

    private fun download(u: Update, to: File) {
        val conn = URL(u.apk).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "ink music/" + BuildConfig.VERSION_NAME)
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

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            // once ink music is the phone's installer of record for itself, later updates can go in without asking
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("ink-music.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val told = PendingIntent.getBroadcast(c, id, Intent(c, Installed::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(told.intentSender)
        }
    }

    /** What the installer says: it wants asking (its screen shown), or it's done or refused. */
    class Installed : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val ask = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> { offer.value = null; step.value = Updating.Offered }
                // turned down on the installer's screen: offered again, to install when it suits
                PackageInstaller.STATUS_FAILURE_ABORTED -> step.value = Updating.Offered
                else -> {
                    Log.w("ink-update", "installer: $status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    step.value = Updating.Failed("the phone wouldn't install it")
                }
            }
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "ink music/" + BuildConfig.VERSION_NAME)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** 1.4.10 is newer than 1.4.9: each part as a number. */
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
