package io.github.pmaxai.cardscanner.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pmaxai.cardscanner.data.CardInfo
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.Pack
import io.github.pmaxai.cardscanner.vision.Readings
import io.github.pmaxai.cardscanner.vision.Recognition
import io.github.pmaxai.cardscanner.vision.Recognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import java.util.concurrent.atomic.AtomicBoolean

enum class Confidence { SURE, LIKELY, UNSURE }

data class Candidate(val info: CardInfo, val score: Float, val reading: String)

data class ScanResult(
    val candidates: List<Candidate>,
    val confidence: Confidence,
    val margin: Float,
    val detected: Boolean,
    val fromPhoto: Boolean,
) {
    val best: Candidate? get() = candidates.firstOrNull()
}

data class UiState(
    val loading: Boolean = true,
    val loadingText: String = "Preparing card database…",
    val error: String? = null,
    val packInfo: String = "",
    /** what is shown in the result panel (stable, confident) */
    val result: ScanResult? = null,
    /** live outline of the last frame, in frame pixels, for the overlay */
    val liveQuad: FloatArray? = null,
    val liveFrame: Pair<Int, Int>? = null,
    val liveConfidence: Confidence? = null,
    val debug: DebugInfo? = null,
    val paused: Boolean = false,
    val threshold: Double = 5.0,
    val showDebug: Boolean = false,
)

data class DebugInfo(val preview: Bitmap?, val lines: List<String>)

class ScannerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var recognizer: Recognizer? = null
    private var catalog: Catalog? = null
    private var pack: Pack? = null
    private var marginSure = 0.055f
    private var marginLikely = 0.02f
    private val busy = AtomicBoolean(false)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // stability: the same card must win in two frames in a row before it is shown
    private var lastTop: String? = null
    private var lastAlerted: String? = null

    init {
        _state.update {
            it.copy(threshold = prefs.getFloat("threshold", 5f).toDouble(), showDebug = prefs.getBoolean("debug", false))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!Pack.hasBundledPack(app)) error("This build has no card database (data pack missing).")
                val pack = Pack.load(app)
                this@ScannerViewModel.pack = pack
                recognizer = Recognizer(pack)
                catalog = Catalog(pack.db)
                marginSure = pack.metaFloat("margin_99", marginSure)
                marginLikely = pack.metaFloat("margin_90", marginLikely)
                val info = "${pack.n} card images · data ${pack.version.take(10)} · sure ≥ %.3f".format(marginSure)
                _state.update { it.copy(loading = false, packInfo = info) }
            } catch (t: Throwable) {
                Log.e(TAG, "loading failed", t)
                _state.update { it.copy(loading = false, error = t.message ?: t.toString()) }
            }
        }
    }

    /** Called on the camera's analysis thread with an upright RGB frame. Drops frames while busy. */
    fun onFrame(rgb: Mat) {
        val rec = recognizer ?: return
        if (_state.value.paused || !busy.compareAndSet(false, true)) return
        try {
            val prior = Readings.guidePrior(rgb.cols(), rgb.rows())
            val r = rec.recognise(rgb, prior, withPreview = _state.value.showDebug)
            publish(r, rgb.cols(), rgb.rows(), fromPhoto = false)
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
        } finally {
            busy.set(false)
        }
    }

    /** A picked photo: no guide frame, shown immediately and the camera pauses. */
    fun onPhoto(rgb: Mat) {
        val rec = recognizer ?: return
        viewModelScope.launch(Dispatchers.Default) {
            while (!busy.compareAndSet(false, true)) kotlinx.coroutines.delay(20)
            try {
                _state.update { it.copy(paused = true) }
                val r = rec.recognise(rgb, null, withPreview = true)
                publish(r, rgb.cols(), rgb.rows(), fromPhoto = true)
            } finally {
                rgb.release()
                busy.set(false)
            }
        }
    }

    private fun publish(r: Recognition, w: Int, h: Int, fromPhoto: Boolean) {
        val cat = catalog ?: return
        val conf = when {
            r.best == null -> Confidence.UNSURE
            r.margin >= marginSure -> Confidence.SURE
            r.margin >= marginLikely -> Confidence.LIKELY
            else -> Confidence.UNSURE
        }
        val quad = r.quad?.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }?.toFloatArray()
        val debug = if (_state.value.showDebug || fromPhoto) DebugInfo(r.preview, buildList {
            add("card found: ${r.detected}   margin %.3f (%s)".format(r.margin, conf))
            add(r.timings.entries.joinToString("  ") { "${it.key} ${it.value} ms" })
            r.hits.take(5).forEachIndexed { i, hit ->
                val name = cat.card(hit.cardId, "en")?.name ?: hit.cardId
                add("${i + 1}. %.3f  %s  %s  [%s]".format(hit.score, hit.cardId, name, Recognizer.READINGS[hit.reading]))
            }
        }) else null

        val top = r.best?.cardId
        val stable = fromPhoto || (top != null && top == lastTop)
        lastTop = top
        var newResult: ScanResult? = null
        if (r.best != null && (fromPhoto || (conf != Confidence.UNSURE && stable))) {
            val langs = pack?.entryLang
            val cands = r.hits.take(4).mapNotNull { hit ->
                // show the card in the language of the reference image that matched (German print -> German name)
                cat.card(hit.cardId, langs?.getOrNull(hit.entry) ?: "en")
                    ?.let { Candidate(it, hit.score, Recognizer.READINGS[hit.reading]) }
            }
            if (cands.isNotEmpty()) newResult = ScanResult(cands, conf, r.margin, r.detected, fromPhoto)
        }
        _state.update {
            it.copy(
                result = newResult ?: it.result,
                liveQuad = quad, liveFrame = w to h, liveConfidence = conf,
                debug = debug ?: it.debug.takeIf { _ -> it.showDebug },
            )
        }
        val shown = newResult
        if (shown != null) shown.best?.let { maybeAlert(it, shown.confidence) }
    }

    private fun maybeAlert(c: Candidate, conf: Confidence) {
        if (conf == Confidence.UNSURE) return
        val price = c.info.prices?.best ?: return
        if (price < _state.value.threshold || lastAlerted == c.info.id) return
        lastAlerted = c.info.id
        vibrate()
    }

    private fun vibrate() {
        val ctx = getApplication<Application>()
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        }
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 90, 150), -1))
    }

    fun setThreshold(eur: Double) {
        prefs.edit().putFloat("threshold", eur.toFloat()).apply()
        lastAlerted = null
        _state.update { it.copy(threshold = eur) }
    }

    fun toggleDebug() {
        val v = !_state.value.showDebug
        prefs.edit().putBoolean("debug", v).apply()
        _state.update { it.copy(showDebug = v, debug = if (v) it.debug else null) }
    }

    fun resume() {
        lastTop = null
        _state.update { it.copy(paused = false, result = it.result?.takeIf { r -> !r.fromPhoto }) }
    }

    fun pause() = _state.update { it.copy(paused = true) }

    /** User picked another candidate as the right card. */
    fun choose(c: Candidate) = _state.update { s ->
        val r = s.result ?: return@update s
        s.copy(result = r.copy(candidates = listOf(c) + r.candidates.filter { it != c }, confidence = Confidence.SURE))
    }

    override fun onCleared() {
        recognizer?.close()
    }

    companion object {
        const val TAG = "CardScanner"
    }
}
