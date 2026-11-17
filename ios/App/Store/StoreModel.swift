import Foundation
import SwiftUI

/// A creator code (affiliate) the account is registered for, with its discount on the first subscription.
struct Affiliate: Equatable {
    let code: String
    let discountPercent: Int?
    let discountMonths: Int?
    /// the App Store offer code that gives the discount (redeemed in Apple's sheet); nil: none for the App Store
    let appleOfferCode: String?

    /// "20 % off for 3 months", "20 % off"
    var discountText: String? {
        guard let p = discountPercent, p > 0 else { return nil }
        return "\(p) % off" + (discountMonths.map { " for " + ($0 == 1 ? "1 month" : "\($0) months") } ?? "")
    }

    static func parse(_ o: JSON?) -> Affiliate? {
        guard let o, let code = AccountStatus.str(o, "code") else { return nil }
        return Affiliate(code: code, discountPercent: (o["discount_percent"] as? NSNumber)?.intValue,
                         discountMonths: (o["discount_months"] as? NSNumber)?.intValue, appleOfferCode: AccountStatus.str(o, "apple_offer_code"))
    }

    /// The creator code in a link: https://overpricedcardboard.com/r/MISTY (nil for other links).
    static func fromLink(_ url: URL) -> String? {
        guard let host = url.host?.lowercased(), PairCode.hosts.contains(host) else { return nil }
        let parts = url.path.split(separator: "/")
        guard parts.count == 2, parts[0] == "r" else { return nil }
        let code = String(parts[1]).removingPercentEncoding ?? String(parts[1])
        return code.count <= 40 && !code.isEmpty && code.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) ? code : nil
    }
}

/// A plan on sale (`GET /store/offers`) with the App Store product ids the shop entered.
struct PlanOffer: Equatable, Identifiable {
    let plan: String
    let name: String
    let features: [String]
    /// "monthly" → the App Store product id (empty while the shop has none for the App Store)
    let productIds: [String: String]
    /// extra devices: per period, the product for 1, 2, 3 … extra devices
    let deviceProductIds: [String: [String]]
    var id: String { plan }

    static func parse(_ list: [JSON]) -> [PlanOffer] {
        list.map { o in
            let a = o["apple"] as? JSON
            var ids: [String: String] = [:]
            if let p = a?["product_ids"] as? JSON { for (k, v) in p { if let s = v as? String, !s.isEmpty { ids[k] = s } } }
            var devices: [String: [String]] = [:]
            if let d = a?["device_product_ids"] as? JSON {
                for (k, v) in d { devices[k] = (v as? [Any])?.compactMap { ($0 as? String).flatMap { $0.isEmpty ? nil : $0 } } ?? [] }
            }
            return PlanOffer(plan: AccountStatus.str(o, "plan") ?? "", name: AccountStatus.str(o, "name") ?? AccountStatus.str(o, "plan") ?? "",
                             features: (o["features"] as? [Any])?.compactMap { $0 as? String } ?? [], productIds: ids, deviceProductIds: devices)
        }
    }

    var allProductIds: [String] { Array(productIds.values) + deviceProductIds.values.flatMap { $0 } }
}

/// What a feature is called on the plans.
func featureLabel(_ f: String) -> String {
    ["scanner": "Card scanner with prices", "trading": "Trading mode for card shows", "collection": "Your collection",
     "wanted": "Missing cards list", "youtube": "YouTube mode", "recording": "Recording and upload", "webshop": "Webshop mode"][f] ?? f
}

/**
 * Buying in the App Store (port of the Play edition's StoreCoordinator): the plans on sale with the
 * App Store's prices, buying one with the account's store_ref as appAccountToken, a plan change
 * (inside the subscription group), extra devices, Restore purchases. Every transaction is reported
 * to the server (`POST /store/apple`), which checks it with Apple; only then is it finished. A
 * report that doesn't get through stays unfinished, and is sent again.
 */
@MainActor
final class StoreModel: ObservableObject {
    @Published private(set) var offers: [PlanOffer] = []
    /// what the App Store sells of them: product id → price and period
    @Published private(set) var products: [String: StoreProduct] = [:]
    @Published private(set) var loading = false
    /// a purchase sheet or a report runs
    @Published private(set) var busy = false
    @Published var error: String?
    /// "Restored", "Sent again when there is a connection"
    @Published var note: String?
    /// transactions waiting to be reported
    @Published private(set) var waiting = 0
    @Published private(set) var affiliate: Affiliate?

    let account: AccountModel
    let billing: AppStoreBilling
    private var pendingFinish: [UInt64: StoreTransaction] = [:]
    private var retry: Task<Void, Never>?

    init(account: AccountModel, billing: AppStoreBilling) {
        self.account = account
        self.billing = billing
    }

    private var status: AccountStatus? { account.status }

    /// a plan's price for a period ("monthly"), nil when the App Store doesn't sell it
    func price(_ offer: PlanOffer, _ period: String) -> StoreProduct? { offer.productIds[period].flatMap { products[$0] } }

    /// the plans the App Store really sells (both the shop's product ids and Apple's answer)
    var onSale: [PlanOffer] { offers.filter { o in o.productIds.values.contains { products[$0] != nil } } }

    /// the running App Store subscription's plan as sold here
    var runningPlan: PlanOffer? {
        guard let st = status, st.source == "app_store", st.active else { return nil }
        return offers.first { $0.plan == st.plan }
    }

    /// the running subscription's billing period: what the server says, else from the renewal date
    var runningPeriod: String {
        if let b = status?.basePlan, b == "monthly" || b == "yearly" { return b }
        guard let renews = status?.renewsAt ?? status?.endsAt else { return "monthly" }
        return renews.timeIntervalSinceNow > 32 * 86_400 ? "yearly" : "monthly"
    }

    /// extra devices bought in the App Store, and how many the plan sells here (nil: none sold in the app)
    var devices: (have: Int, max: Int)? {
        guard let plan = runningPlan, let list = plan.deviceProductIds[runningPeriod], !list.isEmpty else { return nil }
        return (status?.devicesInStore ?? 0, list.count)
    }

    // ---------------------------------------------------------------- start, offers
    /// At start and after connecting: the plans, then every transaction this phone holds goes to the server.
    func start() {
        billing.listen { [weak self] t in await self?.report(t, quiet: true) }
        guard account.store.token != nil else { return }
        Task { [self] in
            await loadOffers()
            for t in await billing.current() { await report(t, quiet: true) }
        }
    }

    /// The plans and the App Store's prices (connected).
    func refresh() {
        guard account.store.token != nil else { return }
        Task { await loadOffers() }
    }

    func loadOffers() async {
        guard let token = account.store.token, let api = account.api() else { return }
        loading = true
        error = nil
        do {
            let o = try await api.offers(token: token)
            let list = PlanOffer.parse(o["offers"] as? [JSON] ?? [])
            let ids = list.flatMap(\.allProductIds)
            let prices = ids.isEmpty ? [:] : ((try? await billing.products(ids)) ?? [:])
            offers = list
            products = prices
            affiliate = Affiliate.parse(o["affiliate"] as? JSON)
        } catch let e as AccountError {
            error = e.friendly
        } catch {
            self.error = "The plans can't be loaded without a connection."
        }
        loading = false
    }

    // ---------------------------------------------------------------- buying
    /// Buy a plan for a period ("monthly", "yearly"). Only without a running subscription (one paid source).
    func buy(_ offer: PlanOffer, _ period: String) {
        guard let st = status else { return }
        guard let ref = st.storeRef.flatMap(UUID.init(uuidString:)) else { error = "The account has no store reference yet. Check again."; return }
        if st.active && st.source != "app_store" { error = "This account already has a subscription."; return }
        guard let product = offer.productIds[period], products[product] != nil else { error = "The App Store doesn't sell this plan right now."; return }
        purchase(product, ref)
    }

    /// Switch the running App Store subscription to another plan (Apple: an upgrade at once, a downgrade at the next renewal).
    func changePlan(to: PlanOffer) {
        guard runningPlan != nil, let ref = status?.storeRef.flatMap(UUID.init(uuidString:)) else {
            error = "No running App Store subscription for this account. Try Restore purchases."; return
        }
        guard let product = to.productIds[runningPeriod], products[product] != nil else { error = "The App Store doesn't sell \(to.name) right now."; return }
        purchase(product, ref)
    }

    /// One more (or one fewer) extra device: the product for that number of devices, in the plan's billing period.
    func changeDevices(by: Int) {
        guard let plan = runningPlan, let ref = status?.storeRef.flatMap(UUID.init(uuidString:)),
              let list = plan.deviceProductIds[runningPeriod], let have = devices?.have else {
            error = "The App Store doesn't sell extra devices for this subscription."; return
        }
        let want = have + by
        if want < 1 {
            error = "To stop the last extra device, cancel it in the App Store's subscriptions (Manage subscription)."; return
        }
        guard want <= list.count else { error = "No more extra devices in the app."; return }
        let product = list[want - 1]
        guard products[product] != nil else { error = "The App Store doesn't sell the extra devices right now."; return }
        purchase(product, ref)
    }

    private func purchase(_ product: String, _ ref: UUID) {
        if busy { return }
        busy = true
        error = nil
        note = nil
        Task { [self] in
            switch await billing.buy(product, accountToken: ref) {
            case .bought(let t): await report(t, quiet: false)
            case .pending: note = "The purchase waits for approval. It unlocks as soon as it goes through."
            case .cancelled: break
            case .failed(let why): error = why
            }
            busy = false
        }
    }

    // ---------------------------------------------------------------- creator codes
    /// "Have a creator code?" on the plans: the account is registered for it.
    func applyCode(_ code: String) {
        guard let token = account.store.token, let api = account.api() else { return }
        let c = code.trimmingCharacters(in: .whitespaces)
        if c.isEmpty || busy { return }
        busy = true
        error = nil
        note = nil
        Task { [self] in
            do {
                let a = Affiliate.parse(try await api.affiliate(token: token, code: c)["affiliate"] as? JSON)
                affiliate = a
                await loadOffers()
                if let a { note = "Creator code \(a.code)" + (a.discountText.map { ": \($0)" } ?? "") + "." }
            } catch let e as AccountError {
                if let a = Affiliate.parse(e.json?["affiliate"] as? JSON) { affiliate = a }
                error = e.error == "unknown_code" ? "There's no creator code \"\(c)\"." : e.friendly
            } catch {
                self.error = "No connection: try the code again when you're online."
            }
            busy = false
        }
    }

    /// what the first sign-in said about the creator code that came with the install
    func signedInWithCode(_ result: String?, _ affiliate: JSON?) {
        guard let a = Affiliate.parse(affiliate) else { return }
        self.affiliate = a
        if result == "applied" { note = "Creator code \(a.code)" + (a.discountText.map { ": \($0)" } ?? "") + "." }
    }

    // ---------------------------------------------------------------- reporting
    /// Restore purchases: the App Store's transactions for this Apple account go to the server again.
    func restore() {
        busy = true
        error = nil
        note = nil
        Task { [self] in
            do { try await billing.sync() } catch { /* cancelled sign-in: what the phone has is still sent */ }
            await reportHeld()
            busy = false
        }
    }

    /// Every transaction the phone's Apple account holds goes to the server (Restore, after asking the App Store).
    func reportHeld() async {
        let all = await billing.current()
        var sent = 0
        for t in all { if await report(t, quiet: false) { sent += 1 } }
        if all.isEmpty { note = "The App Store has no subscription for this phone's Apple account." }
        else if sent > 0 { note = "Restored." }
    }

    /**
     * Tells the server about a transaction (it reads the rest from Apple), then finishes it. Not
     * reachable: it stays unfinished and is sent again. True when the server took it.
     */
    @discardableResult
    func report(_ t: StoreTransaction, quiet: Bool) async -> Bool {
        guard let token = account.store.token, let api = account.api() else { return false }
        // bought for another account of this phone: that account's business
        if let mine = status?.storeRef.flatMap(UUID.init(uuidString:)), let theirs = t.accountToken, mine != theirs {
            if !quiet { error = "This purchase belongs to another account." }
            return false
        }
        do {
            let o = try await api.storeApple(token: token, signedTransaction: t.jws)
            account.applyStatus(o)
            await t.finish()
            forget(t.id)
            return true
        } catch let e as AccountError {
            if e.lostToken { return false }
            if e.status >= 500 || e.error == "store_unavailable" { keep(t) }
            else {
                // the server said no (another account, not ours): it is done for this app
                await t.finish()
                forget(t.id)
                if !quiet { error = e.friendly }
            }
            return false
        } catch {
            keep(t)
            if !quiet { note = "No connection: the purchase is kept and sent as soon as there is one." }
            return false
        }
    }

    private func keep(_ t: StoreTransaction) {
        pendingFinish[t.id] = t
        waiting = pendingFinish.count
        retry?.cancel()
        retry = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 60_000_000_000)
            await self?.sendWaiting()
        }
    }

    private func forget(_ id: UInt64) {
        pendingFinish[id] = nil
        waiting = pendingFinish.count
    }

    /// the reports that didn't get through, again
    func sendWaiting() async {
        for t in Array(pendingFinish.values) { await report(t, quiet: true) }
    }

    func dismiss() { error = nil; note = nil }

    /// Apple's standard licence agreement (the app has no own terms)
    static let termsUrl = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
}
