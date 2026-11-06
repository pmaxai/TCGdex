package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.shop.Money
import io.github.pmaxai.cardscanner.shop.PendingWrite
import io.github.pmaxai.cardscanner.shop.ShopClient
import io.github.pmaxai.cardscanner.shop.ShopCredentials
import io.github.pmaxai.cardscanner.shop.ShopException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real calls against the staging shop (own database, test bookings never touch real stock).
 * Runs only with the repository secrets POKESHOP_STAGING_KEY_ID / _SECRET; skipped otherwise.
 * Books one copy into test box 998 and takes it out again.
 */
@RunWith(AndroidJUnit4::class)
class ShopStagingTest {
    @Test
    fun stagingRoundTrip() {
        val args = InstrumentationRegistry.getArguments()
        val kid = args.getString("shopKeyId").orEmpty()
        val secret = args.getString("shopSecret").orEmpty()
        assumeTrue("no staging key (repository secrets POKESHOP_STAGING_KEY_ID / _SECRET)", kid.isNotBlank() && secret.isNotBlank())
        val c = ShopClient(ShopCredentials("https://staging.pokemonkarten.at/api/v1", kid, secret))

        val h = c.health()
        assertEquals(kid, h.keyId)
        Log.i(TAG, "health ok, shop time ${h.serverTime}")
        val master = c.masterCards(cardRef = "sv03.5-199")
        Log.i(TAG, "master data: ${master.map { "${it.cardRef} ${it.name} ${it.setName}" }}")
        val before = c.productsOfCard("sv03.5-199")
        Log.i(TAG, "products of sv03.5-199: ${before.map { "${it.sku} ${it.stock.quantity} ${it.status}" }}")

        // one copy in, the same request again (replayed, not booked twice), then out again
        val w = PendingWrite.json("POST", "/stock/intake",
            ShopClient.intakeBody("sv03.5-199", "DE", "NM", "holo", 1, "998", Money.ofEuros(129.0)))
        val first = c.intake(w)
        val again = c.intake(w)
        Log.i(TAG, "intake: created=${first.created} ${first.product.sku} stock ${first.product.stock.quantity} warnings ${first.warnings}; " +
            "again replayed=${again.replayed} stock ${again.product.stock.quantity}")
        assertTrue("same key: replayed", again.replayed)
        assertEquals(first.product.stock.quantity, again.product.stock.quantity)
        val stock = c.stock(PendingWrite.json("POST", "/products/${first.product.sku}/stock",
            JSONObject().put("delta", -1).put("storage_location", "998").put("reason", "scanner app test")))
        assertEquals(first.product.stock.quantity - 1, stock.quantity)
        Log.i(TAG, "undo: stock ${stock.quantity}")

        // too many copies out of a box: a clear error, nothing changed
        try {
            c.stock(PendingWrite.json("POST", "/products/${first.product.sku}/stock",
                JSONObject().put("delta", -1000).put("storage_location", "998").put("reason", "scanner app test")))
            fail("no error")
        } catch (e: ShopException) {
            assertEquals("insufficient_stock", e.code)
            Log.i(TAG, "insufficient stock: ${e.friendly}")
        }
    }

    companion object { const val TAG = "ShopStaging" }
}
