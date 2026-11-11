package io.github.pmaxai.cardscanner.ui

import android.Manifest
import android.content.res.Configuration
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.pmaxai.cardscanner.camera.FrameAnalyzer
import io.github.pmaxai.cardscanner.camera.loadPhoto
import io.github.pmaxai.cardscanner.data.CardInfo
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import io.github.pmaxai.cardscanner.data.Graded
import io.github.pmaxai.cardscanner.trading.Condition
import io.github.pmaxai.cardscanner.trading.Reference
import io.github.pmaxai.cardscanner.data.Population
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.vision.FoilEstimate
import io.github.pmaxai.cardscanner.vision.Readings
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

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

    // orientation setting (both modes); YouTube mode also hides the system bars
    val activity = ctx as? Activity
    // a recording keeps the orientation it started in (a turn would change the video's frame size)
    LaunchedEffect(state.settings.orientation, state.recording.recording) {
        activity?.requestedOrientation = if (state.recording.recording) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else orientationOf(state.settings.orientation)
    }
    LaunchedEffect(state.settings.youtube) {
        activity ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (state.settings.youtube) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // back in the app: a newer build? (and after "allow from this source": go on installing)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.onResume()
            // home, lock, a call: a recording ends, so no other app ends up in the video
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.onAppStopped()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize()) {
            // a newer app with newer prices (not over the YouTube picture)
            state.update?.takeIf { !state.loading && !state.settings.youtube && state.settingsPage == null && !state.licence.locked }?.let { u ->
                UpdateBanner(u, state.pricesDate, onUpdate = vm::startUpdate, onLater = vm::dismissUpdate)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !openCvReady -> Message("OpenCV could not be loaded on this phone.")
                    state.error != null -> Message("Could not start: ${state.error}")
                    // without a licence (or after another device took it over) only the licence screen
                    state.licence.locked -> LicenceGate(state.licence, onKey = { vm.activateLicence(it) },
                        camera = { m -> QrCamera(m) { key -> vm.activateLicence(key, scanned = true) } })
                    state.loading -> Loading(state.loadingText)
                    state.settingsPage != null -> SettingsScreen(state, vm, state.settingsPage ?: SettingsPage.MODE)
                    !granted -> Message("The scanner needs the camera.") { Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } }
                    state.settings.youtube -> YouTubeScreen(state, vm)
                    state.settings.trading -> TradingScreen(state, vm)
                    state.settings.webshop -> WebshopScreen(state, vm)
                    else -> Scanner(state, vm)
                }
            }
        }
        // after a double tap: the most likely cards to pick from
        state.picks?.let { PickList(it, state.shown, onPick = vm::pick, onSearch = { vm.openSearch(SearchMode.REPLACE) },
            onClose = vm::closePicks) }
        state.search?.let { SearchDialog(it, vm) }
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
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var torch by rememberSaveable { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) loadPhoto(ctx.contentResolver, uri)?.let { vm.onPhoto(it) }
    }

    val topBar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // look up any card, without it in front of the camera
            Text("🔍  Search cards", color = Muted, fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 6.dp).weight(1f).background(Panel, RoundedCornerShape(20.dp))
                    .clickable { vm.openSearch(SearchMode.LOOK_UP) }.padding(horizontal = 12.dp, vertical = 8.dp))
            AssistChip(onClick = { vm.openSettings(SettingsPage.PRICES) }, label = {
                Text(if (state.settings.threshold > 0) "Alert ≥ ${state.settings.threshold.toInt()} €" else "Alert off", maxLines = 1)
            })
            TextButton({ torch = !torch }) { Text("Light", color = if (torch) Gold else Color.White, maxLines = 1) }
            TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("Photo", color = Color.White, maxLines = 1)
            }
            TextButton({ vm.openSettings() }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
        }
    }
    val camera: @Composable (Modifier) -> Unit = { m ->
        CameraArea(state, vm, torch, video = false, modifier = m) {
            if (!state.paused) {
                val hint = when {
                    state.shown?.locked == true -> "Kept – tap “Next card” for the next one"
                    state.liveConfidence == Confidence.SURE -> "Got it"
                    state.liveConfidence == Confidence.LIKELY -> "Probably…"
                    else -> "Fit the card into the frame"
                }
                Text(hint, color = Color.White, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .background(Color(0x88000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
    val result: @Composable (Modifier) -> Unit = { m ->
        Column(m.verticalScroll(rememberScrollState()).padding(12.dp)) {
            val s = state.shown
            if (s == null) {
                Text("Hold a card inside the frame. Its name and Cardmarket price appear here; " +
                        "the phone vibrates when any printing of it is worth at least your alert price. " +
                        "Not recognised? Double-tap the picture to take it anyway. Pinch to zoom.", color = Muted)
            } else {
                ResultCard(s, state.settings, vm)
            }
            if (state.settings.showDebug || (s?.fromPhoto == true && !s.fromSearch)) state.debug?.let { DebugPanel(it) }
        }
    }

    // camera and result share the screen half and half
    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            camera(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                topBar()
                result(Modifier.weight(1f).fillMaxWidth())
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            topBar()
            camera(Modifier.fillMaxWidth().weight(1f))
            result(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** Screen orientation for the setting: "auto" follows the phone, the others stay put. */
fun orientationOf(setting: String): Int = when (setting) {
    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
}

/**
 * The camera picture with the guide frame. Double tap takes the card by hand, pinch zooms.
 * The frames are cut to the box's shape, so any box size works (half the screen).
 */
@Composable
fun CameraArea(state: UiState, vm: ScannerViewModel, torch: Boolean, video: Boolean, modifier: Modifier,
               still: Boolean = false, extra: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.background(Color.Black).clipToBounds()
        .onSizeChanged { if (it.width > 0 && it.height > 0) vm.setViewAspect(it.width.toFloat() / it.height) }) {
        CameraPreview(onFrame = vm::onFrame, torch = torch, zoom = state.settings.zoom, onMaxZoom = vm::setMaxZoom,
            modifier = Modifier.fillMaxSize(), recordCamera = video, still = still)
        GuideOverlay(state, Modifier.fillMaxSize(), dim = !video)
        CameraGestures(state, vm, alwaysShowZoom = !video)
        extra()
        // a photo or a looked-up card is shown: the camera waits
        if (state.paused) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { vm.resume() },
                contentAlignment = Alignment.Center) {
                Text(when {
                    state.shown?.fromSearch == true -> "Looked up · tap to scan again"
                    state.settings.webshop && state.shown != null -> "Kept while you fill in · tap to scan again"
                    else -> "Tap to scan again"
                },
                    color = Color.White, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun ResultCard(s: Shown, settings: Settings, vm: ScannerViewModel) {
    val ctx = LocalContext.current
    val c = s.version
    Row {
        CardImage(s, Modifier.width(96.dp).aspectRatio(63f / 88f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.setLine(), color = Muted, fontSize = 13.sp)
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
    PrintedOnlyHint(s)

    Spacer(Modifier.height(8.dp))
    PriceBlock(s)
    PriceHistoryLine(s)
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
                            else v.setLine()
                FilterChip(selected = false, onClick = { vm.chooseVersion(v) }, label = { Text(label, fontSize = 12.sp) })
            }
        }
    }
    if (!s.fromSearch) {
        Spacer(Modifier.height(8.dp))
        Text("Not this card?", color = Muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.alternatives.forEach { alt ->
                FilterChip(selected = false, onClick = { vm.choose(alt) }, label = {
                    Text("${alt.info.name} ${alt.info.numberText} · ${eur(alt.info.bestValue)}", fontSize = 12.sp)
                })
            }
            FilterChip(selected = false, onClick = { vm.openSearch(SearchMode.REPLACE) }, label = { Text("🔍 Search…", fontSize = 12.sp) })
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
        Text(when {
            p.eur != null -> eur(p.eur)
            p.usdEur != null -> "≈ ${eur(p.usdEur)}"
            else -> usd(p.usd)
        }, color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(when {
            p.eur != null -> "Cardmarket trend · ${p.kind.label}"
            p.usdEur != null -> "TCGplayer in € · ${p.kind.label}"
            else -> "TCGplayer · ${p.kind.label}"
        }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    val parts = buildList {
        if (p.eur30 != null) add("30-day avg ${eur(p.eur30)}")
        if (p.eurLow != null) add("from ${eur(p.eurLow)}")
        if (p.usd != null && (p.eur != null || p.usdEur != null)) add("TCGplayer ${usd(p.usd)}")
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Color.White, fontSize = 13.sp)
    s.priceCard.priceCheck?.let { Text("⚠ ${it.message}", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
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
                Text(label, color = Muted, fontSize = 11.sp, maxLines = 1, softWrap = false)
                Text(usd(v), color = Color.White, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
    if (!big) Text("eBay sold${printing?.let { " · ${it.label}" } ?: ""} (PokemonPriceTracker)${g.updated?.let { ", $it" } ?: ""}",
        color = Muted, fontSize = 11.sp)
}

/** "Gem rate 11.3% · PSA 10: 340 of 3,000 graded" (PSA population report), on one line */
@Composable
fun GemLine(p: Population, big: Boolean = false) {
    val rate = p.gemRate ?: p.psa10?.let { it.toDouble() / p.psaTotal } ?: return
    val small = if (big) 14.sp else 12.sp
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = Muted, fontSize = small)) { append("Gem rate ") }
        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
            append(String.format(Locale.getDefault(), "%.1f%%", rate * 100))
        }
        withStyle(SpanStyle(color = Muted, fontSize = small)) {
            append(String.format(Locale.getDefault(), "  · PSA 10: %,d of %,d graded", p.psa10 ?: 0, p.psaTotal))
        }
    }
    FitText(text, if (big) 18.sp else 14.sp, Color.White, Modifier.padding(top = 4.dp))
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

/**
 * Touches on the camera picture. Double tap: take the card in the frame now (cards the
 * scanner isn't sure about); a short white flash, like a camera shutter, confirms it.
 * Pinch: zoom. The zoom shows while it changes (and all the time in scanner mode).
 */
@Composable
fun CameraGestures(state: UiState, vm: ScannerViewModel, alwaysShowZoom: Boolean) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.flash) {
        if (state.flash > 0) {
            flash.snapTo(0.75f)
            flash.animateTo(0f, tween(380))
        }
    }
    val zoom = state.settings.zoom
    var firstZoom by remember { mutableStateOf(zoom) }
    var recent by remember { mutableStateOf(false) }
    LaunchedEffect(zoom) {
        if (zoom == firstZoom) return@LaunchedEffect
        firstZoom = Float.NaN
        recent = true
        delay(1500)
        recent = false
    }
    Box(Modifier.fillMaxSize()
        .background(Color.White.copy(alpha = flash.value))
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { vm.manualTrigger() }) }
        .pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> if (z != 1f) vm.zoomBy(z) } }) {
        if (recent || (alwaysShowZoom && zoom > 1.01f)) {
            Text(zoomLabel(zoom), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(Color(0x88000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

fun zoomLabel(z: Float): String =
    if (kotlin.math.abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}×" else String.format(Locale.getDefault(), "%.1f×", z)

@Composable
fun GuideOverlay(state: UiState, modifier: Modifier, dim: Boolean) {
    Canvas(modifier) {
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        val g = Readings.guideQuad(w, h, Readings.guidePrior(w, h, state.settings.guideFraction))
        val gw = (g[1].x - g[0].x).toFloat()
        val gh = (g[3].y - g[0].y).toFloat()
        val tl = Offset(g[0].x.toFloat(), g[0].y.toFloat())
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
            val y = minOf(tl.y + gh + 14f, size.height - 8f)
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
fun CameraPreview(onFrame: (org.opencv.core.Mat) -> Unit, torch: Boolean, zoom: Float, onMaxZoom: (Float) -> Unit,
                  modifier: Modifier, recordCamera: Boolean = false, still: Boolean = false) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // a recording's clean camera file: bound next to preview and analysis while it runs (YouTube mode)
    val videoCapture by io.github.pmaxai.cardscanner.recording.CameraRecording.capture.collectAsStateWithLifecycle()
    val video = videoCapture.takeIf { recordCamera }
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // CameraX puts the zoom back to 1x whenever the camera is bound anew (a recording's camera file joins
    // or leaves, the Webshop still) or opens again (back in the app) - each time, the chosen zoom is set again
    var bindings by remember { mutableIntStateOf(0) }
    val maxZoomSeen by rememberUpdatedState(onMaxZoom)
    DisposableEffect(owner, video, still) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            // 4:3 frames for preview and analysis (same view); the view model cuts them to the box's shape
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
            // Webshop: a full-resolution still for product pictures, next to preview and analysis (without it,
            // a product picture is taken from the scanner's own frames)
            val photo = if (still && video == null) runCatching {
                io.github.pmaxai.cardscanner.shop.ProductPhoto.newCapture().also { pc ->
                    camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, pc)
                }
            }.onFailure { provider.unbindAll() }.getOrNull() else null
            io.github.pmaxai.cardscanner.shop.ProductPhoto.capture = photo
            // with the camera file: not every phone takes three use cases; then scanning goes on without it
            val withVideo = video?.let { v ->
                runCatching {
                    check(!io.github.pmaxai.cardscanner.recording.CameraRecording.simulateBindFailure) { "simulated" }
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, v)
                }.onFailure { provider.unbindAll() }.getOrNull()
            }
            camera = withVideo ?: camera.takeIf { photo != null } ?: provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            bindings++
            if (video != null) io.github.pmaxai.cardscanner.recording.CameraRecording.onBound(ctx, withVideo != null)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            io.github.pmaxai.cardscanner.shop.ProductPhoto.capture = null
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    DisposableEffect(camera, owner) {
        val c = camera ?: return@DisposableEffect onDispose {}
        val zoomSeen = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { z -> maxZoomSeen(z.maxZoomRatio) }
        val opened = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { s ->
            if (s.type == androidx.camera.core.CameraState.Type.OPEN) bindings++
        }
        c.cameraInfo.zoomState.observe(owner, zoomSeen)
        c.cameraInfo.cameraState.observe(owner, opened)
        onDispose {
            c.cameraInfo.zoomState.removeObserver(zoomSeen)
            c.cameraInfo.cameraState.removeObserver(opened)
        }
    }
    LaunchedEffect(zoom, camera, bindings) {
        val c = camera ?: return@LaunchedEffect
        applyZoom(c, zoom, ContextCompat.getMainExecutor(ctx))
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Sets the zoom (within what the camera can do) and waits until the camera has taken it. A camera
 * that is still starting refuses it ("not active"); then it is tried again, for a few seconds.
 */
private suspend fun applyZoom(c: Camera, zoom: Float, executor: java.util.concurrent.Executor) {
    repeat(30) {
        val max = c.cameraInfo.zoomState.value?.maxZoomRatio
        val target = if (max != null) zoom.coerceIn(1f, maxOf(1f, max)) else zoom.coerceAtLeast(1f)
        val f = c.cameraControl.setZoomRatio(target)
        val failure = kotlinx.coroutines.withTimeoutOrNull(2_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Throwable?> { cont ->
                f.addListener({ cont.resume(runCatching { f.get() }.exceptionOrNull()) }, executor)
            }
        } ?: java.util.concurrent.TimeoutException("no answer in 2 s")
        if (failure == null) return
        if (it == 0 || it == 29) android.util.Log.w("CardScanner", "zoom ${target}x not taken (try ${it + 1}): ${failure.cause ?: failure}")
        kotlinx.coroutines.delay(150)
    }
}

/** An Asian print the card data doesn't have (yet): which language it is, and that the western card is shown. */
@Composable
fun PrintedOnlyHint(s: Shown) {
    val lang = s.printedOnly ?: return
    val name = when (lang) { "ja" -> "Japanese"; "ko" -> "Korean"; "zh-tw" -> "Chinese (traditional)"; "zh-cn" -> "Chinese (simplified)"; else -> lang }
    val shown = if (s.version.lang in ScannerViewModel.ASIAN) "its Japanese original (same set and number)"
        else "the ${s.version.lang.uppercase()} card of the same picture"
    Text("Printed in $name – this print isn't in the card data; shown: $shown", color = Warn, fontSize = 12.sp,
        modifier = Modifier.padding(top = 2.dp))
}

/** Labels shared by both screens. */
fun printingPrice(p: Printing): String = if (p.eur != null) eur(p.eur) else usd(p.usd)

/** "Twilight Masquerade · 034/167 · DE · 2024" */
fun CardInfo.setLine(withLang: Boolean = true): String =
    listOfNotNull(setName.takeIf { it.isNotBlank() }, numberText.takeIf { it.isNotBlank() },
        lang.uppercase().takeIf { withLang }, year).joinToString(" · ")

@Suppress("unused")
private fun CardInfo.title(): String = "$name · $setName $numberText"
