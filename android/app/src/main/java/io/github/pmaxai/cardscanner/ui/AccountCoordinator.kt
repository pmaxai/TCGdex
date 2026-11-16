package io.github.pmaxai.cardscanner.ui

import android.app.Application
import io.github.pmaxai.cardscanner.BuildConfig
import io.github.pmaxai.cardscanner.account.AccountApi
import io.github.pmaxai.cardscanner.account.AccountException
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.account.AccountStore
import io.github.pmaxai.cardscanner.account.PairCode
import io.github.pmaxai.cardscanner.account.PendingOp
import io.github.pmaxai.cardscanner.account.TransferCode
import io.github.pmaxai.cardscanner.account.unpairedText
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
    /** S3 storage: still being set up by the shop, or why it couldn't be read */
    val s3Note: String? = null,
    /** Trading: a cart just paid whose cards could go into the collection */
    val offerCart: Long? = null,
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

    private fun api(): AccountApi? = store.base?.let { AccountApi(it, BuildConfig.VERSION_NAME, http) }

    /** At the app's start (after the settings are loaded): the saved state, then a check. */
    fun start() {
        load()
        publishWanted()
        check(force = true)
    }

    /** the saved state: connected?, the last answer, the gate */
    private fun load() {
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
                val p = AccountApi(target.base, BuildConfig.VERSION_NAME, http).pair(target.code, store.deviceId, AccountStore.deviceName())
                store.connected(target.base, p.token, p.status)
                refused = null
                load()
                publish { it.copy(busy = false, justConnected = true) }
                // a queue of another account is not sent to this one
                val acc = store.status()?.accountId
                store.saveQueue(store.queue().filter { it.accountId == null || it.accountId == acc })
                check(force = true)
            } catch (e: AccountException) {
                refused = target.code
                publish { it.copy(busy = false, error = e.friendly) }
            } catch (e: Exception) {
                publish { it.copy(busy = false, error = offline(e, "connecting needs the internet. Try again when you're online.")) }
            }
        }
    }
    @Volatile private var refused: String? = null

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
        publish { it.copy(busy = false, error = null, transfer = null, wanted = emptyList(), collection = emptyMap(), binders = emptyList()) }
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
                publish { it.copy(busy = false, wanted = emptyList(), collection = emptyMap(), binders = emptyList()) }
                onS3(null)
            } catch (e: AccountException) {
                if (e.lostToken) lost(e) else publish { it.copy(busy = false, error = e.friendly) }
            } catch (e: Exception) {
                publish { it.copy(busy = false, error = offline(e, "disconnecting needs the internet.")) }
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
            // added here and not sent yet: it simply goes
            hit.pending -> { store.saveQueue(store.queue().filter { it.clientRef != hit.clientRef }); publishWanted() }
            hit.id != null -> enqueue(PendingOp("wanted_delete", UUID.randomUUID().toString(), acc, cardId, wantedId = hit.id, label = label))
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
