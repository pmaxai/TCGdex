package io.github.pmaxai.cardscanner.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * The Google Play edition: Play updates the app, so it never downloads or installs an APK itself
 * (and has no permission to). Only here so the shared code compiles; nothing calls it
 * (BuildConfig.SELF_UPDATE is false).
 */
object Installer {
    fun download(ctx: Context, r: AppRelease, progress: (Float) -> Unit): File =
        throw UnsupportedOperationException("updates come from Google Play")

    fun canInstall(ctx: Context): Boolean = false

    /** the app's page in the Play Store */
    fun allowIntent(ctx: Context): Intent = storePage(ctx)

    fun installIntent(ctx: Context, apk: File): Intent = storePage(ctx)

    private fun storePage(ctx: Context): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
