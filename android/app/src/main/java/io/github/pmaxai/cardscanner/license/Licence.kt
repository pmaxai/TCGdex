package io.github.pmaxai.cardscanner.license

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The app's licence: one-off, active on one device at a time (the license service next to the
 * shop, apps/license in the pokeshop repository). Activating needs the internet once; after
 * that the app checks now and then and keeps working offline. It locks only when the service
 * says another device took the licence over (or it was revoked) – then this device forgets the
 * key and needs a QR code again.
 */
object LicenceKey {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val IN_TEXT = Regex("CS[-\\s]?([0-9A-Z]{5})[-\\s]?([0-9A-Z]{5})[-\\s]?([0-9A-Z]{5})[-\\s]?([0-9A-Z]{5})", RegexOption.IGNORE_CASE)

    /** "CS-XXXXX-XXXXX-XXXXX-XXXXX" from a scanned QR code or a typed key, or null. Same rules as the service. */
    fun parse(text: String): String? {
        // a QR code may hold more than the key (e.g. a link): take the key out of it
        IN_TEXT.find(text)?.let { m -> body(m.groupValues.drop(1).joinToString(""))?.let { return format(it) } }
        var s = text.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }.replace('O', '0').replace('I', '1').replace('L', '1')
        if (s.length == 22 && s.startsWith("CS")) s = s.drop(2)
        return body(s)?.let(::format)
    }

    private fun body(s: String): String? {
        val b = s.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')
        return b.takeIf { it.length == 20 && it.all { c -> c in ALPHABET } }
    }

    fun format(body: String): String = "CS-" + body.chunked(5).joinToString("-")

    /** "CS-7K2QX-•••••-•••••-1XYZW" */
    fun masked(key: String): String = key.split("-").let { p ->
        if (p.size != 5) key else listOf(p[0], p[1], "•••••", "•••••", p[4]).joinToString("-")
    }
}

/** What the service says about this device's activation. */
sealed class LicenceCheck {
    data object Active : LicenceCheck()
    data class Suspended(val since: Long?, val deviceName: String?) : LicenceCheck()
    data object Revoked : LicenceCheck()
}

data class Activated(val licenceId: String, val token: String, val since: Long)

class LicenceException(val status: Int, val code: String, message: String?) : Exception(message ?: code) {
    val friendly: String
        get() = when (code) {
            "unknown_key" -> "This licence key doesn't exist. Check it, or scan the QR code from your licence e-mail."
            "invalid_key" -> "That isn't a licence key (CS-XXXXX-XXXXX-XXXXX-XXXXX)."
            "revoked" -> "This licence was revoked. Please contact the shop."
            "rate_limited" -> "Too many tries – please wait a while."
            else -> message ?: "The licence service answered $status ($code)."
        }
}

class LicenceApi(baseUrl: String, http: OkHttpClient? = null) {
    private val base = baseUrl.trimEnd('/')
    private val http = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** Activates [key] on this device (any other device loses it). IOException when offline. */
    fun activate(key: String, deviceId: String, deviceName: String?, appVersion: String?): Activated {
        val o = post("/v1/activate", JSONObject().put("key", key).put("device_id", deviceId)
            .put("device_name", deviceName).put("app_version", appVersion))
        return Activated(o.getString("license_id"), o.getString("token"), time(o.optString("since")) ?: System.currentTimeMillis())
    }

    fun check(token: String, appVersion: String?): LicenceCheck {
        val o = post("/v1/check", JSONObject().put("token", token).put("app_version", appVersion))
        return when (o.optString("status")) {
            "active" -> LicenceCheck.Active
            "suspended" -> LicenceCheck.Suspended(time(o.optString("since")), o.optString("device_name").takeIf { it.isNotBlank() && it != "null" })
            "revoked" -> LicenceCheck.Revoked
            else -> throw LicenceException(200, "unexpected", "unexpected answer: ${o.optString("status")}")
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(base + path)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!res.isSuccessful) {
                if (res.code >= 500 && json == null) throw IOException("licence service: HTTP ${res.code}")
                throw LicenceException(res.code, json?.optString("error")?.takeIf { it.isNotBlank() } ?: "http_${res.code}",
                    json?.optString("message")?.takeIf { it.isNotBlank() })
            }
            return json ?: throw IOException("licence service: no JSON")
        }
    }

    private fun time(iso: String?): Long? = iso?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

enum class LicenceStatus { NONE, ACTIVE, SUSPENDED, REVOKED }

data class SavedLicence(
    val status: LicenceStatus,
    val key: String?,
    val token: String?,
    val licenceId: String?,
    val since: Long?,
    val lastCheck: Long?,
    val suspendedBy: String?,
    val suspendedSince: Long?,
)

/**
 * The licence on this phone: key and token encrypted with a key of the Android Keystore,
 * the rest in the app's preferences. The device id is random, made once (no hardware ids).
 */
class LicenceStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("licence", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    fun load(): SavedLicence = SavedLicence(
        status = runCatching { LicenceStatus.valueOf(prefs.getString("status", "NONE")!!) }.getOrDefault(LicenceStatus.NONE),
        key = prefs.getString("key", null)?.let { runCatching { decrypt(it) }.getOrNull() },
        token = prefs.getString("token", null)?.let { runCatching { decrypt(it) }.getOrNull() },
        licenceId = prefs.getString("licence_id", null),
        since = prefs.getLong("since", 0).takeIf { it > 0 },
        lastCheck = prefs.getLong("last_check", 0).takeIf { it > 0 },
        suspendedBy = prefs.getString("suspended_by", null),
        suspendedSince = prefs.getLong("suspended_since", 0).takeIf { it > 0 },
    )

    fun activated(key: String, a: Activated, now: Long = System.currentTimeMillis()) {
        prefs.edit().putString("status", LicenceStatus.ACTIVE.name).putString("key", encrypt(key)).putString("token", encrypt(a.token))
            .putString("licence_id", a.licenceId).putLong("since", a.since).putLong("last_check", now)
            .remove("suspended_by").remove("suspended_since").commit()
    }

    fun checked(now: Long = System.currentTimeMillis()) { prefs.edit().putLong("last_check", now).apply() }

    /** Another device has it now: this one forgets key and token (a QR code brings it back). */
    fun suspended(since: Long?, by: String?) {
        prefs.edit().putString("status", LicenceStatus.SUSPENDED.name).remove("key").remove("token")
            .putString("suspended_by", by).putLong("suspended_since", since ?: System.currentTimeMillis()).commit()
    }

    fun revoked() {
        prefs.edit().putString("status", LicenceStatus.REVOKED.name).remove("key").remove("token").commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "licence-secret"

        /** "Google Pixel 8": shown to the device this one replaces. */
        fun deviceName(): String {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
        }
    }
}
