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
import io.github.pmaxai.cardscanner.data.PriceCheck
import io.github.pmaxai.cardscanner.trading.Cart
import io.github.pmaxai.cardscanner.trading.CartLine
import io.github.pmaxai.cardscanner.trading.Condition
import io.github.pmaxai.cardscanner.trading.DealSummary
import io.github.pmaxai.cardscanner.trading.LineStatus
import io.github.pmaxai.cardscanner.trading.SellVerdict
import io.github.pmaxai.cardscanner.trading.ShowInfo
import io.github.pmaxai.cardscanner.trading.Side
import io.github.pmaxai.cardscanner.trading.TradingBook
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

/** A number to type on the pad: what it is for, where it starts, the market value for the quick buttons, its unit. */
class PadRequest(val title: String, val initial: Double?, val market: Double?, val unit: String = "€",
                 /** false: the market value is in doubt, so no deal verdict while typing */
                 val verdicts: Boolean = true, val onDone: (Double?) -> Unit)

private val GiveBlue = Color(0xFF6FA8FF)

fun sellColor(v: SellVerdict?): Color = when (v) {
    SellVerdict.GOOD -> Sure
    SellVerdict.USUAL -> Color.White
    SellVerdict.LOW -> Warn
    null -> Muted
}

/**
 * Trading Mode (card shows): scan a card, see its market value for its condition, type the
 * vendor's price for a verdict, collect cards per booth in a cart with the negotiated price.
 */
@Composable
fun TradingScreen(state: UiState, vm: ScannerViewModel) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var torch by rememberSaveable { mutableStateOf(false) }
    var cartOpen by rememberSaveable { mutableStateOf(false) }
    var dealsOpen by rememberSaveable { mutableStateOf(false) }
    var showOpen by rememberSaveable { mutableStateOf(false) }
    var pad by remember { mutableStateOf<PadRequest?>(null) }

    val topBar: @Composable () -> Unit = {
        TradingTopBar(state, vm, torch, onTorch = { torch = it }, onCart = { cartOpen = true }, onDeals = { dealsOpen = true },
            onShow = { showOpen = true }, onSettings = { vm.openSettings(SettingsPage.TRADING) })
    }
    val camera: @Composable (Modifier) -> Unit = { m ->
        CameraArea(state, vm, torch, video = false, modifier = m) {
            // which side + Cart puts the next card on: the vendor's cards, or yours for a trade or sale
            val give = state.scanTarget == Side.GIVE
            Text(if (give) "Scanning: I give ⇄" else "Scanning: I get ⇄", color = if (give) Ink else Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                    .background(if (give) GiveBlue else Color(0x99000000), RoundedCornerShape(14.dp))
                    .clickable { vm.setScanTarget(if (give) Side.GET else Side.GIVE) }.padding(horizontal = 12.dp, vertical = 5.dp))
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
    if (cartOpen) CartDialog(state, vm, onPad = { pad = it }) { cartOpen = false }
    if (dealsOpen) DealsDialog(state, vm) { dealsOpen = false }
    if (showOpen) ShowDialog(state, vm) { showOpen = false }
    pad?.let { r -> NumberPad(r) { pad = null } }
}

@Composable
private fun TradingTopBar(state: UiState, vm: ScannerViewModel, torch: Boolean, onTorch: (Boolean) -> Unit, onCart: () -> Unit,
                          onDeals: () -> Unit, onShow: () -> Unit, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cart = state.book.active
    Column {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🔍  Search", color = Muted, fontSize = 15.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(end = 6.dp).background(Panel, RoundedCornerShape(20.dp))
                .clickable { vm.openSearch(SearchMode.LOOK_UP) }.padding(horizontal = 12.dp, vertical = 8.dp))
        // the active cart: tap to open it
        Row(Modifier.weight(1f).background(Gold.copy(alpha = 0.14f), RoundedCornerShape(20.dp)).clickable(onClick = onCart)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🛒 ", fontSize = 14.sp)
            FitText(when {
                cart == null -> "No cart yet"
                cart.isTrade -> "${cart.name} · trade · ${if (cart.settle >= 0) "pay" else "get"} ${eur(kotlin.math.abs(cart.settle))}"
                else -> "${cart.name} · ${cart.cards} · ${eur(cart.payTotal)}"
            }, 15.sp, Color.White, Modifier.weight(1f), weight = FontWeight.Bold)
        }
        Box {
            TextButton({ menu = true }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Deal history") }, onClick = { menu = false; onDeals() })
                DropdownMenuItem(text = { Text(state.book.show?.let { "Show: ${it.name}" } ?: "Show & budget…") },
                    onClick = { menu = false; onShow() })
                DropdownMenuItem(text = { Text("New cart (next booth)") }, onClick = { menu = false; vm.newCart() })
                DropdownMenuItem(text = { Text(if (torch) "Light off" else "Light on") }, onClick = { menu = false; onTorch(!torch) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Settings…") }, onClick = { menu = false; onSettings() })
            }
        }
    }
    BudgetBar(state.book, Modifier.padding(horizontal = 12.dp), thin = true)
    }
}

/** The show's budget: spent (paid deals) and agreed (not paid yet) against it; orange from 90 %. */
@Composable
fun BudgetBar(book: TradingBook, modifier: Modifier = Modifier, thin: Boolean = false) {
    val budget = book.show?.budget?.takeIf { it > 0 } ?: return
    val used = book.spent + book.agreed
    val share = (used / budget).coerceIn(0.0, 1.0).toFloat()
    val color = if (used / budget >= TradingMath.BUDGET_WARN) Warn else Gold
    Column(modifier.fillMaxWidth()) {
        if (!thin) Text("Budget ${eur(budget)}: ${eur(book.spent)} spent" + (if (book.agreed > 0) " · ${eur(book.agreed)} agreed" else "") +
            " · ${eur(TradingMath.round2(budget - used))} left", color = if (used / budget >= TradingMath.BUDGET_WARN) Warn else Muted, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(if (thin) 3.dp else 6.dp).background(Color(0xFF2A2D34), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(share).height(if (thin) 3.dp else 6.dp).background(color, RoundedCornerShape(3.dp)))
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
    val check = s.priceCard.priceCheck
    if (market == null) {
        val head = if (check?.status == PriceCheck.Status.DROPPED) "No reliable Cardmarket price" else "No Cardmarket price"
        val tcg = p?.usd?.let { u -> " · TCGplayer ${usd(u)}" + (p.usdEur?.let { " ≈ ${eur(it)}" } ?: "") } ?: ""
        Text(head + tcg, color = Warn, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
    PriceHistoryLine(s)
    if (state.settings.showPsa) s.graded?.let { PsaLine(it, printing = p?.kind?.takeIf { s.priceCard.printings.size > 1 }) }

    // warnings: a thin price is not to be trusted blindly
    val warnings = buildList {
        check?.let { add(it.message) }
        if (TradingMath.fewSales(p)) add("Few sales – check Cardmarket before you pay")
        if (s.pricedFromEnglish) add("Price of the English version (none for this language)")
        priceAgeDays(s.priceCard.prices?.updated)?.takeIf { it > 3 }?.let { add("Prices are $it days old – update the app before a show") }
    }
    warnings.forEach { Text("⚠ $it", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }

    // the vendor's price and the verdict (your own card: the vendor's offer for it)
    Spacer(Modifier.height(8.dp))
    val give = state.scanTarget == Side.GIVE
    val asking = draft.asking
    val badge: Pair<String, Color>? = if (asking != null && market != null) {
        when {
            check != null -> "Check price" to Muted          // no deal verdict on a doubtful Cardmarket price
            give -> TradingMath.sellVerdict(asking, market)?.let { it.label to sellColor(it) }
            else -> TradingMath.verdict(asking, market)?.let { it.label to verdictColor(it) }
        }
    } else null
    Row(Modifier.fillMaxWidth().border(1.dp, if (give) GiveBlue else Color(0xFF3A3D45), RoundedCornerShape(10.dp))
        .clickable { onPad(PadRequest(if (give) "Vendor's offer for your card" else "Asking price", asking, market, verdicts = check == null) { vm.setAsking(it) }) }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(when {
            asking == null && give -> "Type the vendor's offer"
            asking == null -> "Type the asking price"
            give -> "Vendor offers ${eur(asking)}"
            else -> "Asking ${eur(asking)}"
        }, color = if (asking == null) Muted else Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (badge != null) {
            Text(badge.first, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(badge.second, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
    if (market != null) {
        val pct = asking?.let { " · ${if (give) "offer" else "asking"} = ${(it / market * 100).roundToInt()} % of market" } ?: ""
        val line = if (give) {
            val (lo, hi) = TradingMath.sellRange(market)
            val rate = state.book.active?.takeIf { it.isTrade }?.giveRate ?: TradingMath.GIVE_RATE
            "Vendors usually pay ${eur(lo)} – ${eur(hi)} · in a trade at ${(rate * 100).roundToInt()} %: ${eur(TradingMath.round2(market * rate))}$pct"
        } else "Offer ${eur(TradingMath.offer(market))} · walk away above ${eur(TradingMath.walkAway(market))}$pct"
        Text(line, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
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
    Button(onClick = { vm.addToCart() }, modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = if (give) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = GiveBlue) else androidx.compose.material3.ButtonDefaults.buttonColors()) {
        Text((if (give) "+ I give" else "+ Cart") + (state.book.active?.let { " (${it.name})" } ?: ""), fontSize = 17.sp,
            fontWeight = FontWeight.Bold, maxLines = 1)
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
/** Big keys for prices: two or three taps; quick buttons from the market value. Clear starts over, OK takes it. */
@Composable
fun NumberPad(r: PadRequest, onClose: () -> Unit) {
    val cfg = LocalConfiguration.current
    // a landscape phone is too low for the pad in one column (OK ended up below the screen): keys beside the price
    val wide = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenHeightDp < 600
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = !wide)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel) {
            NumberPadContent(r, wide, compact = wide && cfg.screenHeightDp < 380, onClose = onClose)
        }
    }
}

/** The pad itself: [wide] puts the keys to the right of the price and the buttons; [compact] uses lower keys. */
@Composable
internal fun NumberPadContent(r: PadRequest, wide: Boolean, compact: Boolean = false, onClose: () -> Unit) {
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
    val info: @Composable () -> Unit = {
        Text(r.title, color = Muted, fontSize = 14.sp)
        Text((if (text.isEmpty()) "–" else text.replace('.', comma)) + " " + r.unit, color = Color.White, fontSize = 36.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(vertical = if (compact) 2.dp else 6.dp))
        val m = r.market
        if (m != null) {
            value()?.let { v ->
                val vd = if (r.verdicts) TradingMath.verdict(v, m) else null
                Text("${(v / m * 100).roundToInt()} % of market ${eur(m)}" + (vd?.let { " · ${it.label}" } ?: ""),
                    color = verdictColor(vd), fontSize = 13.sp)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("= market" to 1.0, "−10 %" to 0.9, "−20 %" to 0.8, "offer" to -1.0).forEach { (label, f) ->
                    val v = if (f < 0) TradingMath.offer(m) else TradingMath.round2(m * f)
                    Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.background(Color(0xFF2A2D34), RoundedCornerShape(8.dp))
                        .clickable { text = String.format(Locale.ROOT, "%.2f", v) }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
    val keys: @Composable () -> Unit = {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = if (compact) 3.dp else 4.dp)) {
                row.forEach { k ->
                    Box(Modifier.size(width = 76.dp, height = if (compact) 46.dp else 52.dp).background(Color(0xFF2A2D34), RoundedCornerShape(12.dp))
                        .clickable { type(k) }, contentAlignment = Alignment.Center) {
                        Text(if (k == ".") comma.toString() else k, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    val buttons: @Composable (Modifier) -> Unit = { m ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Clear empties the field and stays open for the new price (OK on an empty field removes the price)
            OutlinedButton({ text = "" }, modifier = Modifier.height(48.dp)) { Text("Clear") }
            Button({ r.onDone(value()?.let { TradingMath.round2(it) }); onClose() }, modifier = Modifier.height(48.dp).width(120.dp)) {
                Text("OK", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
    if (wide) {
        // price, quick buttons and Clear/OK on the left, the keys on the right: fits a landscape phone (scrolls if it still doesn't)
        Row(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                info()
                buttons(Modifier.padding(top = if (compact) 10.dp else 18.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column { keys() }
        }
    } else {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            info()
            Spacer(Modifier.height(10.dp))
            keys()
            buttons(Modifier.padding(top = 10.dp))
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
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
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
                    Text("Nothing in this cart yet. Scan a card and tap + Cart. For a trade, switch the camera's target to " +
                        "\"I give\" and scan your cards too. A new booth gets its own cart (Next booth).",
                        color = Muted, modifier = Modifier.padding(vertical = 16.dp))
                }
                if (cart != null) {
                    val gets = cart.lines.filter { it.active && it.side == Side.GET }
                    val gives = cart.lines.filter { it.active && it.side == Side.GIVE }
                    val dropped = cart.lines.filter { !it.active }
                    val row: @Composable (CartLine) -> Unit = { l ->
                        SwipeToRemove(l.id.hashCode(), onRemove = { vm.updateLine(cart.id, l.id) { it.copy(status = LineStatus.DROPPED) } },
                            label = "Drop from the cart") {
                            CartLineRow(l, selecting, l.id in selected) {
                                if (selecting) {
                                    selected = if (l.id in selected) selected - l.id else selected + l.id
                                } else {
                                    editing = l.id
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        if (cart.isTrade) item { SideHeader("I get", cart.cards, cart.getValue, cart.marketTotal, Gold) }
                        items(gets, key = { it.id }) { row(it) }
                        if (cart.isTrade) {
                            item { SideHeader("I give", cart.giveCards, cart.giveValue, cart.marketGive, GiveBlue) }
                            items(gives, key = { it.id }) { row(it) }
                            if (gives.isEmpty()) item {
                                Text("Switch the camera's target to \"I give\" (top of the picture) and scan your cards.", color = Muted,
                                    fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                        if (dropped.isNotEmpty()) item {
                            Text("${if (showDropped) "▾" else "▸"} Dropped (${dropped.size})", color = Muted, fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().clickable { showDropped = !showDropped }.padding(vertical = 10.dp))
                        }
                        if (showDropped) items(dropped, key = { it.id }) { l -> CartLineRow(l, false, false) { editing = l.id } }
                    }
                    when {
                        selecting -> SelectionBar(cart, selected, onPad, vm) { selecting = false; selected = emptySet() }
                        cart.isTrade -> TradeTotals(cart, vm, onPad)
                        else -> Totals(cart)
                    }
                    BudgetBar(state.book, Modifier.padding(top = 6.dp))
                    if (!selecting) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallButton("Bundle…", Modifier.weight(1f), enabled = cart.activeLines.size > 1) { selecting = true; selected = emptySet() }
                            if (!cart.isTrade) SmallButton("Agree total", Modifier.weight(1f), enabled = cart.getLines.isNotEmpty()) {
                                onPad(PadRequest("Total for the whole cart", cart.payTotal.takeIf { it > 0 }, cart.marketTotal.takeIf { it > 0 }) { v ->
                                    v?.let { vm.agreeTotal(cart.id, cart.getLines.map { l -> l.id }, it) }
                                })
                            }
                            SmallButton(if (cart.isTrade) "No trade" else "Trade", Modifier.weight(1f),
                                enabled = !cart.isTrade || cart.giveLines.isEmpty()) { vm.setTrade(cart.id, !cart.isTrade) }
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ vm.newCart() }, modifier = Modifier.weight(1f)) { Text("Next booth", maxLines = 1) }
                            Button({ paying = true }, enabled = cart.activeLines.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                Text(when {
                                    !cart.isTrade -> "Paid ${eur(cart.payTotal)}"
                                    cart.settle > 0 -> "Done · pay ${eur(cart.settle)}"
                                    cart.settle < 0 -> "Done · get ${eur(-cart.settle)}"
                                    else -> "Done · even"
                                }, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
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
        val title = when {
            !c.isTrade -> "Paid ${eur(c.payTotal)} at ${c.name}?"
            c.settle > 0 -> "Trade done: you pay ${eur(c.settle)}?"
            c.settle < 0 -> "Trade done: ${c.name} pays you ${eur(-c.settle)}?"
            else -> "Even trade done?"
        }
        val body = if (c.isTrade) "${c.cards} cards in, ${c.giveCards} out. The cart moves to the deal history. How was the cash paid?"
                   else "The ${c.cards} cards count as bought, and the cart moves to the deal history. How did you pay?"
        AlertDialog(onDismissRequest = { paying = false }, title = { Text(title) }, text = { Text(body) },
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
private fun SmallButton(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier = modifier, enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Box(Modifier.size(40.dp).background(Color(0xFF2A2D34), CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SideHeader(label: String, cards: Int, value: Double, market: Double, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("  $cards cards · counts ${eur(value)} · market ${eur(market)}", color = Muted, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CartLineRow(l: CartLine, selecting: Boolean, selected: Boolean, onClick: () -> Unit) {
    val blank = remember { ColorPainter(Color(0xFF2A2D34)) }
    val faded = if (l.active) 1f else 0.45f
    Row(Modifier.fillMaxWidth().background(if (selected) Gold.copy(alpha = 0.14f) else Ink).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (selecting) {
            Text(if (selected) "☑" else "☐", color = if (selected) Gold else Muted, fontSize = 20.sp, modifier = Modifier.padding(end = 6.dp))
        }
        AsyncImage(model = l.image, contentDescription = l.name, placeholder = blank, error = blank, fallback = blank,
            modifier = Modifier.width(40.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(4.dp))
                .border(if (l.side == Side.GIVE) 2.dp else 0.dp, if (l.side == Side.GIVE) GiveBlue else Color.Transparent, RoundedCornerShape(4.dp)))
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
            val color = if (l.side == Side.GIVE) sellColor(l.sellVerdict) else verdictColor(l.verdict)
            Text(when {
                l.price != null -> eur(l.price)
                l.side == Side.GIVE -> "no offer"
                else -> "no price"
            }, color = if (l.price == null) Muted else color.copy(alpha = faded), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (l.negotiated != null && l.asking != null && l.negotiated != l.asking) {
                Text("${if (l.side == Side.GIVE) "offered" else "asked"} ${eur(l.asking)}", color = Muted, fontSize = 11.sp, maxLines = 1)
            } else (l.verdict?.label ?: l.sellVerdict?.label ?: if (l.priceDoubt && l.price != null) "check price" else null)
                ?.let { Text(it, color = Muted, fontSize = 11.sp, maxLines = 1) }
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

/** A trade: both sides at their rates, the balance, the cash agreed, and the same trade at market value. */
@Composable
private fun TradeTotals(c: Cart, vm: ScannerViewModel, onPad: (PadRequest) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your cards count ${(c.giveRate * 100).roundToInt()} %", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.background(GiveBlue.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).clickable {
                    onPad(PadRequest("Your cards count at", c.giveRate * 100, null, unit = "%") { v -> v?.let { vm.setRates(c.id, give = it / 100) } })
                }.padding(horizontal = 8.dp, vertical = 5.dp))
            Text("Theirs ${(c.getRate * 100).roundToInt()} %", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.background(Gold.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).clickable {
                    onPad(PadRequest("Their cards count at", c.getRate * 100, null, unit = "%") { v -> v?.let { vm.setRates(c.id, get = it / 100) } })
                }.padding(horizontal = 8.dp, vertical = 5.dp))
        }
        Spacer(Modifier.height(6.dp))
        TotalLine("I get (${c.cards} cards)", eur(c.getValue))
        TotalLine("I give (${c.giveCards} cards)", eur(c.giveValue))
        TotalLine(when {
            c.balance > 0 -> "Balance: you pay"
            c.balance < 0 -> "Balance: vendor pays you"
            else -> "Balance: even"
        }, eur(kotlin.math.abs(c.balance)), strong = true)
        // the cash you settle on; ⇄ flips who pays
        val payer = if ((c.cash ?: c.balance) >= 0) 1 else -1
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Agreed cash", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(c.cash?.let { (if (it >= 0) "you pay " else "you get ") + eur(kotlin.math.abs(it)) } ?: "= balance",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(8.dp)).clickable {
                    onPad(PadRequest(if (payer > 0) "Agreed cash: you pay" else "Agreed cash: vendor pays you",
                        kotlin.math.abs(c.cash ?: c.balance), null) { v -> vm.setCash(c.id, v?.let { it * payer }) })
                }.padding(horizontal = 10.dp, vertical = 4.dp))
            Text("  ⇄", color = Gold, fontSize = 18.sp, modifier = Modifier.clickable { vm.setCash(c.id, -(c.cash ?: c.balance)) })
        }
        val net = c.netAtMarket
        TotalLine("At market value", (if (net >= 0) "+" else "−") + eur(kotlin.math.abs(net)), color = if (net >= 0) Sure else Warn)
        Text("cards in ${eur(c.marketTotal)} · cards out ${eur(c.marketGive)} · cash " +
            (if (c.settle >= 0) "paid ${eur(c.settle)}" else "received ${eur(-c.settle)}"), color = Muted, fontSize = 11.sp)
        if (c.withoutMarket > 0) Text("${c.withoutMarket} card${if (c.withoutMarket > 1) "s" else ""} without a Cardmarket price count 0 €",
            color = Warn, fontSize = 12.sp)
    }
}

/** Bundle: pick cards, see their market value and asking total, agree one price for them. */
@Composable
private fun SelectionBar(c: Cart, selected: Set<Long>, onPad: (PadRequest) -> Unit, vm: ScannerViewModel, onDone: () -> Unit) {
    val lines = c.activeLines.filter { it.id in selected }
    val market = TradingMath.round2(lines.sumOf { (it.market ?: 0.0) * it.qty })
    val asking = TradingMath.round2(lines.sumOf { (it.asking ?: 0.0) * it.qty })
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(if (lines.isEmpty()) "Tap the cards of the bundle" else "${lines.sumOf { it.qty }} cards selected",
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (lines.isNotEmpty()) {
            Text("market ${eur(market)}" + (if (asking > 0) " · asking ${eur(asking)}" else "") +
                (if (market > 0) " · offer ${eur(TradingMath.offer(market))}" else ""), color = Muted, fontSize = 12.sp)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onDone, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button({
                onPad(PadRequest("Price for these ${lines.sumOf { it.qty }} cards", asking.takeIf { it > 0 }, market.takeIf { it > 0 }) { v ->
                    v?.let { vm.agreeTotal(c.id, lines.map { l -> l.id }, it) }
                    onDone()
                })
            }, enabled = lines.isNotEmpty(), modifier = Modifier.weight(1.4f)) { Text("Agree a price", maxLines = 1) }
        }
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

/** One cart line: condition, quantity, prices, status; in a trade also its side. */
@Composable
private fun LineEditor(c: Cart, l: CartLine, vm: ScannerViewModel, onPad: (PadRequest) -> Unit, onClose: () -> Unit) {
    fun change(f: (CartLine) -> CartLine) = vm.updateLine(c.id, l.id, f)
    val give = l.side == Side.GIVE
    AlertDialog(onDismissRequest = onClose,
        title = { Text(l.name, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(l.setLine + (l.printingLabel?.let { " · $it" } ?: ""), color = Muted, fontSize = 12.sp)
                if (c.isTrade) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Side.values().forEach { sd ->
                            FilterChip(selected = sd == l.side, onClick = { change { it.copy(side = sd) } }, label = { Text(sd.label) })
                        }
                    }
                }
                Text("Condition", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Condition.values().forEach { cd ->
                        FilterChip(selected = cd == l.condition, onClick = { change { it.copy(condition = cd) } }, label = { Text(cd.label) })
                    }
                }
                Text("Market value ${eur(l.market)} each", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                l.market?.takeIf { give }?.let { m ->
                    val (lo, hi) = TradingMath.sellRange(m)
                    Text("Vendors usually pay ${eur(lo)} – ${eur(hi)}; without an offer it counts ${eur(TradingMath.round2(m * c.giveRate))}",
                        color = Muted, fontSize = 12.sp)
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OutlinedButton({ change { it.copy(qty = (it.qty - 1).coerceAtLeast(1)) } }) { Text("−") }
                    Text("${l.qty}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.width(40.dp))
                    OutlinedButton({ change { it.copy(qty = it.qty + 1) } }) { Text("+") }
                }
                PriceField(if (give) "Vendor's offer (each)" else "Asking price (each)", l.asking) {
                    onPad(PadRequest(if (give) "Vendor's offer" else "Asking price", l.asking, l.market, verdicts = !l.priceDoubt) { v -> change { it.copy(asking = v) } })
                }
                PriceField(if (give) "Agreed (each)" else "Negotiated price (each)", l.negotiated ?: l.asking,
                    note = if (l.negotiated == null && l.asking != null) "= ${if (give) "offer" else "asking"}" else null) {
                    onPad(PadRequest(if (give) "Agreed price" else "Negotiated price", l.negotiated ?: l.asking, l.market, verdicts = !l.priceDoubt) { v -> change { it.copy(negotiated = v) } })
                }
                (l.verdict?.let { it.label to verdictColor(it) } ?: l.sellVerdict?.let { it.label to sellColor(it) })?.let { (t, col) ->
                    Text(t, color = col, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                }
                if (l.priceDoubt) Text("⚠ The Cardmarket price of this card is in doubt – check it before you pay", color = Warn,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
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

// ---------------------------------------------------------------------------------- show & budget
/** The show (or day) new carts belong to, with its budget. */
@Composable
private fun ShowDialog(state: UiState, vm: ScannerViewModel, onClose: () -> Unit) {
    val show = state.book.show
    val today = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date())
    var name by remember { mutableStateOf(show?.name ?: "") }
    var budget by remember { mutableStateOf(show?.budget?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "") }
    fun parsed(): Double? = budget.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    AlertDialog(onDismissRequest = onClose,
        title = { Text(if (show == null) "Start a show" else "Show: ${show.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Carts started from now on belong to this show; its summary is in the deal history.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                    placeholder = { Text("e.g. Vienna Card Show, $today") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = budget, onValueChange = { v -> budget = v.filter { it.isDigit() || it == ',' || it == '.' } },
                    singleLine = true, label = { Text("Budget € (optional)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (show != null) {
                    BudgetBar(state.book, Modifier.padding(top = 10.dp))
                    val s = DealSummary.of(state.book.paid.filter { it.showId == show.id })
                    Text("${s.deals} deals so far · spent ${eur(s.spent)} · market value in ${eur(s.marketIn)}", color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Row {
                if (show != null) TextButton({ vm.startShow(name.ifBlank { "Show $today" }, parsed()); onClose() }) { Text("New show") }
                TextButton({
                    if (show == null) vm.startShow(name.ifBlank { "Show $today" }, parsed()) else vm.updateShow(name, parsed())
                    onClose()
                }) { Text(if (show == null) "Start" else "Save") }
            }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
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
    val book = state.book
    val paid = book.paid
    // by show where one was started, else by day
    val groups = paid.groupBy { c -> book.shows.firstOrNull { it.id == c.showId }?.let { "show-${it.id}" } ?: "day-${day.format(Date(c.paidAt!!))}" }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Deal history", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (paid.isNotEmpty()) TextButton({ toExport = paid; export.launch(TradingCsv.fileName()) }) { Text("CSV") }
                    CloseButton(onClose)
                }
                if (paid.isEmpty()) Text("No deals yet. A cart lands here when you tap Paid (or Done for a trade).", color = Muted,
                    modifier = Modifier.padding(vertical = 16.dp))
                LazyColumn(Modifier.weight(1f)) {
                    groups.forEach { (key, carts) ->
                        val show = book.shows.firstOrNull { "show-${it.id}" == key }
                        item(key = key) { GroupSummary(show, carts, day) { toExport = carts; export.launch(TradingCsv.fileName()) } }
                        items(carts, key = { it.id }) { c ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Panel, RoundedCornerShape(12.dp))
                                .clickable { open = if (open == c.id) null else c.id }.padding(12.dp)) {
                                Row {
                                    Text(c.name + if (c.isTrade) " · trade" else "", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(when {
                                        c.settle > 0 -> "paid ${eur(c.settle)}"
                                        c.settle < 0 -> "got ${eur(-c.settle)}"
                                        else -> "even"
                                    }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("${time.format(Date(c.paidAt!!))} · ${c.payment ?: ""} · ${c.cards} in" +
                                    (if (c.giveCards > 0) ", ${c.giveCards} out" else "") + " · market ${eur(c.marketTotal)}" +
                                    (if (c.isTrade) " · at market ${if (c.netAtMarket >= 0) "+" else "−"}${eur(kotlin.math.abs(c.netAtMarket))}"
                                     else if (c.pricedMarket > 0) " · saved ${eur(c.savedVsMarket)}" else ""),
                                    color = Muted, fontSize = 12.sp)
                                if (open == c.id) {
                                    c.lines.forEach { l ->
                                        Text("${if (l.side == Side.GIVE) "→ " else ""}${if (l.qty > 1) "${l.qty}× " else ""}${l.name} · ${l.condition.label} · " +
                                            if (l.active) (l.price?.let { eur(it) } ?: "market ${eur(l.market)}") else l.status.label,
                                            color = if (!l.active) Muted else if (l.side == Side.GIVE) GiveBlue else Color.White, fontSize = 13.sp,
                                            modifier = Modifier.padding(top = 4.dp))
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

/** A show's (or day's) summary: money out and in, market value in and out, savings, cards, budget. */
@Composable
private fun GroupSummary(show: ShowInfo?, carts: List<Cart>, day: SimpleDateFormat, onCsv: () -> Unit) {
    val s = DealSummary.of(carts)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(show?.let { "${it.name} · ${day.format(Date(it.started))}" } ?: day.format(Date(carts.first().paidAt!!)),
                color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("CSV", color = Gold, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onCsv).padding(6.dp))
        }
        Text("${s.deals} deal${if (s.deals > 1) "s" else ""} · spent ${eur(s.spent)}" + (if (s.received > 0) " · received ${eur(s.received)}" else "") +
            " · market value in ${eur(s.marketIn)}" + (if (s.marketOut > 0) ", out ${eur(s.marketOut)}" else ""), color = Muted, fontSize = 12.sp)
        Text("${s.bought} cards bought or traded in" + (if (s.given > 0) ", ${s.given} given" else "") +
            (if (s.saved != 0.0) " · saved ${eur(s.saved)} vs market" else "") +
            (show?.budget?.let { " · budget ${(s.spent / it * 100).roundToInt()} % used" } ?: ""), color = Muted, fontSize = 12.sp)
    }
}
