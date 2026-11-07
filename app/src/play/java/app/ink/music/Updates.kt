package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow

/** The play build is kept up to date by its store: nothing offered here. */
object Updates {
    val offer = MutableStateFlow<Update?>(null)
    val step = MutableStateFlow<Updating>(Updating.Offered)
    val said = MutableStateFlow<String?>(null)
    /** The store brings the play build's updates: no check of its own. */
    val manual = false
    fun check(c: android.content.Context, now: Boolean = false) {}
    fun notNow(c: android.content.Context) {}
    fun take(c: android.content.Context) {}
}
