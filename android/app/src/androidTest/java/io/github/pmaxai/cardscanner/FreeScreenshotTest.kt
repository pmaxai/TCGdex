package io.github.pmaxai.cardscanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.ui.AccountGateContent
import io.github.pmaxai.cardscanner.ui.AccountUi
import io.github.pmaxai.cardscanner.ui.CollectionPart
import io.github.pmaxai.cardscanner.ui.CollectionScreen
import io.github.pmaxai.cardscanner.ui.Confidence
import io.github.pmaxai.cardscanner.ui.Free
import io.github.pmaxai.cardscanner.ui.FreeNote
import io.github.pmaxai.cardscanner.ui.FreeReveal
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.LocalItem
import io.github.pmaxai.cardscanner.ui.LockedDialog
import io.github.pmaxai.cardscanner.ui.LockedFeature
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.ResultCard
import io.github.pmaxai.cardscanner.ui.Settings
import io.github.pmaxai.cardscanner.ui.Shown
import io.github.pmaxai.cardscanner.ui.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The free version as pictures: a scanned card with its price to reveal, revealed, the hour's limit, the phone's collection, the locked Missing tab, ⚙ → Account, a locked feature. */
@RunWith(AndroidJUnit4::class)
class FreeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun freeVersion() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.cacheDir, "account-test-catalog.sqlite")
        if (!f.exists()) ctx.assets.open("pack/catalog.sqlite").use { i -> f.outputStream().use { i.copyTo(it, 1 shl 20) } }
        val cat = Catalog(SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY))
        val card = cat.cardExact("sv03.5-199", "de") ?: cat.cardExact("sv03.5-199", "en")
        if (card == null) { Log.w("FreeScreenshot", "card not in this pack"); return }
        val vm = TestVm.shared
        val kind = card.printings.firstOrNull { it.kind == Printing.Kind.HOLO }?.kind ?: card.printings.firstOrNull()?.kind
        val s = Shown(group = "sv03.5-199", version = card, english = null, priceCard = card, printing = kind,
            printingByUser = false, foil = null, confidence = Confidence.SURE, alternatives = emptyList(), languages = emptyList(),
            text = null, locked = true, fromPhoto = false, preview = null, serial = 1)
        val now = System.currentTimeMillis()
        val items = listOf(
            LocalItem(cardId = "sv03.5-199", language = "DE", finish = "holo", condition = "NM", quantity = 2, label = "Glurak-ex · 151 199/165"),
            LocalItem(cardId = "base1-4", language = "EN", finish = "holo", condition = "GD", quantity = 1, label = "Charizard · Base Set 4/102"),
            LocalItem(cardId = "sv08-059", language = "EN", finish = "normal", condition = "NM", quantity = 1, label = "Pikachu-ex · Surging Sparks 059/191"),
        )
        val free = AccountUi(required = true, gate = AccountGate.CONNECT,
            reveals = (0 until 3).map { FreeReveal("other-$it", now - 60_000L * it) }, localItems = items)
        var st by mutableStateOf(UiState(loading = false, shown = s, account = free, settings = Settings()))
        var page by mutableStateOf("result")
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                when (page) {
                    "result" -> Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        ResultCard(s, st, vm)
                    }
                    "collection" -> CollectionScreen(st, vm, CollectionPart.COLLECTION)
                    "missing" -> CollectionScreen(st, vm, CollectionPart.MISSING)
                    "account" -> Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        FreeNote()
                        // the camera stands in as a grey box
                        AccountGateContent(st, vm) { m -> Box(m.background(Color(0xFF30333A)), contentAlignment = Alignment.Center) { Text("camera", color = Color.Gray) } }
                    }
                    "locked" -> Box(Modifier.fillMaxSize().background(Ink)) { LockedDialog(LockedFeature.SEARCH, onPlans = {}, onClose = {}) }
                }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("FreeScreenshot", "skipped: the test screen didn't come up"); return }
        compose.onNodeWithText("👁  Reveal the price").assertExists()
        compose.onNodeWithText("7 of ${Free.REVEALS_PER_HOUR} left this hour in the free version").assertExists()
        compose.onNodeWithText("🔒 Missing").assertExists()
        compose.onNodeWithText("In collection: 2", substring = true).assertExists()
        Screenshots.save(compose, "free-result.png")

        compose.runOnIdle { st = st.copy(account = free.copy(reveals = free.reveals + FreeReveal(card.id, now))) }
        compose.onNodeWithText("Reveal the price", substring = true).assertDoesNotExist()
        Screenshots.save(compose, "free-revealed.png")

        compose.runOnIdle { st = st.copy(account = free.copy(reveals = (0 until 10).map { FreeReveal("other-$it", now - 60_000L * it) })) }
        compose.onNodeWithText("You've seen ${Free.REVEALS_PER_HOUR} prices this hour.").assertExists()
        Screenshots.save(compose, "free-limit.png")

        compose.runOnIdle { page = "collection" }
        compose.onNodeWithText("3 of ${Free.MAX_CARDS} cards · 4 copies").assertExists()
        Screenshots.save(compose, "free-collection.png")

        compose.runOnIdle { page = "missing" }
        compose.onNodeWithText("Missing cards need a subscription").assertExists()
        Screenshots.save(compose, "free-missing.png")

        compose.runOnIdle { page = "account" }
        compose.onNodeWithText("Free version").assertExists()
        Screenshots.save(compose, "free-account.png")

        compose.runOnIdle { page = "locked" }
        compose.onNodeWithText("Search needs a subscription").assertExists()
        Screenshots.saveScreen(compose, "free-locked.png")
    }
}
