package io.github.pmaxai.cardscanner.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.TransformOrigin
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.vision.FoilEstimate
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Recording mode for unboxing / price-check videos: phone on a tripod, screen recording on.
 * Full screen, no buttons in the picture (tap the panel for controls), an animated reveal
 * for every new card, a HIT effect above the alert price, and the pack's running value.
 */
@Composable
fun YouTubeScreen(state: UiState, vm: ScannerViewModel) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val ctx = LocalContext.current
    val savePack = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val r = vm.savePack(ctx.contentResolver, uri)
            Toast.makeText(ctx, r.fold({ "Pack saved: $it cards" }, { "Could not save: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
    var controls by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    LaunchedEffect(controls) { if (controls) { delay(6000); controls = false } }
    val tap = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                CameraBox(state, vm, torch, Modifier.fillMaxHeight().aspectRatio(4f / 3f))
                Column(Modifier.weight(1f).fillMaxHeight().then(tap).padding(16.dp)) {
                    TitleLine(state.settings.title)
                    Reveal(state, vm, Modifier.weight(1f).fillMaxWidth())
                    SessionBar(state)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().then(tap)) { TitleLine(state.settings.title) }
                CameraBox(state, vm, torch, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
                Column(Modifier.weight(1f).fillMaxWidth().then(tap).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Reveal(state, vm, Modifier.weight(1f).fillMaxWidth())
                    SessionBar(state)
                }
            }
        }
        if (controls) Controls(state, vm, torch, onTorch = { torch = it }, onSettings = { settings = true; controls = false },
            onSave = { controls = false; savePack.launch(PackCsv.fileName(state.settings)) })
    }
    if (settings) SettingsDialog(state, vm) { settings = false }
}

@Composable
private fun CameraBox(state: UiState, vm: ScannerViewModel, torch: Boolean, modifier: Modifier) {
    Box(modifier.background(Color.Black)) {
        CameraPreview(onFrame = vm::onFrame, torch = torch, modifier = Modifier.fillMaxSize())
        GuideOverlay(state, Modifier.fillMaxSize(), dim = false)
    }
}

@Composable
private fun TitleLine(title: String) {
    if (title.isBlank()) return
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(8.dp))
}

@Composable
private fun Reveal(state: UiState, vm: ScannerViewModel, modifier: Modifier) {
    val shown = state.shown
    Box(modifier, contentAlignment = Alignment.Center) {
        if (shown == null) {
            Text("Show the next card…", color = Muted, fontSize = 22.sp)
        } else {
            AnimatedContent(
                targetState = shown.serial,
                transitionSpec = {
                    (scaleIn(initialScale = 0.55f, animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) +
                        fadeIn(tween(120))) togetherWith fadeOut(tween(120))
                },
                label = "reveal",
            ) { serial ->
                FitInside {
                    SwipeToRemove(serial, onRemove = vm::dismissShown) {
                        RevealCard(state.shown ?: shown, state.settings, vm::choosePrinting)
                    }
                }
            }
        }
    }
}

@Composable
private fun RevealCard(s: Shown, settings: Settings, onPrinting: (Printing.Kind) -> Unit) {
    val value = s.value
    val hit = settings.threshold > 0 && (value ?: 0.0) >= settings.threshold
    val count = remember(s.serial) { Animatable(0f) }
    LaunchedEffect(s.serial, value) { count.animateTo((value ?: 0.0).toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    val glow = rememberInfiniteTransition(label = "glow")
    val pulse by glow.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")

    Box(Modifier.fillMaxWidth()
        .background(
            if (hit) Brush.radialGradient(listOf(Gold.copy(alpha = pulse), Color.Transparent)) else Brush.linearGradient(listOf(Panel, Panel)),
            RoundedCornerShape(20.dp))
        .border(if (hit) 3.dp else 1.dp, if (hit) Gold else Color(0xFF2A2D34), RoundedCornerShape(20.dp))
        .padding(14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val placeholder = s.preview?.let { BitmapPainter(it.asImageBitmap()) }
                AsyncImage(model = s.version.thumbnailUrl ?: s.priceCard.thumbnailUrl, contentDescription = s.version.name,
                    placeholder = placeholder, error = placeholder, fallback = placeholder,
                    modifier = Modifier.width(110.dp).aspectRatio(63f / 88f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.version.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
                    s.english?.let { Text(it.name, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp) }
                    Text("${s.version.setName} · ${s.version.numberText}", color = Muted, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (s.priceCard.printings.size <= 1) s.selected?.let {
                        Text(it.kind.label, color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (value == null) "–" else eur(count.value.toDouble()), color = if (hit) Gold else Color.White,
                        fontSize = 46.sp, fontWeight = FontWeight.Black)
                    if (settings.showPsa) s.graded?.let { PsaLine(it, big = true) }
                    if (settings.showPsa) s.population?.let { GemLine(it, big = true) }
                }
            }
            if (s.priceCard.printings.size > 1) PrintingChips(s, onPrinting, Modifier.padding(top = 8.dp))
        }
        if (hit) {
            HitBadge(s.serial, Modifier.align(Alignment.TopEnd))
            Burst(s.serial)
        }
    }
}

/**
 * Shows the content at its natural size, or scaled down evenly when the space is too small
 * (a HIT card with English name, PSA prices and printing chips): never cut off or squeezed.
 */
@Composable
private fun FitInside(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else p.height
        val scale = if (p.height > maxH && p.height > 0) maxH.toFloat() / p.height else 1f
        layout(p.width, (p.height * scale).roundToInt()) {
            p.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }
}

/** Swipe the card to the left to take it out of the pack (a wrong match, or a card you don't count). */
@Composable
private fun SwipeToRemove(key: Int, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember(key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(1f) }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        if (offset.value < -8f) {
            // what letting go will do
            Box(Modifier.matchParentSize().background(Color(0xFF7A1F1F), RoundedCornerShape(20.dp)).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd) {
                Text(if (offset.value < -width * REMOVE_AT) "Release to remove" else "Remove from pack",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer { alpha = 1f - (-offset.value / width).coerceIn(0f, 0.6f) }
            .pointerInput(key) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (offset.value < -width * REMOVE_AT) {
                                offset.animateTo(-width * 1.1f, tween(160))
                                onRemove()
                            } else offset.animateTo(0f, spring(dampingRatio = 0.6f))
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, drag ->
                    change.consume()
                    scope.launch { offset.snapTo((offset.value + drag).coerceIn(-width * 1.1f, 0f)) }
                }
            }) { content() }
    }
}

private const val REMOVE_AT = 0.3f

/** Every printing of the card with its price; tap to say which one is in front of the camera. */
@Composable
private fun PrintingChips(s: Shown, onPrinting: (Printing.Kind) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.priceCard.printings.forEach { p ->
            val sel = p.kind == s.selected?.kind
            val detected = s.foil?.let { f ->
                (f.kind == FoilEstimate.Kind.REVERSE && p.kind == Printing.Kind.REVERSE) ||
                    (f.kind == FoilEstimate.Kind.HOLO && p.kind == Printing.Kind.HOLO)
            } == true
            Text("${p.kind.label}${if (detected) " ✨" else ""} ${printingPrice(p)}",
                color = if (sel) Ink else Color.White, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .background(if (sel) Gold else Color(0xFF2A2D34), RoundedCornerShape(10.dp))
                    .clickable { onPrinting(p.kind) }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun HitBadge(key: Int, modifier: Modifier) {
    val pop = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow)) }
    Text("HIT!", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black,
        modifier = modifier.scale(0.3f + 0.7f * pop.value).rotate(-12f)
            .background(Gold, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 2.dp))
}

private data class Particle(val angle: Float, val speed: Float, val radius: Float, val color: Color)

@Composable
private fun BoxScope.Burst(key: Int) {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(1600, easing = LinearOutSlowInEasing)) }
    val parts = remember(key) {
        val rnd = Random(key)
        val colors = listOf(Gold, Color.White, Color(0xFFFFE08A), Color(0xFFFFB347))
        List(48) { Particle(rnd.nextFloat() * 6.283f, 0.35f + rnd.nextFloat() * 0.65f, 3f + rnd.nextFloat() * 6f, colors[it % colors.size]) }
    }
    Canvas(Modifier.matchParentSize()) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val c = Offset(size.width * 0.3f, size.height * 0.5f)
        val reach = size.minDimension * 0.9f
        for (q in parts) {
            val d = reach * q.speed * p
            drawCircle(q.color.copy(alpha = (1f - p).coerceIn(0f, 1f)), q.radius * (1f - 0.5f * p),
                Offset(c.x + cos(q.angle) * d, c.y + sin(q.angle) * d))
        }
    }
}

@Composable
private fun SessionBar(state: UiState) {
    val items = state.session
    val threshold = state.settings.threshold
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${items.size} cards", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.width(12.dp))
            Text("Pack value ", color = Muted, fontSize = 15.sp)
            Text(eur(state.sessionValue), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.asReversed().take(12).forEach { item ->
                    val hit = threshold > 0 && (item.value ?: 0.0) >= threshold
                    Text("${item.name} ${eur(item.value)}", color = if (hit) Ink else Color.White, fontSize = 13.sp,
                        modifier = Modifier.background(if (hit) Gold else Panel, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Controls(state: UiState, vm: ScannerViewModel, torch: Boolean, onTorch: (Boolean) -> Unit, onSettings: () -> Unit,
                     onSave: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.padding(12.dp).background(Color(0xE61A1C21), RoundedCornerShape(14.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onSave, enabled = state.session.isNotEmpty()) { Text("Save pack (CSV)") }
            FilledTonalButton({ vm.resetSession() }) { Text("New pack (reset value)") }
            FilledTonalButton({ vm.undoLast() }, enabled = state.session.isNotEmpty()) { Text("Remove last card") }
            FilledTonalButton({ vm.next() }) { Text("Next card") }
            FilledTonalButton({ onTorch(!torch) }) { Text(if (torch) "Light off" else "Light on") }
            FilledTonalButton(onSettings) { Text("Settings") }
            FilledTonalButton({ vm.updateSettings { it.copy(youtube = false) } }) { Text("Exit YouTube mode") }
        }
    }
}
