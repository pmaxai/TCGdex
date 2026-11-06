package io.github.pmaxai.cardscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pmaxai.cardscanner.trading.Condition
import io.github.pmaxai.cardscanner.trading.Reference
import io.github.pmaxai.cardscanner.update.AppUpdate

/** The settings pages, in the order of the sidebar. A new feature gets its own page here. */
enum class SettingsPage(val short: String, val title: String, val icon: ImageVector) {
    MODE("Mode", "Mode", Icons.Filled.Home),
    CAMERA("Camera", "Camera & scanning", Icons.Filled.Search),
    PRICES("Prices", "Prices & alerts", Icons.Filled.Notifications),
    TRADING("Trading", "Trading Mode", Icons.Filled.ShoppingCart),
    WEBSHOP("Webshop", "Webshop", Icons.AutoMirrored.Filled.List),
    YOUTUBE("YouTube", "YouTube Mode", Icons.Filled.PlayArrow),
    ADVANCED("Advanced", "Advanced", Icons.Filled.Build),
    ABOUT("About", "About & updates", Icons.Filled.Info),
}

/**
 * Settings: a screen of its own with a sidebar of pages. Narrow screens (a phone upright)
 * get a slim rail of icons with short names; wide ones (turned, tablets) a list with full names.
 */
@Composable
fun SettingsScreen(state: UiState, vm: ScannerViewModel, start: SettingsPage) {
    var page by rememberSaveable { mutableStateOf(start) }
    androidx.compose.runtime.LaunchedEffect(page) { vm.settingsPageShown(page) }
    BackHandler { vm.closeSettings() }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.closeSettings() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  ·  ${page.title}", color = Muted, fontSize = 16.sp, maxLines = 1)
        }
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Column(Modifier.width(220.dp).fillMaxHeight().background(Panel).verticalScroll(rememberScrollState()).padding(8.dp)) {
                    SettingsPage.values().forEach { p ->
                        NavigationDrawerItem(
                            label = { Text(p.title) }, selected = p == page, onClick = { page = p },
                            icon = { Icon(p.icon, contentDescription = null) },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Gold.copy(alpha = 0.18f), unselectedContainerColor = Color.Transparent,
                                selectedTextColor = Gold, selectedIconColor = Gold,
                                unselectedTextColor = Color.White, unselectedIconColor = Muted),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            } else {
                NavigationRail(containerColor = Panel, windowInsets = WindowInsets(0),      // insets as the rest of the app
                    modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                    SettingsPage.values().forEach { p ->
                        NavigationRailItem(
                            selected = p == page, onClick = { page = p },
                            icon = { Icon(p.icon, contentDescription = p.title) },
                            label = { Text(p.short, fontSize = 11.sp, maxLines = 1) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Ink, indicatorColor = Gold, selectedTextColor = Gold,
                                unselectedIconColor = Muted, unselectedTextColor = Muted),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                when (page) {
                    SettingsPage.MODE -> ModePage(state, vm)
                    SettingsPage.CAMERA -> CameraPage(state, vm)
                    SettingsPage.PRICES -> PricesPage(state, vm)
                    SettingsPage.TRADING -> TradingPage(state, vm)
                    SettingsPage.WEBSHOP -> WebshopPage(state, vm)
                    SettingsPage.YOUTUBE -> YouTubePage(state, vm)
                    SettingsPage.ADVANCED -> AdvancedPage(state, vm)
                    SettingsPage.ABOUT -> AboutPage(state, vm)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- pages

@Composable
private fun ModePage(state: UiState, vm: ScannerViewModel) {
    val s = state.settings
    Hint("What the app is for right now. ⚙ brings you back here from every mode.")
    AppMode.values().forEach { m ->
        val selected = m == s.mode
        Column(Modifier.fillMaxWidth().padding(top = 10.dp)
            .background(if (selected) Gold.copy(alpha = 0.15f) else Panel, RoundedCornerShape(14.dp))
            .border(1.dp, if (selected) Gold else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable { vm.updateSettings { it.withMode(m) } }
            .padding(14.dp)) {
            Text(m.label, color = if (selected) Gold else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(m.about, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CameraPage(state: UiState, vm: ScannerViewModel) {
    val s = state.settings
    Setting("Wait before taking the card", "how long the frame must stay green; double tap the camera to take it at once") {
        Choices(listOf(0, 300, 600, 1000, 1500, 2000), s.triggerDelayMs, label = ::delayLabel) { v -> vm.updateSettings { it.copy(triggerDelayMs = v) } }
    }
    Setting("Capture frame size", "height of the frame in the camera picture; bigger = hold the card closer") {
        Choices(listOf(50, 60, 70, 80, 90), s.guideSize, label = { "$it%" }) { v -> vm.updateSettings { it.copy(guideSize = v) } }
    }
    Setting("Camera zoom", "or pinch the camera picture") {
        Choices(listOf(1f, 1.5f, 2f, 3f, 4f), listOf(1f, 1.5f, 2f, 3f, 4f).minBy { kotlin.math.abs(it - s.zoom) }
            .takeIf { kotlin.math.abs(it - s.zoom) < 0.05f }, label = ::zoomLabel,
            enabled = { it <= state.maxZoom + 0.01f }) { v -> vm.setZoom(v) }
    }
    Setting("Card must fill the frame at least", "against \"ghost\" cards: smaller or off-centre cards, and the frame without a card outline, are ignored") {
        Choices(listOf(0, 30, 50, 70, 85), s.minFillPercent, label = { if (it == 0) "off" else "$it%" }) { v -> vm.updateSettings { it.copy(minFillPercent = v) } }
    }
    Setting("Energy cards", if (state.hasCategories) "\"skip common\" still recognises gold and secret rare energies" else "needs the next data pack") {
        Choices(listOf(0, 1, 2), s.energyFilter, label = { listOf("recognise", "skip common", "skip all")[it] },
            enabled = { it == 0 || state.hasCategories }) { v -> vm.updateSettings { it.copy(energyFilter = v) } }
    }
    Setting("Screen orientation", "Portrait and Landscape stay put; Auto turns with the phone") {
        Choices(listOf("auto", "portrait", "landscape"), s.orientation, label = { it.replaceFirstChar(Char::uppercase) }) { v ->
            vm.updateSettings { it.copy(orientation = v) }
        }
    }
}

@Composable
private fun PricesPage(state: UiState, vm: ScannerViewModel) {
    val s = state.settings
    Setting("Alert / HIT for cards worth at least", "Scanner: the result turns gold with a sound; YouTube: the HIT reveal") {
        Choices(listOf(0.0, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0), s.threshold, label = { if (it == 0.0) "off" else "${it.toInt()} €" }) { v ->
            vm.updateSettings { it.copy(threshold = v) }
        }
    }
    SettingSwitch("PSA 10 / 9 / 8 prices", if (state.hasPsaData) "graded prices: eBay sold averages, USD" else "not in this data pack yet",
        s.showPsa) { v -> vm.updateSettings { it.copy(showPsa = v) } }
    Heading("Where the prices come from")
    Hint("Cardmarket trend (EUR) first, TCGplayer (USD, shown in € where Cardmarket has none). " +
        "Prices of ${AppUpdate.day(state.pricesDate) ?: "–"}; a new app with that day's prices comes every night at about 01:15.")
    Hint("Price check: some cards carry another card's Cardmarket price in the card database. The app removes " +
        "those (TCGplayer is shown instead) or warns, and Trading Mode gives no deal verdict on them.")
}

@Composable
private fun TradingPage(state: UiState, vm: ScannerViewModel) {
    val s = state.settings
    if (!s.trading) Hint("Used in Trading Mode (Mode page).")
    Setting("Condition of a new scan", "you change it per card while trading") {
        Choices(Condition.values().toList(), s.tradingCondition, label = { it.label }) { v -> vm.updateSettings { it.copy(tradingCondition = v) } }
    }
    Setting("Market value starts from Cardmarket's", null) {
        Choices(Reference.values().toList(), s.reference, label = { it.label }) { v -> vm.updateSettings { it.copy(reference = v) } }
    }
    Heading("How prices are judged")
    Hint("Condition: NM 100 · EX 85 · GD 75 · LP 65 · PL 50 · PO 30 % of that price.")
    Hint("Verdict: up to 80 % of the market value great, 95 % good, 110 % fair, above expensive. Opening offer 85 %.")
    Hint("Show budget and the deal history are in Trading Mode's ⚙ menu.")
}

@Composable
private fun WebshopPage(state: UiState, vm: ScannerViewModel) {
    val shop = state.shop
    var host by rememberSaveable { mutableStateOf(shop.host) }
    var keyId by rememberSaveable { mutableStateOf(shop.keyId) }
    var secret by rememberSaveable { mutableStateOf("") }        // never shown back, only replaced
    Hint("The Pokeshop that Webshop Mode books into. Create a key in the shop's admin: Admin → API-Schlüssel " +
        "(one key per phone, no IP allowlist). Key id and secret are shown there once.")
    Setting("Shop", "its address, e.g. pokemonkarten.at, or staging.pokemonkarten.at for the test shop") {
        OutlinedTextField(value = host, onValueChange = { host = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Host") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    }
    Setting("Key id", null) {
        OutlinedTextField(value = keyId, onValueChange = { keyId = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("kid_…") })
    }
    Setting("Secret", if (shop.hasSecret) "saved, encrypted on this phone – type a new one only to replace it"
        else "sk_… – stored encrypted on this phone, never shown again") {
        OutlinedTextField(value = secret, onValueChange = { secret = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (shop.hasSecret) "•••••••• (saved)" else "sk_…") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.saveShop(host, keyId, secret); secret = "" },
            enabled = host.isNotBlank() && keyId.isNotBlank() && (secret.isNotBlank() || shop.hasSecret)) { Text("Save & test") }
        if (shop.configured) TextButton(onClick = { vm.testShop() }) { Text("Test again") }
    }
    when {
        shop.testing -> Hint("Testing the connection…")
        shop.connection != null -> Text((if (shop.connectionOk == true) "✓ " else "⚠ ") + shop.connection,
            color = if (shop.connectionOk == true) Color(0xFF5BC98A) else Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
    if (shop.configured || shop.hasSecret) {
        TextButton(onClick = { vm.disconnectShop(); host = ""; keyId = ""; secret = "" }) { Text("Disconnect (delete the key from this phone)") }
    }
    Heading("How booking works")
    Hint("Scanning a card only looks it up: you see its prices and what the shop holds of it. Stock changes only when you " +
        "press Add to stock / Create in the shop, after choosing language, condition, copies, price and box. Undo takes the copies out again.")
}

@Composable
private fun YouTubePage(state: UiState, vm: ScannerViewModel) {
    var title by rememberSaveable { mutableStateOf(state.settings.title) }
    Setting("Title line", "shown at the top of the picture in YouTube Mode (optional)") {
        OutlinedTextField(value = title, singleLine = true, modifier = Modifier.fillMaxWidth(),
            onValueChange = { v -> title = v; vm.updateSettings { it.copy(title = v) } },
            label = { Text("e.g. Surging Sparks booster box") })
    }
    Hint("The alert price for the HIT reveal is on the Prices page.")
}

@Composable
private fun AdvancedPage(state: UiState, vm: ScannerViewModel) {
    SettingSwitch("Details", "what the scanner saw, scores, text it read, holo check", state.settings.showDebug) { v ->
        vm.updateSettings { it.copy(showDebug = v) }
    }
}

@Composable
private fun AboutPage(state: UiState, vm: ScannerViewModel) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
    Text("Card Scanner ${version ?: ""}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(state.packInfo, color = Muted, fontSize = 13.sp)
    AppUpdate.day(state.pricesDate)?.let { Text("Prices of $it", color = Muted, fontSize = 13.sp) }
    Heading("Updates")
    val u = state.update
    if (u != null) {
        Text(updateLine(u, state.pricesDate), color = Gold, fontSize = 14.sp)
        u.error?.let { Text("Update failed: $it", color = Warn, fontSize = 12.sp) }
        Button(onClick = { vm.startUpdate() }, modifier = Modifier.padding(top = 6.dp)) {
            Text(if (u.progress != null) "Downloading… ${(u.progress * 100).toInt()} %" else if (u.apk != null) "Install" else "Update now")
        }
    } else {
        Hint("A new app with that day's prices comes every night at about 01:15; the app offers it at the top.")
        TextButton(onClick = { vm.checkForUpdate(announce = true) }) { Text("Check for updates") }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Heading(text: String) {
    HorizontalDivider(Modifier.padding(top = 18.dp, bottom = 8.dp), color = Panel)
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Hint(text: String) = Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))

/** One setting: its name, what it does, and the control. */
@Composable
private fun Setting(name: String, about: String?, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(name, color = Color.White, fontSize = 15.sp)
        if (about != null) Text(about, color = Muted, fontSize = 12.sp)
        Box(Modifier.padding(top = 4.dp)) { control() }
    }
}

/** The choices of a setting, wrapping onto more lines on a narrow page (all of them in sight). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T?, label: (T) -> String, enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { v ->
            FilterChip(selected = v == selected, enabled = enabled(v), onClick = { onSelect(v) }, label = { Text(label(v)) })
        }
    }
}

@Composable
fun SettingSwitch(label: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private fun delayLabel(v: Int): String = when {
    v == 0 -> "instant"
    v < 1000 -> "0.${v / 100} s"
    v % 1000 == 0 -> "${v / 1000} s"
    else -> "${v / 1000}.${v % 1000 / 100} s"
}
