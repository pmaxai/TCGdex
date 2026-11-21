import SwiftUI

/// Find any card by name (any language), number or set: "whimsicott 019", "charizard base", "sv06 34".
struct SearchView: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var results: [CardInfo] = []

    var body: some View {
        NavigationStack {
            List(results) { card in
                NavigationLink(value: card.id + "/" + card.lang) { CardRow(card: card) }
                    .listRowBackground(Theme.ink)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.ink)
            .overlay {
                if results.isEmpty {
                    Text(query.isEmpty ? "Name, number or set" : "No card found").foregroundStyle(Theme.muted)
                }
            }
            .navigationTitle("Search")
            .navigationDestination(for: String.self) { key in
                let parts = key.split(separator: "/", maxSplits: 1).map(String.init)
                if parts.count == 2, let card = model.catalog?.cardExact(parts[0], parts[1]) {
                    CardDetail(card: card, catalog: model.catalog, trading: model.trading)
                }
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Card name, number or set")
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .task(id: query) {
            let q = query
            try? await Task.sleep(nanoseconds: 200_000_000)
            guard !Task.isCancelled, let cat = model.catalog else { return }
            let found = await Task.detached { cat.search(q, limit: 150) }.value
            if !Task.isCancelled { results = found }
        }
    }
}
