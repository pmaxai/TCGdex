package io.github.pmaxai.cardscanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.PriceSource
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.trading.CartLine
import io.github.pmaxai.cardscanner.trading.Condition
import io.github.pmaxai.cardscanner.trading.Side
import io.github.pmaxai.cardscanner.trading.TradingBook
import io.github.pmaxai.cardscanner.ui.AppTab
import io.github.pmaxai.cardscanner.ui.BottomBar
import io.github.pmaxai.cardscanner.ui.CameraCorners
import io.github.pmaxai.cardscanner.ui.CartDialog
import io.github.pmaxai.cardscanner.ui.Confidence
import io.github.pmaxai.cardscanner.ui.DealDialog
import io.github.pmaxai.cardscanner.ui.DealDraft
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.ResultCard
import io.github.pmaxai.cardscanner.ui.SearchCardPage
import io.github.pmaxai.cardscanner.ui.Settings
import io.github.pmaxai.cardscanner.ui.Shown
import io.github.pmaxai.cardscanner.ui.UiState
import io.github.pmaxai.cardscanner.ui.setLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Scanner with carts (as on the iPhone): the result with Add to cart, the camera's corners,
 * the Add to cart popup, the cart, a card opened in the search, the bottom bar – as pictures.
 */
@RunWith(AndroidJUnit4::class)
class MergedScannerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun catalog(): Catalog {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.cacheDir, "account-test-catalog.sqlite")
        if (!f.exists()) ctx.assets.open("pack/catalog.sqlite").use { i -> f.outputStream().use { i.copyTo(it, 1 shl 20) } }
        return Catalog(SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY))
    }

    @Test
    fun scannerWithCarts() {
        val cat = catalog()
        val card = cat.cardExact("sv03.5-199", "de") ?: cat.cardExact("sv03.5-199", "en")
        if (card == null) { Log.w("MergedScreenshot", "card not in this pack"); return }
        val vm = TestVm.shared
        val kind = card.printings.firstOrNull { it.kind == Printing.Kind.HOLO }?.kind ?: card.printings.firstOrNull()?.kind
        val s = Shown(group = "sv03.5-199", version = card, english = cat.cardExact("sv03.5-199", "en"), priceCard = card, printing = kind,
            printingByUser = false, foil = null, confidence = Confidence.SURE, alternatives = emptyList(), languages = emptyList(),
            text = null, locked = true, fromPhoto = false, preview = null, serial = 1, history = runCatching { cat.history(card.id) }.getOrNull())
        // every source of the printing, in the table
        val p = s.selected!!
        Log.i("MergedScreenshot", "sources of ${card.id} ${p.kind}: " +
            PriceSource.values().joinToString { "${it.name}=${p.raw(it)}" } + " usdPerEur=${p.usdPerEur}")
        val now = System.currentTimeMillis()
        val (b0, cart) = TradingBook().withActive(now)
        val book = b0.replace(cart.copy(lines = listOf(
            CartLine(1, card.id, card.lang, s.group, card.name, card.setLine(), null, kind?.name, 40.0, Condition.NM, 1, 35.0, null, added = now),
            CartLine(2, "base1-4", "en", "base1-4", "Charizard", "Base Set · 4/102 · EN · 1999", null, "HOLO", 320.0, Condition.EX, 1, 250.0, null, added = now),
        )))
        val base = UiState(loading = false, shown = s, account = TestAccount.ui(), settings = Settings(), book = book)
        var st by mutableStateOf(base)
        var page by mutableStateOf("scanner")
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                when (page) {
                    // the camera stands in as a grey box, with its real corners
                    "scanner" -> Column(Modifier.fillMaxSize().background(Ink)) {
                        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF30333A))) {
                            Text("camera", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                            CameraCorners(st, vm, torch = false, onTorch = {}, onCart = {}, onPhoto = {})
                        }
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) { ResultCard(s, st, vm) }
                        BottomBar(st, vm)
                    }
                    "result" -> Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        ResultCard(s, st, vm)
                        Spacer(Modifier.height(24.dp))
                    }
                    "deal" -> DealDialog(s, st, vm)
                    "cart" -> CartDialog(st, vm) {}
                    "search" -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { SearchCardPage(s.copy(fromSearch = true), st, vm) }
                        BottomBar(st, vm)
                    }
                }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("MergedScreenshot", "skipped: the test screen didn't come up"); return }
        compose.onNodeWithContentDescription("Cart").assertExists()
        compose.onNodeWithContentDescription("Scan a photo").assertExists()
        compose.onNodeWithText("2").assertExists()          // the cart's cards
        compose.onNodeWithText("Scanner").assertExists()
        compose.onNodeWithText("Search").assertExists()
        Screenshots.saveScreen(compose, "scanner-carts.png")

        compose.runOnIdle { page = "result" }
        compose.onNodeWithText("★ Trend").assertExists()
        compose.onNodeWithText("Add to cart", substring = true).performScrollTo().assertExists()
        Screenshots.save(compose, "scanner-result-prices.png")

        // TCGplayer as the default price: shown first, the table marks it
        compose.runOnIdle { st = base.copy(settings = Settings(priceSource = PriceSource.TCG_MARKET)) }
        if (p.raw(PriceSource.TCG_MARKET) != null) compose.onNodeWithText("★ Market price").assertExists()
        Screenshots.save(compose, "scanner-result-tcgplayer.png")

        compose.runOnIdle { st = base.copy(deal = s, draft = DealDraft(Condition.EX, 30.0)); page = "deal" }
        compose.onNodeWithText("Add to cart · Booth 1").assertExists()
        Screenshots.saveScreen(compose, "add-to-cart.png")
        compose.runOnIdle { st = base.copy(deal = s, draft = DealDraft(Condition.NM, 30.0), scanTarget = Side.GIVE) }
        compose.onNodeWithText("Add to I give · Booth 1").assertExists()
        Screenshots.saveScreen(compose, "add-to-cart-give.png")

        compose.runOnIdle { st = base; page = "cart" }
        compose.onNodeWithContentDescription("Deals, show and carts").assertExists()
        Screenshots.saveScreen(compose, "cart.png")

        compose.runOnIdle { st = base.copy(searchTab = true, searchCard = s); page = "search" }
        assertEquals(AppTab.SEARCH, base.copy(searchTab = true).tab)
        compose.onNodeWithContentDescription("Back to the search").assertExists()
        Screenshots.saveScreen(compose, "search-card.png")
        compose.onNodeWithText("Cardmarket", substring = false).performScrollTo()
        Screenshots.saveScreen(compose, "search-card-bottom.png")

        // the free version: the cart and photo locked
        compose.runOnIdle { st = base.copy(account = base.account.copy(gate = io.github.pmaxai.cardscanner.account.AccountGate.CONNECT)); page = "scanner" }
        compose.onNodeWithContentDescription("Cart, needs a subscription").assertExists()
        compose.onNodeWithContentDescription("Photo, needs a subscription").assertExists()
        Screenshots.saveScreen(compose, "scanner-free.png")
    }
}
