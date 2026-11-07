package app.ink.music

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A file written whole (2026-11-07): beside it first, then moved over it. Java's renameTo won't replace a file that's
 * there on Windows (it says false and does nothing), so every file the computer kept was written once and never again:
 * the pairing (sync.json), the YouTube sign-in handed over (youtube.json), loves, settings. Moved here by NIO, which
 * replaces on every system (atomically where the disk can).
 */
object Keep {
    fun write(f: File, text: String) {
        val tmp = File(f.path + ".part")
        tmp.writeText(text)
        replace(tmp, f)
    }

    /** `from` moved over `to`, whatever's there; false if it couldn't be. */
    fun replace(from: File, to: File): Boolean = try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); true
    } catch (e: Exception) {
        runCatching { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); true }.getOrDefault(false)
    }

    /**
     * What Windows left behind before this: each kept file's newest version stranded beside it as ".part" (its move
     * refused). Put back where it's newer, so a pairing or a sign-in made then comes back without making it again.
     */
    fun recover(dir: File) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json.part") }?.forEach { part ->
            val f = File(dir, part.name.removeSuffix(".part"))
            if (part.length() > 0 && (!f.exists() || part.lastModified() > f.lastModified())) replace(part, f) else part.delete()
        }
    }
}
