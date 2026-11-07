@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.ink.music

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.posix.memcpy

/** A file on the iPhone (inside the app's own folders), as java.io.File does it for core. */
actual class File actual constructor(private val path: String) {
    actual constructor(parent: File, child: String) : this(parent.getPath().trimEnd('/') + "/" + child)

    private val fm get() = NSFileManager.defaultManager
    actual fun getName(): String = path.trimEnd('/').substringAfterLast('/')
    actual fun getPath(): String = path
    actual fun getParentFile(): File? = path.trimEnd('/').substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { File(it) }
    actual fun exists(): Boolean = fm.fileExistsAtPath(path)
    actual fun isFile(): Boolean = exists() && !isDirectory()
    actual fun isDirectory(): Boolean = fm.contentsOfDirectoryAtPath(path, null) != null
    actual fun delete(): Boolean = fm.removeItemAtPath(path, null)
    actual fun mkdirs(): Boolean = !exists() && fm.createDirectoryAtPath(path, true, null, null)
    actual fun length(): Long = (fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    actual fun lastModified(): Long =
        ((fm.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0).times(1000).toLong()
    actual fun setLastModified(time: Long): Boolean =
        fm.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(time / 1000.0)), path, null)
    actual fun listFiles(): Array<File>? = fm.contentsOfDirectoryAtPath(path, null)?.map { File(this, it as String) }?.toTypedArray()
    override fun toString() = path
    override fun equals(other: Any?) = other is File && other.path == path
    override fun hashCode() = path.hashCode()
}

internal fun NSData.toBytes(): ByteArray = ByteArray(length.toInt()).also { out -> if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
internal fun ByteArray.data(): NSData = usePinned { NSData.dataWithBytes(if (isEmpty()) null else it.addressOf(0), size.convert()) }

actual fun File.readText(): String = readBytes().decodeToString()
actual fun File.writeText(text: String) = writeBytes(text.encodeToByteArray())
actual fun File.appendText(text: String) {
    if (!exists()) return writeText(text)
    val h = NSFileHandle.fileHandleForWritingAtPath(getPath()) ?: throw IOException("can't write ${getPath()}")
    h.seekToEndOfFile(); h.writeData(text.encodeToByteArray().data()); h.closeFile()
}
actual fun File.readLines(): List<String> = readText().lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
actual fun File.readBytes(): ByteArray = NSData.dataWithContentsOfFile(getPath())?.toBytes() ?: throw IOException("can't read ${getPath()}")
actual fun File.writeBytes(bytes: ByteArray) { if (!bytes.data().writeToFile(getPath(), true)) throw IOException("can't write ${getPath()}") }
