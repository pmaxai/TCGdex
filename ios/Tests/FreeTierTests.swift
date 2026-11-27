import XCTest
@testable import OverpricedCardboard

/// The free version: 15 reveals at once, then one every 15 seconds; a collection of 50 different cards on the phone,
/// moved into the account's collection once a plan with a collection runs, and back to Free on sign-out.
@MainActor
final class FreeTierTests: XCTestCase {
    private func log(_ s: String) { print("FreeTest: \(s)") }

    private func store() -> AccountStore {
        AccountStore(defaults: UserDefaults(suiteName: "free-\(UUID().uuidString)")!,
                     dir: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), vault: MemoryVault())
    }

    private func account(_ s: AccountStore? = nil) -> AccountModel {
        let a = AccountModel(store: s ?? store(), home: "https://standin.test")
        a.session = StandIn.session()
        return a
    }

    private func until(_ what: String, _ cond: @MainActor () -> Bool) async throws {
        for _ in 0..<150 {
            if cond() { return }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTFail("timed out waiting for: \(what)")
        throw NSError(domain: "FreeTierTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "timed out waiting for: \(what)"])
    }

    /// 15 reveals at once, then one more every 15 seconds (up to 15 again); a card revealed stays revealed for an hour.
    func testABurstOfFifteenThenOneEveryFifteenSeconds() {
        let st = store()
        let a = account(st)
        XCTAssertTrue(a.isFree, "no account: the free version")
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        var t = t0
        a.now = { t }
        XCTAssertEqual(a.revealsLeft, 15)
        XCTAssertNil(a.nextRevealAt, "all there: nothing to wait for")
        XCTAssertEqual(a.revealsText, "15 free reveals left")
        for i in 0..<7 { XCTAssertTrue(a.reveal("card-\(i)"), "reveal \(i + 1)") }
        t = t0.addingTimeInterval(3)
        XCTAssertEqual(a.revealsLeft, 8)
        XCTAssertEqual(a.nextRevealAt, t0.addingTimeInterval(15), "the refill counts from the first one")
        XCTAssertEqual(a.revealsText, "8 free reveals left · next in 0:12")
        for i in 7..<15 { XCTAssertTrue(a.reveal("card-\(i)"), "reveal \(i + 1)") }
        XCTAssertEqual(a.revealsLeft, 0)
        XCTAssertFalse(a.reveal("card-15"), "the 16th right away")
        XCTAssertFalse(a.revealed("card-15"))
        XCTAssertTrue(a.reveal("card-3"), "a card revealed stays revealed (free)")
        XCTAssertEqual(a.revealsText, "No free reveals left · next in 0:12")
        log("after 15: \(a.revealsText)")

        // kept on the phone: the same after a restart
        let again = account(st)
        again.now = { t }
        XCTAssertEqual(again.revealsLeft, 0)
        XCTAssertTrue(again.revealed("card-0"))

        // one every 15 seconds; a reveal keeps the refill's clock where it is
        t = t0.addingTimeInterval(14.5)
        XCTAssertEqual(a.revealsLeft, 0)
        XCTAssertEqual(a.revealsText, "No free reveals left · next in 0:01")
        t = t0.addingTimeInterval(15)
        XCTAssertEqual(a.revealsLeft, 1)
        XCTAssertEqual(a.revealsText, "1 free reveal left · next in 0:15")
        t = t0.addingTimeInterval(20)
        XCTAssertTrue(a.reveal("card-15"))
        XCTAssertEqual(a.revealsLeft, 0)
        XCTAssertEqual(a.nextRevealAt, t0.addingTimeInterval(30), "not 15 seconds after this reveal")
        t = t0.addingTimeInterval(61)
        XCTAssertEqual(a.revealsLeft, 3)

        // all 15 back after enough time, no more than 15
        t = t0.addingTimeInterval(15 + 15 * 20)
        XCTAssertEqual(a.revealsLeft, 15)
        XCTAssertNil(a.nextRevealAt)
        // the clock set back: nothing is lost, nothing gained
        XCTAssertTrue(a.reveal("card-16"))
        t = t.addingTimeInterval(-600)
        XCTAssertEqual(a.revealsLeft, 14)
        XCTAssertEqual(a.nextRevealAt, t.addingTimeInterval(15))

        // a card revealed stays revealed for an hour
        t = t0.addingTimeInterval(3601)
        XCTAssertFalse(a.revealed("card-0"))
        XCTAssertEqual(AccountModel.countdown(75), "1:15")
        XCTAssertEqual(AccountModel.countdown(0.2), "0:01")
    }

    /// Before a reveal a price range; revealed, Cardmarket's trend only (the range falls back to TCGplayer in euros).
    func testThePriceRangeAndTheTrend() {
        XCTAssertEqual(FreePrice.range(0), "0–1 €")
        XCTAssertEqual(FreePrice.range(0.99), "0–1 €")
        XCTAssertEqual(FreePrice.range(1), "1–20 €")
        XCTAssertEqual(FreePrice.range(19.99), "1–20 €")
        XCTAssertEqual(FreePrice.range(20), "20 €+")
        XCTAssertEqual(FreePrice.range(2500), "20 €+")
        let p = Printing(kind: .holo, eur: 12.5, eur30: 11, eurLow: 9, usd: 30, usdEur: 27.5)
        XCTAssertEqual(FreePrice.trend(p), 12.5)
        XCTAssertEqual(FreePrice.rangeValue(p), 12.5)
        XCTAssertEqual(FreePrice.chip(p), "Holo " + Money.eur(12.5))
        let onlyUS = Printing(kind: .reverse, eur: nil, eur30: nil, eurLow: nil, usd: 30, cmTrend: nil, tcgMarket: 30, usdPerEur: 1.2, sourced: true)
        XCTAssertNil(FreePrice.trend(onlyUS), "no Cardmarket trend: none shown")
        XCTAssertEqual(FreePrice.rangeValue(onlyUS), 25, "the range from TCGplayer in euros")
        XCTAssertEqual(FreePrice.chip(onlyUS), "Reverse holo")
        XCTAssertNil(FreePrice.rangeValue(nil))
    }

    func testTheLocalCollectionHoldsFiftyDifferentCards() {
        let st = store()
        let a = account(st)
        for i in 0..<50 {
            XCTAssertTrue(a.addLocally(cardId: "card-\(i)", language: "EN", finish: "normal", condition: "NM", label: "Card \(i)"))
        }
        XCTAssertEqual(a.localCardCount, 50)
        XCTAssertFalse(a.addLocally(cardId: "card-50", language: "EN", finish: "normal", condition: "NM", label: "Card 50"), "a 51st card")
        XCTAssertTrue(a.addLocally(cardId: "card-7", language: "EN", finish: "normal", condition: "NM", label: "Card 7"), "another copy of one in it")
        XCTAssertTrue(a.addLocally(cardId: "card-7", language: "DE", finish: "holo", condition: "EX", quantity: 2, purchaseCents: 450, label: "Card 7"))
        XCTAssertEqual(a.localCardCount, 50, "copies don't count")
        XCTAssertEqual(a.localLines("card-7").map(\.quantity).sorted(), [2, 2], "NM merged into one line; the German one on its own")
        XCTAssertEqual(a.localItems.first?.cardId, "card-7", "newest first")
        a.removeLocally(a.localItems.first { $0.cardId == "card-0" }!.id)
        XCTAssertEqual(a.localCardCount, 49)
        XCTAssertTrue(a.addLocally(cardId: "card-50", language: "EN", finish: "normal", condition: "NM", label: "Card 50"), "room again")
        let again = account(st)
        XCTAssertEqual(again.localItems, a.localItems, "kept on the phone")
    }

    func testACopyOnThePhoneIsEdited() {
        let a = account()
        a.addLocally(cardId: "base1-4", language: "EN", finish: "holo", condition: "NM", label: "Charizard")
        let item = a.localItems[0]
        var d = CopyDetails(item)
        d.quantity = 2; d.condition = "LP"; d.language = "DE"; d.graded = true; d.company = "CGC"; d.grade = "8.5"
        d.purchaseCents = 9900; d.note = "a gift "
        a.updateLocally(item.id, d)
        let e = a.localItems[0]
        XCTAssertEqual(e.id, item.id)
        XCTAssertEqual(e.quantity, 2)
        XCTAssertEqual(e.condition, "LP")
        XCTAssertEqual(e.language, "DE")
        XCTAssertEqual(e.gradingCompany, "CGC")
        XCTAssertEqual(e.gradingGrade, "8.5")
        XCTAssertEqual(e.purchaseCents, 9900)
        XCTAssertEqual(e.note, "a gift")
        XCTAssertEqual(a.localCardCount, 1)
    }

    func testTheLocalCardsMoveIntoTheAccountWithAPlan() async throws {
        StandIn.reset()
        let email = "free-to-collector@test.overpricedcardboard.com"
        let a = account()
        a.addLocally(cardId: "base1-4", language: "EN", finish: "holo", condition: "GD", purchaseCents: 25000, note: "from the flea market", label: "Charizard")
        a.addLocally(cardId: "sv08-059", language: "DE", finish: "normal", condition: "NM", quantity: 2, label: "Pikachu-ex")
        // graded on the phone: the grading goes along
        if let p = a.localItems.first(where: { $0.cardId == "sv08-059" }) {
            var d = CopyDetails(p); d.graded = true; d.company = "PSA"; d.grade = "10"
            a.updateLocally(p.id, d)
        }
        a.signInApple(identityToken: "test_apple:\(email)", authorizationCode: nil)
        try await until("signed in") { a.status != nil && !a.busy }
        XCTAssertTrue(a.isFree, "signed in without a plan: still the free version")
        XCTAssertEqual(a.localItems.count, 2, "nothing moved without a plan")

        StandIn.lock.lock()
        StandIn.accounts[email]?.active = true
        StandIn.accounts[email]?.plan = "collector"
        StandIn.accounts[email]?.basePlan = "monthly"
        StandIn.accounts[email]?.source = "web"
        StandIn.lock.unlock()
        a.check(force: true)
        try await until("moved") { a.has("collection") && a.localItems.isEmpty && a.pending.isEmpty && !a.busy }
        StandIn.lock.lock()
        let coll = StandIn.accounts[email]?.collection ?? []
        StandIn.lock.unlock()
        log("moved: \(coll.map { "\($0["quantity"] ?? "") × \($0["card_id"] ?? "") \($0["language"] ?? "") \($0["condition"] ?? "")" }); message \(a.message ?? "–")")
        XCTAssertEqual(coll.count, 2)
        let charizard = try XCTUnwrap(coll.first { $0["card_id"] as? String == "base1-4" })
        XCTAssertEqual(charizard["finish"] as? String, "holo")
        XCTAssertEqual(charizard["condition"] as? String, "GD")
        XCTAssertEqual((charizard["purchase_price_cents"] as? NSNumber)?.intValue, 25000)
        XCTAssertEqual(charizard["note"] as? String, "from the flea market")
        XCTAssertEqual((coll.first { $0["card_id"] as? String == "sv08-059" }?["quantity"] as? NSNumber)?.intValue, 2)
        XCTAssertEqual((coll.first { $0["card_id"] as? String == "sv08-059" }?["grading"] as? [String: Any])?["grade"] as? String, "10")
        XCTAssertEqual(a.message, "The 2 cards from this phone are now in your account's collection.")
        XCTAssertFalse(a.isFree)

        // signed out: back to the free version
        a.disconnect()
        try await until("signed out") { a.status == nil && !a.busy }
        XCTAssertTrue(a.isFree)
        XCTAssertTrue(a.revealed("anything") == false)
    }
}
