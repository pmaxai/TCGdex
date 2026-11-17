import Foundation
import SwiftUI

/**
 * Trading Mode (card shows), the Android app's trading part of ScannerViewModel: the card the
 * scanner shows with its market value for a condition, the vendor's price and a verdict, carts per
 * booth, trades (I give), shows with a budget, and the deal history.
 */
@MainActor
final class TradingModel: ObservableObject {
    @Published private(set) var book: TradingBook
    /// the condition and the vendor's price for the card shown now
    @Published var condition: Condition = .NM
    @Published var asking: Double?
    /// which side + Cart puts the next card on
    @Published var side: Side = .GET
    /// the last scanned cards (to bring one back)
    @Published private(set) var tray: [Shown] = []
    @Published var message: String?
    /// a cart just paid whose cards could go into the collection
    @Published var offerCart: Int64?
    @Published var reference: Reference { didSet { UserDefaults.standard.set(reference.rawValue, forKey: "trading.reference") } }
    @Published var startCondition: Condition { didSet { UserDefaults.standard.set(startCondition.rawValue, forKey: "trading.condition") } }
    /// what one tap on − / + beside a price changes it by (EUR)
    @Published var priceStep: Double { didSet { UserDefaults.standard.set(priceStep, forKey: "trading.step") } }

    private let store: TradingStore
    static let traySize = 6

    init(store: TradingStore = TradingStore()) {
        self.store = store
        book = store.load()
        let d = UserDefaults.standard
        reference = d.string(forKey: "trading.reference").flatMap(Reference.init(rawValue:)) ?? .TREND
        let start = d.string(forKey: "trading.condition").flatMap(Condition.init(rawValue:)) ?? .NM
        startCondition = start
        priceStep = d.object(forKey: "trading.step") != nil ? d.double(forKey: "trading.step") : 0.5
        condition = start
    }

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// A new card was shown: a fresh draft, and it goes into the tray.
    func shown(_ s: Shown) {
        condition = startCondition
        asking = nil
        if tray.last?.group != s.group { tray = Array((tray + [s]).suffix(Self.traySize)) }
        else { tray[tray.count - 1] = s }
    }

    func market(_ s: Shown) -> Double? { TradingMath.reference(s.selected, reference).map { TradingMath.market($0, condition) } }

    private func update(_ f: (TradingBook) -> TradingBook) {
        book = f(book)
        store.save(book)
    }

    private func updateCart(_ id: Int64, _ f: (Cart) -> Cart) {
        update { b in b.carts.first { $0.id == id }.map { b.replace(f($0)) } ?? b }
    }

    /// + Cart (or + I give): the shown card in the active cart; the same card again counts up
    func addToCart(_ s: Shown) {
        let now = Self.now()
        var cartName = ""
        let printing = s.selected.map { CartLine.code($0.kind) }
        let ref = TradingMath.reference(s.selected, reference)
        let side = self.side, condition = self.condition, asking = self.asking
        update { b0 in
            let (b, cart) = b0.withActive(now: now)
            cartName = cart.name
            var lines = cart.lines
            if let last = lines.last, last.status == .CONSIDERING, last.cardId == s.version.id, last.lang == s.version.lang,
               last.printing == printing, last.condition == condition, last.asking == asking, last.side == side {
                lines[lines.count - 1].qty += 1
            } else {
                lines.append(CartLine(id: now, cardId: s.version.id, lang: s.version.lang, group: s.group, name: s.version.name,
                                      setLine: s.version.setLine(), image: s.imageCard?.thumbnailUrl, printing: printing, reference: ref,
                                      condition: condition, asking: asking, added: now, side: side,
                                      priceDoubt: ref != nil && s.priceCard.priceCheck != nil))
            }
            var c = cart
            c.lines = lines
            c.trade = cart.trade || side == .GIVE
            return b.replace(c)
        }
        self.asking = nil
        message = "Added to " + (side == .GIVE ? "I give (\(cartName))" : cartName)
    }

    func selectCart(_ id: Int64) { update { var b = $0; b.activeId = id; return b } }
    func newCart() { update { $0.newCart(now: Self.now()) } }
    func renameCart(_ id: Int64, _ name: String) {
        let n = name.trimmingCharacters(in: .whitespaces)
        updateCart(id) { var c = $0; if !n.isEmpty { c.name = String(n.prefix(60)) }; return c }
    }
    func deleteCart(_ id: Int64) { update { var b = $0; b.carts.removeAll { $0.id == id }; return b } }

    func setTrade(_ id: Int64, _ on: Bool) {
        updateCart(id) { c in
            if !on && c.lines.contains(where: { $0.side == .GIVE && $0.active }) { return c }
            var x = c
            x.trade = on
            if !on { x.cash = nil }
            return x
        }
    }

    func setRates(_ id: Int64, give: Double? = nil, get: Double? = nil) {
        updateCart(id) { var c = $0; if let give { c.giveRate = give }; if let get { c.getRate = get }; return c }
    }

    func setCash(_ id: Int64, _ cash: Double?) { updateCart(id) { var c = $0; c.cash = cash.map(TradingMath.round2); return c } }

    func updateLine(_ cartId: Int64, _ lineId: Int64, _ f: (CartLine) -> CartLine) {
        updateCart(cartId) { var c = $0; c.lines = c.lines.map { $0.id == lineId ? f($0) : $0 }; return c }
    }

    /// One price for several cards: split over them in proportion to their market values, to the cent.
    func agreeTotal(_ cartId: Int64, _ lineIds: [Int64], _ total: Double) {
        updateCart(cartId) { c in
            let chosen = c.lines.filter { lineIds.contains($0.id) && $0.active }
            if chosen.isEmpty { return c }
            let shares = TradingMath.split(total, chosen.map { ($0.market ?? $0.asking ?? 0) * Double($0.qty) })
            var each: [Int64: Double] = [:]
            for (l, t) in zip(chosen, shares) { each[l.id] = t / Double(l.qty) }
            var x = c
            x.lines = c.lines.map { l in
                guard let v = each[l.id] else { return l }
                var y = l; y.negotiated = v; y.status = .AGREED; return y
            }
            return x
        }
    }

    func startShow(_ name: String, budget: Double?) { update { $0.startShow(name, budget: budget, now: Self.now()) } }
    func updateShow(_ name: String, budget: Double?) { update { $0.updateShow(name, budget: budget) } }

    /// Paid (or Done for a trade): the cart goes into the deal history.
    func markPaid(_ cartId: Int64, _ payment: String, collection: Bool) {
        update { b in
            guard var c = b.carts.first(where: { $0.id == cartId }) else { return b }
            c.paidAt = Self.now()
            c.payment = payment
            c.lines = c.lines.map { l in var x = l; if x.active { x.status = .BOUGHT }; return x }
            var next = b.replace(c)
            next.activeId = next.open.last?.id
            return next
        }
        // the cards you got: offer them for the collection
        if collection, let c = book.carts.first(where: { $0.id == cartId }), c.lines.contains(where: { $0.side == .GET && $0.status == .BOUGHT }) {
            offerCart = cartId
        }
    }

    func reopen(_ cartId: Int64) {
        update { b in
            guard var c = b.carts.first(where: { $0.id == cartId }) else { return b }
            c.paidAt = nil
            c.payment = nil
            c.lines = c.lines.map { l in var x = l; if x.status == .BOUGHT { x.status = .AGREED }; return x }
            var next = b.replace(c)
            next.activeId = cartId
            return next
        }
    }

    /// The bought cards of a paid cart into the collection (each with its condition and price).
    func addCartToCollection(_ cartId: Int64, account: AccountModel) {
        offerCart = nil
        guard let c = book.carts.first(where: { $0.id == cartId }) else { return }
        for l in c.lines where l.side == .GET && l.status == .BOUGHT {
            let finish: String
            switch l.printing {
            case "HOLO": finish = "holo"
            case "REVERSE": finish = "reverse_holo"
            case "FIRST_EDITION": finish = "first_edition"
            default: finish = "normal"
            }
            account.addToCollection(cardId: l.cardId, language: ShopTerms.languageOf(l.lang), finish: finish, condition: l.condition.rawValue,
                                    quantity: l.qty, purchaseCents: l.price.map { Int64(($0 * 100).rounded()) }, label: "\(l.name) · \(l.setLine)")
        }
    }

    /// the deals as a CSV file to share
    func csvFile(_ carts: [Cart]) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(TradingCsv.fileName())
        do {
            try TradingCsv.build(carts, locale: .current, shows: book.shows).write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch { return nil }
    }
}
