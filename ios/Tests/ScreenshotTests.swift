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
            let price = id.hasPrefix("oc_psa_") ? (yearly ? "€32.00" : "€2.99") : creator ? (yearly ? "€129.49" : "€11.99") : (yearly ? "€75.49" : "€6.99")
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
            // the PSA prices add-on: €2.99 a month, €32 a year
            if id.hasPrefix("oc_psa_") {
                out[id] = StoreProduct(id: id, displayPrice: yearly ? "€32.00" : "€2.99", price: yearly ? 32 : 2.99, period: yearly ? "yearly" : "monthly")
                continue
            }
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
    /// scale 3 with tag "ASHOT": the App Store's 1179 × 2556 pixels, opaque, only in test.log (too big for the job's log)
    private func shot(_ name: String, _ view: some View, width: CGFloat = 393, height: CGFloat = 852,
                      scrolledBy: CGFloat? = nil, scrolledToBottom: Bool = false, scale: CGFloat = 2, tag: String = "SHOT") async throws {
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
        let img = UIGraphicsImageRenderer(bounds: window.bounds, format: {
            let f = UIGraphicsImageRendererFormat()
            f.scale = scale
            f.opaque = tag != "SHOT"
            return f
        }()).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        window.isHidden = true
        let png = try XCTUnwrap(img.pngData()).base64EncodedString()
        print("\(tag)-BEGIN \(name)")
        var i = png.startIndex
        while i < png.endIndex {
            let j = png.index(i, offsetBy: 3000, limitedBy: png.endIndex) ?? png.endIndex
            print("\(tag) " + png[i..<j])
            i = j
        }
        print("\(tag)-END")
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

    func testResultAndCart() async throws {
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

        // Add to cart: the popup that prices the card for a deal, then the cart
        let trading = TradingModel(store: TradingStore(file: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)))
        trading.startDeal()
        trading.condition = .EX
        trading.asking = 0.03
        try await shot("ios-add-to-cart", DealSheet(d: DealCard(s), trading: trading))
        trading.addToCart(DealCard(s))
        trading.side = .GIVE
        trading.addToCart(DealCard(s))
        try await shot("ios-cart", CartSheet(trading: trading))
    }

    /**
     * The subscriptions' review screenshots for App Store Connect (Review Information): 400 × 575 points,
     * scaled to 640 × 920 pixels afterwards. The plans screen (top: Collector, bottom: Creator and the
     * renewal terms), the extra devices in the Account tab for each plan, and the PSA prices add-on in
     * Settings › Price data & add-ons (one picture for both of its products).
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

        // the PSA prices add-on, on sale to a running Collector plan
        StandIn.lock.lock()
        StandIn.accounts.values.forEach { $0.active = true; $0.plan = "collector"; $0.basePlan = "monthly"; $0.source = "app_store"; $0.psa = nil }
        StandIn.lock.unlock()
        a.check(force: true)
        try await until { a.status?.plan == "collector" && a.status?.active == true && !a.busy }
        await store.loadOffers()
        let psa = try XCTUnwrap(store.addons.first, "the add-on on sale")
        XCTAssertEqual(store.price(psa, "monthly")?.displayPrice, "€2.99")
        XCTAssertEqual(store.price(psa, "yearly")?.displayPrice, "€32.00")
        XCTAssertNil(store.addonBlocked(psa), "for sale to Collector")
        let page = NavigationStack {
            PriceDataView(account: a, store: store).navigationTitle("Price data & add-ons").navigationBarTitleDisplayMode(.inline)
        }
        try await shot("review-psa", page, width: 400, height: 575)
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
        // a card opened in the search: prices of every source, the chart, PSA, + Collection / ☆ Missing, Add to cart
        let trading = TradingModel(store: TradingStore(file: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)))
        let card = try XCTUnwrap(cat.cardExact("sv08-059", "de"))
        let detail = NavigationStack { CardDetail(card: card, catalog: cat, trading: trading) }.environmentObject(a)
        try await shot("ios-search-card", detail)
        try await shot("ios-search-card-bottom", detail, scrolledToBottom: true)
        // the scanner: no top bar, the cart (top right) and a photo (bottom right) in the camera's corners
        let scanner = ScannerModel(engine: try ScannerEngine(pack: pack), catalog: cat)
        let resolver = CardResolver(catalog: cat, pack: pack)
        scanner.show(resolver.build(group: cat.groupOf(card.id), version: card, text: nil, confidence: .sure, alternatives: [], locked: true,
                                    fromPhoto: false, scan: nil, serial: 1))
        trading.addToCart(DealCard(card: card, priceCard: card, printing: card.printings.first))
        try await shot("ios-scanner", ScannerScreen(model: scanner, trading: trading).environmentObject(a))
        // Settings: the account and the pages; Prices (the default price); Price data & add-ons
        let store = StoreModel(account: a, billing: FixedPrices())
        await store.loadOffers()
        try await shot("ios-settings", SettingsScreen(account: a, store: store, trading: trading, scanner: scanner, catalog: cat, path: .constant([])))
        try await shot("ios-settings-prices", NavigationStack { PricesSettingsView(scanner: scanner).navigationTitle("Prices").navigationBarTitleDisplayMode(.inline) })
        try await shot("ios-settings-price-data", NavigationStack {
            PriceDataView(account: a, store: store).navigationTitle("Price data & add-ons").navigationBarTitleDisplayMode(.inline)
        })
        let bar = VStack(spacing: 0) {
            Spacer()
            BottomBar(tab: .constant(.scanner), hasCollection: true)
        }
        .background(Theme.ink)
        try await shot("ios-bottom-bar", bar, height: 160)
    }

    /**
     * YouTube Mode: the reveal (a HIT) over the pack's bar, the bottom bar with the mode; Settings › YouTube Mode and
     * › Recording (the account's storage, tested), the start sheet, the submit screen with templates and inputs, the
     * Recordings list.
     */
    func testYouTubeMode() async throws {
        guard let pack = ParityTests.pack ?? Pack.bundledDir.flatMap({ try? Pack(dir: $0) }) else { throw XCTSkip("no data pack") }
        let cat = Catalog(db: pack.db)
        let d0 = UserDefaults.standard
        let saved = ["youtube", "title", "threshold"].map { ($0, d0.object(forKey: $0)) }
        defer { for (k, v) in saved { if let v { d0.set(v, forKey: k) } else { d0.removeObject(forKey: k) } } }
        StandIn.reset()
        FakeS3.reset()
        let email = "creator@overpricedcardboard.com"
        let a = account()
        a.signInApple(identityToken: "test_apple:\(email)", authorizationCode: nil)
        try await until { a.status != nil && !a.busy }
        StandIn.lock.lock()
        if let acc = StandIn.accounts[email] { acc.active = true; acc.plan = "creator"; acc.source = "app_store"; acc.emailVerified = true; acc.s3 = true }
        StandIn.lock.unlock()
        a.check(force: true)
        try await until { a.has("youtube") && !a.busy }
        XCTAssertTrue(a.has("recording"))
        XCTAssertTrue(a.has("s3_upload"))

        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("yt-\(UUID().uuidString)")
        let q = UploadQueue()
        q.store = RecordingStore(root: tmp.appendingPathComponent("recordings"))
        q.prefs = RecordingPrefs(defaults: UserDefaults(suiteName: "yt-\(UUID().uuidString)")!, vault: MemoryVault())
        q.session = FakeS3.session()
        let rec = RecordingModel(store: q.store, prefs: q.prefs, uploads: q, templatesCache: tmp.appendingPathComponent("templates/index.json"))
        rec.uploadAllowed = { a.has("s3_upload") && a.has("pipeline") }
        // the account's storage, as the shop hands it out
        a.onS3 = { [weak rec] o in rec?.applyManagedS3(o) }
        a.refreshS3()
        try await until { rec.settings.managed }
        XCTAssertEqual(rec.settings.bucket, "bucket")

        let sc = ScannerModel(engine: try ScannerEngine(pack: pack), catalog: cat)
        sc.settings.youtube = true
        sc.settings.threshold = 5
        sc.settings.title = "Surging Sparks booster box"
        rec.attach(sc)
        let resolver = CardResolver(catalog: cat, pack: pack)
        for (id, lang) in [("swsh12pt5-160", "en"), ("sv08-059", "de"), ("base1-4", "en")] {
            guard let card = cat.cardExact(id, lang) ?? cat.card(id, lang) else { continue }
            sc.show(resolver.build(group: cat.groupOf(card.id), version: card, text: nil, confidence: .sure, alternatives: [], locked: true,
                                   fromPhoto: false, scan: nil, serial: 0))
            sc.addAgain()
        }
        XCTAssertGreaterThanOrEqual(sc.session.count, 2)
        XCTAssertTrue(isHit(sc.shown?.value, 5), "Charizard is a hit")
        try await shot("ios-youtube", YouTubeScreen(model: sc, rec: rec).environmentObject(a))
        let bar = VStack(spacing: 0) {
            Spacer()
            BottomBar(tab: .constant(.settings), hasCollection: true, mode: .youtube)
        }
        .background(Theme.ink)
        try await shot("ios-bottom-bar-youtube", bar, height: 160)

        try await shot("ios-youtube-settings", NavigationStack {
            YouTubeSettingsView(model: sc).navigationTitle("YouTube Mode").navigationBarTitleDisplayMode(.inline)
        })
        var s = rec.settings
        s.uploadS3 = true
        rec.saveSettings(s)
        XCTAssertTrue(rec.settings.uploadS3)
        rec.testConnection()
        try await until { !rec.testing && rec.connection != nil }
        XCTAssertEqual(rec.connectionOk, true, rec.connection ?? "")
        let settingsPage = NavigationStack {
            RecordingSettingsView(rec: rec, account: a).navigationTitle("Recording").navigationBarTitleDisplayMode(.inline)
        }
        try await shot("ios-recording-settings", settingsPage)
        try await shot("ios-recording-settings-bottom", settingsPage, scrolledToBottom: true)
        try await shot("ios-start-recording", StartRecordingSheet(rec: rec, scanner: sc))

        // a finished recording with the pack's cards, one uploaded, one failed
        let d = q.store.create()
        let cards: [JSON] = sc.session.enumerated().map { i, it in
            ["entry": i + 1, "hit": isHit(it.value, 5), "card": RecJSON.cardJson(it.shown), "price": RecJSON.priceJson(it.shown)]
        }
        try RecJSON.data(["schema": Recording.schema, "cards": cards, "video": ["audio": "mic"] as JSON] as JSON).write(to: d.events)
        try Data(count: 4096).write(to: d.video)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let packCount = sc.session.count
        d.updateMeta { $0["status"] = RecStatus.LOCAL.rawValue; $0["duration_ms"] = 293_000; $0["cards"] = packCount; $0["hits"] = 1; $0["created"] = now }
        let up = q.store.create(now: Date().addingTimeInterval(-86_400))
        up.updateMeta { $0["status"] = RecStatus.UPLOADED.rawValue; $0["duration_ms"] = 1_834_000; $0["cards"] = 38; $0["hits"] = 3; $0["created"] = now - 86_400_000 }
        let failed = q.store.create(now: Date().addingTimeInterval(-3600))
        failed.updateMeta {
            $0["status"] = RecStatus.FAILED.rawValue; $0["error"] = "This key may not write to the bucket/prefix"; $0["duration_ms"] = 612_000
            $0["cards"] = 11; $0["hits"] = 0; $0["created"] = now - 3_600_000
        }
        let templates = LoadedTemplates(index: try TemplatesIndex.parse(Data(RecordingTests.templatesJson.utf8)), source: .bucket)
        try await shot("ios-submit", SubmitView(rec: rec, id: d.id, title: "Recording finished", line: "4:53 · 3 cards · 1 hits · 412 MB",
                                                onDone: {}, onLeave: {}, preset: templates, presetPack: Submission.packOf(d.events), presetMic: true))
        rec.refresh()
        XCTAssertEqual(rec.recordings.count, 3)
        try await shot("ios-recordings", RecordingsSheet(rec: rec, account: a))
        sc.settings.youtube = false
        sc.settings.title = ""
    }

    // ------------------------------------------------------------------ the App Store's screenshots

    /// The status bar as on a phone (the simulator's window draws none): 9:41, signal, Wi-Fi, battery.
    private struct StatusBar: View {
        var body: some View {
            HStack {
                Text("9:41").font(.system(size: 17, weight: .semibold))
                Spacer()
                HStack(spacing: 6) {
                    Image(systemName: "cellularbars")
                    Image(systemName: "wifi")
                    Image(systemName: "battery.100percent").font(.system(size: 19))
                }
                .font(.system(size: 15, weight: .semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 34)
            .frame(height: 54, alignment: .center)
            .padding(.top, 2)
        }
    }

    private func onPhone(_ v: some View) -> some View {
        ZStack(alignment: .top) {
            v
            StatusBar().frame(maxHeight: .infinity, alignment: .top).ignoresSafeArea().allowsHitTesting(false)
        }
    }

    /// What the camera would see: the card on a dark table (the simulator has no camera; the picture is the card's own).
    private func onTable(_ card: CardInfo) -> AnyView {
        AnyView(GeometryReader { g in
            ZStack {
                RadialGradient(colors: [Color(red: 0.24, green: 0.2, blue: 0.17), Color(red: 0.08, green: 0.07, blue: 0.06)],
                               center: .center, startRadius: 10, endRadius: max(g.size.width, g.size.height) * 0.75)
                CardPicture(card: card, large: true)
                    .frame(height: g.size.height * 0.66)
                    .rotation3DEffect(.degrees(8), axis: (x: 1, y: -0.3, z: 0), perspective: 0.6)
                    .rotationEffect(.degrees(-2.5))
                    .shadow(color: .black.opacity(0.6), radius: 14, x: 4, y: 10)
            }
            .frame(width: g.size.width, height: g.size.height)
        })
    }

    private func storeShot(_ name: String, _ v: some View) async throws {
        try await shot(name, v, scale: 3, tag: "ASHOT")
    }

    /**
     * The App Store's screenshots, 1179 × 2556 (iPhone with Dynamic Island, medium): the scanner with a card, a card's
     * prices and chart, YouTube Mode with a HIT, the collection, Add to cart, the cart, the missing cards. Card
     * pictures come from the network while the test runs (none is kept in the repo).
     */
    func testAppStoreScreenshots() async throws {
        guard let pack = ParityTests.pack ?? Pack.bundledDir.flatMap({ try? Pack(dir: $0) }) else { throw XCTSkip("no data pack") }
        let cat = Catalog(db: pack.db)
        let d0 = UserDefaults.standard
        let saved = ["youtube", "title", "threshold"].map { ($0, d0.object(forKey: $0)) }
        defer { for (k, v) in saved { if let v { d0.set(v, forKey: k) } else { d0.removeObject(forKey: k) } } }
        func card(_ id: String, _ lang: String = "en") -> CardInfo? { cat.cardExact(id, lang) ?? cat.card(id, lang) }
        let charizardEx = try XCTUnwrap(card("sv03.5-199") ?? card("base1-4"))
        let umbreon = try XCTUnwrap(card("swsh7-215") ?? card("base1-4"))
        let charizard = try XCTUnwrap(card("base1-4"))
        let pikachu = card("sv08-238") ?? card("sv08-059")
        print("StoreTest: App Store cards: \(charizardEx.id) \(umbreon.id) \(charizard.id) \(pikachu?.id ?? "-")")

        StandIn.reset()
        let email = "creator@overpricedcardboard.com"
        let a = account()
        a.signInApple(identityToken: "test_apple:\(email)", authorizationCode: nil)
        try await until { a.status != nil && !a.busy }
        StandIn.lock.lock()
        if let acc = StandIn.accounts[email] { acc.active = true; acc.plan = "creator"; acc.source = "app_store"; acc.emailVerified = true }
        StandIn.lock.unlock()
        a.check(force: true)
        try await until { a.has("youtube") && a.has("collection") && !a.busy }

        // 1 the scanner: a card in front of the camera, its prices below
        let trading = TradingModel(store: TradingStore(file: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)))
        let scanner = ScannerModel(engine: try ScannerEngine(pack: pack), catalog: cat)
        let resolver = CardResolver(catalog: cat, pack: pack)
        func shown(_ c: CardInfo, serial: Int) -> Shown {
            resolver.build(group: cat.groupOf(c.id), version: c, text: nil, confidence: .sure, alternatives: [], locked: true,
                           fromPhoto: false, scan: nil, serial: serial)
        }
        scanner.show(shown(charizardEx, serial: 1))
        trading.addToCart(DealCard(card: umbreon, priceCard: umbreon, printing: umbreon.printings.first))
        try await storeShot("appstore-1-scanner", onPhone(ScannerScreen(model: scanner, trading: trading)
            .environment(\.cameraStandIn, onTable(charizardEx)).environmentObject(a)))

        // 2 a card's page: every price source, the chart, PSA
        try await storeShot("appstore-2-card", onPhone(NavigationStack { CardDetail(card: umbreon, catalog: cat, trading: trading) }.environmentObject(a)))

        // 3 YouTube Mode: the pack's reveal, a HIT
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("as-\(UUID().uuidString)")
        let q = UploadQueue()
        q.store = RecordingStore(root: tmp.appendingPathComponent("recordings"))
        q.prefs = RecordingPrefs(defaults: UserDefaults(suiteName: "as-\(UUID().uuidString)")!, vault: MemoryVault())
        let rec = RecordingModel(store: q.store, prefs: q.prefs, uploads: q, templatesCache: tmp.appendingPathComponent("templates/index.json"))
        let yt = ScannerModel(engine: try ScannerEngine(pack: pack), catalog: cat)
        yt.settings.youtube = true
        yt.settings.threshold = 50
        yt.settings.title = "Opening a booster bundle"
        for c in [pikachu, umbreon, charizard].compactMap({ $0 }) {
            yt.show(shown(c, serial: 0))
            yt.addAgain()
        }
        try await storeShot("appstore-3-youtube", YouTubeScreen(model: yt, rec: rec).environment(\.cameraStandIn, onTable(charizard)).environmentObject(a))

        // 4 the collection, 7 the missing cards
        let picks: [(CardInfo, String, Int)] = [(charizard, "EX", 1), (charizardEx, "NM", 2), (umbreon, "NM", 1)] + (pikachu.map { [($0, "NM", 1)] } ?? [])
        for (c, _, _) in picks {
            StandIn.cards[c.id] = (c.name, c.setName, c.numberText, Int(((c.bestValue ?? 0) * 100).rounded()))
        }
        for (c, cond, qty) in picks {
            a.addToCollection(cardId: c.id, language: ShopTerms.languageOf(c.lang), finish: "normal", condition: cond, quantity: qty, label: c.id)
        }
        for id in ["base1-2", "base1-15", "swsh7-218", "sv03.5-205"] {
            if let c = card(id) {
                StandIn.cards[id] = (c.name, c.setName, c.numberText, Int(((c.bestValue ?? 0) * 100).rounded()))
                a.toggleWanted(cardId: id, language: "EN", finish: nil, label: id)
            }
        }
        try await until { a.pending.isEmpty }
        await a.loadItems()
        try await storeShot("appstore-4-collection", onPhone(CollectionScreen(account: a, catalog: cat).environmentObject(a)))

        // 5 Add to cart: a price for a deal; 6 the cart, both sides
        let deal = shown(charizard, serial: 2)
        trading.startDeal()
        trading.condition = .EX
        trading.asking = (deal.value ?? 300) * 0.8
        try await storeShot("appstore-5-add-to-cart", onPhone(DealSheet(d: DealCard(deal), trading: trading)))
        trading.addToCart(DealCard(deal))
        if let p = pikachu {
            trading.side = .GIVE
            trading.addToCart(DealCard(card: p, priceCard: p, printing: p.printings.first))
        }
        try await storeShot("appstore-6-cart", onPhone(CartSheet(trading: trading)))
        try await storeShot("appstore-7-missing", onPhone(CollectionScreen(account: a, catalog: cat, start: .missing).environmentObject(a)))
        yt.settings.youtube = false
        yt.settings.title = ""
    }
}
