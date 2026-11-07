package app.ink.music

actual fun nowMs(): Long = System.currentTimeMillis()

actual fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
