import SwiftUI

/// After a double tap: the most likely cards, best first, the shown one on top.
struct PickSheet: View {
    struct Items: Identifiable { let list: [Candidate]; var id: String { list.map(\.id).joined(separator: ",") } }

    let list: [Candidate]
    let shown: Shown?
    /// the free version: no prices in the list
    var showPrices = true
    /// the free version: Search is shown locked
    var searchLocked = false
    let onPick: (Candidate) -> Void
    let onSearch: () -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(list) { c in
                    Button { onPick(c) } label: { CandidateRow(c: c, current: c.group == shown?.group, showPrice: showPrices) }
                        .listRowBackground(Theme.ink)
                }
                Button { onSearch() } label: { Label("Not in the list? Search…", systemImage: searchLocked ? "lock.fill" : "magnifyingglass") }
                    .listRowBackground(Theme.ink)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.ink)
            .navigationTitle("Which card is it?")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct CandidateRow: View {
    let c: Candidate
    var current = false
    var showPrice = true

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: c.image.flatMap(URL.init(string:))) { phase in
                switch phase {
                case .success(let img): img.resizable().scaledToFit()
                default: RoundedRectangle(cornerRadius: 4).fill(Theme.panel)
                }
            }
            .frame(width: 44, height: 62)
            VStack(alignment: .leading, spacing: 2) {
                Text(c.info.name).font(.body.weight(.semibold)).foregroundStyle(.white)
                Text(c.info.setLine()).font(.caption).foregroundStyle(Theme.muted)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                if showPrice { Text(Money.eur(c.info.bestValue)).font(.callout.monospacedDigit()).foregroundStyle(Theme.gold) }
                if current { Text("shown").font(.caption2).foregroundStyle(Theme.sure) }
            }
        }
    }
}

/// Find any card by name (any language), number or set: "whimsicott 019", "charizard base", "sv06 34".
struct CardSearchSheet: View {
    let resolver: CardResolver
    let onPick: (Candidate) -> Void
    @State private var query = ""
    @State private var results: [Candidate] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(results) { c in
                Button { onPick(c) } label: { CandidateRow(c: c) }.listRowBackground(Theme.ink)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.ink)
            .overlay { if results.isEmpty { Text(query.isEmpty ? "Name, number or set" : "No card found").foregroundStyle(Theme.muted) } }
            .navigationTitle("Search cards")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Card name, number or set")
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .task(id: query) {
                let q = query
                try? await Task.sleep(nanoseconds: 200_000_000)
                guard !Task.isCancelled else { return }
                let found = await Task.detached { [resolver] in resolver.search(q) }.value
                if !Task.isCancelled { results = found }
            }
        }
    }
}
