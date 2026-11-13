package app.ink.music

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code in ink's look: its squares lit on the black, whole pixels each so its edges stay sharp (a screen read by a
 * phone's camera). Lit on black is a QR code turned over; the phone's scanner reads it either way ([Handover.link]'s
 * reader on the phone tries both). The black around it is its quiet edge.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) {
        runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)) }.getOrNull()
    } ?: return
    Canvas(modifier) {
        val cell = kotlin.math.floor(size.minDimension / m.width).coerceAtLeast(1f)
        val left = kotlin.math.floor((size.width - cell * m.width) / 2)
        val top = kotlin.math.floor((size.height - cell * m.height) / 2)
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y])
            drawRect(Ink.ink, Offset(left + x * cell, top + y * cell), Size(cell, cell))
    }
}
