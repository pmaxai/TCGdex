package io.github.pmaxai.cardscanner.store

import android.app.Activity
import org.json.JSONArray

/**
 * Buying in the app store (Google Play edition): the subscription, its extra devices (add-ons),
 * and Continue with Google. The Play edition has the real one (src/play, Play Billing and
 * Credential Manager); the direct edition sells on the website and has none (src/direct).
 */
interface StoreBilling {
    /** this edition sells in the store */
    val sells: Boolean

    /** Continue with Google: an ID token for [serverClientId]; null when the person cancelled */
    suspend fun googleIdToken(activity: Activity, serverClientId: String): String?

    /**
     * What the store sells of these products: product id → offer key → offer. The key is the
     * base plan id for the base price, and "basePlanId:offerId" for a special offer (a creator
     * code's discount).
     */
    suspend fun offers(productIds: List<String>): Map<String, Map<String, StoreOffer>>

    /** the install referrer Play kept for this install ("aff=MISTY"), null without one */
    suspend fun installReferrer(): String? = null

    /** the subscriptions the store knows for this phone's store account (running or pending) */
    suspend fun purchases(): List<StorePurchase>

    /** Opens the store's purchase sheet; answers when it closes. */
    suspend fun buy(activity: Activity, request: BuyRequest): BuyResult

    /** purchases that finish later (a pending payment that went through) */
    var onLatePurchases: ((List<StorePurchase>) -> Unit)?
}

/**
 * A base plan (monthly, yearly) of a product as the store sells it: the price and what buys it.
 * A special offer ([offerId]) may start with a lower price ([introPrice], for [introCycles]
 * billing periods) before the base price.
 */
data class StoreOffer(val productId: String, val basePlanId: String, val price: String, val offerToken: String,
                      val offerId: String? = null, val introPrice: String? = null, val introCycles: Int = 0) {
    companion object {
        /** the key of an offer in [StoreBilling.offers] */
        fun key(basePlanId: String, offerId: String?) = if (offerId == null) basePlanId else "$basePlanId:$offerId"
    }
}

/** A creator code (affiliate) the account is registered for, with its discount on the first subscription. */
data class Affiliate(val code: String, val discountPercent: Int?, val discountMonths: Int?, val googleOfferId: String?) {
    /** "20 % off for 3 months", "20 % off" */
    val discountText: String? get() = discountPercent?.takeIf { it > 0 }?.let { p ->
        "$p % off" + (discountMonths?.let { m -> " for " + if (m == 1) "1 month" else "$m months" } ?: "")
    }

    companion object {
        fun parse(o: org.json.JSONObject?): Affiliate? = o?.takeIf { it.optString("code").isNotBlank() }?.let {
            Affiliate(it.optString("code"), if (it.isNull("discount_percent")) null else it.optInt("discount_percent"),
                if (it.isNull("discount_months")) null else it.optInt("discount_months"),
                if (it.isNull("google_offer_id")) null else it.optString("google_offer_id").takeIf { s -> s.isNotBlank() })
        }

        /**
         * The creator code in an install referrer: `aff=MISTY` among its parameters (the website
         * links to Play with `referrer=aff%3DMISTY`); also once more URL-encoded. Null without one.
         */
        fun fromReferrer(referrer: String?): String? {
            var r = referrer?.trim().orEmpty()
            if (r.isEmpty()) return null
            if (!r.contains('=') && r.contains("%3D", ignoreCase = true)) r = java.net.URLDecoder.decode(r, "UTF-8")
            val code = r.split('&').map { it.split('=', limit = 2) }.firstOrNull { it.size == 2 && it[0].trim() == "aff" }?.get(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8").trim() }
            return code?.takeIf { it.isNotEmpty() && it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
        }
    }
}

/** A subscription purchase: one token for the plan and its add-ons. */
data class StorePurchase(
    val token: String,
    val products: List<String>,
    /** paid (not still pending) */
    val purchased: Boolean,
    /** the store_ref it was bought with */
    val accountRef: String?,
)

/** How a kept or swapped item of a running subscription is handled (Play's item replacement modes). */
enum class Replace { KEEP_EXISTING, CHARGE_PRORATED_PRICE, DEFERRED }

/** One item of a purchase: [replaces] the old product it takes the place of (kept items: itself). */
data class BuyItem(val offer: StoreOffer, val replaces: String? = null, val mode: Replace? = null)

/**
 * A purchase: the plan first, then its add-ons. [oldPurchaseToken]: a change of the running
 * subscription (every item it keeps is listed again, with [Replace.KEEP_EXISTING]).
 */
data class BuyRequest(val items: List<BuyItem>, val accountRef: String, val oldPurchaseToken: String? = null)

sealed class BuyResult {
    data class Bought(val purchases: List<StorePurchase>) : BuyResult()
    object Cancelled : BuyResult()
    data class Failed(val message: String) : BuyResult()
}

/** A plan on sale (`GET /store/offers`) with the Play product ids the shop entered. */
data class PlanOffer(
    val plan: String,
    val name: String,
    val features: List<String>,
    /** null while the shop has no Play product for it */
    val productId: String?,
    /** "monthly" → the base plan id */
    val basePlans: Map<String, String>,
    /** extra devices: one product per device, sold in this order */
    val deviceProductIds: List<String>,
    /** a creator code's discount: buy the base plan with this Play offer */
    val offerId: String? = null,
) {
    companion object {
        fun parse(a: JSONArray): List<PlanOffer> = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { o ->
            val g = o.optJSONObject("google")
            val bp = g?.optJSONObject("base_plans")
            val feats = o.optJSONArray("features")
            val dev = g?.optJSONArray("device_product_ids")
            PlanOffer(
                plan = o.optString("plan"), name = o.optString("name", o.optString("plan")),
                features = (0 until (feats?.length() ?: 0)).map { feats!!.optString(it) },
                productId = g?.optString("product_id")?.takeIf { it.isNotBlank() },
                basePlans = bp?.let { b -> b.keys().asSequence().associateWith { b.optString(it) } }.orEmpty(),
                deviceProductIds = (0 until (dev?.length() ?: 0)).map { dev!!.optString(it) }.filter { it.isNotBlank() },
                offerId = g?.takeIf { !it.isNull("offer_id") }?.optString("offer_id")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * An add-on on sale (`GET /store/offers` › `addons`): price data on top of a plan (PSA prices now;
 * eBay listings and more later). In Google Play it is a subscription of its own beside the plan's
 * purchase, with a monthly and a yearly base plan.
 */
data class AddonOffer(
    /** "psa_prices" */
    val id: String,
    val name: String,
    /** what it unlocks in the status's features (the add-on's id when the shop gives none) */
    val feature: String,
    /** the plans it can be added to (empty: any) */
    val plans: List<String>,
    /** null while the shop has no Play product for it */
    val productId: String?,
    /** "monthly" → the base plan id */
    val basePlans: Map<String, String>,
) {
    companion object {
        fun parse(a: JSONArray?): List<AddonOffer> = (0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.optJSONObject(i) }.mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val g = o.optJSONObject("google")
            val bp = g?.optJSONObject("base_plans")
            val plans = o.optJSONArray("plans")
            AddonOffer(id, o.optString("name").ifBlank { id }, o.optString("feature").ifBlank { id },
                (0 until (plans?.length() ?: 0)).map { plans!!.optString(it) }.filter { it.isNotBlank() },
                g?.optString("product_id")?.takeIf { it.isNotBlank() },
                bp?.let { b -> b.keys().asSequence().associateWith { b.optString(it) } }.orEmpty())
        }
    }
}

/** The direct edition (and a phone without Play): nothing is sold in a store. */
object NoBilling : StoreBilling {
    override val sells = false
    override suspend fun googleIdToken(activity: Activity, serverClientId: String): String? = null
    override suspend fun offers(productIds: List<String>): Map<String, Map<String, StoreOffer>> = emptyMap()
    override suspend fun purchases(): List<StorePurchase> = emptyList()
    override suspend fun buy(activity: Activity, request: BuyRequest): BuyResult = BuyResult.Failed("not sold in a store")
    override var onLatePurchases: ((List<StorePurchase>) -> Unit)? = null
}
