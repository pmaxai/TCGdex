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
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.vision.CardText
import io.github.pmaxai.cardscanner.vision.FoilEstimate
import io.github.pmaxai.cardscanner.vision.FoilMeter
import io.github.pmaxai.cardscanner.vision.Hit
import io.github.pmaxai.cardscanner.vision.Readings
import io.github.pmaxai.cardscanner.vision.Recognition
import io.github.pmaxai.cardscanner.vision.Recognizer
import io.github.pmaxai.cardscanner.vision.ScanTracker
import io.github.pmaxai.cardscanner.vision.VersionResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean

enum class Confidence { SURE, LIKELY, UNSURE }

/** Another artwork the scanner considered. */
data class Candidate(val group: String, val info: CardInfo, val score: Float, val reading: String)

/** The card in the result panel. */
data class Shown(
    val group: String,
    /** the scanned version: its set, number and language */
    val version: CardInfo,
    /** English name and set, when the scanned version is not English */
    val english: CardInfo?,
    /** whose prices are shown: the version itself, or its English version if it has none */
    val priceCard: CardInfo,
    val printing: Printing.Kind?,
    val printingByUser: Boolean,
    val foil: FoilEstimate?,
    val confidence: Confidence,
    val alternatives: List<Candidate>,
    /** the same card in other languages, to pick from */
    val languages: List<CardInfo>,
    val text: CardText?,
    val locked: Boolean,
    val fromPhoto: Boolean,
    val preview: Bitmap?,
    /** grows with every new card (the YouTube reveal animates on it) */
    val serial: Int,
) {
    val selected: Printing? get() = priceCard.printing(printing)
    val value: Double? get() = selected?.value ?: priceCard.bestValue
    /** the most valuable other printing, if it is worth more than the selected one */
    val richerPrinting: Printing? get() = priceCard.printings
        .filter { it.kind != selected?.kind && (it.value ?: 0.0) > (value ?: 0.0) }.maxByOrNull { it.value ?: 0.0 }
    val pricedFromEnglish: Boolean get() = priceCard.id != version.id || priceCard.lang != version.lang
}

data class SessionItem(val group: String, val name: String, val number: String, val value: Double?,
                       val hit: Boolean, val thumbnail: String?)

data class Settings(
    val threshold: Double = 5.0,
    val showPsa: Boolean = true,
    val showDebug: Boolean = false,
    val youtube: Boolean = false,
    val title: String = "",
)

data class DebugInfo(val preview: Bitmap?, val lines: List<String>)

data class UiState(
    val loading: Boolean = true,
    val loadingText: String = "Preparing card database…",
    val error: String? = null,
    val packInfo: String = "",
    val shown: Shown? = null,
    val liveQuad: FloatArray? = null,
    val liveFrame: Pair<Int, Int>? = null,
    val liveConfidence: Confidence? = null,
    val debug: DebugInfo? = null,
    val paused: Boolean = false,
    val settings: Settings = Settings(),
    val session: List<SessionItem> = emptyList(),
    val hasPsaData: Boolean = false,
) {
    val sessionValue: Double get() = session.sumOf { it.value ?: 0.0 }
    val bestPull: SessionItem? get() = session.maxByOrNull { it.value ?: 0.0 }
}

class ScannerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pack: Pack? = null
    private var recognizer: Recognizer? = null
    private var catalog: Catalog? = null
    private var resolver: VersionResolver? = null
    private var tracker = ScanTracker(0.055f, 0.02f)
    private var foil: FoilMeter? = null
    private var marginSure = 0.055f
    private var marginLikely = 0.02f
    private val busy = AtomicBoolean(false)
    private val lock = Any()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var serial = 0
    private var lastAlerted: String? = null
    private var lastText: CardText? = null

    init {
        _state.update {
            it.copy(settings = Settings(
                threshold = prefs.getFloat("threshold", 5f).toDouble(),
                showPsa = prefs.getBoolean("psa", true),
                showDebug = prefs.getBoolean("debug", false),
                youtube = prefs.getBoolean("youtube", false),
                title = prefs.getString("title", "") ?: "",
            ))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!Pack.hasBundledPack(app)) error("This build has no card database (data pack missing).")
                val p = Pack.load(app)
                pack = p
                val rec = Recognizer(p)
                recognizer = rec
                val cat = Catalog(p.db)
                catalog = cat
                resolver = VersionResolver().also { r -> viewModelScope.launch(Dispatchers.Default) { r.warmUp() } }
                foil = FoilMeter(rec.geometry)
                marginSure = p.metaFloat("margin_99", marginSure)
                marginLikely = p.metaFloat("margin_90", marginLikely)
                tracker = ScanTracker(marginSure, marginLikely)
                val psa = runCatching {
                    p.db.rawQuery("SELECT COUNT(*) FROM graded", null).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
                }.getOrDefault(false)
                val info = "${p.n} card images · data ${p.version.take(10)}" + if (psa) " · PSA prices" else ""
                _state.update { it.copy(loading = false, packInfo = info, hasPsaData = psa) }
            } catch (t: Throwable) {
                Log.e(TAG, "loading failed", t)
                _state.update { it.copy(loading = false, error = t.message ?: t.toString()) }
            }
        }
    }

    // ------------------------------------------------------------------ frames
    /** Called on the camera's analysis thread with an upright RGB frame. Drops frames while busy. */
    fun onFrame(rgb: Mat) {
        val rec = recognizer ?: return
        if (_state.value.paused || !busy.compareAndSet(false, true)) return
        try {
            val prior = Readings.guidePrior(rgb.cols(), rgb.rows())
            val r = rec.recognise(rgb, prior, withPreview = _state.value.settings.showDebug)
            val conf = confidenceOf(r)
            synchronized(lock) {
                val changed = tracker.update(r.best?.group, r.margin)
                val current = _state.value.shown
                if (changed) {
                    showNew(tracker.shown!!, r, rgb, fromPhoto = false, conf = conf)
                } else if (current != null && !current.fromPhoto && r.detected && r.best?.group == current.group) {
                    trackFoil(current, r, rgb)
                }
            }
            publishLive(r, rgb.cols(), rgb.rows(), conf)
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
            while (!busy.compareAndSet(false, true)) delay(20)
            try {
                _state.update { it.copy(paused = true) }
                val r = rec.recognise(rgb, null, withPreview = true)
                val best = r.best
                synchronized(lock) {
                    if (best != null) showNew(best.group, r, rgb, fromPhoto = true, conf = confidenceOf(r))
                }
                publishLive(r, rgb.cols(), rgb.rows(), confidenceOf(r))
            } catch (t: Throwable) {
                Log.e(TAG, "photo failed", t)
            } finally {
                rgb.release()
                busy.set(false)
            }
        }
    }

    private fun confidenceOf(r: Recognition) = when {
        r.best == null -> Confidence.UNSURE
        r.margin >= marginSure -> Confidence.SURE
        r.margin >= marginLikely -> Confidence.LIKELY
        else -> Confidence.UNSURE
    }

    private fun cardQuad(r: Recognition, hit: Hit): Array<Point>? = recognizer?.cardQuad(r, hit)

    private fun readText(rgb: Mat, r: Recognition, hit: Hit, tryJapanese: Boolean): CardText? = runCatching {
        val res = resolver ?: return null
        recognizer?.readText(rgb, r, hit, res, tryJapanese)
    }.onFailure { Log.w(TAG, "text recognition failed", it) }.getOrNull()

    /** Which exact card (set, language) of this artwork group the photo shows. */
    private fun resolveVersion(group: String, entry: Int?, text: CardText?): CardInfo? {
        val cat = catalog ?: return null
        val p = pack ?: return null
        val versions = cat.versions(group)
        if (versions.isEmpty()) return cat.card(group, "en")
        var id = entry?.let { p.entryCard[it] }?.takeIf { e -> versions.any { it.first == e } } ?: versions.first().first
        val num = VersionResolver.normNumber(text?.number)
        val total = text?.total?.let { VersionResolver.normNumber(it) }
        // exact number first; then one easily confused digit (6/8, 1/7 ...) if the set size agrees
        fun numberMatches(cid: String, lang: String, fuzzy: Boolean = false): Boolean {
            val c = cat.cardExact(cid, lang) ?: return false
            val n = VersionResolver.normNumber(c.number)
            val totalOk = total == null || c.setTotal == null || VersionResolver.normNumber(c.setTotal.toString()) == total ||
                (c.number.toIntOrNull() ?: 0) > (c.setTotal)        // secret rares: number above the total
            if (!fuzzy) return n == num && totalOk
            return total != null && c.setTotal != null && VersionResolver.normNumber(c.setTotal.toString()) == total &&
                VersionResolver.numberClose(n, num)
        }
        // Japanese print: pick among the Japanese members
        if (text?.lang == "ja") {
            val ja = versions.filter { it.second == "ja" }.map { it.first }.distinct()
            if (ja.isNotEmpty()) {
                val pick = ja.firstOrNull { num.isNotEmpty() && numberMatches(it, "ja") }
                    ?: ja.firstOrNull { num.isNotEmpty() && numberMatches(it, "ja", fuzzy = true) } ?: ja.first()
                return cat.cardExact(pick, "ja")
            }
        }
        // set: the member whose printed number matches
        if (num.isNotEmpty()) {
            val ids = versions.filter { it.second != "ja" }.map { it.first }.distinct()
            fun matches(cid: String, fuzzy: Boolean) = versions.any { it.first == cid && numberMatches(cid, it.second, fuzzy) }
            val byNumber = ids.filter { matches(it, false) }.ifEmpty { ids.filter { matches(it, true) } }
            if (byNumber.isNotEmpty() && id !in byNumber) id = byNumber.first()
        }
        // language: printed words + name, among the languages this card exists in
        val langs = versions.filter { it.first == id }.map { it.second }.distinct()
        val scores = HashMap<String, Double>()
        for (l in langs) {
            var s = text?.langScores?.get(l) ?: 0.0
            val name = cat.cardExact(id, l)?.name
            val names = langs.mapNotNull { cat.cardExact(id, it)?.name }.toSet()
            if (text != null && name != null && names.size > 1 && text.nameText.isNotBlank()) {
                s += 2.0 * VersionResolver.nameSimilarity(text.nameText, name)
            }
            scores[l] = s
        }
        val bestLang = scores.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }?.key
            ?: "en".takeIf { it in langs } ?: entry?.let { p.entryLang[it] } ?: langs.first()
        return cat.cardExact(id, bestLang) ?: cat.card(id, "en")
    }

    private fun build(group: String, version: CardInfo, text: CardText?, conf: Confidence, alternatives: List<Candidate>,
                      locked: Boolean, fromPhoto: Boolean, preview: Bitmap?): Shown {
        val cat = catalog!!
        val english = if (version.lang == "en") null else cat.cardExact(version.id, "en") ?: cat.englishOf(group)
        val hasOwnPrice = version.printings.any { it.value != null }
        val priceCard = if (hasOwnPrice || english == null) version else english
        val langs = cat.versions(group).filter { it.first == version.id || it.second == "ja" }
            .mapNotNull { (id, l) -> cat.cardExact(id, l) }.filter { it.lang != version.lang || it.id != version.id }
        return Shown(group, version, english, priceCard, priceCard.printings.firstOrNull()?.kind, false, null, conf,
            alternatives, langs, text, locked, fromPhoto, preview, ++serial)
    }

    private fun showNew(group: String, r: Recognition, rgb: Mat, fromPhoto: Boolean, conf: Confidence) {
        val cat = catalog ?: return
        val p = pack ?: return
        val hit = r.hits.firstOrNull { it.group == group }
        val versions = cat.versions(group)
        val text = hit?.let { readText(rgb, r, it, tryJapanese = versions.any { v -> v.second == "ja" }) }
        lastText = text
        val version = resolveVersion(group, hit?.entry, text) ?: return
        val alternatives = r.hits.filter { it.group != group }.take(3).mapNotNull { h ->
            cat.card(p.entryCard[h.entry], p.entryLang[h.entry])?.let { Candidate(h.group, it, h.score, Recognizer.READINGS[h.reading]) }
        }
        foil?.reset()
        val shown = build(group, version, text, conf, alternatives, locked = false, fromPhoto = fromPhoto, preview = r.preview)
        _state.update { s -> s.copy(shown = shown, session = if (s.settings.youtube && !fromPhoto) addToSession(s, shown) else s.session) }
        maybeAlert(shown)
    }

    private fun trackFoil(current: Shown, r: Recognition, rgb: Mat) {
        val hit = r.best ?: return
        val quad = cardQuad(r, hit) ?: return
        val meter = foil ?: return
        meter.add(rgb, quad)
        val est = meter.estimate() ?: return
        var printing = current.printing
        if (!current.printingByUser) {
            val kinds = current.priceCard.printings.map { it.kind }
            printing = when (est.kind) {
                FoilEstimate.Kind.HOLO -> Printing.Kind.HOLO.takeIf { it in kinds } ?: printing
                FoilEstimate.Kind.REVERSE -> Printing.Kind.REVERSE.takeIf { it in kinds } ?: printing
                FoilEstimate.Kind.NORMAL -> kinds.firstOrNull { it == Printing.Kind.NORMAL || it == Printing.Kind.HOLO } ?: printing
                FoilEstimate.Kind.UNSURE -> printing
            }
        }
        _state.update { s ->
            val sh = s.shown?.takeIf { it.serial == current.serial } ?: return@update s
            val updated = sh.copy(foil = est, printing = printing)
            s.copy(shown = updated, session = if (s.settings.youtube) updateSessionValue(s.session, updated) else s.session)
        }
    }

    private fun publishLive(r: Recognition, w: Int, h: Int, conf: Confidence) {
        val cat = catalog ?: return
        val p = pack ?: return
        val quad = r.quad?.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }?.toFloatArray()
        val st = _state.value
        val debug = if (st.settings.showDebug || st.shown?.fromPhoto == true) DebugInfo(r.preview ?: st.shown?.preview, buildList {
            add("card found: ${r.detected}   margin %.3f (%s)".format(r.margin, conf))
            add(r.timings.entries.joinToString("  ") { "${it.key} ${it.value} ms" })
            r.hits.take(5).forEachIndexed { i, hit ->
                val name = cat.card(p.entryCard[hit.entry], "en")?.name ?: hit.group
                add("${i + 1}. %.3f  %s  %s  [%s]".format(hit.score, hit.group, name, Recognizer.READINGS[hit.reading]))
            }
            st.shown?.text?.let { t ->
                add("printed number: ${t.number ?: "?"}/${t.total ?: "?"}   language: ${t.lang ?: "?"} (+%.1f)".format(t.langMargin))
                add("language scores: " + t.langScores.entries.sortedByDescending { it.value }.take(4)
                    .joinToString(" ") { "${it.key} %.1f".format(it.value) })
                add("name bar: ${t.nameText.take(40)}")
            }
            st.shown?.foil?.let { f -> add("holo check: ${f.kind} (artwork %.2f, card %.2f, %d frames)".format(f.art, f.body, f.frames)) }
        }) else null
        _state.update { it.copy(liveQuad = quad, liveFrame = w to h, liveConfidence = conf, debug = debug ?: it.debug) }
    }

    // ------------------------------------------------------------------ session (YouTube mode)
    private fun sessionItem(s: UiState, sh: Shown) = SessionItem(
        sh.group, sh.version.name, sh.version.numberText, sh.value,
        (sh.value ?: 0.0) >= s.settings.threshold && s.settings.threshold > 0, sh.priceCard.thumbnailUrl ?: sh.version.thumbnailUrl,
    )

    private fun addToSession(s: UiState, sh: Shown): List<SessionItem> {
        if (sh.confidence == Confidence.UNSURE) return s.session
        if (s.session.lastOrNull()?.group == sh.group) return s.session
        return s.session + sessionItem(s, sh)
    }

    private fun updateSessionValue(session: List<SessionItem>, sh: Shown): List<SessionItem> {
        val last = session.lastOrNull() ?: return session
        if (last.group != sh.group) return session
        return session.dropLast(1) + last.copy(value = sh.value, hit = (sh.value ?: 0.0) >= _state.value.settings.threshold)
    }

    fun resetSession() = _state.update { it.copy(session = emptyList()) }
    fun undoLast() = _state.update { it.copy(session = it.session.dropLast(1)) }

    // ------------------------------------------------------------------ user choices
    /** The user says it is this other card: show it and keep it. */
    fun choose(c: Candidate) {
        synchronized(lock) { chooseLocked(c) }
    }

    private fun chooseLocked(c: Candidate) {
        val s = _state.value.shown ?: return
        tracker.lock(c.group)
        foil?.reset()
        val version = resolveVersion(c.group, null, lastText) ?: c.info
        val alternatives = (listOf(Candidate(s.group, s.version, 0f, "")) + s.alternatives).filter { it.group != c.group }
        val shown = build(c.group, version, lastText, Confidence.SURE, alternatives, locked = true, fromPhoto = s.fromPhoto,
            preview = s.preview)
        _state.update { st ->
            val session = if (st.settings.youtube && st.session.lastOrNull()?.group == s.group)
                st.session.dropLast(1) + sessionItem(st, shown) else st.session
            st.copy(shown = shown, session = session)
        }
        maybeAlert(shown)
    }

    fun chooseLanguage(info: CardInfo) = _state.update { st ->
        val s = st.shown ?: return@update st
        st.copy(shown = build(s.group, info, s.text, s.confidence, s.alternatives, true, s.fromPhoto, s.preview)
            .copy(serial = s.serial, printing = s.printing, printingByUser = s.printingByUser, foil = s.foil)
            .let { if (it.priceCard.printing(s.printing)?.kind != s.printing) it.copy(printing = it.priceCard.printings.firstOrNull()?.kind) else it })
            .also { tracker.lock(s.group) }
    }

    fun choosePrinting(kind: Printing.Kind) = _state.update { st ->
        val s = st.shown ?: return@update st
        val updated = s.copy(printing = kind, printingByUser = true, locked = true)
        tracker.lock(s.group)
        st.copy(shown = updated, session = if (st.settings.youtube) updateSessionValue(st.session, updated) else st.session)
    }

    /** Forget the current card and look for the next one. */
    fun next() {
        synchronized(lock) {
            tracker.reset()
            foil?.reset()
        }
        _state.update { it.copy(shown = null, paused = false) }
    }

    fun resume() {
        synchronized(lock) { tracker.reset() }
        _state.update { it.copy(paused = false, shown = it.shown?.takeIf { s -> !s.fromPhoto }) }
    }

    // ------------------------------------------------------------------ settings
    fun updateSettings(f: (Settings) -> Settings) {
        val new = f(_state.value.settings)
        prefs.edit()
            .putFloat("threshold", new.threshold.toFloat()).putBoolean("psa", new.showPsa)
            .putBoolean("debug", new.showDebug).putBoolean("youtube", new.youtube).putString("title", new.title)
            .apply()
        if (new.threshold != _state.value.settings.threshold) lastAlerted = null
        _state.update { it.copy(settings = new) }
    }

    private fun maybeAlert(sh: Shown) {
        if (_state.value.settings.youtube || sh.confidence == Confidence.UNSURE) return
        val threshold = _state.value.settings.threshold
        if (threshold <= 0) return
        // any printing above the threshold: a valuable reverse holo must not slip through
        val best = sh.priceCard.bestValue ?: return
        if (best < threshold || lastAlerted == sh.group) return
        lastAlerted = sh.group
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

    override fun onCleared() {
        recognizer?.close()
        resolver?.close()
        foil?.reset()
    }

    companion object {
        const val TAG = "CardScanner"
    }
}
