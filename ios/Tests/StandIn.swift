import Foundation
@testable import OverpricedCardboard

/**
 * A stand-in for the shop's account API (pokeshop docs/app-account-api.md plus the Apple endpoints
 * requested in the shop doc), in memory, behind URLProtocol. Like the Android tests' StoreShop.
 */
final class StandIn: URLProtocol {
    final class Account {
        let id: String
        let email: String
        let storeRef = UUID().uuidString.lowercased()
        var active = false
        var plan: String?
        var basePlan: String?
        var source: String?
        var originalTransaction: String?
        var affiliate: [String: Any]?
        var wanted: [[String: Any]] = []
        var collection: [[String: Any]] = []
        var devicesInStore = 0
        var emailVerified = false
        init(id: String, email: String) { self.id = id; self.email = email }
    }

    struct Call { let method: String; let path: String; let body: [String: Any] }

    static let lock = NSLock()
    nonisolated(unsafe) static var accounts: [String: Account] = [:]        // by email
    nonisolated(unsafe) static var tokens: [String: Account] = [:]
    nonisolated(unsafe) static var calls: [Call] = []
    /// the next /store/apple answers 503 store_unavailable
    nonisolated(unsafe) static var failNextReport = false
    /// every call fails as if there were no connection
    nonisolated(unsafe) static var offline = false
    nonisolated(unsafe) static var nextId = 1
    /// the line-up of the Apple setup guide: 1 to 4 extra devices for both plans and periods (review screenshots)
    nonisolated(unsafe) static var fullLineup = false
    /// GET /collection/items: this many per page instead of the asked limit (paging tests)
    nonisolated(unsafe) static var pageSize: Int?
    /// what the server fills into a collection item per card id: name, set, number, value of a copy (cents)
    nonisolated(unsafe) static var cards: [String: (name: String, set: String, number: String, cents: Int)] = [:]

    static func reset() {
        lock.lock(); defer { lock.unlock() }
        accounts = [:]; tokens = [:]; calls = []; failNextReport = false; offline = false; nextId = 1; fullLineup = false; pageSize = nil; cards = [:]
    }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [StandIn.self]
        return URLSession(configuration: c)
    }

    static func calls(_ path: String) -> [Call] { lock.lock(); defer { lock.unlock() }; return calls.filter { $0.path.hasSuffix(path) } }

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "standin.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let r = request
        var body: [String: Any] = [:]
        if let s = r.httpBodyStream {
            s.open()
            var d = Data()
            var buf = [UInt8](repeating: 0, count: 4096)
            while s.hasBytesAvailable { let n = s.read(&buf, maxLength: buf.count); if n <= 0 { break }; d.append(buf, count: n) }
            s.close()
            body = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] ?? [:]
        } else if let d = r.httpBody {
            body = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] ?? [:]
        }
        let path = r.url!.path
        // a GET's query (cursor, limit) comes to handle() as its body
        if (r.httpMethod ?? "GET") == "GET", let items = URLComponents(url: r.url!, resolvingAgainstBaseURL: false)?.queryItems {
            for q in items { body[q.name] = q.value ?? "" }
        }
        Self.lock.lock()
        let offline = Self.offline
        Self.calls.append(Call(method: r.httpMethod ?? "GET", path: path, body: body))
        let (code, answer) = offline ? (0, nil) : handle(r.httpMethod ?? "GET", path, body, r.value(forHTTPHeaderField: "Authorization"))
        Self.lock.unlock()
        if offline {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let res = HTTPURLResponse(url: r.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: res, cacheStoragePolicy: .notAllowed)
        if let answer { client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: answer)) }
        client?.urlProtocolDidFinishLoading(self)
    }

    private func status(_ a: Account) -> [String: Any] {
        let now = Date()
        let iso = ISO8601DateFormatter()
        var o: [String: Any] = [
            "status": a.active ? "active" : "none",
            "server_time": iso.string(from: now),
            "valid_until": iso.string(from: now.addingTimeInterval(a.active ? 30 * 86_400 : 0)),
            "account": ["id": a.id, "email": a.email, "name": NSNull(), "store_ref": a.storeRef, "email_verified": a.emailVerified] as [String: Any],
            "device": ["id": "dev_1", "name": "iPhone"],
            "features": a.active ? ["scanner": true, "trading": true, "collection": true, "wanted": true, "youtube": a.plan == "creator"] : [String: Bool](),
        ]
        if a.active {
            o["subscription"] = ["plan": a.plan ?? "collector", "base_plan": a.basePlan ?? "monthly", "state": "active", "source": a.source ?? "app_store",
                                 "manage_url": "https://apps.apple.com/account/subscriptions", "renews_at": iso.string(from: now.addingTimeInterval(30 * 86_400)),
                                 "ends_at": NSNull(), "devices": ["included": 1, "extra": a.devicesInStore, "in_store": a.devicesInStore, "on_web": 0, "in_use": 1]] as [String: Any]
        } else {
            o["subscription"] = NSNull()
        }
        return o
    }

    private func signIn(_ email: String) -> (Int, [String: Any]?) {
        var created = false
        let a: Account
        if let x = Self.accounts[email] { a = x } else {
            a = Account(id: "acc_\(Self.nextId)", email: email)
            Self.nextId += 1
            Self.accounts[email] = a
            created = true
        }
        let token = "tok_\(Self.nextId)_\(UUID().uuidString.prefix(8))"
        Self.nextId += 1
        Self.tokens[token] = a
        return (200, ["token": token, "created": created, "status": status(a)])
    }

    private func err(_ code: Int, _ e: String, _ extra: [String: Any] = [:]) -> (Int, [String: Any]?) {
        (code, (["error": e, "message": e] as [String: Any]).merging(extra) { $1 })
    }

    /// the payload of a JWS (the middle part, base64url)
    static func payload(_ jws: String) -> [String: Any]? {
        let parts = jws.split(separator: ".")
        guard parts.count == 3 else { return nil }
        var s = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return Data(base64Encoded: s).flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
    }

    private func handle(_ method: String, _ path: String, _ body: [String: Any], _ auth: String?) -> (Int, [String: Any]?) {
        let p = path.replacingOccurrences(of: "/api/app/v1", with: "")
        switch (method, p) {
        case ("POST", "/login/apple"):
            let t = body["identity_token"] as? String ?? ""
            guard t.hasPrefix("test_apple:") else { return err(400, "invalid_identity") }
            return signIn(String(t.dropFirst("test_apple:".count)))
        case ("POST", "/login/email"):
            return (202, ["resend_after": 60])
        case ("POST", "/login/email/verify"):
            guard body["code"] as? String == "000000" else { return err(400, "wrong_code", ["tries_left": 4]) }
            return signIn(body["email"] as? String ?? "")
        default: break
        }
        guard let token = auth?.replacingOccurrences(of: "Bearer ", with: ""), let a = Self.tokens[token] else { return err(401, "invalid_token") }
        switch (method, p) {
        case ("GET", "/status"):
            return (200, status(a))
        case ("GET", "/store/offers"):
            func plan(_ key: String, _ name: String, _ features: [String], devices: [String: [String]]) -> [String: Any] {
                ["plan": key, "name": name, "features": features, "google": NSNull(),
                 "apple": ["product_ids": ["monthly": "oc_\(key)_monthly", "yearly": "oc_\(key)_yearly"], "device_product_ids": devices] as [String: Any]]
            }
            func lineup(_ key: String) -> [String: [String]] {
                ["monthly": (1...4).map { "oc_\(key)_devices_\($0)_monthly" }, "yearly": (1...4).map { "oc_\(key)_devices_\($0)_yearly" }]
            }
            let collectorDevices = Self.fullLineup ? lineup("collector") : ["monthly": ["oc_collector_devices_1_monthly", "oc_collector_devices_2_monthly"]]
            let creatorDevices = Self.fullLineup ? lineup("creator") : [:]
            return (200, ["offers": [plan("collector", "Collector", ["scanner", "trading", "collection", "wanted"], devices: collectorDevices),
                                     plan("creator", "Creator", ["scanner", "trading", "youtube", "recording", "collection", "wanted"], devices: creatorDevices)],
                          "affiliate": a.affiliate.map { $0 as Any } ?? NSNull()])
        case ("POST", "/store/apple"):
            if Self.failNextReport { Self.failNextReport = false; return err(503, "store_unavailable") }
            guard let jws = body["signed_transaction"] as? String, let t = Self.payload(jws), let product = t["productId"] as? String else {
                return err(400, "invalid_purchase")
            }
            if let ref = t["appAccountToken"] as? String, ref.lowercased() != a.storeRef {
                let owner = Self.accounts.values.first { $0.storeRef == ref.lowercased() }?.email ?? "someone"
                return err(409, "linked_elsewhere", ["owner": String(owner.prefix(1)) + "***"])
            }
            if product.contains("_devices_") {
                a.devicesInStore = Int(product.split(separator: "_")[3]) ?? 1
            } else {
                a.active = true
                a.source = "app_store"
                a.plan = product.contains("creator") ? "creator" : "collector"
                a.basePlan = product.hasSuffix("yearly") ? "yearly" : "monthly"
                a.originalTransaction = "\(t["originalTransactionId"] ?? "")"
            }
            return (200, status(a))
        case ("POST", "/affiliate"):
            guard (body["code"] as? String)?.uppercased() == "MISTY" else { return err(404, "unknown_code") }
            a.affiliate = ["code": "MISTY", "discount_percent": 20, "discount_months": 3, "apple_offer_code": "MISTY20"]
            return (200, ["affiliate": a.affiliate!])
        case ("POST", "/account/delete"):
            Self.tokens = Self.tokens.filter { $0.value !== a }
            Self.accounts[a.email] = nil
            return (202, ["deleted": true, "manage_url": a.source == "app_store" ? "https://apps.apple.com/account/subscriptions" as Any : NSNull()])
        case ("DELETE", "/device"):
            Self.tokens[token] = nil
            return (204, nil)
        case ("GET", "/wanted"):
            return (200, ["items": a.wanted])
        case ("POST", "/wanted"):
            var item = body
            item["id"] = Self.nextId
            item["state"] = "searching"
            Self.nextId += 1
            a.wanted.append(item)
            return (201, item)
        case ("POST", "/collection/items"):
            var item = body
            item["id"] = Self.nextId
            let c = Self.cards[body["card_id"] as? String ?? ""]
            item["name"] = c?.name ?? body["card_id"]
            item["set_name"] = c?.set ?? ""
            item["number"] = c?.number ?? ""
            item["value_cents"] = c.map { $0.cents as Any } ?? NSNull()
            item["updated_at"] = String(format: "2026-11-21T10:%02d:%02d.000Z", (Self.nextId / 60) % 60, Self.nextId % 60)
            Self.nextId += 1
            a.collection.append(item)
            return (201, ["item": item])
        case ("GET", "/collection/items"):
            let limit = Self.pageSize ?? Int(body["limit"] as? String ?? "") ?? 200
            let start = Int(body["cursor"] as? String ?? "") ?? 0
            let page = Array(a.collection.dropFirst(start).prefix(limit))
            let end = start + page.count
            return (200, ["items": page, "next_cursor": end < a.collection.count ? String(end) as Any : NSNull(),
                          "server_time": "2026-11-21T11:00:00.000Z"])
        default:
            if method == "DELETE", p.hasPrefix("/wanted/"), let id = Int(p.dropFirst("/wanted/".count)) {
                a.wanted.removeAll { ($0["id"] as? Int) == id }
                return (204, nil)
            }
            if method == "GET", p.hasPrefix("/collection/cards/") {
                let card = String(p.dropFirst("/collection/cards/".count))
                return (200, ["items": a.collection.filter { $0["card_id"] as? String == card }])
            }
            return err(404, "not_found")
        }
    }
}
