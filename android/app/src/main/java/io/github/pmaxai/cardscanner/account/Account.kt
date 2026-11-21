package io.github.pmaxai.cardscanner.account

import org.json.JSONObject
import java.net.URI
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * The customer's account on overpricedcardboard.com (docs: app-account-api.md): a subscription,
 * the app connected to it by a QR code from the web portal. The server decides what is unlocked
 * (`features`); the app never derives a feature from the plan's name.
 */

/** A pairing code from the portal's QR code (or typed): which server, and the code. */
object PairCode {
    /** Crockford base32: 0-9, A-Z without I, L, O, U */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val HOSTS = setOf("overpricedcardboard.com", "staging.overpricedcardboard.com")
    /** only in debug builds, with a port: the shop on the computer (10.0.2.2 from the emulator) */
    val DEBUG_HOSTS = setOf("localhost", "10.0.2.2")

    data class Target(val base: String, val code: String)

    /** "k7q2 9mz4-d01a" → "K7Q2-9MZ4-D01A": case, spaces and dashes don't matter; O = 0, I and L = 1 */
    fun normalize(typed: String): String? {
        val s = typed.uppercase().filter { !it.isWhitespace() && it != '-' }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
        return s.takeIf { it.length == 12 && it.all { c -> c in ALPHABET } }?.chunked(4)?.joinToString("-")
    }

    /** A scanned QR code: `https://overpricedcardboard.com/pair/K7Q2-9MZ4-D01A` of an allowed host, else null. */
    fun fromUrl(text: String, debug: Boolean): Target? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val ok = when {
            host in HOSTS -> scheme == "https" && (uri.port == -1 || uri.port == 443)
            debug && host in DEBUG_HOSTS -> scheme == "http" || scheme == "https"
            else -> false
        }
        if (!ok || uri.userInfo != null) return null
        val m = Regex("^/pair/([^/]+)/?$").find(uri.rawPath ?: "") ?: return null
        val code = normalize(java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")) ?: return null
        val port = if (host in DEBUG_HOSTS && uri.port != -1) ":${uri.port}" else ""
        return Target("$scheme://$host$port", code)
    }

    /** What was scanned or typed: a pair URL (its host is the server), or a bare code for [defaultBase]. */
    fun parse(text: String, defaultBase: String, debug: Boolean): Target? =
        if (text.contains("://")) fromUrl(text, debug) else normalize(text)?.let { Target(defaultBase.trimEnd('/'), it) }
}

/** `GET /api/app/v1/status` (also inside the pairing answer). */
data class AccountStatus(
    val active: Boolean,
    val serverTime: Long?,
    /** until when the app may run on this answer offline */
    val validUntil: Long,
    val accountId: String?,
    val email: String?,
    val name: String?,
    val deviceName: String?,
    val plan: String?,
    val state: String?,
    val renewsAt: Long?,
    val endsAt: Long?,
    val devicesIncluded: Int,
    val devicesExtra: Int,
    val devicesInUse: Int,
    val features: Map<String, Boolean>,
    val webshopHost: String?,
    val s3Enabled: Boolean,
    val portalUrl: String?,
    val json: JSONObject,
    /** signed in for buying in the store, never had a subscription (`status: "none"`) */
    val none: Boolean = false,
    /** fixed per account: passed with every store purchase (Google: obfuscatedAccountId) */
    val storeRef: String? = null,
    /** false while an account made in the app hasn't clicked its confirmation link */
    val emailVerified: Boolean = true,
    /** where the subscription is managed: web, google_play, app_store */
    val source: String? = null,
    /** "Manage subscription": the portal's page for web, the store's page for the stores */
    val manageUrl: String? = null,
    /** extra devices bought in the app (Play add-ons) and on the website */
    val devicesInStore: Int = 0,
    val devicesOnWeb: Int = 0,
    /** how it is billed: monthly or yearly (null: not known, or given by the shop) */
    val basePlan: String? = null,
    /** the add-ons the account has (`addons`), by id; what they unlock is in [features] (psa_prices) */
    val addons: Map<String, AddonState> = emptyMap(),
) {
    /** a feature the server unlocked (an inactive answer unlocks nothing) */
    fun has(feature: String): Boolean = active && features[feature] == true

    val planName: String get() = when (plan) {
        "collector" -> "Collector"; "creator" -> "Creator"; null, "" -> "No plan"
        else -> plan.replaceFirstChar { it.uppercase() }
    }

    /** "renews on 15 Dec 2026", "ends on …", "payment failed – please update your payment method" */
    val stateText: String get() = when (state) {
        "active" -> renewsAt?.let { "renews on ${day(it)}" } ?: "active"
        "past_due" -> "payment failed – please update your payment method"
        "canceled" -> endsAt?.let { "ends on ${day(it)}" } ?: "cancelled"
        "ended" -> "ended"
        "complimentary" -> "given by the shop" + (endsAt?.let { " until ${day(it)}" } ?: "")
        "none", null -> "no subscription"
        else -> state
    }

    /** "2 of 2 in use" */
    val devicesText: String get() = "$devicesInUse of ${devicesIncluded + devicesExtra} in use"

    companion object {
        fun parse(o: JSONObject): AccountStatus {
            val sub = o.optJSONObject("subscription") ?: JSONObject()
            val dev = sub.optJSONObject("devices") ?: JSONObject()
            val acc = o.optJSONObject("account") ?: JSONObject()
            val f = o.optJSONObject("features") ?: JSONObject()
            val active = o.optString("status") == "active"
            val server = time(o, "server_time")
            return AccountStatus(
                active = active, serverTime = server,
                validUntil = time(o, "valid_until") ?: server ?: 0L,
                accountId = str(acc, "id"), email = str(acc, "email"), name = str(acc, "name"),
                deviceName = o.optJSONObject("device")?.let { str(it, "name") },
                plan = str(sub, "plan"), state = str(sub, "state"),
                renewsAt = time(sub, "renews_at"), endsAt = time(sub, "ends_at"),
                devicesIncluded = dev.optInt("included", 1), devicesExtra = dev.optInt("extra", 0), devicesInUse = dev.optInt("in_use", 0),
                features = f.keys().asSequence().associateWith { k -> f.optBoolean(k, false) },
                webshopHost = o.optJSONObject("webshop")?.let { str(it, "host") },
                s3Enabled = o.optJSONObject("services")?.optJSONObject("s3")?.optBoolean("enabled") == true,
                portalUrl = str(o, "portal_url"),
                json = o,
                none = o.optString("status") == "none",
                storeRef = str(acc, "store_ref"),
                emailVerified = acc.optBoolean("email_verified", true),
                source = str(sub, "source"),
                manageUrl = str(sub, "manage_url"),
                devicesInStore = dev.optInt("in_store", 0),
                devicesOnWeb = dev.optInt("on_web", 0),
                basePlan = str(sub, "base_plan"),
                addons = o.optJSONArray("addons")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(AddonState::parse) }.associateBy { it.id }
                }.orEmpty(),
            )
        }

        internal fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
        internal fun time(o: JSONObject, k: String): Long? = str(o, k)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        fun day(t: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    }
}

/** An add-on of the account (PSA prices): running or not, where it is billed, until when. */
data class AddonState(
    val id: String,
    /** active, canceled (runs until [endsAt]), past_due, complimentary, ended */
    val state: String?,
    /** app_store, google_play, web */
    val source: String?,
    val basePlan: String?,
    val renewsAt: Long?,
    val endsAt: Long?,
) {
    val running: Boolean get() = state == "active" || state == "canceled" || state == "past_due" || state == "complimentary"

    /** "renews on 15 Dec 2026", "ends on …" */
    val stateText: String get() = when (state) {
        "active" -> renewsAt?.let { "renews on ${AccountStatus.day(it)}" } ?: "active"
        "canceled" -> endsAt?.let { "ends on ${AccountStatus.day(it)}" } ?: "cancelled"
        "past_due" -> "payment failed – please update your payment method"
        "complimentary" -> "given by the shop" + (endsAt?.let { " until ${AccountStatus.day(it)}" } ?: "")
        "ended" -> "ended"
        else -> state ?: "–"
    }

    companion object {
        fun parse(o: JSONObject): AddonState? {
            val id = AccountStatus.str(o, "id") ?: return null
            return AddonState(id, AccountStatus.str(o, "state"), AccountStatus.str(o, "source"), AccountStatus.str(o, "base_plan"),
                AccountStatus.time(o, "renews_at"), AccountStatus.time(o, "ends_at"))
        }
    }
}

/** What the app may do right now. */
enum class AccountGate {
    /** not connected to an account: the connect screen */
    CONNECT,
    /** a running subscription: the features it unlocks */
    OPEN,
    /** no running subscription: every mode locked, "Your subscription has ended" */
    ENDED,
    /** the saved answer ran out (valid_until) and the server can't be reached: "Connect to the internet once…" */
    CHECK_ONLINE;

    companion object {
        fun of(connected: Boolean, status: AccountStatus?, now: Long): AccountGate = when {
            !connected -> CONNECT
            status == null -> CHECK_ONLINE
            !status.active -> ENDED
            now > status.validUntil -> CHECK_ONLINE
            else -> OPEN
        }
    }
}

/** this edition sells the subscription in the store (Google Play): no links to buying on the website */
val storeEdition: Boolean get() = storeEditionForTests ?: io.github.pmaxai.cardscanner.BuildConfig.STORE_BILLING

/** tests: draw the other edition's screens */
@Volatile var storeEditionForTests: Boolean? = null

/** An error answer of the account API: `{ "error": "device_limit", "message": "…" }`. */
class AccountException(val status: Int, val error: String, message: String?, val json: JSONObject? = null) :
    Exception(message ?: error) {
    val reason: String? get() = json?.let { AccountStatus.str(it, "reason") }
    val since: Long? get() = json?.let { AccountStatus.time(it, "since") }
    val deviceName: String? get() = json?.let { AccountStatus.str(it, "device_name") }
    val fields: JSONObject? get() = json?.optJSONObject("fields")

    /** the token doesn't count any more: forget it (unpaired: with a reason) */
    val lostToken: Boolean get() = status == 401 && (error == "unpaired" || error == "invalid_token")
    /** worth trying again later (the server's trouble or too many calls) */
    val later: Boolean get() = status >= 500 || status == 429

    /** What the person sees when pairing fails. */
    val friendly: String get() = when (error) {
        "invalid_code" -> "That isn't a pairing code (12 characters, e.g. K7Q2-9MZ4-D01A)."
        "unknown_code" -> "This code doesn't exist. Check it, or show a new QR code in the portal."
        "code_expired" -> "This code has expired or was already used. Show a new one in the portal: Account › Devices › Connect a device."
        "device_limit" -> "All " + (json?.optInt("seats")?.takeIf { it > 0 }?.let { "$it " } ?: "") +
            "devices of your subscription are in use. Remove one in the portal (Account › Devices) or add a device."
        "subscription_required" -> if (storeEdition) "Your account has no active subscription."
            else "Your account has no active subscription. Subscribe at overpricedcardboard.com/app."
        "invalid_identity" -> "Google didn't confirm the sign-in. Try again."
        "invalid_email" -> "That isn't an email address."
        "wrong_code" -> "That code isn't right" + (json?.optInt("tries_left", -1)?.takeIf { it >= 0 }?.let { " – $it tries left." } ?: ".")
        "already_subscribed" -> "This account already has a subscription" +
            (when (json?.optString("source")) { "web" -> " from the website"; "google_play" -> " from Google Play"; else -> "" }) + ". Nothing new was bought."
        "linked_elsewhere" -> "This purchase belongs to another account" + (json?.optString("owner")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + "."
        "invalid_purchase" -> "Google Play doesn't know this purchase."
        "store_unavailable" -> "Google didn't answer just now. The purchase is kept and sent again."
        "already_registered" -> "Your account already has the creator code" +
            (json?.optJSONObject("affiliate")?.optString("code")?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + "."
        "already_customer" -> "Creator codes are only for the first subscription."
        "rate_limited" -> "Too many tries – please wait a few minutes."
        "feature_not_included" -> "Your plan doesn't include this."
        else -> message?.takeIf { it.isNotBlank() && it != error } ?: "The server answered $status ($error)."
    }

    /** "card_id: unknown" from `fields` (a 400) */
    val fieldsText: String? get() = fields?.let { f -> f.keys().asSequence().joinToString(", ") { "$it: ${f.optString(it)}" } }?.takeIf { it.isNotBlank() }
}

/** Why this phone is no longer connected, as the connect screen says it. */
fun unpairedText(reason: String?, since: Long?, deviceName: String?): String = when (reason) {
    "removed" -> "This phone was removed in your account" + (since?.let { " on ${AccountStatus.day(it)}" } ?: "") + "."
    "transferred" -> "Moved to ${deviceName ?: "another phone"}" + (since?.let { " on ${AccountStatus.day(it)}" } ?: "") + "."
    "replaced" -> "This phone was connected again – to another account, or with a new code."
    "disconnected" -> "This phone was disconnected from the account."
    "account_deleted" -> "The account was deleted."
    else -> "This phone is no longer connected to an account."
}

/** Plan words for features the plan lacks (Mode lists them greyed out with this). */
fun featureNeeds(feature: String): String = when (feature) {
    "youtube", "recording", "s3_upload", "pipeline" -> "Creator"
    "webshop" -> "With a connected webshop"
    "psa_prices" -> "Add-on"
    else -> "Collector"
}
