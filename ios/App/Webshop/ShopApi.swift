import CryptoKit
import Foundation

/**
 * The shop's product API v1 (pokeshop docs/openapi.yaml, docs/api-signing.md): look a card up in
 * the shop's catalogue and book copies into stock or out of it. Every request is signed with
 * HMAC-SHA256 (key id + secret); every write carries an Idempotency-Key that stays the same on
 * retries, so a retry never books twice. The secret never leaves the phone: only the signature is
 * sent, and it is never logged. (Port of the Android app's ShopApi.kt.)
 */
enum ApiSigner {
    static func sha256Hex(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }

    /// The X-Signature value, "v1=<hex>": timestamp, method, path with query, body hash, joined by \n.
    static func sign(secret: String, timestamp: Int64, method: String, pathWithQuery: String, body: Data) -> String {
        let canonical = [String(timestamp), method.uppercased(), pathWithQuery, sha256Hex(body)].joined(separator: "\n")
        let mac = HMAC<SHA256>.authenticationCode(for: Data(canonical.utf8), using: SymmetricKey(data: Data(secret.utf8)))
        return "v1=" + mac.map { String(format: "%02x", $0) }.joined()
    }
}

/// The shop's clock as seen from the phone: every answer's Date header corrects it.
final class ServerClock: @unchecked Sendable {
    private let lock = NSLock()
    private var offset: TimeInterval = 0

    var offsetSeconds: TimeInterval { lock.lock(); defer { lock.unlock() }; return offset }
    func nowSeconds() -> Int64 { Int64((Date().timeIntervalSince1970 + offsetSeconds).rounded(.down)) }
    func observe(_ serverDate: Date?) {
        guard let serverDate else { return }
        lock.lock(); offset = serverDate.timeIntervalSinceNow; lock.unlock()
    }

    private static let httpDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f
    }()

    static func parseHttpDate(_ s: String?) -> Date? { s.flatMap { httpDate.date(from: $0) } }
}

/// Where the shop is and the key to it. baseUrl ends with /api/v1.
struct ShopCredentials: CustomStringConvertible {
    let baseUrl: String
    let keyId: String
    let secret: String

    /// never the secret
    var description: String { "ShopCredentials(\(baseUrl), \(keyId), secret=…)" }

    /**
     * "staging.overpricedcardboard.com", "https://overpricedcardboard.com" or ".../api/v1" → https://host/api/v1.
     * Plain http only for a shop on this computer (localhost).
     */
    static func baseUrlOf(_ host: String) -> String? {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        while h.hasSuffix("/") { h.removeLast() }
        if h.isEmpty { return nil }
        if !h.contains("://") { h = "https://" + h }
        guard var c = URLComponents(string: h), let scheme = c.scheme?.lowercased(), let name = c.host?.lowercased(), !name.isEmpty else { return nil }
        guard scheme == "https" || (scheme == "http" && ["localhost", "127.0.0.1", "10.0.2.2"].contains(name)) else { return nil }
        var path = c.percentEncodedPath
        while path.hasSuffix("/") { path.removeLast() }
        if path.hasSuffix("/api/v1") { path.removeLast(7) }
        c.scheme = scheme
        c.host = name
        c.percentEncodedPath = (path + "/api/v1").replacingOccurrences(of: "//", with: "/")
        c.query = nil
        c.fragment = nil
        return c.string
    }
}

/// Money as the shop writes it: integer cents in EUR.
struct ShopMoney: Equatable {
    let cents: Int64
    var euros: Double { Double(cents) / 100 }
    var json: JSON { ["amount": cents, "currency": "EUR"] }
    static func ofEuros(_ v: Double) -> ShopMoney { ShopMoney(cents: Int64((v * 100).rounded())) }
    static func from(_ o: Any?) -> ShopMoney? { (o as? JSON).flatMap { ($0["amount"] as? NSNumber).map { ShopMoney(cents: $0.int64Value) } } }
}

struct StockBox: Equatable {
    let box: String
    let quantity: Int
    let reserved: Int
}

struct ShopStock: Equatable {
    let quantity: Int
    let reserved: Int
    let available: Int
    let boxes: [StockBox]

    static let empty = ShopStock(quantity: 0, reserved: 0, available: 0, boxes: [])

    static func from(_ o: Any?) -> ShopStock {
        guard let o = o as? JSON else { return .empty }
        let boxes = (o["boxes"] as? [JSON] ?? []).map {
            StockBox(box: ShopJSON.str($0, "storage_location") ?? "", quantity: ShopJSON.int($0, "quantity"), reserved: ShopJSON.int($0, "reserved"))
        }
        return ShopStock(quantity: ShopJSON.int(o, "quantity"), reserved: ShopJSON.int(o, "reserved"), available: ShopJSON.int(o, "available"), boxes: boxes)
    }
}

struct ShopImage: Equatable {
    let id: Int64
    let role: String
    let status: String
    let url: String?
}

/// One product of the shop: one card in one language, condition, finish (and grading).
struct ShopProduct: Equatable, Identifiable {
    let sku: String
    let cardRef: String?
    let setCode: String
    let cardNumber: String
    let nameDe: String
    let nameEn: String
    let cardLanguage: String
    let condition: String
    let finish: String
    let grading: String?
    let price: ShopMoney?
    let highValue: Bool
    let status: String
    let url: String?
    var stock: ShopStock
    let images: [ShopImage]
    let updatedAt: String?

    var id: String { sku }

    /// the shop's picture: the first ready front image, else the first ready one
    var imageUrl: String? {
        (images.first { $0.status == "ready" && $0.role == "front" } ?? images.first { $0.status == "ready" })?.url
    }

    static func from(_ o: JSON) throws -> ShopProduct {
        guard let sku = ShopJSON.str(o, "sku") else { throw ShopError(status: 200, code: "bad_answer", serverMessage: "a product without sku") }
        let grading = (o["grading"] as? JSON).map { g in
            "\(ShopJSON.str(g, "company") ?? "") \(ShopJSON.str(g, "grade") ?? "")".trimmingCharacters(in: .whitespaces)
        }
        return ShopProduct(
            sku: sku, cardRef: ShopJSON.str(o, "card_ref"), setCode: ShopJSON.str(o, "set_code") ?? "",
            cardNumber: ShopJSON.str(o, "card_number") ?? "", nameDe: ShopJSON.str(o, "name_de") ?? "", nameEn: ShopJSON.str(o, "name_en") ?? "",
            cardLanguage: ShopJSON.str(o, "card_language") ?? "", condition: ShopJSON.str(o, "condition") ?? "",
            finish: ShopJSON.str(o, "finish") ?? "", grading: grading, price: ShopMoney.from(o["price"]),
            highValue: (o["high_value"] as? Bool) ?? false, status: ShopJSON.str(o, "status") ?? "", url: ShopJSON.str(o, "url"),
            stock: ShopStock.from(o["stock"]),
            images: (o["images"] as? [JSON] ?? []).map {
                ShopImage(id: ($0["id"] as? NSNumber)?.int64Value ?? 0, role: ShopJSON.str($0, "role") ?? "",
                          status: ShopJSON.str($0, "status") ?? "", url: ShopJSON.str($0, "url"))
            },
            updatedAt: ShopJSON.str(o, "updated_at"))
    }
}

/// A card of the shop's master data (the TCGdex catalogue it keeps).
struct MasterCard: Equatable {
    let cardRef: String
    let setCode: String
    let setName: String?
    let localId: String
    let cardCount: Int?
    let name: String?
    let rarity: String?
    let imageUrl: String?
}

struct IntakeResult {
    let created: Bool
    let product: ShopProduct
    let warnings: [String]
    let replayed: Bool
}

/// A write prepared once: the same bytes and the same Idempotency-Key on every retry.
struct PendingWrite: Equatable {
    let method: String
    let path: String
    let body: Data
    var idempotencyKey = UUID().uuidString.lowercased()
    var contentType = "application/json"

    static func == (a: PendingWrite, b: PendingWrite) -> Bool { a.idempotencyKey == b.idempotencyKey }

    static func json(_ method: String, _ path: String, _ body: JSON) -> PendingWrite {
        PendingWrite(method: method, path: path, body: ShopJSON.bytes(body))
    }

    /// POST /products/{sku}/images with a photo (JPEG) as multipart/form-data: fields "role" and "file".
    static func image(sku: String, jpeg: Data, role: String = "front") -> PendingWrite {
        let boundary = "cardscanner-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        var out = Data()
        func line(_ t: String) { out.append(Data((t + "\r\n").utf8)) }
        line("--\(boundary)"); line("Content-Disposition: form-data; name=\"role\""); line(""); line(role)
        line("--\(boundary)"); line("Content-Disposition: form-data; name=\"file\"; filename=\"card.jpg\""); line("Content-Type: image/jpeg"); line("")
        out.append(jpeg); line("")
        line("--\(boundary)--")
        return PendingWrite(method: "POST", path: "/products/\(sku)/images", body: out, contentType: "multipart/form-data; boundary=\(boundary)")
    }

    /// the body as JSON (nil for a photo)
    var jsonBody: JSON? { (try? JSONSerialization.jsonObject(with: body)) as? JSON }
}

/// An answer of the shop that isn't a success (or no answer at all: status 0).
struct ShopError: Error, Equatable {
    let status: Int
    let code: String
    var serverMessage: String? = nil
    var fields: [String: String] = [:]

    /// for the person (the server's message is English developer text, so it's only a fallback)
    var friendly: String {
        switch code {
        case "offline": return "No connection to the shop – check the internet and try again"
        case "missing_headers", "bad_timestamp", "bad_signature_format", "signature_mismatch":
            return "The shop doesn't accept this key – check key id, secret and host (staging keys don't work on the live shop)"
        case "timestamp_out_of_range": return "The phone's clock is more than 5 minutes off"
        case "key_revoked": return "This key is no longer valid – create a new one in the shop's admin"
        case "ip_not_allowed": return "This key only works from certain addresses – remove its IP allowlist for phones"
        case "scope_missing": return "This key isn't allowed to do that"
        case "not_found": return "Not in the shop"
        case "insufficient_stock": return "The box doesn't hold that many copies"
        case "unknown_card": return "The shop doesn't know this card yet"
        case "external_id_taken", "conflict", "identity_mismatch": return "The shop refused: \(serverMessage ?? code)"
        case "idempotency_conflict": return "This booking was changed after it was sent – try again"
        case "too_large": return "Too large for the shop"
        case "rate_limited": return "Too many requests – wait a moment"
        case "validation":
            return "The shop refused the input" + (fields.isEmpty ? "" : ": " + fields.sorted { $0.key < $1.key }.map { "\($0.key): \($0.value)" }.joined(separator: "; "))
        case "internal", "server": return "The shop has a problem right now – try again in a moment"
        default: return "The shop answered \(status) \(serverMessage ?? code)"
        }
    }

    /// sending it again (the same key) may work: no answer, or the shop had a problem
    var retryable: Bool { status == 0 || status >= 500 }
}

/// A friendlier text for the warnings a successful write can carry.
func shopWarningText(_ w: String) -> String {
    switch w {
    case "not_activated_no_price": return "No price: it stays a draft (not visible in the shop)"
    case "not_activated_no_image": return "No picture: it stays a draft (not visible in the shop)"
    case "high_value_ignored_set_by_price": return "High value is set by the price"
    default:
        let p = "copies_in_open_checkout_stay_in_"
        return w.hasPrefix(p) ? "Copies in an open checkout stay in box \(w.dropFirst(p.count))" : w
    }
}

/// JSON helpers for the shop's answers: JSON null and missing keys are nil (never "null").
enum ShopJSON {
    static func str(_ o: JSON, _ k: String) -> String? {
        guard let v = o[k], !(v is NSNull) else { return nil }
        return (v as? String) ?? "\(v)"
    }

    static func int(_ o: JSON, _ k: String) -> Int { (o[k] as? NSNumber)?.intValue ?? 0 }

    /// the bytes sent (and signed): keys sorted, slashes as they are
    static func bytes(_ o: JSON) -> Data {
        (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data("{}".utf8)
    }
}

/**
 * The shop API over HTTPS. Retries with the same Idempotency-Key after no answer or a 5xx
 * (1, 2, 4 s), after "in progress" (1.5 s), after a rate limit (Retry-After) and once after a
 * clock correction.
 */
final class ShopClient: @unchecked Sendable {
    let creds: ShopCredentials
    let clock: ServerClock
    private let session: URLSession
    /// waits between retries (milliseconds); the tests don't wait
    private let sleep: @Sendable (UInt64) async -> Void

    init(_ creds: ShopCredentials, clock: ServerClock = ServerClock(), session: URLSession? = nil,
         sleep: @escaping @Sendable (UInt64) async -> Void = { ms in try? await Task.sleep(nanoseconds: ms * 1_000_000) }) {
        self.creds = creds
        self.clock = clock
        self.sleep = sleep
        if let session { self.session = session } else {
            let c = URLSessionConfiguration.ephemeral
            c.timeoutIntervalForRequest = 60
            c.waitsForConnectivity = false
            self.session = URLSession(configuration: c)
        }
    }

    struct Health { let keyId: String; let serverTime: String? }

    func health() async throws -> Health {
        let o = try await call("GET", "/health")
        return Health(keyId: ShopJSON.str(o, "key_id") ?? "", serverTime: ShopJSON.str(o, "server_time"))
    }

    /// Cards of the shop's master data (the TCGdex catalogue it keeps).
    func masterCards(cardRef: String? = nil, set: String? = nil, number: String? = nil, q: String? = nil, lang: String = "de") async throws -> [MasterCard] {
        var query: [(String, String)] = []
        if let cardRef { query.append(("card_ref", cardRef)) }
        if let set { query.append(("set", set)) }
        if let number { query.append(("number", number)) }
        if let q { query.append(("q", q)) }
        query.append(("lang", lang))
        return (try await call("GET", "/master/cards", query)["items"] as? [JSON] ?? []).compactMap { o in
            guard let ref = ShopJSON.str(o, "card_ref") else { return nil }
            return MasterCard(cardRef: ref, setCode: ShopJSON.str(o, "set_code") ?? "", setName: ShopJSON.str(o, "set_name"),
                              localId: ShopJSON.str(o, "local_id") ?? "", cardCount: (o["card_count"] as? NSNumber)?.intValue,
                              name: ShopJSON.str(o, "name"), rarity: ShopJSON.str(o, "rarity"), imageUrl: ShopJSON.str(o, "image_url"))
        }
    }

    /// Every product of one card (all languages, conditions, finishes), paging through.
    func productsOfCard(_ cardRef: String) async throws -> [ShopProduct] {
        var out: [ShopProduct] = []
        var cursor: String?
        repeat {
            var query = [("card_ref", cardRef), ("limit", "200")]
            if let cursor { query.append(("cursor", cursor)) }
            let o = try await call("GET", "/products", query)
            for p in o["items"] as? [JSON] ?? [] { out.append(try ShopProduct.from(p)) }
            cursor = ShopJSON.str(o, "next_cursor")
        } while cursor != nil && out.count < 2000
        return out
    }

    func product(_ sku: String) async throws -> ShopProduct { try ShopProduct.from(try await call("GET", "/products/\(sku)")) }

    /// POST /stock/intake: find or create the product and add copies to a box.
    func intake(_ w: PendingWrite) async throws -> IntakeResult {
        let (o, replayed) = try await write(w)
        guard let p = o["product"] as? JSON else { throw ShopError(status: 200, code: "bad_answer", serverMessage: "no product in the answer") }
        return IntakeResult(created: (o["created"] as? Bool) ?? false, product: try ShopProduct.from(p),
                            warnings: o["warnings"] as? [String] ?? [], replayed: replayed)
    }

    /// POST /products/{sku}/images: a photo for the product (the shop makes its sizes in the background).
    func uploadImage(_ w: PendingWrite) async throws { _ = try await write(w) }

    /// PATCH /products/{sku}: changed fields (e.g. status); the product as it is now and the shop's warnings.
    func patchProduct(_ w: PendingWrite) async throws -> (ShopProduct, [String]) {
        let o = try await write(w).0
        return (try ShopProduct.from(o), o["warnings"] as? [String] ?? [])
    }

    /// POST /products/{sku}/stock: add, remove, set or move copies. The new stock.
    func stock(_ w: PendingWrite) async throws -> ShopStock { ShopStock.from(try await write(w).0) }

    /// One result of POST /batch: the status the single call would have had, and its body (the new stock, or the error).
    struct BatchResult {
        let status: Int
        let body: JSON?
        var ok: Bool { (200...299).contains(status) }
        var error: ShopError? {
            ok ? nil : ShopError(status: status, code: body.flatMap { ShopJSON.str($0, "error") } ?? "http_\(status)",
                                 serverMessage: body.flatMap { ShopJSON.str($0, "message") })
        }
    }

    /// POST /batch: up to 100 operations under one Idempotency-Key, each on its own (one failing doesn't undo the others).
    func batch(_ w: PendingWrite) async throws -> [BatchResult] {
        (try await write(w).0["results"] as? [JSON] ?? []).map { BatchResult(status: ShopJSON.int($0, "status"), body: $0["body"] as? JSON) }
    }

    /// A write with its Idempotency-Key: the answer and whether it was a replay of an earlier one.
    func write(_ w: PendingWrite) async throws -> (JSON, Bool) {
        var replayed = false
        let o = try await call(w.method, w.path, [], body: w.body, idempotencyKey: w.idempotencyKey, contentType: w.contentType) { replayed = $0 }
        return (o, replayed)
    }

    /// The URL of a call and its path with query exactly as sent (what is signed).
    func url(_ path: String, _ query: [(String, String)]) -> (URL, String)? {
        guard var c = URLComponents(string: creds.baseUrl) else { return nil }
        let segments = path.split(separator: "/").map { String($0).addingPercentEncoding(withAllowedCharacters: Self.segmentChars) ?? String($0) }
        c.percentEncodedPath = c.percentEncodedPath + "/" + segments.joined(separator: "/")
        if !query.isEmpty {
            c.percentEncodedQuery = query.map { "\(Self.queryEncode($0.0))=\(Self.queryEncode($0.1))" }.joined(separator: "&")
        }
        guard let u = c.url else { return nil }
        return (u, c.percentEncodedPath + (c.percentEncodedQuery.map { "?" + $0 } ?? ""))
    }

    private static let segmentChars: CharacterSet = {
        var s = CharacterSet.alphanumerics
        s.insert(charactersIn: "-._~")
        return s
    }()

    private static func queryEncode(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: segmentChars) ?? s }

    private func call(_ method: String, _ path: String, _ query: [(String, String)] = [], body: Data? = nil, idempotencyKey: String? = nil,
                      contentType: String = "application/json", onReplay: (Bool) -> Void = { _ in }) async throws -> JSON {
        guard let target = url(path, query) else { throw ShopError(status: 0, code: "bad_url", serverMessage: creds.baseUrl) }
        let (endpoint, pathWithQuery) = target
        var attempt = 0
        var clockRetried = false
        while true {
            let bytes = body ?? Data()
            let ts = clock.nowSeconds()
            var req = URLRequest(url: endpoint)
            req.httpMethod = method
            req.timeoutInterval = 60
            req.setValue(creds.keyId, forHTTPHeaderField: "X-Key-Id")
            req.setValue(String(ts), forHTTPHeaderField: "X-Timestamp")
            req.setValue(ApiSigner.sign(secret: creds.secret, timestamp: ts, method: method, pathWithQuery: pathWithQuery, body: bytes),
                         forHTTPHeaderField: "X-Signature")
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            if let idempotencyKey { req.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key") }
            if let body {
                req.setValue(contentType, forHTTPHeaderField: "Content-Type")
                req.httpBody = body
            }
            let retryIn: UInt64
            do {
                let (data, resp) = try await session.data(for: req)
                guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
                clock.observe(ServerClock.parseHttpDate(http.value(forHTTPHeaderField: "Date")))
                let json = (try? JSONSerialization.jsonObject(with: data)) as? JSON
                if (200...299).contains(http.statusCode) {
                    onReplay(http.value(forHTTPHeaderField: "Idempotent-Replayed") == "true")
                    return json ?? [:]
                }
                let code = json.flatMap { ShopJSON.str($0, "error") } ?? (http.statusCode >= 500 ? "server" : "http_\(http.statusCode)")
                let fields = (json?["fields"] as? JSON)?.reduce(into: [String: String]()) { $0[$1.key] = ($1.value as? String) ?? "\($1.value)" } ?? [:]
                let err = ShopError(status: http.statusCode, code: code, serverMessage: json.flatMap { ShopJSON.str($0, "message") }, fields: fields)
                if http.statusCode >= 500 && attempt < 3 { retryIn = 1000 << UInt64(attempt) }
                else if code == "idempotency_in_progress" && attempt < 3 { retryIn = 1500 }
                else if http.statusCode == 429 && attempt < 2 {
                    retryIn = UInt64(min(30, max(1, Int(http.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 2))) * 1000
                } else if code == "timestamp_out_of_range" && !clockRetried { clockRetried = true; retryIn = 0 }
                else { throw err }
            } catch let e as ShopError {
                throw e
            } catch {
                if Task.isCancelled { throw error }
                if attempt >= 3 { throw ShopError(status: 0, code: "offline", serverMessage: error.localizedDescription) }
                retryIn = 1000 << UInt64(attempt)
            }
            attempt += 1
            if retryIn > 0 { await sleep(retryIn) }
        }
    }

    /**
     * The body of POST /stock/intake. imageUrl: a picture of the card for the product (the shop
     * fetches it), when the shop has none; status: "active" to make a draft product live.
     */
    static func intakeBody(cardRef: String, language: String, condition: String, finish: String, delta: Int, box: String,
                           price: ShopMoney? = nil, descriptionDe: String? = nil, nameDe: String? = nil, nameEn: String? = nil,
                           imageUrl: String? = nil, status: String? = nil, nameLocal: String? = nil) -> JSON {
        func text(_ s: String?) -> String? { s.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 } }
        var o: JSON = ["card_ref": cardRef, "card_language": language, "condition": condition, "finish": finish, "delta": delta,
                       "storage_location": box]
        if let price { o["price"] = price.json }
        if let d = text(descriptionDe) { o["description_de"] = d }
        if let n = text(nameDe) { o["name_de"] = n }
        if let n = text(nameEn) { o["name_en"] = n }
        if let n = text(nameLocal) { o["name_local"] = n }
        if let imageUrl, imageUrl.hasPrefix("https://") { o["images"] = [["role": "front", "url": imageUrl]] }
        if let status { o["status"] = status }
        return o
    }

    /// The body of POST /batch changing the stock of several products: (sku, box, delta) each, with a reason.
    static func stockBatchBody(_ changes: [(sku: String, box: String, delta: Int)], reason: String) -> JSON {
        ["operations": changes.map { c in
            ["op": "stock", "sku": c.sku, "body": ["delta": c.delta, "storage_location": c.box, "reason": reason] as JSON] as JSON
        }]
    }

    /// A storage box as the shop wants it: "014" for 14 (1–999).
    static func box(_ n: Int) -> String { String(format: "%03d", n) }
}
