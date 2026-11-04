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

/** Your offer rated as the vendor buying from you: vendors usually pay 75-90 % of the market value. */
enum class SellVerdict(val label: String) {
    LOW("Low offer"), USUAL("Usual offer"), GOOD("Good offer")
}

/** Which side of a deal a card is on: cards you get (buy or trade in) or cards you give (sell or trade away). */
enum class Side(val label: String) { GET("I get"), GIVE("I give") }

/** A card show (or a day of trading): its name and the budget for it. */
data class ShowInfo(val id: Long, val name: String, val started: Long, val budget: Double? = null)

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
    /** vendor's offer for your card / market value: under 75 % low, up to 90 % usual, above good */
    const val SELL_USUAL_FROM = 0.75
    const val SELL_GOOD_ABOVE = 0.90
    /** trade rates: your cards count at 80 % of their market value, the vendor's at 100 % */
    const val GIVE_RATE = 0.80
    const val GET_RATE = 1.00
    /** the budget bar turns orange from here */
    const val BUDGET_WARN = 0.90
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

    fun sellVerdict(offer: Double, market: Double): SellVerdict? {
        if (market <= 0 || offer < 0) return null
        val r = offer / market
        return when {
            r < SELL_USUAL_FROM - EPS -> SellVerdict.LOW
            r <= SELL_GOOD_ABOVE + EPS -> SellVerdict.USUAL
            else -> SellVerdict.GOOD
        }
    }

    /** what vendors usually pay for a card of this market value */
    fun sellRange(market: Double): Pair<Double, Double> = round2(market * SELL_USUAL_FROM) to round2(market * SELL_GOOD_ABOVE)

    /**
     * Splits [total] over items in proportion to [weights] (their market values), to the
     * cent, summing exactly to the total: the cents left after rounding down go to the
     * items with the largest remainders. No positive weight: split evenly.
     */
    fun split(total: Double, weights: List<Double>): List<Double> {
        if (weights.isEmpty()) return emptyList()
        val w = weights.map { if (it > 0) it else 0.0 }
        val sum = w.sum()
        val share = if (sum > 0) w.map { it / sum } else List(w.size) { 1.0 / w.size }
        val cents = (total * 100).roundToLong()
        val raw = share.map { it * cents }
        val out = raw.map { floor(it + EPS).toLong() }.toMutableList()
        var rest = cents - out.sum()
        for (i in raw.indices.sortedByDescending { raw[it] - out[it] }) {
            if (rest <= 0) break
            out[i] += 1
            rest--
        }
        return out.map { it / 100.0 }
    }

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
    /** I get (buy, trade in) or I give (sell, trade away); for I give, asking = the vendor's offer */
    val side: Side = Side.GET,
) {
    val market: Double? get() = reference?.let { TradingMath.market(it, condition) }
    /** one piece: the negotiated price, else the asking price (I give: the vendor's offer) */
    val price: Double? get() = negotiated ?: asking
    /** buying: the price against the market value */
    val verdict: Verdict? get() {
        if (side != Side.GET) return null
        val p = price ?: return null; val m = market ?: return null; return TradingMath.verdict(p, m)
    }
    /** selling: the vendor's offer against the market value */
    val sellVerdict: SellVerdict? get() {
        if (side != Side.GIVE) return null
        val p = price ?: return null; val m = market ?: return null; return TradingMath.sellVerdict(p, m)
    }
    val active: Boolean get() = status != LineStatus.DROPPED
    val printingLabel: String? get() = printing?.let { k -> Printing.Kind.values().firstOrNull { it.name == k }?.label }
}

/**
 * The cart of one booth (one vendor). Paid carts are the deal history. A trade adds the
 * I give side: each side counts at its rate (your cards 80 %, the vendor's 100 % of
 * market value) unless a card has its own price, and the balance is the cash difference.
 */
data class Cart(
    val id: Long,
    val name: String,
    val created: Long,
    val lines: List<CartLine> = emptyList(),
    val paidAt: Long? = null,
    val payment: String? = null,
    /** the I give side is open */
    val trade: Boolean = false,
    val giveRate: Double = TradingMath.GIVE_RATE,
    val getRate: Double = TradingMath.GET_RATE,
    /** cash agreed in the end: + you pay, - the vendor pays you (null: the balance) */
    val cash: Double? = null,
    val showId: Long? = null,
) {
    val activeLines: List<CartLine> get() = lines.filter { it.active }
    val getLines: List<CartLine> get() = activeLines.filter { it.side == Side.GET }
    val giveLines: List<CartLine> get() = activeLines.filter { it.side == Side.GIVE }
    val isTrade: Boolean get() = trade || lines.any { it.side == Side.GIVE }

    /** cards you get */
    val cards: Int get() = getLines.sumOf { it.qty }
    val giveCards: Int get() = giveLines.sumOf { it.qty }
    val marketTotal: Double get() = TradingMath.round2(getLines.sumOf { (it.market ?: 0.0) * it.qty })
    val marketGive: Double get() = TradingMath.round2(giveLines.sumOf { (it.market ?: 0.0) * it.qty })
    val askingTotal: Double get() = TradingMath.round2(getLines.sumOf { (it.asking ?: 0.0) * it.qty })
    /** buying: what you pay, negotiated (else asking) prices of the cards you get */
    val payTotal: Double get() = TradingMath.round2(getLines.sumOf { (it.price ?: 0.0) * it.qty })
    /** market value minus price, over the cards you get that have both */
    val savedVsMarket: Double get() = TradingMath.round2(getLines.filter { it.price != null && it.market != null }
        .sumOf { (it.market!! - it.price!!) * it.qty })
    /** market value of the cards you get that have a price (the base of the saved share) */
    val pricedMarket: Double get() = TradingMath.round2(getLines.filter { it.price != null && it.market != null }
        .sumOf { it.market!! * it.qty })
    val savedVsAsking: Double get() = TradingMath.round2(getLines.filter { it.asking != null && it.price != null }
        .sumOf { (it.asking!! - it.price!!) * it.qty })
    /** cards you get that nobody has put a price on yet (in a trade they count at the rate) */
    val unpriced: Int get() = if (isTrade) 0 else getLines.count { it.price == null }

    /** I get: own prices, else (trade) market value x rate */
    val getValue: Double get() = TradingMath.round2(getLines.sumOf {
        (it.price ?: if (isTrade) (it.market ?: 0.0) * getRate else 0.0) * it.qty })
    /** I give: the vendor's offer, else market value x rate */
    val giveValue: Double get() = TradingMath.round2(giveLines.sumOf { (it.price ?: (it.market ?: 0.0) * giveRate) * it.qty })
    /** + you pay, - the vendor pays you */
    val balance: Double get() = TradingMath.round2(getValue - giveValue)
    /** the cash that changes hands: as agreed, else the balance */
    val settle: Double get() = cash ?: balance
    /** at market value: what you get minus what you give, cards and cash */
    val netAtMarket: Double get() = TradingMath.round2(marketTotal - marketGive - settle)
    /** the active cards (both sides) that have no market value */
    val withoutMarket: Int get() = activeLines.count { it.market == null }
}

/** Totals of several deals (a show, a day). */
data class DealSummary(val deals: Int, val spent: Double, val received: Double, val marketIn: Double, val marketOut: Double,
                       val saved: Double, val bought: Int, val given: Int) {
    companion object {
        fun of(carts: List<Cart>) = DealSummary(
            deals = carts.size,
            spent = TradingMath.round2(carts.sumOf { maxOf(it.settle, 0.0) }),
            received = TradingMath.round2(carts.sumOf { maxOf(-it.settle, 0.0) }),
            marketIn = TradingMath.round2(carts.sumOf { it.marketTotal }),
            marketOut = TradingMath.round2(carts.sumOf { it.marketGive }),
            saved = TradingMath.round2(carts.sumOf { it.savedVsMarket }),
            bought = carts.sumOf { it.cards },
            given = carts.sumOf { it.giveCards },
        )
    }
}

/** All carts: open ones (one is active) and paid ones (the deal history). */
data class TradingBook(
    val carts: List<Cart> = emptyList(),
    val activeId: Long? = null,
    val nextId: Long = 1,
    val shows: List<ShowInfo> = emptyList(),
    /** the show new carts belong to */
    val showId: Long? = null,
) {
    val open: List<Cart> get() = carts.filter { it.paidAt == null }
    val paid: List<Cart> get() = carts.filter { it.paidAt != null }.sortedByDescending { it.paidAt }
    val active: Cart? get() = open.firstOrNull { it.id == activeId } ?: open.lastOrNull()
    val show: ShowInfo? get() = shows.firstOrNull { it.id == showId }

    /** spent at the current show: cash paid in its closed deals */
    val spent: Double get() = TradingMath.round2(paid.filter { it.showId != null && it.showId == showId }.sumOf { maxOf(it.settle, 0.0) })
    /** agreed but not paid yet at the current show: agreed cards you get in its open carts */
    val agreed: Double get() = TradingMath.round2(open.filter { it.showId != null && it.showId == showId }
        .sumOf { c -> c.getLines.filter { it.status == LineStatus.AGREED }.sumOf { (it.price ?: 0.0) * it.qty } })

    /** a new show (or day): new carts belong to it; carts still open move along */
    fun startShow(name: String, budget: Double?, now: Long): TradingBook {
        val sh = ShowInfo(nextId, name, now, budget)
        return copy(shows = shows + sh, showId = sh.id, nextId = nextId + 1,
            carts = carts.map { if (it.paidAt == null) it.copy(showId = sh.id) else it })
    }

    fun updateShow(name: String, budget: Double?): TradingBook =
        copy(shows = shows.map { if (it.id == showId) it.copy(name = name, budget = budget) else it })

    fun replace(cart: Cart): TradingBook = copy(carts = carts.map { if (it.id == cart.id) cart else it })

    /** a new, empty cart named after the next booth number; it becomes the active one */
    fun newCart(now: Long): TradingBook {
        val n = (carts.mapNotNull { Regex("""^Booth (\d+)$""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val c = Cart(nextId, "Booth $n", now, showId = showId)
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
        put("showId", showId ?: JSONObject.NULL)
        put("shows", JSONArray().apply { shows.forEach { sh ->
            put(JSONObject().apply { put("id", sh.id); put("name", sh.name); put("started", sh.started); put("budget", sh.budget ?: JSONObject.NULL) })
        } })
    }

    companion object {
        fun fromJson(o: JSONObject): TradingBook = TradingBook(
            carts = o.optJSONArray("carts")?.let { a -> (0 until a.length()).map { cartFromJson(a.getJSONObject(it)) } } ?: emptyList(),
            activeId = if (o.isNull("activeId")) null else o.optLong("activeId"),
            nextId = o.optLong("nextId", 1),
            shows = o.optJSONArray("shows")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { j ->
                ShowInfo(j.getLong("id"), j.optString("name"), j.optLong("started"), j.dbl("budget")) } } } ?: emptyList(),
            showId = if (!o.has("showId") || o.isNull("showId")) null else o.optLong("showId"),
        )
    }
}

private fun Cart.toJson() = JSONObject().apply {
    put("id", id); put("name", name); put("created", created)
    put("paidAt", paidAt ?: JSONObject.NULL); put("payment", payment ?: JSONObject.NULL)
    put("trade", trade); put("giveRate", giveRate); put("getRate", getRate)
    put("cash", cash ?: JSONObject.NULL); put("showId", showId ?: JSONObject.NULL)
    put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
}

private fun CartLine.toJson() = JSONObject().apply {
    put("id", id); put("cardId", cardId); put("lang", lang); put("group", group); put("name", name); put("setLine", setLine)
    put("image", image ?: JSONObject.NULL); put("printing", printing ?: JSONObject.NULL)
    put("reference", reference ?: JSONObject.NULL); put("condition", condition.name); put("qty", qty)
    put("asking", asking ?: JSONObject.NULL); put("negotiated", negotiated ?: JSONObject.NULL)
    put("status", status.name); put("added", added); put("side", side.name)
}

private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)
internal fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun cartFromJson(o: JSONObject) = Cart(
    id = o.getLong("id"), name = o.optString("name"), created = o.optLong("created"),
    paidAt = if (o.isNull("paidAt")) null else o.optLong("paidAt"), payment = o.str("payment"),
    trade = o.optBoolean("trade", false), giveRate = o.dbl("giveRate") ?: TradingMath.GIVE_RATE,
    getRate = o.dbl("getRate") ?: TradingMath.GET_RATE, cash = o.dbl("cash"),
    showId = if (!o.has("showId") || o.isNull("showId")) null else o.optLong("showId"),
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
    side = runCatching { Side.valueOf(o.optString("side", "GET")) }.getOrDefault(Side.GET),
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
