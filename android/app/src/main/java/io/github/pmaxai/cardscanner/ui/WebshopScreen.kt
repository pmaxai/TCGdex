package io.github.pmaxai.cardscanner.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.shop.ShopClient
import io.github.pmaxai.cardscanner.shop.ShopProduct
import io.github.pmaxai.cardscanner.shop.ShopTerms

private val ShopGreen = Color(0xFF5BC98A)
private val SellBlue = Color(0xFF6FA8FF)

/**
 * Webshop Mode: scan or search a card, see its prices and what the Pokeshop holds of it, and
 * book copies into the shop (language, condition, quantity, price, box) – or, in Sell, collect a
 * customer's order and take it out of stock. A scan changes nothing: stock changes only with a button.
 */
@Composable
fun WebshopScreen(state: UiState, vm: ScannerViewModel) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var torch by rememberSaveable { mutableStateOf(false) }
    var pad by remember { mutableStateOf<PadRequest?>(null) }
    var orderOpen by rememberSaveable { mutableStateOf(false) }

    val topBar: @Composable () -> Unit = { WebshopTopBar(state, vm, torch, { torch = it }) { orderOpen = true } }
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
    val panel: @Composable (Modifier) -> Unit = { m -> WebshopPanel(state, vm, m) { pad = it } }

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
            camera(Modifier.fillMaxWidth().weight(0.75f))
            panel(Modifier.fillMaxWidth().weight(1.25f))
        }
    }
    pad?.let { r -> NumberPad(r) { pad = null } }
    state.shop.unknownCard?.let { (de, en) -> UnknownCardDialog(de, en, vm) }
    if (orderOpen) OrderDialog(state, vm) { orderOpen = false }
}

@Composable
private fun WebshopTopBar(state: UiState, vm: ScannerViewModel, torch: Boolean, onTorch: (Boolean) -> Unit, onOrder: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val shop = state.shop
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🔍", fontSize = 18.sp, modifier = Modifier.background(Panel, RoundedCornerShape(20.dp))
            .clickable { vm.openSearch(SearchMode.LOOK_UP) }.padding(horizontal = 12.dp, vertical = 7.dp))
        Spacer(Modifier.width(6.dp))
        // Stock in: book copies into the shop · Sell: collect a customer's order, take it out of stock
        Row(Modifier.weight(1f).background(Panel, RoundedCornerShape(20.dp)).padding(3.dp)) {
            listOf(false to "Stock in", true to "Sell").forEach { (sell, label) ->
                val on = shop.selling == sell
                Text(label, color = if (on) Ink else Color.White, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).background(if (on) (if (sell) SellBlue else Gold) else Color.Transparent, RoundedCornerShape(17.dp))
                        .clickable { vm.setShopSelling(sell) }.padding(vertical = 6.dp))
            }
        }
        if (shop.order.isNotEmpty() || shop.selling) {
            Spacer(Modifier.width(6.dp))
            Text("🧾 ${shop.orderCopies}" + (if (shop.orderTotal > 0) " · ${eur(shop.orderTotal)}" else ""), color = Color.White,
                fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.background(SellBlue.copy(alpha = if (shop.order.isEmpty()) 0.25f else 0.6f), RoundedCornerShape(20.dp))
                    .clickable(onClick = onOrder).padding(horizontal = 10.dp, vertical = 7.dp))
        }
        Box {
            TextButton({ menu = true }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (torch) "Light off" else "Light on") }, onClick = { menu = false; onTorch(!torch) })
                DropdownMenuItem(text = { Text("Look up again in the shop") }, enabled = state.shown != null,
                    onClick = { menu = false; vm.lookupShop() })
                DropdownMenuItem(text = { Text("Customer's order…") }, onClick = { menu = false; onOrder() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Settings…") }, onClick = { menu = false; vm.openSettings(SettingsPage.WEBSHOP) })
            }
        }
    }
}

@Composable
internal fun WebshopPanel(state: UiState, vm: ScannerViewModel, modifier: Modifier, onPad: (PadRequest) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        val shop = state.shop
        if (!shop.configured) {
            Text("Connect your Pokeshop first: host, key id and secret (from the shop's admin → API-Schlüssel).", color = Muted)
            Button(onClick = { vm.openSettings(SettingsPage.WEBSHOP) }, modifier = Modifier.padding(top = 8.dp)) { Text("Connect the shop") }
            Spacer(Modifier.height(12.dp))
        } else {
            val ok = shop.connectionOk != false
            Text("🏪 " + shop.host.removePrefix("https://") + if (shop.selling) "  ·  Sell" else "  ·  Stock in",
                color = if (ok) Muted else Warn, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.clickable { vm.openSettings(SettingsPage.WEBSHOP) }.padding(bottom = 4.dp))
        }
        val s = state.shown
        if (s == null) {
            Text(if (shop.selling) "Scan or search each card the customer wants: it goes into the order. Nothing leaves the stock " +
                "until you complete the sale." else "Show a card, or search one: its prices and what your shop holds of it appear here. " +
                "Nothing is booked until you press the button.", color = Muted)
        } else {
            CardAndPrices(s, state, vm)
            if (shop.configured) {
                ShopEntries(state, vm)
                if (shop.selling) SellBlock(state, vm) else ShopFormBlock(state, vm, onPad)
            }
        }
        // nothing below the button: the last sale's text is in the order (🧾), Undo sits beside the button
    }
}

/** The card, its printing (Normal / Holo / Reverse) and its prices: market value, trend, TCGplayer, PSA. */
@Composable
private fun CardAndPrices(s: Shown, state: UiState, vm: ScannerViewModel) {
    val p = s.selected
    val condition = state.shop.form.condition
    Row {
        CardImage(s, Modifier.width(76.dp).aspectRatio(63f / 88f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            FitText(s.version.name, 21.sp, Color.White, weight = FontWeight.Bold, minSize = 14.sp)
            FitText(s.version.setLine(), 13.sp, Muted)
            s.english?.let { e -> FitText("English: ${e.name}", 12.sp, Color.White) }
            val market = (p?.eur ?: p?.usdEur)?.let { it * ShopTerms.share(condition) }
            if (market != null) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                    Text(eur(market), color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("  market · $condition" + if (p?.eur == null) " (TCGplayer)" else "", color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            } else Text("No market price", color = Warn, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (s.priceCard.printings.size > 1) {
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.priceCard.printings.forEach { pr ->
                FilterChip(selected = pr.kind == p?.kind, onClick = { vm.choosePrinting(pr.kind) },
                    label = { Text("${pr.kind.label} ${printingPrice(pr)}", fontSize = 12.sp, maxLines = 1) })
            }
        }
    }
    val parts = buildList {
        p?.eur?.let { add("trend ${eur(it)}") }
        p?.eur30?.let { add("30-day ${eur(it)}") }
        p?.eurLow?.let { add("from ${eur(it)}") }
        p?.usd?.let { add("TCGplayer ${usd(it)}") }
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    if (state.settings.showPsa) s.graded?.let { PsaLine(it, printing = p?.kind?.takeIf { s.priceCard.printings.size > 1 }) }
    s.priceCard.priceCheck?.let { Text("⚠ ${it.message}", color = Warn, fontSize = 12.sp) }
    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Not this card?", color = Muted, fontSize = 12.sp)
        if (!s.fromSearch) s.alternatives.forEach { alt ->
            FilterChip(selected = false, onClick = { vm.choose(alt) }, label = { Text(alt.info.name, fontSize = 12.sp, maxLines = 1) })
        }
        FilterChip(selected = false, onClick = { vm.openSearch(SearchMode.REPLACE) }, label = { Text("🔍 Search…", fontSize = 12.sp) })
    }
}

/** What the shop holds of this card: one line per product (language · condition · finish). Tap one to book into it. */
@Composable
private fun ShopEntries(state: UiState, vm: ScannerViewModel) {
    val shop = state.shop
    SectionTitle("In your shop")
    when {
        shop.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  Looking it up…", color = Muted, fontSize = 13.sp)
        }
        shop.lookupError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(shop.lookupError, color = Warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton({ vm.lookupShop() }) { Text("Retry") }
        }
        shop.products.isNullOrEmpty() -> Text("Not in the shop yet", color = Muted, fontSize = 14.sp)
        else -> {
            val match = vm.shopMatch(state)
            shop.products.orEmpty().sortedWith(compareBy({ it.cardLanguage != shop.form.language }, { it.condition })).forEach { p ->
                ProductRow(p, selected = if (shop.selling) p.sku == shop.sellSku else p.sku == match?.sku) {
                    if (shop.selling) vm.selectSellProduct(p.sku)
                    else {
                        vm.setShopLanguage(p.cardLanguage)
                        vm.setShopCondition(p.condition)
                        kindOf(p.finish)?.let { k -> if (state.shown?.priceCard?.printings?.any { it.kind == k } == true) vm.choosePrinting(k) }
                    }
                }
            }
        }
    }
}

private fun kindOf(finish: String): Printing.Kind? = when (finish) {
    "normal" -> Printing.Kind.NORMAL; "holo" -> Printing.Kind.HOLO; "reverse_holo" -> Printing.Kind.REVERSE
    "first_edition" -> Printing.Kind.FIRST_EDITION; else -> null
}

@Composable
private fun ProductRow(p: ShopProduct, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)
        .border(1.dp, if (selected) Gold else Color(0xFF2E3138), RoundedCornerShape(10.dp))
        .background(if (selected) Gold.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${p.cardLanguage} · ${p.condition} · ${ShopTerms.finishLabel(p.finish)}" + (p.grading?.let { " · $it" } ?: ""),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(p.price?.let { eur(it.euros) } ?: "no price", color = if (p.price != null) Gold else Warn, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        val boxes = p.stock.boxes.filter { it.quantity > 0 }.joinToString(", ") { "box ${it.box}: ${it.quantity}" }
        Text("${p.stock.quantity} in stock" + (if (p.stock.reserved > 0) " (${p.stock.reserved} in checkouts)" else "") +
            (if (boxes.isNotEmpty()) " · $boxes" else "") + " · ${ShopTerms.statusLabel(p.status)}",
            color = if (p.status == "active") Muted else Warn, fontSize = 12.sp)
        Text(p.sku, color = Color(0xFF6B717C), fontSize = 11.sp)
    }
}

/** Language, (a comment for a new product,) quantity, box, price, condition, then the one button that books (Undo beside it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShopFormBlock(state: UiState, vm: ScannerViewModel, onPad: (PadRequest) -> Unit) {
    val shop = state.shop
    val f = shop.form
    val s = state.shown ?: return
    val match = vm.shopMatch(state)
    val finish = ShopTerms.finishOf(s.printing)
    SectionTitle(if (match != null) "Add copies" else "Add to the shop")

    // language: from the scan, any of the shop's languages can be picked
    var langMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel("Language")
        Box {
            OutlinedButton(onClick = { langMenu = true }) { Text("${f.language} · ${ShopTerms.languageName(f.language)} ▾") }
            DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                ShopTerms.LANGUAGES.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text("$code · $name", fontWeight = if (code == f.language) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { langMenu = false; vm.setShopLanguage(code) })
                }
            }
        }
        Text("  ${ShopTerms.finishLabel(finish)}", color = Muted, fontSize = 13.sp)
    }
    if (match == null) {
        OutlinedTextField(value = f.comment, onValueChange = vm::setShopComment, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            label = { Text("Comment (optional, on the product page)") }, maxLines = 4)
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        // quantity
        Column(Modifier.weight(1f)) {
            FieldLabel("Copies")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−", f.quantity > 1) { vm.setShopQuantity(f.quantity - 1) }
                Text("${f.quantity}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
                StepButton("+", true) { vm.setShopQuantity(f.quantity + 1) }
            }
        }
        // storage box 1–999
        Column(Modifier.width(120.dp)) {
            FieldLabel("Box (1–999)")
            var text by remember(f.box) { mutableStateOf(f.box?.toString() ?: "") }
            OutlinedTextField(value = text, singleLine = true, placeholder = { Text("e.g. 14") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                onValueChange = { v -> val d = v.filter(Char::isDigit).take(3); text = d; vm.setShopBox(d.toIntOrNull()) })
        }
    }
    // selling price: tap for the number pad
    FieldLabel("Selling price")
    Row(Modifier.fillMaxWidth().border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(8.dp))
        .clickable { onPad(PadRequest("Selling price", f.price, null, verdicts = false) { vm.setShopPrice(it) }) }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(f.price?.let { eur(it) } ?: "tap to set", color = if (f.price != null) Color.White else Muted, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        val market = vm.marketValue(s, f.condition)
        Text(when {
            match != null && !f.priceByUser -> "the shop's price"
            match != null -> "replaces the shop's ${match.price?.let { eur(it.euros) } ?: ""}"
            f.priceByUser -> "your price"
            market != null && shop.minPrice != null && market < shop.minPrice -> "your minimum (market ${eur(market)})"
            else -> "market value for ${f.condition}"
        }, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("✎", color = Muted, fontSize = 16.sp)
    }
    FieldLabel("Condition")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ShopTerms.CONDITIONS.forEach { g ->
            val sel = g.code == f.condition
            Text(g.code, color = if (sel) Ink else Color.White, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 2.dp).background(if (sel) Gold else Color(0xFF2A2D34), RoundedCornerShape(8.dp))
                    .clickable { vm.setShopCondition(g.code) }.padding(horizontal = 11.dp, vertical = 6.dp))
        }
    }

    // what the button does, said plainly
    val box = f.box?.let { ShopClient.box(it) }
    Text(if (match != null) "${match.stock.quantity} in stock now – this adds ${f.quantity} to box ${box ?: "…"}"
        else "${s.version.name} (${f.language} · ${f.condition} · ${ShopTerms.finishLabel(finish)}) is new: it is created with ${f.quantity} in box ${box ?: "…"}" +
            (if (f.price == null) " (without a price it stays a draft)" else ""),
        color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    val last = shop.booking?.takeIf { !it.undone }
    last?.warnings?.forEach { Text("⚠ $it", color = Warn, fontSize = 12.sp) }
    shop.error?.let { e ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("⚠ $e", color = Warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (shop.retryable) TextButton({ vm.retryShop() }) { Text("Retry") }
        }
    }
    // the button is the last thing; Undo (the last booking) beside it
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.bookShop() }, enabled = !shop.busy && f.box != null && !shop.loading,
            modifier = Modifier.weight(1f).height(50.dp)) {
            if (shop.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink)
            else Text(if (match != null) "Add ${f.quantity} to stock" else "Create in the shop (${f.quantity})", fontSize = 17.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (last != null) UndoButton(if (last.sku.startsWith("${shop.cardRef}-")) "Undo" else "Undo last", !shop.busy) { vm.undoShopBooking() }
    }
}

/** A small Undo beside a main button. */
@Composable
private fun UndoButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.padding(start = 8.dp).height(50.dp)) {
        Text("↩ $label", fontSize = 13.sp, maxLines = 1)
    }
}

/** Sell: which product and box the copies come from, how many, then into the customer's order. */
@Composable
internal fun SellBlock(state: UiState, vm: ScannerViewModel) {
    val shop = state.shop
    SectionTitle("Sell")
    val p = shop.products?.firstOrNull { it.sku == shop.sellSku }
    if (shop.loading) return
    if (p == null) {
        Text(if (shop.products.isNullOrEmpty()) "Not in the shop – nothing to take out of stock" else "None of these has copies in stock",
            color = Muted, fontSize = 14.sp)
        return
    }
    Text("${p.cardLanguage} · ${p.condition} · ${ShopTerms.finishLabel(p.finish)} · ${p.price?.let { eur(it.euros) } ?: "no price"}",
        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    if (p.stock.quantity <= 0) {
        Text("No copies of this one in stock", color = Muted, fontSize = 14.sp)
        return
    }
    FieldLabel("Out of box")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        p.stock.boxes.filter { it.quantity > 0 }.forEach { b ->
            FilterChip(selected = b.box == shop.sellBox, onClick = { vm.setSellBox(b.box) },
                label = { Text("box ${b.box} · ${b.quantity}" + if (b.reserved > 0) " (${b.reserved} held)" else "") })
        }
    }
    val box = p.stock.boxes.firstOrNull { it.box == shop.sellBox }
    val inOrder = shop.order.filter { it.sku == p.sku && it.box == shop.sellBox }.sumOf { it.quantity }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        FieldLabel("Copies")
        StepButton("−", shop.sellQty > 1) { vm.setSellQty(shop.sellQty - 1) }
        Text("${shop.sellQty}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        StepButton("+", box != null && shop.sellQty < box.quantity) { vm.setSellQty(shop.sellQty + 1) }
        p.price?.let { Text("  = ${eur(it.euros * shop.sellQty)}", color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
    if (box != null && shop.sellQty + inOrder > box.quantity - box.reserved && box.reserved > 0)
        Text("⚠ ${box.reserved} of box ${box.box} is held by an online checkout – selling it shows the product as sold out " +
            "and the shop tells the admin", color = Warn, fontSize = 12.sp)
    if (inOrder > 0) Text("$inOrder already in the order from box ${shop.sellBox}", color = Muted, fontSize = 12.sp)
    Text("Nothing leaves the stock yet: the order is taken out of stock when you complete the sale (🧾 at the top).",
        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    shop.orderError?.takeIf { shop.sale?.undone == false }?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.addToOrder() }, enabled = box != null && inOrder < box.quantity,
            colors = ButtonDefaults.buttonColors(containerColor = SellBlue, contentColor = Ink),
            modifier = Modifier.weight(1f).height(50.dp)) {
            Text("Add to the order", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        shop.sale?.takeIf { !it.undone }?.let { UndoButton("Undo sale", !shop.orderBusy) { vm.undoSale() } }
    }
}

/** The last sale: what was taken out, and Undo. */
@Composable
private fun SaleBanner(state: UiState, vm: ScannerViewModel) {
    val sale = state.shop.sale ?: return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).background(Color(0xFF1E2633), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text((if (sale.undone) "↩ " else "✓ ") + sale.text, color = if (sale.undone) Muted else SellBlue, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!sale.undone) TextButton({ vm.undoSale() }, enabled = !state.shop.orderBusy) { Text("Undo") }
    }
}

/** The customer's order: its lines, the total, and the one button that takes it out of stock. */
@Composable
internal fun OrderDialog(state: UiState, vm: ScannerViewModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth(0.95f)) {
            OrderSheet(state, vm, onClose)
        }
    }
}

/** The order dialog's content. */
@Composable
internal fun OrderSheet(state: UiState, vm: ScannerViewModel, onClose: () -> Unit) {
    val shop = state.shop
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Customer's order", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClose) { Text("Close") }
        }
        if (shop.order.isEmpty()) Text("Empty. In Sell, scan each card the customer wants and add it.", color = Muted, fontSize = 14.sp)
        shop.order.forEachIndexed { i, l ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, if (l.error != null) Warn else Color(0xFF2E3138), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${l.detail} · box ${l.box}", color = Muted, fontSize = 12.sp, maxLines = 2)
                    }
                    Text(l.total?.let { eur(it) } ?: "no price", color = if (l.price != null) Gold else Warn, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−", l.quantity > 1) { vm.setOrderLineQty(i, l.quantity - 1) }
                    Text("${l.quantity}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
                    StepButton("+", true) { vm.setOrderLineQty(i, l.quantity + 1) }
                    Text(l.price?.let { "  × ${eur(it)}" } ?: "", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton({ vm.removeOrderLine(i) }) { Text("Remove", color = Warn) }
                }
                l.error?.let { Text("⚠ $it", color = Warn, fontSize = 12.sp) }
            }
        }
        if (shop.order.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                Text("${shop.orderCopies} cards", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(eur(shop.orderTotal), color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Black)
            }
            if (shop.order.any { it.price == null }) Text("Cards without a price aren't in the total", color = Warn, fontSize = 12.sp)
            Text("Takes every line out of its box in the shop, at once", color = Muted, fontSize = 12.sp)
            Button(onClick = { vm.completeSale() }, enabled = !shop.orderBusy,
                colors = ButtonDefaults.buttonColors(containerColor = SellBlue, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp)) {
                if (shop.orderBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink)
                else Text("Complete sale (${shop.orderCopies} cards)", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            shop.orderError?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠ $e", color = Warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (shop.orderRetryable) TextButton({ vm.retrySale() }) { Text("Retry") }
                }
            }
            if (confirmClear) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Empty the order? Nothing changes in the shop.", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton({ vm.clearOrder(); confirmClear = false }) { Text("Empty", color = Warn) }
                TextButton({ confirmClear = false }) { Text("Keep") }
            } else TextButton({ confirmClear = true }) { Text("Empty the order") }
        }
        shop.sale?.let { SaleBanner(state, vm) }
    }
}

/** The shop doesn't know the card: send its names along (from the card database, editable). */
@Composable
private fun UnknownCardDialog(de: String, en: String, vm: ScannerViewModel) {
    var nameDe by rememberSaveable { mutableStateOf(de) }
    var nameEn by rememberSaveable { mutableStateOf(en) }
    AlertDialog(
        onDismissRequest = { vm.dismissUnknownCard() },
        title = { Text("New card for the shop") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("The shop's card list doesn't have this card yet. Check its names, then it is created with them.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(nameDe, { nameDe = it }, label = { Text("German name") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(nameEn, { nameEn = it }, label = { Text("English name") }, singleLine = true, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { Button(onClick = { vm.bookShop(nameDe, nameEn) }, enabled = nameDe.isNotBlank() || nameEn.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton({ vm.dismissUnknownCard() }) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 6.dp), color = Color(0xFF2A2D34))
    Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun FieldLabel(text: String) = Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, end = 8.dp))

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).background(if (enabled) Color(0xFF2A2D34) else Color(0xFF1C1E23), RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Color.White else Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
