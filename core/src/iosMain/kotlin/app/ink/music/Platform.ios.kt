@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package app.ink.music

import platform.Foundation.NSDate
import platform.Foundation.NSDiacriticInsensitiveSearch
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.stringByFoldingWithOptions
import platform.Foundation.timeIntervalSince1970

actual class Guard actual constructor() {
    private val lock = platform.Foundation.NSRecursiveLock()
    actual fun <T> run(f: () -> T): T { lock.lock(); try { return f() } finally { lock.unlock() } }
}

actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun fold(s: String): String = (s.lowercase() as NSString).stringByFoldingWithOptions(NSDiacriticInsensitiveSearch, null)
