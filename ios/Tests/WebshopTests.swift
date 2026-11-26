import XCTest
@testable import OverpricedCardboard

/// A shop's product API in memory, behind URLProtocol: answers from a queue (or a handler), and every request it saw.
final class FakeShop: URLProtocol {
    struct Reply {
        var status: Int
        var body: String = ""
        var headers: [String: String] = [:]
        /// only for a request whose path ends so (nil: the next request)
        var only: String? = nil
    }

    struct Seen {
        let method: String
        /// path and query as sent
        let path: String
        let headers: [String: String]
        let body: Data
        var json: [String: Any] { (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:] }
        func header(_ k: String) -> String? { headers.first { $0.key.lowercased() == k.lowercased() }?.value }
    }

    static let lock = NSLock()
    nonisolated(unsafe) static var replies: [Reply] = []
    nonisolated(unsafe) static var seen: [Seen] = []
    /// answers when the queue is empty
    nonisolated(unsafe) static var handler: ((Seen) -> Reply)?

    static func reset() { lock.lock(); replies = []; seen = []; handler = nil; lock.unlock() }
    static func enqueue(_ r: Reply...) { lock.lock(); replies += r; lock.unlock() }
    static func requests() -> [Seen] { lock.lock(); defer { lock.unlock() }; return seen }
    static func requests(_ suffix: String) -> [Seen] { requests().filter { $0.path.components(separatedBy: "?")[0].hasSuffix(suffix) } }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeShop.self]
        return URLSession(configuration: c)
    }

    /// every request of a session made with it (session()), whatever its host
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let r = request
        var body = r.httpBody ?? Data()
        if let s = r.httpBodyStream {
            s.open()
            var buf = [UInt8](repeating: 0, count: 4096)
            while s.hasBytesAvailable { let n = s.read(&buf, maxLength: buf.count); if n <= 0 { break }; body.append(buf, count: n) }
            s.close()
        }
        let c = URLComponents(url: r.url!, resolvingAgainstBaseURL: false)!
        let seen = Seen(method: r.httpMethod ?? "GET", path: c.percentEncodedPath + (c.percentEncodedQuery.map { "?" + $0 } ?? ""),
                        headers: r.allHTTPHeaderFields ?? [:], body: body)
        Self.lock.lock()
        Self.seen.append(seen)
        let plain = seen.path.components(separatedBy: "?")[0]
        let reply: Reply
        if let i = Self.replies.firstIndex(where: { $0.only.map { plain.hasSuffix($0) } ?? true }) { reply = Self.replies.remove(at: i) }
        else { reply = Self.handler?(seen) ?? Reply(status: 500, body: #"{"error":"internal"}"#) }
        Self.lock.unlock()
        if reply.status == 0 {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        var headers = ["Content-Type": "application/json"]
        for (k, v) in reply.headers { headers[k] = v }
        let res = HTTPURLResponse(url: r.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: res, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

/// The webshop's product API (the docs' signing vectors, the client against a simulated shop) and Webshop Mode's Stock in and Sell.
@MainActor
final class WebshopTests: XCTestCase {
    private let secret = "sk_test_secret"
    private var saved: [String: Any?] = [:]
    private let keys = ["youtube", "webshop", "threshold"]

    override func setUp() async throws {
        FakeShop.reset()
        for k in keys { saved[k] = UserDefaults.standard.object(forKey: k) }
    }

    override func tearDown() async throws {
        for k in keys { if let v = saved[k] ?? nil { UserDefaults.standard.set(v, forKey: k) } else { UserDefaults.standard.removeObject(forKey: k) } }
    }

    private func client(_ clock: ServerClock = ServerClock()) -> ShopClient {
        ShopClient(ShopCredentials(baseUrl: "https://shop.test/api/v1", keyId: "kid_test", secret: secret), clock: clock, session: FakeShop.session(),
                   sleep: { _ in })
    }

    /// The request is signed as the shop checks it: timestamp, method, path with query, body hash.
    private func assertSigned(_ r: FakeShop.Seen, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(r.header("X-Key-Id"), "kid_test", file: file, line: line)
        let ts = Int64(r.header("X-Timestamp") ?? "") ?? 0
        XCTAssertEqual(r.header("X-Signature"), ApiSigner.sign(secret: secret, timestamp: ts, method: r.method, pathWithQuery: r.path, body: r.body),
                       file: file, line: line)
    }

    static let product = """
        {"sku":"sv03.5-199-DE-NM-H","external_id":null,"card_ref":"sv03.5-199","set_code":"sv03.5","set_name_de":"151","set_name_en":"151",
         "card_number":"199/165","name_de":"Glurak-ex","name_en":"Charizard ex","card_language":"DE","condition":"NM","finish":"holo","grading":null,
         "release_year":2023,"rarity":"Special Illustration Rare","price":{"amount":12900,"currency":"EUR"},"high_value":true,"status":"active",
         "url":"https://overpricedcardboard.com/karten/x",
         "stock":{"quantity":2,"reserved":1,"available":1,"boxes":[{"storage_location":"014","quantity":2,"reserved":1}]},
         "images":[{"id":731,"role":"front","status":"ready","url":"https://overpricedcardboard.com/media/p/128/731-1200.webp"},
                   {"id":732,"role":"back","status":"pending","url":null}],"updated_at":"2026-11-06T01:30:00.000Z"}
        """

    // ------------------------------------------------------------------ the client

    func testSignerMatchesTheApiDocVectors() {
        let ts: Int64 = 1793883600
        XCTAssertEqual(ApiSigner.sign(secret: "sk_example", timestamp: ts, method: "GET", pathWithQuery: "/api/v1/products/sv03.5-199-DE-NM-H", body: Data()),
                       "v1=074aa943428ea866471942182a5e5d1b9e8b58cf22edc8b080afa045e9d63edd")
        XCTAssertEqual(ApiSigner.sign(secret: "sk_example", timestamp: ts, method: "GET", pathWithQuery: "/api/v1/master/cards?q=glurak&lang=de", body: Data()),
                       "v1=85d459b5d4eb8f2e8286dfde286374a14e0c5a579212a602be39fab9cc5ae418")
        let body = Data(#"{"delta":1,"storage_location":"014"}"#.utf8)
        XCTAssertEqual(ApiSigner.sha256Hex(body), "b6b874fc8ed7e5fe2c0bc3b724f309d42b486438807cd12a9ad84a83fcd1bfcf")
        XCTAssertEqual(ApiSigner.sign(secret: "sk_example", timestamp: ts, method: "post", pathWithQuery: "/api/v1/products/sv03.5-199-DE-NM-H/stock", body: body),
                       "v1=977c2cfd09d2cc9b414365a09dec09ff3fce394210e954237b20eccf25f07816")
    }

    func testHostsBecomeApiBaseUrls() {
        XCTAssertEqual(ShopCredentials.baseUrlOf("staging.overpricedcardboard.com"), "https://staging.overpricedcardboard.com/api/v1")
        XCTAssertEqual(ShopCredentials.baseUrlOf(" https://overpricedcardboard.com/ "), "https://overpricedcardboard.com/api/v1")
        XCTAssertEqual(ShopCredentials.baseUrlOf("https://OverpricedCardboard.com/api/v1"), "https://overpricedcardboard.com/api/v1")
        XCTAssertEqual(ShopCredentials.baseUrlOf("http://localhost:3000"), "http://localhost:3000/api/v1")
        XCTAssertNil(ShopCredentials.baseUrlOf("http://overpricedcardboard.com"), "no plain http to the internet")
        XCTAssertNil(ShopCredentials.baseUrlOf("  "))
        XCTAssertFalse("\(ShopCredentials(baseUrl: "https://x/api/v1", keyId: "kid", secret: "sk_secret"))".contains("sk_secret"), "the secret never shows in logs")
    }

    func testHealthAndMasterCardsAreSigned() async throws {
        FakeShop.enqueue(FakeShop.Reply(status: 200, body: #"{"ok":true,"key_id":"kid_test","server_time":"2026-11-06T01:30:00.000Z"}"#),
                         FakeShop.Reply(status: 200, body: """
                             {"items":[{"card_ref":"sv03.5-199","set_code":"sv03.5","set_name":"151","local_id":"199","card_count":165,
                              "name":"Glurak-ex","rarity":"Special Illustration Rare","image_url":null}]}
                             """))
        let c = client()
        let h = try await c.health()
        XCTAssertEqual(h.keyId, "kid_test")
        let cards = try await c.masterCards(q: "glurak")
        XCTAssertEqual(cards.first?.name, "Glurak-ex")
        XCTAssertNil(cards.first?.imageUrl, "JSON null, not \"null\"")
        let r = FakeShop.requests()
        XCTAssertEqual(r.map(\.path), ["/api/v1/health", "/api/v1/master/cards?q=glurak&lang=de"])
        r.forEach { assertSigned($0) }
    }

    func testIntakeIsRetriedWithTheSameKeyAndBytes() async throws {
        FakeShop.enqueue(FakeShop.Reply(status: 503),                                     // the proxy while the shop restarts
                         FakeShop.Reply(status: 500, body: #"{"error":"internal"}"#),
                         FakeShop.Reply(status: 201, body: #"{"created":true,"product":\#(Self.product),"warnings":["not_activated_no_image"]}"#))
        let body = ShopClient.intakeBody(cardRef: "sv03.5-199", language: "DE", condition: "NM", finish: "holo", delta: 2, box: ShopClient.box(14),
                                         price: .ofEuros(129.0), descriptionDe: "  ")
        XCTAssertEqual(NSDictionary(dictionary: body), NSDictionary(dictionary: [
            "card_ref": "sv03.5-199", "card_language": "DE", "condition": "NM", "finish": "holo", "delta": 2, "storage_location": "014",
            "price": ["amount": 12900, "currency": "EUR"]]))
        let w = PendingWrite.json("POST", "/stock/intake", body)
        let r = try await client().intake(w)
        XCTAssertTrue(r.created)
        XCTAssertEqual(r.product.sku, "sv03.5-199-DE-NM-H")
        XCTAssertEqual(r.warnings, ["not_activated_no_image"])
        let sent = FakeShop.requests()
        XCTAssertEqual(sent.count, 3)
        XCTAssertEqual(Set(sent.compactMap { $0.header("Idempotency-Key") }), [w.idempotencyKey])
        XCTAssertEqual(Set(sent.map(\.body)).count, 1, "identical bytes every time")
        sent.forEach { assertSigned($0) }
    }

    /// A product picture taken with the camera: multipart (role, file), signed over its exact bytes, with an Idempotency-Key.
    func testAPhotoGoesUpAsMultipart() async throws {
        FakeShop.enqueue(FakeShop.Reply(status: 202, body: #"{"id":7,"role":"front","status":"pending","url":null}"#),
                         FakeShop.Reply(status: 200, body: Self.product.replacingOccurrences(of: #""status":"active""#, with: #""status":"active""#)))
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 0, 13, 10, 4])
        let w = PendingWrite.image(sku: "sv03.5-199-DE-NM-H", jpeg: jpeg)
        try await client().uploadImage(w)
        let r = try XCTUnwrap(FakeShop.requests().first)
        XCTAssertEqual(r.method, "POST")
        XCTAssertEqual(r.path, "/api/v1/products/sv03.5-199-DE-NM-H/images")
        XCTAssertTrue(r.header("Content-Type")?.hasPrefix("multipart/form-data; boundary=cardscanner-") == true)
        XCTAssertEqual(r.header("Idempotency-Key"), w.idempotencyKey)
        XCTAssertNotNil(r.body.range(of: jpeg), "the photo's bytes are in the body unchanged")
        let text = String(decoding: r.body, as: UTF8.self)
        XCTAssertTrue(text.contains("name=\"role\"\r\n\r\nfront\r\n"))
        XCTAssertTrue(text.contains("name=\"file\"; filename=\"card.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
        XCTAssertTrue(text.hasSuffix("--\r\n"))
        assertSigned(r)
        // then the draft goes live
        let (p, warnings) = try await client().patchProduct(PendingWrite.json("PATCH", "/products/sv03.5-199-DE-NM-H", ["status": "active"]))
        XCTAssertEqual(p.sku, "sv03.5-199-DE-NM-H")
        XCTAssertTrue(warnings.isEmpty)
        let patch = FakeShop.requests()[1]
        XCTAssertEqual(patch.method, "PATCH")
        XCTAssertEqual(String(decoding: patch.body, as: UTF8.self), #"{"status":"active"}"#)
        assertSigned(patch)
    }

    /// No picture in the shop: the app's picture of the card goes along (front, https only), and a draft can be made live.
    func testABookingCanBringThePicture() {
        let url = "https://images.pokemontcg.io/swsh11tg/TG11_hires.png"
        let body = ShopClient.intakeBody(cardRef: "swsh11tg-TG11", language: "EN", condition: "NM", finish: "holo", delta: 1, box: ShopClient.box(3),
                                         imageUrl: url, status: "active")
        XCTAssertEqual(String(decoding: ShopJSON.bytes(body), as: UTF8.self),
                       #"{"card_language":"EN","card_ref":"swsh11tg-TG11","condition":"NM","delta":1,"finish":"holo","images":[{"role":"front","url":"https://images.pokemontcg.io/swsh11tg/TG11_hires.png"}],"status":"active","storage_location":"003"}"#)
        XCTAssertNil(ShopClient.intakeBody(cardRef: "x", language: "DE", condition: "NM", finish: "normal", delta: 1, box: "001", imageUrl: "http://example.com/a.png")["images"])
        // an Asian print: its printed name goes along
        XCTAssertEqual(ShopClient.intakeBody(cardRef: "M1S-005", language: "JA", condition: "NM", finish: "normal", delta: 1, box: "001", nameEn: "Meganium",
                                             nameLocal: "メガニウム")["name_local"] as? String, "メガニウム")
    }

    func testAWrongClockIsCorrectedFromTheDateHeader() async throws {
        let serverNow = Date().addingTimeInterval(10 * 60)                 // the shop is 10 minutes ahead
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        FakeShop.enqueue(FakeShop.Reply(status: 401, body: #"{"error":"timestamp_out_of_range"}"#, headers: ["Date": f.string(from: serverNow)]),
                         FakeShop.Reply(status: 200, body: #"{"ok":true,"key_id":"kid_test","server_time":null}"#))
        _ = try await client().health()
        let second = FakeShop.requests()[1]
        assertSigned(second)
        XCTAssertLessThanOrEqual(abs((Int64(second.header("X-Timestamp") ?? "") ?? 0) - Int64(serverNow.timeIntervalSince1970)), 5, "the timestamp follows the shop's clock")
    }

    func testErrorsBecomeShopErrors() async throws {
        FakeShop.enqueue(FakeShop.Reply(status: 422, body: #"{"error":"validation","message":"request body is not valid","fields":{"price.amount":"Too small"}}"#))
        do {
            _ = try await client().intake(PendingWrite.json("POST", "/stock/intake", ["delta": 1]))
            XCTFail("no error")
        } catch let e as ShopError {
            XCTAssertEqual(e.status, 422)
            XCTAssertEqual(e.code, "validation")
            XCTAssertEqual(e.fields["price.amount"], "Too small")
            XCTAssertTrue(e.friendly.contains("price.amount"), e.friendly)
            XCTAssertFalse(e.retryable)
        }
        XCTAssertEqual(FakeShop.requests().count, 1, "a 4xx is not retried")
        FakeShop.enqueue(FakeShop.Reply(status: 403, body: #"{"error":"key_revoked"}"#))
        do { _ = try await client().health(); XCTFail("no error") } catch let e as ShopError {
            XCTAssertEqual(e.code, "key_revoked")
            XCTAssertTrue(e.friendly.contains("no longer valid"))
        }
        // no connection at all: tried four times, then "offline" (sending it again may work)
        FakeShop.enqueue(FakeShop.Reply(status: 0), FakeShop.Reply(status: 0), FakeShop.Reply(status: 0), FakeShop.Reply(status: 0))
        do { _ = try await client().health(); XCTFail("no error") } catch let e as ShopError {
            XCTAssertEqual(e.code, "offline")
            XCTAssertTrue(e.retryable)
        }
    }

    func testProductsArePagedAndNullsStayNull() async throws {
        let second = Self.product.replacingOccurrences(of: "sv03.5-199-DE-NM-H", with: "sv03.5-199-EN-EX-R")
            .replacingOccurrences(of: #""price":{"amount":12900,"currency":"EUR"}"#, with: #""price":null"#)
        FakeShop.enqueue(FakeShop.Reply(status: 200, body: #"{"items":[\#(Self.product)],"next_cursor":"MTI4"}"#),
                         FakeShop.Reply(status: 200, body: #"{"items":[\#(second)],"next_cursor":null}"#))
        let list = try await client().productsOfCard("sv03.5-199")
        XCTAssertEqual(list.map(\.sku), ["sv03.5-199-DE-NM-H", "sv03.5-199-EN-EX-R"])
        XCTAssertEqual(list[0].price?.euros ?? 0, 129.0, accuracy: 1e-9)
        XCTAssertNil(list[1].price)
        XCTAssertEqual(list[0].stock.quantity, 2)
        XCTAssertEqual(list[0].stock.boxes.map(\.box), ["014"])
        XCTAssertEqual(list[0].imageUrl, "https://overpricedcardboard.com/media/p/128/731-1200.webp")
        XCTAssertNil(list[0].grading)
        XCTAssertEqual(FakeShop.requests().map(\.path), ["/api/v1/products?card_ref=sv03.5-199&limit=200",
                                                         "/api/v1/products?card_ref=sv03.5-199&limit=200&cursor=MTI4"])
    }

    func testAReplayedWriteIsASuccess() async throws {
        FakeShop.enqueue(FakeShop.Reply(status: 200, body: #"{"quantity":1,"reserved":0,"available":1,"boxes":[{"storage_location":"014","quantity":1,"reserved":0}]}"#,
                                        headers: ["Idempotent-Replayed": "true"]))
        let (o, replayed) = try await client().write(PendingWrite.json("POST", "/products/sv03.5-199-DE-NM-H/stock",
                                                                       ["delta": -1, "storage_location": "014", "reason": "undone in the scanner app"]))
        XCTAssertTrue(replayed)
        XCTAssertEqual(ShopStock.from(o).quantity, 1)
        let r = try XCTUnwrap(FakeShop.requests().first)
        XCTAssertEqual(r.path, "/api/v1/products/sv03.5-199-DE-NM-H/stock")
        assertSigned(r)
    }

    func testASaleIsOneBatchAndEachLineGetsItsOwnResult() async throws {
        FakeShop.enqueue(FakeShop.Reply(status: 200, body: """
            {"results":[{"status":200,"body":{"quantity":1,"reserved":0,"available":1,"boxes":[{"storage_location":"014","quantity":1,"reserved":0}]}},
                        {"status":409,"body":{"error":"insufficient_stock","message":"box 003 holds 1"}}]}
            """))
        let body = ShopClient.stockBatchBody([("sv03.5-199-DE-NM-H", "014", -1), ("sv1-1-DE-NM-N", "003", -2)], reason: "sold in person (scanner app)")
        let results = try await client().batch(PendingWrite.json("POST", "/batch", body))
        XCTAssertEqual(results.count, 2)
        XCTAssertTrue(results[0].ok)
        XCTAssertNil(results[0].error)
        XCTAssertEqual(results[0].body?["quantity"] as? Int, 1)
        XCTAssertFalse(results[1].ok)
        XCTAssertEqual(results[1].error?.code, "insufficient_stock")
        let r = try XCTUnwrap(FakeShop.requests().first)
        XCTAssertEqual(r.path, "/api/v1/batch")
        assertSigned(r)
        XCTAssertFalse((r.header("Idempotency-Key") ?? "").isEmpty)
        let ops = try XCTUnwrap(r.json["operations"] as? [[String: Any]])
        XCTAssertEqual(ops.count, 2)
        XCTAssertEqual(ops[1]["op"] as? String, "stock")
        XCTAssertEqual(ops[1]["sku"] as? String, "sv1-1-DE-NM-N")
        let b = try XCTUnwrap(ops[1]["body"] as? [String: Any])
        XCTAssertEqual(b["delta"] as? Int, -2)
        XCTAssertEqual(b["storage_location"] as? String, "003", "the box as the shop writes it")
        XCTAssertEqual(b["reason"] as? String, "sold in person (scanner app)")
    }

    func testAnOrderSurvivesARestart() throws {
        let lines = [ShopOrderLine(sku: "sv03.5-199-DE-NM-H", name: "Glurak-ex", detail: "DE · NM · Holo · 199/165", box: "014", quantity: 2, price: 129.0),
                     ShopOrderLine(sku: "sv1-1-DE-NM-N", name: "Pikachu", detail: "DE · NM · Normal · 1/198", box: "003", quantity: 1, price: nil, error: "gone")]
        let back = try JSONDecoder().decode([ShopOrderLine].self, from: JSONEncoder().encode(lines))
        XCTAssertEqual(back[0], lines[0])
        XCTAssertNil(back[1].error, "a failed attempt's reason isn't kept")
        XCTAssertEqual(back[0].total ?? 0, 258.0, accuracy: 1e-9)
        XCTAssertNil(back[1].total, "no price: not in the total")
    }

    func testTheMinimumPriceIsAFloorForTheSuggestion() {
        XCTAssertEqual(suggestedPrice(0.08, 0.50), 0.50)          // a few cents -> the minimum
        XCTAssertEqual(suggestedPrice(3.20, 0.50), 3.20)          // worth more: its market value
        XCTAssertEqual(suggestedPrice(0.08, nil), 0.08)           // no minimum set
        XCTAssertNil(suggestedPrice(nil, 0.50))                   // no market value: no guess
    }

    func testTheConnectionStaysOnThePhone() {
        let vault = MemoryVault()
        let d = UserDefaults(suiteName: "shop-\(UUID().uuidString)")!
        let s = ShopStore(defaults: d, vault: vault)
        XCTAssertNil(s.credentials())
        s.save(host: " staging.overpricedcardboard.com ", keyId: "kid_1", secret: "sk_1")
        XCTAssertEqual(s.credentials()?.baseUrl, "https://staging.overpricedcardboard.com/api/v1")
        XCTAssertEqual(vault.read("secret"), "sk_1", "the secret is in the vault")
        XCTAssertFalse(d.dictionaryRepresentation().values.contains { "\($0)".contains("sk_1") }, "never in the settings")
        s.save(host: "staging.overpricedcardboard.com", keyId: "kid_2", secret: nil)
        XCTAssertEqual(s.credentials()?.secret, "sk_1", "an empty secret keeps the stored one")
        s.minPrice = 0.5
        XCTAssertEqual(s.minPrice, 0.5)
        s.lastBox = 1000
        XCTAssertNil(s.lastBox, "boxes are 1–999")
        XCTAssertTrue(s.showStatus, "the status line after a booking shows unless switched off")
        s.showStatus = false
        XCTAssertFalse(ShopStore(defaults: d, vault: vault).showStatus, "kept")
        s.clear()
        XCTAssertNil(s.credentials())
        XCTAssertFalse(s.hasSecret)
        XCTAssertEqual(s.minPrice, 0.5, "the minimum price is a setting, not part of the key")
    }

    // ------------------------------------------------------------------ Webshop Mode

    private struct Shop {
        let scanner: ScannerModel
        let web: WebshopModel
        let catalog: Catalog
        let store: ShopStore
        let orderFile: URL
    }

    private func shop(orderFile: URL? = nil) throws -> Shop {
        guard let pack = ParityTests.pack ?? Pack.bundledDir.flatMap({ try? Pack(dir: $0) }) else { throw XCTSkip("no data pack") }
        let cat = Catalog(db: pack.db)
        let sc = ScannerModel(engine: try ScannerEngine(pack: pack), catalog: cat)
        let store = ShopStore(defaults: UserDefaults(suiteName: "shop-\(UUID().uuidString)")!, vault: MemoryVault())
        store.save(host: "https://shop.test", keyId: "kid_test", secret: secret)
        let file = orderFile ?? FileManager.default.temporaryDirectory.appendingPathComponent("order-\(UUID().uuidString).json")
        let web = WebshopModel(scanner: sc, store: store, session: FakeShop.session(), sleep: { _ in }, orderFile: file)
        sc.settings.youtube = false
        sc.settings.webshop = true
        return Shop(scanner: sc, web: web, catalog: cat, store: store, orderFile: file)
    }

    private func candidate(_ cat: Catalog, _ id: String, _ lang: String = "en") throws -> Candidate {
        let c = try XCTUnwrap(cat.cardExact(id, lang) ?? cat.cardExact(id, "en"), "\(id) in the data pack")
        return Candidate(group: cat.groupOf(c.id), info: c, score: 0, reading: "")
    }

    private func until(_ what: String, _ cond: @MainActor () -> Bool) async throws {
        for _ in 0..<100 { if cond() { return }; try await Task.sleep(nanoseconds: 50_000_000) }
        XCTFail("waited in vain: \(what)")
    }

    /// a product of the shown card as the shop writes it
    static func productJSON(sku: String, ref: String, lang: String, condition: String, finish: String, cents: Int?, status: String = "active",
                            boxes: [(String, Int, Int)], images: Bool = true) -> String {
        let qty = boxes.reduce(0) { $0 + $1.1 }, held = boxes.reduce(0) { $0 + $1.2 }
        let b = boxes.map { #"{"storage_location":"\#($0.0)","quantity":\#($0.1),"reserved":\#($0.2)}"# }.joined(separator: ",")
        return """
            {"sku":"\(sku)","card_ref":"\(ref)","set_code":"x","card_number":"4/102","name_de":"Glurak","name_en":"Charizard","card_language":"\(lang)",
             "condition":"\(condition)","finish":"\(finish)","grading":null,"price":\(cents.map { #"{"amount":\#($0),"currency":"EUR"}"# } ?? "null"),
             "high_value":false,"status":"\(status)","url":null,
             "stock":{"quantity":\(qty),"reserved":\(held),"available":\(qty - held),"boxes":[\(b)]},
             "images":\(images ? #"[{"id":1,"role":"front","status":"ready","url":"https://shop.test/p.webp"}]"# : "[]"),"updated_at":null}
            """
    }

    /// Stock in: a card is looked up (nothing booked), the form is filled from the scan and the shop, the button books one copy; Undo takes it out.
    func testStockIn() async throws {
        let s = try shop()
        let c = try candidate(s.catalog, "base1-4")
        // the shown card's finish decides which product matches
        s.scanner.lookUp(c)
        let shown = try XCTUnwrap(s.scanner.shown)
        let finish = ShopTerms.finishOf(shown.printing)
        let sku = "base1-4-EN-NM-\(finish.prefix(1).uppercased())"
        var products = [Self.productJSON(sku: sku, ref: "base1-4", lang: "EN", condition: "NM", finish: finish, cents: 30000, boxes: [("014", 2, 0)])]
        FakeShop.handler = { r in
            let path = r.path.components(separatedBy: "?")[0]
            switch (r.method, path) {
            case ("GET", "/api/v1/master/cards"): return .init(status: 200, body: #"{"items":[{"card_ref":"base1-4","set_code":"base1","local_id":"4","image_url":"https://x/y.png"}]}"#)
            case ("GET", "/api/v1/products"): return .init(status: 200, body: #"{"items":[\#(products.joined(separator: ","))],"next_cursor":null}"#)
            case ("POST", "/api/v1/stock/intake"):
                let body = r.json
                let lang = body["card_language"] as? String ?? "", cond = body["condition"] as? String ?? ""
                let created = !(lang == "EN" && cond == "NM")
                let p = Self.productJSON(sku: "base1-4-\(lang)-\(cond)-\(finish.prefix(1).uppercased())", ref: "base1-4", lang: lang, condition: cond, finish: finish,
                                         cents: (body["price"] as? [String: Any])?["amount"] as? Int ?? 30000, boxes: [("014", created ? 1 : 3, 0)])
                return .init(status: created ? 201 : 200, body: #"{"created":\#(created),"product":\#(p),"warnings":[]}"#)
            case ("POST", "/api/v1/products/\(sku)/stock"):
                return .init(status: 200, body: #"{"quantity":2,"reserved":0,"available":2,"boxes":[{"storage_location":"014","quantity":2,"reserved":0}]}"#)
            default: return .init(status: 404, body: #"{"error":"not_found"}"#)
            }
        }
        // the lookup ran when the card came (the mode was switched on before)
        s.web.lookup()
        try await until("the shop's products") { !s.web.loading && s.web.products != nil }
        XCTAssertEqual(s.web.products?.map(\.sku), [sku])
        XCTAssertEqual(s.web.catalogPicture, true)
        XCTAssertEqual(s.web.form.language, "EN", "the scanned language")
        XCTAssertEqual(s.web.form.condition, "NM")
        XCTAssertEqual(s.web.match?.sku, sku)
        XCTAssertEqual(s.web.form.price, 300, "the shop's price of that product")
        XCTAssertTrue(FakeShop.requests("/stock/intake").isEmpty, "a scan books nothing")

        // no box: nothing is sent
        s.web.book()
        XCTAssertEqual(s.web.error, "Pick a storage box (1–999)")
        s.web.setBox(14)
        XCTAssertTrue(s.scanner.paused, "filling in holds the card")
        s.web.book()
        try await until("the booking") { !s.web.busy && s.web.booking != nil }
        let first = try XCTUnwrap(FakeShop.requests("/stock/intake").last).json
        XCTAssertEqual(first["card_ref"] as? String, "base1-4")
        XCTAssertEqual(first["card_language"] as? String, "EN")
        XCTAssertEqual(first["finish"] as? String, finish)
        XCTAssertEqual(first["storage_location"] as? String, "014")
        XCTAssertEqual(first["delta"] as? Int, 1)
        XCTAssertNil(first["price"], "the shop's price stays: no price sent for an existing product")
        XCTAssertNil(first["images"], "the shop's catalogue has a picture")
        XCTAssertTrue(s.web.booking?.text.hasPrefix("Added: 1 × ") == true, s.web.booking?.text ?? "")
        XCTAssertEqual(s.store.lastBox, 14, "the next card goes to the same box")

        // Undo: the copy comes out of the box again
        s.web.undoBooking()
        try await until("the undo") { s.web.booking?.undone == true }
        let undo = try XCTUnwrap(FakeShop.requests("/stock").last)
        XCTAssertEqual(undo.json["delta"] as? Int, -1)
        XCTAssertEqual(undo.json["storage_location"] as? String, "014")

        // another condition: a new product, with the typed price
        products = []
        s.web.lookup()
        try await until("the lookup") { !s.web.loading && s.web.products != nil }
        s.web.setCondition("EX")
        XCTAssertNil(s.web.match)
        s.web.setPrice(12.5)
        XCTAssertEqual(s.web.form.price, 12.5)
        s.web.setComment("Kleiner Knick oben links")
        s.web.book()
        try await until("the second booking") { !s.web.busy && s.web.booking?.undone == false }
        let second = try XCTUnwrap(FakeShop.requests("/stock/intake").last).json
        XCTAssertEqual(second["condition"] as? String, "EX")
        XCTAssertEqual((second["price"] as? [String: Any])?["amount"] as? Int, 1250)
        XCTAssertEqual(second["description_de"] as? String, "Kleiner Knick oben links", "a new product gets the comment")
        XCTAssertTrue(s.web.booking?.text.hasPrefix("New in the shop: ") == true)
        XCTAssertEqual(s.web.form.comment, "")
        XCTAssertNotNil(s.web.match, "the next copy goes into the product just made")
        XCTAssertEqual(s.web.form.price, 12.5, "at its price in the shop")
        XCTAssertFalse(s.web.form.priceByUser)
        XCTAssertEqual(s.store.lastCondition, "EX")
    }

    /// A booking that fails for lack of a connection is sent again with the same key; one the shop refuses gets a new key.
    func testARetrySendsTheSameBooking() async throws {
        let s = try shop()
        s.scanner.lookUp(try candidate(s.catalog, "base1-4"))
        FakeShop.handler = { r in
            r.path.hasPrefix("/api/v1/products") ? .init(status: 200, body: #"{"items":[],"next_cursor":null}"#) : .init(status: 200, body: #"{"items":[]}"#)
        }
        s.web.lookup()
        try await until("the lookup") { !s.web.loading && s.web.products != nil }
        s.web.setBox(3)
        // no answer four times: "offline", retryable
        let intake = "/stock/intake"
        FakeShop.enqueue(.init(status: 0, only: intake), .init(status: 0, only: intake), .init(status: 0, only: intake), .init(status: 0, only: intake))
        s.web.book()
        try await until("the failure") { !s.web.busy && s.web.error != nil }
        XCTAssertTrue(s.web.retryable)
        let finish = ShopTerms.finishOf(s.scanner.shown?.printing)
        FakeShop.enqueue(.init(status: 201, body: #"{"created":true,"product":\#(Self.productJSON(sku: "base1-4-EN-NM-X", ref: "base1-4", lang: "EN", condition: "NM", finish: finish, cents: nil, status: "draft", boxes: [("003", 1, 0)], images: false)),"warnings":["not_activated_no_price"]}"#, only: intake))
        s.web.retry()
        try await until("the retry") { !s.web.busy && s.web.booking != nil }
        let sent = FakeShop.requests("/stock/intake")
        XCTAssertEqual(sent.count, 5)
        XCTAssertEqual(Set(sent.compactMap { $0.header("Idempotency-Key") }).count, 1, "the same key every time")
        XCTAssertEqual(s.web.booking?.warnings, ["No price: it stays a draft (not visible in the shop)"])
        // the shop doesn't know the card: its names are asked for, then it is created with them
        FakeShop.enqueue(.init(status: 422, body: #"{"error":"unknown_card"}"#, only: intake))
        s.web.book()
        try await until("the unknown card") { !s.web.busy && s.web.unknownCard != nil }
        XCTAssertEqual(s.web.unknownCard?.en, s.scanner.shown?.version.name)
        FakeShop.enqueue(.init(status: 201, body: #"{"created":true,"product":\#(Self.productJSON(sku: "base1-4-EN-NM-X", ref: "base1-4", lang: "EN", condition: "NM", finish: finish, cents: 100, boxes: [("003", 2, 0)])),"warnings":[]}"#, only: intake))
        s.web.book(nameDe: "Glurak", nameEn: "Charizard")
        try await until("the booking with names") { !s.web.busy && s.web.unknownCard == nil }
        let named = try XCTUnwrap(FakeShop.requests("/stock/intake").last)
        XCTAssertEqual(named.json["name_de"] as? String, "Glurak")
        XCTAssertNotEqual(named.header("Idempotency-Key"), sent.last?.header("Idempotency-Key"), "a corrected booking gets a new key")
    }

    /// Sell: the product and the box with the most free copies are picked, copies go into the order (kept on the phone),
    /// Complete sale takes them out in one batch; a line the shop refuses stays; Undo puts the sold ones back.
    func testSell() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("order-\(UUID().uuidString).json")
        let s = try shop(orderFile: file)
        s.scanner.lookUp(try candidate(s.catalog, "base1-4"))
        let finish = ShopTerms.finishOf(s.scanner.shown?.printing)
        let a = Self.productJSON(sku: "base1-4-EN-NM-A", ref: "base1-4", lang: "EN", condition: "NM", finish: finish, cents: 30000,
                                 boxes: [("003", 1, 0), ("014", 2, 1)])
        let b = Self.productJSON(sku: "base1-4-DE-EX-B", ref: "base1-4", lang: "DE", condition: "EX", finish: finish, cents: nil, boxes: [("007", 1, 0)])
        FakeShop.handler = { r in
            r.path.hasPrefix("/api/v1/products") ? .init(status: 200, body: #"{"items":[\#(b),\#(a)],"next_cursor":null}"#) : .init(status: 200, body: #"{"items":[]}"#)
        }
        s.web.setSelling(true)
        s.web.lookup()
        try await until("the lookup") { !s.web.loading && s.web.products != nil }
        XCTAssertEqual(s.web.sellSku, "base1-4-EN-NM-A", "the scanned language first")
        XCTAssertEqual(s.web.sellBox, "014", "as many free copies as box 003, and the fuller one")
        s.web.setSellQty(5)
        XCTAssertEqual(s.web.sellQty, 2, "no more than the box holds")
        s.web.addToOrder()
        s.web.selectSellProduct("base1-4-DE-EX-B")
        s.web.addToOrder()
        XCTAssertEqual(s.web.order.map(\.quantity), [2, 1])
        XCTAssertEqual(s.web.orderTotal, 600, accuracy: 1e-9, "a card without a price isn't in the total")
        XCTAssertTrue(FakeShop.requests("/batch").isEmpty, "nothing leaves the stock yet")
        // the order is kept on the phone
        let again = WebshopModel(scanner: s.scanner, store: s.store, session: FakeShop.session(), sleep: { _ in }, orderFile: file)
        XCTAssertEqual(again.order.map(\.sku), ["base1-4-EN-NM-A", "base1-4-DE-EX-B"])
        XCTAssertTrue(again.selling)

        FakeShop.enqueue(.init(status: 200, body: """
            {"results":[{"status":200,"body":{"quantity":1,"reserved":1,"available":0,"boxes":[{"storage_location":"014","quantity":0,"reserved":0}]}},
                        {"status":409,"body":{"error":"insufficient_stock","message":"box 007 holds 0"}}]}
            """, only: "/batch"))
        s.web.completeSale()
        try await until("the sale") { !s.web.orderBusy && s.web.sale != nil }
        let batch = try XCTUnwrap(FakeShop.requests("/batch").first)
        let ops = try XCTUnwrap(batch.json["operations"] as? [[String: Any]])
        XCTAssertEqual(ops.compactMap { ($0["body"] as? [String: Any])?["delta"] as? Int }, [-2, -1])
        XCTAssertEqual(ops.compactMap { ($0["body"] as? [String: Any])?["reason"] as? String }.first, "sold in person (scanner app)")
        XCTAssertEqual(s.web.order.map(\.sku), ["base1-4-DE-EX-B"], "the refused line stays")
        XCTAssertEqual(s.web.order.first?.error, "The box doesn't hold that many copies")
        XCTAssertTrue(s.web.sale?.text.hasPrefix("Sold 2 cards") == true, s.web.sale?.text ?? "")

        FakeShop.enqueue(.init(status: 200, body: #"{"results":[{"status":200,"body":{"quantity":2,"reserved":1,"available":1,"boxes":[]}}]}"#, only: "/batch"))
        s.web.undoSale()
        try await until("the undo") { !s.web.orderBusy && s.web.sale?.undone == true }
        let undo = try XCTUnwrap(FakeShop.requests("/batch").last)
        let back = try XCTUnwrap((undo.json["operations"] as? [[String: Any]])?.first?["body"] as? [String: Any])
        XCTAssertEqual(back["delta"] as? Int, 2)
        XCTAssertEqual(back["storage_location"] as? String, "014")
        s.web.clearOrder()
        XCTAssertTrue(s.web.order.isEmpty)
    }

    /// The account's connected webshop fills in an empty host; a host typed in stays.
    func testTheAccountBringsTheHost() throws {
        let s = try shop()
        s.web.disconnect()
        XCTAssertEqual(s.web.host, "")
        s.web.applyAccount(has: false, host: "overpricedcardboard.com")
        XCTAssertEqual(s.web.host, "", "only with the feature")
        s.web.applyAccount(has: true, host: "overpricedcardboard.com")
        XCTAssertEqual(s.web.host, "overpricedcardboard.com")
        XCTAssertFalse(s.web.configured, "key id and secret are still typed in")
        s.web.applyAccount(has: true, host: "other.example")
        XCTAssertEqual(s.web.host, "overpricedcardboard.com")
        let st = AccountStatus.parse(["status": "active", "features": ["webshop": true], "webshop": ["host": "shop.example.com"]])
        XCTAssertEqual(st.webshopHost, "shop.example.com")
        XCTAssertTrue(st.has("webshop"))
        XCTAssertNil(AccountStatus.parse(["status": "active", "webshop": NSNull()]).webshopHost)
    }

    /// The product picture: a card's outline straightened at its shape; without one, the guide frame cut out.
    func testAProductPictureIsTheCardAtItsShape() throws {
        let w = 600, h = 800
        var d = Data(repeating: 40, count: w * h * 3)
        // a light card, 252 x 352, at (174, 224)
        for y in 224..<576 { for x in 174..<426 { let i = (y * w + x) * 3; d[i] = 230; d[i + 1] = 220; d[i + 2] = 200 } }
        let img = RGBImage(data: d, width: w, height: h)
        let quad = [CGPoint(x: 174, y: 224), CGPoint(x: 426, y: 224), CGPoint(x: 426, y: 576), CGPoint(x: 174, y: 576)]
        let prior = ScannerEngine.guidePrior(width: w, height: h, size: 0.7)
        let card = ProductPhoto.cardImage(img, quad: quad, prior: prior)
        XCTAssertEqual(card.height, 600, "at least 600 px high")
        XCTAssertEqual(Double(card.width) / Double(card.height), 63.0 / 88.0, accuracy: 0.01)
        XCTAssertGreaterThan(card.data[(300 * card.width + 200) * 3], 200, "the card itself, not the table")
        let framed = ProductPhoto.cardImage(img, quad: nil, prior: prior)
        XCTAssertEqual(Double(framed.width) / Double(framed.height), 63.0 / 88.0, accuracy: 0.01)
        let (jpeg, thumb) = try XCTUnwrap(ProductPhoto.encode(card))
        XCTAssertEqual(Array(jpeg.prefix(2)), [0xFF, 0xD8])
        XCTAssertEqual(thumb.size.height, 220)
        let marked = ProductPhoto.labeled(thumb, label: "Printed in Korean")
        XCTAssertEqual(marked.size, thumb.size)
    }

    // ------------------------------------------------------------------ the staging shop

    /**
     * Real calls against the staging shop (its own database; test bookings never touch real stock).
     * Runs only with the repository secrets POKESHOP_STAGING_KEY_ID / _SECRET (TEST_RUNNER_SHOP_KEY_ID / _SECRET
     * in CI); skipped otherwise. Books one copy into test box 998, sells it in a batch, undoes the sale, takes it out again.
     */
    func testStagingRoundTrip() async throws {
        let env = ProcessInfo.processInfo.environment
        let kid = env["SHOP_KEY_ID"] ?? "", sk = env["SHOP_SECRET"] ?? ""
        guard !kid.isEmpty, !sk.isEmpty else { throw XCTSkip("no staging key (repository secrets POKESHOP_STAGING_KEY_ID / _SECRET)") }
        let c = ShopClient(ShopCredentials(baseUrl: "https://staging.overpricedcardboard.com/api/v1", keyId: kid, secret: sk))
        let h: ShopClient.Health
        do { h = try await c.health() } catch let e as ShopError {
            // the staging shop down, or this key not (or no longer) valid there: nothing to test against, not the app's fault
            if e.retryable || e.status == 401 || e.status == 403 { print("ShopTest: skipped, \(e.code) \(e.status)"); throw XCTSkip("staging shop: \(e.friendly)") }
            throw e
        }
        XCTAssertEqual(h.keyId, kid)
        print("ShopTest: health ok, shop time \(h.serverTime ?? "?")")
        let master = try await c.masterCards(cardRef: "sv03.5-199")
        print("ShopTest: master data \(master.map { "\($0.cardRef) \($0.name ?? "") \($0.setName ?? "")" })")
        let before = try await c.productsOfCard("sv03.5-199")
        print("ShopTest: products \(before.map { "\($0.sku) \($0.stock.quantity) \($0.status)" })")

        // one copy in, the same request again (replayed, not booked twice)
        let w = PendingWrite.json("POST", "/stock/intake", ShopClient.intakeBody(cardRef: "sv03.5-199", language: "DE", condition: "NM", finish: "holo",
                                                                                delta: 1, box: "998", price: .ofEuros(129)))
        let first = try await c.intake(w)
        let again = try await c.intake(w)
        print("ShopTest: intake created=\(first.created) \(first.product.sku) stock \(first.product.stock.quantity) warnings \(first.warnings); again replayed=\(again.replayed)")
        XCTAssertTrue(again.replayed, "same key: replayed")
        XCTAssertEqual(first.product.stock.quantity, again.product.stock.quantity)

        // a customer's order: one batch, each line on its own (one copy sold, one line asking too much refused)
        let sku = first.product.sku
        let sale = PendingWrite.json("POST", "/batch", ShopClient.stockBatchBody([(sku, "998", -1), (sku, "998", -1000)], reason: "scanner app test: sold in person"))
        let sold = try await c.batch(sale)
        let soldText = sold.map { r -> String in
            let what = r.error?.code ?? (r.body?["quantity"] as? Int).map(String.init) ?? ""
            return "\(r.status) \(what)"
        }
        print("ShopTest: sale \(soldText)")
        XCTAssertEqual(sold.count, 2)
        XCTAssertTrue(sold[0].ok)
        XCTAssertEqual(sold[0].body?["quantity"] as? Int, first.product.stock.quantity - 1)
        XCTAssertEqual(sold[1].error?.code, "insufficient_stock")
        let replay = try await c.batch(sale)
        XCTAssertEqual(replay.map(\.status), sold.map(\.status), "the same batch again changes nothing")
        let now = try await c.product(sku)
        XCTAssertEqual(now.stock.quantity, first.product.stock.quantity - 1)
        // the sale undone: back into the box; then the test copy out again
        let back = try await c.batch(PendingWrite.json("POST", "/batch", ShopClient.stockBatchBody([(sku, "998", 1)], reason: "scanner app test: sale undone")))
        XCTAssertTrue(back.first?.ok == true)
        let stock = try await c.stock(PendingWrite.json("POST", "/products/\(sku)/stock", ["delta": -1, "storage_location": "998", "reason": "scanner app test"]))
        XCTAssertEqual(stock.quantity, first.product.stock.quantity - 1)
        do {
            _ = try await c.stock(PendingWrite.json("POST", "/products/\(sku)/stock", ["delta": -1000, "storage_location": "998", "reason": "scanner app test"]))
            XCTFail("no error")
        } catch let e as ShopError {
            XCTAssertEqual(e.code, "insufficient_stock")
            print("ShopTest: too many out of a box: \(e.friendly)")
        }
    }
}
