package app.ink.music

internal actual fun modelBeat(mono: FloatArray, rate: Int, startMs: Long): Beat.Found? = BeatNet.find(mono, rate, startMs)
internal actual fun modelReady(): Boolean = BeatNet.ready
