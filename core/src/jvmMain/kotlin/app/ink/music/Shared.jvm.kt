package app.ink.music

// On the phone and the computer the shared types are Java's own, exactly as before (the apps pass these in and out)
actual typealias JSONObject = org.json.JSONObject
actual typealias JSONArray = org.json.JSONArray
actual typealias File = java.io.File

actual fun File.readText(): String = this.readText(Charsets.UTF_8)
actual fun File.writeText(text: String) = this.writeText(text, Charsets.UTF_8)
actual fun File.appendText(text: String) = this.appendText(text, Charsets.UTF_8)
actual fun File.readLines(): List<String> = this.readLines(Charsets.UTF_8)
actual fun File.readBytes(): ByteArray = java.nio.file.Files.readAllBytes(toPath())
actual fun File.writeBytes(bytes: ByteArray) { java.nio.file.Files.write(toPath(), bytes) }
