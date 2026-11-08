package app.ink.music

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** What the Swift app shows: ink music's screens. */
fun MainViewController(): UIViewController = ComposeUIViewController { Shooting { App() } }
