import Foundation

/**
 * The customer's account on overpricedcardboard.com: a subscription, bought in the App Store (or
 * on the website), the app connected to it. The server decides what is unlocked (`features`);
 * the app never derives a feature from the plan's name. (Port of Account.kt.)
 */
struct AccountStatus {
    var active: Bool
    var serverTime: Date?
    /// until when the app may run on this answer offline
    var validUntil: Date
    var accountId: String?
    var email: String?
    var name: String?
    var deviceName: String?
    var plan: String?
    var state: String?
    var renewsAt: Date?
    var endsAt: Date?
    var devicesIncluded: Int
    var devicesExtra: Int
    var devicesInUse: Int
    var features: [String: Bool]
    var portalUrl: String?
    var json: JSON
    /// signed in for buying in the store, never had a subscription (`status: "none"`)
    var none = false
    /// fixed per account (a UUID): passed with every store purchase as Apple's appAccountToken
    var storeRef: String?
    /// false while an account made in the app hasn't clicked its confirmation link
    var emailVerified = true
    /// where the subscription is managed: web, google_play, app_store
    var source: String?
    /// "Manage subscription": the portal's page for web, the store's page for the stores
    var manageUrl: String?
    var devicesInStore = 0
    var devicesOnWeb = 0
    /// monthly or yearly (nil: not known, or given by the shop)
    var basePlan: String?
    /// the add-ons the account has (`addons`), by id; what they unlock is in `features` (psa_prices)
    var addons: [String: AddonState] = [:]

    /// a feature the server unlocked (an inactive answer unlocks nothing)
    func has(_ feature: String) -> Bool { active && features[feature] == true }

    var planName: String { Self.displayName(plan: plan) }

    /// "Collector" for collector
    static func displayName(plan: String?) -> String {
        switch plan {
        case "collector": return "Collector"
        case "creator": return "Creator"
        case nil, "": return "No plan"
        default: return plan!.prefix(1).uppercased() + plan!.dropFirst()
        }
    }

    /// "renews on 15 Dec 2026", "ends on …", "payment failed – please update your payment method"
    var stateText: String {
        switch state {
        case "active": return renewsAt.map { "renews on \(Self.day($0))" } ?? "active"
        case "past_due": return "payment failed – please update your payment method"
        case "canceled": return endsAt.map { "ends on \(Self.day($0))" } ?? "cancelled"
        case "ended": return "ended"
        case "complimentary": return "given by the shop" + (endsAt.map { " until \(Self.day($0))" } ?? "")
        case "none", nil: return "no subscription"
        default: return state!
        }
    }

    /// "2 of 2 in use"
    var devicesText: String { "\(devicesInUse) of \(devicesIncluded + devicesExtra) in use" }

    static func parse(_ o: JSON) -> AccountStatus {
        let sub = o["subscription"] as? JSON ?? [:]
        let dev = sub["devices"] as? JSON ?? [:]
        let acc = o["account"] as? JSON ?? [:]
        let f = o["features"] as? JSON ?? [:]
        let server = time(o, "server_time")
        func int(_ d: JSON, _ k: String, _ def: Int) -> Int { (d[k] as? NSNumber)?.intValue ?? def }
        return AccountStatus(
            active: o["status"] as? String == "active", serverTime: server,
            validUntil: time(o, "valid_until") ?? server ?? Date(timeIntervalSince1970: 0),
            accountId: str(acc, "id"), email: str(acc, "email"), name: str(acc, "name"),
            deviceName: (o["device"] as? JSON).flatMap { str($0, "name") },
            plan: str(sub, "plan"), state: str(sub, "state"), renewsAt: time(sub, "renews_at"), endsAt: time(sub, "ends_at"),
            devicesIncluded: int(dev, "included", 1), devicesExtra: int(dev, "extra", 0), devicesInUse: int(dev, "in_use", 0),
            features: f.compactMapValues { ($0 as? Bool) ?? ($0 as? NSNumber)?.boolValue },
            portalUrl: str(o, "portal_url"), json: o,
            none: o["status"] as? String == "none", storeRef: str(acc, "store_ref"),
            emailVerified: (acc["email_verified"] as? Bool) ?? true,
            source: str(sub, "source"), manageUrl: str(sub, "manage_url"),
            devicesInStore: int(dev, "in_store", 0), devicesOnWeb: int(dev, "on_web", 0), basePlan: str(sub, "base_plan"),
            addons: Dictionary((o["addons"] as? [JSON] ?? []).compactMap(AddonState.parse).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }))
    }

    static func str(_ o: JSON, _ k: String) -> String? {
        guard let v = o[k], !(v is NSNull) else { return nil }
        let s = (v as? String) ?? "\(v)"
        return s.trimmingCharacters(in: .whitespaces).isEmpty ? nil : s
    }

    static func time(_ o: JSON, _ k: String) -> Date? {
        guard let s = str(o, k) else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }

    static func day(_ d: Date) -> String { d.formatted(date: .abbreviated, time: .omitted) }
}

/// An add-on of the account (PSA prices): running or not, where it is billed, until when.
struct AddonState: Equatable {
    let id: String
    /// active, canceled (runs until endsAt), complimentary, ended
    let state: String?
    /// app_store, google_play, web
    let source: String?
    let basePlan: String?
    let renewsAt: Date?
    let endsAt: Date?

    var running: Bool { state == "active" || state == "canceled" || state == "complimentary" }

    /// "renews on 15 Dec 2026", "ends on …"
    var stateText: String {
        switch state {
        case "active": return renewsAt.map { "renews on \(AccountStatus.day($0))" } ?? "active"
        case "canceled": return endsAt.map { "ends on \(AccountStatus.day($0))" } ?? "cancelled"
        case "complimentary": return "given by the shop" + (endsAt.map { " until \(AccountStatus.day($0))" } ?? "")
        case "ended": return "ended"
        default: return state ?? "–"
        }
    }

    static func parse(_ o: JSON) -> AddonState? {
        guard let id = AccountStatus.str(o, "id") else { return nil }
        return AddonState(id: id, state: AccountStatus.str(o, "state"), source: AccountStatus.str(o, "source"),
                          basePlan: AccountStatus.str(o, "base_plan"), renewsAt: AccountStatus.time(o, "renews_at"), endsAt: AccountStatus.time(o, "ends_at"))
    }
}

/// What the app may do right now.
enum AccountGate {
    /// not connected to an account: the connect screen
    case connect
    /// a running subscription: the features it unlocks
    case open
    /// no running subscription: every mode locked
    case ended
    /// the saved answer ran out (valid_until) and the server can't be reached
    case checkOnline

    static func of(connected: Bool, status: AccountStatus?, now: Date) -> AccountGate {
        if !connected { return .connect }
        guard let status else { return .checkOnline }
        if !status.active { return .ended }
        if now > status.validUntil { return .checkOnline }
        return .open
    }
}

/// An error answer of the account API: `{ "error": "device_limit", "message": "…" }`.
struct AccountError: Error, LocalizedError {
    let status: Int
    let error: String
    let message: String?
    var json: JSON?

    var errorDescription: String? { friendly }
    var reason: String? { json.flatMap { AccountStatus.str($0, "reason") } }
    var since: Date? { json.flatMap { AccountStatus.time($0, "since") } }
    var deviceName: String? { json.flatMap { AccountStatus.str($0, "device_name") } }

    /// the token doesn't count any more: forget it (unpaired: with a reason)
    var lostToken: Bool { status == 401 && (error == "unpaired" || error == "invalid_token") }
    /// worth trying again later (the server's trouble or too many calls)
    var later: Bool { status >= 500 || status == 429 }

    private func int(_ k: String) -> Int? { (json?[k] as? NSNumber)?.intValue }

    /// What the person sees.
    var friendly: String {
        switch error {
        case "invalid_code": return "That isn't a pairing code (12 characters, e.g. K7Q2-9MZ4-D01A)."
        case "unknown_code": return "This code doesn't exist. Check it, or show a new QR code in the portal."
        case "code_expired": return "This code has expired or was already used. Show a new one in the portal: Account › Devices › Connect a device."
        case "device_limit":
            return "All " + (int("seats").flatMap { $0 > 0 ? "\($0) " : nil } ?? "") + "devices of your subscription are in use. Remove one in your account (Devices) or add a device."
        case "subscription_required": return "Your account has no active subscription."
        case "invalid_identity": return "Apple didn't confirm the sign-in. Try again."
        case "invalid_email": return "That isn't an email address."
        case "wrong_code": return "That code isn't right" + (int("tries_left").flatMap { $0 >= 0 ? " – \($0) tries left." : nil } ?? ".")
        case "already_subscribed":
            let from: String
            switch json.flatMap({ AccountStatus.str($0, "source") }) {
            case "web": from = " from the website"
            case "google_play": from = " from Google Play"
            case "app_store": from = " from the App Store"
            default: from = ""
            }
            return "This account already has a subscription\(from). Nothing new was bought."
        case "linked_elsewhere": return "This purchase belongs to another account" + (json.flatMap { AccountStatus.str($0, "owner") }.map { " (\($0))" } ?? "") + "."
        case "invalid_purchase": return "The App Store doesn't know this purchase."
        case "store_unavailable": return "Apple didn't answer just now. The purchase is kept and sent again."
        case "already_registered":
            let code = (json?["affiliate"] as? JSON).flatMap { AccountStatus.str($0, "code") }
            return "Your account already has the creator code" + (code.map { " \($0)" } ?? "") + "."
        case "already_customer": return "Creator codes are only for the first subscription."
        case "rate_limited": return "Too many tries – please wait a few minutes."
        case "feature_not_included": return "Your plan doesn't include this."
        default:
            if let m = message, !m.isEmpty, m != error { return m }
            return "The server answered \(status) (\(error))."
        }
    }
}

/// Why this phone is no longer connected, as the connect screen says it.
func unpairedText(reason: String?, since: Date?, deviceName: String?) -> String {
    let on = since.map { " on \(AccountStatus.day($0))" } ?? ""
    switch reason {
    case "removed": return "This phone was removed in your account\(on)."
    case "transferred": return "Moved to \(deviceName ?? "another phone")\(on)."
    case "replaced": return "This phone was connected again – to another account, or with a new code."
    case "disconnected": return "This phone was disconnected from the account."
    case "account_deleted": return "The account was deleted."
    default: return "This phone is no longer connected to an account."
    }
}

/// A pairing code from the portal's QR code (or typed): which server, and the code.
enum PairCode {
    /// Crockford base32: 0-9, A-Z without I, L, O, U
    static let alphabet = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    static let hosts: Set<String> = ["overpricedcardboard.com", "staging.overpricedcardboard.com"]

    struct Target: Equatable { let base: String; let code: String }

    /// "k7q2 9mz4-d01a" → "K7Q2-9MZ4-D01A": case, spaces and dashes don't matter; O = 0, I and L = 1
    static func normalize(_ typed: String) -> String? {
        let s = String(typed.uppercased().filter { !$0.isWhitespace && $0 != "-" }.map { c -> Character in
            switch c { case "O": return "0"; case "I", "L": return "1"; default: return c }
        })
        guard s.count == 12, s.allSatisfy({ alphabet.contains($0) }) else { return nil }
        let a = Array(s)
        return [String(a[0..<4]), String(a[4..<8]), String(a[8..<12])].joined(separator: "-")
    }

    /// A scanned QR code: `https://overpricedcardboard.com/pair/K7Q2-9MZ4-D01A` of an allowed host, else nil.
    static func fromUrl(_ text: String) -> Target? {
        guard let u = URLComponents(string: text.trimmingCharacters(in: .whitespaces)), let host = u.host?.lowercased(),
              u.scheme?.lowercased() == "https", hosts.contains(host), u.port == nil || u.port == 443, u.user == nil else { return nil }
        let parts = u.path.split(separator: "/")
        guard parts.count == 2, parts[0] == "pair", let code = normalize(String(parts[1]).removingPercentEncoding ?? String(parts[1])) else { return nil }
        return Target(base: "https://\(host)", code: code)
    }

    /// What was scanned or typed: a pair URL (its host is the server), or a bare code for defaultBase.
    static func parse(_ text: String, defaultBase: String) -> Target? {
        if text.contains("://") { return fromUrl(text) }
        return normalize(text).map { Target(base: defaultBase.hasSuffix("/") ? String(defaultBase.dropLast()) : defaultBase, code: $0) }
    }
}
