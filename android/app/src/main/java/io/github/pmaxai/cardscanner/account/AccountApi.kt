package io.github.pmaxai.cardscanner.account

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The account server is there but doesn't answer as the API does (down, not deployed, a proxy's page). Counts as offline. */
class ServerUnavailable(val code: Int, message: String) : IOException(message)

/**
 * Pairing's (or a sign-in's) answer: the device token (secret) and the status; [created]: a new
 * account; [affiliateResult]: what became of a creator code sent with it (applied, unknown_code,
 * already_registered, already_customer) and [affiliate] the account's code with its discount.
 */
class Paired(val token: String, val status: JSONObject, val created: Boolean = false,
             val affiliateResult: String? = null, val affiliate: JSONObject? = null)

/** Move to a new phone: the code the new phone scans (as a QR code of [url]). */
data class TransferCode(val code: String, val url: String, val expiresAt: Long?)

/**
 * The app endpoints of the account API (`/api/app/v1`), JSON both ways. An error answer throws
 * [AccountException]; no connection throws an IOException. The token goes only into the
 * Authorization header, never into a log.
 */
class AccountApi(baseUrl: String, private val appVersion: String?, http: OkHttpClient? = null) {
    val base: String = baseUrl.trimEnd('/')
    private val http = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** [affiliateCode]: a creator code that came with the install (sent with the first pairing or sign-in) */
    fun pair(code: String, deviceId: String, deviceName: String?, platform: String = "android", affiliateCode: String? = null): Paired =
        signedIn(call("POST", url("pair"), null, device(deviceId, deviceName, platform, affiliateCode).put("code", code)))

    fun status(token: String): JSONObject = need(call("GET", url("status"), token))

    // ---------------------------------------------------------------- sign-in (store editions)
    /** Continue with Google: the ID token from Credential Manager. Works without a subscription. */
    fun loginGoogle(idToken: String, deviceId: String, deviceName: String?, platform: String = "android", affiliateCode: String? = null): Paired =
        signedIn(call("POST", url("login", "google"), null, device(deviceId, deviceName, platform, affiliateCode).put("id_token", idToken)))

    /** Use another email: a 6-digit code is emailed. Returns the seconds until another may be asked for. */
    fun loginEmail(email: String): Int = need(call("POST", url("login", "email"), null, JSONObject().put("email", email))).optInt("resend_after", 60)

    fun loginEmailVerify(email: String, code: String, deviceId: String, deviceName: String?, platform: String = "android",
                         affiliateCode: String? = null): Paired =
        signedIn(call("POST", url("login", "email", "verify"), null, device(deviceId, deviceName, platform, affiliateCode).put("email", email).put("code", code)))

    private fun device(deviceId: String, deviceName: String?, platform: String, affiliateCode: String?) = JSONObject().put("device_id", deviceId)
        .put("device_name", deviceName?.take(80)).put("platform", platform).put("app_version", appVersion?.take(40))
        .apply { affiliateCode?.let { put("affiliate_code", it) } }

    private fun signedIn(answer: JSONObject?): Paired {
        val o = need(answer)
        return Paired(o.getString("token"), o.optJSONObject("status") ?: JSONObject(), o.optBoolean("created"),
            AccountStatus.str(o, "affiliate_code"), o.optJSONObject("affiliate"))
    }

    // ---------------------------------------------------------------- creator codes (affiliates)
    /** A creator code typed on the paywall: `{ "affiliate": … }` (404 unknown_code, 409 already_registered / already_customer) */
    fun affiliate(token: String, code: String): JSONObject = need(call("POST", url("affiliate"), token, JSONObject().put("code", code)))

    // ---------------------------------------------------------------- buying in Google Play
    /** the plans on sale with their store product ids (`offers`), and the account's creator code (`affiliate`) */
    fun offers(token: String): JSONObject = need(call("GET", url("store", "offers"), token))

    /** reports a Play purchase (the plan's product id); answers the status */
    fun storeGoogle(token: String, productId: String, purchaseToken: String): JSONObject =
        need(call("POST", url("store", "google"), token, JSONObject().put("product_id", productId).put("purchase_token", purchaseToken)))

    /** Deletes the account (asked first in the app): `{ "deleted": true, "manage_url": … }` */
    fun deleteAccount(token: String): JSONObject = call("POST", url("account", "delete"), token, JSONObject().put("confirm", true)) ?: JSONObject()

    /** the newest data pack: version and files with their download addresses (valid 1 hour) */
    fun pack(token: String): JSONObject = need(call("GET", url("pack"), token))

    fun transfer(token: String): TransferCode {
        val o = need(call("POST", url("transfer"), token, JSONObject()))
        return TransferCode(o.getString("code"), o.getString("url"), AccountStatus.time(o, "expires_at"))
    }

    /** Disconnects this device (its slot is free again; the token stops working). */
    fun disconnect(token: String) { call("DELETE", url("device"), token) }

    fun s3(token: String): JSONObject = need(call("GET", url("services", "s3"), token))

    // ---------------------------------------------------------------- collection
    fun collectionCard(token: String, cardId: String): JSONArray =
        need(call("GET", url("collection", "cards", cardId), token)).optJSONArray("items") ?: JSONArray()

    fun addToCollection(token: String, body: JSONObject): JSONObject = need(call("POST", url("collection", "items"), token, body))

    fun binders(token: String): JSONArray = need(call("GET", url("collection", "binders"), token)).optJSONArray("binders") ?: JSONArray()

    // ---------------------------------------------------------------- missing cards
    fun wanted(token: String): JSONArray = need(call("GET", url("wanted"), token)).optJSONArray("items") ?: JSONArray()

    fun addWanted(token: String, body: JSONObject): JSONObject = need(call("POST", url("wanted"), token, body))

    fun deleteWanted(token: String, id: Long) { call("DELETE", url("wanted", id.toString()), token) }

    /** an answer that must have a JSON body */
    private fun need(o: JSONObject?): JSONObject = o ?: throw ServerUnavailable(200, "account server: an empty answer")

    private fun url(vararg segments: String): HttpUrl {
        val b = base.toHttpUrl().newBuilder().addPathSegments("api/app/v1")
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    /** One call; the answer's JSON (null for 204). */
    private fun call(method: String, url: HttpUrl, token: String?, body: JSONObject? = null): JSONObject? {
        val rb = Request.Builder().url(url).header("Accept", "application/json")
        token?.let { rb.header("Authorization", "Bearer $it") }
        appVersion?.let { rb.header("X-App-Version", it) }
        val payload = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        when (method) {
            "GET" -> rb.get()
            "DELETE" -> if (payload != null) rb.delete(payload) else rb.delete()
            else -> rb.method(method, payload ?: "{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!res.isSuccessful) {
                val error = json?.let { AccountStatus.str(it, "error") }
                // a proxy's page instead of the API's answer: no connection to the API
                if (error == null && (res.code >= 500 || res.code == 404 && json == null)) throw ServerUnavailable(res.code, "account server: HTTP ${res.code}")
                throw AccountException(res.code, error ?: "http_${res.code}", json?.let { AccountStatus.str(it, "message") }, json)
            }
            if (res.code == 204 || text.isBlank()) return null
            return json ?: throw ServerUnavailable(res.code, "account server: not JSON")
        }
    }
}
