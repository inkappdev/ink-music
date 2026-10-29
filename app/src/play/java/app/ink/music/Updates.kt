package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow

/** The play build is kept up to date by its store: nothing offered here. */
object Updates {
    val offer = MutableStateFlow<Update?>(null)
    val step = MutableStateFlow<Updating>(Updating.Offered)
    fun check(c: android.content.Context) {}
    fun notNow(c: android.content.Context) {}
    fun take(c: android.content.Context) {}
}
