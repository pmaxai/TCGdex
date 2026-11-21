import SwiftUI
import UIKit
import XCTest
@testable import OverpricedCardboard

/// The App Store edition's prices without StoreKit (screenshots).
final class FixedPrices: AppStoreBilling {
    func products(_ ids: [String]) async throws -> [String: StoreProduct] {
        var out: [String: StoreProduct] = [:]
        for id in ids {
            let yearly = id.hasSuffix("yearly")
            let creator = id.contains("creator")
            let price = creator ? (yearly ? "€129.49" : "€11.99") : (yearly ? "€75.49" : "€6.99")
            out[id] = StoreProduct(id: id, displayPrice: price, price: 0, period: yearly ? "yearly" : "monthly")
        }
        return out
    }
    func buy(_ productId: String, accountToken: UUID) async -> BuyOutcome { .cancelled }
    func current() async -> [StoreTransaction] { [] }
    func sync() async throws {}
    func listen(_ handler: @escaping @Sendable (StoreTransaction) async -> Void) {}
}

/// The prices of the Apple setup guide: the website's monthly prices; yearly 12 months less 10 %, rounded down
/// to a full euro (€ 75, € 129; a device € 43 or € 75), extra devices that times their number.
final class ReviewPrices: AppStoreBilling {
    func products(_ ids: [String]) async throws -> [String: StoreProduct] {
        var out: [String: StoreProduct] = [:]
        for id in ids {
            let yearly = id.hasSuffix("yearly")
            let creator = id.contains("creator")
            // oc_<plan>_devices_<n>_<period>: n times the plan's device price
            let parts = id.split(separator: "_")
            let devices = id.contains("_devices_") && parts.count > 3 ? Double(parts[3]) ?? 1 : 0
            let unit: Double = devices > 0 ? (creator ? 7 : 4) : (creator ? 12 : 7)
            let price = max(devices, 1) * (yearly ? (unit * 12 * 0.9).rounded(.down) : unit)
            out[id] = StoreProduct(id: id, displayPrice: String(format: "€%.2f", price), price: Decimal(price), period: yearly ? "yearly" : "monthly")
        }
        return out
    }
    func buy(_ productId: String, accountToken: UUID) async -> BuyOutcome { .cancelled }
    func current() async -> [StoreTransaction] { [] }
    func sync() async throws {}
    func listen(_ handler: @escaping @Sendable (StoreTransaction) async -> Void) {}
}

/**
 * Pictures of the main screens on the iPhone simulator, printed into the log as base64 PNG between
 * "SHOT-BEGIN <name>" and "SHOT-END" (every line starts with "SHOT"), as the Android tests do.
 */
@MainActor
final class ScreenshotTests: XCTestCase {
    /// `scrolledBy` / `scrolledToBottom`: where the screen's scroll view stands
    private func shot(_ name: String, _ view: some View, width: CGFloat = 393, height: CGFloat = 852,
                      scrolledBy: CGFloat? = nil, scrolledToBottom: Bool = false) async throws {
        // a window of the test host's scene (a window without a scene draws nothing)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first, "no window scene")
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: width, height: height)
        window.windowLevel = .alert + 1
        window.overrideUserInterfaceStyle = .dark
        let host = UIHostingController(rootView: view.preferredColorScheme(.dark).tint(Theme.gold))
        window.rootViewController = host
        window.makeKeyAndVisible()
        // let SwiftUI lay out, load what it loads, and draw
        for _ in 0..<20 { try await Task.sleep(nanoseconds: 100_000_000); window.layoutIfNeeded() }
        if scrolledToBottom || scrolledBy != nil, let sv = Self.scrollViews(window).max(by: { $0.contentSize.height < $1.contentSize.height }) {
            let bottom = sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom
            let y = scrolledBy.map { -sv.adjustedContentInset.top + $0 } ?? bottom
            sv.setContentOffset(CGPoint(x: 0, y: max(-sv.adjustedContentInset.top, min(bottom, y))), animated: false)
            for _ in 0..<5 { try await Task.sleep(nanoseconds: 100_000_000); window.layoutIfNeeded() }
        }
        let img = UIGraphicsImageRenderer(bounds: window.bounds, format: { let f = UIGraphicsImageRendererFormat(); f.scale = 2; return f }()).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        window.isHidden = true
        let png = try XCTUnwrap(img.pngData()).base64EncodedString()
        print("SHOT-BEGIN \(name)")
        var i = png.startIndex
        while i < png.endIndex {
            let j = png.index(i, offsetBy: 3000, limitedBy: png.endIndex) ?? png.endIndex
            print("SHOT " + png[i..<j])
            i = j
        }
        print("SHOT-END")
    }

    private static func scrollViews(_ v: UIView) -> [UIScrollView] {
        ((v as? UIScrollView).map { [$0] } ?? []) + v.subviews.flatMap { scrollViews($0) }
    }

    private func account() -> AccountModel {
        let suite = "shots-\(UUID().uuidString)"
        let a = AccountModel(store: AccountStore(defaults: UserDefaults(suiteName: suite)!,
                                                 dir: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), vault: MemoryVault()),
                             home: "https://standin.test")
        a.session = StandIn.session()
        return a
    }

    private func until(_ cond: @MainActor () -> Bool) async throws {
        for _ in 0..<100 { if cond() { return }; try await Task.sleep(nanoseconds: 100_000_000) }
    }

    func testSignInAndPlans() async throws {
        StandIn.reset()
        let a = account()
        let store = StoreModel(account: a, billing: FixedPrices())
        try await shot("ios-signin", AccountGateView(account: a, store: store))
        a.signInApple(identityToken: "test_apple:anna@test.overpricedcardboard.com", authorizationCode: nil)
        try await until { a.status != nil && !a.busy }
        await store.loadOffers()
        try await shot("ios-plans", AccountGateView(account: a, store: store))
    }

    func testResultAndTrading() async throws {
        guard let pack = ParityTests.pack ?? Pack.bundledDir.flatMap({ try? Pack(dir: $0) }) else { throw XCTSkip("no data pack") }
        let cat = Catalog(db: pack.db)
        let resolver = CardResolver(catalog: cat, pack: pack)
        let card = try XCTUnwrap(cat.cardExact("sv08-059", "de"))
        let s = resolver.build(group: cat.groupOf(card.id), version: card, text: nil, confidence: .sure, alternatives: [], locked: false,
                               fromPhoto: true, scan: nil, serial: 1)
        StandIn.reset()
        let a = account()
        let engine = try ScannerEngine(pack: pack)
        let scanner = ScannerModel(engine: engine, catalog: cat)
        // not signed in: the free version, this card's price revealed
        a.reveal(s.priceCard.id)
        let result = ScrollView { ResultCard(s: s, model: scanner, onSearch: {}).padding(12) }.background(Theme.ink).environmentObject(a)
        try await shot("ios-result", result)

        let trading = TradingModel(store: TradingStore(file: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)))
        trading.shown(s)
        trading.condition = .EX
        trading.asking = 0.03
        let price = ScrollView { CardPrice(s: s, scanner: scanner, trading: trading, onSearch: {}).padding(12) }.background(Theme.ink).environmentObject(a)
        try await shot("ios-trading", price)
        trading.addToCart(s)
        trading.side = .GIVE
        trading.addToCart(s)
        try await shot("ios-cart", CartSheet(trading: trading))
    }

    /**
     * The subscriptions' review screenshots for App Store Connect (Review Information): 400 × 575 points,
     * scaled to 640 × 920 pixels afterwards. The plans screen (top: Collector, bottom: Creator and the
     * renewal terms), and the extra devices in the Account tab for each plan.
     */
    func testReviewScreenshots() async throws {
        StandIn.reset()
        StandIn.fullLineup = true
        let a = account()
        let store = StoreModel(account: a, billing: ReviewPrices())
        a.signInApple(identityToken: "test_apple:review@overpricedcardboard.com", authorizationCode: nil)
        try await until { a.status != nil && !a.busy }
        StandIn.lock.lock()
        StandIn.accounts.values.forEach { $0.emailVerified = true }
        StandIn.lock.unlock()
        a.check(force: true)
        try await until { a.status?.emailVerified == true && !a.busy }
        await store.loadOffers()
        XCTAssertEqual(store.onSale.map(\.plan), ["collector", "creator"])
        XCTAssertEqual(store.onSale.compactMap { store.price($0, "yearly")?.displayPrice }, ["€75.00", "€129.00"])
        try await shot("review-plans-top", AccountGateView(account: a, store: store), width: 400, height: 575)
        try await shot("review-plans-bottom", AccountGateView(account: a, store: store), width: 400, height: 575, scrolledToBottom: true)

        for plan in ["collector", "creator"] {
            StandIn.lock.lock()
            StandIn.accounts.values.forEach { $0.active = true; $0.plan = plan; $0.basePlan = "monthly"; $0.source = "app_store"; $0.devicesInStore = 0 }
            StandIn.lock.unlock()
            a.check(force: true)
            try await until { a.status?.plan == plan && a.status?.active == true && !a.busy }
            await store.loadOffers()
            XCTAssertEqual(store.devices?.max, 4, "4 extra devices on sale for \(plan)")
            XCTAssertEqual(store.nextDevice?.count, 1)
            XCTAssertEqual(store.nextDevice?.product.displayPrice, plan == "creator" ? "€7.00" : "€4.00")
            let tab = NavigationStack { AccountView(account: a, store: store).navigationBarTitleDisplayMode(.inline) }
            // scrolled so that the plan, the devices row and its line under it are all in the picture
            try await shot("review-devices-\(plan)", tab, width: 400, height: 575, scrolledBy: 60)
        }
    }

    /// The free version: a scanned card with its price to reveal, revealed, the hour's limit, the locked Search
    /// page, the collection on the phone with the locked Missing tab, and the Account tab.
    func testFreeVersion() async throws {
        guard let pack = ParityTests.pack ?? Pack.bundledDir.flatMap({ try? Pack(dir: $0) }) else { throw XCTSkip("no data pack") }
        let cat = Catalog(db: pack.db)
        let resolver = CardResolver(catalog: cat, pack: pack)
        func shown(_ id: String, _ lang: String) throws -> Shown {
            let card = try XCTUnwrap(cat.cardExact(id, lang))
            return resolver.build(group: cat.groupOf(card.id), version: card, text: nil, confidence: .sure, alternatives: [], locked: false,
                                  fromPhoto: true, scan: nil, serial: 1)
        }
        StandIn.reset()
        let a = account()
        XCTAssertTrue(a.isFree, "no account: the free version")
        let scanner = ScannerModel(engine: try ScannerEngine(pack: pack), catalog: cat)
        func result(_ s: Shown) -> AnyView {
            AnyView(ScrollView { ResultCard(s: s, model: scanner, onSearch: {}).padding(12) }.background(Theme.ink).environmentObject(a))
        }
        let s = try shown("sv08-059", "de")
        for i in 0..<3 { a.reveal("other-\(i)") }
        try await shot("ios-free-result", result(s))
        XCTAssertTrue(a.reveal(s.priceCard.id))
        try await shot("ios-free-revealed", result(s))
        for i in 3..<9 { a.reveal("other-\(i)") }
        XCTAssertEqual(a.revealsLeft, 0)
        try await shot("ios-free-limit", result(try shown("base1-4", "en")))
        try await shot("ios-free-search", LockedPage(feature: .search, icon: "magnifyingglass"))
        a.addLocally(cardId: "sv08-059", language: "DE", finish: "normal", condition: "NM", quantity: 2, label: "Pikachu-ex")
        a.addLocally(cardId: "base1-4", language: "EN", finish: "holo", condition: "GD", label: "Charizard")
        a.addLocally(cardId: "swsh12pt5-160", language: "EN", finish: "normal", condition: "NM", label: "Pikachu")
        try await shot("ios-free-collection", CollectionScreen(account: a, catalog: cat).environmentObject(a))
        try await shot("ios-free-missing", CollectionScreen(account: a, catalog: cat, start: .missing).environmentObject(a))
        let store = StoreModel(account: a, billing: FixedPrices())
        try await shot("ios-free-account", AccountGateView(account: a, store: store, free: true))
    }

    /// The collection page (both tabs), a searched card with + Collection / ☆ Missing, and the bottom bar.
    func testCollectionSearchAndBar() async throws {
        guard let pack = ParityTests.pack ?? Pack.bundledDir.flatMap({ try? Pack(dir: $0) }) else { throw XCTSkip("no data pack") }
        let cat = Catalog(db: pack.db)
        StandIn.reset()
        let email = "review@overpricedcardboard.com"
        let a = account()
        a.signInApple(identityToken: "test_apple:\(email)", authorizationCode: nil)
        try await until { a.status != nil && !a.busy }
        StandIn.lock.lock()
        if let acc = StandIn.accounts[email] { acc.active = true; acc.plan = "collector"; acc.source = "web"; acc.emailVerified = true }
        StandIn.lock.unlock()
        a.check(force: true)
        try await until { a.has("collection") && !a.busy }
        // a few cards of the data pack, with names and values as the server fills them in
        let picks = [("sv08-059", "de", "NM", 2), ("sv08-059", "en", "EX", 1), ("base1-4", "en", "GD", 1), ("swsh12pt5-160", "en", "NM", 1)]
        for (id, lang, _, _) in picks {
            if let c = cat.card(id, lang) {
                StandIn.cards[id] = (c.name, c.setName, c.numberText, Int(((c.bestValue ?? 0) * 100).rounded()))
            }
        }
        for (id, lang, cond, qty) in picks {
            a.addToCollection(cardId: id, language: ShopTerms.languageOf(lang), finish: "normal", condition: cond, quantity: qty, label: id)
        }
        a.toggleWanted(cardId: "base1-2", language: "EN", finish: nil, label: "base1-2")
        a.toggleWanted(cardId: "sv08-238", language: "DE", finish: nil, label: "sv08-238")
        try await until { a.pending.isEmpty }
        await a.loadItems()
        try await shot("ios-collection", CollectionScreen(account: a, catalog: cat).environmentObject(a))
        try await shot("ios-missing", CollectionScreen(account: a, catalog: cat, start: .missing).environmentObject(a))
        // a copy's Edit, and Share before and after the public link
        let first = try XCTUnwrap(a.items?.first)
        try await shot("ios-edit-copy", EditCopySheet(title: first.name, binders: [Binder(id: 1, name: "Trade binder")], details: CopyDetails(first),
                                                      onSave: { _ in nil }, onDelete: { nil }))
        try await shot("ios-share-private", ShareCollectionSheet(account: a))
        let made = await a.makePublic()
        XCTAssertNil(made)
        try await shot("ios-share-public", ShareCollectionSheet(account: a))
        let card = try XCTUnwrap(cat.cardExact("sv08-059", "de"))
        try await shot("ios-search-card", NavigationStack { CardDetail(card: card) }.environmentObject(a))
        let bar = VStack(spacing: 0) {
            Spacer()
            BottomBar(tab: .constant(.mode), modes: [.scanner, .trading], mode: .trading, hasCollection: true) { _ in }
        }
        .background(Theme.ink)
        try await shot("ios-bottom-bar", bar, height: 160)
    }
}
