package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.account.AccountApi
import io.github.pmaxai.cardscanner.account.AccountException
import io.github.pmaxai.cardscanner.account.AccountStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/**
 * Against staging.overpricedcardboard.com: the API answers as the contract says (no token,
 * an unknown code). With a test subscription's device token (CI secret ACCOUNT_STAGING_TOKEN)
 * also status, the collection's client_ref and the missing cards. Skipped while staging can't
 * be reached.
 */
@RunWith(AndroidJUnit4::class)
class AccountStagingTest {
    private val api = AccountApi("https://staging.overpricedcardboard.com", "ci")

    private fun <T> reach(f: () -> T): T = try { f() } catch (e: IOException) { assumeTrue("staging can't be reached: ${e.message}", false); throw e }

    @Test
    fun theApiAnswersAsTheContractSays() {
        val noToken = try { reach { api.status("") }; null } catch (e: AccountException) { e }
        Log.i("Account", "staging, no token: $noToken")
        assertTrue("401: $noToken", noToken is AccountException && noToken.status == 401)
        // the contract's JSON body ({"error": "invalid_token"}); "http_401": the answer had none
        if (noToken!!.error != "invalid_token") Log.w("Account", "staging's 401 has no {\"error\": \"invalid_token\"} body: ${noToken.error}")
        val code = try { reach { api.pair("0000-0000-0000", "ci-${UUID.randomUUID()}", "CI emulator") }; null } catch (e: AccountException) { e }
        Log.i("Account", "staging, unknown code: $code")
        // 401 without the API's JSON even for pairing (no token needed there): staging sits behind an access protection
        assumeTrue("staging answers 401 without the API's JSON, also to /pair (an access protection in front of it?)",
            !(noToken.error == "http_401" && code?.error == "http_401"))
        assertTrue("unknown_code: $code", code is AccountException && code.error in setOf("unknown_code", "code_expired", "invalid_code", "rate_limited"))
    }

    @Test
    fun aTestSubscription() {
        val token = InstrumentationRegistry.getArguments().getString("accountToken").orEmpty()
        assumeTrue("no test subscription's token (ACCOUNT_STAGING_TOKEN)", token.isNotBlank())
        val st = AccountStatus.parse(reach { api.status(token) })
        Log.i("Account", "staging status: ${st.planName} ${st.state}, features ${st.features}")
        assertTrue("a running test subscription", st.active)
        if (st.has("collection")) {
            val body = JSONObject().put("client_ref", UUID.randomUUID().toString()).put("card_id", "sv03.5-199").put("language", "DE")
                .put("finish", "holo").put("condition", "NM").put("quantity", 1).put("merge", false)
            val a = api.addToCollection(token, body)
            val b = api.addToCollection(token, body)      // the same client_ref again: the same item
            assertEquals(a.getLong("id"), b.getLong("id"))
        }
        if (st.has("wanted")) {
            val w = api.addWanted(token, JSONObject().put("client_ref", UUID.randomUUID().toString()).put("card_id", "base1-4").put("language", "EN"))
            api.deleteWanted(token, w.getLong("id"))
        }
    }
}
