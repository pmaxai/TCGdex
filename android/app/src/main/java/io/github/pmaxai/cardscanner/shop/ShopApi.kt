package io.github.pmaxai.cardscanner.shop

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pokeshop "Shop API v1" (pokemonkarten.at): look up a card in the shop's catalogue and book
 * copies into stock. Every request is signed with HMAC-SHA256 (key id + secret); every write
 * carries an Idempotency-Key that stays the same on retries, so a retry never books twice.
 * The secret never leaves the phone: only the signature is sent, and it is never logged.
 */
object ApiSigner {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** The X-Signature value, "v1=<hex>" (timestamp, method, path with query, body hash; joined by \n). */
    fun sign(secret: String, timestamp: Long, method: String, pathWithQuery: String, body: ByteArray): String {
        val canonical = listOf(timestamp.toString(), method.uppercase(Locale.ROOT), pathWithQuery, sha256Hex(body)).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "v1=" + mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** The server's clock as seen from the phone: every response's Date header corrects it. */
class ServerClock {
    @Volatile var offsetMs = 0L
        private set
    fun nowSeconds(): Long = (System.currentTimeMillis() + offsetMs) / 1000
    fun observe(serverDate: java.util.Date?) {
        if (serverDate != null) offsetMs = serverDate.time - System.currentTimeMillis()
    }
}

/** Where the shop is and the key to it. [baseUrl] ends with /api/v1. */
data class ShopCredentials(val baseUrl: String, val keyId: String, val secret: String) {
    override fun toString() = "ShopCredentials($baseUrl, $keyId, secret=…)"     // never the secret

    companion object {
        /**
         * "staging.pokemonkarten.at", "https://pokemonkarten.at" or ".../api/v1" -> https://host/api/v1.
         * Plain http only for a shop on this computer (emulator 10.0.2.2, localhost).
         */
        fun baseUrlOf(host: String): String? {
            var h = host.trim().trimEnd('/')
            if (h.isEmpty()) return null
            if (!h.contains("://")) h = "https://$h"
            val url = runCatching { h.toHttpUrl() }.getOrNull() ?: return null
            if (url.scheme == "http" && url.host !in setOf("10.0.2.2", "localhost", "127.0.0.1")) return null
            val path = url.encodedPath.trimEnd('/').removeSuffix("/api/v1")
            return url.newBuilder().encodedPath("$path/api/v1".replace("//", "/")).query(null).fragment(null).build().toString().trimEnd('/')
        }
    }
}

data class Money(val cents: Long) {
    val euros: Double get() = cents / 100.0
    fun toJson(): JSONObject = JSONObject().put("amount", cents).put("currency", "EUR")
    companion object {
        fun ofEuros(v: Double) = Money(Math.round(v * 100))
        fun from(o: JSONObject?): Money? = o?.let { Money(it.optLong("amount")) }
    }
}

data class StockBox(val box: String, val quantity: Int, val reserved: Int)
data class Stock(val quantity: Int, val reserved: Int, val available: Int, val boxes: List<StockBox>) {
    companion object {
        fun from(o: JSONObject?): Stock {
            if (o == null) return Stock(0, 0, 0, emptyList())
            val boxes = o.optJSONArray("boxes").objects().map { StockBox(it.optString("storage_location"), it.optInt("quantity"), it.optInt("reserved")) }
            return Stock(o.optInt("quantity"), o.optInt("reserved"), o.optInt("available"), boxes)
        }
    }
}

data class ShopImage(val id: Long, val role: String, val status: String, val url: String?)

/** One product of the shop: one card in one language, condition, finish (and grading). */
data class ShopProduct(
    val sku: String,
    val cardRef: String?,
    val setCode: String,
    val cardNumber: String,
    val nameDe: String,
    val nameEn: String,
    val cardLanguage: String,
    val condition: String,
    val finish: String,
    val grading: String?,
    val price: Money?,
    val highValue: Boolean,
    val status: String,
    val url: String?,
    val stock: Stock,
    val images: List<ShopImage>,
    val updatedAt: String?,
) {
    /** the shop's picture: the first ready front image, else the first ready one */
    val imageUrl: String? get() = (images.firstOrNull { it.status == "ready" && it.role == "front" } ?: images.firstOrNull { it.status == "ready" })?.url

    companion object {
        fun from(o: JSONObject): ShopProduct = ShopProduct(
            sku = o.getString("sku"),
            cardRef = o.str("card_ref"),
            setCode = o.str("set_code") ?: "",
            cardNumber = o.str("card_number") ?: "",
            nameDe = o.str("name_de") ?: "",
            nameEn = o.str("name_en") ?: "",
            cardLanguage = o.str("card_language") ?: "",
            condition = o.str("condition") ?: "",
            finish = o.str("finish") ?: "",
            grading = o.optJSONObject("grading")?.let { g -> "${g.optString("company")} ${g.optString("grade")}".trim() },
            price = Money.from(o.optJSONObject("price")),
            highValue = o.optBoolean("high_value"),
            status = o.str("status") ?: "",
            url = o.str("url"),
            stock = Stock.from(o.optJSONObject("stock")),
            images = o.optJSONArray("images").objects().map { ShopImage(it.optLong("id"), it.optString("role"), it.optString("status"), it.str("url")) },
            updatedAt = o.str("updated_at"),
        )
    }
}

data class MasterCard(val cardRef: String, val setCode: String, val setName: String?, val localId: String, val cardCount: Int?,
                      val name: String?, val rarity: String?, val imageUrl: String?)

data class IntakeResult(val created: Boolean, val product: ShopProduct, val warnings: List<String>, val replayed: Boolean)

/** A write prepared once: the same bytes and the same Idempotency-Key on every retry. */
data class PendingWrite(val method: String, val path: String, val body: ByteArray, val idempotencyKey: String = UUID.randomUUID().toString()) {
    override fun equals(other: Any?) = other is PendingWrite && other.idempotencyKey == idempotencyKey
    override fun hashCode() = idempotencyKey.hashCode()

    companion object {
        fun json(method: String, path: String, body: JSONObject) = PendingWrite(method, path, body.toString().toByteArray(Charsets.UTF_8))
    }
}

/** An answer of the shop that isn't a success (or no answer at all: [status] 0). */
class ShopException(val status: Int, val code: String, val serverMessage: String? = null, val fields: Map<String, String> = emptyMap()) :
    IOException("$status $code") {
    /** for the user (the server's message is English developer text, so it's only a fallback) */
    val friendly: String get() = when (code) {
        "offline" -> "No connection to the shop – check the internet and try again"
        "missing_headers", "bad_timestamp", "bad_signature_format", "signature_mismatch" ->
            "The shop doesn't accept this key – check key id, secret and host (staging keys don't work on the live shop)"
        "timestamp_out_of_range" -> "The phone's clock is more than 5 minutes off"
        "key_revoked" -> "This key is no longer valid – create a new one in the shop's admin"
        "ip_not_allowed" -> "This key only works from certain addresses – remove its IP allowlist for phones"
        "scope_missing" -> "This key isn't allowed to do that"
        "not_found" -> "Not in the shop"
        "insufficient_stock" -> "The box doesn't hold that many copies"
        "unknown_card" -> "The shop doesn't know this card yet"
        "external_id_taken", "conflict", "identity_mismatch" -> "The shop refused: ${serverMessage ?: code}"
        "idempotency_conflict" -> "This booking was changed after it was sent – try again"
        "too_large" -> "Too large for the shop"
        "rate_limited" -> "Too many requests – wait a moment"
        "validation" -> "The shop refused the input" + (if (fields.isNotEmpty()) ": " + fields.entries.joinToString("; ") { "${it.key}: ${it.value}" } else "")
        "internal", "server" -> "The shop has a problem right now – try again in a moment"
        else -> "The shop answered $status ${serverMessage ?: code}"
    }
}

/** A friendlier text for the warnings a successful write can carry. */
fun shopWarningText(w: String): String = when {
    w == "not_activated_no_price" -> "No price: it stays a draft (not visible in the shop)"
    w == "not_activated_no_image" -> "No picture: it stays a draft (not visible in the shop)"
    w == "high_value_ignored_set_by_price" -> "High value is set by the price"
    w.startsWith("copies_in_open_checkout_stay_in_") -> "Copies in an open checkout stay in box ${w.removePrefix("copies_in_open_checkout_stay_in_")}"
    else -> w
}

/**
 * The shop API over HTTPS. Blocking: call off the main thread. Retries with the same
 * Idempotency-Key after a timeout or a 5xx (1, 2, 4 s), after "in progress" (1.5 s), after a
 * rate limit (Retry-After) and once after a clock correction.
 */
class ShopClient(
    private val creds: ShopCredentials,
    private val clock: ServerClock = ServerClock(),
    http: OkHttpClient? = null,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val http = http ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val base: HttpUrl = creds.baseUrl.toHttpUrl()

    data class Health(val keyId: String, val serverTime: String?)

    fun health(): Health {
        val o = call("GET", "/health")
        return Health(o.optString("key_id"), o.str("server_time"))
    }

    /** Cards of the shop's master data (the TCGdex catalogue it keeps). */
    fun masterCards(cardRef: String? = null, set: String? = null, number: String? = null, q: String? = null, lang: String = "de"): List<MasterCard> {
        val query = listOfNotNull(cardRef?.let { "card_ref" to it }, set?.let { "set" to it }, number?.let { "number" to it },
            q?.let { "q" to it }, "lang" to lang)
        return call("GET", "/master/cards", query).optJSONArray("items").objects().map {
            MasterCard(it.getString("card_ref"), it.optString("set_code"), it.str("set_name"), it.optString("local_id"),
                if (it.isNull("card_count")) null else it.optInt("card_count"), it.str("name"), it.str("rarity"), it.str("image_url"))
        }
    }

    /** Every product of one card (all languages, conditions, finishes), paging through. */
    fun productsOfCard(cardRef: String): List<ShopProduct> {
        val out = mutableListOf<ShopProduct>()
        var cursor: String? = null
        do {
            val o = call("GET", "/products", listOfNotNull("card_ref" to cardRef, "limit" to "200", cursor?.let { "cursor" to it }))
            o.optJSONArray("items").objects().forEach { out += ShopProduct.from(it) }
            cursor = o.str("next_cursor")
        } while (cursor != null && out.size < 2000)
        return out
    }

    fun product(sku: String): ShopProduct = ShopProduct.from(call("GET", "/products/$sku"))

    /** POST /stock/intake: find or create the product and add copies to a box. */
    fun intake(w: PendingWrite): IntakeResult {
        val (o, replayed) = write(w)
        return IntakeResult(o.optBoolean("created"), ShopProduct.from(o.getJSONObject("product")),
            o.optJSONArray("warnings").strings(), replayed)
    }

    /** POST /products/{sku}/stock: add, remove, set or move copies. Returns the new stock. */
    fun stock(w: PendingWrite): Stock = Stock.from(write(w).first)

    /** A write with its Idempotency-Key; the response and whether it was a replay of an earlier one. */
    fun write(w: PendingWrite): Pair<JSONObject, Boolean> {
        var replayed = false
        val o = call(w.method, w.path, emptyList(), w.body, w.idempotencyKey) { replayed = it }
        return o to replayed
    }

    private fun call(method: String, path: String, query: List<Pair<String, String>> = emptyList(), body: ByteArray? = null,
                     idempotencyKey: String? = null, onReplay: (Boolean) -> Unit = {}): JSONObject {
        val url = base.newBuilder().apply {
            path.trim('/').split('/').forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val pathWithQuery = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        var attempt = 0
        var clockRetried = false
        while (true) {
            val bytes = body ?: ByteArray(0)
            val ts = clock.nowSeconds()
            val req = Request.Builder().url(url)
                .header("X-Key-Id", creds.keyId)
                .header("X-Timestamp", ts.toString())
                .header("X-Signature", ApiSigner.sign(creds.secret, ts, method, pathWithQuery, bytes))
                .header("Accept", "application/json")
                .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
                .method(method, body?.toRequestBody(JSON))
                .build()
            val retryIn: Long = try {
                http.newCall(req).execute().use { resp ->
                    clock.observe(resp.headers.getDate("Date"))
                    val text = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (resp.isSuccessful) {
                        onReplay(resp.header("Idempotent-Replayed") == "true")
                        return json ?: JSONObject()
                    }
                    val code = json?.str("error") ?: if (resp.code >= 500) "server" else "http_${resp.code}"
                    val err = ShopException(resp.code, code, json?.str("message"),
                        json?.optJSONObject("fields")?.let { f -> f.keys().asSequence().associateWith { f.optString(it) } } ?: emptyMap())
                    when {
                        resp.code >= 500 && attempt < 3 -> 1000L shl attempt
                        code == "idempotency_in_progress" && attempt < 3 -> 1500L
                        resp.code == 429 && attempt < 2 -> (resp.header("Retry-After")?.toLongOrNull() ?: 2L).coerceIn(1, 30) * 1000
                        code == "timestamp_out_of_range" && !clockRetried -> { clockRetried = true; 0L }
                        else -> throw err
                    }
                }
            } catch (e: ShopException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= 3) throw ShopException(0, "offline", e.message)
                1000L shl attempt
            }
            attempt++
            if (retryIn > 0) sleep(retryIn)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The body of POST /stock/intake (only documented fields; nothing null). */
        fun intakeBody(cardRef: String, language: String, condition: String, finish: String, delta: Int, box: String,
                       price: Money? = null, descriptionDe: String? = null, nameDe: String? = null, nameEn: String? = null): JSONObject =
            JSONObject().apply {
                put("card_ref", cardRef); put("card_language", language); put("condition", condition); put("finish", finish)
                put("delta", delta); put("storage_location", box)
                price?.let { put("price", it.toJson()) }
                descriptionDe?.takeIf { it.isNotBlank() }?.let { put("description_de", it.trim()) }
                nameDe?.takeIf { it.isNotBlank() }?.let { put("name_de", it.trim()) }
                nameEn?.takeIf { it.isNotBlank() }?.let { put("name_en", it.trim()) }
            }

        /** A storage box as the shop wants it: "014" for 14 (1–999). */
        fun box(n: Int): String = "%03d".format(n)
    }
}

/** null for a missing or JSON-null value (org.json's optString would give "null"). */
internal fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
