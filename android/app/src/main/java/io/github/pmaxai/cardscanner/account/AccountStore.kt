package io.github.pmaxai.cardscanner.account

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A change to the collection or the missing cards that waits to be sent (offline, or the server
 * busy). Sent in order; dropped only on a 400 (with why) or when the user deletes it.
 */
data class PendingOp(
    /** collection_add, wanted_add, wanted_delete */
    val kind: String,
    /** the same on every try, so a repeat creates nothing twice */
    val clientRef: String,
    /** the account it belongs to (another account never gets it) */
    val accountId: String?,
    val cardId: String,
    /** what is sent (adds) */
    val body: JSONObject? = null,
    /** the missing card to remove (wanted_delete) */
    val wantedId: Long? = null,
    /** "Glurak-ex · 151 199/165", for "waiting to sync" */
    val label: String,
    val added: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("client_ref", clientRef).put("account_id", accountId)
        .put("card_id", cardId).put("body", body).put("wanted_id", wantedId).put("label", label).put("added", added)

    companion object {
        fun of(o: JSONObject) = PendingOp(o.getString("kind"), o.getString("client_ref"),
            if (o.isNull("account_id")) null else o.optString("account_id"), o.getString("card_id"), o.optJSONObject("body"),
            if (o.isNull("wanted_id")) null else o.optLong("wanted_id"), o.optString("label"), o.optLong("added"))
    }
}

/**
 * The account connection on this phone: the server, the device token (encrypted with a key of
 * the Android Keystore; never in a log, CSV or export), the last status answer (offline it
 * counts until valid_until), the missing cards and what waits to be sent. The device id is the
 * random one the app made before (it survives updates).
 */
class AccountStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val queueFile = File(ctx.filesDir, "account-queue.json")
    private val wantedFile = File(ctx.filesDir, "account-wanted.json")

    init { dropLicence() }

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    /** the server this phone is connected to (from the QR code's host) */
    val base: String? get() = prefs.getString("base", null)
    val token: String? get() = prefs.getString("token", null)?.let { runCatching { decrypt(it) }.getOrNull() }
    val connected: Boolean get() = token != null

    /** the last status answer and when it came */
    fun status(): AccountStatus? = prefs.getString("status", null)?.let { runCatching { AccountStatus.parse(JSONObject(it)) }.getOrNull() }
    val lastCheck: Long? get() = prefs.getLong("last_check", 0).takeIf { it > 0 }

    /** why this phone was disconnected (shown on the connect screen until it is connected again) */
    val notice: String? get() = prefs.getString("notice", null)

    fun connected(base: String, token: String, status: JSONObject, now: Long = System.currentTimeMillis()) {
        prefs.edit().putString("base", base).putString("token", encrypt(token)).putString("status", status.toString())
            .putLong("last_check", now).remove("notice").commit()
    }

    fun saveStatus(status: JSONObject, now: Long = System.currentTimeMillis()) {
        prefs.edit().putString("status", status.toString()).putLong("last_check", now).apply()
    }

    /** Not connected any more: token and saved answer go (the queue stays for this account if it comes back). */
    fun forget(notice: String?) {
        val e = prefs.edit().remove("token").remove("status").remove("last_check").remove("s3_updated_at")
        if (notice != null) e.putString("notice", notice) else e.remove("notice")
        e.commit()
        wantedFile.delete()
    }

    /** a creator code from the install (Play install referrer), sent with the first pairing or sign-in */
    var pendingAffiliate: String?
        get() = prefs.getString("affiliate_code", null)
        set(v) { prefs.edit().apply { if (v == null) remove("affiliate_code") else putString("affiliate_code", v) }.commit() }

    /** the install referrer has been read (once, at the first start) */
    var referrerRead: Boolean
        get() = prefs.getBoolean("referrer_read", false)
        set(v) { prefs.edit().putBoolean("referrer_read", v).apply() }

    /** updated_at of the S3 settings the shop set up (fetched again when it changes) */
    var s3UpdatedAt: String?
        get() = prefs.getString("s3_updated_at", null)
        set(v) { prefs.edit().putString("s3_updated_at", v).apply() }

    // ---------------------------------------------------------------- queue and caches
    @Synchronized fun queue(): List<PendingOp> = runCatching {
        val a = JSONArray(queueFile.readText()); (0 until a.length()).map { PendingOp.of(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized fun saveQueue(ops: List<PendingOp>) {
        val tmp = File(queueFile.path + ".tmp")
        tmp.writeText(JSONArray(ops.map { it.toJson() }).toString())
        tmp.renameTo(queueFile)
    }

    fun wanted(): JSONArray? = runCatching { JSONArray(wantedFile.readText()) }.getOrNull()
    fun saveWanted(items: JSONArray) { runCatching { wantedFile.writeText(items.toString()) } }

    /** The licence of earlier versions is gone: its keys and token are dropped, its device id kept. */
    private fun dropLicence() {
        val old = ctx.getSharedPreferences("licence", Context.MODE_PRIVATE)
        if (old.all.isEmpty()) return
        old.getString("device_id", null)?.let { id -> if (!prefs.contains("device_id")) prefs.edit().putString("device_id", id).commit() }
        old.edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("licence-secret") }
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
        private const val ALIAS = "account-token"

        /** "Google Pixel 8": shown in the portal with the device. */
        fun deviceName(): String {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
        }
    }
}

/** The account's S3 storage, for the upload worker (it runs without the app's screen). */
object ManagedStorage {
    /** Uploads failed with an access error: fetch the S3 settings again (the shop may have changed the key). True when read. */
    fun refresh(ctx: Context): Boolean {
        val store = AccountStore(ctx)
        val token = store.token ?: return false
        val base = store.base ?: return false
        val o = runCatching { AccountApi(base, io.github.pmaxai.cardscanner.BuildConfig.VERSION_NAME).s3(token) }.getOrNull() ?: return false
        io.github.pmaxai.cardscanner.recording.RecordingPrefs(ctx).saveManaged(o)
        store.s3UpdatedAt = o.optString("updated_at")
        return true
    }
}
