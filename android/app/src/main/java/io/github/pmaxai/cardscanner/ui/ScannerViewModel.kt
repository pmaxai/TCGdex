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
import io.github.pmaxai.cardscanner.data.Graded
import io.github.pmaxai.cardscanner.data.Population
import io.github.pmaxai.cardscanner.data.Pack
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.vision.CardText
import io.github.pmaxai.cardscanner.vision.FoilEstimate
import io.github.pmaxai.cardscanner.vision.FoilMeter
import io.github.pmaxai.cardscanner.vision.Hit
import io.github.pmaxai.cardscanner.vision.Prior
import io.github.pmaxai.cardscanner.vision.Readings
import io.github.pmaxai.cardscanner.vision.Recognition
import io.github.pmaxai.cardscanner.vision.Recognizer
import io.github.pmaxai.cardscanner.vision.ScanTracker
import io.github.pmaxai.cardscanner.vision.VersionResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class Confidence { SURE, LIKELY, UNSURE }

enum class SearchMode { LOOK_UP, REPLACE }

/** Another artwork the scanner considered. [image]: thumbnail of it (this version or another one with a picture). */
data class Candidate(val group: String, val info: CardInfo, val score: Float, val reading: String, val image: String? = null)

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
    /** the user picked this card or language: the printed text must not change it */
    val versionByUser: Boolean = false,
    /** every version of this artwork (sets, languages), the shown one included, for switching */
    val versions: List<CardInfo> = emptyList(),
    /** the card as the camera saw it, straightened: shown when there is no image (or no internet) */
    val scan: Bitmap? = null,
    /** looked up in the card search, not seen by the camera */
    val fromSearch: Boolean = false,
) {
    /**
     * Whose picture is shown: this version, else the same card in another language (same
     * artwork), else any other version of the artwork. Null: no version has an image, the
     * scanned card is shown instead.
     */
    val imageCard: CardInfo? get() = (listOfNotNull(version, priceCard, english) +
        versions.sortedBy { if (it.id == version.id) 0 else 1 }).firstOrNull { it.thumbnailUrl != null }
    /** position of the shown version in [versions] */
    val versionIndex: Int get() = versions.indexOfFirst { it.id == version.id && it.lang == version.lang }
    val selected: Printing? get() = priceCard.printing(printing)
    val value: Double? get() = selected?.value ?: priceCard.bestValue
    /** the most valuable other printing, if it is worth more than the selected one */
    val richerPrinting: Printing? get() = priceCard.printings
        .filter { it.kind != selected?.kind && (it.value ?: 0.0) > (value ?: 0.0) }.maxByOrNull { it.value ?: 0.0 }
    val pricedFromEnglish: Boolean get() = priceCard.id != version.id || priceCard.lang != version.lang
    /** PSA prices of the selected printing (eBay sales are kept apart per printing) */
    val graded: Graded? get() = priceCard.gradedFor(selected?.kind)
    /** PSA population (gem rate) of the selected printing, if known */
    val population: Population? get() = priceCard.populationFor(selected?.kind)
    /** the card has PSA sales, just not for the selected printing */
    val gradedOnlyOtherPrintings: Boolean get() = graded == null && priceCard.graded.isNotEmpty()
}

/** One card of the pack (YouTube mode): the card as shown, without the debug picture. */
data class SessionItem(val serial: Int, val time: Long, val shown: Shown) {
    val group: String get() = shown.group
    val name: String get() = shown.version.name
    val value: Double? get() = shown.value
}

data class Settings(
    val threshold: Double = 5.0,
    val showPsa: Boolean = true,
    val showDebug: Boolean = false,
    val youtube: Boolean = false,
    val title: String = "",
    /** how long the frame must stay green before the card is taken (0 = at once) */
    val triggerDelayMs: Int = 300,
    /** energy cards: 0 = recognise, 1 = skip common energy (packs' fillers), 2 = skip all energy */
    val energyFilter: Int = 0,
    /** a card must cover at least this share of the guide frame (percent, 0 = off): no "ghost" cards */
    val minFillPercent: Int = 0,
    /** screen orientation: "auto" (follows the phone), "portrait" or "landscape" (fixed) */
    val orientation: String = "auto",
    /** camera zoom (1 = no zoom) */
    val zoom: Float = 1f,
    /** height of the capture frame, percent of the camera picture */
    val guideSize: Int = 70,
) {
    val guideFraction: Double get() = guideSize / 100.0
}

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
    /** 0..1 while the frame is green and the card is about to be taken (null otherwise) */
    val liveProgress: Float? = null,
    val debug: DebugInfo? = null,
    val paused: Boolean = false,
    val settings: Settings = Settings(),
    val session: List<SessionItem> = emptyList(),
    val hasPsaData: Boolean = false,
    /** the data pack knows card categories (needed to skip energy cards) */
    val hasCategories: Boolean = false,
    /** grows with every double tap on the camera (shutter flash) */
    val flash: Int = 0,
    /** a short message for the user (toast), null when there is none */
    val message: String? = null,
    /** the camera's largest zoom (as far as the app uses it) */
    val maxZoom: Float = ScannerViewModel.MAX_ZOOM,
    /** after a double tap: the most likely cards to pick from (null: no list open) */
    val picks: List<Candidate>? = null,
    /** the card search is open: LOOK_UP shows any card, REPLACE puts it in place of the shown one */
    val search: SearchMode? = null,
) {
    val sessionValue: Double get() = session.sumOf { it.value ?: 0.0 }
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
    private var foilThreshold = 0.2
    private var foilSum = 0.0
    private var foilN = 0
    private var marginSure = 0.055f
    private var marginLikely = 0.02f
    private val busy = AtomicBoolean(false)
    /** double tap on the camera: take the card in the frame with the next frame, whatever the confidence */
    private val manualRequest = AtomicBoolean(false)
    /**
     * The card taken by hand or picked from the list (null: none). While it is shown, every
     * frame reads all outlines like the double tap did, and another card only replaces it
     * when the frame is sure it is not this one (ScanTracker.voteWhileKept).
     */
    @Volatile private var kept: String? = null
    /** cards the user said it isn't (the one replaced and its alternatives): they don't count while a card is kept */
    @Volatile private var rejected: Set<String> = emptySet()
    /** the list shown after the last double tap */
    @Volatile private var lastPicks: List<Candidate> = emptyList()
    private val lock = Any()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var serial = 0
    private var lastAlerted: String? = null
    private var lastText: CardText? = null
    @Volatile private var lastFill: Double? = null
    /** text recognition runs here, one card at a time, so a card shows up without waiting for it */
    private val textDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        _state.update {
            it.copy(settings = Settings(
                threshold = prefs.getFloat("threshold", 5f).toDouble(),
                showPsa = prefs.getBoolean("psa", true),
                showDebug = prefs.getBoolean("debug", false),
                youtube = prefs.getBoolean("youtube", false),
                title = prefs.getString("title", "") ?: "",
                triggerDelayMs = prefs.getInt("trigger_delay", 300),
                energyFilter = prefs.getInt("energy_filter", 0),
                minFillPercent = prefs.getInt("min_fill", 0),
                // before this setting: scanning in portrait, YouTube mode following the phone
                orientation = prefs.getString("orientation", null)
                    ?: if (prefs.getBoolean("youtube", false)) "auto" else "portrait",
                zoom = prefs.getFloat("zoom", 1f),
                guideSize = prefs.getInt("guide_size", 70),
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
                foilThreshold = p.metaFloat("foil_reverse", 0.2f).toDouble()
                tracker = ScanTracker(marginSure, marginLikely).also { it.delayMs = _state.value.settings.triggerDelayMs.toLong() }
                val psa = runCatching {
                    p.db.rawQuery("SELECT COUNT(*) FROM graded", null).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
                }.getOrDefault(false)
                val info = "${p.n} card images · data ${p.version.take(10)}" + if (psa) " · PSA prices" else ""
                _state.update { it.copy(loading = false, packInfo = info, hasPsaData = psa, hasCategories = cat.hasCategory) }
                applyEnergyFilter(_state.value.settings.energyFilter)
            } catch (t: Throwable) {
                Log.e(TAG, "loading failed", t)
                _state.update { it.copy(loading = false, error = t.message ?: t.toString()) }
            }
        }
    }

    // ------------------------------------------------------------------ frames
    /** width / height of the camera box on screen (0: not known yet) */
    @Volatile private var viewAspect = 0f

    /** The camera box changed size: frames are cut to what it shows. */
    fun setViewAspect(aspect: Float) { viewAspect = aspect }

    /**
     * The part of the frame the camera box shows (the preview fills the box and cuts off
     * the rest, centred), so the guide frame, the outline drawn and the analysed picture
     * agree for any box shape. Null: the frame already has the box's shape.
     */
    private fun visiblePart(rgb: Mat): Mat? {
        val a = viewAspect
        if (a <= 0f) return null
        val fw = rgb.cols()
        val fh = rgb.rows()
        val fa = fw.toFloat() / fh
        if (kotlin.math.abs(fa - a) / fa < 0.01f) return null
        val rect = if (a > fa) {
            val h = (fw / a).roundToInt().coerceIn(1, fh)
            org.opencv.core.Rect(0, (fh - h) / 2, fw, h)
        } else {
            val w = (fh * a).roundToInt().coerceIn(1, fw)
            org.opencv.core.Rect((fw - w) / 2, 0, w, fh)
        }
        val sub = rgb.submat(rect)
        return sub.clone().also { sub.release() }
    }

    /** Called on the camera's analysis thread with an upright RGB frame. Drops frames while busy. */
    fun onFrame(full: Mat) {
        if (recognizer == null) return
        if (_state.value.paused || !busy.compareAndSet(false, true)) return
        var crop: Mat? = null
        try {
            crop = visiblePart(full)
            analyse(crop ?: full)
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
        } finally {
            crop?.release()
            busy.set(false)
        }
    }

    private fun analyse(rgb: Mat) {
        val rec = recognizer ?: return
        val prior = Readings.guidePrior(rgb.cols(), rgb.rows(), _state.value.settings.guideFraction)
        if (manualRequest.getAndSet(false)) {
            takeByHand(rec, rgb, prior)
            return
        }
        val keep = kept
        val r0 = rec.recognise(rgb, prior, withPreview = _state.value.settings.showDebug, manual = keep != null)
        // too little of the frame covered by a card: treat as "no card" (no ghost cards)
        val minFill = _state.value.settings.minFillPercent
        val fill = r0.best?.let { rec.guideFill(r0, it, rgb.cols(), rgb.rows(), prior) }
        lastFill = fill
        val r = if (minFill > 0 && (fill ?: 0.0) * 100 < minFill) r0.withoutCard() else r0
        val conf = confidenceOf(r)
        synchronized(lock) {
            // the list after a double tap is open: the user is choosing, nothing is taken meanwhile
            if (_state.value.picks != null) return@synchronized
            val vote = ScanTracker.voteWhileKept(r.best, r.candidates, keep, marginSure)
                ?.takeIf { keep == null || it.group !in rejected }
            val changed = tracker.update(vote?.group, if (vote == null) 0f else r.margin)
            val current = _state.value.shown
            if (changed) {
                kept = null
                rejected = emptySet()
                lastPicks = emptyList()
                showNew(tracker.shown!!, r, rgb, fromPhoto = false, conf = conf)
            } else if (current != null && !current.fromPhoto && r.detected && r.best?.group == current.group) {
                trackFoil(current, r, rgb)
            }
        }
        publishLive(r, rgb.cols(), rgb.rows(), conf)
    }

    /** Double tap on the camera: the next frame is taken by hand. */
    fun manualTrigger() {
        if (_state.value.paused) return
        manualRequest.set(true)
        _state.update { it.copy(flash = it.flash + 1) }
    }

    /**
     * Take the card in the frame now: every outline the detector has plus the guide frame
     * itself are read, the best match is shown and kept, however unsure it is. The frame-fill
     * rule doesn't apply - the user says there is a card.
     */
    private fun takeByHand(rec: Recognizer, rgb: Mat, prior: Prior) {
        val r = rec.recognise(rgb, prior, withPreview = _state.value.settings.showDebug, manual = true)
        val best = r.best
        synchronized(lock) {
            if (best == null) {
                _state.update { it.copy(message = "No card found in the frame") }
                return@synchronized
            }
            tracker.lock(best.group)
            kept = best.group
            rejected = emptySet()
            showNew(best.group, r, rgb, fromPhoto = false, conf = Confidence.LIKELY)
            // the most likely cards to pick from, the shown one first
            val picks = r.candidates.mapNotNull { candidateOf(it) }
            lastPicks = picks
            if (picks.size > 1) _state.update { it.copy(picks = picks) }
        }
        publishLive(r, rgb.cols(), rgb.rows(), confidenceOf(r))
    }

    /** A hit as a card to pick: the version the scanner would show for it, with a picture. */
    private fun candidateOf(h: Hit): Candidate? {
        val cat = catalog ?: return null
        val p = pack ?: return null
        val info = resolveVersion(h.group, h.entry, null) ?: cat.card(p.entryCard[h.entry], p.entryLang[h.entry]) ?: return null
        val image = info.thumbnailUrl ?: cat.versions(h.group).firstNotNullOfOrNull { (id, l) -> cat.cardExact(id, l)?.thumbnailUrl }
        return Candidate(h.group, info, h.score, Recognizer.READINGS[h.reading], image)
    }

    /** Picked from the list after a double tap (the shown card: just close it). */
    fun pick(c: Candidate) {
        _state.update { it.copy(picks = null) }
        if (c.group != _state.value.shown?.group) choose(c)
    }

    fun closePicks() = _state.update { it.copy(picks = null) }

    fun messageShown() = _state.update { it.copy(message = null) }

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

    /**
     * Which exact card (set, language) of this artwork group the photo shows.
     * Asian prints (Japanese, Chinese) are only chosen when the printed text is clearly
     * Japanese or Chinese; otherwise the western version is shown, even when the
     * picture matched an Asian scan best.
     */
    private fun resolveVersion(group: String, entry: Int?, text: CardText?): CardInfo? {
        val cat = catalog ?: return null
        val p = pack ?: return null
        val versions = cat.versions(group)
        if (versions.isEmpty()) return cat.card(group, "en")
        val entryCard = entry?.let { p.entryCard[it] }
        val entryLang = entry?.let { p.entryLang[it] }
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
        fun byNumber(pool: List<Pair<String, String>>): String? {
            if (num.isEmpty()) return null
            // the card whose scan matched the picture comes first when several sets have this number
            val ids = (listOfNotNull(entryCard) + pool.map { it.first }).distinct().filter { id -> pool.any { it.first == id } }
            fun m(cid: String, fuzzy: Boolean) = pool.any { it.first == cid && numberMatches(cid, it.second, fuzzy) }
            val exact = ids.firstOrNull { m(it, false) }
            // a bare number without "/total" ("4") is weak: it may confirm the matched card, but
            // not move to another set (Base Set 2's Charizard is also no. 4, worth a fraction)
            if (exact != null && exact != entryCard && total == null && num.all { it.isDigit() }) return null
            return exact ?: ids.firstOrNull { m(it, true) }
        }
        // Asian print: only when the text says so
        val asianLangs = when (text?.lang) {
            "ja" -> listOf("ja")
            "zh" -> listOf("zh-tw", "zh-cn")
            else -> emptyList()
        }
        if (asianLangs.isNotEmpty()) {
            val pool = versions.filter { it.second in asianLangs }
            if (pool.isNotEmpty()) {
                val ids = pool.map { it.first }.distinct()
                val id = byNumber(pool) ?: entryCard?.takeIf { it in ids } ?: ids.first()
                val langs = pool.filter { it.first == id }.map { it.second }
                val lang = entryLang?.takeIf { entryCard == id && it in langs } ?: asianLangs.first { it in langs }
                return cat.cardExact(id, lang)
            }
        }
        // western print (also when the text could not be read)
        val pool = versions.filter { it.second !in ASIAN }.ifEmpty { versions }
        val ids = pool.map { it.first }.distinct()
        val id = byNumber(pool) ?: entryCard?.takeIf { it in ids } ?: group.takeIf { it in ids } ?: ids.first()
        // language: printed words + name, among the languages this card exists in
        val langs = pool.filter { it.first == id }.map { it.second }.distinct()
        val scores = HashMap<String, Double>()
        val names = langs.mapNotNull { cat.cardExact(id, it)?.name }.toSet()
        for (l in langs) {
            var sc = text?.langScores?.get(l) ?: 0.0
            val name = cat.cardExact(id, l)?.name
            if (text != null && name != null && names.size > 1 && text.nameText.isNotBlank()) {
                sc += 2.0 * VersionResolver.nameSimilarity(text.nameText, name)
            }
            scores[l] = sc
        }
        val bestLang = scores.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }?.key
            ?: "en".takeIf { it in langs } ?: entryLang?.takeIf { it in langs } ?: langs.first()
        return cat.cardExact(id, bestLang) ?: cat.card(id, "en")
    }

    private fun build(group: String, version: CardInfo, text: CardText?, conf: Confidence, alternatives: List<Candidate>,
                      locked: Boolean, fromPhoto: Boolean, preview: Bitmap?, scan: Bitmap?): Shown {
        val cat = catalog!!
        val english = if (version.lang == "en") null else cat.cardExact(version.id, "en") ?: cat.englishOf(group)
        val hasOwnPrice = version.printings.any { it.value != null }
        val priceCard = if (hasOwnPrice || english == null) version else english
        val all = cat.versions(group).sortedBy { if (it.second in ASIAN) 1 else 0 }
            .mapNotNull { (id, l) -> cat.cardExact(id, l) }
            .let { v -> if (v.none { it.id == version.id && it.lang == version.lang }) listOf(version) + v else v }
        val langs = all.filter { (it.id == version.id || it.lang in ASIAN) && (it.lang != version.lang || it.id != version.id) }
        return Shown(group, version, english, priceCard, priceCard.printings.firstOrNull()?.kind, false, null, conf,
            alternatives, langs, text, locked, fromPhoto, preview, ++serial, versions = all, scan = scan)
    }

    private fun showNew(group: String, r: Recognition, rgb: Mat, fromPhoto: Boolean, conf: Confidence) {
        val cat = catalog ?: return
        val p = pack ?: return
        val hit = r.hits.firstOrNull { it.group == group }
        val versions = cat.versions(group)
        // shown at once from the picture; the printed text (language, set number) refines it a moment later
        val version = resolveVersion(group, hit?.entry, null) ?: return
        val alternatives = r.hits.filter { it.group != group }.take(3).mapNotNull { h ->
            cat.card(p.entryCard[h.entry], p.entryLang[h.entry])?.let { Candidate(h.group, it, h.score, Recognizer.READINGS[h.reading]) }
        }
        foil?.reset()
        foilSum = 0.0
        foilN = 0
        lastText = null
        // the card as the camera saw it: the picture when the database has none (or there is no internet)
        val scan = hit?.let { h -> runCatching { recognizer?.cardBitmap(rgb, r, h) }.onFailure { Log.w(TAG, "card picture failed", it) }.getOrNull() }
        var shown = build(group, version, null, conf, alternatives, locked = false, fromPhoto = fromPhoto, preview = r.preview, scan = scan)
        if (hit != null) staticFoil(rgb, r, hit, shown)?.let { est -> shown = shown.copy(foil = est) }
        val first = shown
        _state.update { s -> s.copy(shown = first, picks = null,
            session = if (s.settings.youtube && !fromPhoto) addToSession(s, first) else s.session) }
        if (hit == null) { maybeAlert(first); return }
        val frame = rgb.clone()
        viewModelScope.launch(textDispatcher) {
            val text = try {
                readText(frame, r, hit, tryJapanese = versions.any { v -> v.second in ASIAN })
            } finally {
                frame.release()
            }
            synchronized(lock) { refine(first.serial, group, hit, text) }
        }
    }

    /** The printed text has been read: switch to the exact version (language, set) if it differs. */
    private fun refine(serialShown: Int, group: String, hit: Hit, text: CardText?) {
        val cur = _state.value.shown?.takeIf { it.serial == serialShown } ?: return
        lastText = text
        if (cur.versionByUser) return
        val version = resolveVersion(group, hit.entry, text)
        val updated = if (version == null || (version.id == cur.version.id && version.lang == cur.version.lang)) {
            cur.copy(text = text)
        } else {
            val b = build(group, version, text, cur.confidence, cur.alternatives, locked = cur.locked, fromPhoto = cur.fromPhoto,
                preview = cur.preview, scan = cur.scan)
            val keep = cur.printing.takeIf { cur.printingByUser && b.priceCard.printings.any { it.kind == cur.printing } }
            b.copy(serial = cur.serial, foil = cur.foil, printing = keep ?: b.printing, printingByUser = keep != null)
        }
        _state.update { s -> s.copy(shown = updated, session = replaceInSession(s.session, updated)) }
        maybeAlert(updated)
    }

    /** pack entry of each (card id, language), to compare with the scan of the version shown */
    private val entryOf: Map<String, Int> by lazy {
        val p = pack ?: return@lazy emptyMap()
        HashMap<String, Int>(p.n * 2).also { m -> for (i in 0 until p.n) m.putIfAbsent("${p.entryLang[i]}/${p.entryCard[i]}", i) }
    }

    /**
     * Adds this frame's single-frame foil score (mean over the card's frames). Measured and
     * shown under Details only: on real photos it does not yet separate reverse holos from
     * normal cards reliably, so it never changes the printing.
     */
    private fun staticFoil(rgb: Mat, r: Recognition, hit: Hit, s: Shown): FoilEstimate? {
        val ref = entryOf["${s.version.lang}/${s.version.id}"] ?: entryOf["en/${s.version.id}"]
            ?: entryOf["de/${s.version.id}"] ?: hit.entry
        val res = runCatching { recognizer?.foilScore(rgb, r, hit, pack?.texture, ref) }
            .onFailure { Log.w(TAG, "foil check failed", it) }.getOrNull() ?: return null
        foilSum += res.score
        foilN++
        return FoilEstimate(FoilEstimate.Kind.UNSURE, res.blank, res.text, 0, foilSum / foilN, foilN)
    }

    /** The printing to select for a foil verdict (unless the user picked one). */
    private fun autoPrinting(s: Shown, kind: FoilEstimate.Kind): Printing.Kind? {
        if (s.printingByUser) return s.printing
        val kinds = s.priceCard.printings.map { it.kind }
        return when (kind) {
            FoilEstimate.Kind.HOLO -> Printing.Kind.HOLO.takeIf { it in kinds } ?: s.printing
            FoilEstimate.Kind.REVERSE -> Printing.Kind.REVERSE.takeIf { it in kinds } ?: s.printing
            FoilEstimate.Kind.NORMAL -> kinds.firstOrNull { it == Printing.Kind.NORMAL || it == Printing.Kind.HOLO } ?: s.printing
            FoilEstimate.Kind.UNSURE -> s.printing
        }
    }

    private fun trackFoil(current: Shown, r: Recognition, rgb: Mat) {
        val hit = r.best ?: return
        val still = staticFoil(rgb, r, hit, current)
        val quad = cardQuad(r, hit)
        val meter = foil
        val motion = if (quad != null && meter != null) { meter.add(rgb, quad); meter.estimate() } else null
        // the shimmer while tilting decides (experimental); the still-frame score is only shown
        val est = when {
            motion != null -> motion.copy(staticScore = still?.staticScore, staticFrames = still?.staticFrames ?: 0)
            else -> still ?: return
        }
        val printing = autoPrinting(current, est.kind)
        _state.update { s ->
            val sh = s.shown?.takeIf { it.serial == current.serial } ?: return@update s
            val updated = sh.copy(foil = est, printing = printing)
            s.copy(shown = updated, session = replaceInSession(s.session, updated))
        }
    }

    private fun publishLive(r: Recognition, w: Int, h: Int, conf: Confidence) {
        val cat = catalog ?: return
        val p = pack ?: return
        val quad = r.quad?.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }?.toFloatArray()
        val st = _state.value
        val debug = if (st.settings.showDebug || st.shown?.fromPhoto == true) DebugInfo(r.preview ?: st.shown?.preview, buildList {
            add("card found: ${r.detected}   margin %.3f (%s)".format(r.margin, conf) +
                (lastFill?.let { "   fills %d%% of the frame".format((it * 100).toInt()) } ?: "") +
                (st.settings.minFillPercent.takeIf { it > 0 }?.let { " (needs $it%)" } ?: ""))
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
            st.shown?.foil?.let { f ->
                add("holo check: ${f.kind}" + (f.staticScore?.let { " – still-frame foil score %.2f (normal cards reach %.2f, %d frames)".format(it, foilThreshold, f.staticFrames) } ?: "") +
                    if (f.frames > 0) " – shimmer: artwork %.2f, card %.2f, %d frames".format(f.art, f.body, f.frames) else "")
            }
        }) else null
        val delay = st.settings.triggerDelayMs
        val progress = if (delay > 0) tracker.streakMillis()?.let { (it.toFloat() / delay).coerceIn(0f, 1f) } else null
        _state.update { it.copy(liveQuad = quad, liveFrame = w to h, liveConfidence = conf, liveProgress = progress,
            debug = debug ?: it.debug) }
    }

    // ------------------------------------------------------------------ session (YouTube mode)
    private fun addToSession(s: UiState, sh: Shown): List<SessionItem> {
        if (sh.confidence == Confidence.UNSURE) return s.session
        if (s.session.lastOrNull()?.group == sh.group) return s.session
        return s.session + SessionItem(sh.serial, System.currentTimeMillis(), sh.copy(preview = null, scan = null))
    }

    /** The pack entry of a card that changed (printing, language, text read, or another card chosen). */
    private fun replaceInSession(session: List<SessionItem>, sh: Shown, oldSerial: Int = sh.serial): List<SessionItem> =
        session.map { if (it.serial == oldSerial) it.copy(serial = sh.serial, shown = sh.copy(preview = null, scan = null)) else it }

    fun resetSession() = _state.update { it.copy(session = emptyList()) }

    /**
     * +1: the shown card goes into the pack once more (two of the same card in a row are
     * otherwise counted once). The copy is a card of its own: its printing can differ, and
     * swiping it away leaves the first one in the pack. Also adds a card the scanner wasn't
     * sure enough about to count.
     */
    fun addAgain() = synchronized(lock) {
        _state.update { st ->
            val s = st.shown ?: return@update st
            val copy = s.copy(serial = ++serial)
            st.copy(shown = copy, session = st.session + SessionItem(copy.serial, System.currentTimeMillis(), copy.copy(preview = null, scan = null)))
        }
    }
    fun undoLast() = _state.update { it.copy(session = it.session.dropLast(1)) }

    /** Swiped away: the card leaves the pack. It is not taken again until another card was in front of the camera. */
    fun dismissShown() {
        kept = null
        _state.update { st ->
            val s = st.shown ?: return@update st
            st.copy(shown = null, picks = null, session = st.session.filter { it.serial != s.serial })
        }
    }

    /** Writes the pack as CSV; returns the number of cards. */
    fun savePack(resolver: android.content.ContentResolver, uri: android.net.Uri): Result<Int> = runCatching {
        val st = _state.value
        val csv = PackCsv.build(st.session, st.settings, java.util.Locale.getDefault())
        resolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("cannot write the file")
        st.session.size
    }.onFailure { Log.e(TAG, "saving the pack failed", it) }

    // ------------------------------------------------------------------ card search
    fun openSearch(mode: SearchMode) = _state.update { it.copy(search = mode, picks = null) }
    fun closeSearch() = _state.update { it.copy(search = null) }

    /** Cards matching the search words (name in any language, number, set), with a picture to show. */
    suspend fun search(query: String): List<Candidate> = withContext(Dispatchers.IO) {
        val cat = catalog ?: return@withContext emptyList()
        runCatching {
            cat.search(query).map { info ->
                val group = cat.groupOf(info.id)
                val image = info.thumbnailUrl ?: cat.cardExact(info.id, "en")?.thumbnailUrl
                    ?: cat.versions(group).firstNotNullOfOrNull { (id, l) -> cat.cardExact(id, l)?.thumbnailUrl }
                Candidate(group, info, 0f, "search", image)
            }
        }.onFailure { Log.e(TAG, "search failed", it) }.getOrDefault(emptyList())
    }

    /** A card from the search, the way the search was opened. */
    fun pickFromSearch(c: Candidate) {
        val mode = _state.value.search
        _state.update { it.copy(search = null) }
        if (mode == SearchMode.REPLACE && _state.value.shown != null) replaceWith(c) else lookUp(c)
    }

    /**
     * Looked up without the card in front of the camera: shown, the camera pauses (tap the
     * picture to scan again). Not added to the pack; +1 adds it.
     */
    fun lookUp(c: Candidate) = synchronized(lock) {
        tracker.reset()
        kept = null
        rejected = emptySet()
        foil?.reset()
        lastText = null
        val shown = build(c.group, c.info, null, Confidence.SURE, emptyList(), locked = true, fromPhoto = true,
            preview = null, scan = null).copy(versionByUser = true, fromSearch = true)
        _state.update { it.copy(shown = shown, paused = true, picks = null) }
    }

    /** The shown card is this one (found in the search): it takes its place, also in the pack. */
    fun replaceWith(c: Candidate) = synchronized(lock) {
        val s = _state.value.shown ?: return@synchronized lookUp(c)
        tracker.lock(c.group)
        kept = c.group
        rejected = rejectedBy(s, c.group)
        val alternatives = (listOf(Candidate(s.group, s.version, 0f, "")) + s.alternatives).filter { it.group != c.group }
        val shown = build(c.group, c.info, lastText, Confidence.SURE, alternatives, locked = true, fromPhoto = s.fromPhoto,
            preview = s.preview, scan = s.scan).copy(versionByUser = true, fromSearch = s.fromSearch)
        _state.update { st -> st.copy(shown = shown, picks = null, session = replaceInSession(st.session, shown, oldSerial = s.serial)) }
        maybeAlert(shown)
    }

    /** What the user said the card isn't: the shown card, its alternatives, the list after a double tap. */
    private fun rejectedBy(s: Shown, chosen: String): Set<String> =
        (listOf(s.group) + s.alternatives.map { it.group } + lastPicks.map { it.group }).filter { it != chosen }.toSet()

    // ------------------------------------------------------------------ user choices
    /** The user says it is this other card: show it and keep it. */
    fun choose(c: Candidate) {
        synchronized(lock) { chooseLocked(c) }
    }

    private fun chooseLocked(c: Candidate) {
        val s = _state.value.shown ?: return
        tracker.lock(c.group)
        kept = c.group
        rejected = rejectedBy(s, c.group)
        foil?.reset()
        val version = resolveVersion(c.group, null, lastText) ?: c.info
        val alternatives = (listOf(Candidate(s.group, s.version, 0f, "")) + s.alternatives).filter { it.group != c.group }
        // another artwork: the picture of the scanned card still shows what was in front of the camera
        val shown = build(c.group, version, lastText, Confidence.SURE, alternatives, locked = true, fromPhoto = s.fromPhoto,
            preview = s.preview, scan = s.scan).copy(versionByUser = true)
        _state.update { st -> st.copy(shown = shown, session = replaceInSession(st.session, shown, oldSerial = s.serial)) }
        maybeAlert(shown)
    }

    /** Another version of the same artwork (set, language): same card, its own prices. */
    fun chooseVersion(info: CardInfo) = chooseLanguage(info)

    /** Step through the versions of the shown card (+1 next, -1 previous). */
    fun stepVersion(step: Int) {
        val s = _state.value.shown ?: return
        if (s.versions.size < 2) return
        val i = s.versionIndex.coerceAtLeast(0)
        chooseVersion(s.versions[((i + step) % s.versions.size + s.versions.size) % s.versions.size])
    }

    fun chooseLanguage(info: CardInfo) = _state.update { st ->
        val s = st.shown ?: return@update st
        val shown = build(s.group, info, s.text, s.confidence, s.alternatives, true, s.fromPhoto, s.preview, s.scan)
            .copy(serial = s.serial, printing = s.printing, printingByUser = s.printingByUser, foil = s.foil, versionByUser = true)
            .let { if (it.priceCard.printing(s.printing)?.kind != s.printing) it.copy(printing = it.priceCard.printings.firstOrNull()?.kind) else it }
        tracker.lock(s.group)
        st.copy(shown = shown, session = replaceInSession(st.session, shown))
    }

    fun choosePrinting(kind: Printing.Kind) = _state.update { st ->
        val s = st.shown ?: return@update st
        val updated = s.copy(printing = kind, printingByUser = true, locked = true)
        tracker.lock(s.group)
        st.copy(shown = updated, session = replaceInSession(st.session, updated))
    }

    /** Forget the current card and look for the next one. */
    fun next() {
        synchronized(lock) {
            tracker.reset()
            kept = null
            rejected = emptySet()
            foil?.reset()
            foilSum = 0.0
            foilN = 0
        }
        _state.update { it.copy(shown = null, paused = false, picks = null) }
    }

    fun resume() {
        synchronized(lock) { tracker.reset(); kept = null }
        _state.update { it.copy(paused = false, shown = it.shown?.takeIf { s -> !s.fromPhoto }) }
    }

    // ------------------------------------------------------------------ settings
    fun updateSettings(f: (Settings) -> Settings) {
        val new = f(_state.value.settings)
        prefs.edit()
            .putFloat("threshold", new.threshold.toFloat()).putBoolean("psa", new.showPsa)
            .putBoolean("debug", new.showDebug).putBoolean("youtube", new.youtube).putString("title", new.title)
            .putInt("trigger_delay", new.triggerDelayMs)
            .putInt("energy_filter", new.energyFilter).putInt("min_fill", new.minFillPercent)
            .putString("orientation", new.orientation).putFloat("zoom", new.zoom).putInt("guide_size", new.guideSize)
            .apply()
        tracker.delayMs = new.triggerDelayMs.toLong()
        if (new.energyFilter != _state.value.settings.energyFilter) applyEnergyFilter(new.energyFilter)
        if (new.threshold != _state.value.settings.threshold) lastAlerted = null
        _state.update { it.copy(settings = new) }
    }

    private var zoomSave: kotlinx.coroutines.Job? = null

    /** Pinch or zoom setting: applied at once, saved when the fingers rest. */
    fun setZoom(z: Float) {
        val v = z.coerceIn(1f, _state.value.maxZoom)
        _state.update { it.copy(settings = it.settings.copy(zoom = v)) }
        zoomSave?.cancel()
        zoomSave = viewModelScope.launch { delay(500); prefs.edit().putFloat("zoom", v).apply() }
    }

    /** Pinch: zoom by this factor (reads the current zoom at once, so fast pinches add up). */
    fun zoomBy(factor: Float) = setZoom(_state.value.settings.zoom * factor)

    /** The camera says how far it can zoom. */
    fun setMaxZoom(max: Float) {
        val m = max.coerceIn(1f, MAX_ZOOM)
        if (m != _state.value.maxZoom) _state.update {
            it.copy(maxZoom = m, settings = it.settings.copy(zoom = it.settings.zoom.coerceAtMost(m)))
        }
    }

    /** Energy cards the user switched off are never matched (so they can't be shown either). */
    private fun applyEnergyFilter(mode: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val p = pack ?: return@launch
            val cat = catalog ?: return@launch
            val rec = recognizer ?: return@launch
            if (mode == 0) { rec.setExcluded(null); return@launch }
            val ids = cat.energyIds(commonOnly = mode == 1)
            val mask = BooleanArray(p.n) { p.entryCard[it] in ids }
            rec.setExcluded(mask)
            Log.i(TAG, "energy filter $mode: ${mask.count { it }} of ${p.n} card images skipped")
        }
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
        textDispatcher.close()
        foil?.reset()
    }

    companion object {
        const val TAG = "CardScanner"
        /** further than this the analysed picture gets too coarse */
        const val MAX_ZOOM = 5f
        /** languages with their own set ids and script */
        val ASIAN = setOf("ja", "ko", "zh-tw", "zh-cn", "th")
    }
}
