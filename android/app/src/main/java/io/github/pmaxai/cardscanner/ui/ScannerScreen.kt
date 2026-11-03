package io.github.pmaxai.cardscanner.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.pmaxai.cardscanner.camera.FrameAnalyzer
import io.github.pmaxai.cardscanner.camera.loadPhoto
import io.github.pmaxai.cardscanner.data.CardInfo
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import io.github.pmaxai.cardscanner.data.Graded
import io.github.pmaxai.cardscanner.data.Population
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.vision.FoilEstimate
import io.github.pmaxai.cardscanner.vision.Readings
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

val Gold = Color(0xFFF2C14E)
val Sure = Color(0xFF4CD07D)
val Likely = Color(0xFFF2C14E)
val Ink = Color(0xFF101114)
val Panel = Color(0xFF1A1C21)
val Muted = Color(0xFF9AA0AA)
val Warn = Color(0xFFFF8A3D)

@Composable
fun CardScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel),
        content = content,
    )
}

fun eur(v: Double?): String = v?.let { String.format(Locale.getDefault(), "%.2f €", it) } ?: "–"
fun usd(v: Double?): String = v?.let { if (it >= 100) String.format(Locale.US, "$%.0f", it) else String.format(Locale.US, "$%.2f", it) } ?: "–"

val LANG_NAMES = mapOf("en" to "English", "de" to "Deutsch", "fr" to "Français", "es" to "Español",
    "it" to "Italiano", "pt" to "Português", "ja" to "日本語", "zh-tw" to "繁體中文", "zh-cn" to "简体中文", "ko" to "한국어")

@Composable
fun App(openCvReady: Boolean, vm: ScannerViewModel = viewModel()) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.messageShown() }
    }

    // YouTube mode: follow the phone's orientation (tripod), hide the system bars
    val activity = ctx as? Activity
    LaunchedEffect(state.settings.youtube) {
        activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (state.settings.youtube) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                                        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (state.settings.youtube) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        when {
            !openCvReady -> Message("OpenCV could not be loaded on this phone.")
            state.error != null -> Message("Could not start: ${state.error}")
            state.loading -> Loading(state.loadingText)
            !granted -> Message("The scanner needs the camera.") { Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } }
            state.settings.youtube -> YouTubeScreen(state, vm)
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
        Text("(first start only: unpacking the card database)", color = Muted, fontSize = 12.sp)
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
    var settings by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) loadPhoto(ctx.contentResolver, uri)?.let { vm.onPhoto(it) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Card Scanner", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(start = 8.dp).weight(1f))
            AssistChip(onClick = { settings = true }, label = {
                Text(if (state.settings.threshold > 0) "Alert ≥ ${state.settings.threshold.toInt()} €" else "Alert off")
            })
            TextButton({ torch = !torch }) { Text("Light", color = if (torch) Gold else Color.White) }
            TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("Photo", color = Color.White)
            }
            TextButton({ settings = true }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
        }

        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Color.Black)) {
            CameraPreview(onFrame = vm::onFrame, torch = torch, modifier = Modifier.fillMaxSize())
            GuideOverlay(state, Modifier.fillMaxSize(), dim = true)
            ManualTrigger(state, vm)
            if (state.paused) {
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { vm.resume() },
                    contentAlignment = Alignment.Center) {
                    Text("Tap to scan again", color = Color.White, fontSize = 18.sp)
                }
            } else {
                val hint = when {
                    state.shown?.locked == true -> "Kept – tap “Next card” for the next one"
                    state.liveConfidence == Confidence.SURE -> "Got it"
                    state.liveConfidence == Confidence.LIKELY -> "Probably…"
                    else -> "Fit the card into the frame"
                }
                Text(hint, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .background(Color(0x88000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            val s = state.shown
            if (s == null) {
                Text("Hold a card inside the frame. Its name and Cardmarket price appear here; " +
                        "the phone vibrates when any printing of it is worth at least your alert price. " +
                        "Not recognised? Double-tap the picture to take it anyway.", color = Muted)
            } else {
                ResultCard(s, state.settings, vm)
            }
            if (state.settings.showDebug || s?.fromPhoto == true) state.debug?.let { DebugPanel(it) }
            Spacer(Modifier.height(8.dp))
            Text(state.packInfo, color = Muted, fontSize = 11.sp)
        }
    }
    if (settings) SettingsDialog(state, vm) { settings = false }
}

@Composable
private fun ResultCard(s: Shown, settings: Settings, vm: ScannerViewModel) {
    val ctx = LocalContext.current
    val c = s.version
    Row {
        AsyncImage(model = c.thumbnailUrl ?: s.priceCard.thumbnailUrl, contentDescription = c.name,
            modifier = Modifier.width(86.dp).aspectRatio(63f / 88f).background(Panel, RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${c.setName} · ${c.numberText} · ${c.lang.uppercase()}", color = Muted, fontSize = 13.sp)
            s.english?.let { e -> Text("English: ${e.name} · ${e.setName} ${e.numberText}", color = Color.White, fontSize = 13.sp) }
            c.rarity?.let { Text(it, color = Muted, fontSize = 12.sp) }
            Spacer(Modifier.height(4.dp))
            val (txt, col) = when {
                s.locked -> "📌 kept" to Gold
                s.confidence == Confidence.SURE -> "✓ sure match" to Sure
                s.confidence == Confidence.LIKELY -> "likely – check the alternatives" to Likely
                else -> "unsure – check the alternatives" to Muted
            }
            Text(txt, color = col, fontSize = 12.sp)
        }
    }

    // printings: which ones exist and what each is worth
    if (s.priceCard.printings.size > 1) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.priceCard.printings.forEach { p ->
                FilterChip(selected = p.kind == s.selected?.kind, onClick = { vm.choosePrinting(p.kind) },
                    label = { Text("${p.kind.label} ${printingPrice(p)}", fontSize = 12.sp) })
            }
        }
        FoilLine(s)
    }
    s.richerPrinting?.let { r ->
        Text("⚠ The ${r.kind.label.lowercase()} of this card is worth ${printingPrice(r)} – check the finish!",
            color = Warn, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }

    Spacer(Modifier.height(8.dp))
    PriceBlock(s)
    if (settings.showPsa) {
        val printing = s.selected?.kind?.takeIf { s.priceCard.printings.size > 1 }
        s.graded?.let { PsaLine(it, printing = printing) }
        s.population?.let { GemLine(it) }
        if (s.gradedOnlyOtherPrintings && printing != null)
            Text("No PSA sales for the ${printing.label.lowercase()} yet", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
    }

    val others = s.versions.filter { it.id != s.version.id || it.lang != s.version.lang }
    if (others.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Other version or language?", color = Muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            others.forEach { v ->
                val label = if (v.id == s.version.id) "${LANG_NAMES[v.lang] ?: v.lang}: ${v.name}"
                            else "${v.setName} ${v.numberText} · ${v.lang.uppercase()}"
                FilterChip(selected = false, onClick = { vm.chooseVersion(v) }, label = { Text(label, fontSize = 12.sp) })
            }
        }
    }
    if (s.alternatives.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Not this card?", color = Muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.alternatives.forEach { alt ->
                FilterChip(selected = false, onClick = { vm.choose(alt) }, label = {
                    Text("${alt.info.name} ${alt.info.numberText} · ${eur(alt.info.bestValue)}", fontSize = 12.sp)
                })
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.next() }) { Text("Next card") }
        OutlinedButton({
            val q = URLEncoder.encode(s.priceCard.name, "UTF-8")
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.cardmarket.com/en/Pokemon/Products/Search?searchString=$q")))
        }) { Text("Cardmarket") }
    }
}

@Composable
fun FoilLine(s: Shown) {
    val f = s.foil
    val kinds = s.priceCard.printings.map { it.kind }
    val text = when {
        s.printingByUser -> null
        f == null -> if (Printing.Kind.REVERSE in kinds || Printing.Kind.HOLO in kinds) "Tilt the card a little to check holo / reverse" else null
        f.kind == FoilEstimate.Kind.REVERSE -> "✨ Foil outside the artwork → looks like a reverse holo"
        f.kind == FoilEstimate.Kind.HOLO -> "✨ Shimmering artwork → looks like a holo"
        f.kind == FoilEstimate.Kind.NORMAL -> "No foil seen → looks like the normal printing"
        else -> "Holo check unsure – tilt the card a little, or tap the printing"
    }
    if (text != null) Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun PriceBlock(s: Shown) {
    val p = s.selected
    if (p == null || (p.eur == null && p.usd == null)) {
        Text("No price for this card.", color = Muted)
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(if (p.eur != null) eur(p.eur) else usd(p.usd), color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(if (p.eur != null) "Cardmarket trend · ${p.kind.label}" else "TCGplayer · ${p.kind.label}", color = Muted,
            fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    val parts = buildList {
        if (p.eur30 != null) add("30-day avg ${eur(p.eur30)}")
        if (p.eurLow != null) add("from ${eur(p.eurLow)}")
        if (p.usd != null && p.eur != null) add("TCGplayer ${usd(p.usd)}")
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Color.White, fontSize = 13.sp)
    if (s.pricedFromEnglish) Text("Price of the English version (no price for this language)", color = Muted, fontSize = 11.sp)
    s.priceCard.prices?.updated?.let { Text("Prices from ${it.take(10)}", color = Muted, fontSize = 11.sp) }
}

@Composable
fun PsaLine(g: Graded, big: Boolean = false, printing: Printing.Kind? = null) {
    if (g.psa10 == null && g.psa9 == null && g.psa8 == null) return
    val size = if (big) 18.sp else 14.sp
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("PSA 10" to g.psa10, "PSA 9" to g.psa9, "PSA 8" to g.psa8).forEach { (label, v) ->
            Column {
                Text(label, color = Muted, fontSize = 11.sp)
                Text(usd(v), color = Color.White, fontSize = size, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (!big) Text("eBay sold${printing?.let { " · ${it.label}" } ?: ""} (PokemonPriceTracker)${g.updated?.let { ", $it" } ?: ""}",
        color = Muted, fontSize = 11.sp)
}

/** "Gem rate 11.3% · PSA 10: 340 of 3,000 graded" (PSA population report) */
@Composable
fun GemLine(p: Population, big: Boolean = false) {
    val rate = p.gemRate ?: p.psa10?.let { it.toDouble() / p.psaTotal } ?: return
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text("Gem rate ", color = Muted, fontSize = if (big) 14.sp else 12.sp)
        Text(String.format(Locale.getDefault(), "%.1f%%", rate * 100), color = Color.White,
            fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.Bold)
        Text(String.format(Locale.getDefault(), "  · PSA 10: %,d of %,d graded", p.psa10 ?: 0, p.psaTotal),
            color = Muted, fontSize = if (big) 14.sp else 12.sp)
    }
}

@Composable
private fun DebugPanel(d: DebugInfo) {
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(8.dp)) {
        d.preview?.let {
            Image(it.asImageBitmap(), "what the scanner saw", Modifier.width(80.dp).aspectRatio(63f / 88f))
            Spacer(Modifier.width(8.dp))
        }
        Column { d.lines.forEach { Text(it, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } }
    }
}

@Composable
fun SettingsDialog(state: UiState, vm: ScannerViewModel, onClose: () -> Unit) {
    val s = state.settings
    var title by remember { mutableStateOf(s.title) }
    AlertDialog(
        onDismissRequest = { vm.updateSettings { it.copy(title = title) }; onClose() },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Alert / HIT for cards worth at least", color = Muted, fontSize = 13.sp)
                listOf(0.0, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0).chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { v ->
                            FilterChip(selected = v == s.threshold, onClick = { vm.updateSettings { it.copy(threshold = v) } },
                                label = { Text(if (v == 0.0) "off" else "${v.toInt()} €") })
                        }
                    }
                }
                Text("Wait before taking the card (frame green)", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp))
                listOf(0, 300, 600, 1000, 1500, 2000).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { v ->
                            FilterChip(selected = v == s.triggerDelayMs, onClick = { vm.updateSettings { it.copy(triggerDelayMs = v) } },
                                label = { Text(if (v == 0) "instant" else if (v < 1000) "0.${v / 100} s" else "${v / 1000}${if (v % 1000 != 0) ".${v % 1000 / 100}" else ""} s") })
                        }
                    }
                }
                Text("Energy cards", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "recognise", 1 to "skip common", 2 to "skip all").forEach { (v, label) ->
                        FilterChip(selected = v == s.energyFilter, enabled = v == 0 || state.hasCategories,
                            onClick = { vm.updateSettings { it.copy(energyFilter = v) } }, label = { Text(label) })
                    }
                }
                Text(if (state.hasCategories) "\"skip common\" still recognises gold and secret rare energies"
                     else "needs the next data pack", color = Muted, fontSize = 12.sp)
                Text("Card must fill the frame at least", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 30, 50, 70, 85).forEach { v ->
                        FilterChip(selected = v == s.minFillPercent, onClick = { vm.updateSettings { it.copy(minFillPercent = v) } },
                            label = { Text(if (v == 0) "off" else "$v%") })
                    }
                }
                Text("against \"ghost\" cards: smaller or off-centre cards, and the frame without a card outline, are ignored",
                    color = Muted, fontSize = 12.sp)
                SettingSwitch("PSA 10 / 9 / 8 prices", if (state.hasPsaData) "eBay sold averages, USD" else "not in this data pack yet",
                    s.showPsa) { v -> vm.updateSettings { it.copy(showPsa = v) } }
                SettingSwitch("YouTube mode", "full screen, price reveal, pack value; follows the phone's orientation",
                    s.youtube) { v -> vm.updateSettings { it.copy(youtube = v, title = title) } }
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text("YouTube title line (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                SettingSwitch("Details", "what the scanner saw, scores, text it read, holo check", s.showDebug) { v ->
                    vm.updateSettings { it.copy(showDebug = v) }
                }
            }
        },
        confirmButton = { TextButton({ vm.updateSettings { it.copy(title = title) }; onClose() }) { Text("Done") } },
    )
}

@Composable
private fun SettingSwitch(label: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/**
 * Double tap on the camera picture: take the card in the frame now (cards the scanner isn't
 * sure about). A short white flash, like a camera shutter, confirms it.
 */
@Composable
fun ManualTrigger(state: UiState, vm: ScannerViewModel) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.flash) {
        if (state.flash > 0) {
            flash.snapTo(0.75f)
            flash.animateTo(0f, tween(380))
        }
    }
    Box(Modifier.fillMaxSize()
        .background(Color.White.copy(alpha = flash.value))
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { vm.manualTrigger() }) })
}

@Composable
fun GuideOverlay(state: UiState, modifier: Modifier, dim: Boolean) {
    Canvas(modifier) {
        val prior = Readings.guidePrior(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        val gh = (prior.h * size.height).toFloat()
        val gw = gh * 63f / 88f
        val tl = Offset((size.width - gw) / 2, (size.height - gh) / 2)
        val col = when (state.liveConfidence) { Confidence.SURE -> Sure; Confidence.LIKELY -> Likely; else -> Color.White }
        if (dim) {
            val guide = Path().apply { addRoundRect(RoundRect(tl.x, tl.y, tl.x + gw, tl.y + gh, CornerRadius(18f, 18f))) }
            clipPath(guide, clipOp = ClipOp.Difference) { drawRect(Color(0x66000000)) }
            drawPath(guide, col, style = Stroke(width = 4f))
        } else {
            // corner brackets only: a clean picture for videos
            val l = gw * 0.16f
            val stroke = 6f
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(col, Offset(x, y), Offset(x + dx * l, y), stroke, StrokeCap.Round)
                drawLine(col, Offset(x, y), Offset(x, y + dy * l), stroke, StrokeCap.Round)
            }
            corner(tl.x, tl.y, 1f, 1f); corner(tl.x + gw, tl.y, -1f, 1f)
            corner(tl.x, tl.y + gh, 1f, -1f); corner(tl.x + gw, tl.y + gh, -1f, -1f)
        }
        // hold still: the green bar under the frame fills up until the card is taken
        state.liveProgress?.takeIf { it > 0f && state.liveConfidence == Confidence.SURE }?.let { p ->
            val y = tl.y + gh + 14f
            drawLine(Color.White.copy(alpha = 0.25f), Offset(tl.x, y), Offset(tl.x + gw, y), 8f, StrokeCap.Round)
            drawLine(Sure, Offset(tl.x, y), Offset(tl.x + gw * p, y), 8f, StrokeCap.Round)
        }
        val q = state.liveQuad
        val f = state.liveFrame
        if (q != null && f != null && !state.paused && dim) {
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
fun CameraPreview(onFrame: (org.opencv.core.Mat) -> Unit, torch: Boolean, modifier: Modifier) {
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

/** Labels shared by both screens. */
fun printingPrice(p: Printing): String = if (p.eur != null) eur(p.eur) else usd(p.usd)

@Suppress("unused")
private fun CardInfo.title(): String = "$name · $setName $numberText"
