package io.github.pmaxai.cardscanner.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import io.github.pmaxai.cardscanner.BuildConfig
import io.github.pmaxai.cardscanner.account.AccountException
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.store.BuyItem
import io.github.pmaxai.cardscanner.store.BuyRequest
import io.github.pmaxai.cardscanner.store.BuyResult
import io.github.pmaxai.cardscanner.store.PlanOffer
import io.github.pmaxai.cardscanner.store.Replace
import io.github.pmaxai.cardscanner.store.StoreBilling
import io.github.pmaxai.cardscanner.store.StoreOffer
import io.github.pmaxai.cardscanner.store.StorePurchase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Buying in the store, as the screens see it. */
data class StoreUi(
    /** the plans on sale (`/store/offers`) */
    val offers: List<PlanOffer> = emptyList(),
    /** what the store sells of them: product id → base plan id → price and offer */
    val prices: Map<String, Map<String, StoreOffer>> = emptyMap(),
    /** this phone's running Play subscription of this account (for extra devices and plan changes) */
    val purchase: StorePurchase? = null,
    val loading: Boolean = false,
    /** a purchase sheet or a report runs */
    val busy: Boolean = false,
    val error: String? = null,
    /** "Restored", "Sent again when there is a connection" */
    val note: String? = null,
    /** purchases waiting to be reported */
    val waiting: Int = 0,
) {
    /** a plan's price for a period ("monthly"), null when the store doesn't sell it */
    fun price(offer: PlanOffer, period: String): StoreOffer? =
        offer.productId?.let { p -> offer.basePlans[period]?.let { prices[p]?.get(it) } }

    /** the plans the store really sells (both the shop's product ids and Play's answer) */
    val onSale: List<PlanOffer> get() = offers.filter { o -> o.productId != null && prices[o.productId]?.isNotEmpty() == true }

    /** the running subscription's plan as sold here, its purchase on this phone and the extra devices on it */
    fun running(st: AccountStatus?): Triple<PlanOffer, StorePurchase, List<String>>? {
        val plan = offers.firstOrNull { it.plan == st?.plan && it.productId != null } ?: return null
        val p = purchase ?: return null
        if (plan.productId !in p.products) return null
        return Triple(plan, p, plan.deviceProductIds.filter { it in p.products })
    }

    /** extra devices bought in the app, and how many the plan sells here */
    fun devices(st: AccountStatus?): Pair<Int, Int>? = running(st)?.let { (plan, _, have) -> have.size to plan.deviceProductIds.size }
}

/**
 * The Play edition's purchases: the plans on sale with the store's prices, buying one, extra
 * devices as add-ons of the running purchase, a plan change, Restore purchases. Every purchase is
 * reported to the server (`POST /store/google`), which checks it with Google and acknowledges
 * it; a report that doesn't get through waits and is sent again.
 */
class StoreCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val account: AccountCoordinator,
    val billing: StoreBilling,
    private val publish: ((StoreUi) -> StoreUi) -> Unit,
    private val current: () -> StoreUi,
    private val status: () -> AccountStatus?,
) {
    private val prefs = app.getSharedPreferences("store", Context.MODE_PRIVATE)
    private var retry: Job? = null

    val sells: Boolean get() = billing.sells

    init {
        billing.onLatePurchases = { list -> scope.launch(Dispatchers.IO) { list.forEach { report(it, null) } } }
    }

    // ---------------------------------------------------------------- offers
    /** The plans, the store's prices and this phone's purchase (connected, Play edition). */
    fun refresh() {
        if (!sells || account.store.token == null) return
        scope.launch(Dispatchers.IO) { if (loadOffers()) findPurchase() }
    }

    private suspend fun loadOffers(): Boolean {
        val token = account.store.token ?: return false
        publish { it.copy(loading = true, error = null) }
        return try {
            val offers = PlanOffer.parse(account.api()!!.offers(token))
            val ids = offers.flatMap { o -> listOfNotNull(o.productId) + o.deviceProductIds }
            val prices = billing.offers(ids)
            publish { it.copy(offers = offers, prices = prices, loading = false) }
            true
        } catch (e: AccountException) {
            publish { it.copy(loading = false, error = e.friendly) }
            false
        } catch (e: Exception) {
            publish { it.copy(loading = false, error = "The plans can't be loaded without a connection.") }
            false
        }
    }

    /** this account's running Play purchase on this phone */
    private suspend fun findPurchase(): StorePurchase? {
        val plans = current().offers.mapNotNull { it.productId }.toSet()
        val ref = status()?.storeRef
        val p = billing.purchases().firstOrNull { p -> p.products.any { it in plans } && (p.accountRef == null || p.accountRef == ref) }
        publish { it.copy(purchase = p) }
        return p
    }

    /** At start and after connecting (Play edition): the plans, then what waits and what the store holds goes to the server. */
    fun start() {
        if (!sells || account.store.token == null) return
        publish { it.copy(waiting = waiting().length()) }
        scope.launch(Dispatchers.IO) {
            loadOffers()
            sendWaiting()
            billing.purchases().forEach { report(it, null, quiet = true) }
            findPurchase()
        }
    }

    // ---------------------------------------------------------------- buying
    /** Buy [offer]'s plan for [period] ("monthly", "yearly"). Only without a running subscription (one paid source). */
    fun buy(activity: Activity, offer: PlanOffer, period: String) {
        val st = status() ?: return
        val ref = st.storeRef ?: return publish { it.copy(error = "The account has no store reference yet. Check again.") }
        if (st.active) return publish { it.copy(error = "This account already has a subscription.") }
        val o = current().price(offer, period) ?: return publish { it.copy(error = "Google Play doesn't sell this plan right now.") }
        launchFlow(activity, BuyRequest(listOf(BuyItem(o)), ref), offer.productId!!, period)
    }

    /** One more device on the running Play subscription (an add-on), up to what the plan sells. */
    fun addDevice(activity: Activity) = changeDevices(activity, +1)

    /** One device fewer: it runs to the end of its period. */
    fun removeDevice(activity: Activity) = changeDevices(activity, -1)

    private fun running(): Triple<PlanOffer, StorePurchase, List<String>>? = current().running(status())

    private fun changeDevices(activity: Activity, by: Int) {
        val (plan, p, have) = running() ?: return publish { it.copy(error = "No running Google Play subscription on this phone. Try Restore purchases.") }
        val ref = status()?.storeRef ?: return
        val period = periodOf(p) ?: return publish { it.copy(error = "Google Play doesn't sell the extra devices for this subscription.") }
        val planOffer = current().price(plan, period) ?: return publish { it.copy(error = "Google Play doesn't sell this plan right now.") }
        val keep = if (by > 0) have else have.dropLast(1)
        val items = ArrayList<BuyItem>()
        items += BuyItem(planOffer, plan.productId, Replace.KEEP_EXISTING)
        for (d in keep) items += BuyItem(deviceOffer(d, plan, period) ?: return noDevices(), d, Replace.KEEP_EXISTING)
        if (by > 0) {
            val next = plan.deviceProductIds.firstOrNull { it !in have } ?: return publish { it.copy(error = "No more extra devices in the app.") }
            items += BuyItem(deviceOffer(next, plan, period) ?: return noDevices())
        } else if (have.isEmpty()) return
        launchFlow(activity, BuyRequest(items, ref, oldPurchaseToken = p.token), plan.productId!!, period)
    }

    private fun noDevices() = publish { it.copy(error = "Google Play doesn't sell the extra devices right now.") }

    private fun deviceOffer(productId: String, plan: PlanOffer, period: String): StoreOffer? =
        plan.basePlans[period]?.let { current().prices[productId]?.get(it) }

    /**
     * Switch the running Play subscription to [to] (Collector ↔ Creator), its extra devices too:
     * a bigger plan at once (the rest of the period charged in proportion), a smaller one from the
     * next renewal.
     */
    fun changePlan(activity: Activity, to: PlanOffer) {
        val (from, p, have) = running() ?: return publish { it.copy(error = "No running Google Play subscription on this phone. Try Restore purchases.") }
        val ref = status()?.storeRef ?: return
        val period = periodOf(p) ?: return
        val toOffer = current().price(to, period) ?: return publish { it.copy(error = "Google Play doesn't sell ${to.name} right now.") }
        val offers = current().offers
        val mode = if (offers.indexOf(to) > offers.indexOf(from)) Replace.CHARGE_PRORATED_PRICE else Replace.DEFERRED
        val items = ArrayList<BuyItem>()
        items += BuyItem(toOffer, from.productId, mode)
        // the extra devices move to the new plan's device products (the server counts only those)
        have.forEachIndexed { i, old ->
            val new = to.deviceProductIds.getOrNull(i) ?: return@forEachIndexed
            items += BuyItem(deviceOffer(new, to, period) ?: return noDevices(), old, mode)
        }
        launchFlow(activity, BuyRequest(items, ref, oldPurchaseToken = p.token), to.productId!!, period)
    }

    /**
     * The running purchase's billing period (its add-ons must have the same): what the server
     * says, else what this phone bought, else from the renewal date (more than 32 days away: yearly).
     */
    private fun periodOf(p: StorePurchase): String? {
        val st = status()
        st?.json?.optJSONObject("subscription")?.optString("base_plan")?.takeIf { it.isNotBlank() }?.let { bp ->
            current().offers.firstOrNull()?.basePlans?.entries?.firstOrNull { it.value == bp }?.let { return it.key }
        }
        prefs.getString("period:" + p.token, null)?.let { return it }
        val renews = st?.renewsAt ?: st?.endsAt ?: return "monthly"
        return if (renews - System.currentTimeMillis() > 32 * 86_400_000L) "yearly" else "monthly"
    }

    private fun launchFlow(activity: Activity, req: BuyRequest, planProduct: String, period: String) {
        if (current().busy) return
        publish { it.copy(busy = true, error = null, note = null) }
        scope.launch {
            val r = try { billing.buy(activity, req) } catch (e: Exception) { BuyResult.Failed(e.message ?: e.toString()) }
            when (r) {
                is BuyResult.Bought -> {
                    r.purchases.forEach { prefs.edit().putString("period:" + it.token, period).apply() }
                    launch(Dispatchers.IO) {
                        r.purchases.forEach { report(it, planProduct) }
                        findPurchase()
                        publish { it.copy(busy = false) }
                    }
                }
                BuyResult.Cancelled -> publish { it.copy(busy = false) }
                is BuyResult.Failed -> publish { it.copy(busy = false, error = r.message) }
            }
        }
    }

    // ---------------------------------------------------------------- reporting
    /** Restore purchases: every purchase the store holds goes to the server again. */
    fun restore() {
        if (!sells) return
        publish { it.copy(busy = true, error = null, note = null) }
        scope.launch(Dispatchers.IO) {
            val all = billing.purchases()
            var sent = 0
            all.forEach { if (report(it, null)) sent++ }
            findPurchase()
            publish { it.copy(busy = false, note = if (all.isEmpty()) "Google Play has no subscription for this phone's Google account."
                else if (sent > 0) "Restored." else it.note) }
        }
    }

    /**
     * Tells the server about a purchase (the plan's product id; it reads the rest from Google).
     * Not reachable: kept and sent again. True when the server took it.
     */
    private fun report(p: StorePurchase, planProduct: String?, quiet: Boolean = false): Boolean {
        val token = account.store.token ?: return false
        val product = planProduct ?: planOf(p) ?: p.products.firstOrNull() ?: return false
        return try {
            val o = account.api()!!.storeGoogle(token, product, p.token)
            account.applyStatus(o)
            forget(p.token)
            true
        } catch (e: AccountException) {
            when {
                e.lostToken -> { }
                e.status >= 500 || e.error == "store_unavailable" -> keep(p.token, product)
                else -> { forget(p.token); if (!quiet) publish { it.copy(error = e.friendly) } }
            }
            false
        } catch (e: IOException) {
            keep(p.token, product)
            if (!quiet) publish { it.copy(note = "No connection: the purchase is kept and sent as soon as there is one.") }
            false
        } catch (e: Exception) {
            Log.w(TAG, "report failed", e)
            keep(p.token, product)
            false
        }
    }

    /** the plan product among a purchase's items */
    private fun planOf(p: StorePurchase): String? {
        val plans = current().offers.mapNotNull { it.productId }.toSet()
        return p.products.firstOrNull { it in plans } ?: p.products.firstOrNull { !it.contains("_device_") }
    }

    private fun waiting(): JSONArray = runCatching { JSONArray(prefs.getString("waiting", "[]")) }.getOrDefault(JSONArray())

    @Synchronized private fun keep(purchaseToken: String, product: String) {
        val a = waiting()
        if ((0 until a.length()).none { a.getJSONObject(it).optString("token") == purchaseToken })
            a.put(JSONObject().put("token", purchaseToken).put("product", product))
        prefs.edit().putString("waiting", a.toString()).apply()
        publish { it.copy(waiting = a.length()) }
        retry?.cancel()
        retry = scope.launch(Dispatchers.IO) { delay(60_000); sendWaiting() }
    }

    @Synchronized private fun forget(purchaseToken: String) {
        val a = waiting()
        val b = JSONArray(); for (i in 0 until a.length()) a.getJSONObject(i).takeIf { it.optString("token") != purchaseToken }?.let { b.put(it) }
        prefs.edit().putString("waiting", b.toString()).apply()
        publish { it.copy(waiting = b.length()) }
    }

    /** the reports that didn't get through, again */
    fun sendWaiting() {
        val a = waiting()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            report(StorePurchase(o.getString("token"), listOf(o.getString("product")), true, null), o.getString("product"), quiet = true)
        }
    }

    fun dismiss() = publish { it.copy(error = null, note = null) }

    companion object {
        private const val TAG = "Store"
        /** the shop's privacy policy of the app */
        val privacyUrl: String get() = BuildConfig.ACCOUNT_URL.trimEnd('/') + "/app/privacy"
    }
}
