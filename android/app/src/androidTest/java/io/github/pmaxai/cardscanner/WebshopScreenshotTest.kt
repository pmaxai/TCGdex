package io.github.pmaxai.cardscanner

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.shop.ShopProduct
import io.github.pmaxai.cardscanner.ui.Confidence
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.ScannerViewModel
import io.github.pmaxai.cardscanner.ui.Settings
import io.github.pmaxai.cardscanner.ui.ShopBooking
import io.github.pmaxai.cardscanner.ui.ShopForm
import io.github.pmaxai.cardscanner.ui.ShopOrderLine
import io.github.pmaxai.cardscanner.ui.ShopSale
import io.github.pmaxai.cardscanner.ui.ShopState
import io.github.pmaxai.cardscanner.ui.Shown
import io.github.pmaxai.cardscanner.ui.UiState
import io.github.pmaxai.cardscanner.ui.WebshopPanel
import io.github.pmaxai.cardscanner.ui.OrderSheet
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The Webshop panel for a real card of the data pack, in its states (Stock in and Sell), saved as pictures (the CI prints them). */
@RunWith(AndroidJUnit4::class)
class WebshopScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun catalog(): Catalog {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.cacheDir, "webshop-test-catalog.sqlite")
        if (!f.exists()) ctx.assets.open("pack/catalog.sqlite").use { i -> f.outputStream().use { i.copyTo(it, 1 shl 20) } }
        return Catalog(SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY))
    }

    @Test
    fun panelStates() {
        val cat = catalog()
        val de = cat.cardExact("sv03.5-199", "de") ?: cat.cardExact("sv03.5-199", "en")
        if (de == null) { Log.w("WebshopScreenshot", "card not in this pack"); return }
        val en = cat.cardExact("sv03.5-199", "en")
        val kind = de.printings.firstOrNull()?.kind ?: Printing.Kind.HOLO
        val shown = Shown(group = "sv03.5-199", version = de, english = en?.takeIf { de.lang != "en" }, priceCard = de, printing = kind,
            printingByUser = false, foil = null, confidence = Confidence.SURE, alternatives = emptyList(), languages = emptyList(),
            text = null, locked = true, fromPhoto = false, preview = null, serial = 1)
        val inShop = ShopProduct.from(JSONObject(ShopApiTest.PRODUCT))
        val enDraft = ShopProduct.from(JSONObject(ShopApiTest.PRODUCT.replace("sv03.5-199-DE-NM-H", "sv03.5-199-EN-EX-H")
            .replace("\"card_language\":\"DE\"", "\"card_language\":\"EN\"").replace("\"condition\":\"NM\"", "\"condition\":\"EX\"")
            .replace("\"status\":\"active\"", "\"status\":\"draft\"")))
        val order = listOf(ShopOrderLine("sv1-1-DE-NM-N", "Pikachu", "DE · NM · Normal · 1/198", "003", 1, 0.50),
            ShopOrderLine(inShop.sku, "Glurak-ex", "DE · NM · Holo · 199/165", "014", 2, 129.0))
        val base = UiState(loading = false, shown = shown, settings = Settings(webshop = true),
            packInfo = "21094 card images · data 2026-11-06 · PSA prices")
        val states = listOf(
            "1-in-shop" to base.copy(shop = ShopState(configured = true, host = "staging.pokemonkarten.at", connectionOk = true,
                cardRef = "sv03.5-199", products = listOf(inShop, enDraft),
                form = ShopForm(language = "DE", condition = "NM", quantity = 1, price = 129.0, box = 14),
                booking = ShopBooking("Added", emptyList(), inShop.sku, 1, "014"))),
            "2-new" to base.copy(shop = ShopState(configured = true, host = "staging.pokemonkarten.at", connectionOk = true,
                cardRef = "sv03.5-199", products = emptyList(),
                form = ShopForm(language = "DE", condition = "EX", quantity = 2, price = 271.58, box = 14, comment = "Slight whitening on the back"),
                booking = ShopBooking("Added: 1 × Pikachu (DE · NM · Normal) to box 014 · now 3 in stock · 0.50 € · for sale",
                    listOf("No picture: it stays a draft (not visible in the shop)"), "sv1-1-DE-NM-N", 1, "014"))),
            "3-not-connected" to base.copy(shop = ShopState()),
            "4-sell" to base.copy(shop = ShopState(configured = true, host = "staging.pokemonkarten.at", connectionOk = true,
                cardRef = "sv03.5-199", products = listOf(inShop, enDraft), selling = true,
                sellSku = inShop.sku, sellBox = "014", sellQty = 2, order = order.take(1),
                sale = ShopSale("Sold 1 card · 0.50 € – taken out of stock", order.take(1)))),
            "5-sold" to base.copy(shop = ShopState(configured = true, host = "staging.pokemonkarten.at", connectionOk = true,
                cardRef = "sv03.5-199", products = emptyList(), selling = true,
                sale = ShopSale("Sold 3 cards · 258.50 € – taken out of stock", order))),
        )
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ScannerViewModel(ApplicationProvider.getApplicationContext<Application>())
        }
        var current by mutableStateOf(states.first().second)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                Box(Modifier.fillMaxSize().background(Ink)) {
                    key(current) { WebshopPanel(current, vm, Modifier.fillMaxSize()) {} }
                }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("WebshopScreenshot", "skipped: the test screen didn't come up"); return }
        for ((name, st) in states) {
            compose.runOnIdle { current = st }
            compose.waitForIdle()
            Screenshots.save(compose, "webshop-$name.png")
            // the form further down: scroll the panel to its end
            runCatching {
                repeat(3) { compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() } }
                Screenshots.save(compose, "webshop-$name-form.png")
            }.onFailure { Log.w("WebshopScreenshot", "$name: no scrolled picture: ${it.message}") }
        }
    }

    /** The customer's order, with a line the shop refused. */
    @Test
    fun orderDialog() {
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ScannerViewModel(ApplicationProvider.getApplicationContext<Application>())
        }
        val st = UiState(loading = false, settings = Settings(webshop = true), shop = ShopState(configured = true,
            host = "staging.pokemonkarten.at", connectionOk = true, selling = true, order = listOf(
                ShopOrderLine("sv1-1-DE-NM-N", "Pikachu", "DE · NM · Normal · 1/198", "003", 1, 0.50),
                ShopOrderLine("sv03.5-199-DE-NM-H", "Glurak-ex", "DE · NM · Holo · 199/165", "014", 2, 129.0,
                    error = "Box 014 holds only 1 – nothing was taken out"),
                ShopOrderLine("sv2-12-EN-EX-R", "Oinkologne", "EN · EX · Reverse Holo · 12/193", "120", 3, null))))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                Box(Modifier.fillMaxSize().background(Panel)) { OrderSheet(st, vm) {} }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("WebshopScreenshot", "skipped: the test screen didn't come up"); return }
        Screenshots.save(compose, "webshop-6-order.png")
    }
}
