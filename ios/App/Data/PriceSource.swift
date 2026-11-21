import Foundation

/**
 * Where a price comes from. Cardmarket (EUR) and TCGplayer (USD) are in every plan; the person
 * picks the one the app shows first (Settings › Prices): the headline of a card, the lists, the
 * price alert and Trading's market value. A card without a price from it shows the next one in
 * [fallback].
 */
enum PriceSource: String, CaseIterable, Identifiable {
    case cmTrend, cmAvg30, cmAvg, cmLow, tcgMarket, tcgMid, tcgLow

    var id: String { rawValue }

    /// "Cardmarket trend"
    var label: String { "\(provider) \(short)" }

    /// "trend", in the price table under its provider
    var short: String {
        switch self {
        case .cmTrend: return "trend"
        case .cmAvg30: return "30-day average"
        case .cmAvg: return "average sell price"
        case .cmLow: return "lowest offer"
        case .tcgMarket: return "market price"
        case .tcgMid: return "mid price"
        case .tcgLow: return "low price"
        }
    }

    var provider: String { isUSD ? "TCGplayer" : "Cardmarket" }

    /// TCGplayer gives USD (shown in EUR at the data pack's rate as well)
    var isUSD: Bool { self == .tcgMarket || self == .tcgMid || self == .tcgLow }

    static let key = "price.source"

    /// the person's choice (Cardmarket trend until they pick another)
    static var current: PriceSource { UserDefaults.standard.string(forKey: key).flatMap(PriceSource.init(rawValue:)) ?? .cmTrend }

    /// when the chosen source has no price for a card: the next ones, in this order
    static let fallback: [PriceSource] = [.cmTrend, .cmAvg30, .cmAvg, .tcgMarket, .tcgMid, .cmLow, .tcgLow]
}
