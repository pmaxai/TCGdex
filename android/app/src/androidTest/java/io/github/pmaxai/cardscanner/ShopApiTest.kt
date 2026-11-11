package io.github.pmaxai.cardscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.shop.ApiSigner
import io.github.pmaxai.cardscanner.shop.Money
import io.github.pmaxai.cardscanner.shop.PendingWrite
import io.github.pmaxai.cardscanner.shop.ServerClock
import io.github.pmaxai.cardscanner.shop.ShopClient
import io.github.pmaxai.cardscanner.shop.ShopCredentials
import io.github.pmaxai.cardscanner.shop.ShopException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import kotlin.math.abs

/** The Pokeshop client: the API doc's signing test vectors, and its behaviour against a simulated shop. */
@RunWith(AndroidJUnit4::class)
class ShopApiTest {
    private lateinit var server: MockWebServer
    private val secret = "sk_test_secret"

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun client(clock: ServerClock = ServerClock()) =
        ShopClient(ShopCredentials(server.url("/api/v1").toString().trimEnd('/'), "kid_test", secret), clock, sleep = {})

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    /** The request is signed as the shop checks it: timestamp, method, path with query, body hash. */
    private fun assertSigned(r: RecordedRequest) {
        assertEquals("kid_test", r.getHeader("X-Key-Id"))
        val ts = r.getHeader("X-Timestamp")!!.toLong()
        val body = r.body.readByteArray()
        assertEquals(ApiSigner.sign(secret, ts, r.method!!, r.path!!, body), r.getHeader("X-Signature"))
    }

    @Test
    fun signerMatchesTheApiDocVectors() {
        val ts = 1793883600L
        assertEquals("v1=074aa943428ea866471942182a5e5d1b9e8b58cf22edc8b080afa045e9d63edd",
            ApiSigner.sign("sk_example", ts, "GET", "/api/v1/products/sv03.5-199-DE-NM-H", ByteArray(0)))
        assertEquals("v1=85d459b5d4eb8f2e8286dfde286374a14e0c5a579212a602be39fab9cc5ae418",
            ApiSigner.sign("sk_example", ts, "GET", "/api/v1/master/cards?q=glurak&lang=de", ByteArray(0)))
        val body = """{"delta":1,"storage_location":"014"}""".toByteArray()
        assertEquals("b6b874fc8ed7e5fe2c0bc3b724f309d42b486438807cd12a9ad84a83fcd1bfcf", ApiSigner.sha256Hex(body))
        assertEquals("v1=977c2cfd09d2cc9b414365a09dec09ff3fce394210e954237b20eccf25f07816",
            ApiSigner.sign("sk_example", ts, "post", "/api/v1/products/sv03.5-199-DE-NM-H/stock", body))
    }

    @Test
    fun hostsBecomeApiBaseUrls() {
        assertEquals("https://staging.pokemonkarten.at/api/v1", ShopCredentials.baseUrlOf("staging.pokemonkarten.at"))
        assertEquals("https://pokemonkarten.at/api/v1", ShopCredentials.baseUrlOf(" https://pokemonkarten.at/ "))
        assertEquals("https://pokemonkarten.at/api/v1", ShopCredentials.baseUrlOf("https://pokemonkarten.at/api/v1"))
        assertEquals("http://10.0.2.2:3000/api/v1", ShopCredentials.baseUrlOf("http://10.0.2.2:3000"))
        assertNull("no plain http to the internet", ShopCredentials.baseUrlOf("http://pokemonkarten.at"))
        assertNull(ShopCredentials.baseUrlOf("  "))
        assertFalse("the secret never shows in logs", ShopCredentials("https://x/api/v1", "kid", "sk_secret").toString().contains("sk_secret"))
    }

    @Test
    fun healthAndMasterCardsAreSigned() {
        server.enqueue(json(200, """{"ok":true,"key_id":"kid_test","server_time":"2026-11-06T01:30:00.000Z"}"""))
        server.enqueue(json(200, """{"items":[{"card_ref":"sv03.5-199","set_code":"sv03.5","set_name":"151","local_id":"199",
            "card_count":165,"name":"Glurak-ex","rarity":"Special Illustration Rare","image_url":null}]}"""))
        val c = client()
        assertEquals("kid_test", c.health().keyId)
        val cards = c.masterCards(q = "glurak")
        assertEquals("Glurak-ex", cards.single().name)
        assertNull(cards.single().imageUrl)                      // JSON null, not "null"
        server.takeRequest().let { assertEquals("/api/v1/health", it.path); assertSigned(it) }
        server.takeRequest().let { assertEquals("/api/v1/master/cards?q=glurak&lang=de", it.path); assertSigned(it) }
    }

    @Test
    fun intakeIsRetriedWithTheSameKeyAndBytes() {
        server.enqueue(MockResponse().setResponseCode(503))                      // the proxy while the shop restarts
        server.enqueue(json(500, """{"error":"internal"}"""))
        server.enqueue(json(201, """{"created":true,"product":${PRODUCT},"warnings":["not_activated_no_image"]}"""))
        val body = ShopClient.intakeBody("sv03.5-199", "DE", "NM", "holo", 2, ShopClient.box(14), Money.ofEuros(129.0), descriptionDe = "  ")
        assertEquals("""{"card_ref":"sv03.5-199","card_language":"DE","condition":"NM","finish":"holo","delta":2,"storage_location":"014","price":{"amount":12900,"currency":"EUR"}}""",
            body.toString())
        val w = PendingWrite.json("POST", "/stock/intake", body)
        val r = client().intake(w)
        assertTrue(r.created)
        assertEquals("sv03.5-199-DE-NM-H", r.product.sku)
        assertEquals(listOf("not_activated_no_image"), r.warnings)
        val sent = (1..3).map { server.takeRequest() }
        sent.forEach { assertEquals(w.idempotencyKey, it.getHeader("Idempotency-Key")) }
        assertEquals(1, sent.map { it.body.readUtf8() }.toSet().size)            // identical bytes every time
    }

    /** A product picture taken with the camera: multipart (role, file), signed over its exact bytes, with an Idempotency-Key. */
    @Test
    fun aPhotoGoesUpAsMultipart() {
        server.enqueue(json(202, """{"id":7,"role":"front","status":"pending","url":null}"""))
        server.enqueue(json(200, PRODUCT.replace("\"status\":\"draft\"", "\"status\":\"active\"")))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0, 13, 10, 4)
        val w = PendingWrite.image("sv03.5-199-DE-NM-H", jpeg)
        client().uploadImage(w)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/products/sv03.5-199-DE-NM-H/images", r.path)
        val type = r.getHeader("Content-Type")!!
        assertTrue(type, type.startsWith("multipart/form-data; boundary=cardscanner-"))
        assertEquals(w.idempotencyKey, r.getHeader("Idempotency-Key"))
        val bytes = r.body.peek().readByteArray()
        assertTrue("the photo's bytes are in the body unchanged", String(bytes, Charsets.ISO_8859_1).contains(String(jpeg, Charsets.ISO_8859_1)))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"role\"\r\n\r\nfront\r\n"))
        assertTrue(text.contains("name=\"file\"; filename=\"card.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
        assertTrue(text.endsWith("--\r\n"))
        assertSigned(r)
        // then the draft goes live
        val (p, warnings) = client().patchProduct(PendingWrite.json("PATCH", "/products/sv03.5-199-DE-NM-H", JSONObject().put("status", "active")))
        assertEquals("sv03.5-199-DE-NM-H", p.sku)
        assertTrue(warnings.isEmpty())
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"status":"active"}""", patch.body.peek().readUtf8())
        assertSigned(patch)
    }

    /** No picture in the shop: the app's picture of the card goes along (front, https only), and a draft can be made live. */
    @Test
    fun aBookingCanBringThePicture() {
        val url = "https://images.pokemontcg.io/swsh11tg/TG11_hires.png"
        val body = ShopClient.intakeBody("swsh11tg-TG11", "EN", "NM", "holo", 1, ShopClient.box(3), imageUrl = url, status = "active")
        assertEquals("""{"card_ref":"swsh11tg-TG11","card_language":"EN","condition":"NM","finish":"holo","delta":1,"storage_location":"003",""" +
            """"images":[{"role":"front","url":"https:\/\/images.pokemontcg.io\/swsh11tg\/TG11_hires.png"}],"status":"active"}""", body.toString())
        assertTrue(!ShopClient.intakeBody("x", "DE", "NM", "normal", 1, "001", imageUrl = "http://example.com/a.png").has("images"))
        // an Asian print: its printed name goes along
        assertEquals("メガニウム", ShopClient.intakeBody("M1S-005", "JA", "NM", "normal", 1, "001", nameEn = "Meganium",
            nameLocal = "メガニウム").getString("name_local"))
    }

    @Test
    fun aWrongClockIsCorrectedFromTheDateHeader() {
        val serverNow = System.currentTimeMillis() + 10 * 60_000L                 // the shop is 10 minutes ahead
        val httpDate = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(Date(serverNow))
        server.enqueue(json(401, """{"error":"timestamp_out_of_range"}""").setHeader("Date", httpDate))
        server.enqueue(json(200, """{"ok":true,"key_id":"kid_test","server_time":null}"""))
        val clock = ServerClock()
        client(clock).health()
        server.takeRequest()
        val second = server.takeRequest()
        assertSigned(second)
        assertTrue("timestamp follows the shop's clock", abs(second.getHeader("X-Timestamp")!!.toLong() - serverNow / 1000) <= 5)
    }

    @Test
    fun errorsBecomeShopExceptions() {
        server.enqueue(json(422, """{"error":"validation","message":"request body is not valid","fields":{"price.amount":"Too small"}}"""))
        try {
            client().intake(PendingWrite.json("POST", "/stock/intake", JSONObject().put("delta", 1)))
            fail("no exception")
        } catch (e: ShopException) {
            assertEquals(422, e.status)
            assertEquals("validation", e.code)
            assertEquals("Too small", e.fields["price.amount"])
            assertTrue(e.friendly, e.friendly.contains("price.amount"))
        }
        assertEquals(1, server.requestCount)                                    // a 4xx is not retried
        server.enqueue(json(403, """{"error":"key_revoked"}"""))
        try { client().health(); fail("no exception") } catch (e: ShopException) {
            assertEquals("key_revoked", e.code); assertTrue(e.friendly.contains("no longer valid"))
        }
    }

    @Test
    fun productsArePagedAndNullsStayNull() {
        server.enqueue(json(200, """{"items":[$PRODUCT],"next_cursor":"MTI4"}"""))
        server.enqueue(json(200, """{"items":[${PRODUCT.replace("sv03.5-199-DE-NM-H", "sv03.5-199-EN-EX-R")
            .replace("\"price\":{\"amount\":12900,\"currency\":\"EUR\"}", "\"price\":null")}],"next_cursor":null}"""))
        val list = client().productsOfCard("sv03.5-199")
        assertEquals(listOf("sv03.5-199-DE-NM-H", "sv03.5-199-EN-EX-R"), list.map { it.sku })
        assertEquals(129.0, list[0].price!!.euros, 1e-9)
        assertNull(list[1].price)
        assertEquals(2, list[0].stock.quantity)
        assertEquals("014", list[0].stock.boxes.single().box)
        assertEquals("https://pokemonkarten.at/media/p/128/731-1200.webp", list[0].imageUrl)
        assertEquals("/api/v1/products?card_ref=sv03.5-199&limit=200", server.takeRequest().path)
        assertEquals("/api/v1/products?card_ref=sv03.5-199&limit=200&cursor=MTI4", server.takeRequest().path)
    }

    @Test
    fun aReplayedWriteIsASuccess() {
        server.enqueue(json(200, """{"quantity":1,"reserved":0,"available":1,"boxes":[{"storage_location":"014","quantity":1,"reserved":0}]}""")
            .setHeader("Idempotent-Replayed", "true"))
        val stock = client().stock(PendingWrite.json("POST", "/products/sv03.5-199-DE-NM-H/stock",
            JSONObject().put("delta", -1).put("storage_location", "014").put("reason", "undone in the scanner app")))
        assertEquals(1, stock.quantity)
        val r = server.takeRequest()
        assertEquals("/api/v1/products/sv03.5-199-DE-NM-H/stock", r.path)
        assertSigned(r)
    }

    @Test
    fun aSaleIsOneBatchAndEachLineGetsItsOwnResult() {
        server.enqueue(json(200, """{"results":[
            {"status":200,"body":{"quantity":1,"reserved":0,"available":1,"boxes":[{"storage_location":"014","quantity":1,"reserved":0}]}},
            {"status":409,"body":{"error":"insufficient_stock","message":"box 003 holds 1"}}]}"""))
        val body = ShopClient.stockBatchBody(listOf(Triple("sv03.5-199-DE-NM-H", "014", -1), Triple("sv1-1-DE-NM-N", "003", -2)),
            "sold in person (scanner app)")
        val results = client().batch(PendingWrite.json("POST", "/batch", body))
        assertEquals(2, results.size)
        assertTrue(results[0].ok)
        assertNull(results[0].error)
        assertEquals(1, results[0].body!!.getInt("quantity"))
        assertFalse(results[1].ok)
        assertEquals("insufficient_stock", results[1].error!!.code)

        val r = server.takeRequest()
        val sent = r.body.clone().readUtf8()                                   // (the signature check reads the body)
        assertEquals("/api/v1/batch", r.path)
        assertSigned(r)
        assertTrue(r.getHeader("Idempotency-Key")!!.isNotBlank())
        val ops = JSONObject(sent).getJSONArray("operations")
        assertEquals(2, ops.length())
        ops.getJSONObject(1).let { op ->
            assertEquals("stock", op.getString("op"))
            assertEquals("sv1-1-DE-NM-N", op.getString("sku"))
            val b = op.getJSONObject("body")
            assertEquals(-2, b.getInt("delta"))
            assertEquals("003", b.getString("storage_location"))      // the box as the shop writes it
            assertEquals("sold in person (scanner app)", b.getString("reason"))
        }
    }

    @Test
    fun anOrderSurvivesARestart() {
        val lines = listOf(io.github.pmaxai.cardscanner.ui.ShopOrderLine("sv03.5-199-DE-NM-H", "Glurak-ex", "DE · NM · Holo · 199/165", "014", 2, 129.0),
            io.github.pmaxai.cardscanner.ui.ShopOrderLine("sv1-1-DE-NM-N", "Pikachu", "DE · NM · Normal · 1/198", "003", 1, null))
        val back = lines.map { io.github.pmaxai.cardscanner.ui.ShopOrderLine.from(JSONObject(it.toJson().toString())) }
        assertEquals(lines, back)
        assertEquals(258.0, back[0].total!!, 1e-9)
        assertNull(back[1].total)                                              // no price: not in the total
    }

    @Test
    fun theMinimumPriceIsAFloorForTheSuggestion() {
        assertEquals(0.50, io.github.pmaxai.cardscanner.ui.suggestedPrice(0.08, 0.50)!!, 1e-9)    // a few cents -> the minimum
        assertEquals(3.20, io.github.pmaxai.cardscanner.ui.suggestedPrice(3.20, 0.50)!!, 1e-9)    // worth more: its market value
        assertEquals(0.08, io.github.pmaxai.cardscanner.ui.suggestedPrice(0.08, null)!!, 1e-9)    // no minimum set
        assertNull(io.github.pmaxai.cardscanner.ui.suggestedPrice(null, 0.50))                    // no market value: no guess
    }

    companion object {
        const val PRODUCT = """{"sku":"sv03.5-199-DE-NM-H","external_id":null,"card_ref":"sv03.5-199","set_code":"sv03.5",
            "set_name_de":"151","set_name_en":"151","card_number":"199/165","name_de":"Glurak-ex","name_en":"Charizard ex",
            "card_language":"DE","condition":"NM","finish":"holo","grading":null,"release_year":2023,"rarity":"Special Illustration Rare",
            "price":{"amount":12900,"currency":"EUR"},"high_value":true,"status":"active","url":"https://pokemonkarten.at/karten/x",
            "stock":{"quantity":2,"reserved":1,"available":1,"boxes":[{"storage_location":"014","quantity":2,"reserved":1}]},
            "images":[{"id":731,"role":"front","status":"ready","url":"https://pokemonkarten.at/media/p/128/731-1200.webp"},
                      {"id":732,"role":"back","status":"pending","url":null}],"updated_at":"2026-11-06T01:30:00.000Z"}"""
    }
}
