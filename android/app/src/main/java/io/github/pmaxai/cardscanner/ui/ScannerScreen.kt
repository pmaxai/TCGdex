package io.github.pmaxai.cardscanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.pmaxai.cardscanner.camera.FrameAnalyzer
import io.github.pmaxai.cardscanner.camera.loadPhoto
import io.github.pmaxai.cardscanner.data.CardInfo
import io.github.pmaxai.cardscanner.vision.Readings
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

private val Gold = Color(0xFFF2C14E)
private val Sure = Color(0xFF4CD07D)
private val Likely = Color(0xFFF2C14E)
private val Ink = Color(0xFF101114)
private val Panel = Color(0xFF1A1C21)
private val Muted = Color(0xFF9AA0AA)

@Composable
fun CardScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel),
        content = content,
    )
}

fun eur(v: Double?): String = v?.let { String.format(Locale.getDefault(), "%.2f €", it) } ?: "–"

@Composable
fun App(openCvReady: Boolean, vm: ScannerViewModel = viewModel()) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    val state by vm.state.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = Ink) {
        when {
            !openCvReady -> Message("OpenCV could not be loaded on this phone.")
            state.error != null -> Message("Could not start: ${state.error}")
            state.loading -> Loading(state.loadingText)
            !granted -> Message("The scanner needs the camera.") { Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } }
            else -> Scanner(state, vm)
        }
    }
}

@Composable
private fun Loading(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Gold)
        Spacer(Modifier.height(16.dp))
        Text(text, color = Muted)
        Text("(first start only: unpacking ~50 MB)", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) =
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Color.White)
            Spacer(Modifier.height(16.dp))
            action()
        }
    }

@Composable
private fun Scanner(state: UiState, vm: ScannerViewModel) {
    val ctx = LocalContext.current
    var torch by remember { mutableStateOf(false) }
    var editThreshold by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) loadPhoto(ctx.contentResolver, uri)?.let { vm.onPhoto(it) }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Card Scanner", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(start = 8.dp).weight(1f))
            AssistChip(onClick = { editThreshold = true }, label = {
                Text(if (state.threshold > 0) "Alert ≥ ${state.threshold.toInt()} €" else "Alert off")
            })
            TextButton({ torch = !torch }) { Text(if (torch) "Light on" else "Light", color = if (torch) Gold else Color.White) }
            TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("Photo", color = Color.White)
            }
        }

        // ---------------------------------------------------------------- camera + guide
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Color.Black)) {
            CameraPreview(onFrame = vm::onFrame, torch = torch, modifier = Modifier.fillMaxSize())
            GuideOverlay(state, Modifier.fillMaxSize())
            if (state.paused) {
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { vm.resume() },
                    contentAlignment = Alignment.Center) {
                    Text("Tap to scan again", color = Color.White, fontSize = 18.sp)
                }
            } else {
                Text(
                    when (state.liveConfidence) {
                        Confidence.SURE -> "Got it"
                        Confidence.LIKELY -> "Probably…"
                        else -> "Fit the card into the frame"
                    },
                    color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .background(Color(0x88000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        // ---------------------------------------------------------------- result
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            val r = state.result
            if (r?.best == null) {
                Text("Hold a card inside the frame. Its name and Cardmarket price appear here; " +
                        "the phone vibrates when a card is worth at least your alert price.", color = Muted)
            } else {
                ResultCard(r, onPick = vm::choose)
            }
            if (state.showDebug || r?.fromPhoto == true) state.debug?.let { DebugPanel(it) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.packInfo, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(vm::toggleDebug) { Text(if (state.showDebug) "Hide details" else "Details", fontSize = 12.sp) }
            }
        }
    }

    if (editThreshold) ThresholdDialog(state.threshold, onDone = { v -> v?.let(vm::setThreshold); editThreshold = false })
}

@Composable
private fun ResultCard(r: ScanResult, onPick: (Candidate) -> Unit) {
    val ctx = LocalContext.current
    val c = r.best!!.info
    Row {
        AsyncImage(
            model = c.thumbnailUrl, contentDescription = c.name,
            modifier = Modifier.width(86.dp).aspectRatio(63f / 88f).background(Panel, RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text("${c.setName} · ${c.numberText} · ${c.lang.uppercase()}", color = Muted, fontSize = 13.sp)
            c.rarity?.let { Text(it, color = Muted, fontSize = 12.sp) }
            Spacer(Modifier.height(6.dp))
            val color = when (r.confidence) { Confidence.SURE -> Sure; Confidence.LIKELY -> Likely; else -> Muted }
            Text(
                when (r.confidence) {
                    Confidence.SURE -> "✓ sure match"
                    Confidence.LIKELY -> "likely – check the alternatives"
                    else -> "unsure – check the alternatives"
                }, color = color, fontSize = 12.sp,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    PriceBlock(c)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({
            val q = URLEncoder.encode(c.name, "UTF-8")
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.cardmarket.com/en/Pokemon/Products/Search?searchString=$q")))
        }) { Text("Cardmarket") }
    }
    if (r.candidates.size > 1) {
        Spacer(Modifier.height(10.dp))
        Text("Not this card? Other candidates:", color = Muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            r.candidates.drop(1).forEach { alt ->
                FilterChip(selected = false, onClick = { onPick(alt) }, label = {
                    Text("${alt.info.name} ${alt.info.numberText} · ${eur(alt.info.prices?.best)}", fontSize = 12.sp)
                })
            }
        }
    }
}

@Composable
private fun PriceBlock(c: CardInfo) {
    val p = c.prices
    if (p == null) {
        Text("No Cardmarket price for this card.", color = Muted)
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(eur(p.headline), color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("trend", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    Text("30-day avg ${eur(p.avg30)} · from ${eur(p.low)} · avg ${eur(p.avg)}", color = Color.White, fontSize = 13.sp)
    if (p.headlineHolo != null) {
        Text("Holo/reverse: trend ${eur(p.trendHolo)} · 30-day ${eur(p.avg30Holo)} · from ${eur(p.lowHolo)}",
            color = Color.White, fontSize = 13.sp)
    }
    p.updated?.let { Text("Cardmarket price guide, ${it.take(10)}", color = Muted, fontSize = 11.sp) }
}

@Composable
private fun DebugPanel(d: DebugInfo) {
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp)) {
        d.preview?.let {
            Image(it.asImageBitmap(), "what the scanner saw", Modifier.width(80.dp).aspectRatio(63f / 88f))
            Spacer(Modifier.width(8.dp))
        }
        Column {
            d.lines.forEach { Text(it, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun ThresholdDialog(current: Double, onDone: (Double?) -> Unit) {
    val options = listOf(0.0, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Vibrate for cards worth at least") },
        text = {
            Column {
                options.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { v ->
                            FilterChip(selected = v == current, onClick = { onDone(v) },
                                label = { Text(if (v == 0.0) "off" else "${v.toInt()} €") })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onDone(null) }) { Text("Close") } },
    )
}

@Composable
private fun GuideOverlay(state: UiState, modifier: Modifier) {
    Canvas(modifier) {
        val prior = Readings.guidePrior(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        val gh = (prior.h * size.height).toFloat()
        val gw = gh * 63f / 88f
        val tl = Offset((size.width - gw) / 2, (size.height - gh) / 2)
        val guide = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(tl.x, tl.y, tl.x + gw, tl.y + gh, CornerRadius(18f, 18f)))
        }
        clipPath(guide, clipOp = ClipOp.Difference) { drawRect(Color(0x66000000)) }
        val col = when (state.liveConfidence) { Confidence.SURE -> Sure; Confidence.LIKELY -> Likely; else -> Color.White }
        drawPath(guide, col, style = Stroke(width = 4f))
        val q = state.liveQuad
        val f = state.liveFrame
        if (q != null && f != null && !state.paused) {
            val sx = size.width / f.first
            val sy = size.height / f.second
            val path = Path().apply {
                moveTo(q[0] * sx, q[1] * sy)
                for (i in 1 until 4) lineTo(q[2 * i] * sx, q[2 * i + 1] * sy)
                close()
            }
            drawPath(path, col.copy(alpha = 0.9f), style = Stroke(width = 3f))
        }
    }
}

@Composable
private fun CameraPreview(onFrame: (org.opencv.core.Mat) -> Unit, torch: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            // 4:3 frames; the preview box has the same shape, so the guide matches the analysed frame
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor, FrameAnalyzer(onFrame))
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    AndroidView(factory = { previewView }, modifier = modifier)
}
