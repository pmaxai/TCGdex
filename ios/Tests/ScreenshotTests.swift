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

/// The prices of the Apple setup guide: the website's monthly prices; the yearly ones 10 % below 12 months.
final class ReviewPrices: AppStoreBilling {
    func products(_ ids: [String]) async throws -> [String: StoreProduct] {
        var out: [String: StoreProduct] = [:]
        for id in ids {
            let yearly = id.hasSuffix("yearly")
            let creator = id.contains("creator")
            // oc_<plan>_devices_<n>_<period>: n times the plan's device price
            let parts = id.split(separator: "_")
            let devices = id.contains("_devices_") && parts.count > 3 ? Double(parts[3]) ?? 1 : 0
            let monthly = devices > 0 ? devices * (creator ? 7 : 4) : (creator ? 12 : 7)
            let price = yearly ? monthly * 12 * 0.9 : monthly
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
            let tab = NavigationStack { AccountView(account: a, store: store).navigationBarTitleDisplayMode(.inline) }
            // scrolled so that the plan, the devices row and its line under it are all in the picture
            try await shot("review-devices-\(plan)", tab, width: 400, height: 575, scrolledBy: 60)
        }
    }
}
