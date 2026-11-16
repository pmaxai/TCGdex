package io.github.pmaxai.cardscanner.store

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** The Play edition sells with Play Billing (one client for the app). */
object Stores {
    @Volatile private var billing: StoreBilling? = null
    fun billing(ctx: Context): StoreBilling = billing ?: synchronized(this) {
        billing ?: PlayBilling(ctx.applicationContext).also { billing = it }
    }
}

/**
 * Google Play: the subscription and its extra devices (add-ons on the same purchase) with Play
 * Billing; Continue with Google with Credential Manager. Purchases are never acknowledged here:
 * the server does it once it has accepted them.
 */
class PlayBilling(private val ctx: Context) : StoreBilling, PurchasesUpdatedListener {
    override val sells = true
    override var onLatePurchases: ((List<StorePurchase>) -> Unit)? = null

    private val client: BillingClient = BillingClient.newBuilder(ctx)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()
    private val connecting = Mutex()
    /** what Play answered about each product (the purchase sheet needs it) */
    private val details = ConcurrentHashMap<String, ProductDetails>()
    @Volatile private var waiting: CompletableDeferred<BuyResult>? = null

    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady) return true
        suspendCancellableCoroutine<Boolean> { c ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    if (r.responseCode != BillingResponseCode.OK) Log.w(TAG, "Play Billing: ${r.responseCode} ${r.debugMessage}")
                    if (c.isActive) c.resume(r.responseCode == BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() { if (c.isActive) c.resume(false) }
            })
        }
    }

    override suspend fun googleIdToken(activity: Activity, serverClientId: String): String? {
        val req = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build()).build()
        return try {
            val c = CredentialManager.create(activity).getCredential(activity, req).credential
            if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) GoogleIdTokenCredential.createFrom(c.data).idToken
            else throw IllegalStateException("Google didn't hand over a sign-in")
        } catch (e: GetCredentialCancellationException) {
            null
        } catch (e: GetCredentialException) {
            throw IllegalStateException("Google sign-in isn't available: ${e.message ?: e.type}", e)
        }
    }

    override suspend fun offers(productIds: List<String>): Map<String, Map<String, StoreOffer>> {
        if (productIds.isEmpty() || !ready()) return emptyMap()
        val params = QueryProductDetailsParams.newBuilder().setProductList(productIds.distinct().map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        val list = suspendCancellableCoroutine<List<ProductDetails>> { c ->
            client.queryProductDetailsAsync(params) { r, result ->
                if (r.responseCode != BillingResponseCode.OK) Log.w(TAG, "products: ${r.responseCode} ${r.debugMessage}")
                if (c.isActive) c.resume(if (r.responseCode == BillingResponseCode.OK) result.productDetailsList else emptyList())
            }
        }
        list.forEach { details[it.productId] = it }
        // the base plans themselves (no special offers): their recurring price
        return list.associate { pd ->
            pd.productId to pd.subscriptionOfferDetails.orEmpty().filter { it.offerId == null }.associate { o ->
                o.basePlanId to StoreOffer(pd.productId, o.basePlanId, o.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice.orEmpty(), o.offerToken)
            }
        }
    }

    override suspend fun purchases(): List<StorePurchase> {
        if (!ready()) return emptyList()
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        return suspendCancellableCoroutine<List<StorePurchase>> { c ->
            client.queryPurchasesAsync(params) { r, list ->
                if (c.isActive) c.resume(if (r.responseCode == BillingResponseCode.OK) list.map(::toStore) else emptyList())
            }
        }
    }

    override suspend fun buy(activity: Activity, request: BuyRequest): BuyResult {
        if (!ready()) return BuyResult.Failed("Google Play isn't available on this phone.")
        request.items.map { it.offer.productId }.filter { !details.containsKey(it) }.takeIf { it.isNotEmpty() }?.let { offers(it) }
        val items = request.items.map { item ->
            val pd = details[item.offer.productId] ?: return BuyResult.Failed("Google Play doesn't sell ${item.offer.productId}.")
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).setOfferToken(item.offer.offerToken).apply {
                if (item.replaces != null && item.mode != null) setSubscriptionProductReplacementParams(
                    SubscriptionProductReplacementParams.newBuilder().setOldProductId(item.replaces).setReplacementMode(mode(item.mode)).build())
            }.build()
        }
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(items).setObfuscatedAccountId(request.accountRef).apply {
            request.oldPurchaseToken?.let { setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder().setOldPurchaseToken(it).build()) }
        }.build()
        val w = CompletableDeferred<BuyResult>()
        waiting = w
        val r = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, flow) }
        if (r.responseCode != BillingResponseCode.OK) {
            waiting = null
            return BuyResult.Failed(answer(r))
        }
        return w.await()
    }

    override fun onPurchasesUpdated(r: BillingResult, list: MutableList<Purchase>?) {
        val res = when (r.responseCode) {
            BillingResponseCode.OK -> BuyResult.Bought(list.orEmpty().map(::toStore))
            BillingResponseCode.USER_CANCELED -> BuyResult.Cancelled
            BillingResponseCode.ITEM_ALREADY_OWNED -> BuyResult.Failed("This Google account already has the subscription. Use Restore purchases.")
            else -> BuyResult.Failed(answer(r))
        }
        val w = waiting
        waiting = null
        if (w != null) w.complete(res) else if (res is BuyResult.Bought) onLatePurchases?.invoke(res.purchases)
    }

    private fun answer(r: BillingResult) = r.debugMessage.takeIf { it.isNotBlank() } ?: "Google Play answered ${r.responseCode}."

    private fun toStore(p: Purchase) = StorePurchase(p.purchaseToken, p.products, p.purchaseState == Purchase.PurchaseState.PURCHASED,
        p.accountIdentifiers?.obfuscatedAccountId)

    private fun mode(m: Replace): Int = when (m) {
        Replace.KEEP_EXISTING -> SubscriptionProductReplacementParams.ReplacementMode.KEEP_EXISTING
        Replace.CHARGE_PRORATED_PRICE -> SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE
        Replace.DEFERRED -> SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
    }

    companion object { private const val TAG = "Store" }
}
