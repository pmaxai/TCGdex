package io.github.pmaxai.cardscanner

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.account.PendingOp
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.ui.AccountGateScreen
import io.github.pmaxai.cardscanner.ui.AccountUi
import io.github.pmaxai.cardscanner.ui.CollectionBar
import io.github.pmaxai.cardscanner.ui.CollectionLine
import io.github.pmaxai.cardscanner.ui.Confidence
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.ScannerViewModel
import io.github.pmaxai.cardscanner.ui.Settings
import io.github.pmaxai.cardscanner.ui.SettingsPage
import io.github.pmaxai.cardscanner.ui.SettingsScreen
import io.github.pmaxai.cardscanner.ui.Shown
import io.github.pmaxai.cardscanner.ui.UiState
import io.github.pmaxai.cardscanner.ui.WantedItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The account's screens as pictures: connect, connected, ended, offline too long; Mode for a Collector; + Collection and ☆ Missing. */
@RunWith(AndroidJUnit4::class)
class AccountScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun screens() {
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = ScannerViewModel(ApplicationProvider.getApplicationContext<Application>()) }
        val base = UiState(loading = false)
        val creator = AccountStatus.parse(TestAccount.statusJson())
        val states = listOf(
            "connect" to base.copy(account = AccountUi(required = true, gate = AccountGate.CONNECT)),
            "connect-moved" to base.copy(account = AccountUi(required = true, gate = AccountGate.CONNECT, notice = "Moved to Pixel 9 on 15 Nov 2026.")),
            "connected" to base.copy(account = AccountUi(required = true, gate = AccountGate.OPEN, status = creator, justConnected = true)),
            "ended" to base.copy(account = AccountUi(required = true, gate = AccountGate.ENDED,
                status = AccountStatus.parse(TestAccount.statusJson(active = false, state = "ended")))),
            "check-online" to base.copy(account = AccountUi(required = true, gate = AccountGate.CHECK_ONLINE, status = creator,
                lastCheck = System.currentTimeMillis() - 40 * 86_400_000L)),
        )
        var shown by mutableStateOf(states.first().second)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                // the camera stands in as a grey box
                key(shown) { AccountGateScreen(shown, vm) { m -> Box(m.background(Color(0xFF30333A)), contentAlignment = Alignment.Center) { Text("camera", color = Color.Gray) } } }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("AccountScreenshot", "skipped: the test screen didn't come up"); return }
        for ((name, st) in states) {
            compose.runOnIdle { shown = st }
            compose.waitForIdle()
            when (name) {
                "connect" -> compose.onNodeWithText("Connect the app to your account").assertExists()
                "connected" -> compose.onNodeWithText("anna@example.com").assertExists()
                "ended" -> compose.onNodeWithText("Your subscription has ended").assertExists()
                "check-online" -> compose.onNodeWithText("Connect to the internet once to check your subscription").assertExists()
            }
            Screenshots.save(compose, "account-$name.png")
        }
    }

    /** Mode for a Collector: YouTube and Webshop greyed out with what they need. */
    @Test
    fun modesTheplanLacks() {
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = ScannerViewModel(ApplicationProvider.getApplicationContext<Application>()) }
        val st = UiState(loading = false, account = TestAccount.ui(TestAccount.ALL - setOf("youtube", "recording", "s3_upload", "pipeline", "webshop"), plan = "collector"))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                SettingsScreen(st, vm, SettingsPage.MODE)
            }
        }
        if (!Screenshots.ready(compose)) return
        compose.onNodeWithText("Creator").assertExists()
        compose.onNodeWithText("With a connected webshop").assertExists()
        Screenshots.save(compose, "account-modes-collector.png")
    }

    /** Under a card: + Collection, ★ Missing with the shop's offer, "In collection: 2 (…) · +1 waiting to sync". */
    @Test
    fun collectionAndMissing() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.cacheDir, "account-test-catalog.sqlite")
        if (!f.exists()) ctx.assets.open("pack/catalog.sqlite").use { i -> f.outputStream().use { i.copyTo(it, 1 shl 20) } }
        val cat = Catalog(SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY))
        val de = cat.cardExact("sv03.5-199", "de") ?: cat.cardExact("sv03.5-199", "en")
        if (de == null) { Log.w("AccountScreenshot", "card not in this pack"); return }
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = ScannerViewModel(ApplicationProvider.getApplicationContext<Application>()) }
        val kind = de.printings.firstOrNull { it.kind == Printing.Kind.HOLO }?.kind ?: de.printings.firstOrNull()?.kind
        val s = Shown(group = "sv03.5-199", version = de, english = null, priceCard = de, printing = kind,
            printingByUser = false, foil = null, confidence = Confidence.SURE, alternatives = emptyList(), languages = emptyList(),
            text = null, locked = true, fromPhoto = false, preview = null, serial = 1)
        val lang = io.github.pmaxai.cardscanner.shop.ShopTerms.languageOf(de.lang)
        val acc = TestAccount.ui().copy(
            collection = mapOf("sv03.5-199" to listOf(CollectionLine(lang, "holo", "NM", 2, null), CollectionLine("EN", "normal", "EX", 1, null))),
            pending = listOf(PendingOp("collection_add", "ref-1", "acc_test0001", "sv03.5-199",
                org.json.JSONObject().put("quantity", 1), label = "${de.name}")),
            wanted = listOf(WantedItem(77, null, "sv03.5-199", lang, null, "offer")))
        val st = UiState(loading = false, shown = s, account = acc, settings = Settings())
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                Column(Modifier.fillMaxSize().background(Ink).padding(16.dp)) {
                    Text(de.name, color = Color.White)
                    CollectionBar(st, vm, s)
                }
            }
        }
        if (!Screenshots.ready(compose)) return
        compose.onNodeWithText("+ Collection").assertExists()
        compose.onNodeWithText("★ Missing").assertExists()
        compose.onNodeWithText("Offer from the shop ›").assertExists()
        compose.onNodeWithText("In collection: 3", substring = true).assertExists()
        Screenshots.save(compose, "account-collection.png")
    }
}
