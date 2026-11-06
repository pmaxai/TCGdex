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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import io.github.pmaxai.cardscanner.data.CardInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.vision.FoilEstimate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Recording mode for unboxing / price-check videos: phone on a tripod, screen recording on.
 * Full screen, camera and card half and half, an animated reveal for every new card, a HIT
 * effect above the alert price, and the pack's running value. ⚙ next to the pack value
 * opens the menu (save pack, new pack, light, settings ...).
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
    var torch by rememberSaveable { mutableStateOf(false) }
    val menu: @Composable () -> Unit = {
        MenuButton(state, vm, torch, onTorch = { torch = it }, onSettings = { vm.openSettings(SettingsPage.YOUTUBE) },
            onSave = { savePack.launch(PackCsv.fileName(state.settings)) })
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                CameraArea(state, vm, torch, video = true, modifier = Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    TitleLine(state.settings.title)
                    Reveal(state, vm, Modifier.weight(1f).fillMaxWidth())
                    SessionBar(state, menu, vm::addAgain)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TitleLine(state.settings.title)
                CameraArea(state, vm, torch, video = true, modifier = Modifier.fillMaxWidth().weight(1f))
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Reveal(state, vm, Modifier.weight(1f).fillMaxWidth())
                    SessionBar(state, menu, vm::addAgain)
                }
            }
        }
    }
}

@Composable
private fun TitleLine(title: String) {
    if (title.isBlank()) return
    FitText(title, 20.sp, Color.White, Modifier.fillMaxWidth().padding(8.dp), weight = FontWeight.Bold, align = TextAlign.Center)
}

@Composable
private fun Reveal(state: UiState, vm: ScannerViewModel, modifier: Modifier) {
    val shown = state.shown
    Box(modifier, contentAlignment = Alignment.Center) {
        if (shown == null) {
            FitText("Show the next card…", 22.sp, Muted, align = TextAlign.Center)
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
                        val s = state.shown ?: shown
                        RevealCard(s, state.settings, state.session.count { it.group == s.group }, vm::choosePrinting,
                            vm::stepVersion, vm::chooseVersion, vm::choose) { vm.openSearch(SearchMode.REPLACE) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RevealCard(s: Shown, settings: Settings, copies: Int, onPrinting: (Printing.Kind) -> Unit, onStep: (Int) -> Unit,
                       onVersion: (CardInfo) -> Unit, onOther: (Candidate) -> Unit, onSearch: () -> Unit) {
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
                Box {
                    CardImage(s, Modifier.width(120.dp).aspectRatio(63f / 88f))
                    // the same card more than once in this pack (+1)
                    if (copies > 1) Text("×$copies", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                            .background(Gold, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    FitText(s.version.name, 30.sp, Color.White, weight = FontWeight.Black, minSize = 16.sp)
                    s.english?.let { FitText(it.name, 16.sp, Color.White.copy(alpha = 0.8f)) }
                    FitText(s.version.setLine(withLang = false), 15.sp, Muted)
                    VersionSwitcher(s, onStep, onVersion, onOther, onSearch)
                    if (s.priceCard.printings.size <= 1) s.selected?.let {
                        FitText(it.kind.label, 15.sp, Gold, weight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    // sized for the final price, so it doesn't jump while counting up
                    FitText(if (value == null) "–" else eur(count.value.toDouble()), 46.sp, if (hit) Gold else Color.White,
                        weight = FontWeight.Black, sizing = if (value == null) "–" else eur(value))
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
 * Lays the card out at a fixed width ([designWidth]) and scales it evenly to the space it
 * has: the same look in portrait and landscape, nothing wrapped, cut off or squeezed
 * (a HIT card with English name, PSA prices and printing chips just gets a bit smaller).
 */
@Composable
private fun FitInside(designWidth: Dp = 440.dp, maxScale: Float = 1.25f, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val dw = designWidth.roundToPx()
        val p = measurables.first().measure(Constraints(maxWidth = dw, maxHeight = Constraints.Infinity))
        val availW = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width
        val availH = if (constraints.hasBoundedHeight) constraints.maxHeight else p.height
        val scale = minOf(maxScale, availW.toFloat() / p.width.coerceAtLeast(1), availH.toFloat() / p.height.coerceAtLeast(1))
            .coerceAtLeast(0.2f)
        layout((p.width * scale).roundToInt().coerceAtMost(availW), (p.height * scale).roundToInt().coerceAtMost(availH)) {
            p.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Swipe to the left to take it out (of the pack, or a cart): a wrong match, or a card you don't count. */
@Composable
fun SwipeToRemove(key: Int, onRemove: () -> Unit, label: String = "Remove from pack", content: @Composable () -> Unit) {
    val offset = remember(key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(1f) }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        if (offset.value < -8f) {
            // what letting go will do
            Box(Modifier.matchParentSize().background(Color(0xFF7A1F1F), RoundedCornerShape(20.dp)).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd) {
                Text(if (offset.value < -width * REMOVE_AT) "Release to remove" else label,
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

/**
 * Other versions of the same artwork (sets, languages): ‹ › step through them, the middle
 * opens the list, with the other candidate cards below it ("not this card?").
 */
@Composable
private fun VersionSwitcher(s: Shown, onStep: (Int) -> Unit, onVersion: (CardInfo) -> Unit, onOther: (Candidate) -> Unit,
                            onSearch: () -> Unit) {
    val n = s.versions.size
    if (n < 2 && s.alternatives.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val chip = Modifier.background(Color(0xFF2A2D34), RoundedCornerShape(8.dp))
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (n > 1) Text(" ‹ ", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = chip.clickable { onStep(-1) }.padding(horizontal = 8.dp, vertical = 1.dp))
        Box {
            Text(if (n > 1) "Version ${s.versionIndex + 1}/$n · ${s.version.lang.uppercase()} ▾" else "Other card ▾",
                color = Color.White, fontSize = 14.sp, maxLines = 1, softWrap = false,
                modifier = chip.clickable { open = true }.padding(horizontal = 10.dp, vertical = 4.dp))
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                s.versions.forEach { v ->
                    val current = v.id == s.version.id && v.lang == s.version.lang
                    DropdownMenuItem(
                        text = {
                            Text("${if (current) "✓ " else ""}${v.name} · ${v.setLine(withLang = false)} · " +
                                "${LANG_NAMES[v.lang] ?: v.lang}  ${v.bestValue?.let { eur(it) } ?: "–"}", fontSize = 14.sp)
                        },
                        onClick = { open = false; if (!current) onVersion(v) })
                }
                if (s.alternatives.isNotEmpty()) {
                    if (n > 0) HorizontalDivider()
                    Text("Not this card?", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    s.alternatives.forEach { c ->
                        DropdownMenuItem(text = { Text("${c.info.name} · ${c.info.setLine(withLang = false)}", fontSize = 14.sp) },
                            onClick = { open = false; onOther(c) })
                    }
                }
                DropdownMenuItem(text = { Text("🔍 Search for the right card…", fontSize = 14.sp, color = Gold) },
                    onClick = { open = false; onSearch() })
            }
        }
        if (n > 1) Text(" › ", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = chip.clickable { onStep(+1) }.padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

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
                maxLines = 1, softWrap = false,
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
private fun SessionBar(state: UiState, menu: @Composable () -> Unit, onPlusOne: () -> Unit) {
    val items = state.session
    val threshold = state.settings.threshold
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val line = buildAnnotatedString {
                withStyle(SpanStyle(color = Muted, fontSize = 15.sp)) { append("${items.size} cards   Pack value ") }
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(eur(state.sessionValue)) }
            }
            FitText(line, 22.sp, Color.White, Modifier.weight(1f))
            // +1: the shown card once more (two of the same in a row)
            val can = state.shown != null
            Box(Modifier.padding(end = 8.dp).size(44.dp).border(2.dp, if (can) Gold else Muted.copy(alpha = 0.4f), CircleShape)
                .clickable(enabled = can, onClick = onPlusOne), contentAlignment = Alignment.Center) {
                Text("+1", color = if (can) Gold else Muted.copy(alpha = 0.4f), fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            menu()
        }
        if (items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.asReversed().take(12).forEach { item ->
                    val hit = threshold > 0 && (item.value ?: 0.0) >= threshold
                    Text("${item.name} ${eur(item.value)}", color = if (hit) Ink else Color.White, fontSize = 13.sp,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.background(if (hit) Gold else Panel, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

/** The settings button (⚙) with the YouTube mode's menu. */
@Composable
private fun MenuButton(state: UiState, vm: ScannerViewModel, torch: Boolean, onTorch: (Boolean) -> Unit, onSettings: () -> Unit,
                       onSave: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(44.dp).background(Panel, CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
            Text("⚙", color = Color.White, fontSize = 22.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items: List<Triple<String, Boolean, () -> Unit>> = listOf(
                Triple("Save pack (CSV)", state.session.isNotEmpty(), onSave),
                Triple("New pack (reset value)", true) { vm.resetSession() },
                Triple("Remove last card", state.session.isNotEmpty()) { vm.undoLast() },
                Triple("Next card", true) { vm.next() },
                Triple(if (torch) "Light off" else "Light on", true) { onTorch(!torch) },
                Triple("Search a card…", true) { vm.openSearch(SearchMode.LOOK_UP) },
                Triple("Settings…", true, onSettings),
                Triple("Exit YouTube mode", true) { vm.updateSettings { it.copy(youtube = false) } },
            )
            items.forEachIndexed { i, (label, enabled, action) ->
                if (i == 5) HorizontalDivider()
                DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { open = false; action() })
            }
        }
    }
}
