package app.ink.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The play build has no YouTube Music: no lists to pick from. */
object CoolLists {
    data class Cool(val list: TubeList, val why: String, val score: Double)
    val lists: StateFlow<List<Cool>> = MutableStateFlow(emptyList())
    suspend fun read(c: Context) {}
}
