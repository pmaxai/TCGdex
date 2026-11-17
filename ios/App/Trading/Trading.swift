import Foundation

/// Cardmarket's condition scale; share = part of the reference price a card in that condition is worth.
enum Condition: String, Codable, CaseIterable, Identifiable {
    case NM, EX, GD, LP, PL, PO
    var id: String { rawValue }
    var label: String { rawValue }
    var share: Double {
        switch self {
        case .NM: return 1.00
        case .EX: return 0.85
        case .GD: return 0.75
        case .LP: return 0.65
        case .PL: return 0.50
        case .PO: return 0.30
        }
    }
}

enum LineStatus: String, Codable, CaseIterable {
    case CONSIDERING, AGREED, BOUGHT, DROPPED
    var label: String { ["CONSIDERING": "Considering", "AGREED": "Agreed", "BOUGHT": "Bought", "DROPPED": "Dropped"][rawValue]! }
}

/// Asking (or negotiated) price against the market value.
enum Verdict: String {
    case GREAT, GOOD, FAIR, EXPENSIVE
    var label: String { ["GREAT": "Great deal", "GOOD": "Good price", "FAIR": "Fair", "EXPENSIVE": "Expensive"][rawValue]! }
}

/// Your offer rated as the vendor buying from you: vendors usually pay 75-90 % of the market value.
enum SellVerdict: String {
    case LOW, USUAL, GOOD
    var label: String { ["LOW": "Low offer", "USUAL": "Usual offer", "GOOD": "Good offer"][rawValue]! }
}

/// Which side of a deal a card is on: cards you get (buy or trade in) or cards you give (sell or trade away).
enum Side: String, Codable {
    case GET, GIVE
    var label: String { self == .GET ? "I get" : "I give" }
}

/// Which Cardmarket price the market value starts from.
enum Reference: String, CaseIterable, Identifiable {
    case TREND, AVG30, LOW
    var id: String { rawValue }
    var label: String { ["TREND": "Trend", "AVG30": "30-day average", "LOW": "Lowest price"][rawValue]! }
}

/// A card show (or a day of trading): its name and the budget for it.
struct ShowInfo: Codable, Equatable, Identifiable {
    let id: Int64
    var name: String
    let started: Int64
    var budget: Double?
}

/**
 * The numbers of Trading Mode, in one place (as TradingMath.kt): market value for a condition, the
 * verdict on a price, the opening offer and the walk-away price.
 */
enum TradingMath {
    /// opening offer = market value x this
    static let offerShare = 0.85
    /// walk away above market value x this
    static let walkAwayShare = 1.00
    /// asking price / market value: up to 80 % great, 95 % good, 110 % fair, above expensive
    static let greatUpTo = 0.80, goodUpTo = 0.95, fairUpTo = 1.10
    /// vendor's offer for your card / market value: under 75 % low, up to 90 % usual, above good
    static let sellUsualFrom = 0.75, sellGoodAbove = 0.90
    /// trade rates: your cards count at 80 % of their market value, the vendor's at 100 %
    static let giveRate = 0.80, getRate = 1.00
    /// the budget bar turns orange from here
    static let budgetWarn = 0.90
    private static let eps = 1e-9

    /// to the cent, halves away from zero (as Kotlin's roundToLong for positive values)
    static func round2(_ v: Double) -> Double { (v * 100).rounded(.toNearestOrAwayFromZero) / 100 }

    /// The Cardmarket price of a printing the market value starts from (falls back to the trend).
    static func reference(_ p: Printing?, _ ref: Reference) -> Double? {
        let v: Double?
        switch ref {
        case .TREND: v = p?.eur
        case .AVG30: v = p?.eur30 ?? p?.eur
        case .LOW: v = p?.eurLow ?? p?.eur
        }
        return v.flatMap { $0 > 0 ? $0 : nil }
    }

    static func market(_ reference: Double, _ condition: Condition, languageFactor: Double = 1) -> Double {
        round2(reference * condition.share * languageFactor)
    }

    static func verdict(_ price: Double, _ market: Double) -> Verdict? {
        if market <= 0 || price < 0 { return nil }
        let r = price / market
        if r <= greatUpTo + eps { return .GREAT }
        if r <= goodUpTo + eps { return .GOOD }
        if r <= fairUpTo + eps { return .FAIR }
        return .EXPENSIVE
    }

    /// market value x 85 %, rounded down: to 10 cents under 5 EUR, whole euros under 50, 5 EUR steps above
    static func offer(_ market: Double) -> Double {
        let t = market * offerShare + eps
        if t < 5 { return round2((t * 10).rounded(.down) / 10) }
        if t < 50 { return round2(t.rounded(.down)) }
        return round2((t / 5).rounded(.down) * 5)
    }

    static func walkAway(_ market: Double) -> Double { round2(market * walkAwayShare) }

    static func sellVerdict(_ offer: Double, _ market: Double) -> SellVerdict? {
        if market <= 0 || offer < 0 { return nil }
        let r = offer / market
        if r < sellUsualFrom - eps { return .LOW }
        if r <= sellGoodAbove + eps { return .USUAL }
        return .GOOD
    }

    /// what vendors usually pay for a card of this market value
    static func sellRange(_ market: Double) -> (Double, Double) { (round2(market * sellUsualFrom), round2(market * sellGoodAbove)) }

    /**
     * Splits total over items in proportion to weights (their market values), to the cent, summing
     * exactly to the total: the cents left after rounding down go to the items with the largest
     * remainders. No positive weight: split evenly.
     */
    static func split(_ total: Double, _ weights: [Double]) -> [Double] {
        if weights.isEmpty { return [] }
        let w = weights.map { $0 > 0 ? $0 : 0 }
        let sum = w.reduce(0, +)
        let share = sum > 0 ? w.map { $0 / sum } : Array(repeating: 1.0 / Double(w.count), count: w.count)
        let cents = Int64((total * 100).rounded(.toNearestOrAwayFromZero))
        let raw = share.map { $0 * Double(cents) }
        var out = raw.map { Int64(($0 + eps).rounded(.down)) }
        var rest = cents - out.reduce(0, +)
        // largest remainder first; on a tie the earlier item (a stable sort, as Kotlin's)
        let order = raw.indices.sorted { a, b in
            let ra = raw[a] - Double(out[a]), rb = raw[b] - Double(out[b])
            return ra != rb ? ra > rb : a < b
        }
        for i in order where rest > 0 {
            out[i] += 1
            rest -= 1
        }
        return out.map { Double($0) / 100 }
    }

    /// trend against the 30-day average (+0.12 = 12 % up), nil without both
    static func trend(_ p: Printing?) -> Double? {
        guard let now = p?.eur, let avg = p?.eur30, avg > 0 else { return nil }
        return (now - avg) / avg
    }

    /// few recent sales: no 30-day average, or the lowest offer under half the trend
    static func fewSales(_ p: Printing?) -> Bool {
        guard let p, let now = p.eur else { return false }
        return p.eur30 == nil || (p.eurLow.map { $0 < 0.5 * now } ?? false)
    }
}

/// One card in a cart. Prices are per piece, in EUR; reference is kept so the condition can change later.
struct CartLine: Codable, Equatable, Identifiable {
    let id: Int64
    var cardId: String
    var lang: String
    var group: String
    var name: String
    var setLine: String
    var image: String?
    var printing: String?
    var reference: Double?
    var condition: Condition
    var qty: Int = 1
    var asking: Double?
    var negotiated: Double?
    var status: LineStatus = .CONSIDERING
    var added: Int64
    /// I get (buy, trade in) or I give (sell, trade away); for I give, asking = the vendor's offer
    var side: Side = .GET
    /// the Cardmarket price was in doubt (price check): no deal verdict on it
    var priceDoubt = false

    var market: Double? { reference.map { TradingMath.market($0, condition) } }
    /// one piece: the negotiated price, else the asking price (I give: the vendor's offer)
    var price: Double? { negotiated ?? asking }
    /// buying: the price against the market value
    var verdict: Verdict? {
        guard side == .GET, !priceDoubt, let p = price, let m = market else { return nil }
        return TradingMath.verdict(p, m)
    }
    /// selling: the vendor's offer against the market value
    var sellVerdict: SellVerdict? {
        guard side == .GIVE, !priceDoubt, let p = price, let m = market else { return nil }
        return TradingMath.sellVerdict(p, m)
    }
    var active: Bool { status != .DROPPED }
    var printingLabel: String? {
        printing.flatMap { k in Printing.Kind.allCases.first { Self.code($0) == k }?.rawValue }
    }

    /// the printing's name as the Android app stores it (NORMAL, HOLO, REVERSE, FIRST_EDITION)
    static func code(_ k: Printing.Kind) -> String {
        switch k {
        case .normal: return "NORMAL"
        case .holo: return "HOLO"
        case .reverse: return "REVERSE"
        case .firstEdition: return "FIRST_EDITION"
        }
    }

    enum CodingKeys: String, CodingKey {
        case id, cardId, lang, group, name, setLine, image, printing, reference, condition, qty, asking, negotiated, status, added, side, priceDoubt
    }

    init(id: Int64, cardId: String, lang: String, group: String, name: String, setLine: String, image: String?, printing: String?,
         reference: Double?, condition: Condition, qty: Int = 1, asking: Double? = nil, negotiated: Double? = nil,
         status: LineStatus = .CONSIDERING, added: Int64, side: Side = .GET, priceDoubt: Bool = false) {
        self.id = id; self.cardId = cardId; self.lang = lang; self.group = group; self.name = name; self.setLine = setLine
        self.image = image; self.printing = printing; self.reference = reference; self.condition = condition; self.qty = qty
        self.asking = asking; self.negotiated = negotiated; self.status = status; self.added = added; self.side = side; self.priceDoubt = priceDoubt
    }

    /// older files lack the newer fields: they get their defaults
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        cardId = try c.decodeIfPresent(String.self, forKey: .cardId) ?? ""
        lang = try c.decodeIfPresent(String.self, forKey: .lang) ?? ""
        group = try c.decodeIfPresent(String.self, forKey: .group) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        setLine = try c.decodeIfPresent(String.self, forKey: .setLine) ?? ""
        image = try c.decodeIfPresent(String.self, forKey: .image)
        printing = try c.decodeIfPresent(String.self, forKey: .printing)
        reference = try c.decodeIfPresent(Double.self, forKey: .reference)
        condition = (try? c.decode(Condition.self, forKey: .condition)) ?? .NM
        qty = try c.decodeIfPresent(Int.self, forKey: .qty) ?? 1
        asking = try c.decodeIfPresent(Double.self, forKey: .asking)
        negotiated = try c.decodeIfPresent(Double.self, forKey: .negotiated)
        status = (try? c.decode(LineStatus.self, forKey: .status)) ?? .CONSIDERING
        added = try c.decodeIfPresent(Int64.self, forKey: .added) ?? 0
        side = (try? c.decode(Side.self, forKey: .side)) ?? .GET
        priceDoubt = try c.decodeIfPresent(Bool.self, forKey: .priceDoubt) ?? false
    }
}

/**
 * The cart of one booth (one vendor). Paid carts are the deal history. A trade adds the I give side:
 * each side counts at its rate (your cards 80 %, the vendor's 100 % of market value) unless a card
 * has its own price, and the balance is the cash difference.
 */
struct Cart: Codable, Equatable, Identifiable {
    let id: Int64
    var name: String
    let created: Int64
    var lines: [CartLine] = []
    var paidAt: Int64?
    var payment: String?
    /// the I give side is open
    var trade = false
    var giveRate = TradingMath.giveRate
    var getRate = TradingMath.getRate
    /// cash agreed in the end: + you pay, - the vendor pays you (nil: the balance)
    var cash: Double?
    var showId: Int64?

    init(id: Int64, name: String, created: Int64, lines: [CartLine] = [], paidAt: Int64? = nil, payment: String? = nil, trade: Bool = false,
         giveRate: Double = TradingMath.giveRate, getRate: Double = TradingMath.getRate, cash: Double? = nil, showId: Int64? = nil) {
        self.id = id; self.name = name; self.created = created; self.lines = lines; self.paidAt = paidAt; self.payment = payment
        self.trade = trade; self.giveRate = giveRate; self.getRate = getRate; self.cash = cash; self.showId = showId
    }

    enum CodingKeys: String, CodingKey { case id, name, created, lines, paidAt, payment, trade, giveRate, getRate, cash, showId }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        created = try c.decodeIfPresent(Int64.self, forKey: .created) ?? 0
        lines = try c.decodeIfPresent([CartLine].self, forKey: .lines) ?? []
        paidAt = try c.decodeIfPresent(Int64.self, forKey: .paidAt)
        payment = try c.decodeIfPresent(String.self, forKey: .payment)
        trade = try c.decodeIfPresent(Bool.self, forKey: .trade) ?? false
        giveRate = try c.decodeIfPresent(Double.self, forKey: .giveRate) ?? TradingMath.giveRate
        getRate = try c.decodeIfPresent(Double.self, forKey: .getRate) ?? TradingMath.getRate
        cash = try c.decodeIfPresent(Double.self, forKey: .cash)
        showId = try c.decodeIfPresent(Int64.self, forKey: .showId)
    }

    private static func sum<T: Sequence>(_ s: T, _ f: (T.Element) -> Double) -> Double { s.reduce(0) { $0 + f($1) } }

    var activeLines: [CartLine] { lines.filter(\.active) }
    var getLines: [CartLine] { activeLines.filter { $0.side == .GET } }
    var giveLines: [CartLine] { activeLines.filter { $0.side == .GIVE } }
    var isTrade: Bool { trade || lines.contains { $0.side == .GIVE } }

    /// cards you get
    var cards: Int { getLines.reduce(0) { $0 + $1.qty } }
    var giveCards: Int { giveLines.reduce(0) { $0 + $1.qty } }
    var marketTotal: Double { TradingMath.round2(Self.sum(getLines) { ($0.market ?? 0) * Double($0.qty) }) }
    var marketGive: Double { TradingMath.round2(Self.sum(giveLines) { ($0.market ?? 0) * Double($0.qty) }) }
    var askingTotal: Double { TradingMath.round2(Self.sum(getLines) { ($0.asking ?? 0) * Double($0.qty) }) }
    /// buying: what you pay, negotiated (else asking) prices of the cards you get
    var payTotal: Double { TradingMath.round2(Self.sum(getLines) { ($0.price ?? 0) * Double($0.qty) }) }
    /// market value minus price, over the cards you get that have both
    var savedVsMarket: Double {
        var sum = 0.0
        for l in getLines { if let p = l.price, let m = l.market { sum += (m - p) * Double(l.qty) } }
        return TradingMath.round2(sum)
    }
    /// market value of the cards you get that have a price (the base of the saved share)
    var pricedMarket: Double {
        var sum = 0.0
        for l in getLines { if l.price != nil, let m = l.market { sum += m * Double(l.qty) } }
        return TradingMath.round2(sum)
    }
    var savedVsAsking: Double {
        var sum = 0.0
        for l in getLines { if let a = l.asking, let p = l.price { sum += (a - p) * Double(l.qty) } }
        return TradingMath.round2(sum)
    }
    /// cards you get that nobody has put a price on yet (in a trade they count at the rate)
    var unpriced: Int { isTrade ? 0 : getLines.filter { $0.price == nil }.count }
    /// I get: own prices, else (trade) market value x rate
    var getValue: Double {
        let trade = isTrade, rate = getRate
        var sum = 0.0
        for l in getLines {
            let each: Double = l.price ?? (trade ? (l.market ?? 0) * rate : 0)
            sum += each * Double(l.qty)
        }
        return TradingMath.round2(sum)
    }
    /// I give: the vendor's offer, else market value x rate
    var giveValue: Double {
        let rate = giveRate
        var sum = 0.0
        for l in giveLines {
            let each: Double = l.price ?? (l.market ?? 0) * rate
            sum += each * Double(l.qty)
        }
        return TradingMath.round2(sum)
    }
    /// + you pay, - the vendor pays you
    var balance: Double { TradingMath.round2(getValue - giveValue) }
    /// the cash that changes hands: as agreed, else the balance
    var settle: Double { cash ?? balance }
    /// at market value: what you get minus what you give, cards and cash
    var netAtMarket: Double { TradingMath.round2(marketTotal - marketGive - settle) }
    /// the active cards (both sides) that have no market value
    var withoutMarket: Int { activeLines.filter { $0.market == nil }.count }
}

/// Totals of several deals (a show, a day).
struct DealSummary: Equatable {
    let deals: Int
    let spent: Double, received: Double, marketIn: Double, marketOut: Double, saved: Double
    let bought: Int, given: Int

    static func of(_ carts: [Cart]) -> DealSummary {
        var spent = 0.0, received = 0.0, marketIn = 0.0, marketOut = 0.0, saved = 0.0
        var bought = 0, given = 0
        for c in carts {
            let s = c.settle
            spent += max(s, 0)
            received += max(-s, 0)
            marketIn += c.marketTotal
            marketOut += c.marketGive
            saved += c.savedVsMarket
            bought += c.cards
            given += c.giveCards
        }
        return DealSummary(deals: carts.count, spent: TradingMath.round2(spent), received: TradingMath.round2(received),
                           marketIn: TradingMath.round2(marketIn), marketOut: TradingMath.round2(marketOut), saved: TradingMath.round2(saved),
                           bought: bought, given: given)
    }
}

/// All carts: open ones (one is active) and paid ones (the deal history).
struct TradingBook: Codable, Equatable {
    var version = 1
    var carts: [Cart] = []
    var activeId: Int64?
    var nextId: Int64 = 1
    var shows: [ShowInfo] = []
    /// the show new carts belong to
    var showId: Int64?

    init(carts: [Cart] = [], activeId: Int64? = nil, nextId: Int64 = 1, shows: [ShowInfo] = [], showId: Int64? = nil) {
        self.carts = carts; self.activeId = activeId; self.nextId = nextId; self.shows = shows; self.showId = showId
    }

    enum CodingKeys: String, CodingKey { case version, carts, activeId, nextId, shows, showId }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        carts = try c.decodeIfPresent([Cart].self, forKey: .carts) ?? []
        activeId = try c.decodeIfPresent(Int64.self, forKey: .activeId)
        nextId = try c.decodeIfPresent(Int64.self, forKey: .nextId) ?? 1
        shows = try c.decodeIfPresent([ShowInfo].self, forKey: .shows) ?? []
        showId = try c.decodeIfPresent(Int64.self, forKey: .showId)
    }

    var open: [Cart] { carts.filter { $0.paidAt == nil } }
    var paid: [Cart] { carts.filter { $0.paidAt != nil }.sorted { ($0.paidAt ?? 0) > ($1.paidAt ?? 0) } }
    var active: Cart? { open.first { $0.id == activeId } ?? open.last }
    var show: ShowInfo? { shows.first { $0.id == showId } }

    /// spent at the current show: cash paid in its closed deals
    var spent: Double {
        var sum = 0.0
        for c in paid where c.showId != nil && c.showId == showId { sum += max(c.settle, 0) }
        return TradingMath.round2(sum)
    }
    /// agreed but not paid yet at the current show: agreed cards you get in its open carts
    var agreed: Double {
        var sum = 0.0
        for c in open where c.showId != nil && c.showId == showId {
            for l in c.getLines where l.status == .AGREED { sum += (l.price ?? 0) * Double(l.qty) }
        }
        return TradingMath.round2(sum)
    }

    /// a new show (or day): new carts belong to it; carts still open move along
    func startShow(_ name: String, budget: Double?, now: Int64) -> TradingBook {
        var b = self
        let sh = ShowInfo(id: nextId, name: name, started: now, budget: budget)
        b.shows.append(sh)
        b.showId = sh.id
        b.nextId += 1
        b.carts = carts.map { c in var x = c; if x.paidAt == nil { x.showId = sh.id }; return x }
        return b
    }

    func updateShow(_ name: String, budget: Double?) -> TradingBook {
        var b = self
        b.shows = shows.map { s in var x = s; if x.id == showId { x.name = name; x.budget = budget }; return x }
        return b
    }

    func replace(_ cart: Cart) -> TradingBook {
        var b = self
        b.carts = carts.map { $0.id == cart.id ? cart : $0 }
        return b
    }

    /// a new, empty cart named after the next booth number; it becomes the active one
    func newCart(now: Int64) -> TradingBook {
        let n = (carts.compactMap { c -> Int? in
            guard c.name.hasPrefix("Booth "), let k = Int(c.name.dropFirst(6)), String(k) == String(c.name.dropFirst(6)) else { return nil }
            return k
        }.max() ?? 0) + 1
        var b = self
        let c = Cart(id: nextId, name: "Booth \(n)", created: now, showId: showId)
        b.carts.append(c)
        b.activeId = c.id
        b.nextId += 1
        return b
    }

    /// the active cart, created when there is none
    func withActive(now: Int64) -> (TradingBook, Cart) {
        if let a = active { return (self, a) }
        let b = newCart(now: now)
        return (b, b.active!)
    }
}

/// Carts on the phone: one JSON file, written whole and swapped in, so a crash never leaves half a file.
final class TradingStore {
    let file: URL
    private let lock = NSLock()

    init(file: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("trading.json")) {
        self.file = file
    }

    func load() -> TradingBook {
        guard let d = try? Data(contentsOf: file) else { return TradingBook() }
        return (try? JSONDecoder().decode(TradingBook.self, from: d)) ?? TradingBook()
    }

    func save(_ book: TradingBook) {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let d = try? JSONEncoder().encode(book) { try? d.write(to: file, options: .atomic) }
    }
}

/**
 * Deals (carts) as CSV: one row per card with condition, market value, asking and negotiated price,
 * verdict, vendor and date, then each cart's totals. ';' and decimal commas where the phone uses a
 * comma, UTF-8 BOM (as on Android).
 */
enum TradingCsv {
    static let header = ["Booth", "Paid", "Payment", "Status", "Name", "Set / number / language / year", "Card ID", "Language",
                         "Printing", "Condition", "Qty", "Market value each EUR", "Asking each EUR", "Negotiated each EUR",
                         "You pay EUR", "Verdict", "Side"]

    static func build(_ carts: [Cart], locale: Locale, shows: [ShowInfo] = []) -> String {
        let decimalComma = (locale.decimalSeparator ?? ".") == ","
        let sep: Character = decimalComma ? ";" : ","
        func num(_ v: Double?) -> String {
            guard let v else { return "" }
            let s = String(format: "%.2f", v)
            return decimalComma ? s.replacingOccurrences(of: ".", with: ",") : s
        }
        // a value as text; an optional inside Any is unwrapped ("" for none)
        func flat(_ v: Any?) -> String {
            guard let v else { return "" }
            let m = Mirror(reflecting: v)
            if m.displayStyle == .optional { return m.children.first.map { flat($0.value) } ?? "" }
            return "\(v)"
        }
        func cell(_ v: Any?) -> String {
            let t = flat(v)
            return t.contains(where: { $0 == sep || $0 == "\"" || $0 == "\n" || $0 == "\r" }) ? "\"" + t.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : t
        }
        let time = DateFormatter()
        time.locale = locale
        time.dateFormat = "yyyy-MM-dd HH:mm"
        func when(_ ms: Int64) -> String { time.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000)) }
        var out = "\u{FEFF}"
        func row(_ values: [Any?]) { out += values.map(cell).joined(separator: String(sep)) + "\r\n" }
        row(header)
        for c in carts {
            let paid = c.paidAt.map(when) ?? ""
            for l in c.lines {
                let pay: String = l.active && l.side == .GET ? num(l.price.map { $0 * Double(l.qty) }) : ""
                let verdict: String? = l.verdict?.label ?? l.sellVerdict?.label
                let values: [Any?] = [c.name, paid, c.payment, l.status.label, l.name, l.setLine, l.cardId, l.lang, l.printingLabel, l.condition.label,
                                      l.qty, num(l.market), num(l.asking), num(l.negotiated), pay, verdict, l.side.label]
                row(values)
            }
        }
        out += "\r\n"
        row(["Booth", "Paid", "Cards", "Market value EUR", "Asking EUR", "You pay EUR", "Saved vs market EUR", "Cards given",
             "Market value given EUR", "Your cards count at %", "Trade balance EUR", "Cash paid (+) / received (-) EUR", "Show"])
        for c in carts {
            let t = c.isTrade
            let rate: String = t ? String(Int((c.giveRate * 100).rounded())) : ""
            let paid: String = c.paidAt.map(when) ?? "open"
            let show: String? = shows.first { $0.id == c.showId }?.name
            let values: [Any?] = [c.name, paid, c.cards, num(c.marketTotal), num(c.askingTotal), num(c.payTotal), num(c.savedVsMarket),
                                  t ? String(c.giveCards) : "", t ? num(c.marketGive) : "", rate, t ? num(c.balance) : "", num(c.settle), show]
            row(values)
        }
        if carts.count > 1 {
            var cards = 0, given = 0
            var market = 0.0, asking = 0.0, pay = 0.0, saved = 0.0, marketGive = 0.0, settle = 0.0
            for c in carts {
                cards += c.cards; given += c.giveCards
                market += c.marketTotal; asking += c.askingTotal; pay += c.payTotal
                saved += c.savedVsMarket; marketGive += c.marketGive; settle += c.settle
            }
            let all: [Any?] = ["All", "", cards, num(market), num(asking), num(pay), num(saved), given, num(marketGive), "", "", num(settle), ""]
            row(all)
        }
        return out
    }

    static func fileName(_ now: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd_HHmm"
        return "deals_\(f.string(from: now)).csv"
    }
}
