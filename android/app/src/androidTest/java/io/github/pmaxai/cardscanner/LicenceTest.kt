package io.github.pmaxai.cardscanner

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.pmaxai.cardscanner.license.Activated
import io.github.pmaxai.cardscanner.license.LicenceApi
import io.github.pmaxai.cardscanner.license.LicenceCheck
import io.github.pmaxai.cardscanner.license.LicenceException
import io.github.pmaxai.cardscanner.license.LicenceKey
import io.github.pmaxai.cardscanner.license.LicenceStatus
import io.github.pmaxai.cardscanner.license.LicenceStore
import io.github.pmaxai.cardscanner.ui.LicenceState
import io.github.pmaxai.cardscanner.ui.qrBitmap
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.io.IOException

/** The licence: keys, the license service's answers, what the phone keeps, the QR code. */
@RunWith(AndroidJUnit4::class)
class LicenceTest {
    private lateinit var server: MockWebServer
    private val key = "CS-7K2QX-9MZ4D-01ABC-1XYZW"

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun api() = LicenceApi(server.url("/").toString())
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun keysAreReadLikeTheServiceReadsThem() {
        assertEquals(key, LicenceKey.parse(key))
        assertEquals(key, LicenceKey.parse("cs 7k2qx 9mz4d oiabc lxyzw"))           // case, spaces, O→0, I/L→1
        assertEquals(key, LicenceKey.parse("7K2QX9MZ4D01ABC1XYZW"))
        assertEquals(key, LicenceKey.parse("https://pokemonkarten.at/licence?k=$key"))  // a QR code holding more than the key
        assertNull(LicenceKey.parse("CS-7K2QX-9MZ4D-01ABC"))
        assertNull(LicenceKey.parse("CS-7K2QX-9MZ4D-01ABC-1XYZU"))                 // U isn't in the alphabet
        assertNull(LicenceKey.parse("hello"))
        assertEquals("CS-7K2QX-•••••-•••••-1XYZW", LicenceKey.masked(key))
    }

    @Test
    fun activating() {
        server.enqueue(json(200, """{"license_id":"lic_0123456789abcdef","token":"tok","since":"2026-11-08T19:40:00.000Z"}"""))
        val a = api().activate(key, "dev-1", "Google Pixel 8", "1.2.30")
        assertEquals("lic_0123456789abcdef", a.licenceId)
        assertEquals("tok", a.token)
        assertEquals(java.time.Instant.parse("2026-11-08T19:40:00Z").toEpochMilli(), a.since)
        val r = server.takeRequest()
        assertEquals("/v1/activate", r.path)
        val body = JSONObject(r.body.readUtf8())
        assertEquals(key, body.getString("key"))
        assertEquals("dev-1", body.getString("device_id"))
        assertEquals("Google Pixel 8", body.getString("device_name"))

        server.enqueue(json(404, """{"error":"unknown_key","message":"unknown licence key"}"""))
        try { api().activate(key, "dev-1", null, null); fail() } catch (e: LicenceException) {
            assertEquals("unknown_key", e.code)
            assertTrue(e.friendly.contains("doesn't exist"))
        }
        server.enqueue(json(410, """{"error":"revoked","message":"this licence was revoked"}"""))
        try { api().activate(key, "dev-1", null, null); fail() } catch (e: LicenceException) { assertEquals("revoked", e.code) }
        // the service away (proxy error page): like being offline
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        try { api().activate(key, "dev-1", null, null); fail() } catch (e: IOException) { /* offline-like */ }
    }

    @Test
    fun checking() {
        server.enqueue(json(200, """{"status":"active"}"""))
        assertEquals(LicenceCheck.Active, api().check("tok", "1.2.30"))
        server.enqueue(json(200, """{"status":"suspended","since":"2026-11-08T20:00:00.000Z","device_name":"Galaxy S24"}"""))
        val s = api().check("tok", null) as LicenceCheck.Suspended
        assertEquals("Galaxy S24", s.deviceName)
        assertEquals(java.time.Instant.parse("2026-11-08T20:00:00Z").toEpochMilli(), s.since)
        server.enqueue(json(200, """{"status":"suspended","since":"2026-11-08T20:00:00.000Z","device_name":null}"""))
        assertNull((api().check("tok", null) as LicenceCheck.Suspended).deviceName)
        server.enqueue(json(200, """{"status":"revoked"}"""))
        assertEquals(LicenceCheck.Revoked, api().check("tok", null))
        assertEquals("/v1/check", server.takeRequest().path)
    }

    @Test
    fun thePhoneKeepsTheLicenceUntilAnotherDeviceTakesIt() {
        val store = LicenceStore(ApplicationProvider.getApplicationContext())
        val id = store.deviceId
        assertEquals("the device id stays", id, store.deviceId)
        store.activated(key, Activated("lic_0123456789abcdef", "secret-token", 1_000L), now = 2_000L)
        store.load().let {
            assertEquals(LicenceStatus.ACTIVE, it.status)
            assertEquals(key, it.key)
            assertEquals("secret-token", it.token)
            assertEquals(1_000L, it.since)
            assertEquals(2_000L, it.lastCheck)
        }
        assertTrue(LicenceState(required = true, status = LicenceStatus.ACTIVE).let { !it.locked })
        store.suspended(3_000L, "Galaxy S24")
        store.load().let {
            assertEquals(LicenceStatus.SUSPENDED, it.status)
            assertNull("the key is forgotten: only a QR code brings it back", it.key)
            assertNull(it.token)
            assertEquals("Galaxy S24", it.suspendedBy)
        }
        assertTrue(LicenceState(required = true, status = LicenceStatus.SUSPENDED).locked)
        assertFalse("builds without licensing never lock", LicenceState(required = false, status = LicenceStatus.SUSPENDED).locked)
        store.activated(key, Activated("lic_0123456789abcdef", "t2", 4_000L))
        assertEquals(LicenceStatus.ACTIVE, store.load().status)
        assertNull(store.load().suspendedBy)
    }

    @Test
    fun theQrCodeOnScreenReadsBackAsTheKey() {
        val b: Bitmap = Bitmap.createScaledBitmap(qrBitmap(key), 300, 300, false)
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(b.width, b.height, px)))).text
        assertEquals(key, text)
        assertEquals(key, LicenceKey.parse(text))
    }
}
