import StoreKit
import StoreKitTest
import XCTest
@testable import OverpricedCardboard

/**
 * Signing in, buying in the App Store (StoreKitTest with OverpricedCardboard.storekit), reporting the
 * transaction to the server, Restore purchases, creator codes, deleting the account, and the
 * collection's offline queue, against a stand-in of the shop (StandIn). Lines starting with
 * "StoreTest:" are what the CI log shows.
 */
@MainActor
final class StoreTests: XCTestCase {
    private var storeKit: SKTestSession?
    private var defaults: UserDefaults!
    private var suite = ""

    override func setUp() async throws {
        StandIn.reset()
        suite = "store-test-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() async throws {
        storeKit?.clearTransactions()
        storeKit = nil
        defaults.removePersistentDomain(forName: suite)
    }

    private func log(_ s: String) { print("StoreTest: " + s) }

    private func account() -> AccountModel {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let a = AccountModel(store: AccountStore(defaults: defaults, dir: dir, vault: MemoryVault()), home: "https://standin.test")
        a.session = StandIn.session()
        return a
    }

    private func startStoreKit() throws -> SKTestSession {
        let url = try XCTUnwrap(Bundle(for: StoreTests.self).url(forResource: "OverpricedCardboard", withExtension: "storekit"), "storekit file missing")
        let s = try SKTestSession(contentsOf: url)
        s.disableDialogs = true
        s.clearTransactions()
        s.resetToDefaultState()
        storeKit = s
        return s
    }

    /// waits (up to 15 s) until the condition holds
    private func until(_ what: String, _ cond: @MainActor () -> Bool) async throws {
        for _ in 0..<150 {
            if cond() { return }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTFail("timed out waiting for: \(what)")
        throw NSError(domain: "StoreTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "timed out waiting for: \(what)"])
    }

    private func signedIn(_ email: String = "anna@test.overpricedcardboard.com") async throws -> AccountModel {
        let a = account()
        a.signInApple(identityToken: "test_apple:\(email)", authorizationCode: "c0de")
        try await until("signed in") { a.status != nil && !a.busy }
        return a
    }

    func testSignInWithAppleSendsTheTokensAndNeedsAPlan() async throws {
        let a = try await signedIn()
        let call = try XCTUnwrap(StandIn.calls("/login/apple").first)
        XCTAssertEqual(call.body["platform"] as? String, "ios")
        XCTAssertEqual(call.body["authorization_code"] as? String, "c0de")
        XCTAssertNotNil(call.body["device_id"] as? String)
        XCTAssertEqual(a.gate, .ended, "a new account without a plan sees the plans")
        XCTAssertTrue(a.status?.none == true)
        XCTAssertNotNil(a.status?.storeRef)
        log("signed in: \(a.status?.email ?? "-") store_ref \(a.status?.storeRef ?? "-") gate \(a.gate)")
    }

    func testBuyingAPlanReportsTheTransactionAndOpensTheApp() async throws {
        let sk = try startStoreKit()
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: StoreKitBilling())
        await store.loadOffers()
        log("on sale: \(store.onSale.map { "\($0.name) \(store.price($0, "monthly")?.displayPrice ?? "-") / \(store.price($0, "yearly")?.displayPrice ?? "-")" })")
        XCTAssertEqual(store.onSale.map(\.plan), ["collector", "creator"])
        let collector = try XCTUnwrap(store.onSale.first)
        XCTAssertEqual(store.price(collector, "monthly")?.period, "monthly")
        XCTAssertEqual(store.price(collector, "yearly")?.period, "yearly")
        XCTAssertEqual(PlanCard.text(try XCTUnwrap(store.price(collector, "monthly")), "month").hasSuffix(" / month"), true)

        store.buy(collector, "monthly")
        try await until("the account is active") { a.gate == .open }
        let report = try XCTUnwrap(StandIn.calls("/store/apple").last)
        let jws = try XCTUnwrap(report.body["signed_transaction"] as? String)
        let payload = try XCTUnwrap(StandIn.payload(jws))
        XCTAssertEqual((payload["appAccountToken"] as? String)?.lowercased(), a.status?.storeRef, "bought with the account's store_ref")
        XCTAssertEqual(payload["productId"] as? String, "oc_collector_monthly")
        XCTAssertEqual(a.status?.source, "app_store")
        XCTAssertEqual(store.runningPlan?.plan, "collector")
        XCTAssertNil(store.error)
        log("bought: \(payload["productId"] ?? "-") → \(a.status?.planName ?? "-") · \(a.status?.stateText ?? "-")")
        // finished after the server took it
        var unfinished = 0
        for await _ in Transaction.unfinished { unfinished += 1 }
        XCTAssertEqual(unfinished, 0, "the transaction wasn't finished")
        _ = sk

        // an extra device: the product for one extra device of the plan's period
        XCTAssertEqual(store.devices?.have, 0)
        XCTAssertEqual(store.devices?.max, 2)
        store.changeDevices(by: 1)
        try await until("one extra device") { a.status?.devicesInStore == 1 }
        XCTAssertEqual(StandIn.payload(StandIn.calls("/store/apple").last?.body["signed_transaction"] as? String ?? "")?["productId"] as? String,
                       "oc_collector_devices_1_monthly")
    }

    func testRestorePurchasesOnANewPhone() async throws {
        _ = try startStoreKit()
        let first = try await signedIn()
        let store = StoreModel(account: first, billing: StoreKitBilling())
        await store.loadOffers()
        store.buy(try XCTUnwrap(store.onSale.first), "yearly")
        try await until("bought") { first.gate == .open }

        // the same Apple account on a "new phone", and a server that lost the purchase: Restore purchases sends it again
        let reports = StandIn.calls("/store/apple").count
        StandIn.lock.lock()
        StandIn.accounts.values.forEach { $0.active = false; $0.plan = nil; $0.basePlan = nil }
        StandIn.lock.unlock()
        let second = try await signedIn()
        XCTAssertEqual(second.gate, .ended)
        let store2 = StoreModel(account: second, billing: StoreKitBilling())
        store2.restore()
        try await until("restored") { !store2.busy && StandIn.calls("/store/apple").count > reports }
        XCTAssertEqual(second.status?.basePlan, "yearly")
        XCTAssertEqual(second.gate, .open)
        log("restore: \(store2.note ?? store2.error ?? "-")")
    }

    func testAReportThatDoesntGetThroughIsSentAgain() async throws {
        _ = try startStoreKit()
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: StoreKitBilling())
        await store.loadOffers()
        StandIn.lock.lock(); StandIn.failNextReport = true; StandIn.lock.unlock()
        store.buy(try XCTUnwrap(store.onSale.first), "monthly")
        try await until("kept") { store.waiting == 1 && !store.busy }
        XCTAssertNotEqual(a.gate, .open)
        await store.sendWaiting()
        XCTAssertEqual(store.waiting, 0)
        XCTAssertEqual(a.gate, .open)
    }

    func testCreatorCode() async throws {
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: StoreKitBilling())
        store.applyCode("nope")
        try await until("refused") { !store.busy }
        XCTAssertEqual(store.error, "There's no creator code \"nope\".")
        store.applyCode("misty")
        try await until("applied") { !store.busy && store.affiliate != nil }
        XCTAssertEqual(store.affiliate?.discountText, "20 % off for 3 months")
        XCTAssertEqual(store.affiliate?.appleOfferCode, "MISTY20")
        XCTAssertEqual(store.note, "Creator code MISTY: 20 % off for 3 months.")
        XCTAssertEqual(Affiliate.fromLink(URL(string: "https://overpricedcardboard.com/r/MISTY")!), "MISTY")
        XCTAssertNil(Affiliate.fromLink(URL(string: "https://example.com/r/MISTY")!))
    }

    func testDeletingTheAccount() async throws {
        let a = try await signedIn()
        a.deleteAccount()
        try await until("deleted") { a.gate == .connect && !a.busy }
        XCTAssertEqual(a.notice, "The account was deleted.")
        XCTAssertNil(a.store.token)
        XCTAssertEqual(StandIn.calls("/account/delete").first?.body["confirm"] as? Bool, true)
    }

    func testEmailCodeSignIn() async throws {
        let a = account()
        a.emailCode("ben@test.overpricedcardboard.com")
        try await until("code sent") { a.emailSentTo != nil && !a.busy }
        a.emailVerify("123456")
        try await until("refused") { !a.busy && a.error != nil }
        XCTAssertEqual(a.error, "That code isn't right – 4 tries left.")
        a.emailVerify("000 000")
        try await until("signed in") { a.status != nil && !a.busy }
        XCTAssertEqual(a.status?.email, "ben@test.overpricedcardboard.com")
    }

    func testCollectionAndMissingCardsWaitWhileOffline() async throws {
        _ = try startStoreKit()
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: StoreKitBilling())
        await store.loadOffers()
        store.buy(try XCTUnwrap(store.onSale.first), "monthly")
        try await until("active") { a.gate == .open }
        XCTAssertTrue(a.has("collection"))

        StandIn.lock.lock(); StandIn.offline = true; StandIn.lock.unlock()
        a.addToCollection(cardId: "sv06-034", language: "EN", finish: "normal", condition: "NM", quantity: 2, label: "Darumaka · Twilight Masquerade 034/167")
        a.toggleWanted(cardId: "sv08-059", language: "DE", finish: "normal", label: "Magneton")
        try await until("queued") { a.pending.count == 2 && !a.syncing }
        XCTAssertEqual(a.wanted.first?.pending, true)

        StandIn.lock.lock(); StandIn.offline = false; StandIn.lock.unlock()
        a.sync()
        try await until("sent") { a.pending.isEmpty }
        try await until("collection loaded") { a.collection["sv06-034"]?.first?.quantity == 2 }
        XCTAssertEqual(a.wanted.first?.pending, false)
        XCTAssertNotNil(StandIn.calls("/collection/items").first?.body["client_ref"] as? String)
        // a second tap takes it off the list again
        a.toggleWanted(cardId: "sv08-059", language: "DE", finish: "normal", label: "Magneton")
        try await until("removed") { a.wanted.isEmpty && a.pending.isEmpty }
    }

    func testPairingCodes() {
        XCTAssertEqual(PairCode.normalize("k7q2 9mz4-d01a"), "K7Q2-9MZ4-D01A")
        XCTAssertEqual(PairCode.normalize("K7Q2-9MZ4-DO1A"), "K7Q2-9MZ4-D01A", "O is 0")
        XCTAssertNil(PairCode.normalize("K7Q2-9MZ4"))
        XCTAssertEqual(PairCode.fromUrl("https://overpricedcardboard.com/pair/K7Q2-9MZ4-D01A"),
                       PairCode.Target(base: "https://overpricedcardboard.com", code: "K7Q2-9MZ4-D01A"))
        XCTAssertNil(PairCode.fromUrl("https://evil.example/pair/K7Q2-9MZ4-D01A"))
        XCTAssertNil(PairCode.fromUrl("http://overpricedcardboard.com/pair/K7Q2-9MZ4-D01A"))
    }

    func testStatusParsing() {
        let st = AccountStatus.parse(["status": "active", "valid_until": "2026-12-18T09:30:00.000Z", "server_time": "2026-11-15T09:30:00Z",
                                      "account": ["id": "acc_1", "email": "anna@example.com", "store_ref": "0f6c1c58-3b8e-4f43-9c1f-2a7d5e9b4c10", "email_verified": false] as [String: Any],
                                      "subscription": ["plan": "creator", "base_plan": "yearly", "state": "canceled", "source": "app_store",
                                                       "ends_at": "2027-01-01T00:00:00Z", "devices": ["included": 1, "extra": 2, "in_store": 2, "in_use": 3]] as [String: Any],
                                      "features": ["scanner": true, "youtube": true, "webshop": false]])
        XCTAssertTrue(st.active)
        XCTAssertTrue(st.has("youtube"))
        XCTAssertFalse(st.has("webshop"))
        XCTAssertEqual(st.planName, "Creator")
        XCTAssertTrue(st.stateText.hasPrefix("ends on "))
        XCTAssertEqual(st.devicesText, "3 of 3 in use")
        XCTAssertEqual(st.devicesInStore, 2)
        XCTAssertFalse(st.emailVerified)
        XCTAssertEqual(AccountGate.of(connected: true, status: st, now: st.validUntil.addingTimeInterval(1)), .checkOnline)
        XCTAssertEqual(AccountGate.of(connected: true, status: st, now: st.validUntil.addingTimeInterval(-1)), .open)
        XCTAssertEqual(AccountGate.of(connected: false, status: st, now: Date()), .connect)
    }
}
