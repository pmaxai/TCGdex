package io.github.pmaxai.cardscanner.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The newest build on GitHub (release 'app-latest', file version.json written by the Android build). */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    /** when the APK was built, ISO UTC */
    val built: String?,
    /** TCGdex's price date of its data pack, ISO UTC */
    val prices: String?,
    val apkUrl: String,
)

/**
 * Checks for a newer app (with newer prices), downloads it and hands it to Android's installer.
 * New builds are signed with the same key, so they install over this one and keep its data.
 */
object AppUpdate {
    const val BASE = "https://github.com/pmaxai/TCGdex/releases/download/app-latest/"
    private const val UA = "CardScanner-update-check"

    fun parse(json: String): AppRelease? = runCatching {
        val o = JSONObject(json)
        AppRelease(
            versionCode = o.getInt("versionCode"),
            versionName = o.optString("versionName", o.getInt("versionCode").toString()),
            built = o.optString("built").ifBlank { null },
            prices = o.optString("prices").ifBlank { null },
            apkUrl = BASE + o.optString("apk", "CardScanner.apk").ifBlank { "CardScanner.apk" },
        )
    }.getOrNull()

    /** "5 Nov" from an ISO time (in the phone's time zone); null if it can't be read */
    fun day(iso: String?): String? = iso?.let {
        runCatching {
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.parse(it).atZone(ZoneId.systemDefault()))
        }.getOrNull()
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true      // GitHub sends release files from another host
        c.useCaches = false
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    /** The newest build, or null (offline, GitHub unreachable, nothing published). Blocking: call off the main thread. */
    fun latest(): AppRelease? = runCatching {
        val c = open(BASE + "version.json", 8_000)
        try {
            if (c.responseCode != 200) null else parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Downloads the APK into the cache (progress 0..1). Blocking. */
    fun download(ctx: Context, r: AppRelease, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }         // older downloads
        val out = File(dir, "CardScanner-${r.versionCode}.apk")
        val tmp = File(dir, "download.tmp")
        val c = open(r.apkUrl, 30_000)
        try {
            if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 }
            c.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total != null) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; progress(done.toFloat() / total) }
                        }
                    }
                }
            }
            if (total != null && tmp.length() != total) throw IOException("download incomplete")
        } finally {
            c.disconnect()
        }
        if (!tmp.renameTo(out)) throw IOException("could not save the update")
        return out
    }

    /** Android lets this app hand APKs to the installer (asked once, in Settings). */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Settings page where the user allows it */
    fun allowIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android's installer for the downloaded APK (it asks the user to confirm) */
    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
