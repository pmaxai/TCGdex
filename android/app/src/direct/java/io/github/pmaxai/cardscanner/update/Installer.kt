package io.github.pmaxai.cardscanner.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * The direct edition (APK from GitHub): downloads the new APK and hands it to Android's installer.
 * New builds are signed with the same key, so they install over this one and keep its data.
 */
object Installer {
    /** Downloads the APK into the cache (progress 0..1). Blocking. */
    fun download(ctx: Context, r: AppRelease, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }         // older downloads
        val out = File(dir, "CardScanner-${r.versionCode}.apk")
        val tmp = File(dir, "download.tmp")
        val c = AppUpdate.open(r.apkUrl, 30_000)
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
