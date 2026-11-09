package io.github.pmaxai.cardscanner.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.pmaxai.cardscanner.R
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Uploads one recording, also with the app closed: WorkManager runs it when the network fits
 * (Wi-Fi only, unless Upload now), with a notification showing the progress. A temporary
 * failure waits (5 s, 10 s, … up to 10 min) and goes on where it stopped; a permanent one
 * (wrong key, no bucket) stops as Failed until the settings change or the user taps Retry.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val store = RecordingStore(ctx)

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val rec = store.get(id) ?: return Result.failure()
        if (rec.status == RecStatus.UPLOADED) return Result.success()
        val prefs = RecordingPrefs(applicationContext)
        val settings = prefs.load()
        val cfg = prefs.s3Config(settings) ?: run {
            rec.updateMeta { it.put("status", RecStatus.FAILED.name).put("error", "Upload settings are incomplete") }
            return Result.failure()
        }
        runCatching { setForeground(foreground(rec, 0)) }
        rec.updateMeta { it.put("status", RecStatus.UPLOADING.name).put("error", "").put("waiting_wifi", false) }
        val client = S3Client(cfg)
        var wait = 5_000L
        var lastPct = -1
        while (true) {
            val uploader = RecordingUploader(client, id, UploadState(rec.uploadState), appVersion()) { done, total ->
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    rec.updateMeta { it.put("progress", pct) }
                    setProgressAsync(workDataOf("progress" to pct))
                    runCatching { notify(rec, pct) }
                }
            }
            try {
                uploader.run(rec.uploadFiles())
                rec.updateMeta { it.put("status", RecStatus.UPLOADED.name).put("progress", 100).put("uploaded_at", System.currentTimeMillis()) }
                if (settings.deleteAfterUpload) { rec.video.delete(); rec.camera.delete() }
                return Result.success()
            } catch (e: S3Exception) {
                if (e.permanent) {
                    rec.updateMeta { it.put("status", RecStatus.FAILED.name).put("error", e.friendly) }
                    return Result.failure()
                }
                Log.w(TAG, "upload $id: ${e.code}, trying again in ${wait / 1000} s")
            } catch (e: Exception) {
                if (isStopped) break
                Log.w(TAG, "upload $id: ${e.message}, trying again in ${wait / 1000} s")
            }
            if (isStopped) break
            rec.updateMeta { it.put("status", RecStatus.QUEUED.name) }
            delay(wait)
            wait = (wait * 2).coerceAtMost(10 * 60_000L)
            rec.updateMeta { it.put("status", RecStatus.UPLOADING.name) }
        }
        // stopped by the system (network gone, battery saver): WorkManager runs it again later
        rec.updateMeta { it.put("status", RecStatus.QUEUED.name) }
        return Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(store.get(inputData.getString(KEY_ID) ?: "") ?: RecordingDir(applicationContext.filesDir), 0)

    private fun appVersion(): String = runCatching {
        applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun notification(rec: RecordingDir, pct: Int): Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW))
        val waiting = store.list().count { it.status == RecStatus.QUEUED || it.status == RecStatus.UPLOADING }
        val all = store.list().filter { it.status != RecStatus.LOCAL && it.status != RecStatus.FAILED }
        val n = all.sortedBy { it.id }.indexOfFirst { it.id == rec.id } + 1
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(applicationContext, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(applicationContext)
        return b.setSmallIcon(R.drawable.ic_upload).setContentTitle("Uploading recording" + if (waiting > 1) " $n of ${all.size}" else "")
            .setContentText("$pct %").setProgress(100, pct, false).setOngoing(true).build()
    }

    private fun foreground(rec: RecordingDir, pct: Int): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification(rec, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification(rec, pct))

    private fun notify(rec: RecordingDir, pct: Int) {
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(rec, pct))
    }

    companion object {
        private const val TAG = "Recording"
        private const val KEY_ID = "id"
        private const val CHANNEL = "uploads"
        private const val NOTIFICATION_ID = 42

        fun workName(id: String) = "upload-$id"

        /** Queues a recording's upload; [anyNetwork]: Upload now, also on mobile data. */
        fun enqueue(ctx: Context, id: String, wifiOnly: Boolean, anyNetwork: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly && !anyNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("upload")
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context, id: String) { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)) }
    }
}
