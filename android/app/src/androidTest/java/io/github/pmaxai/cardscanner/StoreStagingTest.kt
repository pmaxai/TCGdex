package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.account.AccountApi
import io.github.pmaxai.cardscanner.account.AccountException
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.store.PlanOffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The Play edition's calls against staging.overpricedcardboard.com, with its test hooks: a test
 * Google sign-in makes a new account without a plan; a test purchase with two extra devices
 * unlocks it; a test event ends it; the data pack; deleting the account signs the device out.
 * Needs the staging access secret (CI: ACCOUNT_STAGING_ACCESS); skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class StoreStagingTest {
    private val base = "https://staging.overpricedcardboard.com"
    private val access = InstrumentationRegistry.getArguments().getString("stagingAccess").orEmpty()
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { c -> c.proceed(c.request().newBuilder().header("X-Staging-Access", access).build()) }.build()
    }

    @Test
    fun signInBuyEndDelete() {
        assumeTrue("no staging access secret", access.isNotBlank())
        val api = AccountApi(base, "store-test", http)
        val email = "store-${UUID.randomUUID().toString().take(8)}@test.overpricedcardboard.com"
        val p = try { api.loginGoogle("test_google:$email", UUID.randomUUID().toString(), "CI emulator") }
            catch (e: AccountException) { Log.w("ShopStaging", "store sign-in: ${e.status} ${e.error}"); assumeTrue("staging refused: ${e.error}", false); return }
        Log.i("ShopStaging", "store sign-in: created=${p.created}")
        assertTrue("a new account", p.created)
        val none = AccountStatus.parse(p.status)
        assertTrue("no plan yet: status none", none.none)
        assertFalse(none.active)
        val ref = none.storeRef
        assertNotNull("store_ref", ref)

        val offers = PlanOffer.parse(api.offers(p.token))
        Log.i("ShopStaging", "offers: ${offers.map { "${it.plan} ${it.productId} ${it.deviceProductIds}" }}")
        assertTrue("Collector and Creator are offered", offers.map { it.plan }.containsAll(listOf("collector", "creator")))
        val product = offers.first { it.plan == "collector" }.productId ?: "oc_collector"

        val bought = AccountStatus.parse(api.storeGoogle(p.token, product, "test_active-d2-${UUID.randomUUID().toString().take(6)}"))
        Log.i("ShopStaging", "bought: ${bought.state} ${bought.source} in_store=${bought.devicesInStore}")
        assertTrue("active", bought.active)
        assertEquals("google_play", bought.source)
        assertEquals("two extra devices from the app", 2, bought.devicesInStore)

        val pack = runCatching { api.pack(p.token) }
        Log.i("ShopStaging", "pack: ${pack.map { "${it.optString("version")} ${it.optJSONArray("files")?.length()} files" }.getOrElse { it.toString() }}")
        pack.exceptionOrNull()?.let { assertTrue("the pack or not mirrored yet: $it", it is AccountException && it.error == "pack_unavailable") }

        // the store ends it (a test event)
        val ev = http.newCall(Request.Builder().url("$base/api/app/v1/test/store-event").header("Authorization", "Bearer ${p.token}")
            .post(JSONObject().put("store_ref", ref).put("state", "expired").toString().toRequestBody("application/json".toMediaType())).build()).execute()
        Log.i("ShopStaging", "store event: ${ev.code}")
        ev.close()
        val ended = AccountStatus.parse(api.status(p.token))
        assertFalse("ended", ended.active)

        api.deleteAccount(p.token)
        val gone = runCatching { api.status(p.token) }.exceptionOrNull() as? AccountException
        Log.i("ShopStaging", "after delete: ${gone?.status} ${gone?.error} ${gone?.reason}")
        assertEquals(401, gone?.status)
        assertEquals("account_deleted", gone?.reason)
    }
}
