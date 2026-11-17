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

/**
 * Pictures of the main screens on the iPhone simulator, printed into the log as base64 PNG between
 * "SHOT-BEGIN <name>" and "SHOT-END" (every line starts with "SHOT"), as the Android tests do.
 */
@MainActor
final class ScreenshotTests: XCTestCase {
    private func shot(_ name: String, _ view: some View, height: CGFloat = 852) async throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: height))
        window.overrideUserInterfaceStyle = .dark
        let host = UIHostingController(rootView: view.preferredColorScheme(.dark).tint(Theme.gold))
        window.rootViewController = host
        window.makeKeyAndVisible()
        // let SwiftUI lay out, load what it loads, and draw
        for _ in 0..<20 { try await Task.sleep(nanoseconds: 100_000_000); window.layoutIfNeeded() }
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
}
