package io.github.pmaxai.cardscanner.update

import org.json.JSONObject
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
 * Checks for a newer app (with newer prices). Downloading and installing it is [Installer]'s job,
 * which only the direct edition (the APK from GitHub) has: on Google Play, Play updates the app.
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

    internal fun open(url: String, timeoutMs: Int): HttpURLConnection {
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
}
