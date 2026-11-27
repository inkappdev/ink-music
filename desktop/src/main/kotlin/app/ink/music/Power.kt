package app.ink.music

/**
 * Whether this computer runs on a battery, and whether it's plugged in (2026-11-27: keep what you play most only while
 * charging, as the phone's). Linux: the kernel's power supplies (a mains one online); the Mac: pmset's "AC Power"; Windows:
 * GetSystemPowerStatus's AC line. A computer with no battery is always plugged in; a reading that fails, too.
 */
object Power {
    private val linux = java.io.File("/sys/class/power_supply")

    /** Whether there's a battery at all (a laptop): the charging choice is only offered then. */
    val battery: Boolean by lazy {
        runCatching {
            when {
                Home.mac -> run("pmset", "-g", "batt").contains("InternalBattery")
                Home.windows -> status()?.let { it.BatteryFlag.toInt() and 128 == 0 } ?: false
                else -> linux.listFiles()?.any { runCatching { java.io.File(it, "type").readText().trim() == "Battery" }.getOrDefault(false) } ?: false
            }
        }.getOrDefault(false)
    }

    /** Plugged in now. */
    fun charging(): Boolean = !battery || runCatching {
        when {
            Home.mac -> run("pmset", "-g", "batt").contains("AC Power")
            Home.windows -> status()?.ACLineStatus?.toInt() == 1
            else -> linux.listFiles()?.any {
                runCatching { java.io.File(it, "type").readText().trim() == "Mains" && java.io.File(it, "online").readText().trim() == "1" }.getOrDefault(false)
            } ?: true
        }
    }.getOrDefault(true)

    private fun run(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).start().inputStream.bufferedReader().use { it.readText() }

    @Suppress("PropertyName")
    @com.sun.jna.Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
    class SystemPowerStatus : com.sun.jna.Structure() {
        @JvmField var ACLineStatus: Byte = 0
        @JvmField var BatteryFlag: Byte = 0
        @JvmField var BatteryLifePercent: Byte = 0
        @JvmField var SystemStatusFlag: Byte = 0
        @JvmField var BatteryLifeTime: Int = 0
        @JvmField var BatteryFullLifeTime: Int = 0
    }

    private interface Kernel : com.sun.jna.Library { fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean }

    private fun status(): SystemPowerStatus? = runCatching {
        val s = SystemPowerStatus()
        if (com.sun.jna.Native.load("kernel32", Kernel::class.java).GetSystemPowerStatus(s)) s else null
    }.getOrNull()
}
