import SwiftUI

/// The card's picture from TCGdex (or the one the camera took when there is none).
struct CardPicture: View {
    let card: CardInfo
    var large = false
    var fallback: UIImage? = nil

    var body: some View {
        let url = (large ? card.highUrl : card.thumbnailUrl).flatMap(URL.init(string:))
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let image): image.resizable().scaledToFit()
            default:
                if let fallback { Image(uiImage: fallback).resizable().scaledToFit() }
                else { RoundedRectangle(cornerRadius: 8).fill(Theme.panel).overlay(Image(systemName: "photo").foregroundStyle(Theme.muted)) }
            }
        }
        .aspectRatio(0.716, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: large ? 12 : 4))
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
                                Chip(text: priced ? "\(p.kind.rawValue) \(p.priceText)" : p.kind.rawValue, selected: p.kind == selected?.kind) { printing = p.kind }
                            }
                        }
                    }
                }
                // the free version: revealed one card at a time
                if priced {
                    PriceBlock(p: selected, card: priceCard, pricedFromEnglish: priceCard.id != card.id || priceCard.lang != card.lang)
                    if let h = history { PriceHistoryChart(history: h, printing: selected?.kind, hasNormal: priceCard.printings.contains { $0.kind == .normal }) }
                } else {
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
