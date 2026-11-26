package app.ink.music

/** The trained model's reading of a stretch (BeatNet on the phone and the computer); null where there's none, or no steady beat. */
internal expect fun modelBeat(mono: FloatArray, rate: Int, startMs: Long): Beat.Found?

/** Whether there's a model to read with here. */
internal expect fun modelReady(): Boolean
