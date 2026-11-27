import SwiftUI

/// The card's picture from TCGdex (or the one the camera took when there is none).
struct CardPicture: View {
    let card: CardInfo
    var large = false
    var fallback: UIImage? = nil
    @Environment(\.cardArtStandIn) private var standIn

    var body: some View {
        let url = (large ? card.highUrl : card.thumbnailUrl).flatMap(URL.init(string:))
        Group {
            if standIn { DrawnCard(seed: card.id) }
            else {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image): image.resizable().scaledToFit()
                    default:
                        if let fallback { Image(uiImage: fallback).resizable().scaledToFit() }
                        else { RoundedRectangle(cornerRadius: 8).fill(Theme.panel).overlay(Image(systemName: "photo").foregroundStyle(Theme.muted)) }
                    }
                }
            }
        }
        .aspectRatio(0.716, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: large ? 12 : 4))
    }
}

/// The App Store's images: a drawn card instead of every card's picture (no card artwork in them).
private struct CardArtStandInKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    var cardArtStandIn: Bool {
        get { self[CardArtStandInKey.self] }
        set { self[CardArtStandInKey.self] = newValue }
    }
}

/// A trading card drawn by the app: a frame, an abstract picture and lines for the text, coloured by the seed.
struct DrawnCard: View {
    let seed: String

    /// the same colour for the same card, in every run
    private var hue: Double {
        Double(seed.unicodeScalars.reduce(UInt32(5381)) { ($0 &* 33) &+ $1.value } % 360) / 360
    }

    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height
            let art = Color(hue: hue, saturation: 0.55, brightness: 0.85)
            let deep = Color(hue: (hue + 0.12).truncatingRemainder(dividingBy: 1), saturation: 0.7, brightness: 0.45)
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: w * 0.05)
                    .fill(LinearGradient(colors: [Color(red: 0.98, green: 0.84, blue: 0.42), Color(red: 0.78, green: 0.58, blue: 0.2)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                RoundedRectangle(cornerRadius: w * 0.035)
                    .fill(Color(hue: hue, saturation: 0.25, brightness: 0.22))
                    .padding(w * 0.045)
                // the name
                Capsule().fill(.white.opacity(0.75)).frame(width: w * 0.5, height: h * 0.035).offset(x: w * 0.1, y: h * 0.065)
                Capsule().fill(.white.opacity(0.5)).frame(width: w * 0.14, height: h * 0.035).offset(x: w * 0.74, y: h * 0.065)
                // the picture
                ZStack {
                    LinearGradient(colors: [art, deep], startPoint: .top, endPoint: .bottom)
                    Circle().fill(.white.opacity(0.22)).frame(width: w * 0.5).offset(x: w * 0.18, y: -h * 0.06)
                    Circle().fill(deep.opacity(0.7)).frame(width: w * 0.7).offset(x: -w * 0.22, y: h * 0.16)
                    Image(systemName: "sparkles").font(.system(size: w * 0.2, weight: .bold)).foregroundStyle(.white.opacity(0.8))
                        .offset(x: w * 0.05, y: -h * 0.02)
                }
                .frame(width: w * 0.82, height: h * 0.42)
                .clipShape(RoundedRectangle(cornerRadius: w * 0.02))
                .offset(x: w * 0.09, y: h * 0.125)
                // the text
                ForEach(0..<4, id: \.self) { i in
                    Capsule().fill(.white.opacity(i == 0 ? 0.55 : 0.3))
                        .frame(width: w * (i == 0 ? 0.6 : [0.78, 0.7, 0.5][i - 1]), height: h * 0.022)
                        .offset(x: w * 0.1, y: h * (0.6 + Double(i) * 0.062))
                }
                Capsule().fill(.white.opacity(0.3)).frame(width: w * 0.3, height: h * 0.018).offset(x: w * 0.1, y: h * 0.9)
            }
        }
        .aspectRatio(0.716, contentMode: .fit)
        .accessibilityLabel("Card")
    }
}

struct CardRow: View {
    let card: CardInfo

    var body: some View {
        HStack(spacing: 12) {
            CardPicture(card: card).frame(width: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(card.name).font(.body.weight(.semibold))
                Text("\(card.setName) · \(card.numberText) · \(card.lang.uppercased())").font(.caption).foregroundStyle(Theme.muted)
            }
            Spacer()
            Text(card.bestPriceText).font(.callout.monospacedDigit()).foregroundStyle(Theme.gold)
        }
    }
}

/**
 * A card's page (the search, the collection): the picture, the printing, its prices in every
 * source, the price chart, PSA, + Collection / ☆ Missing and Add to cart – what the Scanner shows
 * for a scanned card.
 */
struct CardDetail: View {
    let card: CardInfo
    var fallback: UIImage? = nil
    /// for the price chart and the English prices of a card without its own
    var catalog: Catalog? = nil
    /// Add to cart (the search)
    var trading: TradingModel? = nil
    @EnvironmentObject var account: AccountModel
    /// whose prices are shown: the card, or its English version when it has none
    private let priceCard: CardInfo
    /// the printing the prices, + Collection, ☆ Missing and Add to cart are for (the card's default one to start)
    @State private var printing: Printing.Kind?
    @State private var history: PriceHistory?
    @State private var deal: DealCard?
    @State private var locked: LockedFeature?

    init(card: CardInfo, fallback: UIImage? = nil, catalog: Catalog? = nil, trading: TradingModel? = nil) {
        self.card = card
        self.fallback = fallback
        self.catalog = catalog
        self.trading = trading
        let own = card.printings.contains { $0.value != nil }
        priceCard = own || card.lang == "en" ? card : (catalog?.cardExact(card.id, "en") ?? card)
        _printing = State(initialValue: defaultPrinting(priceCard))
    }

    var body: some View {
        let priced = account.revealed(priceCard.id)
        let free = account.isFree
        let selected = priceCard.printing(printing)
        let several = priceCard.printings.count > 1
        let graded = priceCard.gradedFor(selected?.kind)
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                CardPicture(card: card, large: true, fallback: fallback).frame(maxWidth: 280).frame(maxWidth: .infinity)
                VStack(alignment: .leading, spacing: 4) {
                    Text(card.name).font(.title2.weight(.bold))
                    Text([card.setName, card.numberText, card.lang.uppercased(), card.year, card.rarity].compactMap { $0 }.joined(separator: " · "))
                        .foregroundStyle(Theme.muted)
                }
                if several {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(priceCard.printings, id: \.kind) { p in
                                Chip(text: !priced ? p.kind.rawValue : free ? FreePrice.chip(p) : "\(p.kind.rawValue) \(p.priceText)",
                                     selected: p.kind == selected?.kind) { printing = p.kind }
                            }
                        }
                    }
                }
                // the free version: a price range; revealed one card at a time, Cardmarket's trend only
                if priced && free {
                    FreeTrend(p: selected, card: priceCard, pricedFromEnglish: priceCard.id != card.id || priceCard.lang != card.lang)
                } else if priced {
                    PriceBlock(p: selected, card: priceCard, pricedFromEnglish: priceCard.id != card.id || priceCard.lang != card.lang)
                    if let h = history { PriceHistoryChart(history: h, printing: selected?.kind, hasNormal: priceCard.printings.contains { $0.kind == .normal }) }
                } else {
                    FreePriceRange(p: selected)
                    RevealPrice(account: account, cardId: priceCard.id)
                }
                PsaSection(account: account, graded: graded, population: priceCard.populationFor(selected?.kind),
                           printing: several ? selected?.kind : nil, onlyOtherPrintings: graded == nil && !priceCard.graded.isEmpty)
                if account.isFree || account.has("collection") || account.has("wanted") {
                    CollectionBar(account: account, card: card, printing: selected?.kind)
                }
                HStack(spacing: 8) {
                    if let trading {
                        let open = account.has("trading")
                        Button {
                            if open { trading.startDeal(); deal = DealCard(card: card, priceCard: priceCard, printing: selected) } else { locked = .trading }
                        } label: { Label("Add to cart", systemImage: open ? "cart.badge.plus" : "lock.fill") }
                        .buttonStyle(.bordered).tint(open ? Theme.gold : Theme.muted)
                    }
                    Link("Cardmarket", destination: cardmarketUrl).buttonStyle(.bordered)
                }
            }
            .padding(16)
        }
        .background(Theme.ink)
        .navigationTitle(card.name)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: priceCard.id + priceCard.lang) { history = catalog?.history(priceCard.id) }
        .lockedAlert($locked)
        .sheet(item: $deal) { d in if let trading { DealSheet(d: d, trading: trading) } }
        .overlay(alignment: .bottom) {
            if let trading, let m = trading.message {
                Text(m).font(.footnote).foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 8)
                    .background(.black.opacity(0.8), in: Capsule()).padding(.bottom, 12)
                    .task(id: m) { try? await Task.sleep(nanoseconds: 1_800_000_000); trading.message = nil }
            }
        }
    }

    private var cardmarketUrl: URL {
        let q = priceCard.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        return URL(string: "https://www.cardmarket.com/en/Pokemon/Products/Search?searchString=\(q)")!
    }
}
