package io.github.pmaxai.cardscanner.ui

import android.app.Application
import io.github.pmaxai.cardscanner.BuildConfig
import io.github.pmaxai.cardscanner.account.AccountApi
import io.github.pmaxai.cardscanner.account.AccountException
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.account.AccountStore
import io.github.pmaxai.cardscanner.account.PairCode
import io.github.pmaxai.cardscanner.account.Paired
import io.github.pmaxai.cardscanner.account.PendingOp
import io.github.pmaxai.cardscanner.account.TransferCode
import io.github.pmaxai.cardscanner.account.unpairedText
import io.github.pmaxai.cardscanner.shop.ShopTerms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** A card on the missing cards list (`GET /wanted`); [pending]: added here, not sent yet. */
data class WantedItem(
    val id: Long?, val clientRef: String?, val cardId: String, val language: String?, val finish: String?,
    /** searching, offer, available */
    val state: String, val pending: Boolean = false,
) {
    fun matches(cardId: String, language: String?, finish: String?): Boolean =
        this.cardId == cardId && (this.language == null || language == null || this.language == language) &&
            (this.finish == null || finish == null || this.finish == finish)
}

/** A line of the collection for one card: "2 × DE Holo NM". */
data class CollectionLine(val language: String, val finish: String, val condition: String, val quantity: Int, val graded: String?)

/** A copy in the collection (`GET /collection/items`), as the collection page lists it. */
data class CollectionItem(
    val id: Long, val cardId: String, val language: String, val finish: String, val condition: String, val graded: String?,
    val quantity: Int, val name: String, val setName: String, val number: String, val imageUrl: String?,
    /** the server's value of one copy (null: no price) */
    val valueCents: Long?, val updatedAt: String,
) {
    /** "glurak-ex 151 199/165 de holo nm …" for the filter */
    val searchText: String = "$name $setName $number $cardId $language ${ShopTerms.finishLabel(finish)} $condition".lowercase()

    companion object {
        /** null: a deleted copy, or not one at all */
        fun of(o: JSONObject): CollectionItem? {
            if (o.optBoolean("deleted") || !o.has("id") || o.isNull("id")) return null
            val card = AccountStatus.str(o, "card_id") ?: return null
            return CollectionItem(
                o.optLong("id"), card, AccountStatus.str(o, "language") ?: "", AccountStatus.str(o, "finish") ?: "",
                AccountStatus.str(o, "condition") ?: "",
                o.optJSONObject("grading")?.let { g -> "${g.optString("company")} ${g.optString("grade")}".trim() },
                o.optInt("quantity", 1), AccountStatus.str(o, "name") ?: card, AccountStatus.str(o, "set_name") ?: "",
                AccountStatus.str(o, "number") ?: "", AccountStatus.str(o, "image_url"),
                if (!o.has("value_cents") || o.isNull("value_cents")) null else o.optLong("value_cents"),
                AccountStatus.str(o, "updated_at") ?: "",
            )
        }
    }
}

/** Move to a new phone: the code shown, and what happened. */
data class TransferUi(val code: TransferCode? = null, val busy: Boolean = false, val error: String? = null)

/** What the screens need to know about the account. */
data class AccountUi(
    /** this build needs an account (`cardscanner.accountRequired`) */
    val required: Boolean = false,
    val gate: AccountGate = AccountGate.CONNECT,
    val status: AccountStatus? = null,
    val lastCheck: Long? = null,
    /** why this phone was disconnected (the connect screen says it) */
    val notice: String? = null,
    /** pairing or checking runs */
    val busy: Boolean = false,
    val error: String? = null,
    /** just connected: the account and plan, then Continue */
    val justConnected: Boolean = false,
    val transfer: TransferUi? = null,
    /** changes waiting to be sent */
    val pending: List<PendingOp> = emptyList(),
    val wanted: List<WantedItem> = emptyList(),
    /** the collection's lines per card id (cards looked at so far) */
    val collection: Map<String, List<CollectionLine>> = emptyMap(),
    /** the customer's binders: id → name */
    val binders: List<Pair<Long, String>> = emptyList(),
    /** the collection page: every copy, newest first (null: not loaded yet) */
    val items: List<CollectionItem>? = null,
    val itemsLoading: Boolean = false,
    /** why the collection couldn't be loaded */
    val itemsError: String? = null,
    /** grows with every copy sent to the collection; [itemsAt]: its value when [items] were loaded */
    val collectionEdits: Int = 0,
    val itemsAt: Int = 0,
    /** S3 storage: still being set up by the shop, or why it couldn't be read */
    val s3Note: String? = null,
    /** Trading: a cart just paid whose cards could go into the collection */
    val offerCart: Long? = null,
    /** Use another email: where the code went, and from when another may be asked for */
    val emailSentTo: String? = null,
    val resendAt: Long = 0,
    /** buying in the store (Play edition) */
    val store: StoreUi = StoreUi(),
) {
    /** a feature unlocked now (builds without an account: everything) */
    fun has(feature: String): Boolean = !required || (gate == AccountGate.OPEN && status?.has(feature) == true)
    /** the app can't be used now: connect, subscription ended, or check online once */
    val locked: Boolean get() = required && gate != AccountGate.OPEN
    val portalUrl: String get() = status?.portalUrl ?: (BuildConfig.ACCOUNT_URL.trimEnd('/') + "/account")
}

/**
 * The account in the app: pairing by QR code, the status check (at start, at most hourly,
 * after an hour in the background), Move to a new phone, Disconnect, the S3 storage the shop
 * set up, and the collection and missing cards with their offline queue.
 */
class AccountCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val publish: ((AccountUi) -> AccountUi) -> Unit,
    private val current: () -> AccountUi,
    private val message: (String) -> Unit,
    /** a new answer: modes, recording upload, webshop host follow the features */
    private val onStatus: (AccountStatus?) -> Unit,
    /** the S3 settings the shop set up (JSON of GET /services/s3), to fill Recording & upload */
    private val onS3: (JSONObject?) -> Unit,
) {
    val store = AccountStore(app)
    @Volatile private var checking = false
    @Volatile private var syncing = false
    @Volatile private var syncAgain = false
    private var transferPoll: Job? = null
    private var retry: Job? = null

    /** the HTTP client (tests: short timeouts); null: the API's own */
    @Volatile var http: okhttp3.OkHttpClient? = null

    internal fun api(): AccountApi? = store.base?.let { AccountApi(it, BuildConfig.VERSION_NAME, http) }

    /** an answer that carries the status (a purchase report): kept like a check's */
    internal fun applyStatus(o: JSONObject) {
        store.saveStatus(o)
        load()
    }

    /** At the app's start (after the settings are loaded): the saved state, then a check. */
    fun start() {
        load()
        publishWanted()
        check(force = true)
    }

    /** the saved state: connected?, the last answer, the gate */
    internal fun load() {
        val st = store.status()
        publish {
            it.copy(required = BuildConfig.ACCOUNT_REQUIRED, status = st, lastCheck = store.lastCheck, notice = store.notice,
                gate = AccountGate.of(store.connected, st, System.currentTimeMillis()))
        }
        onStatus(st.takeIf { store.connected })
    }

    // ---------------------------------------------------------------- pairing
    /** A scanned QR code or a typed code: connect this phone to the account. */
    fun pair(text: String, scanned: Boolean) {
        if (current().busy) return
        val target = PairCode.parse(text, BuildConfig.ACCOUNT_URL, BuildConfig.DEBUG)
        if (target == null) {
            publish { it.copy(error = if (scanned) "This QR code isn't from overpricedcardboard.com. Scan the one shown in the portal under Account › Devices › Connect a device."
                else "That isn't a pairing code: 12 characters, e.g. K7Q2-9MZ4-D01A.") }
            return
        }
        if (scanned && target.code == refused) return
        publish { it.copy(busy = true, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val p = AccountApi(target.base, BuildConfig.VERSION_NAME, http).pair(target.code, store.deviceId, AccountStore.deviceName(),
                    affiliateCode = store.pendingAffiliate)
                refused = null
                connectedWith(target.base, p)
            } catch (e: AccountException) {
                refused = target.code
                publish { it.copy(busy = false, error = e.friendly) }
            } catch (e: Exception) {
                publish { it.copy(busy = false, error = offline(e, "connecting needs the internet. Try again when you're online.")) }
            }
        }
    }
    @Volatile private var refused: String? = null

    /** paired or signed in: keep the token, show who (a new account without a plan goes straight to the plans) */
    private fun connectedWith(base: String, p: Paired) {
        store.connected(base, p.token, p.status)
        // the install's creator code went with it: once is enough, whatever became of it
        if (p.affiliateResult != null) store.pendingAffiliate = null
        onAffiliate?.invoke(p.affiliateResult, p.affiliate)
        // said before the gate opens (load), so whoever sees it open sees "Connected" too
        val active = runCatching { AccountStatus.parse(p.status).active }.getOrDefault(false)
        publish { it.copy(busy = false, error = null, justConnected = active, emailSentTo = null) }
        load()
        // a queue of another account is not sent to this one
        val acc = store.status()?.accountId
        store.saveQueue(store.queue().filter { it.accountId == null || it.accountId == acc })
        onConnected?.invoke()
        check(force = true)
    }

    /** the store's part (Play edition): after connecting, look at the store's purchases */
    @Volatile var onConnected: (() -> Unit)? = null

    /** the store's part: what became of the install's creator code, and the account's code */
    @Volatile var onAffiliate: ((String?, JSONObject?) -> Unit)? = null

    // ---------------------------------------------------------------- sign-in (store editions)
    /** the build's own server: sign-ins go there (a QR code names its own); tests: a stand-in */
    @Volatile var home: String = BuildConfig.ACCOUNT_URL.trimEnd('/')

    /** Continue with Google: the ID token the phone's Google account gave */
    fun signInGoogle(idToken: String) = signIn("signing in needs the internet.") {
        it.loginGoogle(idToken, store.deviceId, AccountStore.deviceName(), affiliateCode = store.pendingAffiliate)
    }

    /** Use another email: the code is emailed */
    fun emailCode(email: String) {
        val e = email.trim()
        if (current().busy || e.isEmpty()) return
        publish { it.copy(busy = true, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val wait = AccountApi(home, BuildConfig.VERSION_NAME, http).loginEmail(e)
                publish { it.copy(busy = false, emailSentTo = e, resendAt = System.currentTimeMillis() + wait * 1000L) }
            } catch (x: AccountException) {
                publish { it.copy(busy = false, error = x.friendly) }
            } catch (x: Exception) {
                publish { it.copy(busy = false, error = offline(x, "signing in needs the internet.")) }
            }
        }
    }

    /** the code from the email */
    fun emailVerify(code: String) {
        val email = current().emailSentTo ?: return
        signIn("signing in needs the internet.") {
            it.loginEmailVerify(email, code.filter(Char::isDigit), store.deviceId, AccountStore.deviceName(), affiliateCode = store.pendingAffiliate)
        }
    }

    /** another address (back from the code) */
    fun emailBack() = publish { it.copy(emailSentTo = null, error = null) }

    fun signInFailed(why: String) = publish { it.copy(busy = false, error = why) }

    private fun signIn(what: String, call: (AccountApi) -> Paired) {
        if (current().busy) return
        publish { it.copy(busy = true, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                connectedWith(home, call(AccountApi(home, BuildConfig.VERSION_NAME, http)))
            } catch (e: AccountException) {
                publish { it.copy(busy = false, error = e.friendly) }
            } catch (e: Exception) {
                publish { it.copy(busy = false, error = offline(e, what)) }
            }
        }
    }

    /** The connected screen's Continue. */
    fun connectedSeen() = publish { it.copy(justConnected = false) }

    // ---------------------------------------------------------------- status
    /**
     * Asks the server what is unlocked: [force] at start, after pairing, on Check again;
     * otherwise at most once an hour. Offline (or the server's trouble) the saved answer counts
     * until valid_until.
     */
    fun check(force: Boolean = false, manual: Boolean = false) {
        val token = store.token ?: return
        val last = store.lastCheck
        if (!force && last != null && System.currentTimeMillis() - last < HOUR) return
        // one at a time; asked again meanwhile (Check again, right after pairing): once more afterwards
        synchronized(this) {
            if (checking) { if (force) { checkAgain = true; checkAgainManual = checkAgainManual || manual }; return }
            checking = true
        }
        if (manual) publish { it.copy(busy = true, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val api = api() ?: return@launch
                val o = api.status(token)
                store.saveStatus(o)
                load()
                publish { it.copy(busy = false, error = null) }
                val st = store.status()
                if (st?.has("s3_upload") == true) fetchS3(api, token) else { onS3(null); publish { it.copy(s3Note = null) } }
                if (st?.has("wanted") == true) refreshWanted()
                sync()
            } catch (e: AccountException) {
                // a check nobody asked for leaves the screen's message alone: right after Check again it
                // would wipe what that one just said
                if (e.lostToken) lost(e) else if (manual) publish { it.copy(busy = false, error = e.friendly) }
            } catch (e: Exception) {
                load()      // valid_until may have passed meanwhile
                if (manual) publish { it.copy(busy = false, error = offline(e, "the subscription can't be checked now.")) }
            } finally {
                val again = synchronized(this@AccountCoordinator) {
                    checking = false
                    (checkAgain to checkAgainManual).also { checkAgain = false; checkAgainManual = false }
                }
                if (again.first) check(force = true, manual = again.second)
            }
        }
    }
    @Volatile private var checkAgain = false
    @Volatile private var checkAgainManual = false

    /** The token doesn't count any more: forget it and the saved answer, say why, back to Connect. */
    private fun lost(e: AccountException) {
        val why = if (e.error == "unpaired") unpairedText(e.reason, e.since, e.deviceName) else "This phone is no longer connected to an account."
        stopTransfer()
        store.forget(why)
        load()
        publish { it.copy(busy = false, error = null, transfer = null, wanted = emptyList(), collection = emptyMap(), binders = emptyList(), items = null, itemsError = null, store = StoreUi()) }
        onS3(null)
    }

    /** the app came to the front: after more than an hour away, check */
    fun onForeground() { check() }

    /** while the app is open: at most once an hour */
    suspend fun hourly() {
        while (scope.isActive) {
            delay(HOUR)
            check()
        }
    }

    private fun fetchS3(api: AccountApi, token: String) {
        try {
            val o = api.s3(token)
            val updated = o.optString("updated_at")
            if (updated != store.s3UpdatedAt) { onS3(o); store.s3UpdatedAt = updated }
            publish { it.copy(s3Note = null) }
        } catch (e: AccountException) {
            publish { it.copy(s3Note = if (e.error == "not_configured") "Overpriced Cardboard is still setting up your storage." else e.friendly) }
        } catch (e: Exception) { /* next time */ }
    }

    /** Uploads failed with an access error: the shop may have changed the key – fetch it again now. */
    fun refreshS3() {
        val token = store.token ?: return
        scope.launch(Dispatchers.IO) { store.s3UpdatedAt = null; api()?.let { fetchS3(it, token) } }
    }

    // ---------------------------------------------------------------- move, disconnect
    /** Move to a new phone: a transfer code as a QR code; asks every 5 s whether the new phone took it. */
    fun startTransfer() {
        val token = store.token ?: return
        publish { it.copy(transfer = TransferUi(busy = true)) }
        scope.launch(Dispatchers.IO) {
            try {
                val t = api()!!.transfer(token)
                publish { it.copy(transfer = TransferUi(t)) }
                transferPoll?.cancel()
                transferPoll = scope.launch(Dispatchers.IO) {
                    while (isActive && current().transfer?.code != null) {
                        delay(5_000)
                        try { api()?.status(token)?.let { store.saveStatus(it) } }
                        catch (e: AccountException) { if (e.lostToken) { lost(e); message(store.notice ?: "Moved"); break } }
                        catch (e: Exception) { }
                        val exp = current().transfer?.code?.expiresAt
                        if (exp != null && System.currentTimeMillis() > exp) {
                            publish { it.copy(transfer = TransferUi(error = "The code has expired. Show a new one.")) }
                            break
                        }
                    }
                }
            } catch (e: AccountException) {
                if (e.lostToken) lost(e) else publish { it.copy(transfer = TransferUi(error = e.friendly)) }
            } catch (e: Exception) {
                publish { it.copy(transfer = TransferUi(error = offline(e, "moving needs the internet."))) }
            }
        }
    }

    fun stopTransfer() {
        transferPoll?.cancel(); transferPoll = null
        publish { it.copy(transfer = null) }
    }

    /** Disconnect this phone: its slot is free again; back to Connect. */
    fun disconnect() {
        val token = store.token ?: return
        publish { it.copy(busy = true, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                api()!!.disconnect(token)
                store.forget("This phone was disconnected from the account.")
                load()
                publish { it.copy(busy = false, wanted = emptyList(), collection = emptyMap(), binders = emptyList(), items = null, itemsError = null, store = StoreUi()) }
                onS3(null)
            } catch (e: AccountException) {
                if (e.lostToken) lost(e) else publish { it.copy(busy = false, error = e.friendly) }
            } catch (e: Exception) {
                publish { it.copy(busy = false, error = offline(e, "disconnecting needs the internet.")) }
            }
        }
    }

    /**
     * Delete the account (asked first): every device is signed out, the data goes, renewals stop.
     * An App Store subscription the person cancels themselves ([message] says where).
     */
    fun deleteAccount() {
        val token = store.token ?: return
        publish { it.copy(busy = true, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val o = api()!!.deleteAccount(token)
                store.forget("The account was deleted.")
                store.saveQueue(emptyList())
                load()
                publish { it.copy(busy = false, wanted = emptyList(), collection = emptyMap(), binders = emptyList(), items = null, itemsError = null, store = StoreUi()) }
                onS3(null)
                o.optString("manage_url").takeIf { it.isNotBlank() && !o.isNull("manage_url") }?.let {
                    message("The account is deleted. Cancel the subscription in the App Store too: $it")
                } ?: message("The account is deleted.")
            } catch (e: AccountException) {
                if (e.lostToken) lost(e) else publish { it.copy(busy = false, error = if (e.error == "internal")
                    "The subscription couldn't be stopped just now, so nothing was deleted. Try again later." else e.friendly) }
            } catch (e: Exception) {
                publish { it.copy(busy = false, error = offline(e, "deleting needs the internet.")) }
            }
        }
    }

    // ---------------------------------------------------------------- collection
    /** The collection's lines of a card, for "In collection: 2" (cached; [force]: again). */
    fun loadCollection(cardId: String, force: Boolean = false) {
        if (!current().has("collection")) return
        if (!force && current().collection.containsKey(cardId)) return
        val token = store.token ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val a = api()!!.collectionCard(token, cardId)
                val lines = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { !it.optBoolean("deleted") }.map { o ->
                    CollectionLine(o.optString("language"), o.optString("finish"), o.optString("condition"), o.optInt("quantity", 1),
                        o.optJSONObject("grading")?.let { g -> "${g.optString("company")} ${g.optString("grade")}".trim() })
                }
                publish { it.copy(collection = it.collection + (cardId to lines)) }
            } catch (e: AccountException) {
                if (e.lostToken) lost(e)
            } catch (e: Exception) { }
        }
    }

    fun loadBinders() {
        val token = store.token ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { api()!!.binders(token) }.getOrNull()?.let { a ->
                publish { it.copy(binders = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { b -> b.optLong("id") to b.optString("name") }) }
            }
        }
    }

    @Volatile private var loadingItems = false

    /** The collection page: the whole collection, page by page, newest first. */
    fun loadItems() {
        if (!current().has("collection")) return
        val token = store.token ?: return
        synchronized(this) { if (loadingItems) return; loadingItems = true }
        val at = current().collectionEdits
        publish { it.copy(itemsLoading = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val api = api() ?: throw IOException("not connected")
                val all = ArrayList<CollectionItem>()
                var cursor: String? = null
                do {
                    val (page, next) = api.collectionItems(token, cursor)
                    (0 until page.length()).mapNotNullTo(all) { i -> page.optJSONObject(i)?.let { o -> CollectionItem.of(o) } }
                    cursor = next
                } while (cursor != null && all.size < 100_000)
                val sorted = all.sortedWith(compareByDescending<CollectionItem> { it.updatedAt }.thenByDescending { it.id })
                publish { it.copy(items = sorted, itemsAt = at, itemsLoading = false, itemsError = null) }
            } catch (e: AccountException) {
                if (e.lostToken) lost(e)
                publish { it.copy(itemsLoading = false, itemsError = if (e.lostToken) null else e.friendly) }
            } catch (e: Exception) {
                publish { it.copy(itemsLoading = false, itemsError = offline(e, "the collection can't be loaded now.")) }
            } finally {
                loadingItems = false
            }
        }
    }

    /** A missing card turned up elsewhere: into the collection (the missing card's language and finish, else the card's own), and off the missing cards. */
    fun foundElsewhere(w: WantedItem, condition: String, quantity: Int = 1, purchaseCents: Long? = null, binderId: Long? = null,
                       note: String? = null, defaultLanguage: String, defaultFinish: String, label: String) {
        addToCollection(w.cardId, w.language ?: defaultLanguage, w.finish ?: defaultFinish, condition, quantity, purchaseCents, binderId, note, label)
        removeWanted(w, label)
    }

    /** + Collection: queued with its own client_ref and sent at once (later when offline). */
    fun addToCollection(cardId: String, language: String, finish: String, condition: String, quantity: Int = 1,
                        purchaseCents: Long? = null, binderId: Long? = null, note: String? = null, label: String) {
        val body = JSONObject().put("card_id", cardId).put("language", language).put("finish", finish).put("condition", condition)
            .put("quantity", quantity.coerceIn(1, 999)).put("grading", JSONObject.NULL)
            .put("purchase_price_cents", purchaseCents ?: JSONObject.NULL).put("note", note?.takeIf { it.isNotBlank() }?.take(500) ?: JSONObject.NULL)
            .put("binder_id", binderId ?: JSONObject.NULL).put("merge", true)
        enqueue(PendingOp("collection_add", UUID.randomUUID().toString(), current().status?.accountId, cardId, body, label = label))
    }

    // ---------------------------------------------------------------- missing cards
    private fun savedWanted(): List<WantedItem> {
        val a = store.wanted() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            WantedItem(o.optLong("id"), AccountStatus.str(o, "client_ref"), o.optString("card_id"), AccountStatus.str(o, "language"),
                AccountStatus.str(o, "finish"), o.optString("state", "searching"))
        }
    }

    /** counts changes made here to the missing cards: a list fetched meanwhile is older than them and not kept */
    private val wantedEdits = java.util.concurrent.atomic.AtomicInteger()

    private fun refreshWanted() {
        val token = store.token ?: return
        try {
            val before = wantedEdits.get()
            val list = api()!!.wanted(token)
            if (before == wantedEdits.get() && store.queue().none { it.kind.startsWith("wanted") }) store.saveWanted(list)
            publishWanted()
        } catch (e: AccountException) { if (e.lostToken) lost(e) } catch (e: Exception) { }
    }

    /** the list as the server has it, plus what waits to be sent (adds shown, removes left out) */
    private fun publishWanted() {
        val q = store.queue()
        val removing = q.filter { it.kind == "wanted_delete" }.mapNotNull { it.wantedId }.toSet()
        val adding = q.filter { it.kind == "wanted_add" }.map { op ->
            WantedItem(null, op.clientRef, op.cardId, op.body?.let { AccountStatus.str(it, "language") }, op.body?.let { AccountStatus.str(it, "finish") },
                "searching", pending = true)
        }
        publish { it.copy(wanted = savedWanted().filter { w -> w.id !in removing } + adding, pending = q) }
    }

    /** ☆ Missing: on the list, or off it again (a second tap). */
    fun toggleWanted(cardId: String, language: String?, finish: String?, label: String) {
        val hit = current().wanted.firstOrNull { it.matches(cardId, language, finish) }
        val acc = current().status?.accountId
        when {
            hit == null -> {
                val body = JSONObject().put("card_id", cardId).put("language", language ?: JSONObject.NULL).put("finish", finish ?: JSONObject.NULL)
                enqueue(PendingOp("wanted_add", UUID.randomUUID().toString(), acc, cardId, body, label = label))
            }
            else -> removeWanted(hit, label)
        }
    }

    /** This card off the missing cards (the collection page's Remove, and Found it). */
    fun removeWanted(w: WantedItem, label: String) {
        when {
            // added here and not sent yet: it simply goes
            w.pending -> { store.saveQueue(store.queue().filter { it.clientRef != w.clientRef }); publishWanted() }
            w.id != null -> enqueue(PendingOp("wanted_delete", UUID.randomUUID().toString(), current().status?.accountId, w.cardId,
                wantedId = w.id, label = label))
        }
    }

    // ---------------------------------------------------------------- the queue
    private fun enqueue(op: PendingOp) {
        store.saveQueue(store.queue() + op)
        publishWanted()
        sync()
    }

    /** The user deletes a change that waits to be sent. */
    fun dropPending(clientRef: String) {
        store.saveQueue(store.queue().filter { it.clientRef != clientRef })
        publishWanted()
    }

    /**
     * Sends what waits, in order. No connection, 5xx, too many calls: it stays (and is tried
     * again in a minute while the app is open). A 400: dropped, with why.
     */
    fun sync() {
        if (store.token == null) return
        // one at a time; a change made meanwhile is sent right after
        synchronized(this) {
            if (syncing) { syncAgain = true; return }
            syncing = true
        }
        scope.launch(Dispatchers.IO) {
            var stuck = false
            var again = false
            try {
                while (true) {
                    val token = store.token ?: break
                    val op = store.queue().firstOrNull() ?: break
                    if (op.accountId != null && current().status?.accountId != null && op.accountId != current().status?.accountId) { done(op); continue }
                    try {
                        send(token, op)
                        done(op)
                    } catch (e: AccountException) {
                        when {
                            e.lostToken -> { lost(e); stuck = true }
                            e.status == 400 -> {
                                done(op)
                                message("Not ${if (op.kind == "collection_add") "added to the collection" else "changed in the missing cards"}: ${op.label} (${e.fieldsText ?: e.friendly})")
                            }
                            e.status == 404 && op.kind == "wanted_delete" -> done(op)
                            else -> stuck = true
                        }
                        if (stuck) break
                    } catch (e: Exception) {
                        // no connection, the server's trouble, an answer it couldn't read: try again later
                        stuck = true; break
                    }
                }
            } finally {
                again = synchronized(this@AccountCoordinator) { syncing = false; syncAgain.also { syncAgain = false } }
                publishWanted()
            }
            if (again && !stuck) { sync(); return@launch }
            if (stuck && store.token != null && store.queue().isNotEmpty()) {
                retry?.cancel()
                retry = scope.launch { delay(60_000); sync() }
            }
        }
    }

    private fun send(token: String, op: PendingOp) {
        val api = api() ?: throw IOException("not connected")
        when (op.kind) {
            "collection_add" -> {
                api.addToCollection(token, JSONObject(op.body!!.toString()).put("client_ref", op.clientRef))
                loadCollection(op.cardId, force = true)
                // the collection page loads again
                publish { it.copy(collectionEdits = it.collectionEdits + 1) }
            }
            "wanted_add" -> {
                wantedEdits.incrementAndGet()
                val o = api.addWanted(token, JSONObject(op.body!!.toString()).put("client_ref", op.clientRef))
                val list = store.wanted() ?: JSONArray()
                if ((0 until list.length()).none { list.optJSONObject(it)?.optLong("id") == o.optLong("id") }) list.put(o)
                store.saveWanted(list)
            }
            "wanted_delete" -> {
                wantedEdits.incrementAndGet()
                api.deleteWanted(token, op.wantedId!!)
                val list = store.wanted() ?: JSONArray()
                store.saveWanted(JSONArray((0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optLong("id") != op.wantedId }))
            }
        }
    }

    private fun done(op: PendingOp) = store.saveQueue(store.queue().filter { it.clientRef != op.clientRef })

    /** "No connection: …" – or, when the server is there but not answering as the API, that. */
    private fun offline(e: Exception, what: String): String = when (e) {
        is io.github.pmaxai.cardscanner.account.ServerUnavailable ->
            "overpricedcardboard.com isn't answering as expected right now (HTTP ${e.code}) – try again later."
        else -> "No connection: $what"
    }

    companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
