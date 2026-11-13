package io.github.pmaxai.cardscanner.recording

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the recordings go: a preset fills endpoint, region and addressing; every field can still be changed. */
enum class S3Provider(val label: String) {
    AWS("AWS S3"), EXOSCALE("Exoscale SOS"), OTHER("Other S3-compatible");

    fun endpointFor(region: String): String = when (this) {
        AWS -> "https://s3.$region.amazonaws.com"
        EXOSCALE -> "https://sos-$region.exo.io"
        OTHER -> ""
    }

    val defaultRegion: String get() = when (this) { AWS -> "eu-central-1"; EXOSCALE -> "at-vie-1"; OTHER -> "us-east-1" }
    val pathStyle: Boolean get() = this == OTHER

    companion object {
        val AWS_REGIONS = listOf("eu-central-1", "eu-central-2", "eu-west-1", "eu-west-2", "eu-west-3", "eu-north-1", "eu-south-1",
            "us-east-1", "us-east-2", "us-west-1", "us-west-2", "ca-central-1", "ap-southeast-1", "ap-southeast-2", "ap-northeast-1",
            "ap-south-1", "sa-east-1")
        val EXOSCALE_ZONES = listOf("at-vie-1", "at-vie-2", "de-fra-1", "de-muc-1", "ch-gva-2", "ch-dk-2", "bg-sof-1", "hr-zag-1")
    }
}

data class RecordingSettings(
    val provider: S3Provider = S3Provider.AWS,
    val endpoint: String = S3Provider.AWS.endpointFor("eu-central-1"),
    val region: String = "eu-central-1",
    val bucket: String = "",
    val prefix: String = "",
    val pathStyle: Boolean = false,
    val accessKeyId: String = "",
    val hasSecret: Boolean = false,
    val allowHttp: Boolean = false,
    val uploadAuto: Boolean = true,
    val wifiOnly: Boolean = true,
    val deleteAfterUpload: Boolean = true,
    /** 1080p (else 720p) */
    val hd: Boolean = true,
    val bitrateMbps: Int = 8,
    val recordCamera: Boolean = true,
    val fit169: Boolean = true,
    val mic: Boolean = false,
    val showTimer: Boolean = true,
    /** recordings go to the S3 bucket; off (the default): saved on this phone, in the Gallery. The S3 fields stay either way. */
    val uploadS3: Boolean = false,
) {
    /** everything the upload needs is filled in */
    val uploadReady: Boolean get() = endpoint.isNotBlank() && region.isNotBlank() && bucket.isNotBlank() && accessKeyId.isNotBlank() && hasSecret
    /** ● REC can start: saving on the phone needs nothing, uploading needs the bucket set up */
    val readyToRecord: Boolean get() = !uploadS3 || uploadReady
    val longSide: Int get() = if (hd) 1920 else 1280
}

/**
 * The recording settings on this phone; the S3 secret encrypted with a key of the Android
 * Keystore (its own alias), never shown again, never in a log.
 */
class RecordingPrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("recording", Context.MODE_PRIVATE)

    fun load(): RecordingSettings = RecordingSettings(
        provider = runCatching { S3Provider.valueOf(prefs.getString("provider", "AWS")!!) }.getOrDefault(S3Provider.AWS),
        endpoint = prefs.getString("endpoint", null) ?: S3Provider.AWS.endpointFor("eu-central-1"),
        region = prefs.getString("region", null) ?: "eu-central-1",
        bucket = prefs.getString("bucket", "") ?: "",
        prefix = prefs.getString("prefix", "") ?: "",
        pathStyle = prefs.getBoolean("path_style", false),
        accessKeyId = prefs.getString("access_key", "") ?: "",
        hasSecret = prefs.contains("secret"),
        allowHttp = prefs.getBoolean("allow_http", false),
        uploadAuto = prefs.getBoolean("upload_auto", true),
        wifiOnly = prefs.getBoolean("wifi_only", true),
        deleteAfterUpload = prefs.getBoolean("delete_after", true),
        hd = prefs.getBoolean("hd", true),
        bitrateMbps = prefs.getInt("bitrate", 8),
        recordCamera = prefs.getBoolean("camera", true),
        fit169 = prefs.getBoolean("fit169", true),
        mic = prefs.getBoolean("mic", false),
        showTimer = prefs.getBoolean("timer", true),
        uploadS3 = prefs.getBoolean("upload_s3", false),
    )

    /** Saves the settings; [secret] null keeps the stored one. */
    fun save(s: RecordingSettings, secret: String? = null) {
        val e = prefs.edit().putString("provider", s.provider.name).putString("endpoint", s.endpoint.trim()).putString("region", s.region.trim())
            .putString("bucket", s.bucket.trim()).putString("prefix", S3Config.normalizePrefix(s.prefix)).putBoolean("path_style", s.pathStyle)
            .putString("access_key", s.accessKeyId.trim()).putBoolean("allow_http", s.allowHttp).putBoolean("upload_auto", s.uploadAuto)
            .putBoolean("wifi_only", s.wifiOnly).putBoolean("delete_after", s.deleteAfterUpload).putBoolean("hd", s.hd)
            .putInt("bitrate", s.bitrateMbps).putBoolean("camera", s.recordCamera).putBoolean("fit169", s.fit169)
            .putBoolean("mic", s.mic).putBoolean("timer", s.showTimer).putBoolean("upload_s3", s.uploadS3)
        if (!secret.isNullOrBlank()) e.putString("secret", encrypt(secret.trim()))
        e.apply()
    }

    /** The bucket connection, or null when something is missing. */
    fun s3Config(s: RecordingSettings = load()): S3Config? {
        if (!s.uploadReady) return null
        val secret = prefs.getString("secret", null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: return null
        return S3Config(s.endpoint, s.region, s.bucket, S3Config.normalizePrefix(s.prefix), s.pathStyle, s.accessKeyId, secret, s.allowHttp)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    companion object { private const val ALIAS = "recording-s3-secret" }
}
