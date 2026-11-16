package io.github.pmaxai.cardscanner

import android.content.Context
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.account.AccountStore
import io.github.pmaxai.cardscanner.ui.AccountUi
import org.json.JSONObject
import java.time.Instant

/** A pretend account for the tests: a status answer as the server gives it, connected or as screen state. */
object TestAccount {
    val ALL = listOf("scanner", "trading", "youtube", "recording", "s3_upload", "pipeline", "webshop", "collection", "wanted")

    fun statusJson(plan: String = "creator", features: Collection<String> = ALL, active: Boolean = true, state: String = "active",
                   validDays: Long = 30, now: Long = System.currentTimeMillis()): JSONObject {
        val f = JSONObject(); ALL.forEach { f.put(it, active && it in features) }
        val iso = { t: Long -> Instant.ofEpochMilli(t).toString() }
        return JSONObject()
            .put("status", if (active) "active" else "inactive")
            .put("server_time", iso(now))
            .put("valid_until", iso(if (active) now + validDays * 86_400_000L else now))
            .put("account", JSONObject().put("id", "acc_test0001").put("email", "anna@example.com").put("name", "Anna"))
            .put("device", JSONObject().put("id", "dev_test0001").put("name", "Google Pixel 8").put("paired_at", iso(now - 86_400_000L)))
            .put("subscription", JSONObject().put("plan", if (active) plan else JSONObject.NULL).put("state", state)
                .put("renews_at", iso(now + 29 * 86_400_000L)).put("ends_at", JSONObject.NULL)
                .put("devices", JSONObject().put("included", 1).put("extra", 1).put("in_use", 2)))
            .put("features", f)
            .put("webshop", if ("webshop" in features) JSONObject().put("host", "shop.example.com") else JSONObject.NULL)
            .put("services", JSONObject().put("s3", JSONObject().put("enabled", "s3_upload" in features))
                .put("pipeline", JSONObject().put("enabled", "pipeline" in features)))
            .put("portal_url", "https://overpricedcardboard.com/account")
    }

    fun ui(features: Collection<String> = ALL, plan: String = "creator") =
        AccountUi(required = true, gate = AccountGate.OPEN, status = AccountStatus.parse(statusJson(plan, features)),
            lastCheck = System.currentTimeMillis() - 600_000L)

    /**
     * This phone connected to the pretend account. Its server is unreachable (nothing listens
     * there), so the app keeps the saved answer like offline, until valid_until.
     */
    fun connect(ctx: Context, features: Collection<String> = ALL) =
        AccountStore(ctx).connected("http://127.0.0.1:9", "test-token", statusJson(features = features))

    fun disconnect(ctx: Context) = AccountStore(ctx).forget(null)
}
