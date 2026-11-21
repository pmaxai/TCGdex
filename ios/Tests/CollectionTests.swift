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

    func testACopyIsEditedAndDeleted() async throws {
        StandIn.reset()
        StandIn.cards = ["base1-4": ("Charizard", "Base Set", "4/102", 41200)]
        let email = "editor@test.overpricedcardboard.com"
        let a = try await signedIn(email)
        a.addToCollection(cardId: "base1-4", language: "EN", finish: "holo", condition: "NM", label: "Charizard")
        try await until("sent") { a.pending.isEmpty && a.collectionEdits == 1 }
        await a.loadItems()
        let item = try XCTUnwrap(a.items?.first)
        var d = CopyDetails(item)
        d.quantity = 3; d.condition = "EX"; d.language = "DE"; d.graded = true; d.company = "PSA"; d.grade = "9"
        d.purchaseCents = 25000; d.note = "  binder page 3 "
        let changes = d.changes(from: CopyDetails(item))
        log("changes: \(changes)")
        XCTAssertEqual(Set(changes.keys), ["quantity", "condition", "language", "grading", "purchase_price_cents", "note"], "only what changed")
        let saved = await a.updateItem(item, changes)
        XCTAssertNil(saved)
        let edited = try XCTUnwrap(a.items?.first)
        XCTAssertEqual(edited.quantity, 3)
        XCTAssertEqual(edited.condition, "EX")
        XCTAssertEqual(edited.language, "DE")
        XCTAssertEqual(edited.graded, "PSA 9")
        XCTAssertEqual(edited.purchaseCents, 25000)
        XCTAssertEqual(edited.note, "binder page 3", "trimmed")
        XCTAssertEqual(edited.finish, "holo", "unchanged")
        StandIn.lock.lock()
        let server = StandIn.accounts[email]?.collection.first ?? [:]
        StandIn.lock.unlock()
        XCTAssertEqual(server["condition"] as? String, "EX")
        XCTAssertEqual((server["grading"] as? [String: Any])?["company"] as? String, "PSA")

        // cleared again: not graded, no price, no note → null
        var back = CopyDetails(edited)
        back.graded = false; back.purchaseCents = nil; back.note = ""
        let clear = back.changes(from: CopyDetails(edited))
        XCTAssertTrue(clear["grading"] is NSNull)
        XCTAssertTrue(clear["purchase_price_cents"] is NSNull)
        XCTAssertTrue(clear["note"] is NSNull)
        XCTAssertEqual(clear.count, 3)
        let cleared = await a.updateItem(edited, clear)
        XCTAssertNil(cleared)
        XCTAssertNil(a.items?.first?.graded)
        XCTAssertNil(a.items?.first?.purchaseCents)

        let last = try XCTUnwrap(a.items?.first)
        let deleted = await a.deleteItem(last)
        XCTAssertNil(deleted)
        XCTAssertEqual(a.items?.count, 0)
        StandIn.lock.lock()
        XCTAssertEqual(StandIn.accounts[email]?.collection.count, 0, "gone on the server")
        StandIn.lock.unlock()
        log("edited and deleted")
    }

    func testTheCollectionIsSharedAndMadePrivateAgain() async throws {
        StandIn.reset()
        let email = "sharer@test.overpricedcardboard.com"
        let a = try await signedIn(email)
        let first = await a.loadShare()
        XCTAssertNil(first)
        XCTAssertNil(a.publicLink, "private to start")
        let made = await a.makePublic()
        XCTAssertNil(made)
        let link = try XCTUnwrap(a.publicLink)
        XCTAssertTrue(link.url.contains("/c/"))
        let again = await a.makePublic()
        XCTAssertNil(again)
        XCTAssertEqual(a.publicLink, link, "the same link again, not a second one")
        XCTAssertEqual(StandIn.calls("/collection/share").last?.body["show_prices"] as? Bool, true, "with prices")
        // a public link of one binder, made on the website
        StandIn.lock.lock()
        StandIn.accounts[email]?.otherPublic = 1
        StandIn.lock.unlock()
        _ = await a.loadShare()
        XCTAssertEqual(a.otherPublicLinks, 1)
        let off = await a.makePrivate()
        XCTAssertNil(off)
        XCTAssertNil(a.publicLink)
        XCTAssertEqual(a.otherPublicLinks, 0)
        StandIn.lock.lock()
        XCTAssertNil(StandIn.accounts[email]?.publicShare, "switched off on the server")
        StandIn.lock.unlock()
        log("shared \(link.url), then private")
    }

    func testSharingBeforeTheWebsiteHasIt() async throws {
        StandIn.reset()
        let a = try await signedIn("early@test.overpricedcardboard.com")
        StandIn.shareMissing = true
        let e = await a.loadShare()
        XCTAssertEqual(e, PublicLink.notYet)
        let made = await a.makePublic()
        XCTAssertEqual(made, PublicLink.notYet)
    }

    func testShopLanguagesMapBackToThePack() {
        XCTAssertEqual(ShopTerms.appLang("DE"), "de")
        XCTAssertEqual(ShopTerms.appLang("ZH-HANT"), "zh-tw")
        XCTAssertEqual(ShopTerms.appLang(nil), "en")
        XCTAssertEqual(ShopTerms.appLang(ShopTerms.languageOf("ja")), "ja")
    }
}
