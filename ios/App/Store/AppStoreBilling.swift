import Foundation
import StoreKit

/// A product as the App Store sells it: its price and billing period.
struct StoreProduct: Equatable {
    let id: String
    /// "€6.99"
    let displayPrice: String
    let price: Decimal
    /// "monthly", "yearly" (nil: not a subscription, or another period)
    let period: String?
    /// an introductory offer: "€4.99 / month for 3 months" (nil: none)
    var intro: String? = nil
    var groupId: String? = nil
}

/// A transaction of this phone's Apple account: the JWS the server checks, and how to finish it.
struct StoreTransaction {
    let id: UInt64
    let originalId: UInt64
    let productId: String
    /// the store_ref it was bought with (nil: bought without one, e.g. through an offer code)
    let accountToken: UUID?
    /// the signed transaction, as StoreKit 2 gives it
    let jws: String
    let revoked: Bool
    let finish: @Sendable () async -> Void
}

enum BuyOutcome {
    case bought(StoreTransaction)
    /// Ask to Buy, or a payment that finishes later (it arrives as an update)
    case pending
    case cancelled
    case failed(String)
}

/// Buying in the App Store; the app has the real one (StoreKit 2), the tests a stand-in.
protocol AppStoreBilling: AnyObject {
    func products(_ ids: [String]) async throws -> [String: StoreProduct]
    func buy(_ productId: String, accountToken: UUID) async -> BuyOutcome
    /// the subscriptions this phone's Apple account holds now (and transactions not finished yet)
    func current() async -> [StoreTransaction]
    /// Restore purchases: asks the App Store for this Apple account's transactions (it may ask to sign in)
    func sync() async throws
    /// transactions that arrive later (renewals, a pending payment that went through, another device's purchase)
    func listen(_ handler: @escaping @Sendable (StoreTransaction) async -> Void)
}

/// StoreKit 2.
final class StoreKitBilling: AppStoreBilling, @unchecked Sendable {
    private var cache: [String: Product] = [:]
    private var updates: Task<Void, Never>?
    private let lock = NSLock()

    deinit { updates?.cancel() }

    func products(_ ids: [String]) async throws -> [String: StoreProduct] {
        let found = try await Product.products(for: Set(ids))
        var out: [String: StoreProduct] = [:]
        lock.lock()
        for p in found { cache[p.id] = p }
        lock.unlock()
        for p in found {
            let sub = p.subscription
            var intro: String?
            if let o = sub?.introductoryOffer {
                let periods = o.periodCount
                intro = o.paymentMode == .freeTrial ? "free for \(Self.text(o.period, count: periods))"
                    : "\(o.displayPrice) / \(Self.text(o.period, count: 1)) for \(Self.text(o.period, count: periods))"
            }
            out[p.id] = StoreProduct(id: p.id, displayPrice: p.displayPrice, price: p.price, period: sub.flatMap { Self.period($0.subscriptionPeriod) },
                                     intro: intro, groupId: sub?.subscriptionGroupID)
        }
        return out
    }

    private static func period(_ p: Product.SubscriptionPeriod) -> String? {
        switch (p.unit, p.value) {
        case (.month, 1): return "monthly"
        case (.year, 1): return "yearly"
        default: return nil
        }
    }

    private static func text(_ p: Product.SubscriptionPeriod, count: Int) -> String {
        let n = p.value * count
        let unit: String
        switch p.unit {
        case .day: unit = "day"
        case .week: unit = "week"
        case .month: unit = "month"
        case .year: unit = "year"
        @unknown default: unit = "period"
        }
        return n == 1 ? unit : "\(n) \(unit)s"
    }

    func buy(_ productId: String, accountToken: UUID) async -> BuyOutcome {
        lock.lock()
        let cached = cache[productId]
        lock.unlock()
        let product: Product
        if let cached { product = cached } else {
            guard let p = try? await Product.products(for: [productId]).first else { return .failed("The App Store doesn't sell this right now.") }
            product = p
        }
        do {
            switch try await product.purchase(options: [.appAccountToken(accountToken)]) {
            case .success(let v): return .bought(Self.transaction(v))
            case .pending: return .pending
            case .userCancelled: return .cancelled
            @unknown default: return .cancelled
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    static func transaction(_ v: VerificationResult<Transaction>) -> StoreTransaction {
        let t: Transaction
        switch v {
        case .verified(let x): t = x
        case .unverified(let x, _): t = x
        }
        return StoreTransaction(id: t.id, originalId: t.originalID, productId: t.productID, accountToken: t.appAccountToken,
                                jws: v.jwsRepresentation, revoked: t.revocationDate != nil, finish: { await t.finish() })
    }

    func current() async -> [StoreTransaction] {
        var out: [StoreTransaction] = []
        var seen = Set<UInt64>()
        for await v in Transaction.currentEntitlements {
            let t = Self.transaction(v)
            if seen.insert(t.id).inserted { out.append(t) }
        }
        for await v in Transaction.unfinished {
            let t = Self.transaction(v)
            if seen.insert(t.id).inserted { out.append(t) }
        }
        return out
    }

    func sync() async throws { try await AppStore.sync() }

    func listen(_ handler: @escaping @Sendable (StoreTransaction) async -> Void) {
        updates?.cancel()
        updates = Task.detached {
            for await v in Transaction.updates { await handler(Self.transaction(v)) }
        }
    }
}
