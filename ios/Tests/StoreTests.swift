import StoreKit
import StoreKitTest
import XCTest
@testable import OverpricedCardboard

/** The App Store without StoreKit: purchases as unsigned JWS-shaped tokens the stand-in reads (as the Android tests' FakeBilling). */
final class FakeAppStore: AppStoreBilling, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var held: [StoreTransaction] = []
    private(set) var finished: [UInt64] = []
    private var nextId: UInt64 = 1000

    func products(_ ids: [String]) async throws -> [String: StoreProduct] {
        var out: [String: StoreProduct] = [:]
        for id in ids { out[id] = StoreProduct(id: id, displayPrice: id.contains("creator") ? "€11.99" : "€6.99", price: 0, period: id.hasSuffix("yearly") ? "yearly" : "monthly") }
        return out
    }

    func buy(_ productId: String, accountToken: UUID) async -> BuyOutcome {
        lock.lock(); defer { lock.unlock() }
        nextId += 1
        let id = nextId
        let original = held.first { $0.productId == productId || (!$0.productId.contains("_devices_") && !productId.contains("_devices_")) }?.originalId ?? id
        let payload: [String: Any] = ["productId": productId, "appAccountToken": accountToken.uuidString.lowercased(),
                                      "transactionId": "\(id)", "originalTransactionId": "\(original)"]
        let b64 = try! JSONSerialization.data(withJSONObject: payload).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let t = StoreTransaction(id: id, originalId: original, productId: productId, accountToken: accountToken, jws: "eyJhbGciOiJub25lIn0.\(b64).sig",
                                 revoked: false, finish: { [weak self] in self?.markFinished(id) })
        held.removeAll { $0.productId.contains("_devices_") == productId.contains("_devices_") }
        held.append(t)
        return .bought(t)
    }

    private func markFinished(_ id: UInt64) { lock.lock(); finished.append(id); lock.unlock() }
    func current() async -> [StoreTransaction] { lock.lock(); defer { lock.unlock() }; return held }
    func sync() async throws {}
    func listen(_ handler: @escaping @Sendable (StoreTransaction) async -> Void) {}
}

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

    private func startStoreKit() async throws -> SKTestSession {
        let url = try XCTUnwrap(Bundle(for: StoreTests.self).url(forResource: "OverpricedCardboard", withExtension: "storekit"), "storekit file missing")
        let s = try SKTestSession(contentsOf: url)
        s.disableDialogs = true
        s.clearTransactions()
        s.resetToDefaultState()
        storeKit = s
        // some simulator runtimes don't load StoreKit test files from xcodebuild (Apple FB22237318): those tests are skipped there
        let found = (try? await Product.products(for: ["oc_collector_monthly"])) ?? []
        try XCTSkipIf(found.isEmpty, "StoreKitTest doesn't load the .storekit file on this simulator runtime (Apple FB22237318)")
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

    /// waits up to the given seconds; true when the condition held
    private func within(_ seconds: Double, _ cond: @MainActor () -> Bool) async -> Bool {
        for _ in 0..<Int(seconds * 10) {
            if cond() { return true }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return cond()
    }

    /**
     * A purchase in StoreKitTest. The purchase sheet and the App Store sign-in of Restore need a screen,
     * which a unit test hasn't: the purchase is made as the App Store's own (SKTestSession) for the
     * account's store_ref, and the app reports what the phone holds, as after Restore.
     */
    @discardableResult
    private func storeKitBuy(_ sk: SKTestSession, _ store: StoreModel, _ a: AccountModel, _ product: String,
                             required: Bool = true) async throws -> Bool {
        let ref = try XCTUnwrap(a.status?.storeRef.flatMap(UUID.init(uuidString:)))
        let t = try await sk.buyProduct(identifier: product, options: [.appAccountToken(ref)])
        log("bought \(product) in StoreKitTest: transaction \(t.id), appAccountToken \(t.appAccountToken?.uuidString.lowercased() ?? "-")")
        // the signed transaction, as the purchase call would have given it (StoreKitTest may take a moment)
        var found: VerificationResult<Transaction>?
        for _ in 0..<30 where found == nil {
            found = await Transaction.latest(for: product)
            if found == nil {
                for await v in Transaction.all {
                    if v.unsafePayloadValue.id == t.id { found = v; break }
                }
            }
            if found == nil { try await Task.sleep(nanoseconds: 100_000_000) }
        }
        guard let v = found else {
            if required { XCTFail("no signed transaction for \(product)") }
            return false
        }
        let ok = await store.report(StoreKitBilling.transaction(v), quiet: false)
        XCTAssertTrue(ok, "the server didn't take \(product): \(store.error ?? "-")")
        return ok
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

    func testStoreKitTestSellsTheStandInProducts() async throws {
        let sk = try await startStoreKit()
        do {
            let ps = try await Product.products(for: ["oc_collector_monthly", "oc_creator_yearly", "oc_collector_devices_1_monthly"])
            log("StoreKitTest products: \(ps.map { "\($0.id) \($0.displayPrice) \($0.subscription.map { "\($0.subscriptionPeriod.value) \($0.subscriptionPeriod.unit)" } ?? "-")" })")
            XCTAssertEqual(ps.count, 3)
        } catch {
            log("StoreKitTest products failed: \(error)")
            throw error
        }
        _ = sk
    }

    func testBuyingAPlanReportsTheTransactionAndOpensTheApp() async throws {
        let sk = try await startStoreKit()
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: StoreKitBilling())
        await store.loadOffers()
        log("on sale: \(store.onSale.map { "\($0.name) \(store.price($0, "monthly")?.displayPrice ?? "-") / \(store.price($0, "yearly")?.displayPrice ?? "-")" })")
        XCTAssertEqual(store.onSale.map(\.plan), ["collector", "creator"])
        let collector = try XCTUnwrap(store.onSale.first)
        XCTAssertEqual(store.price(collector, "monthly")?.period, "monthly")
        XCTAssertEqual(store.price(collector, "yearly")?.period, "yearly")
        XCTAssertEqual(PlanCard.text(try XCTUnwrap(store.price(collector, "monthly")), "month").hasSuffix(" / month"), true)

        try await storeKitBuy(sk, store, a, "oc_collector_monthly")
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
        // StoreKitTest doesn't always hand out the signed transaction of a second subscription group;
        // testExtraDevicesUseThePlansPeriod checks the same report with the fake App Store
        guard try await storeKitBuy(sk, store, a, "oc_collector_devices_1_monthly", required: false) else {
            log("StoreKitTest gave no signed transaction for the extra device; covered by testExtraDevicesUseThePlansPeriod")
            return
        }
        try await until("one extra device") { a.status?.devicesInStore == 1 }
        XCTAssertTrue(StandIn.calls("/store/apple").contains { StandIn.payload($0.body["signed_transaction"] as? String ?? "")?["productId"] as? String == "oc_collector_devices_1_monthly" })
    }

    func testRestorePurchasesOnANewPhone() async throws {
        let sk = try await startStoreKit()
        let first = try await signedIn()
        let store = StoreModel(account: first, billing: StoreKitBilling())
        await store.loadOffers()
        try await storeKitBuy(sk, store, first, "oc_collector_yearly")
        try await until("bought") { first.gate == .open }

        // the same Apple account on a "new phone", and a server that lost the purchase: Restore purchases sends it again
        let reports = StandIn.calls("/store/apple").count
        StandIn.lock.lock()
        StandIn.accounts.values.forEach { $0.active = false; $0.plan = nil; $0.basePlan = nil }
        StandIn.lock.unlock()
        let second = try await signedIn()
        XCTAssertEqual(second.gate, .ended)
        let store2 = StoreModel(account: second, billing: StoreKitBilling())
        await store2.reportHeld()
        try await until("restored") { StandIn.calls("/store/apple").count > reports }
        XCTAssertEqual(second.status?.basePlan, "yearly")
        XCTAssertEqual(second.gate, .open)
        log("restore: \(store2.note ?? store2.error ?? "-")")
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

    // ---------------------------------------------------------------- the same flows without StoreKit

    func testBuyingReportsTheTransactionAndFinishesIt() async throws {
        let shop = FakeAppStore()
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: shop)
        await store.loadOffers()
        XCTAssertEqual(store.onSale.map(\.plan), ["collector", "creator"])
        store.buy(try XCTUnwrap(store.onSale.first), "yearly")
        try await until("active") { a.gate == .open && !store.busy }
        let jws = try XCTUnwrap(StandIn.calls("/store/apple").last?.body["signed_transaction"] as? String)
        XCTAssertEqual((StandIn.payload(jws)?["appAccountToken"] as? String), a.status?.storeRef)
        XCTAssertEqual(a.status?.basePlan, "yearly")
        XCTAssertEqual(shop.finished.count, 1, "finished once the server took it")
        // a plan change: Creator in the same period
        store.changePlan(to: try XCTUnwrap(store.offers.first { $0.plan == "creator" }))
        try await until("creator") { a.status?.plan == "creator" && !store.busy }
        XCTAssertEqual(StandIn.payload(StandIn.calls("/store/apple").last?.body["signed_transaction"] as? String ?? "")?["productId"] as? String, "oc_creator_yearly")
        log("fake App Store: \(a.status?.planName ?? "-") · \(a.status?.stateText ?? "-") · finished \(shop.finished.count)")
    }

    func testAReportThatFailsStaysUnfinishedAndIsSentAgain() async throws {
        let shop = FakeAppStore()
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: shop)
        await store.loadOffers()
        StandIn.lock.lock(); StandIn.failNextReport = true; StandIn.lock.unlock()
        store.buy(try XCTUnwrap(store.onSale.first), "monthly")
        try await until("kept") { store.waiting == 1 && !store.busy }
        XCTAssertTrue(shop.finished.isEmpty, "not finished while the server hasn't taken it")
        XCTAssertNotEqual(a.gate, .open)
        await store.sendWaiting()
        XCTAssertEqual(store.waiting, 0)
        XCTAssertEqual(a.gate, .open)
        XCTAssertEqual(shop.finished.count, 1)
    }

    func testRestoreSendsWhatThePhoneHolds() async throws {
        let shop = FakeAppStore()
        let first = try await signedIn()
        let store = StoreModel(account: first, billing: shop)
        await store.loadOffers()
        store.buy(try XCTUnwrap(store.onSale.first), "monthly")
        try await until("bought") { first.gate == .open && !store.busy }
        StandIn.lock.lock(); StandIn.accounts.values.forEach { $0.active = false }; StandIn.lock.unlock()
        let second = try await signedIn()
        XCTAssertEqual(second.gate, .ended)
        let store2 = StoreModel(account: second, billing: shop)
        store2.restore()
        try await until("restored") { !store2.busy && second.gate == .open }
        XCTAssertEqual(store2.note, "Restored.")
    }

    func testAPurchaseOfAnotherAccountIsLeftAlone() async throws {
        let shop = FakeAppStore()
        let anna = try await signedIn("anna@test.overpricedcardboard.com")
        let store = StoreModel(account: anna, billing: shop)
        await store.loadOffers()
        store.buy(try XCTUnwrap(store.onSale.first), "monthly")
        try await until("bought") { anna.gate == .open && !store.busy }
        // another account on the same phone (the same Apple account): Anna's purchase isn't theirs
        let ben = try await signedIn("ben@test.overpricedcardboard.com")
        let store2 = StoreModel(account: ben, billing: shop)
        let reports = StandIn.calls("/store/apple").count
        store2.restore()
        try await until("done") { !store2.busy }
        XCTAssertEqual(StandIn.calls("/store/apple").count, reports, "not sent for the other account")
        XCTAssertEqual(store2.error, "This purchase belongs to another account.")
        XCTAssertNotEqual(ben.gate, .open)
    }

    func testExtraDevicesUseThePlansPeriod() async throws {
        let shop = FakeAppStore()
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: shop)
        await store.loadOffers()
        store.buy(try XCTUnwrap(store.onSale.first), "monthly")
        try await until("active") { a.gate == .open && !store.busy }
        XCTAssertEqual(store.devices?.max, 2)
        store.changeDevices(by: 1)
        try await until("one device") { a.status?.devicesInStore == 1 && !store.busy }
        store.changeDevices(by: 1)
        try await until("two devices") { a.status?.devicesInStore == 2 && !store.busy }
        store.changeDevices(by: 1)
        XCTAssertEqual(store.error, "No more extra devices in the app.")
    }

    func testCollectionQueueWithoutStoreKit() async throws {
        let shop = FakeAppStore()
        let a = try await signedIn()
        let store = StoreModel(account: a, billing: shop)
        await store.loadOffers()
        store.buy(try XCTUnwrap(store.onSale.first), "monthly")
        try await until("active") { a.gate == .open && !store.busy }
        StandIn.lock.lock(); StandIn.offline = true; StandIn.lock.unlock()
        a.addToCollection(cardId: "sv06-034", language: "EN", finish: "normal", condition: "NM", quantity: 2, label: "Darumaka")
        a.toggleWanted(cardId: "sv08-059", language: "DE", finish: "normal", label: "Magneton")
        try await until("queued") { a.pending.count == 2 && !a.syncing }
        StandIn.lock.lock(); StandIn.offline = false; StandIn.lock.unlock()
        a.sync()
        try await until("sent") { a.pending.isEmpty && !a.syncing }
        try await until("collection loaded") { a.collection["sv06-034"]?.first?.quantity == 2 }
        a.toggleWanted(cardId: "sv08-059", language: "DE", finish: "normal", label: "Magneton")
        try await until("removed") { a.wanted.isEmpty && a.pending.isEmpty }
    }
}
