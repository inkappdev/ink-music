package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/**
 * Windows' firewall and ink music (Windows only). A new app's ports stay shut until someone allows it, so the phone
 * finds the computer on the wifi and then can't get in (Bronson's PC, 2026-10-29: pairing timed out); a speaker
 * can't reach Serve either. Windows also starts every new network as public, where an allowed app stays shut. Looked
 * at without asking anything (PowerShell's firewall reading); LET IT IN then asks Windows once, as an administrator:
 * any block Windows put on ink music goes, it's let in on private networks, and this wifi is made private if it's
 * public. Never let in on public networks: Serve hands out the music folder to whoever asks.
 */
object Firewall {
    enum class State { UNKNOWN, OPEN, SHUT, PUBLIC }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state
    /** What the last LET IT IN came to, said under it; null before one. */
    val said = MutableStateFlow<String?>(null)

    private const val RULE = "ink music, your phone"

    /** The program the rule is for: ink music.exe (jpackage's launcher runs Java inside itself). */
    private fun program(): String? = ProcessHandle.current().info().command().orElse(null)

    /** Looked at again, off the main thread (a second or two: PowerShell starting). */
    fun check() {
        if (!Home.windows) return
        val p = program() ?: return
        val ip = Serve.address() ?: return
        thread(isDaemon = true, name = "firewall") {
            val out = powershell("""
                ${'$'}p = ${quote(p)}
                ${'$'}c = [string](Get-NetConnectionProfile -InterfaceIndex (Get-NetIPAddress -IPAddress ${quote(ip)}).InterfaceIndex -EA SilentlyContinue).NetworkCategory
                if (${'$'}c -eq 'DomainAuthenticated') { ${'$'}c = 'Domain' }
                ${'$'}r = @(Get-NetFirewallApplicationFilter -Program ${'$'}p -EA SilentlyContinue | Get-NetFirewallRule -EA SilentlyContinue |
                    Where-Object { ${'$'}_.Enabled -eq 'True' -and ${'$'}_.Direction -eq 'Inbound' -and (${'$'}_.Profile.ToString() -eq 'Any' -or ${'$'}_.Profile.ToString() -match ${'$'}c) })
                ${'$'}open = (@(${'$'}r | Where-Object Action -eq 'Allow').Count -gt 0) -and (@(${'$'}r | Where-Object Action -eq 'Block').Count -eq 0)
                "${'$'}c ${'$'}open"
            """) ?: return@thread
            val (category, open) = out.trim().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) == "True") }
            _state.value = when {
                category == "Public" -> State.PUBLIC
                category.isEmpty() -> State.UNKNOWN
                open -> State.OPEN
                else -> State.SHUT
            }
        }
    }

    /** LET IT IN: Windows asks once (its administrator prompt); then looked at again. */
    fun letIn() {
        if (!Home.windows) return
        val p = program() ?: return
        val ip = Serve.address()
        said.value = "windows is asking"
        thread(isDaemon = true, name = "firewall") {
            val inner = """
                ${'$'}p = ${quote(p)}
                Get-NetFirewallApplicationFilter -Program ${'$'}p -EA SilentlyContinue | Get-NetFirewallRule -EA SilentlyContinue | Where-Object Action -eq 'Block' | Remove-NetFirewallRule
                Remove-NetFirewallRule -DisplayName ${quote(RULE)} -EA SilentlyContinue
                New-NetFirewallRule -DisplayName ${quote(RULE)} -Direction Inbound -Program ${'$'}p -Action Allow -Profile Private,Domain | Out-Null
                ${ip?.let { "Get-NetConnectionProfile -InterfaceIndex (Get-NetIPAddress -IPAddress ${quote(it)}).InterfaceIndex -EA SilentlyContinue | Where-Object NetworkCategory -eq 'Public' | Set-NetConnectionProfile -NetworkCategory Private" } ?: ""}
            """
            // the administrator's PowerShell started by this one; declined, Start-Process fails and so does this
            val ok = powershell("Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ErrorAction Stop " +
                "-ArgumentList '-NoProfile','-EncodedCommand','${encoded(inner)}'; 'ok'")?.trim() == "ok"
            said.value = if (ok) null else "not let in: windows asked, and it wasn't allowed"
            check()
        }
    }

    private fun quote(s: String) = "'" + s.replace("'", "''") + "'"

    /** PowerShell's -EncodedCommand: the script as UTF-16LE in base64, so nothing in it needs quoting. */
    private fun encoded(script: String) = java.util.Base64.getEncoder().encodeToString(script.trimIndent().toByteArray(Charsets.UTF_16LE))

    private fun powershell(script: String): String? = runCatching {
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded(script))
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() == 0) out else null
    }.getOrNull()
}
