package io.github.pmaxai.cardscanner.shop

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The webshop connection on this phone: host and key id in the app's preferences, the secret
 * encrypted with a key of the Android Keystore (the key itself can't be read out of the phone).
 * Nothing of it is in the APK, in Git or in a log.
 */
class ShopStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("webshop", Context.MODE_PRIVATE)

    val host: String get() = prefs.getString("host", "") ?: ""
    val keyId: String get() = prefs.getString("key_id", "") ?: ""
    val hasSecret: Boolean get() = prefs.contains("secret")

    /** the last storage box used, so the next card goes to the same one (1–999) */
    var lastBox: Int?
        get() = prefs.getInt("last_box", 0).takeIf { it in 1..999 }
        set(v) { prefs.edit().putInt("last_box", v ?: 0).apply() }

    /**
     * The lowest price a new product is offered for (EUR; null: none). A card worth a few cents
     * is still sold for this: the suggested price is never below it.
     */
    var minPrice: Double?
        get() = prefs.getFloat("min_price", 0f).toDouble().takeIf { it > 0 }?.let { Math.round(it * 100) / 100.0 }
        set(v) { prefs.edit().putFloat("min_price", (v?.takeIf { it > 0 } ?: 0.0).toFloat()).apply() }

    /** Webshop Mode was last on Sell (a customer's order) rather than Stock in */
    var selling: Boolean
        get() = prefs.getBoolean("selling", false)
        set(v) { prefs.edit().putBoolean("selling", v).apply() }

    /** the last condition picked (most cards of a batch are in the same condition) */
    var lastCondition: String
        get() = prefs.getString("last_condition", "NM") ?: "NM"
        set(v) { prefs.edit().putString("last_condition", v).apply() }

    /** Saves the connection; [secret] null keeps the stored one. */
    fun save(host: String, keyId: String, secret: String?) {
        val e = prefs.edit().putString("host", host.trim()).putString("key_id", keyId.trim())
        if (secret != null) e.putString("secret", encrypt(secret.trim()))
        e.apply()
    }

    fun clear() {
        prefs.edit().remove("host").remove("key_id").remove("secret").apply()
    }

    /** The connection, or null if something is missing (or the secret can't be decrypted any more). */
    fun credentials(): ShopCredentials? {
        val base = ShopCredentials.baseUrlOf(host) ?: return null
        val id = keyId.takeIf { it.isNotBlank() } ?: return null
        val secret = prefs.getString("secret", null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: return null
        return ShopCredentials(base, id, secret)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
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
        private const val ALIAS = "webshop-secret"
    }
}
