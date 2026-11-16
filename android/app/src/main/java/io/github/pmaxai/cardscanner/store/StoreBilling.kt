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

    /** what the store sells of these products: product id → base plan id → its offer */
    suspend fun offers(productIds: List<String>): Map<String, Map<String, StoreOffer>>

    /** the subscriptions the store knows for this phone's store account (running or pending) */
    suspend fun purchases(): List<StorePurchase>

    /** Opens the store's purchase sheet; answers when it closes. */
    suspend fun buy(activity: Activity, request: BuyRequest): BuyResult

    /** purchases that finish later (a pending payment that went through) */
    var onLatePurchases: ((List<StorePurchase>) -> Unit)?
}

/** A base plan (monthly, yearly) of a product as the store sells it: the price and what buys it. */
data class StoreOffer(val productId: String, val basePlanId: String, val price: String, val offerToken: String)

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
            )
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
