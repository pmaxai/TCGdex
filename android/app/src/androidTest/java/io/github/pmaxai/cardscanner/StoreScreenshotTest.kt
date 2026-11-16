package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.account.storeEditionForTests
import io.github.pmaxai.cardscanner.store.Affiliate
import io.github.pmaxai.cardscanner.store.PlanOffer
import io.github.pmaxai.cardscanner.store.StoreOffer
import io.github.pmaxai.cardscanner.store.StorePurchase
import io.github.pmaxai.cardscanner.ui.AccountGateScreen
import io.github.pmaxai.cardscanner.ui.AccountUi
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.SettingsPage
import io.github.pmaxai.cardscanner.ui.SettingsScreen
import io.github.pmaxai.cardscanner.ui.StoreUi
import io.github.pmaxai.cardscanner.ui.UiState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Play edition's screens as pictures: sign-in, the email code, the plans, ⚙ → Account with extra devices. */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @After fun tearDown() { storeEditionForTests = null }

    private val offers = PlanOffer.parse(JSONArray()
        .put(offer("collector", "Collector", listOf("scanner", "trading", "collection", "wanted"), 4))
        .put(offer("creator", "Creator", listOf("scanner", "trading", "youtube", "recording", "collection", "wanted"), 2)))

    private val withCode = PlanOffer.parse(JSONArray()
        .put(offer("collector", "Collector", listOf("scanner", "trading", "collection", "wanted"), 4, "creator-20-3m"))
        .put(offer("creator", "Creator", listOf("scanner", "trading", "youtube", "recording", "collection", "wanted"), 2, "creator-20-3m")))

    private fun offer(plan: String, name: String, f: List<String>, devices: Int, offerId: String? = null) = JSONObject().put("plan", plan).put("name", name)
        .put("features", JSONArray(f)).put("google", JSONObject().put("product_id", "oc_$plan")
            .put("base_plans", JSONObject().put("monthly", "monthly").put("yearly", "yearly"))
            .put("device_product_ids", JSONArray((1..devices).map { "oc_${plan}_device_$it" }))
            .put("offer_id", offerId ?: JSONObject.NULL))

    private fun prices(): Map<String, Map<String, StoreOffer>> {
        val m = HashMap<String, Map<String, StoreOffer>>()
        fun put(id: String, month: String, year: String) { m[id] = mapOf("monthly" to StoreOffer(id, "monthly", month, "t"), "yearly" to StoreOffer(id, "yearly", year, "t")) }
        put("oc_collector", "€7.00", "€75.60"); put("oc_creator", "€12.00", "€129.60")
        // a creator code's offer: 20 % off the first 3 months (the first year)
        m["oc_collector"] = m.getValue("oc_collector") + mapOf(
            "monthly:creator-20-3m" to StoreOffer("oc_collector", "monthly", "€7.00", "t", "creator-20-3m", "€5.60", 3),
            "yearly:creator-20-3m" to StoreOffer("oc_collector", "yearly", "€75.60", "t", "creator-20-3m", "€60.48", 1))
        m["oc_creator"] = m.getValue("oc_creator") + mapOf(
            "monthly:creator-20-3m" to StoreOffer("oc_creator", "monthly", "€12.00", "t", "creator-20-3m", "€9.60", 3),
            "yearly:creator-20-3m" to StoreOffer("oc_creator", "yearly", "€129.60", "t", "creator-20-3m", "€103.68", 1))
        (1..4).forEach { put("oc_collector_device_$it", "€4.00", "€43.20") }
        (1..2).forEach { put("oc_creator_device_$it", "€7.00", "€75.60") }
        return m
    }

    @Test
    fun signInAndPlans() {
        storeEditionForTests = true
        val vm = TestVm.shared
        val shop = StoreTest.StoreShop()
        val none = AccountStatus.parse(shop.status())
        val base = UiState(loading = false)
        val states = listOf(
            "signin" to base.copy(account = AccountUi(required = true, gate = AccountGate.CONNECT)),
            "code" to base.copy(account = AccountUi(required = true, gate = AccountGate.CONNECT, emailSentTo = "anna@example.com",
                resendAt = Long.MAX_VALUE)),
            "plans" to base.copy(account = AccountUi(required = true, gate = AccountGate.ENDED, status = none,
                store = StoreUi(offers = offers, prices = prices()))),
            "plans-code" to base.copy(account = AccountUi(required = true, gate = AccountGate.ENDED, status = none,
                store = StoreUi(offers = withCode, prices = prices(), affiliate = Affiliate("MISTY", 20, 3, "creator-20-3m")))),
        )
        var shown by mutableStateOf(states.first().second)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                key(shown) { AccountGateScreen(shown, vm) { m -> Box(m.background(Color(0xFF30333A)), contentAlignment = Alignment.Center) { Text("camera", color = Color.Gray) } } }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("StoreScreenshot", "skipped: the test screen didn't come up"); return }
        for ((name, st) in states) {
            compose.runOnIdle { shown = st }
            if (!Screenshots.ready(compose)) { Log.w("StoreScreenshot", "$name skipped: the test screen went away"); return }
            when (name) {
                "signin" -> compose.onNodeWithText("Sign in to choose a plan, or to use the one you have.").assertExists()
                "code" -> compose.onNodeWithText("We sent a 6-digit code to anna@example.com.").assertExists()
                "plans" -> { compose.onNodeWithText("Choose your plan").assertExists(); compose.onNodeWithText("€12.00 / month").assertExists()
                    compose.onNodeWithText("Have a creator code?").assertExists() }
                "plans-code" -> { compose.onNodeWithText("Creator code MISTY · 20 % off for 3 months").assertExists()
                    compose.onNodeWithText("€9.60 / month for 3 months, then €12.00").assertExists() }
            }
            Screenshots.save(compose, "store-$name.png")
        }
    }

    @Test
    fun accountWithExtraDevices() {
        storeEditionForTests = true
        val vm = TestVm.shared
        val shop = StoreTest.StoreShop().apply { plan = "collector"; source = "google_play"; inStore = 1 }
        val st = AccountStatus.parse(shop.status())
        val ui = AccountUi(required = true, gate = AccountGate.OPEN, status = st, lastCheck = System.currentTimeMillis(),
            store = StoreUi(offers = offers, prices = prices(),
                purchase = StorePurchase("gp-1-d1", listOf("oc_collector", "oc_collector_device_1"), true, shop.storeRef)))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                SettingsScreen(UiState(loading = false, account = ui), vm, SettingsPage.ACCOUNT)
            }
        }
        if (!Screenshots.ready(compose)) return
        compose.onNodeWithText("+ Add a device · €4.00").assertExists()
        compose.onNodeWithText("Switch to Creator · €12.00 / month").assertExists()
        Screenshots.save(compose, "store-account.png")
    }
}
