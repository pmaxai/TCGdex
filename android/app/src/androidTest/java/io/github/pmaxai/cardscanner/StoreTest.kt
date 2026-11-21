package io.github.pmaxai.cardscanner

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.account.AccountStore
import io.github.pmaxai.cardscanner.store.Affiliate
import io.github.pmaxai.cardscanner.store.BuyRequest
import io.github.pmaxai.cardscanner.store.BuyResult
import io.github.pmaxai.cardscanner.store.Replace
import io.github.pmaxai.cardscanner.store.StoreBilling
import io.github.pmaxai.cardscanner.store.StoreOffer
import io.github.pmaxai.cardscanner.store.StorePurchase
import io.github.pmaxai.cardscanner.ui.AccountCoordinator
import io.github.pmaxai.cardscanner.ui.AccountUi
import io.github.pmaxai.cardscanner.ui.StoreCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The Play edition's account and purchases (app-account-api.md: Sign-in, Buying in Google Play,
 * Extra devices, account deletion) against a stand-in of the shop and of Play Billing: sign in by
 * email code, buy a plan with the account's store_ref, report it; extra devices as add-ons of
 * the running purchase (KEEP_EXISTING); a plan change moves the devices; a report that doesn't get
 * through waits; Restore purchases; delete the account.
 */
@RunWith(AndroidJUnit4::class)
class StoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var server: MockWebServer
    private lateinit var shop: StoreShop
    private lateinit var scope: CoroutineScope
    private val ui = MutableStateFlow(AccountUi())
    private lateinit var account: AccountCoordinator
    private lateinit var store: StoreCoordinator
    private val billing = FakeBilling()
    private lateinit var activity: Activity

    /** the shop's server for the store editions, in memory */
    class StoreShop : Dispatcher() {
        val storeRef = "0f6c1c58-3b8e-4f43-9c1f-2a7d5e9b4c10"
        @Volatile var token: String? = null
        @Volatile var plan: String? = null
        @Volatile var source: String? = null
        @Volatile var inStore = 0
        @Volatile var offline = false
        @Volatile var busyOnce = false
        @Volatile var refuse: String? = null
        @Volatile var deleted = false
        /** how the running subscription is billed (status.subscription.base_plan) */
        @Volatile var basePlan: String? = null
        /** the creator code the account is registered for */
        @Volatile var affiliate: String? = null
        /** the PSA prices add-on: its billing period while it runs */
        @Volatile var psa: String? = null
        /** creator codes that came with a sign-in */
        val signInCodes = CopyOnWriteArrayList<String>()
        val reports = CopyOnWriteArrayList<Pair<String, String>>()
        val deleteBodies = CopyOnWriteArrayList<JSONObject>()

        fun status(): JSONObject {
            val now = System.currentTimeMillis()
            val iso = { t: Long -> Instant.ofEpochMilli(t).toString() }
            val active = plan != null
            val f = JSONObject()
            TestAccount.ALL.forEach { f.put(it, active && (plan == "creator" || it in listOf("scanner", "trading", "collection", "wanted"))) }
            // an add-on unlocks its feature only while a plan runs
            f.put("psa_prices", active && psa != null)
            return JSONObject().put("status", if (active) "active" else "none").put("server_time", iso(now))
                .put("valid_until", iso(if (active) now + 30 * 86_400_000L else now))
                .put("account", JSONObject().put("id", "acc_store01").put("email", "anna@test.overpricedcardboard.com")
                    .put("store_ref", storeRef).put("email_verified", true))
                .put("subscription", JSONObject().put("plan", plan ?: JSONObject.NULL).put("state", if (active) "active" else "none")
                    .put("source", source ?: JSONObject.NULL).put("manage_url", "https://play.google.com/store/account/subscriptions")
                    .put("renews_at", if (active) iso(now + 29 * 86_400_000L) else JSONObject.NULL).put("ends_at", JSONObject.NULL)
                    .put("base_plan", if (active) basePlan ?: JSONObject.NULL else JSONObject.NULL)
                    .put("devices", JSONObject().put("included", 1).put("extra", inStore).put("in_store", inStore).put("on_web", 0).put("in_use", 1)))
                .put("features", f).put("webshop", JSONObject.NULL)
                .put("services", JSONObject().put("s3", JSONObject().put("enabled", false)))
                .put("portal_url", "https://overpricedcardboard.com/account")
                .put("addons", JSONArray(listOfNotNull(psa?.let { p ->
                    JSONObject().put("id", "psa_prices").put("state", "active").put("source", "google_play").put("base_plan", p)
                        .put("renews_at", iso(now + (if (p == "yearly") 365 else 30) * 86_400_000L)).put("ends_at", JSONObject.NULL)
                })))
        }

        private fun json(code: Int, o: Any) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(o.toString())
        private fun err(code: Int, error: String, extra: JSONObject = JSONObject()) = json(code, extra.put("error", error).put("message", error))

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            val path = request.requestUrl!!.encodedPath.removePrefix("/api/app/v1")
            val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            when {
                request.method == "POST" && path == "/login/email" -> return json(202, JSONObject().put("resend_after", 60))
                request.method == "POST" && path == "/login/email/verify" -> {
                    if (body.optString("code") != "000000") return err(400, "wrong_code", JSONObject().put("tries_left", 4))
                    val t = "tok_store_" + "y".repeat(34); token = t
                    val answer = JSONObject().put("token", t).put("created", true).put("status", status())
                    body.optString("affiliate_code").takeIf { it.isNotBlank() }?.let { c ->
                        signInCodes += c
                        if (c.equals("MISTY", true)) { affiliate = "MISTY"; answer.put("affiliate_code", "applied").put("affiliate", affiliateJson()) }
                        else answer.put("affiliate_code", "unknown_code").put("affiliate", JSONObject.NULL)
                    }
                    return json(200, answer)
                }
            }
            val auth = request.getHeader("Authorization")?.removePrefix("Bearer ")
            if (deleted) return err(401, "unpaired", JSONObject().put("reason", "account_deleted"))
            if (auth == null || auth != token) return err(401, "invalid_token")
            return when {
                request.method == "GET" && path == "/status" -> json(200, status())
                request.method == "GET" && path == "/store/offers" -> json(200, JSONObject().put("offers", JSONArray()
                    .put(offer("collector", "Collector", 4)).put(offer("creator", "Creator", 2)))
                    .put("addons", JSONArray().put(JSONObject().put("id", "psa_prices").put("name", "PSA prices").put("feature", "psa_prices")
                        .put("plans", JSONArray(listOf("collector", "creator")))
                        .put("google", JSONObject().put("product_id", "oc_psa").put("base_plans", JSONObject().put("monthly", "monthly").put("yearly", "yearly")))
                        .put("apple", JSONObject.NULL)))
                    .put("affiliate", if (affiliate != null && plan == null) affiliateJson() else JSONObject.NULL))
                request.method == "POST" && path == "/affiliate" -> {
                    val c = body.optString("code").uppercase()
                    when {
                        plan != null -> err(409, "already_customer")
                        affiliate != null && affiliate != c -> err(409, "already_registered", JSONObject().put("affiliate", affiliateJson()))
                        c != "MISTY" -> err(404, "unknown_code")
                        else -> { affiliate = c; json(200, JSONObject().put("affiliate", affiliateJson())) }
                    }
                }
                request.method == "POST" && path == "/store/google" -> {
                    if (busyOnce) { busyOnce = false; return err(503, "store_unavailable") }
                    refuse?.let { return err(409, it, JSONObject().put("source", "web")) }
                    val product = body.getString("product_id"); val t = body.getString("purchase_token")
                    reports += product to t
                    // an add-on is kept with the account, beside the plan
                    if (product == "oc_psa") { psa = if ("yearly" in t) "yearly" else "monthly"; return json(200, status()) }
                    plan = product.removePrefix("oc_"); source = "google_play"
                    inStore = Regex("-d(\\d)").find(t)?.groupValues?.get(1)?.toInt() ?: 0
                    json(200, status())
                }
                request.method == "POST" && path == "/account/delete" -> {
                    deleteBodies += body
                    if (!body.optBoolean("confirm")) return err(400, "invalid_request")
                    deleted = true
                    json(202, JSONObject().put("deleted", true).put("manage_url", JSONObject.NULL))
                }
                else -> err(404, "not_found")
            }
        }

        fun affiliateJson(): JSONObject = JSONObject().put("code", affiliate).put("discount_percent", 20).put("discount_months", 3)
            .put("google_offer_id", "creator-20-3m")

        private fun offer(plan: String, name: String, devices: Int) = JSONObject().put("plan", plan).put("name", name)
            .put("features", JSONArray(listOf("scanner", "trading")))
            .put("google", JSONObject().put("product_id", "oc_$plan")
                .put("base_plans", JSONObject().put("monthly", "monthly").put("yearly", "yearly"))
                .put("device_product_ids", JSONArray((1..devices).map { "oc_${plan}_device_$it" }))
                .put("offer_id", if (affiliate != null && this.plan == null) "creator-20-3m" else JSONObject.NULL))
            .put("apple", JSONObject.NULL)
    }

    /** Play Billing as the app sees it: prices, what the phone holds, the purchase sheet */
    class FakeBilling : StoreBilling {
        override val sells = true
        override var onLatePurchases: ((List<StorePurchase>) -> Unit)? = null
        val held = CopyOnWriteArrayList<StorePurchase>()
        val asked = CopyOnWriteArrayList<BuyRequest>()
        /** the next purchase sheet's answer: its token (with "-dN" for N devices), or null for cancelled */
        @Volatile var next: String? = "gp-1"

        override suspend fun googleIdToken(activity: Activity, serverClientId: String): String? = null

        /** the install referrer Play kept */
        @Volatile var referrer: String? = null
        override suspend fun installReferrer(): String? = referrer

        override suspend fun offers(productIds: List<String>) = productIds.associateWith { id ->
            if (id == "oc_psa") return@associateWith mapOf("monthly" to StoreOffer(id, "monthly", "€2.99", "$id:monthly"),
                "yearly" to StoreOffer(id, "yearly", "€32.00", "$id:yearly"))
            val m = mutableMapOf("monthly" to StoreOffer(id, "monthly", if ("device" in id) "€4.00" else "€7.00", "$id:monthly"),
                "yearly" to StoreOffer(id, "yearly", if ("device" in id) "€43.20" else "€75.60", "$id:yearly"))
            // the creator codes' offer under every base plan of the plans
            if ("device" !in id) {
                m["monthly:creator-20-3m"] = StoreOffer(id, "monthly", "€7.00", "$id:monthly:creator-20-3m", "creator-20-3m", "€5.60", 3)
                m["yearly:creator-20-3m"] = StoreOffer(id, "yearly", "€75.60", "$id:yearly:creator-20-3m", "creator-20-3m", "€60.48", 1)
            }
            m.toMap()
        }

        override suspend fun purchases(): List<StorePurchase> = held.toList()

        override suspend fun buy(activity: Activity, request: BuyRequest): BuyResult {
            asked += request
            val t = next ?: return BuyResult.Cancelled
            val p = StorePurchase(t, request.items.map { it.offer.productId }, true, request.accountRef)
            // a subscription each: the plan (with its devices), the PSA add-on
            val addon = "oc_psa" in p.products
            held.removeAll { ("oc_psa" in it.products) == addon }; held += p
            return BuyResult.Bought(listOf(p))
        }
    }

    @Before fun setUp() {
        AccountStore(app).pendingAffiliate = null
        AccountStore(app).referrerRead = true
        shop = StoreShop()
        server = MockWebServer().apply { dispatcher = shop; start() }
        AccountStore(app).forget(null)
        app.getSharedPreferences("store", 0).edit().clear().commit()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        account = AccountCoordinator(app, scope, { f -> ui.update(f) }, { ui.value }, { }, {}, {})
        account.http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        store = StoreCoordinator(app, scope, account, billing, { f -> ui.update { it.copy(store = f(it.store)) } }, { ui.value.store }, { ui.value.status })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity = Activity() }
    }

    @After fun tearDown() {
        scope.cancel()
        AccountStore(app).forget(null)
        app.getSharedPreferences("store", 0).edit().clear().commit()
        server.shutdown()
    }

    private fun waitUntil(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out: $what (gate ${ui.value.gate}, error ${ui.value.error}, store ${ui.value.store})" }
            Thread.sleep(50)
        }
    }

    private fun base() = "http://${server.hostName}:${server.port}"

    /** signed in by email code (Use another email), as the connect screen does it */
    private fun signIn() {
        account.home = base()
        account.onConnected = { store.start() }
        account.onAffiliate = { r, a -> store.signedInWithCode(r, a) }
        account.emailCode("anna@test.overpricedcardboard.com")
        waitUntil("the code is sent") { ui.value.emailSentTo != null && !ui.value.busy }
        account.emailVerify("000000")
        waitUntil("signed in") { AccountStore(app).token != null && ui.value.status != null && !ui.value.busy }
        waitUntil("the plans") { ui.value.store.onSale.size == 2 }
    }

    @Test
    fun aWrongCodeSaysTriesLeft() {
        account.home = base()
        account.emailCode("anna@test.overpricedcardboard.com")
        waitUntil("the code is sent") { ui.value.emailSentTo != null && !ui.value.busy }
        account.emailVerify("123456")
        waitUntil("said") { ui.value.error != null }
        assertEquals("That code isn't right – 4 tries left.", ui.value.error)
        assertNull(AccountStore(app).token)
    }

    @Test
    fun signedInWithoutPlanThenBuys() {
        signIn()
        assertEquals("no subscription yet: the plans", AccountGate.ENDED, ui.value.gate)
        assertTrue(ui.value.status!!.none)
        val collector = ui.value.store.onSale.first { it.plan == "collector" }
        assertEquals("€7.00", ui.value.store.price(collector, "monthly")!!.price)
        store.buy(activity, collector, "yearly")
        waitUntil("bought and reported") { ui.value.gate == AccountGate.OPEN }
        val req = billing.asked.single()
        assertEquals("bought for this account", shop.storeRef, req.accountRef)
        assertNull(req.oldPurchaseToken)
        assertEquals(listOf("oc_collector:yearly"), req.items.map { it.offer.offerToken })
        assertEquals(listOf("oc_collector" to "gp-1"), shop.reports.toList())
        assertEquals("google_play", ui.value.status!!.source)
    }

    /** PSA prices: an add-on for a running plan, bought as its own Play subscription beside it; it unlocks psa_prices. */
    @Test
    fun psaAddonNeedsAPlanAndRunsBesideIt() {
        signIn()
        waitUntil("the add-on") { ui.value.store.addons.isNotEmpty() }
        val psa = ui.value.store.addons.single()
        assertEquals("psa_prices", psa.id)
        assertEquals(listOf("collector", "creator"), psa.plans)
        assertEquals("€2.99", ui.value.store.price(psa, "monthly")!!.price)
        assertEquals("€32.00", ui.value.store.price(psa, "yearly")!!.price)
        // no plan yet: not on sale for this account
        assertEquals("PSA prices is an add-on for a running plan – choose a plan first.", ui.value.store.addonBlocked(psa, ui.value.status))
        store.buyAddon(activity, psa, "monthly")
        waitUntil("refused") { ui.value.store.error != null }
        assertTrue("no purchase sheet", billing.asked.isEmpty())
        store.dismiss()

        billing.next = "gp-1"
        store.buy(activity, ui.value.store.onSale.first { it.plan == "collector" }, "monthly")
        waitUntil("the plan") { ui.value.gate == AccountGate.OPEN && ui.value.store.purchase != null && !ui.value.store.busy }
        assertFalse(ui.value.status!!.has("psa_prices"))
        assertNull(ui.value.store.addonBlocked(psa, ui.value.status))
        billing.next = "gp-psa-yearly"
        store.buyAddon(activity, psa, "yearly")
        waitUntil("PSA prices unlocked") { ui.value.status!!.has("psa_prices") && !ui.value.store.busy }
        val req = billing.asked.last()
        assertNull("its own subscription, not a change of the plan's purchase", req.oldPurchaseToken)
        assertEquals(listOf("oc_psa:yearly"), req.items.map { it.offer.offerToken })
        assertEquals(listOf("oc_collector" to "gp-1", "oc_psa" to "gp-psa-yearly"), shop.reports.toList())
        assertEquals("the plan runs on", "collector", ui.value.status!!.plan)
        assertEquals("yearly", ui.value.status!!.addons["psa_prices"]?.basePlan)
        assertTrue(ui.value.status!!.addons["psa_prices"]!!.running)
        assertEquals("two subscriptions side by side", 2, billing.held.size)
        assertEquals("the plan's purchase stays the one for devices", "gp-1", ui.value.store.purchase?.token)
        assertEquals("PSA prices is already running.", ui.value.store.addonBlocked(psa, ui.value.status))
        Log.i("Store", "PSA add-on: ${ui.value.status!!.addons["psa_prices"]?.stateText} beside ${ui.value.status!!.planName}")
    }

    @Test
    fun extraDevicesAreAddOnsOfTheRunningPurchase() {
        signIn()
        billing.next = "gp-1"
        store.buy(activity, ui.value.store.onSale.first { it.plan == "collector" }, "monthly")
        waitUntil("bought") { ui.value.gate == AccountGate.OPEN && ui.value.store.purchase != null && !ui.value.store.busy }
        assertEquals(0 to 4, ui.value.store.devices(ui.value.status))

        billing.next = "gp-2-d1"
        store.addDevice(activity)
        waitUntil("one device") { ui.value.status!!.devicesInStore == 1 && !ui.value.store.busy }
        var req = billing.asked.last()
        assertEquals("a change of the running purchase", "gp-1", req.oldPurchaseToken)
        assertEquals(listOf("oc_collector", "oc_collector_device_1"), req.items.map { it.offer.productId })
        assertEquals("the plan stays as it is", Replace.KEEP_EXISTING, req.items[0].mode)
        assertEquals("oc_collector", req.items[0].replaces)
        assertNull("the new device is added", req.items[1].mode)
        assertEquals("the same billing period as the plan", "oc_collector_device_1:monthly", req.items[1].offer.offerToken)
        assertEquals("reported with the plan's product id", "oc_collector" to "gp-2-d1", shop.reports.last())
        assertEquals(1 to 4, ui.value.store.devices(ui.value.status))

        billing.next = "gp-3-d2"
        store.addDevice(activity)
        waitUntil("two devices") { ui.value.status!!.devicesInStore == 2 && !ui.value.store.busy }
        req = billing.asked.last()
        assertEquals(listOf("oc_collector", "oc_collector_device_1", "oc_collector_device_2"), req.items.map { it.offer.productId })
        assertEquals(listOf(Replace.KEEP_EXISTING, Replace.KEEP_EXISTING, null), req.items.map { it.mode })

        billing.next = "gp-4-d1"
        store.removeDevice(activity)
        waitUntil("one again") { ui.value.status!!.devicesInStore == 1 && !ui.value.store.busy }
        req = billing.asked.last()
        assertEquals("the last device is left out", listOf("oc_collector", "oc_collector_device_1"), req.items.map { it.offer.productId })
        assertEquals("gp-3-d2", req.oldPurchaseToken)
    }

    @Test
    fun aPlanChangeMovesTheDevices() {
        signIn()
        billing.next = "gp-1-d1"
        store.buy(activity, ui.value.store.onSale.first { it.plan == "collector" }, "monthly")
        waitUntil("bought") { ui.value.gate == AccountGate.OPEN && !ui.value.store.busy }
        // the purchase holds the plan and one device (bought on another day)
        billing.held.clear(); billing.held += StorePurchase("gp-1-d1", listOf("oc_collector", "oc_collector_device_1"), true, shop.storeRef)
        store.refresh()
        waitUntil("the purchase") { ui.value.store.devices(ui.value.status) == (1 to 4) }
        billing.next = "gp-2-d1"
        store.changePlan(activity, ui.value.store.onSale.first { it.plan == "creator" })
        waitUntil("Creator") { ui.value.status!!.plan == "creator" && !ui.value.store.busy }
        val req = billing.asked.last()
        assertEquals(listOf("oc_creator", "oc_creator_device_1"), req.items.map { it.offer.productId })
        assertEquals(listOf("oc_collector", "oc_collector_device_1"), req.items.map { it.replaces })
        assertEquals("a bigger plan at once", listOf(Replace.CHARGE_PRORATED_PRICE, Replace.CHARGE_PRORATED_PRICE), req.items.map { it.mode })
        assertEquals("oc_creator" to "gp-2-d1", shop.reports.last())

        // back to Collector: from the next renewal; Creator sells 2 devices, Collector 4
        billing.held.clear(); billing.held += StorePurchase("gp-2-d1", listOf("oc_creator", "oc_creator_device_1"), true, shop.storeRef)
        store.refresh()
        waitUntil("the purchase") { ui.value.store.devices(ui.value.status) == (1 to 2) }
        billing.next = "gp-3-d1"
        store.changePlan(activity, ui.value.store.onSale.first { it.plan == "collector" })
        waitUntil("reported") { shop.reports.last().second == "gp-3-d1" }
        assertEquals("a smaller plan at the next renewal", Replace.DEFERRED, billing.asked.last().items[0].mode)
    }

    @Test
    fun aReportThatDoesntGetThroughWaits() {
        signIn()
        shop.busyOnce = true
        store.buy(activity, ui.value.store.onSale.first { it.plan == "creator" }, "monthly")
        waitUntil("kept") { ui.value.store.waiting == 1 && !ui.value.store.busy }
        assertEquals("not unlocked yet", AccountGate.ENDED, ui.value.gate)
        store.sendWaiting()
        waitUntil("sent again") { ui.value.store.waiting == 0 && ui.value.gate == AccountGate.OPEN }
        assertEquals(listOf("oc_creator" to "gp-1"), shop.reports.toList())
    }

    @Test
    fun restoreReportsWhatThePhoneHolds() {
        signIn()
        billing.held += StorePurchase("gp-old-d2", listOf("oc_creator", "oc_creator_device_1", "oc_creator_device_2"), true, shop.storeRef)
        store.restore()
        waitUntil("restored") { ui.value.gate == AccountGate.OPEN && ui.value.store.note == "Restored." }
        assertEquals(listOf("oc_creator" to "gp-old-d2"), shop.reports.toList())
        assertEquals(2, ui.value.status!!.devicesInStore)
    }

    @Test
    fun anotherSubscriptionIsSaid() {
        signIn()
        shop.refuse = "already_subscribed"
        store.buy(activity, ui.value.store.onSale.first { it.plan == "collector" }, "monthly")
        waitUntil("said") { ui.value.store.error != null }
        assertEquals("This account already has a subscription from the website. Nothing new was bought.", ui.value.store.error)
        assertEquals(0, ui.value.store.waiting)
    }

    @Test
    fun theInstallsCreatorCodeGoesWithTheSignIn() {
        billing.referrer = "utm_source=website&aff=MISTY"
        AccountStore(app).referrerRead = false
        store.readReferrer()
        waitUntil("the referrer read") { AccountStore(app).pendingAffiliate == "MISTY" }
        signIn()
        assertEquals("sent with the first sign-in", listOf("MISTY"), shop.signInCodes.toList())
        assertNull("once is enough", AccountStore(app).pendingAffiliate)
        waitUntil("the code's discount on the plans") { ui.value.store.affiliate?.code == "MISTY" && ui.value.store.offers.any { it.offerId != null } }
        assertEquals("20 % off for 3 months", ui.value.store.affiliate!!.discountText)
        val creator = ui.value.store.onSale.first { it.plan == "creator" }
        assertEquals("€5.60", ui.value.store.discounted(creator, "monthly")!!.introPrice)
        store.buy(activity, creator, "monthly")
        waitUntil("bought") { ui.value.gate == AccountGate.OPEN }
        assertEquals("bought with the code's Play offer", "oc_creator:monthly:creator-20-3m", billing.asked.last().items.single().offer.offerToken)
    }

    @Test
    fun aTypedCreatorCode() {
        signIn()
        assertNull(ui.value.store.affiliate)
        store.applyCode("nope")
        waitUntil("said") { ui.value.store.error != null && !ui.value.store.busy }
        assertEquals("There's no creator code \"nope\".", ui.value.store.error)
        store.applyCode("misty")
        waitUntil("applied") { ui.value.store.affiliate != null && !ui.value.store.busy }
        assertEquals("Creator code MISTY: 20 % off for 3 months.", ui.value.store.note)
        val collector = ui.value.store.onSale.first { it.plan == "collector" }
        assertEquals("the yearly offer too", "€60.48", ui.value.store.discounted(collector, "yearly")!!.introPrice)
    }

    @Test
    fun referrers() {
        assertEquals("MISTY", Affiliate.fromReferrer("aff=MISTY"))
        assertEquals("MISTY", Affiliate.fromReferrer("utm_source=google-play&aff=MISTY&utm_medium=x"))
        assertEquals("MISTY", Affiliate.fromReferrer("aff%3DMISTY"))
        assertNull(Affiliate.fromReferrer("utm_source=google-play&utm_medium=organic"))
        assertNull(Affiliate.fromReferrer(""))
        assertNull("not a code", Affiliate.fromReferrer("aff=<script>"))
    }

    /** extra devices take the subscription's billing period from the server, also on a phone that didn't buy it */
    @Test
    fun extraDevicesFollowTheServersBillingPeriod() {
        signIn()
        shop.basePlan = "yearly"
        billing.held += StorePurchase("gp-other-phone", listOf("oc_collector"), true, shop.storeRef)
        store.restore()
        waitUntil("restored") { ui.value.gate == AccountGate.OPEN && ui.value.store.purchase != null && !ui.value.store.busy }
        assertEquals("yearly", ui.value.status!!.basePlan)
        billing.next = "gp-2-d1"
        store.addDevice(activity)
        waitUntil("one device") { ui.value.status!!.devicesInStore == 1 && !ui.value.store.busy }
        assertEquals(listOf("oc_collector:yearly", "oc_collector_device_1:yearly"), billing.asked.last().items.map { it.offer.offerToken })
    }

    @Test
    fun deleteTheAccount() {
        signIn()
        account.deleteAccount()
        waitUntil("signed out") { ui.value.gate == AccountGate.CONNECT }
        assertTrue("asked with confirm", shop.deleteBodies.single().optBoolean("confirm"))
        assertNull(AccountStore(app).token)
        assertEquals("The account was deleted.", ui.value.notice)
        assertFalse(ui.value.busy)
        Log.i("Store", "deleted: ${ui.value.notice}")
    }
}
