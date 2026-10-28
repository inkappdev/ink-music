package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Settings: your server (signed in or not), where the music folder is, and who made this. */
@Composable
fun SettingsPage(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Word("close", pixel(13.sp, Ink.mid), Modifier.padding(start = 32.dp, top = (if (Home.mac) 28 else 0).dp + 12.dp), touch = 8.dp, onClick = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp)) {
          Column(Modifier.widthIn(max = 640.dp)) {
            BasicText("SETTINGS", style = headline(64.sp))
            Spacer(Modifier.height(32.dp))

            val account by Server.account.collectAsState()
            val count by Server.count.collectAsState()
            val syncing by Server.syncing.collectAsState()
            BasicText("YOUR SERVER", style = headline(24.sp))
            Spacer(Modifier.height(6.dp))
            val a = account
            if (a == null) {
                BasicText("navidrome, or any subsonic server: its songs, played from it", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(16.dp))
                SignIn()
            } else {
                BasicText(
                    listOf(a.name.lowercase(), a.base.removePrefix("http://").removePrefix("https://").lowercase(),
                        if (syncing) "reading" else "$count songs").joinToString(" · "),
                    style = pixel(12.sp, Ink.mid),
                )
                Spacer(Modifier.height(12.dp))
                Word("read again", pixel(14.sp), touch = 8.dp) { Server.sync() }
                Word("sign out", pixel(14.sp, Ink.mid), touch = 8.dp) { Server.signOut() }
            }

            Spacer(Modifier.height(36.dp))
            BasicText("YOUR FILES", style = headline(24.sp))
            Spacer(Modifier.height(6.dp))
            BasicText("every song in ${Library.said}", style = pixel(12.sp, Ink.mid))

            Spacer(Modifier.height(48.dp))
            BasicText("made by ink · free and open, gpl-3.0", style = pixel(12.sp, Ink.mid))
          }
        }
    }
}

/** Signing in to your server: its address, your name and password; what went wrong, said plainly. */
@Composable
private fun SignIn() {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var trying by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    fun go() {
        if (trying || address.isBlank() || user.isBlank()) return
        trying = true; said = null
        scope.launch {
            said = when (Server.signIn(address, user, password)) {
                Server.SignIn.OK -> { password = ""; null }
                Server.SignIn.WRONG -> "that name and password weren't right"
                Server.SignIn.UNREACHABLE ->
                    if (Home.mac) "couldn't reach it. is the address right? a mac asks first: allow ink music on your local network (system settings, privacy and security, local network)"
                    else "couldn't reach it. is the address right, and this computer on the same network?"
                Server.SignIn.NOT_A_SERVER -> "something answered, but not a subsonic server"
            }
            trying = false
        }
    }
    Column {
        Field("address", address, { address = it }, "192.168.1.10:4533", onEnter = ::go)
        Field("name", user, { user = it }, "", onEnter = ::go)
        Field("password", password, { password = it }, "", secret = true, onEnter = ::go)
        Spacer(Modifier.height(10.dp))
        said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
        Word(if (trying) "SIGNING IN" else "SIGN IN", headline(40.sp, if (trying) Ink.mid else Ink.ink), touch = 10.dp) { go() }
    }
}

/** A word to type: its name above, a hairline under; in Archivo as typed (an address is case sensitive). */
@Composable
private fun Field(name: String, value: String, onValue: (String) -> Unit, hint: String, secret: Boolean = false, onEnter: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        BasicText(name, style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        Box {
            if (value.isEmpty() && hint.isNotEmpty()) BasicText(hint, style = headline(18.sp, Ink.dim))
            BasicTextField(
                value, onValue, singleLine = true, textStyle = headline(18.sp), cursorBrush = SolidColor(Ink.ink),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { k ->
                    if (k.type == KeyEventType.KeyDown && k.key == Key.Enter) { onEnter(); true } else false
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}
