import SwiftUI

/// A card the collection page opens (its detail page).
struct CardKey: Hashable { let id: String; let lang: String }

/// The collection page: the person's collection, and the missing cards as its second tab.
struct CollectionScreen: View {
    @ObservedObject var account: AccountModel
    let catalog: Catalog?

    enum Part: String, CaseIterable, Identifiable {
        case collection = "Collection", missing = "Missing"
        var id: String { rawValue }
    }
    @State private var part: Part

    init(account: AccountModel, catalog: Catalog?, start: Part = .collection) {
        _account = ObservedObject(wrappedValue: account)
        self.catalog = catalog
        _part = State(initialValue: start)
    }

    private var parts: [Part] { (account.has("collection") ? [.collection] : []) + (account.has("wanted") ? [.missing] : []) }
    private var shown: Part { parts.contains(part) ? part : (parts.first ?? .collection) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if parts.count > 1 {
                    Picker("Part", selection: $part) { ForEach(parts) { Text($0.rawValue).tag($0) } }
                        .pickerStyle(.segmented)
                        .padding(.horizontal, 16).padding(.vertical, 8)
                }
                switch shown {
                case .collection: CollectionList(account: account, catalog: catalog)
                case .missing: MissingList(account: account, catalog: catalog)
                }
            }
            .background(Theme.ink)
            .navigationTitle(shown == .collection ? "Collection" : "Missing cards")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: CardKey.self) { k in
                if let card = catalog?.card(k.id, k.lang) { CardDetail(card: card) }
            }
        }
    }
}

/// Every copy in the collection, newest first, with a filter and the total value.
struct CollectionList: View {
    @ObservedObject var account: AccountModel
    let catalog: Catalog?
    @State private var filter = ""

    var body: some View {
        let all = account.items ?? []
        let words = filter.lowercased().split(whereSeparator: \.isWhitespace)
        let shown = words.isEmpty ? all : all.filter { item in words.allSatisfy { item.searchText.contains($0) } }
        List {
            if !all.isEmpty {
                let copies = all.reduce(0) { $0 + $1.quantity }
                let cents = all.reduce(Int64(0)) { $0 + ($1.valueCents ?? 0) * Int64($1.quantity) }
                HStack {
                    Text("\(Set(all.map(\.cardId)).count) cards · \(copies) copies").foregroundStyle(Theme.muted)
                    Spacer()
                    Text(Money.eur(Double(cents) / 100)).font(.callout.monospacedDigit().bold()).foregroundStyle(Theme.gold)
                }
                .listRowBackground(Theme.ink)
            }
            ForEach(shown) { item in
                let lang = ShopTerms.appLang(item.language)
                NavigationLink(value: CardKey(id: item.cardId, lang: lang)) {
                    CollectionRow(item: item, card: catalog?.card(item.cardId, lang))
                }
                .listRowBackground(Theme.ink)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .overlay {
            if account.items == nil || all.isEmpty {
                VStack(spacing: 8) {
                    if account.items == nil && account.itemsLoading { ProgressView() }
                    Text(account.itemsError ?? (account.items == nil ? "Loading the collection…" : "No cards in the collection yet. Tap + Collection on a card to add it."))
                        .foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                }
                .padding(24)
            } else if shown.isEmpty {
                Text("No card matches “\(filter)”.").foregroundStyle(Theme.muted)
            }
        }
        .searchable(text: $filter, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Filter the collection")
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .refreshable { await account.loadItems() }
        .task { if account.items == nil { await account.loadItems() } }
        .onChange(of: account.collectionEdits) { _, _ in Task { await account.loadItems() } }
    }
}

struct CollectionRow: View {
    let item: CollectionItem
    let card: CardInfo?

    var body: some View {
        HStack(spacing: 12) {
            if let card { CardPicture(card: card).frame(width: 40) }
            else { RoundedRectangle(cornerRadius: 4).fill(Theme.panel).aspectRatio(0.716, contentMode: .fit).frame(width: 40) }
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(.body.weight(.semibold)).lineLimit(1)
                Text([item.setName, item.number].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                Text([item.language, ShopTerms.finishLabel(item.finish), item.condition, item.graded].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("× \(item.quantity)").font(.callout.monospacedDigit())
                if let v = item.valueCents { Text(Money.eur(Double(v * Int64(item.quantity)) / 100)).font(.caption.monospacedDigit()).foregroundStyle(Theme.gold) }
            }
        }
    }
}

/// The missing cards: what the shop looks for. Tap (or swipe) a card to remove it, or to put it into the
/// collection when it turned up elsewhere.
struct MissingList: View {
    @ObservedObject var account: AccountModel
    let catalog: Catalog?
    /// the card whose actions are shown
    @State private var chosen: WantedItem?
    /// the card that turned up elsewhere: its copy's details
    @State private var found: WantedItem?

    private func card(_ w: WantedItem) -> CardInfo? { catalog?.card(w.cardId, ShopTerms.appLang(w.language)) }
    private func label(_ w: WantedItem) -> String { card(w).map { "\($0.name) · \($0.setName) \($0.numberText)" } ?? w.cardId }

    private func remove(_ w: WantedItem) {
        account.toggleWanted(cardId: w.cardId, language: w.language, finish: w.finish, label: label(w))
    }

    var body: some View {
        List {
            if account.wanted.isEmpty {
                Text("No missing cards yet. Tap ☆ Missing on a card to add it.").foregroundStyle(Theme.muted)
                    .listRowBackground(Theme.ink)
            }
            ForEach(account.wanted) { w in
                Button { chosen = w } label: { MissingRow(w: w, card: card(w)) }
                    .foregroundStyle(.white)
                    .listRowBackground(Theme.ink)
                    .swipeActions(edge: .trailing) {
                        Button("Remove", role: .destructive) { remove(w) }
                    }
                    .swipeActions(edge: .leading) {
                        if account.has("collection") { Button("Found it") { account.loadBinders(); found = w }.tint(Theme.gold) }
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { account.check(force: true) }
        .confirmationDialog(chosen.map(label) ?? "", isPresented: Binding(get: { chosen != nil }, set: { if !$0 { chosen = nil } }),
                            titleVisibility: .visible, presenting: chosen) { w in
            if account.has("collection") { Button("Found it: add to the collection") { account.loadBinders(); found = w } }
            Button("Remove from the missing cards", role: .destructive) { remove(w) }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(item: $found) { w in
            let c = card(w)
            CollectionSheet(account: account, title: "Found it") { cond, qty, cents, binder, note in
                account.foundElsewhere(w, condition: cond, quantity: qty, purchaseCents: cents, binderId: binder, note: note,
                                       defaultLanguage: ShopTerms.languageOf(c?.lang), defaultFinish: ShopTerms.finishOf(c.flatMap(defaultPrinting)),
                                       label: label(w))
            }
            .presentationDetents([.medium, .large])
        }
    }
}

struct MissingRow: View {
    let w: WantedItem
    let card: CardInfo?

    var body: some View {
        HStack(spacing: 12) {
            if let card { CardPicture(card: card).frame(width: 40) }
            VStack(alignment: .leading, spacing: 2) {
                Text(card?.name ?? w.cardId).font(.body.weight(.semibold))
                Text([card?.setLine(withLang: false), w.language, w.finish.map(ShopTerms.finishLabel)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Theme.muted)
            }
            Spacer()
            Text(w.pending ? "waiting to sync" : w.state == "offer" ? "Offer ›" : w.state == "available" ? "In the shop ›" : "searching")
                .font(.caption).foregroundStyle(w.state == "offer" ? Theme.gold : w.state == "available" ? Theme.sure : Theme.muted)
        }
    }
}
