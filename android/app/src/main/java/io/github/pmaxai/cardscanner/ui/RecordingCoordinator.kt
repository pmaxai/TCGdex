package io.github.pmaxai.cardscanner.ui

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.media.MediaExtractor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.FileProvider
import io.github.pmaxai.cardscanner.recording.CameraRecording
import io.github.pmaxai.cardscanner.recording.EventLog
import io.github.pmaxai.cardscanner.recording.Finished
import io.github.pmaxai.cardscanner.recording.FragmentedMp4Writer
import io.github.pmaxai.cardscanner.recording.GallerySaver
import io.github.pmaxai.cardscanner.recording.LoadedTemplates
import io.github.pmaxai.cardscanner.recording.PackCard
import io.github.pmaxai.cardscanner.recording.Submission
import io.github.pmaxai.cardscanner.recording.Templates
import io.github.pmaxai.cardscanner.recording.RecStatus
import io.github.pmaxai.cardscanner.recording.RecordingController
import io.github.pmaxai.cardscanner.recording.RecordingDir
import io.github.pmaxai.cardscanner.recording.RecordingPrefs
import io.github.pmaxai.cardscanner.recording.RecordingSettings
import io.github.pmaxai.cardscanner.recording.RecordingStore
import io.github.pmaxai.cardscanner.recording.RecordingUploader
import io.github.pmaxai.cardscanner.recording.S3Client
import io.github.pmaxai.cardscanner.recording.S3Exception
import io.github.pmaxai.cardscanner.recording.StartRequest
import io.github.pmaxai.cardscanner.recording.UploadState
import io.github.pmaxai.cardscanner.recording.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/** One recording in the Recordings list. */
data class RecInfo(
    val id: String, val date: String, val durationMs: Long, val cards: Int, val hits: Int, val bytes: Long,
    val status: RecStatus, val statusText: String, val error: String?,
)

/** What the screens show of recording: the phase, the settings, the list, the last result. */
data class RecordingUi(
    val phase: RecordingController.Phase = RecordingController.Phase.Idle,
    val settings: RecordingSettings = RecordingSettings(),
    val recordings: List<RecInfo> = emptyList(),
    /** "Recording 30:34 · 38 cards · 3 hits · 1.8 GB · queued" after Stop */
    val lastSummary: String? = null,
    val connection: String? = null,
    val connectionOk: Boolean? = null,
    val testing: Boolean = false,
    /** uploads may be stopped by the phone's battery saver: a hint with a button to its settings */
    val batteryHint: Boolean = false,
    /** a finished recording waiting for the user: submit (upload) or delete; with its summary line */
    val toDecide: RecInfo? = null,
    val toDecideText: String? = null,
) {
    val recording: Boolean get() = phase !is RecordingController.Phase.Idle
}

/**
 * Recording mode's side of the view model: starts and finishes recordings (folder, header,
 * event log, pack CSV), recovers one a crash left behind, queues uploads, and keeps the
 * Recordings list. The screen capture itself runs in RecordingService.
 */
class RecordingCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: () -> UiState,
    private val publish: ((RecordingUi) -> RecordingUi) -> Unit,
    private val message: (String) -> Unit,
) {
    private val prefs = RecordingPrefs(app)
    val store = RecordingStore(app)
    /** the Fit to 16:9 area on the screen (pixels) while recording; null: the whole screen */
    @Volatile var contentRect: Rect? = null

    init {
        publish { it.copy(settings = prefs.load()) }
        RecordingController.onFinished = { f -> scope.launch { finish(f) } }
        scope.launch { RecordingController.phase.collect { p -> publish { it.copy(phase = p) } } }
        scope.launch(Dispatchers.IO) { recover(); refresh() }
    }

    fun settings(): RecordingSettings = prefs.load()

    // ------------------------------------------------------------------ start and stop
    /** After Android's consent: a new folder, the header, the log; the service starts capturing. */
    /** between Start and the capture's start (the screen is getting ready) */
    @Volatile private var starting = false

    fun start(resultCode: Int, consent: Intent, landscape: Boolean) {
        if (RecordingController.recording || starting) return
        val s = prefs.load()
        val dir = store.create()
        val st = state()
        val header = JSONObject()
            .put("app", JSONObject().put("version", appVersion()).put("data_pack", st.packVersion ?: JSONObject.NULL)
                .put("prices_date", st.pricesDate ?: JSONObject.NULL))
            .put("device", JSONObject().put("model", deviceName()).put("android_sdk", Build.VERSION.SDK_INT))
            .put("video", JSONObject().put("file", "video.mp4").put("started_at", JSONObject.NULL).put("duration_ms", JSONObject.NULL)
                .put("width", JSONObject.NULL).put("height", JSONObject.NULL).put("fps", 30)
                .put("orientation", if (landscape) "landscape" else "portrait").put("audio", if (s.mic) "mic" else "none")
                .put("content_rect", JSONObject.NULL))
            .put("camera", JSONObject().put("file", JSONObject.NULL).put("width", JSONObject.NULL).put("height", JSONObject.NULL)
                .put("fps", JSONObject.NULL).put("offset_ms", JSONObject.NULL).put("error", "off"))
            .put("settings", JSONObject().put("hit_threshold_eur", st.settings.threshold).put("title", st.settings.title)
                .put("show_psa", st.settings.showPsa))
            .put("ui", JSONObject().put("count_up_ms", COUNT_UP_MS).put("hit_burst_ms", HIT_BURST_MS))
        dir.header.writeText(header.toString(2))
        dir.updateMeta { it.put("status", RecStatus.RECORDING.name).put("created", System.currentTimeMillis()) }
        contentRect = null                    // the layout sets it once the 16:9 area is in place
        val log = EventLog(dir.id, dir.eventsJsonl, st.settings.threshold, revealTiming = true)
        RecordingController.onFirstFrame = { nanos ->
            val now = state()
            log.start(nanos, now.session, now.shown)
            RecordingController.log = log
        }
        // the screen takes the recording layout first (no ● REC in the bar, the 16:9 area) and the camera its
        // new binding; the capture starts after that, so the video's first frame already shows them
        publish { it.copy(lastSummary = null, phase = RecordingController.Phase.Starting) }
        // the clean camera file: the camera binds a VideoCapture next to preview and analysis
        if (s.recordCamera) CameraRecording.prepare(dir.camera, s.hd, s.bitrateMbps * 1_000_000)
        starting = true
        scope.launch {
            try {
                if (s.recordCamera) withTimeoutOrNull(3_000) { while (!CameraRecording.active && CameraRecording.error == null) delay(50) }
                delay(300)
            } finally {
                starting = false
            }
            RecordingController.begin(app, StartRequest(dir, resultCode, consent, s.longSide, s.bitrateMbps * 1_000_000, s.mic))
        }
    }

    fun stop(reason: String = "user") = RecordingController.stop(app, reason)

    /** YouTube mode drew the first frame of the reveal of the card with [serial]: its event takes this time. */
    fun revealDrawn(serial: Int) { RecordingController.log?.revealDrawn(serial, System.nanoTime()) }

    /** The service has ended the capture: events.json, pack.csv, status, upload. */
    private suspend fun finish(f: Finished) {
        val log = RecordingController.log
        RecordingController.log = null
        RecordingController.onFirstFrame = null
        val dir = f.dir
        val s0 = prefs.load()
        withContext(Dispatchers.IO) { CameraRecording.stop() }
        if (log == null || f.durationMs <= 0) {
            withContext(Dispatchers.IO) { dir.dir.deleteRecursively() }
            message("Recording could not start" + (f.error?.let { ": $it" } ?: ""))
            refresh(); return
        }
        log.stop(f.reason)
        val st = state()
        withContext(Dispatchers.IO) {
            val header = runCatching { JSONObject(dir.header.readText()) }.getOrElse { JSONObject() }
            header.optJSONObject("video")?.apply {
                put("started_at", Instant.ofEpochMilli(f.startedAtMs).toString()).put("duration_ms", f.durationMs)
                put("width", f.width).put("height", f.height)
                put("content_rect", contentRectJson(f))
            }
            header.put("camera", CameraRecording.headerJson(s0.recordCamera, log.zeroNanos))
            val events = log.finish(header, st.session, "complete")
            dir.events.writeText(events)
            dir.packCsv.writeText(PackCsv.build(st.session, st.settings, Locale.getDefault()))
            val hits = st.session.count { isHit(it.value, st.settings.threshold) }
            // kept on the phone until the user says: submit (upload) or delete
            dir.updateMeta {
                it.put("status", RecStatus.LOCAL.name).put("duration_ms", f.durationMs)
                    .put("cards", st.session.size).put("hits", hits).put("stop_reason", f.reason).put("decide", true)
            }
        }
        if (f.reason == "error" && f.error != null) dir.updateMeta { it.put("stop_error", f.error) }
        publish { it.copy(lastSummary = null) }
        refresh()
    }

    /** The 16:9 area in video pixels (the whole frame when Fit to 16:9 is off). */
    private fun contentRectJson(f: Finished): JSONObject {
        val r = contentRect.takeIf { prefs.load().fit169 }
        contentRect = null
        val sx = f.width.toDouble() / f.screenWidth.coerceAtLeast(1)
        val sy = f.height.toDouble() / f.screenHeight.coerceAtLeast(1)
        return if (r == null || f.screenWidth == 0) JSONObject().put("x", 0).put("y", 0).put("width", f.width).put("height", f.height)
        else JSONObject().put("x", Math.round(r.left * sx)).put("y", Math.round(r.top * sy))
            .put("width", Math.round(r.width() * sx)).put("height", Math.round(r.height() * sy))
    }

    /** A recording the app didn't finish (crash, force-stop): cut to its last whole second, log rebuilt, queued. */
    private fun recover() {
        if (RecordingController.recording) return
        store.list().filter { it.status == RecStatus.RECORDING }.forEach { dir ->
            runCatching {
                val kept = if (dir.video.exists()) FragmentedMp4Writer.repair(dir.video) else 0
                if (kept <= 0) { dir.dir.deleteRecursively(); return@forEach }
                dir.camera.delete()            // the camera file is only complete after Stop
                val duration = lastSampleMs(dir)
                val header = runCatching { JSONObject(dir.header.readText()) }.getOrElse { JSONObject() }
                header.optJSONObject("camera")?.put("file", JSONObject.NULL)?.put("error", "lost")
                val (events, cards) = RecordingStore.recoverEvents(header, dir.eventsJsonl, dir.id, duration)
                dir.events.writeText(events)
                dir.packCsv.writeText(RecordingStore.csvOf(cards))
                // like a finished one: waits for the user's submit or delete
                dir.updateMeta {
                    it.put("status", RecStatus.LOCAL.name).put("duration_ms", duration)
                        .put("cards", cards.size).put("hits", cards.count { c -> c.optBoolean("hit") }).put("recovered", true)
                        .put("decide", true)
                }
            }.onFailure { Log.w(TAG, "recovering ${dir.id}", it) }
        }
    }

    private fun lastSampleMs(dir: RecordingDir): Long = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(dir.video.path)
            ex.selectTrack(0)
            var last = 0L
            while (true) { last = maxOf(last, ex.sampleTime); if (!ex.advance()) break }
            last / 1000
        } finally { ex.release() }
    }.getOrDefault(0L)

    // ------------------------------------------------------------------ the list and the uploads
    fun refresh() {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val wifi = onUnmetered()
        val s = prefs.load()
        val list = store.list().filter { it.status != RecStatus.RECORDING || RecordingController.recording }.map { d ->
            val m = d.meta()
            if (d.status == RecStatus.QUEUED) d.updateMeta { it.put("waiting_wifi", s.wifiOnly && !wifi) }
            RecInfo(d.id, df.format(Date(m.optLong("created", d.dir.lastModified()))), m.optLong("duration_ms"), m.optInt("cards"),
                m.optInt("hits"), d.shownBytes, d.status, if (d.id in saving) "saving to the Gallery…" else d.statusText(s.uploadS3),
                m.optString("error").takeIf { it.isNotBlank() })
        }
        val pm = app.getSystemService(PowerManager::class.java)
        val optimized = pm != null && !pm.isIgnoringBatteryOptimizations(app.packageName)
        val stopped = store.list().any { it.status != RecStatus.UPLOADED && it.meta().optBoolean("stopped_by_system") }
        val seen = app.getSharedPreferences("recording", 0).getBoolean(HINT_SEEN, false)
        // the newest recording still waiting for submit or delete (not while one is running)
        val decide = if (RecordingController.recording) null else store.list().firstOrNull { it.status == RecStatus.LOCAL && it.meta().optBoolean("decide") }
        val decideInfo = decide?.let { d -> list.firstOrNull { it.id == d.id } }
        val decideText = decide?.let { d -> decideLine(d) }
        publish { it.copy(recordings = list, batteryHint = !seen && list.isNotEmpty() && (optimized || stopped),
            toDecide = decideInfo, toDecideText = decideText) }
    }

    /** "4:53 · 12 cards · 2 hits · 412 MB", and why it stopped when it wasn't the user */
    private fun decideLine(d: RecordingDir): String {
        val m = d.meta()
        val secs = m.optLong("duration_ms") / 1000
        val size = if (d.bytes >= 1_000_000_000) "%.1f GB".format(Locale.ROOT, d.bytes / 1e9) else "%d MB".format(Locale.ROOT, d.bytes / 1_000_000)
        val why = when {
            m.optBoolean("recovered") -> " · recovered after the app stopped"
            m.optString("stop_reason") == "low_storage" -> " · stopped: storage low"
            m.optString("stop_reason") == "background" -> " · stopped: the app left the screen"
            m.optString("stop_reason") == "error" -> " · stopped: " + (m.optString("stop_error").ifBlank { "encoder error" })
            else -> ""
        }
        return "%d:%02d · %d cards · %d hits · %s".format(Locale.ROOT, secs / 60, secs % 60, m.optInt("cards"), m.optInt("hits"), size) + why
    }

    // ------------------------------------------------------------------ submit: templates and inputs
    /** the last templates list read from the bucket, kept on the phone */
    private val templatesCache = java.io.File(app.filesDir, "templates/index.json")

    /**
     * The templates list for the submit screen (R2): the bucket's (saved on the phone), else the
     * last saved one, else the built-in default. At most about 10 s; offline answers at once.
     */
    suspend fun loadTemplates(): LoadedTemplates = withContext(Dispatchers.IO) {
        val cfg = prefs.s3Config()
        val fetch: (() -> ByteArray)? = cfg?.let { c -> {
            val http = okhttp3.OkHttpClient.Builder().connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            val client = S3Client(c, http, sleep = { Thread.sleep(minOf(it, 300L)) })
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try { pool.submit<ByteArray> { client.getObject(Templates.key(c)) }.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception) ?: e }
            finally { pool.shutdownNow() }
        } }
        Templates.load(fetch, templatesCache)
    }

    /** The recording's pack at Stop, for the "cards" inputs. */
    suspend fun packCards(id: String): List<PackCard> = withContext(Dispatchers.IO) {
        store.get(id)?.let { Submission.packOf(it.events) } ?: emptyList()
    }

    /** Whether the recording has the microphone's sound (the voice option "own" needs it). */
    suspend fun hasMicSound(id: String): Boolean = withContext(Dispatchers.IO) {
        store.get(id)?.let { Submission.hasMicSound(it.events, it.header) } ?: false
    }

    /**
     * "Submit" (R1, R5): submission.json with the ticked templates and the filled-in inputs goes
     * into the recording's folder; then the recording goes into the upload queue (only on Wi-Fi
     * if that is set). The uploader sends it before `_complete.json`.
     */
    fun submit(id: String, templates: List<String>, inputs: JSONObject) {
        val d = store.get(id) ?: return
        val s = prefs.load()
        // answered: the question doesn't come back (a failed write leaves it in the list, not submitted)
        d.updateMeta { it.put("decide", false) }
        publish { it.copy(toDecide = null, toDecideText = null) }
        scope.launch(Dispatchers.IO) {
            val written = runCatching { Submission.write(d.dir, Submission.build(templates, inputs, appVersion())) }
            if (written.isFailure) {
                Log.w(TAG, "submission.json", written.exceptionOrNull())
                message("Could not save the submission: ${written.exceptionOrNull()?.message}")
                refresh()
                return@launch
            }
            d.updateMeta { it.put("status", RecStatus.QUEUED.name).put("error", "").put("templates", JSONArray(templates)) }
            UploadWorker.enqueue(app, id, s.wifiOnly)
            refresh()
            publish { it.copy(lastSummary = d.summary()) }
        }
    }

    // ------------------------------------------------------------------ saved on the phone (S3 off)
    /**
     * "Save" (uploading to S3 is off): the recording's videos into the Gallery, Movies/Card
     * Scanner - the screen video rewritten as a plain MP4 (seekable), the camera video as it
     * is - then the app's copies are removed; the card log stays in the app's list.
     */
    fun saveToPhone(id: String) {
        val d = store.get(id) ?: return
        d.updateMeta { it.put("decide", false) }
        publish { it.copy(toDecide = null, toDecideText = null) }
        if (!saving.add(id)) return
        scope.launch(Dispatchers.IO) {
            refresh()
            val taken = d.meta().optLong("created", d.dir.lastModified())
            val videos = d.video.length().coerceAtLeast(0) + d.camera.length().coerceAtLeast(0)
            val saved = ArrayList<android.net.Uri>()
            val r = runCatching {
                if (d.video.exists()) saved += GallerySaver.save(app, d.video, "$id.mp4", taken, remux = true)
                if (d.camera.exists()) saved += GallerySaver.save(app, d.camera, "$id-camera.mp4", taken, remux = false)
                check(saved.isNotEmpty()) { "this recording has no video" }
            }
            if (r.isSuccess) {
                d.updateMeta { it.put("status", RecStatus.SAVED.name).put("error", "").put("saved_bytes", videos)
                    .put("gallery", JSONArray(saved.map { u -> u.toString() })) }
                d.video.delete(); d.camera.delete()
                message("Saved in the Gallery: ${GallerySaver.place}")
                publish { it.copy(lastSummary = d.summary()) }
            } else {
                // nothing half-saved: what got into the Gallery goes out again; the recording stays in the app
                saved.forEach { u -> runCatching { if (u.scheme == "file") java.io.File(u.path!!).delete() else app.contentResolver.delete(u, null, null) } }
                Log.w(TAG, "save to the Gallery", r.exceptionOrNull())
                message("Could not save in the Gallery: ${r.exceptionOrNull()?.message ?: r.exceptionOrNull()?.javaClass?.simpleName}")
            }
            saving.remove(id)
            refresh()
        }
    }

    /** Leaving the submit screen without choosing: the recording stays on the phone, unsubmitted (in the list). */
    fun later(id: String) {
        store.get(id)?.updateMeta { it.put("decide", false) }
        publish { it.copy(toDecide = null, toDecideText = null) }
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** "Delete": the finished recording is thrown away. */
    fun discard(id: String) {
        store.get(id)?.updateMeta { it.put("decide", false) }
        publish { it.copy(toDecide = null, toDecideText = null, lastSummary = null) }
        delete(id)
        message("Recording deleted")
    }

    /** The battery-saver hint is shown once: "Got it" hides it for good. */
    fun batteryHintSeen() {
        app.getSharedPreferences("recording", 0).edit().putBoolean(HINT_SEEN, true).apply()
        publish { it.copy(batteryHint = false) }
    }

    fun retry(id: String) {
        val d = store.get(id) ?: return
        d.updateMeta { it.put("status", RecStatus.QUEUED.name).put("error", "") }
        UploadWorker.enqueue(app, id, prefs.load().wifiOnly)
        refresh()
    }

    /** Upload now: also on mobile data, this one time. */
    fun uploadNow(id: String) {
        val d = store.get(id) ?: return
        d.updateMeta { it.put("status", RecStatus.QUEUED.name).put("error", "") }
        UploadWorker.enqueue(app, id, wifiOnly = true, anyNetwork = true)
        refresh()
    }

    /** Deletes the recording on the phone; an upload in progress is aborted (no orphaned parts in the bucket). */
    fun delete(id: String) {
        val d = store.get(id) ?: return
        UploadWorker.cancel(app, id)
        scope.launch(Dispatchers.IO) {
            prefs.s3Config()?.let { cfg -> runCatching {
                RecordingUploader(S3Client(cfg), id, UploadState(d.uploadState), appVersion()).abortOpenUploads()
            } }
            d.dir.deleteRecursively()
            refresh()
        }
    }

    /** The video to share with another app. */
    fun shareIntent(id: String): Intent? {
        val d = store.get(id) ?: return null
        // saved in the Gallery: its video there
        val uri = if (d.video.exists()) FileProvider.getUriForFile(app, app.packageName + ".updates", d.video)
            else d.meta().optJSONArray("gallery")?.optString(0)?.takeIf { it.startsWith("content:") }?.let(android.net.Uri::parse) ?: return null
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share recording")
    }

    // ------------------------------------------------------------------ settings
    fun saveSettings(s: RecordingSettings, secret: String?) {
        prefs.save(s, secret)
        publish { it.copy(settings = prefs.load()) }
    }

    /** Save & test: writes a small file into the bucket and deletes it again. */
    fun testConnection() {
        val cfg = prefs.s3Config() ?: run { publish { it.copy(connection = "Endpoint, bucket, key and secret are needed", connectionOk = false) }; return }
        publish { it.copy(testing = true, connection = null) }
        scope.launch(Dispatchers.IO) {
            val client = runCatching { S3Client(cfg) }
            val r = client.mapCatching { it.connectionTest() }
            publish {
                r.fold(
                    { ok -> it.copy(testing = false, connection = ok, connectionOk = true) },
                    { e -> it.copy(testing = false, connectionOk = false, connection = when (e) {
                        is S3Exception -> if (e.code == "RequestTimeTooSkewed") "Phone clock is off by ${Math.abs(client.getOrNull()?.clock?.offsetMs ?: 0) / 60_000} min" else e.friendly
                        is java.net.UnknownHostException, is javax.net.ssl.SSLException, is java.net.ConnectException -> "Can't reach host"
                        is IllegalArgumentException -> e.message ?: "Endpoint is not valid"
                        else -> e.message ?: "failed"
                    }) },
                )
            }
        }
    }

    private fun onUnmetered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun appVersion(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    companion object {
        /** recordings being saved into the Gallery now (this process) */
        private val saving: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        private const val TAG = "Recording"
        private const val HINT_SEEN = "battery_hint_seen"
        /** the reveal's animation lengths (YouTube mode), for the pipeline */
        const val COUNT_UP_MS = 900
        const val HIT_BURST_MS = 1600

        fun deviceName(): String {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
        }
    }
}
