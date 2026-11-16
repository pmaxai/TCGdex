package io.github.pmaxai.cardscanner.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.pmaxai.cardscanner.BuildConfig
import io.github.pmaxai.cardscanner.account.AccountApi
import io.github.pmaxai.cardscanner.account.AccountException
import io.github.pmaxai.cardscanner.account.AccountStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The store editions get no new app every night, so they download the nightly data pack
 * (prices, new cards) from the shop (`GET /api/app/v1/pack`): only the files that changed,
 * each checked against its SHA-256, resumed where a download stopped. A complete set waits in
 * `pack-new` and replaces the pack at the app's next start ([Pack.load]). A pack made for
 * another fingerprint model than this app's is left alone (that needs an app update).
 */
object PackUpdate {
    private const val TAG = "PackUpdate"
    /** a complete newer pack, used from the next start */
    const val READY_DIR = "pack-new"
    /** downloads in progress (kept, so they resume) */
    private const val WORK_DIR = "pack-dl"
    const val MARKER = "ready"

    /** the version of a pack directory (its pack.json), null without one */
    fun versionOf(dir: File): String? = manifest(dir)?.optString("version")?.takeIf { it.isNotBlank() }

    fun manifest(dir: File): JSONObject? = File(dir, "pack.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    /** a newer pack waits for the next start (in [root], the app's files): its version */
    fun waiting(root: File): String? = File(root, READY_DIR).takeIf { File(it, MARKER).exists() }?.let { versionOf(it) }

    /** two packs work with the same app: the same fingerprint model and size */
    fun fits(a: JSONObject?, b: JSONObject?): Boolean =
        a != null && b != null && a.optString("model") == b.optString("model") && a.optInt("dim") == b.optInt("dim")

    /**
     * Downloads the newest pack when it is newer than the one in use (or waiting) and fits this
     * app. Returns its version when a new one is ready, else null. Blocking.
     */
    fun download(root: File, api: AccountApi, token: String, http: OkHttpClient? = null): String? {
        val inUse = File(root, "pack")
        val ready = File(root, READY_DIR)
        val current = manifest(inUse)
        val have = listOfNotNull(versionOf(inUse), waiting(root)).maxOrNull()
        val o = api.pack(token)
        val version = o.getString("version")
        if (have != null && version <= have) return null
        val files = o.getJSONArray("files").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val client = http ?: OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val work = File(root, WORK_DIR)
        // parts of another version's download don't count
        if (runCatching { File(work, "version").readText() }.getOrNull() != version) work.deleteRecursively()
        work.mkdirs()
        File(work, "version").writeText(version)
        // the manifest first: a pack for another model is not for this app
        val order = files.sortedBy { if (it.getString("name") == "pack.json") 0 else 1 }
        for (f in order) {
            val name = f.getString("name")
            require(name.matches(Regex("[A-Za-z0-9._-]+")) && !name.startsWith(".")) { "odd file name $name" }
            val size = f.getLong("size")
            val sha = f.getString("sha256").lowercase()
            val target = File(work, name)
            if (!(target.length() == size && sha256(target) == sha)) {
                target.delete()
                val old = File(inUse, name)
                val oldSha = current?.optJSONObject("files")?.optJSONObject(name)?.optString("sha256")?.lowercase()
                if (oldSha == sha && old.length() == size) old.copyTo(target, overwrite = true)        // unchanged: from the pack in use
                else fetch(client, f.getString("url"), File(work, "$name.part"), size, sha, target)
            }
            if (name == "pack.json" && current != null && !fits(current, manifest(work))) {
                Log.w(TAG, "pack $version is for another model (${manifest(work)?.optString("model")}): needs an app update")
                work.deleteRecursively()
                return null
            }
        }
        // complete: it replaces what waits, and is used from the next start
        ready.deleteRecursively()
        File(work, "version").delete()
        work.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        if (!work.renameTo(ready)) throw IOException("could not keep the new pack")
        File(ready, MARKER).writeText(version)
        Log.i(TAG, "pack $version ready (${files.size} files)")
        return version
    }

    /** one file, resumed from [part] where it stopped; checked against [sha] */
    private fun fetch(client: OkHttpClient, url: String, part: File, size: Long, sha: String, target: File) {
        val from = part.length().takeIf { it in 1 until size } ?: 0L
        if (from == 0L) part.delete()
        val rb = Request.Builder().url(url)
        if (from > 0) rb.header("Range", "bytes=$from-")
        client.newCall(rb.build()).execute().use { res ->
            if (res.code == 410 || res.code == 403) throw IOException("the download address ran out (HTTP ${res.code})")
            if (!res.isSuccessful) throw IOException("download: HTTP ${res.code}")
            val append = from > 0 && res.code == 206
            FileOutputStream(part, append).use { out -> res.body!!.byteStream().use { it.copyTo(out, 1 shl 16) } }
        }
        if (part.length() != size) throw IOException("${target.name}: ${part.length()} of $size bytes")
        if (sha256(part) != sha) { part.delete(); throw IOException("${target.name}: checksum doesn't match") }
        if (!part.renameTo(target)) throw IOException("could not keep ${target.name}")
    }

    fun sha256(f: File): String? {
        if (!f.exists()) return null
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * At the start, before the pack is opened: a complete newer pack takes the place of the one
     * in use (if it fits the pack this app came with).
     */
    fun promote(root: File, bundled: JSONObject?) {
        val ready = File(root, READY_DIR)
        if (!File(ready, MARKER).exists()) return
        val inUse = File(root, "pack")
        val v = versionOf(ready)
        val ok = v != null && fits(manifest(ready), bundled) && (versionOf(inUse) ?: "") < v
        if (ok) {
            val old = File(root, "pack-old")
            old.deleteRecursively()
            if (inUse.exists() && !inUse.renameTo(old)) { ready.deleteRecursively(); return }
            if (ready.renameTo(inUse)) { File(inUse, MARKER).delete(); old.deleteRecursively(); Log.i(TAG, "using pack $v") }
            else old.renameTo(inUse)
        }
        ready.deleteRecursively()
    }

    /** the store editions: every 6 hours with a connection, and once now */
    fun schedule(ctx: Context) {
        if (!BuildConfig.STORE_BILLING) return
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork("pack-nightly", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PackWorker>(6, TimeUnit.HOURS).setConstraints(net).build())
        wm.enqueueUniqueWork("pack-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PackWorker>().setConstraints(net).build())
    }
}

/** Downloads the newest data pack in the background (store editions). */
class PackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = AccountStore(applicationContext)
        val token = store.token ?: return Result.success()
        val base = store.base ?: return Result.success()
        if (store.status()?.active != true) return Result.success()
        return try {
            PackUpdate.download(applicationContext.filesDir, AccountApi(base, BuildConfig.VERSION_NAME), token)
            Result.success()
        } catch (e: AccountException) {
            if (e.status >= 500 || e.status == 429) Result.retry() else Result.success()
        } catch (e: IOException) {
            Log.w("PackUpdate", "download stopped: ${e.message}")
            if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}
