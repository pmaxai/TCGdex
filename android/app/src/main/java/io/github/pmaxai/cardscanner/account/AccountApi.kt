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

/** Pairing's answer: the device token (secret) and the status. */
class Paired(val token: String, val status: JSONObject)

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

    fun pair(code: String, deviceId: String, deviceName: String?, platform: String = "android"): Paired {
        val o = call("POST", url("pair"), null, JSONObject().put("code", code).put("device_id", deviceId)
            .put("device_name", deviceName?.take(80)).put("platform", platform).put("app_version", appVersion?.take(40)))!!
        return Paired(o.getString("token"), o.optJSONObject("status") ?: JSONObject())
    }

    fun status(token: String): JSONObject = call("GET", url("status"), token)!!

    fun transfer(token: String): TransferCode {
        val o = call("POST", url("transfer"), token, JSONObject())!!
        return TransferCode(o.getString("code"), o.getString("url"), AccountStatus.time(o, "expires_at"))
    }

    /** Disconnects this device (its slot is free again; the token stops working). */
    fun disconnect(token: String) { call("DELETE", url("device"), token) }

    fun s3(token: String): JSONObject = call("GET", url("services", "s3"), token)!!

    // ---------------------------------------------------------------- collection
    fun collectionCard(token: String, cardId: String): JSONArray =
        call("GET", url("collection", "cards", cardId), token)!!.optJSONArray("items") ?: JSONArray()

    fun addToCollection(token: String, body: JSONObject): JSONObject = call("POST", url("collection", "items"), token, body)!!

    fun binders(token: String): JSONArray = call("GET", url("collection", "binders"), token)!!.optJSONArray("binders") ?: JSONArray()

    // ---------------------------------------------------------------- missing cards
    fun wanted(token: String): JSONArray = call("GET", url("wanted"), token)!!.optJSONArray("items") ?: JSONArray()

    fun addWanted(token: String, body: JSONObject): JSONObject = call("POST", url("wanted"), token, body)!!

    fun deleteWanted(token: String, id: Long) { call("DELETE", url("wanted", id.toString()), token) }

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
                if (error == null && (res.code >= 500 || res.code == 404 && json == null)) throw IOException("account server: HTTP ${res.code}")
                throw AccountException(res.code, error ?: "http_${res.code}", json?.let { AccountStatus.str(it, "message") }, json)
            }
            if (res.code == 204 || text.isBlank()) return null
            return json ?: throw IOException("account server: not JSON")
        }
    }
}
