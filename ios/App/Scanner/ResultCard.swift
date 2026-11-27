import Charts
import SwiftUI

/// The card the scanner shows: name, set, printings with their prices, PSA, other versions, alternatives, Add to cart.
struct ResultCard: View {
    let s: Shown
    @ObservedObject var model: ScannerModel
    let onSearch: () -> Void
    /// Add to cart: the popup that prices the card for a deal
    var onAddToCart: () -> Void = {}
    @EnvironmentObject var account: AccountModel
    @State private var fullscreen = false
    @State private var locked: LockedFeature?

    var body: some View {
        let c = s.version
        // the free version: a price range, and Cardmarket's trend only once revealed
        let priced = account.revealed(s.priceCard.id)
        let free = account.isFree
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                ShownPicture(s: s)
                    .frame(width: 96)
                    .onLongPressGesture(minimumDuration: 0.6) { fullscreen = true }
                VStack(alignment: .leading, spacing: 2) {
                    Text(c.name).font(.title2.bold()).foregroundStyle(.white).lineLimit(2)
                    Text(c.setLine()).font(.footnote).foregroundStyle(Theme.muted)
                    if let e = s.english { Text("English: \(e.name) · \(e.setName) \(e.numberText)").font(.footnote).foregroundStyle(.white) }
                    if let r = c.rarity { Text(r).font(.caption).foregroundStyle(Theme.muted) }
                    Text(matchText).font(.caption).foregroundStyle(matchColor).padding(.top, 4)
                }
            }

            // printings: which ones exist and what each is worth
            if s.priceCard.printings.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(s.priceCard.printings, id: \.kind) { p in
                            Chip(text: !priced ? p.kind.rawValue : free ? FreePrice.chip(p) : "\(p.kind.rawValue) \(p.priceText)",
                                 selected: p.kind == s.selected?.kind, repeats: model.printingLock == p.kind,
                                 onHold: { model.togglePrintingLock(p.kind) }) { model.choosePrinting(p.kind) }
                        }
                    }
                }
                if let k = model.printingLock {
                    PrintingLockNote(kind: k, has: s.priceCard.printings.contains { $0.kind == k })
                } else if !s.printingByUser, s.priceCard.printings.contains(where: { $0.kind == .reverse || $0.kind == .holo }) {
                    Text("Check the finish: tap the printing you have · hold one for a second to keep it for the next cards")
                        .font(.caption).foregroundStyle(Theme.muted)
                }
            }
            if let r = s.richerPrinting {
                let worth: String? = free ? FreePrice.trend(r).map { Money.eur($0) } : r.priceText
                Text(priced && worth != nil ? "⚠ The \(r.kind.rawValue.lowercased()) of this card is worth \(worth ?? "") – check the finish!"
                     : "⚠ The \(r.kind.rawValue.lowercased()) of this card is worth more – check the finish!")
                    .font(.footnote.bold()).foregroundStyle(Theme.warn)
            }
            if let lang = s.printedOnly {
                let name = ["ja": "Japanese", "ko": "Korean", "zh-tw": "Chinese (traditional)", "zh-cn": "Chinese (simplified)"][lang] ?? lang
                let shownText = CardResolver.asian.contains(s.version.lang) ? "its Japanese original (same set and number)"
                    : "the \(s.version.lang.uppercased()) card of the same picture"
                Text("Printed in \(name) – this print isn't in the card data; shown: \(shownText)").font(.caption).foregroundStyle(Theme.warn)
            } else if let note = s.symbolicPictureNote {
                // no picture of this card in its language: another print's stands in
                Text(note).font(.caption).foregroundStyle(Theme.muted)
            }

            if priced && free {
                FreeTrend(p: s.selected, card: s.priceCard, pricedFromEnglish: s.pricedFromEnglish)
            } else if priced {
                PriceBlock(p: s.selected, card: s.priceCard, pricedFromEnglish: s.pricedFromEnglish)
            } else {
                FreePriceRange(p: s.selected)
                RevealPrice(account: account, cardId: s.priceCard.id)
            }
            CollectionBar(account: account, s: s)
            if priced, !free, let h = s.history { PriceHistoryChart(history: h, printing: s.selected?.kind, hasNormal: s.priceCard.printings.contains { $0.kind == .normal }) }
            PsaSection(account: account, graded: s.graded, population: s.population,
                       printing: s.priceCard.printings.count > 1 ? s.selected?.kind : nil, onlyOtherPrintings: s.gradedOnlyOtherPrintings)

            let others = s.versions.filter { $0.id != s.version.id || $0.lang != s.version.lang }
            if !others.isEmpty {
                Text("Other version or language?").font(.caption).foregroundStyle(Theme.muted).padding(.top, 4)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(others, id: \.versionKey) { v in
                            Chip(text: v.id == s.version.id ? "\(languageNames[v.lang] ?? v.lang): \(v.name)" : v.setLine(), selected: false) { model.chooseVersion(v) }
                        }
                    }
                }
            }
            if !s.fromSearch {
                Text("Not this card?").font(.caption).foregroundStyle(Theme.muted).padding(.top, 4)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(s.alternatives) { alt in
                            Chip(text: "\(alt.info.name) \(alt.info.numberText)" + (account.isFree ? "" : " · \(alt.info.bestPriceText)"), selected: false) { model.choose(alt) }
                        }
                        Chip(text: account.isFree ? "🔒 Search…" : "🔍 Search…", selected: false) { if account.isFree { locked = .search } else { onSearch() } }
                    }
                }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { nextButton; cartButton; cardmarketLink }
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) { nextButton; cartButton }
                    cardmarketLink
                }
            }
            .padding(.top, 4)
        }
        .lockedAlert($locked)
        .fullScreenCover(isPresented: $fullscreen) {
            ZStack {
                Color.black.ignoresSafeArea()
                if let card = s.imageCard { CardPicture(card: card, large: true, fallback: s.scan).padding() }
                else if let scan = s.scan { Image(uiImage: scan).resizable().scaledToFit().padding() }
            }
            .onTapGesture { fullscreen = false }
        }
    }

    private var nextButton: some View {
        Button("Next card") { model.next() }.buttonStyle(.borderedProminent).foregroundStyle(Theme.ink).fixedSize()
    }

    /// carts and deals come with a plan; the free version shows the button locked
    private var cartButton: some View {
        let open = account.has("trading")
        return Button { if open { onAddToCart() } else { locked = .trading } } label: {
            Label("Add to cart", systemImage: open ? "cart.badge.plus" : "lock.fill")
        }
        .buttonStyle(.bordered)
        .tint(open ? Theme.gold : Theme.muted)
        .fixedSize()
    }

    private var cardmarketLink: some View {
        Link("Cardmarket", destination: cardmarketUrl).buttonStyle(.bordered).fixedSize()
    }

    private var matchText: String {
        if s.locked { return "📌 kept" }
        switch s.confidence {
        case .sure: return "✓ sure match"
        case .likely: return "likely – check the alternatives"
        case .unsure: return "unsure – check the alternatives"
        }
    }

    private var matchColor: Color {
        if s.locked { return Theme.gold }
        switch s.confidence { case .sure: return Theme.sure; case .likely: return Theme.likely; case .unsure: return Theme.muted }
    }

    private var cardmarketUrl: URL {
        let q = s.priceCard.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        return URL(string: "https://www.cardmarket.com/en/Pokemon/Products/Search?searchString=\(q)")!
    }
}

extension CardInfo {
    var versionKey: String { id + "/" + lang }
}

/// The card's picture: this version, another one of the same artwork, or the camera's straightened scan.
struct ShownPicture: View {
    let s: Shown
    var body: some View {
        if let card = s.imageCard { CardPicture(card: card, fallback: s.scan) }
        else if let scan = s.scan { Image(uiImage: scan).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 4)) }
        else { RoundedRectangle(cornerRadius: 4).fill(Theme.panel).aspectRatio(63.0 / 88.0, contentMode: .fit) }
    }
}

struct Chip: View {
    let text: String
    let selected: Bool
    /// the printing locked for the next cards: the repeat symbol
    var repeats = false
    /// held for a second (the printings: lock this one for the next cards)
    var onHold: (() -> Void)? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if repeats { Image(systemName: "repeat").font(.caption.bold()) }
                Text(text).font(.caption).lineLimit(1)
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .foregroundStyle(selected ? Theme.ink : .white)
            .background(selected ? Theme.gold : Theme.panel, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(repeats ? Theme.gold : selected ? .clear : Theme.muted.opacity(0.4), lineWidth: repeats ? 2 : 1))
        }
        .buttonStyle(.plain)
        .holdForASecond(onHold)
    }
}

extension View {
    /// Held for a second: `action` (the button's tap still follows when the finger lifts).
    @ViewBuilder func holdForASecond(_ action: (() -> Void)?) -> some View {
        if let action { simultaneousGesture(LongPressGesture(minimumDuration: 1).onEnded { _ in action() }) } else { self }
    }
}

/// Under the printings while one is locked: what happens to the next cards.
struct PrintingLockNote: View {
    let kind: Printing.Kind
    /// the shown card has that printing
    let has: Bool
    var body: some View {
        Label(has ? "\(kind.rawValue) kept for the next cards · hold it again to stop"
              : "\(kind.rawValue) kept for the next cards – this card has no \(kind.rawValue.lowercased())",
              systemImage: "repeat")
            .font(.caption).foregroundStyle(has ? Theme.gold : Theme.warn)
    }
}

struct PsaLine: View {
    let g: Graded
    var printing: Printing.Kind?
    /// YouTube Mode's reveal: larger
    var big = false

    var body: some View {
        if g.psa10 != nil || g.psa9 != nil || g.psa8 != nil {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 16) {
                    let grades: [(String, Double?)] = [("PSA 10", g.psa10), ("PSA 9", g.psa9), ("PSA 8", g.psa8)]
                    ForEach(grades.indices, id: \.self) { i in
                        VStack(alignment: .leading) {
                            Text(grades[i].0).font(big ? .footnote : .caption2).foregroundStyle(Theme.muted)
                            Text(Money.usd(grades[i].1)).font(big ? .title3.bold() : .subheadline.bold()).foregroundStyle(.white)
                        }
                    }
                }
                if !big { Text("eBay sold\(printing.map { " · \($0.rawValue)" } ?? "") (PokemonPriceTracker)\(g.updated.map { ", \($0)" } ?? "")")
                    .font(.caption2).foregroundStyle(Theme.muted) }
            }
            .padding(.top, 6)
        }
    }
}

/// "Gem rate 11.3% · PSA 10: 340 of 3,000 graded"
struct GemLine: View {
    let p: Population
    /// YouTube Mode's reveal: larger
    var big = false

    var body: some View {
        if let rate = p.gemRate ?? p.psa10.map({ Double($0) / Double(max(1, p.psaTotal)) }) {
            (Text("Gem rate ").foregroundStyle(Theme.muted).font(big ? .subheadline : .caption)
             + Text(String(format: "%.1f%%", rate * 100)).bold().foregroundStyle(.white).font(big ? .subheadline : .body)
             + Text("  · PSA 10: \((p.psa10 ?? 0).formatted()) of \(p.psaTotal.formatted()) graded").foregroundStyle(Theme.muted).font(big ? .subheadline : .caption))
                .lineLimit(1).minimumScaleFactor(0.7)
                .padding(.top, 4)
        }
    }
}

/// The price over the last year (weekly) and the last 90 days (daily): Cardmarket and TCGplayer in €.
struct PriceHistoryChart: View {
    let history: PriceHistory
    let printing: Printing.Kind?
    let hasNormal: Bool

    private struct Point: Identifiable { let id: String; let date: Date; let value: Double; let source: String }

    private var points: [Point] {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        var out: [Point] = []
        for (source, series) in [("Cardmarket", history.eur(printing, hasNormal: hasNormal)), ("TCGplayer", history.usdInEur(printing))] {
            guard let series else { continue }
            for (i, v) in series.enumerated() where i < history.dates.count && !v.isNaN {
                if let d = f.date(from: history.dates[i]) { out.append(Point(id: "\(source)\(i)", date: d, value: Double(v), source: source)) }
            }
        }
        return out
    }

    var body: some View {
        let pts = points
        if pts.count > 1 {
            Chart(pts) { p in
                LineMark(x: .value("Date", p.date), y: .value("€", p.value))
                    .foregroundStyle(by: .value("Source", p.source))
                    .interpolationMethod(.monotone)
            }
            .chartForegroundStyleScale(["Cardmarket": Theme.gold, "TCGplayer": Theme.good])
            .chartYAxis { AxisMarks(position: .leading) }
            .frame(height: 120)
            .padding(.top, 6)
        }
    }
}
