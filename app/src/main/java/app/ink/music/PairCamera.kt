package app.ink.music

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera, reading the computer's QR code to pair (2026-11-12: a laptop the phone couldn't find on the wifi, nor
 * reach by the address shown, so: point the phone at the screen, and nothing typed). CameraX for the picture, ZXing's
 * reader for the code (no Play services); only a code of ink music's pairing ([Handover.read]) is taken, once. The
 * picture in black and white, as everything here but the covers; a TextureView under it (COMPATIBLE), so it frosts
 * under the bars like the rest of the page.
 */
@Composable
fun PairCamera(modifier: Modifier, onRead: (Handover.Link) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val read = rememberUpdatedState(onRead)
    val view = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
        }
    }
    DisposableEffect(owner) {
        val worker = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(ctx)
        val preview = Preview.Builder().build()
        // a screen's code from a step away: more than the 640x480 analysis gets by default
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(worker) { image ->
            val link = if (done.get()) null else runCatching { QrReading.read(image) }.getOrNull()?.let(Handover::read)
            image.close()
            if (link != null && done.compareAndSet(false, true)) view.post { read.value(link) }
        }
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            preview.setSurfaceProvider(view.surfaceProvider)
            runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                .onFailure { android.util.Log.d("ink-pair", "camera: $it") }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            done.set(true)
            runCatching { if (future.isDone) future.get().unbind(preview, analysis) }
            analysis.clearAnalyzer()
            worker.shutdown()
        }
    }
    AndroidView({ view }, modifier)
}

/** A QR code read from the camera's frame: its brightness alone (the Y plane), as it is, then turned over (the computer's is lit on black). */
private object QrReading {
    fun read(image: ImageProxy): String? {
        val plane = image.planes[0]
        val w = image.width; val h = image.height; val stride = plane.rowStride
        val buf = plane.buffer.apply { rewind() }
        val y = ByteArray(w * h)
        if (stride == w) buf.get(y, 0, w * h)
        else for (r in 0 until h) { buf.position(r * stride); buf.get(y, r * w, w) }
        val src = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
        return decode(src) ?: decode(src.invert())
    }

    private fun decode(src: LuminanceSource): String? = runCatching {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    }.getOrNull()
}
