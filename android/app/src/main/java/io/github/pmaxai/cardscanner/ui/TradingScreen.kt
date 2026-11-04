package io.github.pmaxai.cardscanner.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.github.pmaxai.cardscanner.trading.Cart
import io.github.pmaxai.cardscanner.trading.CartLine
import io.github.pmaxai.cardscanner.trading.Condition
import io.github.pmaxai.cardscanner.trading.LineStatus
import io.github.pmaxai.cardscanner.trading.TradingCsv
import io.github.pmaxai.cardscanner.trading.TradingMath
import io.github.pmaxai.cardscanner.trading.Verdict
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Good = Color(0xFF9BE3B0)

fun verdictColor(v: Verdict?): Color = when (v) {
    Verdict.GREAT -> Sure
    Verdict.GOOD -> Good
    Verdict.FAIR -> Color.White
    Verdict.EXPENSIVE -> Warn
    null -> Muted
}

/** A price to type on the number pad: what it is for, where it starts, and the market value for the quick buttons. */
class PadRequest(val title: String, val initial: Double?, val market: Double?, val onDone: (Double?) -> Unit)

/**
 * Trading Mode (card shows): scan a card, see its market value for its condition, type the
 * vendor's price for a verdict, collect cards per booth in a cart with the negotiated price.
 */
@Composable
fun TradingScreen(state: UiState, vm: ScannerViewModel) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var torch by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var cartOpen by rememberSaveable { mutableStateOf(false) }
    var dealsOpen by rememberSaveable { mutableStateOf(false) }
    var pad by remember { mutableStateOf<PadRequest?>(null) }

    val topBar: @Composable () -> Unit = {
        TradingTopBar(state, vm, torch, onTorch = { torch = it }, onCart = { cartOpen = true }, onDeals = { dealsOpen = true },
            onSettings = { settings = true })
    }
    val camera: @Composable (Modifier) -> Unit = { m ->
        CameraArea(state, vm, torch, video = false, modifier = m) {
            if (!state.paused) {
                val hint = when {
                    state.shown?.locked == true -> "Kept – show the next card"
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
    val panel: @Composable (Modifier) -> Unit = { m -> PricePanel(state, vm, m) { pad = it } }

    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            camera(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                topBar()
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            topBar()
            camera(Modifier.fillMaxWidth().weight(0.85f))
            panel(Modifier.fillMaxWidth().weight(1.15f))
        }
    }
    if (settings) SettingsDialog(state, vm) { settings = false }
    if (cartOpen) CartDialog(state, vm, onPad = { pad = it }) { cartOpen = false }
    if (dealsOpen) DealsDialog(state, vm) { dealsOpen = false }
    pad?.let { r -> NumberPad(r) { pad = null } }
}

@Composable
private fun TradingTopBar(state: UiState, vm: ScannerViewModel, torch: Boolean, onTorch: (Boolean) -> Unit, onCart: () -> Unit,
                          onDeals: () -> Unit, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cart = state.book.active
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🔍  Search", color = Muted, fontSize = 15.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(end = 6.dp).background(Panel, RoundedCornerShape(20.dp))
                .clickable { vm.openSearch(SearchMode.LOOK_UP) }.padding(horizontal = 12.dp, vertical = 8.dp))
        // the active cart: tap to open it
        Row(Modifier.weight(1f).background(Gold.copy(alpha = 0.14f), RoundedCornerShape(20.dp)).clickable(onClick = onCart)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🛒 ", fontSize = 14.sp)
            FitText(if (cart == null) "No cart yet" else "${cart.name} · ${cart.cards} · ${eur(cart.payTotal)}", 15.sp, Color.White,
                Modifier.weight(1f), weight = FontWeight.Bold)
        }
        Box {
            TextButton({ menu = true }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Deal history") }, onClick = { menu = false; onDeals() })
                DropdownMenuItem(text = { Text("New cart (next booth)") }, onClick = { menu = false; vm.newCart() })
                DropdownMenuItem(text = { Text(if (torch) "Light off" else "Light on") }, onClick = { menu = false; onTorch(!torch) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Settings…") }, onClick = { menu = false; onSettings() })
            }
        }
    }
}

/** Days since the prices were taken (null: unknown). */
private fun priceAgeDays(updated: String?): Long? = runCatching {
    ChronoUnit.DAYS.between(LocalDate.parse(updated!!.take(10)), LocalDate.now())
}.getOrNull()

@Composable
private fun PricePanel(state: UiState, vm: ScannerViewModel, modifier: Modifier, onPad: (PadRequest) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        val s = state.shown
        if (s == null) {
            Text("Show a card: its market value for its condition appears here. Type the vendor's price for a verdict, " +
                "then + Cart. Not recognised? Double-tap the picture, or search.", color = Muted)
        } else {
            CardPrice(s, state, vm, onPad)
        }
        if (state.tray.size > 1) Tray(state, vm)
        Spacer(Modifier.height(8.dp))
        Text(state.packInfo, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun CardPrice(s: Shown, state: UiState, vm: ScannerViewModel, onPad: (PadRequest) -> Unit) {
    val draft = state.draft
    val p = s.selected
    val ref = TradingMath.reference(p, state.settings.reference)
    val market = ref?.let { TradingMath.market(it, draft.condition) }

    Row {
        CardImage(s, Modifier.width(84.dp).aspectRatio(63f / 88f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            FitText(s.version.name, 22.sp, Color.White, weight = FontWeight.Bold, minSize = 14.sp)
            FitText(s.version.setLine(), 13.sp, Muted)
            s.english?.let { e -> FitText("English: ${e.name}", 12.sp, Color.White) }
            // condition: the camera can't judge it, so it's one tap here
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Condition.values().forEach { c ->
                    val sel = c == draft.condition
                    Text(c.label, color = if (sel) Ink else Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.background(if (sel) Gold else Color(0xFF2A2D34), RoundedCornerShape(8.dp))
                            .clickable { vm.setCondition(c) }.padding(horizontal = 9.dp, vertical = 4.dp))
                }
            }
        }
    }
    if (s.priceCard.printings.size > 1) {
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.priceCard.printings.forEach { pr ->
                FilterChip(selected = pr.kind == p?.kind, onClick = { vm.choosePrinting(pr.kind) },
                    label = { Text("${pr.kind.label} ${printingPrice(pr)}", fontSize = 12.sp, maxLines = 1) })
            }
        }
    }

    // market value for this condition, and where it comes from
    Spacer(Modifier.height(6.dp))
    if (market == null) {
        Text("No Cardmarket price" + (p?.usd?.let { " · TCGplayer ${usd(it)}" } ?: ""), color = Warn, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    } else {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(eur(market), color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            val t = TradingMath.trend(p)
            if (t != null && abs(t) >= 0.005) {
                Text("${if (t > 0) "▲" else "▼"} ${(abs(t) * 100).roundToInt()} % vs 30 days", color = if (t > 0) Sure else Warn,
                    fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Text("market value · ${draft.condition.label} = ${(draft.condition.share * 100).roundToInt()} % of the " +
            "${state.settings.reference.label.lowercase()} ${eur(ref)}", color = Muted, fontSize = 11.sp)
    }
    val parts = buildList {
        p?.eur?.let { add("trend ${eur(it)}") }
        p?.eur30?.let { add("30-day ${eur(it)}") }
        p?.eurLow?.let { add("from ${eur(it)}") }
        if (market != null) p?.usd?.let { add("TCGplayer ${usd(it)}") }
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Color.White, fontSize = 12.sp)
    if (state.settings.showPsa) s.graded?.let { PsaLine(it, printing = p?.kind?.takeIf { s.priceCard.printings.size > 1 }) }

    // warnings: a thin price is not to be trusted blindly
    val warnings = buildList {
        if (TradingMath.fewSales(p)) add("Few sales – check Cardmarket before you pay")
        if (s.pricedFromEnglish) add("Price of the English version (none for this language)")
        priceAgeDays(s.priceCard.prices?.updated)?.takeIf { it > 3 }?.let { add("Prices are $it days old – update the app before a show") }
    }
    warnings.forEach { Text("⚠ $it", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }

    // the vendor's price and the verdict
    Spacer(Modifier.height(8.dp))
    val asking = draft.asking
    val verdict = if (asking != null && market != null) TradingMath.verdict(asking, market) else null
    Row(Modifier.fillMaxWidth().border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(10.dp))
        .clickable { onPad(PadRequest("Asking price", asking, market) { vm.setAsking(it) }) }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (asking == null) "Type the asking price" else "Asking ${eur(asking)}",
            color = if (asking == null) Muted else Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (verdict != null) {
            Text(verdict.label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(verdictColor(verdict), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
    if (market != null) {
        val pct = asking?.let { " · asking = ${(it / market * 100).roundToInt()} % of market" } ?: ""
        Text("Offer ${eur(TradingMath.offer(market))} · walk away above ${eur(TradingMath.walkAway(market))}$pct",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }

    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Not this card?", color = Muted, fontSize = 12.sp)
        if (!s.fromSearch) s.alternatives.forEach { alt ->
            FilterChip(selected = false, onClick = { vm.choose(alt) }, label = { Text(alt.info.name, fontSize = 12.sp, maxLines = 1) })
        }
        FilterChip(selected = false, onClick = { vm.openSearch(SearchMode.REPLACE) }, label = { Text("🔍 Search…", fontSize = 12.sp) })
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { vm.addToCart() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text("+ Cart" + (state.book.active?.let { " (${it.name})" } ?: ""), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The last scanned cards; tap one to bring it back. */
@Composable
private fun Tray(state: UiState, vm: ScannerViewModel) {
    Spacer(Modifier.height(10.dp))
    Text("Last scans", color = Muted, fontSize = 12.sp)
    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val blank = remember { ColorPainter(Color(0xFF2A2D34)) }
        state.tray.asReversed().forEach { item ->
            val current = item.serial == state.shown?.serial
            Column(Modifier.width(52.dp).clickable { vm.showFromTray(item) }, horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(model = item.shown.imageCard?.thumbnailUrl, contentDescription = item.name, placeholder = blank, error = blank,
                    fallback = blank, modifier = Modifier.width(48.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(4.dp))
                        .border(if (current) 2.dp else 0.dp, if (current) Gold else Color.Transparent, RoundedCornerShape(4.dp)))
                Text(item.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------------- number pad
/** Big keys for prices: two or three taps; quick buttons from the market value. */
@Composable
fun NumberPad(r: PadRequest, onClose: () -> Unit) {
    val comma = java.text.DecimalFormatSymbols.getInstance().decimalSeparator
    var text by remember { mutableStateOf(r.initial?.let { String.format(Locale.ROOT, "%.2f", it).trimEnd('0').trimEnd('.') } ?: "") }
    fun value(): Double? = text.toDoubleOrNull()
    fun type(k: String) {
        text = when (k) {
            "⌫" -> text.dropLast(1)
            "." -> if (text.contains('.')) text else (text.ifEmpty { "0" } + ".")
            else -> if (text.contains('.') && text.substringAfter('.').length >= 2) text
                    else if (text == "0") k else (text + k).take(9)
        }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(r.title, color = Muted, fontSize = 14.sp)
                Text((if (text.isEmpty()) "–" else text.replace('.', comma)) + " €", color = Color.White, fontSize = 36.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(vertical = 6.dp))
                val m = r.market
                if (m != null) {
                    value()?.let { v ->
                        val vd = TradingMath.verdict(v, m)
                        Text("${(v / m * 100).roundToInt()} % of market ${eur(m)}" + (vd?.let { " · ${it.label}" } ?: ""),
                            color = verdictColor(vd), fontSize = 13.sp)
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("= market" to 1.0, "−10 %" to 0.9, "−20 %" to 0.8, "offer" to -1.0).forEach { (label, f) ->
                            val v = if (f < 0) TradingMath.offer(m) else TradingMath.round2(m * f)
                            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.background(Color(0xFF2A2D34), RoundedCornerShape(8.dp))
                                .clickable { text = String.format(Locale.ROOT, "%.2f", v) }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { k ->
                            Box(Modifier.size(width = 76.dp, height = 52.dp).background(Color(0xFF2A2D34), RoundedCornerShape(12.dp))
                                .clickable { type(k) }, contentAlignment = Alignment.Center) {
                                Text(if (k == ".") comma.toString() else k, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ r.onDone(null); onClose() }) { Text("Clear") }
                    Button({ r.onDone(value()?.let { TradingMath.round2(it) }); onClose() }) { Text("OK", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- cart
@Composable
private fun CartDialog(state: UiState, vm: ScannerViewModel, onPad: (PadRequest) -> Unit, onClose: () -> Unit) {
    val cart = state.book.active
    var switcher by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var showDropped by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && cart != null) {
            val r = vm.saveDeals(ctx.contentResolver, uri, listOf(cart))
            Toast.makeText(ctx, r.fold({ "Saved: $it cards" }, { "Could not save: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Row(Modifier.clickable { switcher = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            FitText(cart?.name ?: "No cart yet", 22.sp, Color.White, Modifier.weight(1f, fill = false), weight = FontWeight.Bold)
                            Text("  ▾", color = Muted, fontSize = 18.sp)
                        }
                        DropdownMenu(expanded = switcher, onDismissRequest = { switcher = false }) {
                            state.book.open.forEach { c ->
                                DropdownMenuItem(text = { Text("${if (c.id == cart?.id) "✓ " else ""}${c.name} · ${c.cards} cards · ${eur(c.payTotal)}") },
                                    onClick = { switcher = false; vm.selectCart(c.id) })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("New cart (next booth)") }, onClick = { switcher = false; vm.newCart() })
                            if (cart != null) {
                                DropdownMenuItem(text = { Text("Rename this cart") }, onClick = { switcher = false; rename = true })
                                DropdownMenuItem(text = { Text("Save as CSV") }, onClick = { switcher = false; export.launch(TradingCsv.fileName()) })
                                DropdownMenuItem(text = { Text("Delete this cart") }, onClick = { switcher = false; confirmDelete = true })
                            }
                        }
                    }
                    CloseButton(onClose)
                }
                if (cart == null || cart.lines.isEmpty()) {
                    Text("Nothing in this cart yet. Scan a card and tap + Cart; a new booth gets its own cart (⚙ → New cart).",
                        color = Muted, modifier = Modifier.padding(vertical = 16.dp))
                }
                if (cart != null) {
                    val active = cart.lines.filter { it.active }
                    val dropped = cart.lines.filter { !it.active }
                    LazyColumn(Modifier.weight(1f)) {
                        items(active, key = { it.id }) { l ->
                            SwipeToRemove(l.id.hashCode(), onRemove = { vm.updateLine(cart.id, l.id) { it.copy(status = LineStatus.DROPPED) } },
                                label = "Drop from the cart") {
                                CartLineRow(l) { editing = l.id }
                            }
                        }
                        if (dropped.isNotEmpty()) item {
                            Text("${if (showDropped) "▾" else "▸"} Dropped (${dropped.size})", color = Muted, fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().clickable { showDropped = !showDropped }.padding(vertical = 10.dp))
                        }
                        if (showDropped) items(dropped, key = { it.id }) { l -> CartLineRow(l) { editing = l.id } }
                    }
                    Totals(cart)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.newCart() }, modifier = Modifier.weight(1f)) { Text("Next booth", maxLines = 1) }
                        Button({ paying = true }, enabled = cart.activeLines.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text("Paid ${eur(cart.payTotal)}", fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    val c = cart ?: return
    editing?.let { id -> c.lines.firstOrNull { it.id == id }?.let { l -> LineEditor(c, l, vm, onPad) { editing = null } } }
    if (rename) {
        var name by remember { mutableStateOf(c.name) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Name of this cart") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("e.g. Max, table 14") }) },
            confirmButton = { TextButton({ vm.renameCart(c.id, name); rename = false }) { Text("Done") } })
    }
    if (paying) {
        AlertDialog(onDismissRequest = { paying = false }, title = { Text("Paid ${eur(c.payTotal)} at ${c.name}?") },
            text = { Text("The ${c.cards} cards count as bought, and the cart moves to the deal history. How did you pay?") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Cash", "Card", "Other").forEach { m -> TextButton({ vm.markPaid(c.id, m); paying = false; onClose() }) { Text(m) } }
                }
            },
            dismissButton = { TextButton({ paying = false }) { Text("Not yet") } })
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${c.name}?") },
            text = { Text("Its ${c.lines.size} lines are removed from the phone.") },
            confirmButton = { TextButton({ vm.deleteCart(c.id); confirmDelete = false }) { Text("Delete", color = Warn) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } })
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Box(Modifier.size(40.dp).background(Color(0xFF2A2D34), CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CartLineRow(l: CartLine, onClick: () -> Unit) {
    val blank = remember { ColorPainter(Color(0xFF2A2D34)) }
    val faded = if (l.active) 1f else 0.45f
    Row(Modifier.fillMaxWidth().background(Ink).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = l.image, contentDescription = l.name, placeholder = blank, error = blank, fallback = blank,
            modifier = Modifier.width(40.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text((if (l.qty > 1) "${l.qty}× " else "") + l.name, color = Color.White.copy(alpha = faded), fontSize = 15.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(l.condition.label, l.printingLabel, l.setLine).joinToString(" · "), color = Muted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("market ${eur(l.market)}" + if (l.status != LineStatus.CONSIDERING) " · ${l.status.label}" else "",
                color = Muted, fontSize = 11.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (l.price == null) "no price" else eur(l.price), color = if (l.price == null) Muted else verdictColor(l.verdict).copy(alpha = faded),
                fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (l.negotiated != null && l.asking != null && l.negotiated != l.asking) {
                Text("asked ${eur(l.asking)}", color = Muted, fontSize = 11.sp, maxLines = 1)
            } else l.verdict?.let { Text(it.label, color = Muted, fontSize = 11.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun Totals(c: Cart) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
        TotalLine("Market value (${c.cards} cards)", eur(c.marketTotal))
        if (c.askingTotal > 0) TotalLine("Asking", eur(c.askingTotal))
        TotalLine("You pay", eur(c.payTotal), strong = true)
        if (c.pricedMarket > 0) {
            val pct = (c.savedVsMarket / c.pricedMarket * 100).roundToInt()
            TotalLine("Saved vs market", "${eur(c.savedVsMarket)} · $pct %", color = if (c.savedVsMarket >= 0) Sure else Warn)
        }
        if (c.savedVsAsking > 0) TotalLine("Saved vs asking", eur(c.savedVsAsking), color = Sure)
        if (c.unpriced > 0) Text("${c.unpriced} line${if (c.unpriced > 1) "s" else ""} without a price (not in \"You pay\")",
            color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun TotalLine(label: String, value: String, strong: Boolean = false, color: Color = Color.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = if (strong) Color.White else Muted, fontSize = 14.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = if (strong) 17.sp else 14.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}

/** One cart line: condition, quantity, asking and negotiated price, status. */
@Composable
private fun LineEditor(c: Cart, l: CartLine, vm: ScannerViewModel, onPad: (PadRequest) -> Unit, onClose: () -> Unit) {
    fun change(f: (CartLine) -> CartLine) = vm.updateLine(c.id, l.id, f)
    AlertDialog(onDismissRequest = onClose,
        title = { Text(l.name, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(l.setLine + (l.printingLabel?.let { " · $it" } ?: ""), color = Muted, fontSize = 12.sp)
                Text("Condition", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Condition.values().forEach { cd ->
                        FilterChip(selected = cd == l.condition, onClick = { change { it.copy(condition = cd) } }, label = { Text(cd.label) })
                    }
                }
                Text("Market value ${eur(l.market)} each", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OutlinedButton({ change { it.copy(qty = (it.qty - 1).coerceAtLeast(1)) } }) { Text("−") }
                    Text("${l.qty}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.width(40.dp))
                    OutlinedButton({ change { it.copy(qty = it.qty + 1) } }) { Text("+") }
                }
                PriceField("Asking price (each)", l.asking) {
                    onPad(PadRequest("Asking price", l.asking, l.market) { v -> change { it.copy(asking = v) } })
                }
                PriceField("Negotiated price (each)", l.negotiated ?: l.asking, note = if (l.negotiated == null && l.asking != null) "= asking" else null) {
                    onPad(PadRequest("Negotiated price", l.negotiated ?: l.asking, l.market) { v -> change { it.copy(negotiated = v) } })
                }
                l.verdict?.let { v -> Text(v.label, color = verdictColor(v), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                Text("Status", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(LineStatus.CONSIDERING, LineStatus.AGREED, LineStatus.DROPPED).forEach { st ->
                        FilterChip(selected = st == l.status, onClick = { change { it.copy(status = st) } }, label = { Text(st.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Done") } })
}

@Composable
private fun PriceField(label: String, value: Double?, note: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(10.dp)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text((if (value == null) "–" else eur(value)) + (note?.let { "  $it" } ?: ""), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------------- deal history
@Composable
private fun DealsDialog(state: UiState, vm: ScannerViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var toExport by remember { mutableStateOf<List<Cart>>(emptyList()) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && toExport.isNotEmpty()) {
            val r = vm.saveDeals(ctx.contentResolver, uri, toExport)
            Toast.makeText(ctx, r.fold({ "Saved: $it cards" }, { "Could not save: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
    var open by remember { mutableStateOf<Long?>(null) }
    val day = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    val paid = state.book.paid
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Deal history", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (paid.isNotEmpty()) TextButton({ toExport = paid; export.launch(TradingCsv.fileName()) }) { Text("CSV") }
                    CloseButton(onClose)
                }
                if (paid.isEmpty()) Text("No deals yet. A cart lands here when you tap Paid.", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
                LazyColumn(Modifier.weight(1f)) {
                    paid.groupBy { day.format(Date(it.paidAt!!)) }.forEach { (d, carts) ->
                        item(key = "day-$d") {
                            Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
                                Text(d, color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("${carts.size} deal${if (carts.size > 1) "s" else ""} · paid ${eur(carts.sumOf { it.payTotal })} · " +
                                    "market value ${eur(carts.sumOf { it.marketTotal })}", color = Muted, fontSize = 12.sp)
                            }
                        }
                        items(carts, key = { it.id }) { c ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Panel, RoundedCornerShape(12.dp))
                                .clickable { open = if (open == c.id) null else c.id }.padding(12.dp)) {
                                Row {
                                    Text(c.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(eur(c.payTotal), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("${time.format(Date(c.paidAt!!))} · ${c.payment ?: ""} · ${c.cards} cards · market ${eur(c.marketTotal)}" +
                                    (if (c.pricedMarket > 0) " · saved ${eur(c.savedVsMarket)}" else ""), color = Muted, fontSize = 12.sp)
                                if (open == c.id) {
                                    c.lines.forEach { l ->
                                        Text("${if (l.qty > 1) "${l.qty}× " else ""}${l.name} · ${l.condition.label} · ${if (l.active) eur(l.price) else l.status.label}",
                                            color = if (l.active) Color.White else Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                                    }
                                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton({ vm.reopen(c.id); onClose() }) { Text("Reopen") }
                                        OutlinedButton({ toExport = listOf(c); export.launch(TradingCsv.fileName()) }) { Text("CSV") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
