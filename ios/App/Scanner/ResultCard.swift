import Charts
import SwiftUI

/// The card the scanner shows: name, set, printings with their prices, PSA, other versions, alternatives.
struct ResultCard: View {
    let s: Shown
    @ObservedObject var model: ScannerModel
    let onSearch: () -> Void
    @State private var fullscreen = false

    var body: some View {
        let c = s.version
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
                            Chip(text: "\(p.kind.rawValue) \(p.priceText)", selected: p.kind == s.selected?.kind) { model.choosePrinting(p.kind) }
                        }
                    }
                }
                if !s.printingByUser, s.priceCard.printings.contains(where: { $0.kind == .reverse || $0.kind == .holo }) {
                    Text("Check the finish: tap the printing you have").font(.caption).foregroundStyle(Theme.muted)
                }
            }
            if let r = s.richerPrinting {
                Text("⚠ The \(r.kind.rawValue.lowercased()) of this card is worth \(r.priceText) – check the finish!")
                    .font(.footnote.bold()).foregroundStyle(Theme.warn)
            }
            if let lang = s.printedOnly {
                let name = ["ja": "Japanese", "ko": "Korean", "zh-tw": "Chinese (traditional)", "zh-cn": "Chinese (simplified)"][lang] ?? lang
                let shownText = CardResolver.asian.contains(s.version.lang) ? "its Japanese original (same set and number)"
                    : "the \(s.version.lang.uppercased()) card of the same picture"
                Text("Printed in \(name) – this print isn't in the card data; shown: \(shownText)").font(.caption).foregroundStyle(Theme.warn)
            }

            PriceBlock(s: s)
            if let h = s.history { PriceHistoryChart(history: h, printing: s.selected?.kind, hasNormal: s.priceCard.printings.contains { $0.kind == .normal }) }
            if model.settings.showPsa {
                let printing = s.priceCard.printings.count > 1 ? s.selected?.kind : nil
                if let g = s.graded { PsaLine(g: g, printing: printing) }
                if let p = s.population { GemLine(p: p) }
                if s.gradedOnlyOtherPrintings, let printing {
                    Text("No PSA sales for the \(printing.rawValue.lowercased()) yet").font(.caption).foregroundStyle(Theme.muted)
                }
            }

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
                            Chip(text: "\(alt.info.name) \(alt.info.numberText) · \(Money.eur(alt.info.bestValue))", selected: false) { model.choose(alt) }
                        }
                        Chip(text: "🔍 Search…", selected: false, action: onSearch)
                    }
                }
            }
            HStack(spacing: 8) {
                Button("Next card") { model.next() }.buttonStyle(.borderedProminent).foregroundStyle(Theme.ink)
                Link("Cardmarket", destination: cardmarketUrl).buttonStyle(.bordered)
            }
            .padding(.top, 4)
        }
        .fullScreenCover(isPresented: $fullscreen) {
            ZStack {
                Color.black.ignoresSafeArea()
                if let card = s.imageCard { CardPicture(card: card, large: true, fallback: s.scan).padding() }
                else if let scan = s.scan { Image(uiImage: scan).resizable().scaledToFit().padding() }
            }
            .onTapGesture { fullscreen = false }
        }
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
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text).font(.caption).lineLimit(1)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .foregroundStyle(selected ? Theme.ink : .white)
                .background(selected ? Theme.gold : Theme.panel, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? .clear : Theme.muted.opacity(0.4)))
        }
        .buttonStyle(.plain)
    }
}

struct PriceBlock: View {
    let s: Shown

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let p = s.selected, p.eur != nil || p.usd != nil {
                HStack(alignment: .lastTextBaseline, spacing: 8) {
                    Text(p.eur != nil ? Money.eur(p.eur) : p.usdEur != nil ? "≈ \(Money.eur(p.usdEur))" : Money.usd(p.usd))
                        .font(.system(size: 30, weight: .bold)).foregroundStyle(Theme.gold)
                    Text(p.eur != nil ? "Cardmarket trend · \(p.kind.rawValue)" : p.usdEur != nil ? "TCGplayer in € · \(p.kind.rawValue)" : "TCGplayer · \(p.kind.rawValue)")
                        .font(.caption).foregroundStyle(Theme.muted)
                }
                let parts = [p.eur30.map { "30-day avg \(Money.eur($0))" }, p.eurLow.map { "from \(Money.eur($0))" },
                             p.usd != nil && (p.eur != nil || p.usdEur != nil) ? "TCGplayer \(Money.usd(p.usd))" : nil].compactMap { $0 }
                if !parts.isEmpty { Text(parts.joined(separator: " · ")).font(.footnote).foregroundStyle(.white) }
                if let check = s.priceCard.priceCheck { Text("⚠ \(check.message)").font(.caption).foregroundStyle(Theme.warn) }
                if s.pricedFromEnglish { Text("Price of the English version (no price for this language)").font(.caption2).foregroundStyle(Theme.muted) }
                if let d = s.priceCard.prices?.updated { Text("Prices from \(String(d.prefix(10)))").font(.caption2).foregroundStyle(Theme.muted) }
            } else {
                Text("No price for this card.").foregroundStyle(Theme.muted)
            }
        }
        .padding(.top, 4)
    }
}

struct PsaLine: View {
    let g: Graded
    var printing: Printing.Kind?

    var body: some View {
        if g.psa10 != nil || g.psa9 != nil || g.psa8 != nil {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 16) {
                    let grades: [(String, Double?)] = [("PSA 10", g.psa10), ("PSA 9", g.psa9), ("PSA 8", g.psa8)]
                    ForEach(grades.indices, id: \.self) { i in
                        VStack(alignment: .leading) {
                            Text(grades[i].0).font(.caption2).foregroundStyle(Theme.muted)
                            Text(Money.usd(grades[i].1)).font(.subheadline.bold()).foregroundStyle(.white)
                        }
                    }
                }
                Text("eBay sold\(printing.map { " · \($0.rawValue)" } ?? "") (PokemonPriceTracker)\(g.updated.map { ", \($0)" } ?? "")")
                    .font(.caption2).foregroundStyle(Theme.muted)
            }
            .padding(.top, 6)
        }
    }
}

/// "Gem rate 11.3% · PSA 10: 340 of 3,000 graded"
struct GemLine: View {
    let p: Population

    var body: some View {
        if let rate = p.gemRate ?? p.psa10.map({ Double($0) / Double(max(1, p.psaTotal)) }) {
            (Text("Gem rate ").foregroundStyle(Theme.muted).font(.caption)
             + Text(String(format: "%.1f%%", rate * 100)).bold().foregroundStyle(.white)
             + Text("  · PSA 10: \((p.psa10 ?? 0).formatted()) of \(p.psaTotal.formatted()) graded").foregroundStyle(Theme.muted).font(.caption))
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
