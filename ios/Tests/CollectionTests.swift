import XCTest
@testable import OverpricedCardboard

/// The collection page's data: the whole collection page by page, and a missing card found elsewhere.
@MainActor
final class CollectionTests: XCTestCase {
    private func log(_ s: String) { print("CollectionTest: \(s)") }

    private func account() -> AccountModel {
        let suite = "collection-\(UUID().uuidString)"
        let a = AccountModel(store: AccountStore(defaults: UserDefaults(suiteName: suite)!,
                                                 dir: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), vault: MemoryVault()),
                             home: "https://standin.test")
        a.session = StandIn.session()
        return a
    }

    private func until(_ what: String, _ cond: @MainActor () -> Bool) async throws {
        for _ in 0..<150 {
            if cond() { return }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTFail("timed out waiting for: \(what)")
        throw NSError(domain: "CollectionTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "timed out waiting for: \(what)"])
    }

    private func signedIn(_ email: String) async throws -> AccountModel {
        let a = account()
        a.signInApple(identityToken: "test_apple:\(email)", authorizationCode: nil)
        try await until("signed in") { a.status != nil && !a.busy }
        // a running Collector plan (bought on the website)
        StandIn.lock.lock()
        StandIn.accounts[email]?.active = true
        StandIn.accounts[email]?.plan = "collector"
        StandIn.accounts[email]?.basePlan = "monthly"
        StandIn.accounts[email]?.source = "web"
        StandIn.lock.unlock()
        a.check(force: true)
        try await until("the plan's features") { a.has("collection") && a.has("wanted") && !a.busy }
        return a
    }

    func testTheCollectionLoadsEveryPageNewestFirst() async throws {
        StandIn.reset()
        StandIn.cards = ["sv08-059": ("Pikachu-ex", "Surging Sparks", "059/191", 1890), "base1-4": ("Charizard", "Base Set", "4/102", 41200)]
        let a = try await signedIn("collector@test.overpricedcardboard.com")
        a.addToCollection(cardId: "base1-4", language: "EN", finish: "holo", condition: "EX", label: "Charizard")
        a.addToCollection(cardId: "sv08-059", language: "DE", finish: "normal", condition: "NM", quantity: 2, label: "Pikachu-ex")
        a.addToCollection(cardId: "sv08-059", language: "EN", finish: "normal", condition: "NM", label: "Pikachu-ex")
        try await until("3 copies sent") { a.pending.isEmpty && a.collectionEdits == 3 }
        StandIn.pageSize = 2
        await a.loadItems()
        let items = try XCTUnwrap(a.items)
        log("loaded \(items.count) items: \(items.map { "\($0.quantity) × \($0.name) \($0.language)" })")
        XCTAssertEqual(items.count, 3, "both pages")
        XCTAssertEqual(items.first?.name, "Pikachu-ex", "newest first")
        XCTAssertEqual(items.first?.language, "EN")
        XCTAssertEqual(items.last?.name, "Charizard")
        XCTAssertEqual(items.reduce(0) { $0 + $1.quantity }, 4)
        XCTAssertEqual(items.compactMap(\.valueCents).reduce(0, +), 1890 * 2 + 41200)
        let pages = StandIn.calls.filter { $0.method == "GET" && $0.path.hasSuffix("/collection/items") }
        XCTAssertEqual(pages.count, 2, "two pages asked for")
        XCTAssertEqual(pages.last?.body["cursor"] as? String, "2")
        XCTAssertTrue(items.first?.searchText.contains("surging") == true)
    }

    func testAMissingCardFoundElsewhereGoesIntoTheCollection() async throws {
        StandIn.reset()
        let a = try await signedIn("finder@test.overpricedcardboard.com")
        a.toggleWanted(cardId: "base1-4", language: "EN", finish: nil, label: "Charizard")
        try await until("on the missing cards") { a.pending.isEmpty && a.wanted.first?.id != nil }
        let w = try XCTUnwrap(a.wanted.first)
        a.foundElsewhere(w, condition: "GD", quantity: 1, purchaseCents: 25000, defaultLanguage: "DE", defaultFinish: "holo", label: "Charizard")
        try await until("in the collection, off the list") { a.pending.isEmpty && a.wanted.isEmpty && a.collectionEdits == 1 }
        StandIn.lock.lock()
        let coll = StandIn.accounts["finder@test.overpricedcardboard.com"]?.collection ?? []
        let left = StandIn.accounts["finder@test.overpricedcardboard.com"]?.wanted ?? []
        StandIn.lock.unlock()
        log("collection \(coll.map { "\($0["card_id"] ?? "") \($0["language"] ?? "") \($0["finish"] ?? "") \($0["condition"] ?? "")" }), missing left \(left.count)")
        XCTAssertEqual(coll.count, 1)
        XCTAssertEqual(coll.first?["language"] as? String, "EN", "the missing card's language")
        XCTAssertEqual(coll.first?["finish"] as? String, "holo", "no finish on the missing card: the card's own")
        XCTAssertEqual(coll.first?["condition"] as? String, "GD")
        XCTAssertEqual((coll.first?["purchase_price_cents"] as? NSNumber)?.intValue, 25000)
        XCTAssertTrue(left.isEmpty, "the server's missing cards are empty")
    }

    func testShopLanguagesMapBackToThePack() {
        XCTAssertEqual(ShopTerms.appLang("DE"), "de")
        XCTAssertEqual(ShopTerms.appLang("ZH-HANT"), "zh-tw")
        XCTAssertEqual(ShopTerms.appLang(nil), "en")
        XCTAssertEqual(ShopTerms.appLang(ShopTerms.languageOf("ja")), "ja")
    }
}
