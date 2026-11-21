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
            Text(Money.eur(card.bestValue)).font(.callout.monospacedDigit()).foregroundStyle(Theme.gold)
        }
    }
}

/// Name, set and the prices of every printing.
struct CardDetail: View {
    let card: CardInfo
    var fallback: UIImage? = nil
    @EnvironmentObject var account: AccountModel
    /// the printing + Collection and ☆ Missing are for (the card's default one to start)
    @State private var printing: Printing.Kind?

    init(card: CardInfo, fallback: UIImage? = nil) {
        self.card = card
        self.fallback = fallback
        _printing = State(initialValue: defaultPrinting(card))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                CardPicture(card: card, large: true, fallback: fallback).frame(maxWidth: 320).frame(maxWidth: .infinity)
                VStack(alignment: .leading, spacing: 4) {
                    Text(card.name).font(.title2.weight(.bold))
                    Text([card.setName, card.numberText, card.year, card.rarity].compactMap { $0 }.joined(separator: " · "))
                        .foregroundStyle(Theme.muted)
                }
                PriceTable(card: card)
                if let d = card.prices?.updated {
                    Text("Cardmarket prices of \(String(d.prefix(10)))").font(.caption).foregroundStyle(Theme.muted)
                }
                if account.has("collection") || account.has("wanted") {
                    VStack(alignment: .leading, spacing: 6) {
                        if card.printings.count > 1 {
                            Picker("Printing", selection: $printing) {
                                ForEach(card.printings, id: \.kind) { p in Text(p.kind.rawValue).tag(Optional(p.kind)) }
                            }
                            .pickerStyle(.segmented)
                        }
                        CollectionBar(account: account, card: card, printing: printing)
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.ink)
        .navigationTitle(card.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PriceTable: View {
    let card: CardInfo

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Printing").frame(maxWidth: .infinity, alignment: .leading)
                Text("Cardmarket").frame(width: 96, alignment: .trailing)
                Text("TCGplayer").frame(width: 96, alignment: .trailing)
            }
            .font(.caption).foregroundStyle(Theme.muted).padding(.bottom, 6)
            ForEach(card.printings, id: \.kind) { p in
                HStack {
                    Text(p.kind.rawValue).frame(maxWidth: .infinity, alignment: .leading)
                    Text(Money.eur(p.eur)).frame(width: 96, alignment: .trailing).foregroundStyle(Theme.gold)
                    Text(Money.usd(p.usd)).frame(width: 96, alignment: .trailing)
                }
                .font(.callout.monospacedDigit())
                .padding(.vertical, 6)
                Divider().overlay(Theme.panel)
            }
        }
        .padding(12)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 12))
    }
}
