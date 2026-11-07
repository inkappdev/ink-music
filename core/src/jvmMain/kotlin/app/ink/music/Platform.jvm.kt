package app.ink.music

actual class Guard actual constructor() {
    private val lock = Any()
    actual fun <T> run(f: () -> T): T = synchronized(lock) { f() }
}

actual fun nowMs(): Long = System.currentTimeMillis()

actual fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
