package io.github.pmaxai.cardscanner

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.account.AccountApi
import io.github.pmaxai.cardscanner.account.AccountException
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.account.AccountStore
import io.github.pmaxai.cardscanner.account.PairCode
import io.github.pmaxai.cardscanner.ui.AccountCoordinator
import io.github.pmaxai.cardscanner.shop.ShopTerms
import io.github.pmaxai.cardscanner.ui.AccountUi
import io.github.pmaxai.cardscanner.ui.CopyDetails
import io.github.pmaxai.cardscanner.ui.PublicLink
import io.github.pmaxai.cardscanner.ui.Free
import io.github.pmaxai.cardscanner.ui.localCardCount
import io.github.pmaxai.cardscanner.ui.localLines
import io.github.pmaxai.cardscanner.ui.nextRevealAt
import io.github.pmaxai.cardscanner.ui.revealed
import io.github.pmaxai.cardscanner.ui.revealsLeft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The account in the app (app-account-api.md, "Tests the app should have"), against a stand-in
 * of the shop's server: QR codes, pairing → status → features, inactive, unpaired, offline until
 * valid_until, Move to a new phone, the collection's client_ref after a timeout, missing cards.
 */
@RunWith(AndroidJUnit4::class)
class AccountTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var server: MockWebServer
    private lateinit var fake: FakeShop
    private lateinit var scope: CoroutineScope
    private val ui = MutableStateFlow(AccountUi())
    private val messages = ArrayList<String>()
    private lateinit var account: AccountCoordinator

    /** the shop's server as the contract describes it, in memory */
    class FakeShop : Dispatcher() {
        val codes = ConcurrentHashMap<String, String>()          // pairing code → "pair" or "transfer:<token>"
        val tokens = ConcurrentHashMap<String, String>()         // token → device name
        val gone = ConcurrentHashMap<String, JSONObject>()       // token → the unpaired answer
        @Volatile var active = true
        @Volatile var plan = "creator"
        @Volatile var offline = false
        @Volatile var notTheApi = false
        /** client_refs whose first answer comes too late (the app's call times out; the item is made anyway) */
        val slowOnce = ConcurrentHashMap.newKeySet<String>()
        val items = ConcurrentHashMap<String, JSONObject>()      // client_ref → item
        val wanted = ConcurrentHashMap<Long, JSONObject>()
        @Volatile var nextId = 100L
        /** the collection page: a card's name, set, number and value per copy as the shop knows them */
        val cards = ConcurrentHashMap<String, JSONObject>()
        /** copies per page of GET /collection/items (whatever limit the app asks for) */
        @Volatile var pageSize = 500
        /** the cursors GET /collection/items was asked with ("" for the first page) */
        val pages: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        /** sharing from the app: the app's public link, other public links made on the website, how many links were made */
        @Volatile var publicShare: JSONObject? = null
        @Volatile var otherPublic = 0
        @Volatile var sharesMade = 0
        @Volatile var lastShareBody: JSONObject? = null
        /** the website without sharing from the app yet: /collection/share is an unknown page (404, no JSON) */
        @Volatile var shareMissing = false
        @Volatile var statusCalls = 0

        fun status(): JSONObject = TestAccount.statusJson(plan = plan, active = active, state = if (active) "active" else "ended",
            features = if (plan == "creator") TestAccount.ALL else TestAccount.ALL - setOf("youtube", "recording", "s3_upload", "pipeline", "webshop"))

        private fun json(code: Int, o: Any) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(o.toString())
        private fun err(code: Int, error: String, extra: JSONObject = JSONObject()) = json(code, extra.put("error", error).put("message", error))

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            // the server is there but not the API (not deployed, a proxy's page)
            if (notTheApi) return MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<html>Not found</html>")
            val path = request.requestUrl!!.encodedPath.removePrefix("/api/app/v1")
            val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            if (request.method == "POST" && path == "/pair") {
                val code = body.optString("code")
                val what = codes.remove(code) ?: return err(404, "unknown_code")
                if (!active && !what.startsWith("transfer:")) return err(402, "subscription_required")
                val token = "tok_" + (nextId++) + "_" + "x".repeat(30)
                tokens[token] = body.optString("device_name")
                if (what.startsWith("transfer:")) {
                    val old = what.removePrefix("transfer:")
                    tokens.remove(old)
                    gone[old] = JSONObject().put("reason", "transferred").put("since", java.time.Instant.now().toString())
                        .put("device_name", body.optString("device_name"))
                }
                return json(201, JSONObject().put("token", token).put("device", JSONObject().put("id", "dev_1").put("name", body.optString("device_name")))
                    .put("status", status()))
            }
            val token = request.getHeader("Authorization")?.removePrefix("Bearer ")
            gone[token]?.let { return err(401, "unpaired", JSONObject(it.toString())) }
            if (token == null || !tokens.containsKey(token)) return err(401, "invalid_token")
            return when {
                request.method == "GET" && path == "/status" -> { statusCalls++; json(200, status()) }
                request.method == "POST" && path == "/transfer" -> {
                    val code = "Q2M8-4KD1-0ZXA"; codes[code] = "transfer:$token"
                    json(201, JSONObject().put("code", code).put("url", "http://localhost/pair/$code")
                        .put("expires_at", java.time.Instant.now().plusSeconds(600).toString()))
                }
                request.method == "DELETE" && path == "/device" -> {
                    tokens.remove(token); gone[token] = JSONObject().put("reason", "disconnected"); MockResponse().setResponseCode(204)
                }
                request.method == "POST" && path == "/collection/items" -> {
                    if (body.optString("card_id") == "nope-1") return err(400, "invalid_request", JSONObject().put("fields", JSONObject().put("card_id", "unknown")))
                    val ref = body.optString("client_ref")
                    val known = items[ref]
                    val item = known ?: JSONObject(body.toString()).put("id", nextId++).put("source", "app").also { o ->
                        val c = cards[o.optString("card_id")]
                        o.put("name", c?.optString("name") ?: o.optString("card_id")).put("set_name", c?.optString("set_name") ?: "")
                            .put("number", c?.optString("number") ?: "").put("value_cents", c?.opt("value_cents") ?: JSONObject.NULL)
                            .put("updated_at", java.time.Instant.ofEpochSecond(1_790_000_000L + o.getLong("id")).toString())
                        items[ref] = o
                    }
                    val r = json(if (known != null) 200 else 201, item)
                    if (slowOnce.remove(ref)) r.setHeadersDelay(3, TimeUnit.SECONDS) else r
                }
                path == "/collection/share" && shareMissing ->
                    MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<html>Not found</html>")
                request.method == "GET" && path == "/collection/share" ->
                    json(200, JSONObject().put("public", publicShare ?: JSONObject.NULL).put("other_public", otherPublic))
                request.method == "POST" && path == "/collection/share" -> synchronized(this) {
                    lastShareBody = body
                    val existing = publicShare
                    val share = existing ?: JSONObject().put("id", nextId++).put("url", "https://overpricedcardboard.com/c/Ab${nextId}xQ7kLm2Zp9Rt4Vw")
                        .put("show_prices", body.optBoolean("show_prices", true)).put("views", 0).put("created_at", "2026-11-21T12:00:00.000Z")
                        .also { publicShare = it; sharesMade++ }
                    json(if (existing != null) 200 else 201, JSONObject().put("public", share).put("other_public", otherPublic))
                }
                request.method == "DELETE" && path == "/collection/share" -> { publicShare = null; otherPublic = 0; MockResponse().setResponseCode(204) }
                path.startsWith("/collection/items/") && (request.method == "PATCH" || request.method == "DELETE") -> {
                    val id = path.removePrefix("/collection/items/").toLong()
                    val entry = items.entries.firstOrNull { it.value.optLong("id") == id }
                    when {
                        entry == null -> if (request.method == "DELETE") MockResponse().setResponseCode(204) else err(404, "not_found")
                        request.method == "DELETE" -> { items.remove(entry.key); MockResponse().setResponseCode(204) }
                        else -> {
                            val item = entry.value
                            for (k in listOf("quantity", "language", "finish", "condition", "grading", "purchase_price_cents", "note", "binder_id")) {
                                if (body.has(k)) item.put(k, body.get(k))
                            }
                            item.put("updated_at", "2026-11-21T13:00:00.000Z")
                            json(200, item)
                        }
                    }
                }
                request.method == "GET" && path == "/collection/items" -> {
                    val cursor = request.requestUrl!!.queryParameter("cursor")
                    pages += cursor ?: ""
                    val all = items.values.sortedBy { it.getLong("id") }
                    val from = cursor?.toInt() ?: 0
                    val n = minOf(pageSize, request.requestUrl!!.queryParameter("limit")?.toInt() ?: 100)
                    val next = (from + n).takeIf { it < all.size }
                    json(200, JSONObject().put("items", JSONArray(all.drop(from).take(n))).put("next_cursor", next?.toString() ?: JSONObject.NULL)
                        .put("server_time", java.time.Instant.now().toString()))
                }
                request.method == "GET" && path.startsWith("/collection/cards/") -> {
                    val id = path.removePrefix("/collection/cards/")
                    json(200, JSONObject().put("items", JSONArray(items.values.filter { it.optString("card_id") == id })))
                }
                request.method == "GET" && path == "/wanted" -> json(200, JSONObject().put("items", JSONArray(wanted.values.toList())))
                request.method == "POST" && path == "/wanted" -> {
                    val w = JSONObject(body.toString()).put("id", nextId++).put("state", "searching"); wanted[w.getLong("id")] = w; json(201, w)
                }
                request.method == "DELETE" && path.startsWith("/wanted/") -> { wanted.remove(path.removePrefix("/wanted/").toLong()); MockResponse().setResponseCode(204) }
                else -> err(404, "not_found")
            }
        }
    }

    @Before fun setUp() {
        fake = FakeShop()
        server = MockWebServer().apply { dispatcher = fake; start() }
        AccountStore(app).forget(null)
        AccountStore(app).saveQueue(emptyList())
        AccountStore(app).saveLocalCollection(emptyList())
        AccountStore(app).saveFreeReveals(emptyList())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        account = AccountCoordinator(app, scope, { f -> ui.update(f) }, { ui.value }, { m -> synchronized(messages) { messages += m } }, {}, {})
        // a short read timeout, so "the answer comes too late" doesn't take long
        account.http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        account.start()
    }

    @After fun tearDown() {
        scope.cancel()
        AccountStore(app).forget(null)
        AccountStore(app).saveQueue(emptyList())
        AccountStore(app).saveLocalCollection(emptyList())
        AccountStore(app).saveFreeReveals(emptyList())
        server.shutdown()
    }

    private fun waitUntil(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { check(System.currentTimeMillis() < end) { "timed out: $what (state ${ui.value.gate}, error ${ui.value.error})" }; Thread.sleep(50) }
    }

    private fun base() = "http://${server.hostName}:${server.port}"

    /** connects this phone with a QR code of the stand-in server */
    private fun pair() {
        fake.codes["K7Q2-9MZ4-D01A"] = "pair"
        account.pair("${base()}/pair/K7Q2-9MZ4-D01A", scanned = true)
        waitUntil("connected") { ui.value.gate == AccountGate.OPEN }
    }

    @Test
    fun qrCodesAndTypedCodes() {
        val d = "https://overpricedcardboard.com"
        assertEquals(PairCode.Target(d, "K7Q2-9MZ4-D01A"), PairCode.parse("https://overpricedcardboard.com/pair/K7Q2-9MZ4-D01A", d, debug = false))
        assertEquals("https://staging.overpricedcardboard.com", PairCode.parse("https://staging.overpricedcardboard.com/pair/K7Q2-9MZ4-D01A", d, false)?.base)
        // typed: case, spaces, dashes don't matter; O = 0, I and L = 1
        assertEquals(PairCode.Target(d, "K7Q2-9MZ4-D01A"), PairCode.parse(" k7q2 9mz4-dO1a ", d, false))
        assertEquals("K7Q2-9MZ4-D11A", PairCode.parse("K7Q29MZ4DIlA", d, false)?.code)
        // not ours: other hosts, http, a look-alike host, another path, a user part, a bad code
        listOf("https://evil.example/pair/K7Q2-9MZ4-D01A", "http://overpricedcardboard.com/pair/K7Q2-9MZ4-D01A",
            "https://overpricedcardboard.com.evil.example/pair/K7Q2-9MZ4-D01A", "https://overpricedcardboard.com/account/K7Q2-9MZ4-D01A",
            "https://anna@overpricedcardboard.com/pair/K7Q2-9MZ4-D01A", "https://overpricedcardboard.com/pair/K7Q2-9MZ4",
            "https://overpricedcardboard.com/pair/K7Q2-9MZ4-DU1A", "K7Q2-9MZ4", "CS-7K2QX-9MZ4D-01ABC-1XYZW").forEach {
            assertNull("rejects $it", PairCode.parse(it, d, debug = false))
        }
        // the shop on the computer: only debug builds, with its port
        assertEquals("http://10.0.2.2:3000", PairCode.parse("http://10.0.2.2:3000/pair/K7Q2-9MZ4-D01A", d, debug = true)?.base)
        assertNull(PairCode.parse("http://10.0.2.2:3000/pair/K7Q2-9MZ4-D01A", d, debug = false))
        assertNull(PairCode.parse("http://localhost:3000/pair/K7Q2-9MZ4-D01A", d, debug = false))
    }

    /** Pair → status → features; Collector without YouTube; the token stored encrypted, the status saved. */
    @Test
    fun pairingUnlocksTheFeatures() {
        pair()
        val st = ui.value.status!!
        assertEquals("anna@example.com", st.email)
        assertTrue(ui.value.justConnected)
        listOf("scanner", "trading", "youtube", "recording", "collection", "wanted").forEach { assertTrue("$it unlocked", ui.value.has(it)) }
        waitUntil("the status asked right after pairing") { fake.statusCalls >= 1 }
        assertTrue("token kept on the phone", AccountStore(app).connected)
        assertFalse("never in plain text", app.getSharedPreferences("account", 0).all.values.any { it.toString().contains("tok_") })
        // the plan changes to Collector: YouTube goes, the scanner stays
        fake.plan = "collector"
        account.check(force = true)
        waitUntil("Collector") { ui.value.status?.plan == "collector" }
        assertFalse(ui.value.has("youtube")); assertFalse(ui.value.has("recording")); assertTrue(ui.value.has("scanner"))
    }

    /** The subscription ends: every mode locked, the token kept (renewing unlocks without a new QR code). */
    @Test
    fun inactiveLocksEverything() {
        pair()
        fake.active = false
        account.check(force = true, manual = true)
        waitUntil("ended") { ui.value.gate == AccountGate.ENDED }
        // ended: the free version – the Scanner only
        assertTrue(ui.value.isFree)
        assertTrue(ui.value.has("scanner"))
        assertFalse(ui.value.has("trading"))
        assertFalse(ui.value.has("collection"))
        assertTrue("the token stays", AccountStore(app).connected)
        fake.active = true
        account.check(force = true, manual = true)
        waitUntil("open again") { ui.value.gate == AccountGate.OPEN }
    }

    /** Removed in the portal: the token and the saved answer are forgotten, the connect screen says why. */
    @Test
    fun unpairedForgetsTheToken() {
        pair()
        val token = AccountStore(app).token!!
        fake.tokens.remove(token)
        fake.gone[token] = JSONObject().put("reason", "removed")
        account.check(force = true)
        waitUntil("back to connect") { ui.value.gate == AccountGate.CONNECT }
        assertNull(AccountStore(app).token)
        assertNull(AccountStore(app).status())
        assertTrue(ui.value.notice!!, ui.value.notice!!.startsWith("This phone was removed in your account"))
    }

    /** Offline after a successful check: it works until valid_until, then asks to connect once. */
    @Test
    fun offlineUntilValidUntil() {
        pair()
        fake.offline = true
        account.check(force = true, manual = true)
        // done once it says it can't reach the server (a check still running from the pairing goes first)
        waitUntil("checked offline") { !ui.value.busy && ui.value.error != null }
        assertEquals("offline: the saved answer counts", AccountGate.OPEN, ui.value.gate)
        val st = ui.value.status!!
        assertEquals(AccountGate.OPEN, AccountGate.of(true, st, st.validUntil - 1))
        assertEquals(AccountGate.CHECK_ONLINE, AccountGate.of(true, st, st.validUntil + 1))
        // the saved answer has run out (valid_until passed) and the server can't be reached
        Thread.sleep(300)
        AccountStore(app).saveStatus(TestAccount.statusJson(validDays = -1))
        account.check(force = true, manual = true)
        waitUntil("check online once") { ui.value.gate == AccountGate.CHECK_ONLINE }
        assertTrue(ui.value.locked)
        fake.offline = false
        account.check(force = true, manual = true)
        waitUntil("open again online") { ui.value.gate == AccountGate.OPEN }
    }

    /** Move to a new phone: the new phone pairs with the transfer code; this one learns it on its next check. */
    @Test
    fun movedToANewPhone() {
        pair()
        account.startTransfer()
        waitUntil("the transfer code") { ui.value.transfer?.code != null }
        val code = ui.value.transfer!!.code!!.code
        // the new phone scans it
        AccountApi(base(), "test").pair(code, "new-phone-id", "Pixel 9")
        waitUntil("this phone learns it", ms = 15_000) { ui.value.gate == AccountGate.CONNECT }
        assertTrue(ui.value.notice!!, ui.value.notice!!.startsWith("Moved to Pixel 9"))
        assertNull(AccountStore(app).token)
    }

    /**
     * + Collection retried after a timeout creates one item: offline first ("waiting to sync"),
     * then the answer comes too late (the server made the item), then the repeat with the same
     * client_ref finds it.
     */
    @Test
    fun aTimeoutKeepsTheAddQueued() {
        pair()
        fake.offline = true
        account.addToCollection("sv03.5-199", "DE", "holo", "NM", label = "Glurak-ex")
        waitUntil("waiting to sync") { ui.value.pending.size == 1 }
        val ref = ui.value.pending.first().clientRef
        fake.slowOnce += ref
        fake.offline = false
        account.sync()
        // the first try times out (1 s read timeout, 3 s answer) but the server made the item
        waitUntil("made on the server", ms = 8_000) { fake.items.containsKey(ref) }
        Thread.sleep(1500)
        assertEquals("still queued after the timeout", 1, AccountStore(app).queue().size)
        account.sync()
        waitUntil("sent") { AccountStore(app).queue().isEmpty() }
        assertEquals("one item", 1, fake.items.size)
        waitUntil("In collection: 1") { ui.value.collection["sv03.5-199"]?.sumOf { it.quantity } == 1 }
        Log.i("Account", "collection after a retried add: ${fake.items.values}")
    }

    /** A 400 (unknown card) drops the change and says why; a 5xx or no connection never does. */
    @Test
    fun aBadRequestIsDroppedWithWhy() {
        pair()
        account.addToCollection("nope-1", "EN", "normal", "NM", label = "Nope")
        waitUntil("dropped") { AccountStore(app).queue().isEmpty() && synchronized(messages) { messages.isNotEmpty() } }
        assertTrue(messages.toString(), synchronized(messages) { messages.any { it.contains("card_id: unknown") } })
    }

    /** ☆ Missing: on the list, filled; a second tap takes it off again. */
    @Test
    fun missingCardsOnAndOff() {
        pair()
        account.toggleWanted("base1-4", "EN", null, "Charizard · Base Set 4/102")
        waitUntil("on the list") { fake.wanted.size == 1 && ui.value.wanted.any { it.cardId == "base1-4" && !it.pending } }
        assertNotNull(ui.value.wanted.first().id)
        account.toggleWanted("base1-4", "EN", "holo", "Charizard · Base Set 4/102")
        waitUntil("off the list") { fake.wanted.isEmpty() && ui.value.wanted.isEmpty() }
    }

    /** The collection page: every page of the collection, newest first, with names and values. */
    @Test
    fun theCollectionLoadsEveryPageNewestFirst() {
        fake.cards["sv08-059"] = JSONObject().put("name", "Pikachu-ex").put("set_name", "Surging Sparks").put("number", "059/191").put("value_cents", 1890)
        fake.cards["base1-4"] = JSONObject().put("name", "Charizard").put("set_name", "Base Set").put("number", "4/102").put("value_cents", 41200)
        pair()
        account.addToCollection("base1-4", "EN", "holo", "EX", label = "Charizard")
        account.addToCollection("sv08-059", "DE", "normal", "NM", quantity = 2, label = "Pikachu-ex")
        account.addToCollection("sv08-059", "EN", "normal", "NM", label = "Pikachu-ex")
        waitUntil("3 copies sent") { AccountStore(app).queue().isEmpty() && ui.value.collectionEdits == 3 }
        fake.pageSize = 2
        account.loadItems()
        waitUntil("loaded") { ui.value.items != null && !ui.value.itemsLoading }
        val items = ui.value.items!!
        Log.i("Account", "collection: ${items.map { "${it.quantity} × ${it.name} ${it.language}" }}")
        assertEquals("both pages", 3, items.size)
        assertEquals("newest first", "Pikachu-ex", items.first().name)
        assertEquals("EN", items.first().language)
        assertEquals("Charizard", items.last().name)
        assertEquals(4, items.sumOf { it.quantity })
        assertEquals(1890L * 2 + 41200, items.mapNotNull { it.valueCents }.sum())
        assertEquals("two pages asked for", listOf("", "2"), fake.pages.toList())
        assertTrue(items.first().searchText.contains("surging"))
        assertEquals("the page knows it's current", ui.value.collectionEdits, ui.value.itemsAt)
    }

    /** Found it on the missing cards: into the collection (the missing card's language, the card's finish), off the list. */
    @Test
    fun aMissingCardFoundElsewhereGoesIntoTheCollection() {
        pair()
        account.toggleWanted("base1-4", "EN", null, "Charizard")
        waitUntil("on the missing cards") { AccountStore(app).queue().isEmpty() && ui.value.wanted.firstOrNull()?.id != null }
        val w = ui.value.wanted.first()
        account.foundElsewhere(w, "GD", 1, 25000, defaultLanguage = "DE", defaultFinish = "holo", label = "Charizard")
        waitUntil("in the collection, off the list") {
            AccountStore(app).queue().isEmpty() && ui.value.wanted.isEmpty() && ui.value.collectionEdits == 1 && fake.wanted.isEmpty()
        }
        val copy = fake.items.values.single()
        Log.i("Account", "found elsewhere: $copy")
        assertEquals("the missing card's language", "EN", copy.optString("language"))
        assertEquals("no finish on the missing card: the card's own", "holo", copy.optString("finish"))
        assertEquals("GD", copy.optString("condition"))
        assertEquals(25000, copy.optInt("purchase_price_cents"))
    }

    /** Remove on the missing cards takes off that entry only, not another of the same card. */
    @Test
    fun removeTakesOffThatEntryOnly() {
        pair()
        account.toggleWanted("base1-4", "EN", null, "Charizard")
        account.toggleWanted("base1-4", "DE", null, "Glurak")
        waitUntil("both on the list") { AccountStore(app).queue().isEmpty() && ui.value.wanted.size == 2 && ui.value.wanted.all { it.id != null } }
        val de = ui.value.wanted.single { it.language == "DE" }
        account.removeWanted(de, "Glurak")
        waitUntil("the German one off") { AccountStore(app).queue().isEmpty() && fake.wanted.size == 1 && ui.value.wanted.size == 1 }
        assertEquals("EN", ui.value.wanted.single().language)
        assertEquals("EN", fake.wanted.values.single().optString("language"))
    }

    /** The free version: 10 prices in any 60 minutes; a card revealed stays revealed for that hour. */
    @Test
    fun tenPricesInAnySixtyMinutes() {
        assertTrue("not connected: the free version", ui.value.isFree)
        val t0 = 1_800_000_000_000L
        var t = t0
        account.now = { t }
        for (i in 0 until 10) { assertTrue("reveal ${i + 1}", account.reveal("card-$i")); t += 60_000 }
        assertEquals(0, ui.value.revealsLeft(t))
        assertFalse("the 11th within the hour", account.reveal("card-10"))
        assertFalse(ui.value.revealed("card-10", t))
        assertTrue("a card revealed this hour stays revealed", account.reveal("card-3"))
        assertEquals("the next one when the first is an hour old", t0 + Free.HOUR, ui.value.nextRevealAt(t))
        assertEquals("kept on the phone", 10, AccountStore(app).freeReveals().size)
        Log.i("Account", "free: 10 revealed, next at ${ui.value.nextRevealAt(t)}")
        // rolling: an hour after the first, one is free again (and the first card is hidden again)
        t = t0 + Free.HOUR + 1_000
        assertEquals(1, ui.value.revealsLeft(t))
        assertFalse(ui.value.revealed("card-0", t))
        assertTrue(account.reveal("card-10"))
        assertEquals(0, ui.value.revealsLeft(t))
    }

    /** The free version's collection on the phone: 50 different cards, copies don't count. */
    @Test
    fun theLocalCollectionHoldsFiftyDifferentCards() {
        for (i in 0 until 50) assertTrue(account.addLocally("card-$i", "EN", "normal", "NM", label = "Card $i"))
        assertEquals(50, ui.value.localCardCount)
        assertFalse("a 51st card", account.addLocally("card-50", "EN", "normal", "NM", label = "Card 50"))
        assertTrue("another copy of one in it", account.addLocally("card-7", "EN", "normal", "NM", label = "Card 7"))
        assertTrue(account.addLocally("card-7", "DE", "holo", "EX", 2, 450, label = "Card 7"))
        assertEquals("copies don't count", 50, ui.value.localCardCount)
        assertEquals("NM merged into one line; the German one on its own", listOf(2, 2), ui.value.localLines("card-7").map { it.quantity }.sorted())
        assertEquals("newest first", "card-7", ui.value.localItems.first().cardId)
        account.removeLocally(ui.value.localItems.first { it.cardId == "card-0" }.id)
        assertEquals(49, ui.value.localCardCount)
        assertTrue("room again", account.addLocally("card-50", "EN", "normal", "NM", label = "Card 50"))
        assertEquals("kept on the phone", ui.value.localItems, AccountStore(app).localCollection())
    }

    /** With a plan that has a collection, the phone's cards move into the account's; signed out, the app is the free version again. */
    @Test
    fun theLocalCardsMoveIntoTheAccountWithAPlan() {
        account.addLocally("base1-4", "EN", "holo", "GD", purchaseCents = 25000, note = "from the flea market", label = "Charizard")
        account.addLocally("sv08-059", "DE", "normal", "NM", quantity = 2, label = "Pikachu-ex")
        // graded on the phone: the grading goes along
        ui.value.localItems.first { it.cardId == "sv08-059" }.let { p ->
            account.updateLocally(p.id, CopyDetails.of(p).copy(graded = true, company = "PSA", grade = "10"))
        }
        assertTrue(ui.value.isFree)
        pair()
        waitUntil("moved") { fake.items.size == 2 && ui.value.localItems.isEmpty() && AccountStore(app).queue().isEmpty() }
        Log.i("Account", "moved: ${fake.items.values.map { "${it.optInt("quantity")} × ${it.optString("card_id")} ${it.optString("language")} ${it.optString("condition")}" }}")
        val charizard = fake.items.values.single { it.optString("card_id") == "base1-4" }
        assertEquals("holo", charizard.optString("finish"))
        assertEquals("GD", charizard.optString("condition"))
        assertEquals(25000, charizard.optInt("purchase_price_cents"))
        assertEquals("from the flea market", charizard.optString("note"))
        assertEquals(2, fake.items.values.single { it.optString("card_id") == "sv08-059" }.optInt("quantity"))
        assertEquals("10", fake.items.values.single { it.optString("card_id") == "sv08-059" }.optJSONObject("grading")?.optString("grade"))
        assertTrue(messages.toString(), synchronized(messages) { messages.contains("The 2 cards from this phone are now in your account's collection.") })
        assertFalse(ui.value.isFree)
        assertTrue("nothing left on the phone", AccountStore(app).localCollection().isEmpty())
        account.disconnect()
        waitUntil("signed out") { ui.value.gate == AccountGate.CONNECT && !ui.value.busy }
        assertTrue("signed out: the free version again", ui.value.isFree)
    }

    /** Edit sends only what changed (null clears); Delete removes the copy. */
    @Test
    fun aCopyIsEditedAndDeleted() {
        fake.cards["base1-4"] = JSONObject().put("name", "Charizard").put("set_name", "Base Set").put("number", "4/102").put("value_cents", 41200)
        pair()
        account.addToCollection("base1-4", "EN", "holo", "NM", label = "Charizard")
        waitUntil("sent") { AccountStore(app).queue().isEmpty() && ui.value.collectionEdits == 1 }
        account.loadItems()
        waitUntil("loaded") { ui.value.items?.size == 1 && !ui.value.itemsLoading }
        val item = ui.value.items!!.single()
        val d = CopyDetails.of(item).copy(quantity = 3, condition = "EX", language = "DE", graded = true, company = "PSA", grade = "9",
            purchaseCents = 25000, note = "  binder page 3 ")
        val changes = d.changes(CopyDetails.of(item))
        Log.i("Account", "changes: $changes")
        assertEquals("only what changed", setOf("quantity", "condition", "language", "grading", "purchase_price_cents", "note"),
            changes.keys().asSequence().toSet())
        assertNull(runBlocking { account.updateItem(item, changes) })
        val edited = ui.value.items!!.single()
        assertEquals(3, edited.quantity)
        assertEquals("EX", edited.condition)
        assertEquals("DE", edited.language)
        assertEquals("PSA 9", edited.graded)
        assertEquals(25000L, edited.purchaseCents)
        assertEquals("trimmed", "binder page 3", edited.note)
        assertEquals("unchanged", "holo", edited.finish)
        assertEquals("EX", fake.items.values.single().optString("condition"))
        // cleared again: not graded, no price, no note → null
        val clear = CopyDetails.of(edited).copy(graded = false, purchaseCents = null, note = "").changes(CopyDetails.of(edited))
        assertEquals(3, clear.length())
        assertTrue(clear.isNull("grading") && clear.isNull("purchase_price_cents") && clear.isNull("note"))
        assertNull(runBlocking { account.updateItem(edited, clear) })
        assertNull(ui.value.items!!.single().graded)
        assertNull(ui.value.items!!.single().purchaseCents)
        assertNull(runBlocking { account.deleteItem(ui.value.items!!.single()) })
        assertTrue(ui.value.items!!.isEmpty())
        assertTrue("gone on the server", fake.items.isEmpty())
    }

    /** Share makes one public link (with prices), the same one again; Make private switches every public link off. */
    @Test
    fun theCollectionIsSharedAndMadePrivateAgain() {
        pair()
        assertNull(runBlocking { account.loadShare() })
        assertNull("private to start", ui.value.publicLink)
        assertNull(runBlocking { account.makePublic() })
        val link = ui.value.publicLink!!
        assertTrue(link.url, link.url.contains("/c/"))
        assertNull(runBlocking { account.makePublic() })
        assertEquals("the same link again", link, ui.value.publicLink)
        assertEquals("one link made", 1, fake.sharesMade)
        assertTrue("with prices", fake.lastShareBody?.optBoolean("show_prices") == true)
        // a public link of one binder, made on the website
        fake.otherPublic = 1
        assertNull(runBlocking { account.loadShare() })
        assertEquals(1, ui.value.otherPublicLinks)
        assertNull(runBlocking { account.makePrivate() })
        assertNull(ui.value.publicLink)
        assertEquals(0, ui.value.otherPublicLinks)
        assertNull("switched off on the server", fake.publicShare)
        Log.i("Account", "shared ${link.url}, then private")
    }

    /** Before the website has sharing from the app: the app says so. */
    @Test
    fun sharingBeforeTheWebsiteHasIt() {
        pair()
        fake.shareMissing = true
        assertEquals(PublicLink.NOT_YET, runBlocking { account.loadShare() })
        assertEquals(PublicLink.NOT_YET, runBlocking { account.makePublic() })
    }

    /** The free version: a copy on the phone is edited in place. */
    @Test
    fun aCopyOnThePhoneIsEdited() {
        account.addLocally("base1-4", "EN", "holo", "NM", label = "Charizard")
        val item = ui.value.localItems.single()
        account.updateLocally(item.id, CopyDetails.of(item).copy(quantity = 2, condition = "LP", language = "DE", graded = true, company = "CGC",
            grade = "8.5", purchaseCents = 9900, note = "a gift "))
        val e = ui.value.localItems.single()
        assertEquals(item.id, e.id)
        assertEquals(2, e.quantity)
        assertEquals("LP", e.condition)
        assertEquals("DE", e.language)
        assertEquals("CGC", e.gradingCompany)
        assertEquals("8.5", e.gradingGrade)
        assertEquals(9900L, e.purchaseCents)
        assertEquals("a gift", e.note)
        assertEquals("kept on the phone", ui.value.localItems, AccountStore(app).localCollection())
    }

    /** The shop's card languages back to the data pack's. */
    @Test
    fun shopLanguagesMapBackToThePack() {
        assertEquals("de", ShopTerms.appLang("DE"))
        assertEquals("zh-tw", ShopTerms.appLang("ZH-HANT"))
        assertEquals("en", ShopTerms.appLang(null))
        assertEquals("ja", ShopTerms.appLang(ShopTerms.languageOf("ja")))
    }

    /** Disconnect this phone: the slot is free, the app asks for a QR code again. */
    @Test
    fun disconnect() {
        pair()
        account.disconnect()
        waitUntil("disconnected") { ui.value.gate == AccountGate.CONNECT }
        assertNull(AccountStore(app).token)
        assertTrue(fake.tokens.isEmpty())
    }

    /** The server answers, but not as the API (HTML 404): like offline – the saved answer counts, the message says so. */
    @Test
    fun aServerWithoutTheApiCountsAsOffline() {
        pair()
        fake.notTheApi = true
        account.check(force = true, manual = true)
        waitUntil("checked") { !ui.value.busy && ui.value.error != null }
        assertEquals(AccountGate.OPEN, ui.value.gate)
        assertTrue(ui.value.error!!, ui.value.error!!.contains("HTTP 404"))
        assertTrue("the token stays", AccountStore(app).connected)
    }

    /** Pairing errors in words: an unknown code, a full subscription. */
    @Test
    fun pairingErrors() {
        account.pair("${base()}/pair/AAAA-BBBB-CCCC", scanned = false)
        waitUntil("refused") { ui.value.error != null && !ui.value.busy }
        assertTrue(ui.value.error!!, ui.value.error!!.startsWith("This code doesn't exist"))
        val e = AccountException(409, "device_limit", "All 2 devices…", JSONObject().put("error", "device_limit").put("seats", 2))
        assertTrue(e.friendly, e.friendly.startsWith("All 2 devices of your subscription are in use"))
        assertEquals("Creator", AccountStatus.parse(TestAccount.statusJson()).planName)
    }
}
