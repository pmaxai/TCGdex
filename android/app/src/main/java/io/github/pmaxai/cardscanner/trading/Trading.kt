package io.github.pmaxai.cardscanner.trading

import io.github.pmaxai.cardscanner.data.Printing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToLong

/** Cardmarket's condition scale; [share] = part of the reference price a card in that condition is worth. */
enum class Condition(val label: String, val share: Double) {
    NM("NM", 1.00), EX("EX", 0.85), GD("GD", 0.75), LP("LP", 0.65), PL("PL", 0.50), PO("PO", 0.30)
}

enum class LineStatus(val label: String) {
    CONSIDERING("Considering"), AGREED("Agreed"), BOUGHT("Bought"), DROPPED("Dropped")
}

/** Asking (or negotiated) price against the market value. */
enum class Verdict(val label: String) {
    GREAT("Great deal"), GOOD("Good price"), FAIR("Fair"), EXPENSIVE("Expensive")
}

/** Which Cardmarket price the market value starts from. */
enum class Reference(val label: String) {
    TREND("Trend"), AVG30("30-day average"), LOW("Lowest price")
}

/**
 * The numbers of Trading Mode, in one place: market value for a condition, the verdict on
 * a price, the opening offer and the walk-away price. Defaults agreed in the concept.
 */
object TradingMath {
    /** opening offer = market value x this */
    const val OFFER_SHARE = 0.85
    /** walk away above market value x this */
    const val WALK_AWAY_SHARE = 1.00
    /** asking price / market value: up to 80 % great, 95 % good, 110 % fair, above expensive */
    const val GREAT_UP_TO = 0.80
    const val GOOD_UP_TO = 0.95
    const val FAIR_UP_TO = 1.10
    private const val EPS = 1e-9

    fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

    /** The Cardmarket price of a printing the market value starts from (falls back to the trend). */
    fun reference(p: Printing?, ref: Reference): Double? = when (ref) {
        Reference.TREND -> p?.eur
        Reference.AVG30 -> p?.eur30 ?: p?.eur
        Reference.LOW -> p?.eurLow ?: p?.eur
    }?.takeIf { it > 0 }

    fun market(reference: Double, condition: Condition, languageFactor: Double = 1.0): Double =
        round2(reference * condition.share * languageFactor)

    fun verdict(price: Double, market: Double): Verdict? {
        if (market <= 0 || price < 0) return null
        val r = price / market
        return when {
            r <= GREAT_UP_TO + EPS -> Verdict.GREAT
            r <= GOOD_UP_TO + EPS -> Verdict.GOOD
            r <= FAIR_UP_TO + EPS -> Verdict.FAIR
            else -> Verdict.EXPENSIVE
        }
    }

    /** market value x 85 %, rounded down: to 10 cents under 5 EUR, whole euros under 50, 5 EUR steps above */
    fun offer(market: Double): Double {
        val t = market * OFFER_SHARE + EPS
        return round2(when {
            t < 5 -> floor(t * 10) / 10
            t < 50 -> floor(t)
            else -> floor(t / 5) * 5
        })
    }

    fun walkAway(market: Double): Double = round2(market * WALK_AWAY_SHARE)

    /** trend against the 30-day average (+0.12 = 12 % up), null without both */
    fun trend(p: Printing?): Double? {
        val now = p?.eur ?: return null
        val avg = p.eur30?.takeIf { it > 0 } ?: return null
        return (now - avg) / avg
    }

    /** few recent sales: no 30-day average, or the lowest offer under half the trend */
    fun fewSales(p: Printing?): Boolean {
        val now = p?.eur ?: return false
        return p.eur30 == null || (p.eurLow != null && p.eurLow < 0.5 * now)
    }
}

/** One card in a cart. Prices are per piece, in EUR; [reference] is kept so the condition can change later. */
data class CartLine(
    val id: Long,
    val cardId: String,
    val lang: String,
    val group: String,
    val name: String,
    val setLine: String,
    val image: String?,
    val printing: String?,
    val reference: Double?,
    val condition: Condition,
    val qty: Int = 1,
    val asking: Double? = null,
    val negotiated: Double? = null,
    val status: LineStatus = LineStatus.CONSIDERING,
    val added: Long,
) {
    val market: Double? get() = reference?.let { TradingMath.market(it, condition) }
    /** what one costs: the negotiated price, else the asking price */
    val price: Double? get() = negotiated ?: asking
    val verdict: Verdict? get() { val p = price ?: return null; val m = market ?: return null; return TradingMath.verdict(p, m) }
    val active: Boolean get() = status != LineStatus.DROPPED
    val printingLabel: String? get() = printing?.let { k -> Printing.Kind.values().firstOrNull { it.name == k }?.label }
}

/** The cart of one booth (one vendor). Paid carts are the deal history. */
data class Cart(
    val id: Long,
    val name: String,
    val created: Long,
    val lines: List<CartLine> = emptyList(),
    val paidAt: Long? = null,
    val payment: String? = null,
) {
    val activeLines: List<CartLine> get() = lines.filter { it.active }
    val cards: Int get() = activeLines.sumOf { it.qty }
    val marketTotal: Double get() = TradingMath.round2(activeLines.sumOf { (it.market ?: 0.0) * it.qty })
    val askingTotal: Double get() = TradingMath.round2(activeLines.sumOf { (it.asking ?: 0.0) * it.qty })
    /** what you pay: negotiated (else asking) prices */
    val payTotal: Double get() = TradingMath.round2(activeLines.sumOf { (it.price ?: 0.0) * it.qty })
    /** market value minus price, over the lines that have both */
    val savedVsMarket: Double get() = TradingMath.round2(activeLines.filter { it.price != null && it.market != null }
        .sumOf { (it.market!! - it.price!!) * it.qty })
    /** market value of the lines that have a price (the base of the saved share) */
    val pricedMarket: Double get() = TradingMath.round2(activeLines.filter { it.price != null && it.market != null }
        .sumOf { it.market!! * it.qty })
    val savedVsAsking: Double get() = TradingMath.round2(activeLines.filter { it.asking != null && it.price != null }
        .sumOf { (it.asking!! - it.price!!) * it.qty })
    /** lines nobody has put a price on yet */
    val unpriced: Int get() = activeLines.count { it.price == null }
}

/** All carts: open ones (one is active) and paid ones (the deal history). */
data class TradingBook(val carts: List<Cart> = emptyList(), val activeId: Long? = null, val nextId: Long = 1) {
    val open: List<Cart> get() = carts.filter { it.paidAt == null }
    val paid: List<Cart> get() = carts.filter { it.paidAt != null }.sortedByDescending { it.paidAt }
    val active: Cart? get() = open.firstOrNull { it.id == activeId } ?: open.lastOrNull()

    fun replace(cart: Cart): TradingBook = copy(carts = carts.map { if (it.id == cart.id) cart else it })

    /** a new, empty cart named after the next booth number; it becomes the active one */
    fun newCart(now: Long): TradingBook {
        val n = (carts.mapNotNull { Regex("""^Booth (\d+)$""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val c = Cart(nextId, "Booth $n", now)
        return copy(carts = carts + c, activeId = c.id, nextId = nextId + 1)
    }

    /** the active cart, created when there is none */
    fun withActive(now: Long): Pair<TradingBook, Cart> {
        active?.let { return this to it }
        val b = newCart(now)
        return b to b.active!!
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("activeId", activeId ?: JSONObject.NULL)
        put("nextId", nextId)
        put("carts", JSONArray().apply { carts.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): TradingBook = TradingBook(
            carts = o.optJSONArray("carts")?.let { a -> (0 until a.length()).map { cartFromJson(a.getJSONObject(it)) } } ?: emptyList(),
            activeId = if (o.isNull("activeId")) null else o.optLong("activeId"),
            nextId = o.optLong("nextId", 1),
        )
    }
}

private fun Cart.toJson() = JSONObject().apply {
    put("id", id); put("name", name); put("created", created)
    put("paidAt", paidAt ?: JSONObject.NULL); put("payment", payment ?: JSONObject.NULL)
    put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
}

private fun CartLine.toJson() = JSONObject().apply {
    put("id", id); put("cardId", cardId); put("lang", lang); put("group", group); put("name", name); put("setLine", setLine)
    put("image", image ?: JSONObject.NULL); put("printing", printing ?: JSONObject.NULL)
    put("reference", reference ?: JSONObject.NULL); put("condition", condition.name); put("qty", qty)
    put("asking", asking ?: JSONObject.NULL); put("negotiated", negotiated ?: JSONObject.NULL)
    put("status", status.name); put("added", added)
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun cartFromJson(o: JSONObject) = Cart(
    id = o.getLong("id"), name = o.optString("name"), created = o.optLong("created"),
    paidAt = if (o.isNull("paidAt")) null else o.optLong("paidAt"), payment = o.str("payment"),
    lines = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { lineFromJson(a.getJSONObject(it)) } } ?: emptyList(),
)

private fun lineFromJson(o: JSONObject) = CartLine(
    id = o.getLong("id"), cardId = o.optString("cardId"), lang = o.optString("lang"), group = o.optString("group"),
    name = o.optString("name"), setLine = o.optString("setLine"), image = o.str("image"), printing = o.str("printing"),
    reference = o.dbl("reference"),
    condition = runCatching { Condition.valueOf(o.optString("condition")) }.getOrDefault(Condition.NM),
    qty = o.optInt("qty", 1), asking = o.dbl("asking"), negotiated = o.dbl("negotiated"),
    status = runCatching { LineStatus.valueOf(o.optString("status")) }.getOrDefault(LineStatus.CONSIDERING),
    added = o.optLong("added"),
)

/** Carts on the phone: one JSON file, written whole and swapped in, so a crash never leaves half a file. */
class TradingStore(private val file: File) {
    fun load(): TradingBook = runCatching {
        if (!file.exists()) TradingBook() else TradingBook.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }.getOrElse { TradingBook() }

    @Synchronized
    fun save(book: TradingBook) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(book.toJson().toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
