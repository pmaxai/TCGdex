package io.github.pmaxai.cardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.pmaxai.cardscanner.license.LicenceKey
import io.github.pmaxai.cardscanner.license.LicenceStatus
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** What the screens need to know about the licence. */
data class LicenceState(
    /** this build asks for a licence (off until the license service runs) */
    val required: Boolean = false,
    val status: LicenceStatus = LicenceStatus.NONE,
    /** the key, only while this device holds the licence (for Switch to new device) */
    val key: String? = null,
    val since: Long? = null,
    val lastCheck: Long? = null,
    val suspendedBy: String? = null,
    val suspendedSince: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val locked: Boolean get() = required && status != LicenceStatus.ACTIVE
}

/**
 * Before the app can be used: scan the licence QR code (or type the key). Also shown when the
 * licence was moved to another device (or revoked): then it says so first.
 */
@Composable
fun LicenceGate(state: LicenceState, onKey: (String) -> Unit, camera: @Composable (Modifier) -> Unit = { m -> QrCamera(m, onKey) }) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        when (state.status) {
            LicenceStatus.SUSPENDED -> {
                Text("This licence is now used on another device", color = Warn, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Text(listOfNotNull(state.suspendedBy, state.suspendedSince?.let { "since ${stamp(it)}" }).joinToString(" · "),
                    color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                Text("To use the app here again, scan the licence QR code: on the other device under Settings › Licence › " +
                    "Switch to new device, or from your licence e-mail. The other device then stops.",
                    color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            LicenceStatus.REVOKED -> {
                Text("This licence was revoked", color = Warn, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Please contact the shop, or scan another licence.", color = Muted, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            else -> {
                Text("Activate Card Scanner", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Scan the QR code from your licence e-mail.", color = Muted, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        if (!typing) {
            Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).background(Panel),
                contentAlignment = Alignment.Center) {
                camera(Modifier.fillMaxSize())
                Box(Modifier.size(190.dp).border(3.dp, Gold, RoundedCornerShape(14.dp)))
                if (state.busy) CircularProgressIndicator(color = Gold)
            }
            Text("Point the camera at the licence QR code", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            TextButton({ typing = true }) { Text("Type the key instead") }
        } else {
            OutlinedTextField(typed, { typed = it.uppercase() }, label = { Text("Licence key") }, singleLine = true,
                placeholder = { Text("CS-XXXXX-XXXXX-XXXXX-XXXXX") },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ typing = false }) { Text("Scan instead") }
                Button({ onKey(typed) }, enabled = !state.busy && typed.isNotBlank()) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink) else Text("Activate")
                }
            }
        }
        state.error?.let { Text("⚠ $it", color = Warn, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
        Text("Activating needs the internet once; afterwards the app also works offline. A licence is used on one device at a time.",
            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
    }
}

/** Settings › Licence: where it is active, and Switch to new device (the QR code again). */
@Composable
fun LicencePage(state: LicenceState) {
    var showQr by rememberSaveable { mutableStateOf(false) }
    val key = state.key
    Text(if (state.status == LicenceStatus.ACTIVE) "Active on this device" else "Not active", color = Color.White,
        fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    state.since?.let { Text("since ${stamp(it)}", color = Muted, fontSize = 13.sp) }
    key?.let { Text(LicenceKey.masked(it), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
    Text(state.lastCheck?.let { "Last checked ${stamp(it)}" } ?: "Not checked yet", color = Muted, fontSize = 12.sp,
        modifier = Modifier.padding(top = 2.dp))
    if (key == null) return
    Text("Switch to new device", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
    Text("The licence works on one device at a time. On the new device, open the app and scan this code. " +
        "As soon as the new device is activated, this one stops (the next time it is online).",
        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    if (!showQr) {
        Button({ showQr = true }, modifier = Modifier.padding(top = 10.dp)) { Text("Show the licence QR code") }
    } else {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            QrImage(key, 240.dp)
            Text(key, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton({ showQr = false }) { Text("Hide") }
        }
    }
}

/** A QR code, black on white with a quiet zone, sharp at any size. */
@Composable
fun QrImage(text: String, size: Dp) {
    val bitmap = remember(text) { qrBitmap(text) }
    Image(bitmap.asImageBitmap(), contentDescription = "Licence QR code", filterQuality = FilterQuality.None,
        modifier = Modifier.size(size).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp))
}

fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

/** The back camera, looking for a licence QR code; [onKey] gets the first key it reads (once). */
@Composable
fun QrCamera(modifier: Modifier, onKey: (String) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Box(modifier, contentAlignment = Alignment.Center) { Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } }
        return
    }
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        val reader = QrReader { key -> ContextCompat.getMainExecutor(ctx).execute { onKey(key) } }
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor, reader)
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Reads QR codes from the camera's frames (the Y plane is enough) and reports licence keys,
 * at most one every two seconds (the view model ignores a key it is busy with or that just failed).
 */
private class QrReader(private val onKey: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val last = AtomicLong(0)
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    override fun analyze(image: ImageProxy) {
        image.use {
            if (System.currentTimeMillis() - last.get() < 2000) return
            val plane = it.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining()).also { b -> buf.get(b) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
            val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
            reader.reset()
            val key = text?.let(LicenceKey::parse) ?: return
            last.set(System.currentTimeMillis())
            onKey(key)
        }
    }
}

private fun stamp(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))
