package app.ink.music

/**
 * A file, shared (2026-11-07): java.io.File on the phone and the computer (the apps hand core their folders as these),
 * the iPhone's own of the same shape over its file system (iosMain). Only what core uses; reading and writing as the
 * extensions below (UTF-8).
 */
expect class File(path: String) {
    constructor(parent: File, child: String)
    fun getName(): String
    fun getPath(): String
    fun getParentFile(): File?
    fun exists(): Boolean
    fun isFile(): Boolean
    fun isDirectory(): Boolean
    fun delete(): Boolean
    fun mkdirs(): Boolean
    fun length(): Long
    fun lastModified(): Long
    fun setLastModified(time: Long): Boolean
    fun listFiles(): Array<File>?
}

expect fun File.readText(): String
expect fun File.writeText(text: String)
expect fun File.appendText(text: String)
expect fun File.readLines(): List<String>
expect fun File.readBytes(): ByteArray
expect fun File.writeBytes(bytes: ByteArray)

/** This file moved over `to`, whatever's there; false if it couldn't be. */
expect fun File.moveOver(to: File): Boolean
