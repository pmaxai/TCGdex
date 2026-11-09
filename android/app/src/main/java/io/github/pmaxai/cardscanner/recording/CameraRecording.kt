package io.github.pmaxai.cardscanner.recording

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The clean camera file (camera.mp4, no overlays) next to the screen recording: a CameraX
 * VideoCapture bound with the existing Preview and ImageAnalysis. Not every phone takes the
 * three together: then scanning goes on unchanged, only the screen is recorded, and the log
 * says why ([error] "bind_failed"; "encoder" when the file failed).
 */
object CameraRecording {
    private const val TAG = "Recording"

    private val _capture = MutableStateFlow<VideoCapture<Recorder>?>(null)
    /** the use case the camera should bind now (null: none) */
    val capture: StateFlow<VideoCapture<Recorder>?> = _capture

    private var file: File? = null
    private var recording: Recording? = null
    private var finalized: CountDownLatch? = null
    /** when the camera file's first frame was taken (System.nanoTime's clock) */
    @Volatile var firstFrameNanos: Long? = null
        private set
    @Volatile var error: String? = null
        private set
    /** the camera file's picture size as it is shown (rotation applied) */
    @Volatile var size: Pair<Int, Int>? = null
        private set
    /** a test can make the bind fail */
    @Volatile var simulateBindFailure = false

    val active: Boolean get() = recording != null

    /** A recording with the camera file starts: the camera binds a VideoCapture next to the others. */
    fun prepare(target: File, hd: Boolean, bitrate: Int) {
        file = target; error = null; firstFrameNanos = null; size = null
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(if (hd) Quality.FHD else Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(bitrate)
            .build()
        _capture.value = VideoCapture.withOutput(recorder)
    }

    /** The camera's answer: bound (start the file) or not (screen only). */
    @SuppressLint("MissingPermission")
    fun onBound(ctx: Context, ok: Boolean) {
        val vc = _capture.value ?: return
        val f = file ?: return
        if (!ok) { error = "bind_failed"; _capture.value = null; return }
        if (recording != null) return
        runCatching {
            vc.resolutionInfo?.let { info ->
                val r = info.resolution
                size = if (info.rotationDegrees % 180 == 0) r.width to r.height else r.height to r.width
            }
            val done = CountDownLatch(1)
            finalized = done
            recording = vc.output.prepareRecording(ctx, FileOutputOptions.Builder(f).build())
                .start(ContextCompat.getMainExecutor(ctx)) { e ->
                    when (e) {
                        is VideoRecordEvent.Status -> {
                            val d = e.recordingStats.recordedDurationNanos
                            if (firstFrameNanos == null && d > 0) firstFrameNanos = System.nanoTime() - d
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (e.hasError() && e.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                                Log.w(TAG, "camera file: error ${e.error}", e.cause)
                                if (e.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) error = error ?: "encoder"
                            }
                            done.countDown()
                        }
                        else -> {}
                    }
                }
        }.onFailure { Log.w(TAG, "camera file could not start", it); error = "encoder"; _capture.value = null }
    }

    /** Ends the camera file (waits for it to be written) and hands the camera back to scanning alone. */
    fun stop() {
        recording?.let { r -> runCatching { r.stop() }; finalized?.await(8, TimeUnit.SECONDS) }
        recording = null
        _capture.value = null
    }

    /** The log's camera object; [screenZeroNanos]: the screen file's first frame. */
    fun headerJson(enabled: Boolean, screenZeroNanos: Long?): JSONObject {
        val f = file
        val ok = enabled && error == null && f != null && f.exists() && f.length() > 0
        if (!ok) {
            if (enabled && f != null && f.exists() && error != null) f.delete()
            return JSONObject().put("file", JSONObject.NULL).put("width", JSONObject.NULL).put("height", JSONObject.NULL)
                .put("fps", JSONObject.NULL).put("offset_ms", JSONObject.NULL).put("error", if (!enabled) "off" else error ?: "encoder")
        }
        val off = firstFrameNanos?.let { a -> screenZeroNanos?.let { z -> (a - z) / 1_000_000 } }
        return JSONObject().put("file", "camera.mp4").put("width", size?.first ?: JSONObject.NULL).put("height", size?.second ?: JSONObject.NULL)
            .put("fps", 30).put("offset_ms", off ?: JSONObject.NULL).put("error", JSONObject.NULL)
    }

    /**
     * A point of the scanner's upright analysis frame (0–1) in the camera file's picture (0–1).
     * Both are centred crops of the same sensor picture; the wider one keeps the full width.
     */
    fun toCameraFile(x: Double, y: Double, analysisAspect: Double, videoAspect: Double): DoubleArray =
        if (videoAspect > analysisAspect) {
            val k = analysisAspect / videoAspect
            doubleArrayOf(x, ((y - (1 - k) / 2) / k).coerceIn(0.0, 1.0))
        } else {
            val k = videoAspect / analysisAspect
            doubleArrayOf(((x - (1 - k) / 2) / k).coerceIn(0.0, 1.0), y)
        }
}
