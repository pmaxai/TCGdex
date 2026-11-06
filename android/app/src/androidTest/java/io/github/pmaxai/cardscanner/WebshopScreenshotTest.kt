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
import androidx.compose.ui.test.isRoot
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
import io.github.pmaxai.cardscanner.ui.ShopState
import io.github.pmaxai.cardscanner.ui.Shown
import io.github.pmaxai.cardscanner.ui.UiState
import io.github.pmaxai.cardscanner.ui.WebshopPanel
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The Webshop panel for a real card of the data pack, in three states, saved as pictures (the CI prints them). */
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
        val base = UiState(loading = false, shown = shown, settings = Settings(webshop = true),
            packInfo = "21094 card images · data 2026-11-06 · PSA prices")
        val states = listOf(
            "1-in-shop" to base.copy(shop = ShopState(configured = true, host = "staging.pokemonkarten.at", connectionOk = true,
                cardRef = "sv03.5-199", products = listOf(inShop, enDraft),
                form = ShopForm(language = "DE", condition = "NM", quantity = 1, price = 129.0, box = 14))),
            "2-new" to base.copy(shop = ShopState(configured = true, host = "staging.pokemonkarten.at", connectionOk = true,
                cardRef = "sv03.5-199", products = emptyList(),
                form = ShopForm(language = "DE", condition = "EX", quantity = 2, price = 271.58, box = 14, comment = "Slight whitening on the back"),
                booking = ShopBooking("Added: 1 × Pikachu (DE · NM · Normal) to box 014 · now 3 in stock · 0.50 € · for sale",
                    listOf("No picture: it stays a draft (not visible in the shop)"), "sv1-1-DE-NM-N", 1, "014"))),
            "3-not-connected" to base.copy(shop = ShopState()),
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
        val shownUp = runCatching {
            compose.waitUntil(15_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        }.isSuccess
        if (!shownUp) { Log.w("WebshopScreenshot", "skipped: the test screen didn't come up"); return }
        for ((name, st) in states) {
            compose.runOnIdle { current = st }
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            TestStorage().openOutputFile("webshop-$name.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
