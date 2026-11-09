package io.github.pmaxai.cardscanner.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import io.github.pmaxai.cardscanner.MainActivity
import io.github.pmaxai.cardscanner.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What a recording needs from the screen side: where, how big, how good, sound or not. */
data class StartRequest(
    val dir: RecordingDir,
    val resultCode: Int,
    val consent: Intent,
    val longSide: Int,
    val bitrate: Int,
    val mic: Boolean,
)

/** A recording that ended: the screen file's facts, and why it ended. */
data class Finished(
    val dir: RecordingDir,
    val reason: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val audio: Boolean,
    val startedAtMs: Long,
    val error: String?,
)

/** The recording, process-wide: the screen service and the view model meet here. */
object RecordingController {
    sealed class Phase {
        data object Idle : Phase()
        data object Starting : Phase()
        /** [since]: SystemClock.elapsedRealtime() of the first frame */
        data class Recording(val since: Long) : Phase()
        data object Stopping : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    /** the event log of the running recording (null: none, or the first frame isn't in yet) */
    @Volatile var log: EventLog? = null
    @Volatile var request: StartRequest? = null
        internal set
    /** set by the view model: the first frame is in (its time), the recording ended */
    @Volatile var onFirstFrame: ((Long) -> Unit)? = null
    @Volatile var onFinished: ((Finished) -> Unit)? = null

    val recording: Boolean get() = _phase.value !is Phase.Idle

    fun begin(ctx: Context, req: StartRequest) {
        request = req
        _phase.value = Phase.Starting
        ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_START))
    }

    fun stop(ctx: Context, reason: String) {
        if (_phase.value is Phase.Idle || _phase.value is Phase.Stopping) return
        ctx.startService(Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP).putExtra("reason", reason))
    }

    internal fun started(firstFrameNanos: Long) {
        _phase.value = Phase.Recording(SystemClock.elapsedRealtime())
        onFirstFrame?.invoke(firstFrameNanos)
    }

    internal fun stopping() { _phase.value = Phase.Stopping }

    internal fun finished(f: Finished) {
        request = null
        _phase.value = Phase.Idle
        onFinished?.invoke(f)
    }
}

/**
 * The screen recording as a foreground service (type mediaProjection, as Android requires):
 * MediaProjection's virtual display feeds the H.264 encoder; the microphone the AAC one when
 * it is switched on. Stops on Stop (app or notification), when the projection ends, when the
 * storage runs low or an encoder fails.
 */
class RecordingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoders: Encoders? = null
    private var stopping = false
    private var startedAtMs = 0L
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop(intent.getStringExtra("reason") ?: "user")
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val req = RecordingController.request ?: run { stopSelf(); return }
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(req.resultCode, req.consent) ?: error("no screen capture")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stop("user") } }
            }, main)
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
            val (w, h) = Encoders.chooseSize(screenW, screenH, req.longSide)
            val enc = Encoders(req.dir.video, w, h, req.bitrate, if (req.mic) MicSource() else null) { nanos ->
                main.post { RecordingController.started(nanos) }
            }.prepare()
            encoders = enc
            startedAtMs = System.currentTimeMillis()
            enc.start()
            display = p.createVirtualDisplay("tcgdex-recording", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                enc.inputSurface, null, main)
            watch()
        } catch (t: Throwable) {
            Log.e(TAG, "recording could not start", t)
            finish("error", t.message ?: t.javaClass.simpleName)
        }
    }

    /** every 10 s: storage left, encoders alive */
    private fun watch() {
        main.postDelayed({
            if (stopping) return@postDelayed
            val free = runCatching { android.os.StatFs(filesDirFor().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            when {
                free < LOW_STORAGE -> stop("low_storage")
                encoders?.failure != null -> stop("error")
                else -> watch()
            }
        }, 10_000)
    }

    private fun filesDirFor(): File = RecordingController.request?.dir?.dir ?: filesDir

    private fun stop(reason: String) {
        if (stopping) return
        stopping = true
        RecordingController.stopping()
        val err = encoders?.failure?.message
        Thread({ finish(reason, err) }, "rec-stop").start()
    }

    private fun finish(reason: String, error: String?) {
        val req = RecordingController.request
        val enc = encoders
        runCatching { display?.release() }
        val duration = enc?.let { runCatching { it.stop() }.getOrDefault(0L) } ?: 0L
        runCatching { projection?.stop() }
        main.post {
            if (req != null) RecordingController.finished(Finished(req.dir, reason, duration, enc?.width ?: 0, enc?.height ?: 0,
                screenW, screenH, req.mic, startedAtMs, error))
            else RecordingController.finished(Finished(RecordingDir(filesDir), reason, 0, 0, 0, 0, 0, false, 0, error))
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP).putExtra("reason", "user"),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_rec).setContentTitle("Recording YouTube mode").setContentText("Tap Stop to end the recording")
            .setUsesChronometer(true).setWhen(System.currentTimeMillis()).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    companion object {
        const val ACTION_START = "io.github.pmaxai.cardscanner.recording.START"
        const val ACTION_STOP = "io.github.pmaxai.cardscanner.recording.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "Recording"
        /** below this much free space the recording stops cleanly */
        const val LOW_STORAGE = 300L * 1024 * 1024

    }
}
