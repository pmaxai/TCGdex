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
    @State private var sharing = false
    @State private var locked: LockedFeature?

    init(account: AccountModel, catalog: Catalog?, start: Part = .collection) {
        _account = ObservedObject(wrappedValue: account)
        self.catalog = catalog
        _part = State(initialValue: start)
    }

    /// the free version: its collection on this phone, and the missing cards shown locked
    private var parts: [Part] {
        if account.isFree { return [.collection, .missing] }
        return (account.has("collection") ? [.collection] : []) + (account.has("wanted") ? [.missing] : [])
    }
    private var shown: Part { parts.contains(part) ? part : (parts.first ?? .collection) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if parts.count > 1 {
                    Picker("Part", selection: $part) {
                    ForEach(parts) { p in Text(account.isFree && p == .missing ? "\(p.rawValue) 🔒" : p.rawValue).tag(p) }
                }
                        .pickerStyle(.segmented)
                        .padding(.horizontal, 16).padding(.vertical, 8)
                }
                switch shown {
                case .collection:
                    if account.isFree { LocalCollectionList(account: account, catalog: catalog) } else { CollectionList(account: account, catalog: catalog) }
                case .missing:
                    if account.isFree { LockedPage(feature: .missing, icon: "star") } else { MissingList(account: account, catalog: catalog) }
                }
            }
            .background(Theme.ink)
            .navigationTitle(shown == .collection ? "Collection" : "Missing cards")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: CardKey.self) { k in
                if let card = catalog?.card(k.id, k.lang) { CardDetail(card: card) }
            }
            .toolbar {
                // a public link for friends (the free version: shown locked)
                if shown == .collection {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { if account.isFree { locked = .share } else { sharing = true } } label: {
                            Label("Share", systemImage: "square.and.arrow.up")
                        }
                    }
                }
            }
            .sheet(isPresented: $sharing) { ShareCollectionSheet(account: account).presentationDetents([.medium, .large]) }
            .lockedAlert($locked)
        }
    }
}

/// A row's Edit and Delete: swipe (right: Edit, left: Delete) or press and hold.
private struct CopyActions: ViewModifier {
    let onEdit: () -> Void
    let onDelete: () -> Void

    func body(content: Content) -> some View {
        content
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button { onDelete() } label: { Label("Delete", systemImage: "trash") }.tint(.red)
            }
            .swipeActions(edge: .leading) {
                Button { onEdit() } label: { Label("Edit", systemImage: "pencil") }.tint(Theme.gold)
            }
            .contextMenu {
                Button { onEdit() } label: { Label("Edit details…", systemImage: "pencil") }
                Button(role: .destructive) { onDelete() } label: { Label("Delete", systemImage: "trash") }
            }
    }
}

/// "2 × DE · Holo · NM · PSA 10"
private func copyLine(_ quantity: Int, _ language: String, _ finish: String, _ condition: String, _ graded: String?) -> String {
    "\(quantity) × " + [language, ShopTerms.finishLabel(finish), condition, graded ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
}

/// Every copy in the collection, newest first, with a filter and the total value.
struct CollectionList: View {
    @ObservedObject var account: AccountModel
    let catalog: Catalog?
    @State private var filter = ""
    @State private var editing: CollectionItem?
    @State private var deleting: CollectionItem?

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
                .modifier(CopyActions(onEdit: { account.loadBinders(); editing = item }, onDelete: { deleting = item }))
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
        .sheet(item: $editing) { item in
            EditCopySheet(title: item.name, binders: account.binders, details: CopyDetails(item),
                          onSave: { d in await account.updateItem(item, d.changes(from: CopyDetails(item))) },
                          onDelete: { await account.deleteItem(item) })
        }
        .confirmationDialog(deleting.map { "Delete \($0.name)?" } ?? "", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { item in
            Button("Delete", role: .destructive) {
                Task { if let e = await account.deleteItem(item) { account.message = e } }
            }
        } message: { item in Text(copyLine(item.quantity, item.language, item.finish, item.condition, item.graded) + " – it leaves your collection.") }
    }
}

/// The free version's collection, kept on this phone: up to 50 different cards, newest first, with a filter.
/// Values come with a subscription; a swipe removes a card.
struct LocalCollectionList: View {
    @ObservedObject var account: AccountModel
    let catalog: Catalog?
    @State private var filter = ""
    @State private var locked: LockedFeature?
    @State private var editing: LocalItem?
    @State private var deleting: LocalItem?

    var body: some View {
        let all = account.localItems
        let words = filter.lowercased().split(whereSeparator: \.isWhitespace)
        let shown = words.isEmpty ? all : all.filter { item in words.allSatisfy { item.searchText.contains($0) } }
        List {
            if !all.isEmpty {
                HStack {
                    Text("\(account.localCardCount) of \(AccountModel.freeMaxCards) cards · \(all.reduce(0) { $0 + $1.quantity }) copies")
                        .foregroundStyle(Theme.muted)
                    Spacer()
                    Button { locked = .values } label: { Label("Value", systemImage: "lock.fill").font(.callout.bold()).foregroundStyle(Theme.gold) }
                        .buttonStyle(.borderless)
                }
                .listRowBackground(Theme.ink)
            }
            ForEach(shown) { item in
                let lang = ShopTerms.appLang(item.language)
                NavigationLink(value: CardKey(id: item.cardId, lang: lang)) {
                    LocalRow(item: item, card: catalog?.card(item.cardId, lang))
                }
                .listRowBackground(Theme.ink)
                .modifier(CopyActions(onEdit: { editing = item }, onDelete: { deleting = item }))
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .overlay {
            if all.isEmpty {
                Text("No cards in the collection yet. Tap + Collection on a scanned card to add it – the free version keeps up to \(AccountModel.freeMaxCards) different cards on this phone.")
                    .foregroundStyle(Theme.muted).multilineTextAlignment(.center).padding(24)
            } else if shown.isEmpty {
                Text("No card matches “\(filter)”.").foregroundStyle(Theme.muted)
            }
        }
        .searchable(text: $filter, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Filter the collection")
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .lockedAlert($locked)
        .sheet(item: $editing) { item in
            EditCopySheet(title: catalog?.card(item.cardId, ShopTerms.appLang(item.language))?.name ?? item.label, binders: [], details: CopyDetails(item),
                          onSave: { d in account.updateLocally(item.id, d); return nil },
                          onDelete: { account.removeLocally(item.id); return nil })
        }
        .confirmationDialog(deleting.map { "Delete \($0.label)?" } ?? "", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { item in
            Button("Delete", role: .destructive) { account.removeLocally(item.id) }
        } message: { item in
            Text(copyLine(item.quantity, item.language, item.finish, item.condition, item.gradingCompany.map { "\($0) \(item.gradingGrade ?? "")" }) + " – it leaves the collection on this phone.")
        }
    }
}

struct LocalRow: View {
    let item: LocalItem
    let card: CardInfo?

    var body: some View {
        HStack(spacing: 12) {
            if let card { CardPicture(card: card).frame(width: 40) }
            else { RoundedRectangle(cornerRadius: 4).fill(Theme.panel).aspectRatio(0.716, contentMode: .fit).frame(width: 40) }
            VStack(alignment: .leading, spacing: 2) {
                Text(card?.name ?? item.label).font(.body.weight(.semibold)).lineLimit(1)
                if let card { Text("\(card.setName) · \(card.numberText)").font(.caption).foregroundStyle(Theme.muted).lineLimit(1) }
                Text([item.language, ShopTerms.finishLabel(item.finish), item.condition,
                      item.gradingCompany.map { "\($0) \(item.gradingGrade ?? "")" } ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
            }
            Spacer()
            Text("× \(item.quantity)").font(.callout.monospacedDigit())
        }
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
