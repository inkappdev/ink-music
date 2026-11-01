package app.ink.music

import android.app.Activity
import android.os.Bundle

/**
 * Opened by Android when a USB DAC is plugged in (its filter: res/xml/usb_audio.xml), only so Android can offer
 * "always" for ink music and remember it (Bronson, 2026-11-01: the permission asked each plug-in otherwise). Shows
 * nothing: the player told to look again (with exact on, the DAC's taken), and gone.
 */
class UsbAttach : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(android.content.Intent(UsbDac.ALLOWED).setPackage(packageName))
        finish()
    }
}
