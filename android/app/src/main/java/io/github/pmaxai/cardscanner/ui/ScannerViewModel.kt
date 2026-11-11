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
import io.github.pmaxai.cardscanner.data.PriceHistory
import io.github.pmaxai.cardscanner.trading.Cart
import io.github.pmaxai.cardscanner.trading.CartLine
import io.github.pmaxai.cardscanner.trading.Condition
import io.github.pmaxai.cardscanner.trading.LineStatus
import io.github.pmaxai.cardscanner.trading.Reference
import io.github.pmaxai.cardscanner.trading.Side
import io.github.pmaxai.cardscanner.trading.TradingBook
import io.github.pmaxai.cardscanner.trading.TradingCsv
import io.github.pmaxai.cardscanner.trading.TradingMath
import io.github.pmaxai.cardscanner.trading.TradingStore
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
import io.github.pmaxai.cardscanner.update.AppRelease
import io.github.pmaxai.cardscanner.BuildConfig
import io.github.pmaxai.cardscanner.license.LicenceApi
import io.github.pmaxai.cardscanner.recording.CameraRecording
import io.github.pmaxai.cardscanner.recording.EventLog
import io.github.pmaxai.cardscanner.recording.RecordingController
import io.github.pmaxai.cardscanner.license.LicenceCheck
import io.github.pmaxai.cardscanner.license.LicenceException
import io.github.pmaxai.cardscanner.license.LicenceKey
import io.github.pmaxai.cardscanner.license.LicenceStatus
import io.github.pmaxai.cardscanner.license.LicenceStore
import io.github.pmaxai.cardscanner.shop.Money
import io.github.pmaxai.cardscanner.shop.PendingWrite
import io.github.pmaxai.cardscanner.shop.ProductPhoto
import io.github.pmaxai.cardscanner.shop.ShopClient
import io.github.pmaxai.cardscanner.shop.ShopException
import io.github.pmaxai.cardscanner.shop.ShopProduct
import io.github.pmaxai.cardscanner.shop.ShopStore
import io.github.pmaxai.cardscanner.shop.ShopTerms
import io.github.pmaxai.cardscanner.shop.ServerClock
import io.github.pmaxai.cardscanner.shop.ShopCredentials
import io.github.pmaxai.cardscanner.shop.shopWarningText
import io.github.pmaxai.cardscanner.update.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class Confidence { SURE, LIKELY, UNSURE }

enum class SearchMode { LOOK_UP, REPLACE }

/** Another artwork the scanner considered. [image]: thumbnail of it (this version or another one with a picture). */
data class Candidate(val group: String, val info: CardInfo, val score: Float, val reading: String, val image: String? = null)

/** The card in the result panel. */
/** The printing picked, only if the price data has it (else null: a reverse holo without prices has no market value). */
/** The printing a card starts with: Normal whenever the card has one, else the first it has. */
fun defaultPrinting(card: CardInfo): Printing.Kind? =
    card.printings.firstOrNull { it.kind == Printing.Kind.NORMAL }?.kind ?: card.printings.firstOrNull()?.kind

val Shown.pickedPrinting: Printing? get() = if (printing == null) selected else priceCard.printings.firstOrNull { it.kind == printing }

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
    /** price history of [priceCard] (null: none in the data pack) */
    val history: PriceHistory? = null,
) {
    /**
     * Whose picture is shown: this version, else the same card in another language (same
     * artwork), else any other version of the artwork. Null: no version has an image, the
     * scanned card is shown instead.
     */
    val imageCard: CardInfo? get() = (listOfNotNull(version, priceCard, english) +
        versions.sortedBy { if (it.id == version.id) 0 else 1 }).firstOrNull { it.thumbnailUrl != null }
    /** a picture of this card as printed in its language (TCGdex's are per language; the second source's are English) */
    val hasPictureInItsLanguage: Boolean get() = version.imageUrl != null || (version.lang == "en" && version.highUrl != null)
    /** the large picture of this card in its language, as the app shows it; for the shop (null: none, a photo is needed) */
    val pictureUrl: String? get() = version.highUrl?.takeIf { hasPictureInItsLanguage }
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

/** The one hit rule: the HIT badge, the pack bar, the pack CSV and the recording's log all ask this. */
fun isHit(value: Double?, threshold: Double): Boolean = threshold > 0 && (value ?: 0.0) >= threshold

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
    /** Trading Mode (card shows): price check, carts per booth, deals */
    val trading: Boolean = false,
    /** condition a newly scanned card starts with in Trading Mode */
    val tradingCondition: Condition = Condition.NM,
    /** Cardmarket price the market value starts from */
    val reference: Reference = Reference.TREND,
    /** Webshop Mode: look cards up in the Pokeshop and book copies into its stock */
    val webshop: Boolean = false,
    /** what one tap on − / + beside a price changes it by (EUR) */
    val priceStep: Double = 0.5,
) {
    val guideFraction: Double get() = guideSize / 100.0
    val mode: AppMode get() = when {
        youtube -> AppMode.YOUTUBE; trading -> AppMode.TRADING; webshop -> AppMode.WEBSHOP; else -> AppMode.SCANNER
    }
    fun withMode(m: AppMode) = copy(youtube = m == AppMode.YOUTUBE, trading = m == AppMode.TRADING, webshop = m == AppMode.WEBSHOP)
}

enum class AppMode(val label: String, val about: String) {
    SCANNER("Scanner", "flea market: spot the valuable card fast"),
    TRADING("Trading", "card show: judge prices, negotiate, carts per booth"),
    WEBSHOP("Webshop", "your Pokeshop: see what's in stock, add copies with price and box, sell a customer's order"),
    YOUTUBE("YouTube", "full screen, price reveal, pack value"),
}

/** Webshop Mode: what goes into the shop for the shown card (the user fills it in; nothing is booked by itself). */
data class ShopForm(
    val language: String = "EN",
    val languageByUser: Boolean = false,
    val condition: String = "NM",
    val quantity: Int = 1,
    /** selling price, EUR; suggested from the market value (or the shop's price) until the user types one */
    val price: Double? = null,
    val priceByUser: Boolean = false,
    /** storage box 1–999 */
    val box: Int? = null,
    /** shown on the product page (description) of a new product */
    val comment: String = "",
)

/**
 * The price a new product is suggested at: its market value, but never below the minimum selling
 * price. Without a market value there is no suggestion (the card may be worth anything).
 */
fun suggestedPrice(market: Double?, minPrice: Double?): Double? =
    market?.let { m -> if (minPrice != null && m < minPrice) minPrice else m }

/** Webshop Mode, Sell: one line of a customer's order (copies of one product out of one box). */
data class ShopOrderLine(
    val sku: String,
    val name: String,
    /** "DE · NM · Holo" */
    val detail: String,
    val box: String,
    val quantity: Int,
    /** the shop's price of one copy, EUR (null: none) */
    val price: Double?,
    /** the last attempt to take it out failed: why */
    val error: String? = null,
) {
    val total: Double? get() = price?.let { it * quantity }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("sku", sku).put("name", name).put("detail", detail).put("box", box)
        .put("quantity", quantity).put("price", price ?: org.json.JSONObject.NULL)

    companion object {
        fun from(o: org.json.JSONObject) = ShopOrderLine(o.getString("sku"), o.optString("name"), o.optString("detail"), o.getString("box"),
            o.optInt("quantity", 1), if (o.isNull("price")) null else o.optDouble("price"))
    }
}

/** A completed sale: what was taken out (for Undo). */
data class ShopSale(val text: String, val lines: List<ShopOrderLine>, val undone: Boolean = false)

/** A booking done in this session, for its confirmation and Undo. */
/** A product picture taken with the camera: for [cardRef], as JPEG, and small for the screen. */
class ShopPhoto(val cardRef: String, val jpeg: ByteArray, val thumb: android.graphics.Bitmap)

data class ShopBooking(val text: String, val warnings: List<String>, val sku: String, val delta: Int, val box: String,
                       val undone: Boolean = false)

data class ShopState(
    /** host, key id and secret are saved */
    val configured: Boolean = false,
    val host: String = "",
    val keyId: String = "",
    val hasSecret: Boolean = false,
    /** the lowest price a new product is suggested at, EUR (null: none) */
    val minPrice: Double? = null,
    /** the last connection test: its text, and whether it worked (null: not tested) */
    val connection: String? = null,
    val connectionOk: Boolean? = null,
    val testing: Boolean = false,
    /** the card looked up in the shop, and its products there (null: not looked up yet) */
    val cardRef: String? = null,
    val loading: Boolean = false,
    val products: List<ShopProduct>? = null,
    /** the shop's card catalogue has a picture of the card (null: not known) */
    val catalogPicture: Boolean? = null,
    /** a product picture taken with the camera for the shown card; it goes up with the next booking */
    val photo: ShopPhoto? = null,
    val photoBusy: Boolean = false,
    /** about the product picture: why it couldn't be taken or didn't go up */
    val photoNote: String? = null,
    val lookupError: String? = null,
    val form: ShopForm = ShopForm(),
    /** a booking is on its way */
    val busy: Boolean = false,
    /** the last booking failed; [retryable]: sending it again (same idempotency key) may work */
    val error: String? = null,
    val retryable: Boolean = false,
    val booking: ShopBooking? = null,
    /** the shop doesn't know the card: names to send along (German, English), for the user to confirm */
    val unknownCard: Pair<String, String>? = null,
    /** Sell: a customer's order is collected (copies come out of stock), instead of Stock in */
    val selling: Boolean = false,
    /** Sell: the product, box and copies picked for the shown card */
    val sellSku: String? = null,
    val sellBox: String? = null,
    val sellQty: Int = 1,
    /** Sell: the customer's order so far (kept on the phone until it's sold or cleared) */
    val order: List<ShopOrderLine> = emptyList(),
    val orderBusy: Boolean = false,
    /** the sale failed as a whole; [orderRetryable]: sending it again (same key) may work */
    val orderError: String? = null,
    val orderRetryable: Boolean = false,
    /** the last completed sale, for its confirmation and Undo */
    val sale: ShopSale? = null,
) {
    val orderCopies: Int get() = order.sumOf { it.quantity }
    val orderTotal: Double get() = order.sumOf { it.total ?: 0.0 }
}

/** A newer build of the app on GitHub, and how far its download / install has got. */
data class UpdateState(
    val release: AppRelease,
    /** 0..1 while downloading */
    val progress: Float? = null,
    /** downloaded: the installer was opened (tap again if it was cancelled) */
    val apk: java.io.File? = null,
    /** Android first needs "allow from this source" for Card Scanner (Settings was opened) */
    val needsPermission: Boolean = false,
    val error: String? = null,
)

/** Trading Mode: what the user set for the shown card before it goes into a cart. */
data class DealDraft(val condition: Condition = Condition.NM, val asking: Double? = null)

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
    /** Trading Mode: the last scanned cards, newest last */
    val tray: List<SessionItem> = emptyList(),
    /** Trading Mode: carts (open ones and the paid deal history) */
    val book: TradingBook = TradingBook(),
    /** Trading Mode: condition and asking price of the shown card */
    val draft: DealDraft = DealDraft(),
    /** Trading Mode: + Cart puts the card on this side (I get: buying, I give: your card for a trade or sale) */
    val scanTarget: Side = Side.GET,
    /** the settings screen is open on this page (null: closed) */
    val settingsPage: SettingsPage? = null,
    /** Webshop Mode: the connection, the shown card in the shop, the booking form */
    val shop: ShopState = ShopState(),
    /** a newer app with newer prices is out (null: none, or not checked yet) */
    val update: UpdateState? = null,
    /** TCGdex's price date of this app's data pack, ISO UTC */
    val pricesDate: String? = null,
    /** the app's licence on this device */
    val licence: LicenceState = LicenceState(),
    /** YouTube mode's recording: phase, settings, the Recordings list */
    val recording: RecordingUi = RecordingUi(),
    /** the data pack's version (date) */
    val packVersion: String? = null,
) {
    val sessionValue: Double get() = session.sumOf { it.value ?: 0.0 }
}

class ScannerViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx: Application = app
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    /** YouTube mode's recording (screen, event log, uploads) */
    val rec = RecordingCoordinator(app, viewModelScope, { _state.value },
        { f -> _state.update { it.copy(recording = f(it.recording)) } }, { m -> _state.update { it.copy(message = m) } })

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
    /** Webshop Mode: the shop connection (secret encrypted by the Android Keystore) and the shop's clock */
    private val shopStore = ShopStore(app)
    private val shopClock = ServerClock()
    /** Webshop Mode, Sell: the customer's order, kept until it's sold or cleared */
    private val orderFile = java.io.File(app.filesDir, "shop-order.json")
    /** the licence on this phone, and the license service next to the shop */
    private val licenceStore = LicenceStore(app)
    private val licenceApi = LicenceApi(BuildConfig.LICENSE_URL)
    @Volatile private var licenceToken: String? = null
    @Volatile private var licenceChecking = false
    /** a scanned key that the service just refused: not sent again for every frame */
    @Volatile private var refusedKey: String? = null
    private var serial = 0
    private var lastAlerted: String? = null
    private var lastText: CardText? = null
    @Volatile private var lastFill: Double? = null
    /** Trading Mode's carts on the phone; written one after the other on their own thread */
    private val tradingStore = TradingStore(java.io.File(app.filesDir, "trading.json"))
    private val saveDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
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
                trading = prefs.getBoolean("trading", false),
                tradingCondition = runCatching { Condition.valueOf(prefs.getString("trade_condition", null) ?: "NM") }
                    .getOrDefault(Condition.NM),
                reference = runCatching { Reference.valueOf(prefs.getString("trade_reference", null) ?: "TREND") }
                    .getOrDefault(Reference.TREND),
                webshop = prefs.getBoolean("webshop", false),
                priceStep = (Math.round(prefs.getFloat("price_step", 0.5f) * 100.0) / 100.0).takeIf { it > 0 } ?: 0.5,
            ), draft = DealDraft(runCatching { Condition.valueOf(prefs.getString("trade_condition", null) ?: "NM") }
                .getOrDefault(Condition.NM)))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val book = tradingStore.load()
            _state.update { it.copy(book = book) }
        }
        loadShopConnection()
        loadShopOrder()
        loadLicence()
        checkLicence(force = true)
        // Webshop Mode: a new card (or language, printing) -> look it up in the shop, fill in the form
        viewModelScope.launch {
            state.map { s -> if (s.settings.webshop) s.shown?.let { Triple(it.version.id, it.version.lang, it.printing) } else null }
                .distinctUntilChanged()
                .collect { key -> if (key != null) onShopCard() }
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
                val prices = runCatching {
                    p.db.rawQuery("SELECT MAX(updated) FROM prices", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                _state.update { it.copy(loading = false, packInfo = info, hasPsaData = psa, hasCategories = cat.hasCategory,
                    pricesDate = prices, packVersion = p.version.take(10)) }
                applyEnergyFilter(_state.value.settings.energyFilter)
                checkForUpdate()
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

    /** a product picture waits for the next camera frame (when the camera has no still) */
    @Volatile private var frameWanted: kotlinx.coroutines.CompletableDeferred<Mat>? = null

    /** Called on the camera's analysis thread with an upright RGB frame. Drops frames while busy. */
    fun onFrame(full: Mat) {
        lastFullW = full.cols(); lastFullH = full.rows()
        frameWanted?.let { d -> if (!d.isCompleted) d.complete(full.clone()) }
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
        keepOutline(r, rgb.cols(), rgb.rows())
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
        keepOutline(r, rgb.cols(), rgb.rows())
        val best = r.best
        synchronized(lock) {
            if (best == null) {
                _state.update { it.copy(message = "No card found in the frame") }
                return@synchronized
            }
            tracker.lock(best.group)
            kept = best.group
            rejected = emptySet()
            showNew(best.group, r, rgb, fromPhoto = false, conf = Confidence.LIKELY, source = "double_tap")
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

    private var lastSettingsPage = SettingsPage.MODE

    /** The settings screen, on [page] (or where it was left last time). */
    fun openSettings(page: SettingsPage? = null) = _state.update { it.copy(settingsPage = page ?: lastSettingsPage) }

    fun settingsPageShown(page: SettingsPage) { lastSettingsPage = page }

    fun closeSettings() = _state.update { it.copy(settingsPage = null) }

    // ------------------------------------------------------------------ Webshop Mode
    private var shopClient: Pair<String, ShopClient>? = null
    /** the booking last sent: a retry sends the same bytes with the same idempotency key */
    private var pendingShop: PendingWrite? = null
    private var pendingShopText: String = ""

    private fun client(): ShopClient? {
        val c = shopStore.credentials() ?: return null
        val tag = "${c.baseUrl}|${c.keyId}|${c.secret.hashCode()}"
        shopClient?.takeIf { it.first == tag }?.let { return it.second }
        return ShopClient(c, shopClock).also { shopClient = tag to it }
    }

    private fun updateShop(f: (ShopState) -> ShopState) = _state.update { it.copy(shop = f(it.shop)) }

    private fun loadShopConnection() = updateShop {
        it.copy(configured = shopStore.credentials() != null, host = shopStore.host, keyId = shopStore.keyId, hasSecret = shopStore.hasSecret,
            minPrice = shopStore.minPrice)
    }

    /** Settings → Webshop: the minimum selling price (null or 0: none). The form's suggestion follows at once. */
    fun setShopMinPrice(v: Double?) {
        shopStore.minPrice = v?.let { TradingMath.round2(it) }?.takeIf { it > 0 }
        updateShop { it.copy(minPrice = shopStore.minPrice) }
        _state.value.shown?.let { s -> updateShop { it.copy(form = withSuggestedPrice(it.form, s, it.products)) } }
    }

    /** Saves the connection (an empty [secret] keeps the stored one) and tests it. */
    fun saveShop(host: String, keyId: String, secret: String) {
        if (ShopCredentials.baseUrlOf(host) == null) {
            updateShop { it.copy(connection = "Not a valid host – e.g. pokemonkarten.at or staging.pokemonkarten.at", connectionOk = false) }
            return
        }
        shopStore.save(host, keyId, secret.ifBlank { null })
        shopClient = null
        loadShopConnection()
        testShop()
    }

    /** GET /health: key, secret and clock work. */
    fun testShop() {
        val c = client()
        if (c == null) { updateShop { it.copy(connection = "Host, key id and secret are needed", connectionOk = false) }; return }
        updateShop { it.copy(testing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { c.health() }
            updateShop { st ->
                r.fold(
                    { h -> st.copy(testing = false, connectionOk = true,
                        connection = "Connected as ${h.keyId}" + (h.serverTime?.let { " · shop time ${it.take(16).replace('T', ' ')} UTC" } ?: "")) },
                    { e -> st.copy(testing = false, connectionOk = false, connection = (e as? ShopException)?.friendly ?: (e.message ?: "failed")) },
                )
            }
            if (r.isSuccess && _state.value.settings.webshop) onShopCard(force = true)
        }
    }

    fun disconnectShop() {
        shopStore.clear()
        shopClient = null
        updateShop { ShopState(minPrice = shopStore.minPrice) }        // the minimum price is a setting, not part of the key
    }

    /** The shown card changed (Webshop Mode): fill in the form and look the card up in the shop. */
    private fun onShopCard(force: Boolean = false) {
        val s = _state.value.shown ?: return
        val shop = _state.value.shop
        val newCard = force || shop.cardRef != s.version.id
        val lang = ShopTerms.languageOf(s.version.lang)
        val form = if (newCard) ShopForm(language = lang, condition = shopStore.lastCondition, box = shopStore.lastBox)
            else shop.form.let { f -> if (f.languageByUser) f else f.copy(language = lang) }
        updateShop { it.copy(form = withSuggestedPrice(form, s, if (newCard) null else it.products),
            error = if (newCard) null else it.error, unknownCard = null,
            // the last booking's warnings belong to that card (the booking itself stays, for Undo)
            booking = if (newCard) it.booking?.copy(warnings = emptyList()) else it.booking,
            photo = it.photo?.takeIf { p -> p.cardRef == s.version.id }, photoNote = if (newCard) null else it.photoNote,
            sellSku = if (newCard) null else it.sellSku, sellBox = if (newCard) null else it.sellBox) }
        if (newCard) lookupShop(s.version.id)
    }

    /** Looks the card up in the shop: every product of it (languages, conditions, finishes). */
    fun lookupShop(cardRef: String? = _state.value.shown?.version?.id) {
        cardRef ?: return
        val c = client()
        if (c == null) { updateShop { it.copy(cardRef = cardRef, products = null, loading = false, lookupError = null) }; return }
        updateShop { it.copy(cardRef = cardRef, loading = true, lookupError = null, products = null, catalogPicture = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // does the shop's card catalogue have a picture? if not, a booking sends the app's own
            val picture = async { runCatching { c.masterCards(cardRef = cardRef).any { it.imageUrl != null } }.getOrNull() }
            val r = runCatching { c.productsOfCard(cardRef) }
            val hasPicture = picture.await()
            updateShop { st ->
                if (st.cardRef != cardRef) st          // another card meanwhile
                else r.fold(
                    { list -> withSellDefaults(st.copy(loading = false, products = list, catalogPicture = hasPicture,
                        form = _state.value.shown?.let { withSuggestedPrice(st.form, it, list) } ?: st.form)) },
                    { e -> st.copy(loading = false, lookupError = (e as? ShopException)?.friendly ?: (e.message ?: "lookup failed")) },
                )
            }
        }
    }

    /** The shop's product for the shown card with the form's language, condition and finish (null: none yet). */
    fun shopMatch(st: UiState = _state.value): ShopProduct? {
        val s = st.shown ?: return null
        val finish = ShopTerms.finishOf(s.printing)
        return st.shop.products?.firstOrNull {
            it.cardLanguage == st.shop.form.language && it.condition == st.shop.form.condition && it.finish == finish && it.grading == null
        }
    }

    /** The price the form starts with: the shop's price of that product, else the market value for the condition. */
    private fun withSuggestedPrice(f: ShopForm, s: Shown, products: List<ShopProduct>?): ShopForm {
        if (f.priceByUser) return f
        val finish = ShopTerms.finishOf(s.printing)
        val existing = products?.firstOrNull { it.cardLanguage == f.language && it.condition == f.condition && it.finish == finish && it.grading == null }
        return f.copy(price = existing?.price?.euros ?: suggestedPrice(marketValue(s, f.condition), _state.value.shop.minPrice))
    }

    /** The market value of the shown printing in [condition] (Cardmarket, else TCGplayer in euros). */
    fun marketValue(s: Shown, condition: String): Double? =
        s.pickedPrinting?.let { p -> (p.eur ?: p.usdEur)?.let { TradingMath.round2(it * ShopTerms.share(condition)) } }

    /** Webshop: the card type (Normal / Holo / Reverse Holo …), also one the price data doesn't have. */
    fun setShopFinish(kind: Printing.Kind) { holdCard(); choosePrinting(kind) }

    /** Filling in the form: the camera waits, so another card in view can't replace this one meanwhile. */
    private fun holdCard() {
        if (_state.value.paused || _state.value.shown == null) return
        synchronized(lock) { _state.value.shown?.let { tracker.lock(it.group) } }
        _state.update { it.copy(paused = true) }
    }

    fun setShopLanguage(code: String) {
        val s = _state.value.shown ?: return
        holdCard()
        updateShop { it.copy(form = withSuggestedPrice(it.form.copy(language = code, languageByUser = true), s, it.products)) }
    }

    fun setShopCondition(code: String) {
        val s = _state.value.shown ?: return
        holdCard()
        updateShop { it.copy(form = withSuggestedPrice(it.form.copy(condition = code), s, it.products)) }
    }

    fun setShopQuantity(n: Int) { holdCard(); updateShop { it.copy(form = it.form.copy(quantity = n.coerceIn(1, 1000))) } }
    fun setShopPrice(v: Double?) { holdCard(); updateShop { it.copy(form = it.form.copy(price = v?.let { p -> TradingMath.round2(p) }, priceByUser = true)) } }
    fun setShopBox(n: Int?) { holdCard(); updateShop { it.copy(form = it.form.copy(box = n?.takeIf { b -> b in 1..999 })) } }
    fun setShopComment(t: String) { holdCard(); updateShop { it.copy(form = it.form.copy(comment = t.take(5000))) } }

    /**
     * The user's go: book the copies (POST /stock/intake). Creates the product if the shop has
     * none for this language, condition and finish, else adds the copies to it. Never automatic.
     */
    fun bookShop(nameDe: String? = null, nameEn: String? = null) {
        val st = _state.value
        val s = st.shown ?: return
        val f = st.shop.form
        if (st.shop.busy) return
        val box = f.box ?: run { updateShop { it.copy(error = "Pick a storage box (1–999)", retryable = false) }; return }
        val match = shopMatch(st)
        val finish = ShopTerms.finishOf(s.printing)
        // the price goes along for a new product, or when the user typed one (it then replaces the shop's)
        val price = f.price?.takeIf { match == null || f.priceByUser }?.let { Money.ofEuros(it) }
        // no picture in the shop (its card catalogue has none, the product none of its own): the app's picture of
        // the card goes along, so the product can go live; a product left a draft for that alone is made live now
        val ownPicture = match != null && match.images.any { it.status != "failed" }
        // a photo taken with the camera goes up after the booking instead
        val photo = st.shop.photo?.takeIf { it.cardRef == s.version.id }
        val picture = s.pictureUrl?.takeIf { photo == null && st.shop.catalogPicture != true && !ownPicture }
        val activate = picture != null && match != null && match.status == "draft" && (match.price != null || price != null)
        val body = ShopClient.intakeBody(s.version.id, f.language, f.condition, finish, f.quantity, ShopClient.box(box), price,
            descriptionDe = f.comment.takeIf { match == null }, nameDe = nameDe, nameEn = nameEn,
            imageUrl = picture, status = if (activate) "active" else null)
        val w = PendingWrite.json("POST", "/stock/intake", body)
        val label = "${s.version.name} (${f.language} · ${f.condition} · ${ShopTerms.finishLabel(finish)})"
        pendingShop = w
        pendingShopText = label
        shopStore.lastBox = box
        shopStore.lastCondition = f.condition
        send(w, label, f.quantity, ShopClient.box(box))
    }

    /** Sends the failed booking again: the same bytes, the same idempotency key (never booked twice). */
    fun retryShop() {
        val w = pendingShop ?: return
        val delta = runCatching { org.json.JSONObject(String(w.body)).optInt("delta") }.getOrDefault(0)
        val box = runCatching { org.json.JSONObject(String(w.body)).optString("storage_location") }.getOrDefault("")
        send(w, pendingShopText, delta, box)
    }

    private fun send(w: PendingWrite, label: String, delta: Int, box: String) {
        val c = client() ?: run { updateShop { it.copy(error = "Connect the shop first (⚙ → Webshop)", retryable = false) }; return }
        updateShop { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = c.intake(w)
                pendingShop = null
                var p = r.product
                var warnings = r.warnings
                // the product picture taken with the camera: up to the product; a draft with a price goes live
                val photo = _state.value.shop.photo?.takeIf { it.cardRef == p.cardRef }
                var photoNote: String? = null
                if (photo != null) {
                    runCatching {
                        c.uploadImage(PendingWrite.image(p.sku, photo.jpeg))
                        if (p.status == "draft" && p.price != null) {
                            val (p2, w2) = c.patchProduct(PendingWrite.json("PATCH", "/products/${p.sku}", org.json.JSONObject().put("status", "active")))
                            p = p2; warnings = w2
                        } else warnings = warnings - "not_activated_no_image"
                        p = runCatching { c.product(p.sku) }.getOrDefault(p)        // with its new picture
                    }.onFailure { e ->
                        Log.w(TAG, "product picture upload failed", e)
                        photoNote = "The picture didn't go up (${(e as? ShopException)?.friendly ?: e.message}) – it goes along with the next booking"
                    }
                }
                val uploaded = photo != null && photoNote == null
                val inBox = p.stock.boxes.firstOrNull { it.box == box }?.quantity
                val text = (if (r.created) "New in the shop: " else "Added: ") + "$delta × $label to box $box" +
                    " · now ${p.stock.quantity} in stock" + (inBox?.let { " ($it in box $box)" } ?: "") +
                    (p.price?.let { " · ${String.format(Locale.getDefault(), "%.2f €", it.euros)}" } ?: "") +
                    " · ${ShopTerms.statusLabel(p.status)}" + (if (uploaded) " · with your picture" else "") +
                    (if (r.replayed) " (already booked before)" else "")
                val finalP = p
                updateShop { st ->
                    st.copy(busy = false, booking = ShopBooking(text, warnings.map(::shopWarningText), finalP.sku, delta, box),
                        photo = if (uploaded) null else st.photo, photoNote = photoNote,
                        products = (st.products.orEmpty().filter { it.sku != finalP.sku } + finalP),
                        form = st.form.copy(quantity = 1, priceByUser = false, comment = ""), unknownCard = null)
                }
                // booked: on to the next card
                if (_state.value.paused && _state.value.shown?.fromPhoto != true) resume()
            } catch (e: ShopException) {
                if (e.code == "unknown_card") {
                    pendingShop = null
                    val s = _state.value.shown
                    val de = s?.let { sh -> if (sh.version.lang == "de") sh.version.name else catalog?.cardExact(sh.version.id, "de")?.name } ?: ""
                    val en = s?.let { sh -> sh.english?.name ?: if (sh.version.lang == "en") sh.version.name else catalog?.cardExact(sh.version.id, "en")?.name } ?: ""
                    updateShop { it.copy(busy = false, unknownCard = de to en) }
                } else {
                    if (e.status in 400..499) pendingShop = null      // the shop said no: a corrected booking gets a new key
                    updateShop { it.copy(busy = false, error = e.friendly, retryable = e.status == 0 || e.status >= 500) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "shop booking failed", t)
                updateShop { it.copy(busy = false, error = t.message ?: "failed", retryable = true) }
            }
        }
    }

    fun dismissUnknownCard() = updateShop { it.copy(unknownCard = null) }

    // ---------------------------------------------------------------- Webshop Mode: Sell
    /** the sale last sent: a retry sends the same batch with the same idempotency key */
    private var pendingSale: Pair<PendingWrite, List<ShopOrderLine>>? = null

    private fun loadShopOrder() {
        val o = runCatching { org.json.JSONObject(orderFile.readText()) }.getOrNull()
        val lines = o?.optJSONArray("lines")?.let { a -> (0 until a.length()).mapNotNull { runCatching { ShopOrderLine.from(a.getJSONObject(it)) }.getOrNull() } }
        updateShop { it.copy(order = lines.orEmpty(), selling = shopStore.selling) }
    }

    private fun saveShopOrder(order: List<ShopOrderLine>) {
        runCatching {
            orderFile.writeText(org.json.JSONObject().put("lines", org.json.JSONArray().apply { order.forEach { put(it.toJson()) } }).toString())
        }.onFailure { Log.w(TAG, "order not saved", it) }
    }

    /** Stock in (book copies into the shop) or Sell (collect a customer's order, take it out of stock). */
    fun setShopSelling(on: Boolean) {
        shopStore.selling = on
        updateShop { withSellDefaults(it.copy(selling = on)) }
    }

    /** The product and box a sale takes from, picked for the shown card: the scanned language and printing first, a box with free copies. */
    private fun withSellDefaults(st: ShopState): ShopState {
        val products = st.products.orEmpty().filter { it.stock.quantity > 0 }
        if (products.isEmpty()) return st.copy(sellSku = null, sellBox = null, sellQty = 1)
        st.sellSku?.let { sku -> products.firstOrNull { it.sku == sku } }?.let { keep ->
            if (keep.stock.boxes.any { it.box == st.sellBox && it.quantity > 0 }) return st
        }
        val s = _state.value.shown
        val lang = s?.let { ShopTerms.languageOf(it.version.lang) }
        val finish = s?.let { ShopTerms.finishOf(it.printing) }
        val p = products.sortedWith(compareBy({ it.cardLanguage != lang }, { it.finish != finish }, { -it.stock.available })).first()
        return st.copy(sellSku = p.sku, sellBox = bestBox(p), sellQty = 1)
    }

    /** the box with the most free copies (not held by online checkouts), else the fullest */
    private fun bestBox(p: ShopProduct): String? =
        p.stock.boxes.filter { it.quantity > 0 }.maxWithOrNull(compareBy({ it.quantity - it.reserved }, { it.quantity }))?.box

    fun selectSellProduct(sku: String) {
        holdCard()
        updateShop { st ->
            val p = st.products?.firstOrNull { it.sku == sku } ?: return@updateShop st
            st.copy(sellSku = sku, sellBox = bestBox(p), sellQty = 1)
        }
    }

    fun setSellBox(box: String) { holdCard(); updateShop { it.copy(sellBox = box, sellQty = 1) } }

    fun setSellQty(n: Int) {
        holdCard()
        updateShop { st ->
            val max = st.products?.firstOrNull { it.sku == st.sellSku }?.stock?.boxes?.firstOrNull { it.box == st.sellBox }?.quantity ?: 1
            st.copy(sellQty = n.coerceIn(1, max.coerceAtLeast(1)))
        }
    }

    /** The picked copies go into the customer's order (nothing leaves the stock yet). */
    fun addToOrder() {
        val st = _state.value.shop
        val p = st.products?.firstOrNull { it.sku == st.sellSku } ?: return
        val box = st.sellBox ?: return
        val inBox = p.stock.boxes.firstOrNull { it.box == box }?.quantity ?: 0
        val s = _state.value.shown
        val name = (if (p.cardLanguage == "DE") p.nameDe else p.nameEn).ifBlank { s?.version?.name ?: p.sku }
        val detail = "${p.cardLanguage} · ${p.condition} · ${ShopTerms.finishLabel(p.finish)}" + (p.grading?.let { " · $it" } ?: "") +
            " · ${p.cardNumber}"
        val order = st.order.toMutableList()
        val i = order.indexOfFirst { it.sku == p.sku && it.box == box }
        if (i >= 0) order[i] = order[i].copy(quantity = (order[i].quantity + st.sellQty).coerceAtMost(inBox), error = null)
        else order += ShopOrderLine(p.sku, name, detail, box, st.sellQty.coerceAtMost(inBox), p.price?.euros)
        saveShopOrder(order)
        updateShop { it.copy(order = order, sellQty = 1, orderError = null) }
        _state.update { it.copy(message = "In the order: ${order.sumOf { l -> l.quantity }} cards") }
        // on to the next card the customer wants
        if (_state.value.paused && _state.value.shown?.fromPhoto != true) resume()
    }

    fun setOrderLineQty(i: Int, n: Int) = updateShop { st ->
        if (i !in st.order.indices) return@updateShop st
        val order = st.order.toMutableList().also { it[i] = it[i].copy(quantity = n.coerceIn(1, 999)) }
        saveShopOrder(order)
        st.copy(order = order)
    }

    fun removeOrderLine(i: Int) = updateShop { st ->
        if (i !in st.order.indices) return@updateShop st
        val order = st.order.filterIndexed { j, _ -> j != i }
        saveShopOrder(order)
        st.copy(order = order)
    }

    fun clearOrder() {
        pendingSale = null
        saveShopOrder(emptyList())
        updateShop { it.copy(order = emptyList(), orderError = null) }
    }

    /**
     * The user's go: take the whole order out of stock (one POST /batch, reason "sold in person").
     * Lines the shop refuses (e.g. the box no longer holds them) stay in the order with the reason.
     */
    fun completeSale() {
        val st = _state.value.shop
        if (st.orderBusy || st.order.isEmpty()) return
        val lines = st.order.map { it.copy(error = null) }
        // the same order after a failed attempt: the same key, so it can't be taken out twice
        val w = pendingSale?.takeIf { it.second == lines }?.first ?: PendingWrite.json("POST", "/batch",
            ShopClient.stockBatchBody(lines.map { Triple(it.sku, it.box, -it.quantity) }, "sold in person (scanner app)"))
        pendingSale = w to lines
        sendSale(w, lines)
    }

    /** Sends the failed sale again: the same batch, the same key (nothing is taken out twice). */
    fun retrySale() { pendingSale?.let { (w, lines) -> sendSale(w, lines) } }

    private fun sendSale(w: PendingWrite, lines: List<ShopOrderLine>) {
        val c = client() ?: run { updateShop { it.copy(orderError = "Connect the shop first (⚙ → Webshop)", orderRetryable = false) }; return }
        updateShop { it.copy(orderBusy = true, orderError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = c.batch(w)
                pendingSale = null
                val sold = mutableListOf<ShopOrderLine>()
                val left = mutableListOf<ShopOrderLine>()
                lines.forEachIndexed { i, l ->
                    val r = results.getOrNull(i)
                    if (r?.ok == true) sold += l
                    else left += l.copy(error = r?.error?.friendly ?: "no answer for this card")
                }
                val total = sold.sumOf { it.total ?: 0.0 }
                val text = if (sold.isEmpty()) "Nothing was taken out" else
                    "Sold ${sold.sumOf { it.quantity }} cards" + (if (total > 0) " · " + String.format(Locale.getDefault(), "%.2f €", total) else "") +
                        " – taken out of stock" + if (left.isNotEmpty()) " · ${left.size} line(s) failed, see the order" else ""
                saveShopOrder(left)
                updateShop { it.copy(orderBusy = false, order = left, sale = if (sold.isEmpty()) it.sale else ShopSale(text, sold),
                    orderError = if (sold.isEmpty() && left.isNotEmpty()) "Nothing was taken out – see the lines" else null) }
                lookupShop()                                       // the shown card's stock, fresh
            } catch (e: ShopException) {
                if (e.status in 400..499) pendingSale = null
                updateShop { it.copy(orderBusy = false, orderError = e.friendly, orderRetryable = e.status == 0 || e.status >= 500) }
            } catch (t: Throwable) {
                Log.w(TAG, "sale failed", t)
                updateShop { it.copy(orderBusy = false, orderError = t.message ?: "failed", orderRetryable = true) }
            }
        }
    }

    /** the undo last sent, for its sale: pressing Undo again after a failure reuses its key */
    private var pendingUndo: Pair<ShopSale, PendingWrite>? = null

    /** Undo the last sale: the copies go back into their boxes. */
    fun undoSale() {
        val sale = _state.value.shop.sale?.takeIf { !it.undone } ?: return
        val c = client() ?: return
        val w = pendingUndo?.takeIf { it.first == sale }?.second ?: PendingWrite.json("POST", "/batch",
            ShopClient.stockBatchBody(sale.lines.map { Triple(it.sku, it.box, it.quantity) }, "sale undone (scanner app)"))
        pendingUndo = sale to w
        updateShop { it.copy(orderBusy = true, orderError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { c.batch(w) }
            val results = r.getOrNull()
            if (results != null) pendingUndo = null
            // lines put back are done; only the others are left to undo
            val left = if (results == null) sale.lines else sale.lines.filterIndexed { i, _ -> results.getOrNull(i)?.ok != true }
            updateShop { st ->
                if (left.isEmpty()) st.copy(orderBusy = false, sale = sale.copy(undone = true, text = "Undone: the cards are back in their boxes"))
                else st.copy(orderBusy = false, sale = sale.copy(lines = left), orderError = "Undo failed for ${left.size} line(s): " +
                    (r.exceptionOrNull()?.let { (it as? ShopException)?.friendly ?: it.message } ?: results?.firstNotNullOfOrNull { it.error?.friendly } ?: ""))
            }
            lookupShop()
        }
    }

    /**
     * Webshop: "Create Product Image" – a photo of the shown card for its shop product, when no
     * picture of the card in its language exists. Only what the camera box shows; the card's
     * outer edge found and straightened (the guide frame cut out if no edge is found). It goes up
     * with the next booking.
     */
    fun takeProductPhoto() {
        val s = _state.value.shown ?: return
        val rec = recognizer ?: return
        if (_state.value.shop.photoBusy) return
        holdCard()
        updateShop { it.copy(photoBusy = true, photoNote = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                val still = runCatching { ProductPhoto.take() }.onFailure { Log.w(TAG, "still photo failed", it) }.getOrNull()
                val full = if (still != null) Recognizer.bitmapToRgb(still).also { still.recycle() }
                    else kotlinx.coroutines.CompletableDeferred<Mat>().also { frameWanted = it }.let { d ->
                        kotlinx.coroutines.withTimeout(3_000) { d.await() }.also { frameWanted = null }
                    }
                // what the camera box shows
                val vis = visiblePart(full)?.also { full.release() } ?: full
                val prior = Readings.guidePrior(vis.cols(), vis.rows(), _state.value.settings.guideFraction)
                // the card's outer edge: found on a smaller copy (the matcher knows artwork box from card), used at full size
                val k = minOf(1.0, 1600.0 / maxOf(vis.cols(), vis.rows()))
                val small = if (k < 1.0) Mat().also { Imgproc.resize(vis, it, org.opencv.core.Size(), k, k, Imgproc.INTER_AREA) } else vis
                while (!busy.compareAndSet(false, true)) delay(20)
                val quad = try {
                    val r = rec.recognise(small, prior, manual = true)
                    val hit = r.hits.firstOrNull { it.group == s.group } ?: r.best
                    (hit?.let { rec.cardQuad(r, it, margin = 0.0) } ?: r.quad)?.map { org.opencv.core.Point(it.x / k, it.y / k) }?.toTypedArray()
                } finally {
                    busy.set(false)
                    if (small !== vis) small.release()
                }
                val card = ProductPhoto.cardImage(vis, quad, prior)
                vis.release()
                val (jpeg, thumb) = ProductPhoto.encode(card)
                card.release()
                Log.i(TAG, "product picture: ${jpeg.size / 1024} kB, outline ${if (quad != null) "found" else "not found, guide frame"}, " +
                    if (still != null) "still" else "camera frame")
                ShopPhoto(s.version.id, jpeg, thumb)
            }
            updateShop { st ->
                if (_state.value.shown?.version?.id != s.version.id) st.copy(photoBusy = false)       // another card meanwhile
                else result.fold({ ph -> st.copy(photoBusy = false, photo = ph, photoNote = null) },
                    { e -> Log.w(TAG, "product picture failed", e); st.copy(photoBusy = false, photoNote = "No picture taken: ${e.message ?: "camera error"}") })
            }
        }
    }

    /** Undo the last booking: take the copies out of the box again (a stock correction). */
    fun undoShopBooking() {
        val b = _state.value.shop.booking?.takeIf { !it.undone } ?: return
        val c = client() ?: return
        val body = org.json.JSONObject().put("delta", -b.delta).put("storage_location", b.box).put("reason", "undone in the scanner app")
        val w = PendingWrite.json("POST", "/products/${b.sku}/stock", body)
        updateShop { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { c.stock(w) }
            updateShop { st ->
                r.fold(
                    { stock -> st.copy(busy = false, booking = b.copy(undone = true, text = "Undone: ${b.delta} × out of box ${b.box} · now ${stock.quantity} in stock"),
                        products = st.products?.map { p -> if (p.sku == b.sku) p.copy(stock = stock) else p }) },
                    { e -> st.copy(busy = false, error = "Undo failed: " + ((e as? ShopException)?.friendly ?: e.message), retryable = false) },
                )
            }
        }
    }

    // ------------------------------------------------------------------ app update
    private var lastUpdateCheck = 0L
    private var dismissedUpdate = -1

    private fun installedVersionCode(): Long = runCatching {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(appCtx.packageManager.getPackageInfo(appCtx.packageName, 0))
    }.getOrDefault(Long.MAX_VALUE)

    /**
     * Looks for a newer build on GitHub (at start and when the app comes back, at most every 30
     * minutes). [announce]: say so when there is none (the Settings button).
     */
    fun checkForUpdate(announce: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!announce && now - lastUpdateCheck < 30 * 60_000L) return
        lastUpdateCheck = now
        viewModelScope.launch(Dispatchers.IO) {
            val r = AppUpdate.latest()
            when {
                r == null -> if (announce) _state.update { it.copy(message = "Couldn't reach GitHub – try again with internet") }
                r.versionCode <= installedVersionCode() ->
                    if (announce) _state.update { it.copy(message = "You have the newest version") }
                announce || r.versionCode != dismissedUpdate -> _state.update { s ->
                    if (s.update?.release?.versionCode == r.versionCode) s else s.copy(update = UpdateState(r))
                }
            }
        }
    }

    /** Update: download the new APK and open Android's installer (the user confirms there). */
    fun startUpdate() {
        val u = _state.value.update ?: return
        if (u.progress != null) return
        u.apk?.takeIf { it.exists() }?.let { install(it); return }
        _state.update { it.copy(update = u.copy(progress = 0f, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = AppUpdate.download(appCtx, u.release) { p -> _state.update { s -> s.copy(update = s.update?.copy(progress = p)) } }
                _state.update { s -> s.copy(update = s.update?.copy(progress = null, apk = f)) }
                withContext(Dispatchers.Main) { install(f) }
            } catch (t: Throwable) {
                Log.w(TAG, "update download failed", t)
                _state.update { s -> s.copy(update = s.update?.copy(progress = null, error = t.message ?: "download failed")) }
            }
        }
    }

    private fun install(apk: java.io.File) {
        if (!AppUpdate.canInstall(appCtx)) {
            // once: Android wants "allow from this source" for Card Scanner; back in the app it goes on
            _state.update { s -> s.copy(update = s.update?.copy(needsPermission = true)) }
            runCatching { appCtx.startActivity(AppUpdate.allowIntent(appCtx)) }
            return
        }
        _state.update { s -> s.copy(update = s.update?.copy(needsPermission = false)) }
        runCatching { appCtx.startActivity(AppUpdate.installIntent(appCtx, apk)) }.onFailure { t ->
            _state.update { s -> s.copy(update = s.update?.copy(error = t.message ?: "installer didn't open")) }
        }
    }

    /** The app is back in front: look for updates; after "allow from this source", go on installing. */
    // ---------------------------------------------------------------- recording: the event log
    /** the last camera frame's size (upright), for the card's corners in the recording's camera file */
    @Volatile private var lastFullW = 0
    @Volatile private var lastFullH = 0

    /** The card's outer edge in an analysed frame (crop coordinates) and that frame's sizes. */
    private class Outline(val quad: Array<org.opencv.core.Point>, val w: Int, val h: Int, val fullW: Int, val fullH: Int, val nanos: Long)
    /** the last frame's card outline, taken before that frame's card is shown or changed */
    @Volatile private var outline: Outline? = null

    /** While recording the camera file: the card's outer edge in this frame (an artwork-box detection turned into the whole card). */
    private fun keepOutline(r: Recognition, w: Int, h: Int) {
        if (!CameraRecording.active) return
        val hit = r.best?.takeIf { r.detected } ?: return
        val q = runCatching { recognizer?.cardQuad(r, hit, margin = 0.0) }.getOrNull() ?: return
        outline = Outline(q, w, h, lastFullW, lastFullH, System.nanoTime())
    }

    /** The shown card's corners in camera.mp4's picture (0–1), clockwise from top left; null without a camera file or an outline. */
    private fun cameraQuad(): List<DoubleArray>? {
        if (!CameraRecording.active) return null
        if (_state.value.shown?.fromPhoto == true) return null
        val o = outline?.takeIf { System.nanoTime() - it.nanos < 1_000_000_000L } ?: return null
        val fw = o.fullW; val fh = o.fullH
        if (fw <= 0 || fh <= 0 || o.quad.size != 4) return null
        // the file plays upright like the scanner's frame (the screen is locked while recording)
        val (vw, vh) = CameraRecording.size ?: CameraRecording.nominal?.let { (l, sh) -> if (fw >= fh) Pair(l, sh) else Pair(sh, l) } ?: return null
        // the analysed frame is a centred crop of the camera's frame
        val ox = (fw - o.w) / 2.0; val oy = (fh - o.h) / 2.0
        return CameraRecording.clockwiseFromTopLeft(o.quad.map { p ->
            CameraRecording.toCameraFile((ox + p.x) / fw, (oy + p.y) / fh, fw.toDouble() / fh, vw.toDouble() / vh)
        })
    }

    /** Right after the pack or the shown card changed: the recording's log works out what happened. */
    private fun logPack(reason: EventLog.Reason, source: String? = null) {
        val log = RecordingController.log ?: return
        val st = _state.value
        runCatching { log.observe(reason, source, st.shown, st.session, cameraQuad()) }
            .onFailure { Log.w(TAG, "event log", it) }
    }

    // ---------------------------------------------------------------- licence

    private fun loadLicence() {
        val saved = licenceStore.load()
        licenceToken = saved.token
        _state.update {
            it.copy(licence = LicenceState(required = BuildConfig.LICENSE_REQUIRED, status = saved.status, key = saved.key,
                since = saved.since, lastCheck = saved.lastCheck, suspendedBy = saved.suspendedBy, suspendedSince = saved.suspendedSince))
        }
    }

    private fun updateLicence(f: (LicenceState) -> LicenceState) = _state.update { it.copy(licence = f(it.licence)) }

    /** A scanned QR code or a typed key: activate it on this device (the device that had it stops). */
    fun activateLicence(text: String, scanned: Boolean = false) {
        if (_state.value.licence.busy) return
        val key = LicenceKey.parse(text)
        if (key == null) {
            if (!scanned) updateLicence { it.copy(error = "That isn't a licence key (CS-XXXXX-XXXXX-XXXXX-XXXXX).") }
            return
        }
        if (scanned && key == refusedKey) return
        updateLicence { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val a = licenceApi.activate(key, licenceStore.deviceId, LicenceStore.deviceName(), BuildConfig.VERSION_NAME)
                licenceStore.activated(key, a)
                refusedKey = null
                loadLicence()
                _state.update { it.copy(message = "Licence activated") }
            } catch (e: LicenceException) {
                refusedKey = key
                updateLicence { it.copy(busy = false, error = e.friendly) }
            } catch (e: java.io.IOException) {
                updateLicence { it.copy(busy = false, error = "No connection: activating needs the internet once. Try again when you're online.") }
            }
        }
    }

    /**
     * Asks the service whether this device still holds the licence: at every start, otherwise at
     * most once an hour. Offline (or the service away): nothing happens, the app goes on.
     */
    fun checkLicence(force: Boolean = false) {
        val l = _state.value.licence
        if (!l.required || l.status != LicenceStatus.ACTIVE || licenceChecking) return
        val token = licenceToken ?: return
        if (!force && l.lastCheck != null && System.currentTimeMillis() - l.lastCheck < 60 * 60 * 1000L) return
        licenceChecking = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val r = licenceApi.check(token, BuildConfig.VERSION_NAME)) {
                    LicenceCheck.Active -> licenceStore.checked()
                    is LicenceCheck.Suspended -> licenceStore.suspended(r.since, r.deviceName)
                    LicenceCheck.Revoked -> licenceStore.revoked()
                }
                loadLicence()
            } catch (e: Exception) {
                // offline, the service is away or doesn't know the token: keep working
            } finally {
                licenceChecking = false
            }
        }
    }

    /** The app left the screen: a recording ends here, so nothing else gets into the video. */
    fun onAppStopped() { if (RecordingController.recording) rec.stop("background") }

    fun onResume() {
        rec.refresh()
        checkLicence()
        checkForUpdate()
        val u = _state.value.update ?: return
        if (u.needsPermission && u.apk != null && AppUpdate.canInstall(appCtx)) install(u.apk)
    }

    /** Later: hide it until the app starts again. */
    fun dismissUpdate() {
        dismissedUpdate = _state.value.update?.release?.versionCode ?: -1
        _state.update { it.copy(update = null) }
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
        return Shown(group, version, english, priceCard, defaultPrinting(priceCard), false, null, conf,
            alternatives, langs, text, locked, fromPhoto, preview, ++serial, versions = all, scan = scan,
            history = runCatching { cat.history(priceCard.id) }.getOrNull())
    }

    private fun showNew(group: String, r: Recognition, rgb: Mat, fromPhoto: Boolean, conf: Confidence, source: String = "camera") {
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
            session = if (s.settings.youtube && !fromPhoto) addToSession(s, first) else s.session,
            tray = if (s.settings.trading) addToTray(s.tray, first) else s.tray,
            draft = DealDraft(s.settings.tradingCondition)) }
        logPack(EventLog.Reason.SHOWN, if (fromPhoto) "photo" else source)
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
        _state.update { s -> s.copy(shown = updated, session = replaceInSession(s.session, updated), tray = replaceInSession(s.tray, updated)) }
        logPack(EventLog.Reason.TEXT_READ)
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

    /** The printing to select for a foil verdict (unless the user picked one); never Holo or Reverse when there is a Normal. */
    private fun autoPrinting(s: Shown, kind: FoilEstimate.Kind): Printing.Kind? {
        if (s.printingByUser) return s.printing
        val kinds = s.priceCard.printings.map { it.kind }
        if (Printing.Kind.NORMAL in kinds) return Printing.Kind.NORMAL
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
            s.copy(shown = updated, session = replaceInSession(s.session, updated), tray = replaceInSession(s.tray, updated))
        }
        if (printing != current.printing) logPack(EventLog.Reason.PRINTING)
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

    fun resetSession() {
        _state.update { it.copy(session = emptyList()) }
        logPack(EventLog.Reason.RESET)
    }

    // ------------------------------------------------------------------ Trading Mode
    private fun addToTray(tray: List<SessionItem>, sh: Shown): List<SessionItem> {
        if (tray.lastOrNull()?.group == sh.group) return tray
        return (tray + SessionItem(sh.serial, System.currentTimeMillis(), sh.copy(preview = null, scan = null))).takeLast(TRAY_SIZE)
    }

    /** A card from the tray back in the price panel; it stays until another card is clearly in front of the camera. */
    fun showFromTray(item: SessionItem) = synchronized(lock) {
        tracker.lock(item.group)
        kept = item.group
        rejected = emptySet()
        _state.update { it.copy(shown = item.shown, picks = null, draft = DealDraft(it.settings.tradingCondition)) }
    }

    fun setCondition(c: Condition) = _state.update { it.copy(draft = it.draft.copy(condition = c)) }
    fun setScanTarget(side: Side) = _state.update { it.copy(scanTarget = side) }
    fun setAsking(v: Double?) = _state.update { it.copy(draft = it.draft.copy(asking = v)) }

    /** All carts change through here: shown at once, written to the phone in order. */
    private fun updateBook(f: (TradingBook) -> TradingBook) {
        var saved: TradingBook? = null
        _state.update { st -> f(st.book).also { saved = it }.let { st.copy(book = it) } }
        val b = saved ?: return
        viewModelScope.launch(saveDispatcher) {
            runCatching { tradingStore.save(b) }.onFailure { Log.e(TAG, "saving the carts failed", it) }
        }
    }

    /** + Cart: the shown card with its printing, condition and asking price into the active cart. */
    fun addToCart() {
        val st = _state.value
        val s = st.shown ?: return
        val now = System.currentTimeMillis()
        var cartName = ""
        updateBook { b0 ->
            val (b, cart) = b0.withActive(now)
            cartName = cart.name
            val printing = s.selected?.kind?.name
            val ref = TradingMath.reference(s.selected, st.settings.reference)
            val side = st.scanTarget
            val last = cart.lines.lastOrNull()
            val same = last != null && last.status == LineStatus.CONSIDERING && last.cardId == s.version.id &&
                last.lang == s.version.lang && last.printing == printing && last.condition == st.draft.condition &&
                last.asking == st.draft.asking && last.side == side
            val lines = if (same) cart.lines.dropLast(1) + last!!.copy(qty = last.qty + 1)
                else cart.lines + CartLine(id = now, cardId = s.version.id, lang = s.version.lang, group = s.group,
                    name = s.version.name, setLine = s.version.setLine(), image = s.imageCard?.thumbnailUrl,
                    printing = printing, reference = ref, condition = st.draft.condition, asking = st.draft.asking, added = now,
                    side = side, priceDoubt = ref != null && s.priceCard.priceCheck != null)
            b.replace(cart.copy(lines = lines, trade = cart.trade || side == Side.GIVE))
        }
        val where = if (st.scanTarget == Side.GIVE) "I give ($cartName)" else cartName
        _state.update { it.copy(draft = it.draft.copy(asking = null), message = "Added to $where") }
    }

    fun selectCart(id: Long) = updateBook { it.copy(activeId = id) }
    fun newCart() = updateBook { it.newCart(System.currentTimeMillis()) }
    fun renameCart(id: Long, name: String) = updateBook { b ->
        b.carts.firstOrNull { it.id == id }?.let { b.replace(it.copy(name = name.trim().ifEmpty { it.name })) } ?: b
    }
    fun deleteCart(id: Long) = updateBook { b -> b.copy(carts = b.carts.filter { it.id != id }) }

    private fun updateCart(cartId: Long, f: (Cart) -> Cart) = updateBook { b ->
        b.carts.firstOrNull { it.id == cartId }?.let { b.replace(f(it)) } ?: b
    }

    /** the I give side on or off (off only while it is empty) */
    fun setTrade(cartId: Long, on: Boolean) = updateCart(cartId) { c ->
        if (!on && c.lines.any { it.side == Side.GIVE && it.active }) c else c.copy(trade = on, cash = if (on) c.cash else null)
    }

    /** trade rates as shares (0.8 = 80 %) */
    fun setRates(cartId: Long, give: Double? = null, get: Double? = null) = updateCart(cartId) { c ->
        c.copy(giveRate = give?.coerceIn(0.0, 2.0) ?: c.giveRate, getRate = get?.coerceIn(0.0, 2.0) ?: c.getRate)
    }

    /** the cash agreed in a trade: + you pay, - the vendor pays you, null = the balance */
    fun setCash(cartId: Long, cash: Double?) = updateCart(cartId) { it.copy(cash = cash?.let { v -> TradingMath.round2(v) }) }

    /**
     * One price for several cards (a bundle, or the whole cart): split over them by market
     * value to the cent, each card's negotiated price set and marked agreed.
     */
    fun agreeTotal(cartId: Long, lineIds: List<Long>, total: Double) = updateCart(cartId) { c ->
        val chosen = c.lines.filter { it.id in lineIds && it.active }
        if (chosen.isEmpty()) return@updateCart c
        val shares = TradingMath.split(total, chosen.map { ((it.market ?: it.asking ?: 0.0) * it.qty) })
        val each = chosen.zip(shares).associate { (l, t) -> l.id to t / l.qty }
        c.copy(lines = c.lines.map { l -> each[l.id]?.let { l.copy(negotiated = it, status = LineStatus.AGREED) } ?: l })
    }

    /** a new show (or day) with an optional budget; open carts move along */
    fun startShow(name: String, budget: Double?) = updateBook { it.startShow(name.trim().ifEmpty { "Show" }, budget, System.currentTimeMillis()) }
    fun updateShow(name: String, budget: Double?) = updateBook { it.updateShow(name.trim().ifEmpty { "Show" }, budget) }

    fun updateLine(cartId: Long, lineId: Long, f: (CartLine) -> CartLine) = updateBook { b ->
        val c = b.carts.firstOrNull { it.id == cartId } ?: return@updateBook b
        b.replace(c.copy(lines = c.lines.map { if (it.id == lineId) f(it) else it }))
    }

    /** Paid: the cart's cards are bought; it moves to the deal history and the next open cart becomes active. */
    fun markPaid(cartId: Long, payment: String) = updateBook { b ->
        val c = b.carts.firstOrNull { it.id == cartId } ?: return@updateBook b
        val paid = c.copy(paidAt = System.currentTimeMillis(), payment = payment,
            lines = c.lines.map { if (it.active) it.copy(status = LineStatus.BOUGHT) else it })
        val next = b.replace(paid)
        next.copy(activeId = next.open.lastOrNull()?.id)
    }

    /** A paid deal back to an open cart (to fix a mistake). */
    fun reopen(cartId: Long) = updateBook { b ->
        val c = b.carts.firstOrNull { it.id == cartId } ?: return@updateBook b
        b.replace(c.copy(paidAt = null, payment = null,
            lines = c.lines.map { if (it.status == LineStatus.BOUGHT) it.copy(status = LineStatus.AGREED) else it }))
            .copy(activeId = cartId)
    }

    /** Writes these carts as CSV; returns the number of cards. */
    fun saveDeals(resolver: android.content.ContentResolver, uri: android.net.Uri, carts: List<Cart>): Result<Int> = runCatching {
        val csv = TradingCsv.build(carts, java.util.Locale.getDefault(), _state.value.book.shows)
        resolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("cannot write the file")
        carts.sumOf { it.cards }
    }.onFailure { Log.e(TAG, "saving the deals failed", it) }

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
        logPack(EventLog.Reason.ADD_AGAIN)
    }
    fun undoLast() {
        _state.update { it.copy(session = it.session.dropLast(1)) }
        logPack(EventLog.Reason.REMOVE_LAST)
    }

    /** Swiped away: the card leaves the pack. It is not taken again until another card was in front of the camera. */
    fun dismissShown() {
        kept = null
        _state.update { st ->
            val s = st.shown ?: return@update st
            st.copy(shown = null, picks = null, session = st.session.filter { it.serial != s.serial })
        }
        logPack(EventLog.Reason.SWIPE)
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
        _state.update { it.copy(shown = shown, paused = true, picks = null, draft = DealDraft(it.settings.tradingCondition)) }
        logPack(EventLog.Reason.SHOWN, "search")
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
        _state.update { st -> st.copy(shown = shown, picks = null, session = replaceInSession(st.session, shown, oldSerial = s.serial), tray = replaceInSession(st.tray, shown, oldSerial = s.serial)) }
        logPack(EventLog.Reason.SEARCH_REPLACE)
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
        _state.update { st -> st.copy(shown = shown, session = replaceInSession(st.session, shown, oldSerial = s.serial), tray = replaceInSession(st.tray, shown, oldSerial = s.serial)) }
        logPack(EventLog.Reason.OTHER_CARD)
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

    fun chooseLanguage(info: CardInfo) {
        _state.update { st ->
            val s = st.shown ?: return@update st
            val shown = build(s.group, info, s.text, s.confidence, s.alternatives, true, s.fromPhoto, s.preview, s.scan)
                .copy(serial = s.serial, printing = s.printing, printingByUser = s.printingByUser, foil = s.foil, versionByUser = true)
                .let { if (it.priceCard.printing(s.printing)?.kind != s.printing) it.copy(printing = defaultPrinting(it.priceCard)) else it }
            tracker.lock(s.group)
            st.copy(shown = shown, session = replaceInSession(st.session, shown), tray = replaceInSession(st.tray, shown))
        }
        logPack(EventLog.Reason.VERSION)
    }

    fun choosePrinting(kind: Printing.Kind) {
        _state.update { st ->
            val s = st.shown ?: return@update st
            val updated = s.copy(printing = kind, printingByUser = true, locked = true)
            tracker.lock(s.group)
            st.copy(shown = updated, session = replaceInSession(st.session, updated), tray = replaceInSession(st.tray, updated))
        }
        logPack(EventLog.Reason.PRINTING)
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
        logPack(EventLog.Reason.NEXT)
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
            .putBoolean("trading", new.trading).putString("trade_condition", new.tradingCondition.name)
            .putString("trade_reference", new.reference.name).putBoolean("webshop", new.webshop)
            .putFloat("price_step", new.priceStep.toFloat())
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
        saveDispatcher.close()
        foil?.reset()
    }

    companion object {
        const val TAG = "CardScanner"
        /** further than this the analysed picture gets too coarse */
        const val MAX_ZOOM = 5f
        /** scanned cards kept in Trading Mode's tray */
        const val TRAY_SIZE = 20
        /** languages with their own set ids and script */
        val ASIAN = setOf("ja", "ko", "zh-tw", "zh-cn", "th")
    }
}
