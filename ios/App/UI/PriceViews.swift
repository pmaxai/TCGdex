import SwiftUI

/// The price of a printing in the person's default source (or the next one with a price), large.
struct PriceHeadline: View {
    let p: Printing
    @AppStorage(PriceSource.key) private var sourceName = PriceSource.cmTrend.rawValue

    var body: some View {
        let def = PriceSource(rawValue: sourceName) ?? .cmTrend
        VStack(alignment: .leading, spacing: 2) {
            if let shown = p.shown(def) {
                HStack(alignment: .lastTextBaseline, spacing: 8) {
                    Text(shown.text).font(.system(size: 30, weight: .bold)).foregroundStyle(Theme.gold)
                    Text("\(shown.source.label) · \(p.kind.rawValue)").font(.caption).foregroundStyle(Theme.muted)
                }
                if shown.source != def {
                    Text("No \(def.label) for this card – \(shown.source.label) shown").font(.caption2).foregroundStyle(Theme.muted)
                }
            } else {
                Text("No price for this card.").foregroundStyle(Theme.muted)
            }
        }
    }
}

/// Every source's price of one printing, grouped by provider; the default source marked ★.
struct PriceSourcesTable: View {
    struct Provider { let name: String; let sources: [PriceSource] }
    static let providers = [Provider(name: "Cardmarket", sources: [.cmTrend, .cmAvg30, .cmAvg, .cmLow]),
                            Provider(name: "TCGplayer", sources: [.tcgMarket, .tcgMid, .tcgLow])]
    let p: Printing
    @AppStorage(PriceSource.key) private var sourceName = PriceSource.cmTrend.rawValue

    var body: some View {
        let def = PriceSource(rawValue: sourceName) ?? .cmTrend
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Self.providers, id: \.name) { provider in
                let name = provider.name, sources = provider.sources
                if sources.contains(where: { p.raw($0) != nil }) {
                    Text(name == "Cardmarket" ? "Cardmarket · €" : "TCGplayer · $").font(.caption.bold()).foregroundStyle(Theme.muted)
                        .padding(.top, name == "Cardmarket" ? 0 : 8).padding(.bottom, 2)
                    ForEach(sources) { src in
                        let mine = src == def
                        HStack(spacing: 6) {
                            Text((mine ? "★ " : "") + src.short.prefix(1).uppercased() + src.short.dropFirst())
                                .foregroundStyle(mine ? Theme.gold : .white)
                            Spacer()
                            if let v = p.raw(src) {
                                Text(src.isUSD ? Money.usd(v) : Money.eur(v)).foregroundStyle(mine ? Theme.gold : .white)
                                if src.isUSD, let e = p.eurValue(src) {
                                    Text("≈ \(Money.eur(e))").foregroundStyle(Theme.muted).frame(minWidth: 70, alignment: .trailing)
                                }
                            } else {
                                Text("–").foregroundStyle(Theme.muted)
                            }
                        }
                        .font(.callout.monospacedDigit().weight(mine ? .bold : .regular))
                        .padding(.vertical, 4)
                    }
                }
            }
            if !PriceSource.allCases.contains(where: { p.raw($0) != nil }) {
                Text("No prices for the \(p.kind.rawValue.lowercased()).").font(.callout).foregroundStyle(Theme.muted)
            }
        }
        .padding(12)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// A printing's prices: the headline, the table of sources, and what to know about them.
struct PriceBlock: View {
    let p: Printing?
    let card: CardInfo
    var pricedFromEnglish = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let p {
                PriceHeadline(p: p)
                PriceSourcesTable(p: p)
            } else {
                Text("No price for this card.").foregroundStyle(Theme.muted)
            }
            if let check = card.priceCheck { Text("⚠ \(check.message)").font(.caption).foregroundStyle(Theme.warn) }
            if pricedFromEnglish { Text("Prices of the English version (none for this language)").font(.caption2).foregroundStyle(Theme.muted) }
            if let d = card.prices?.updated { Text("Prices from \(String(d.prefix(10)))").font(.caption2).foregroundStyle(Theme.muted) }
        }
        .padding(.top, 4)
    }
}

/// PSA: the sold prices of graded copies (an add-on) and the gem rate. Without the add-on a locked line says what it adds.
struct PsaSection: View {
    @ObservedObject var account: AccountModel
    let graded: Graded?
    let population: Population?
    /// the printing it's about (cards with several printings)
    let printing: Printing.Kind?
    /// PSA sales only for other printings
    var onlyOtherPrintings = false
    @AppStorage("show_psa") private var showPsa = true
    @State private var locked: LockedFeature?

    var body: some View {
        if showPsa {
            VStack(alignment: .leading, spacing: 2) {
                if account.has("psa_prices") {
                    if let g = graded { PsaLine(g: g, printing: printing) }
                    else if onlyOtherPrintings, let printing {
                        Text("No PSA sales for the \(printing.rawValue.lowercased()) yet").font(.caption).foregroundStyle(Theme.muted)
                    }
                } else if graded != nil || onlyOtherPrintings {
                    Button { locked = .psa } label: {
                        Label("PSA 10 · 9 · 8 sold prices – add-on", systemImage: "lock.fill").font(.footnote).foregroundStyle(Theme.gold)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 6)
                }
                if let p = population { GemLine(p: p) }
            }
            .lockedAlert($locked)
        }
    }
}
