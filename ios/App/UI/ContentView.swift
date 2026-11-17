import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var account: AccountModel

    var body: some View {
        if account.locked || account.justConnected {
            AccountGateView(account: account, store: model.store)
                .alert(account.message ?? "", isPresented: Binding(get: { account.message != nil }, set: { if !$0 { account.message = nil } })) {
                    Button("OK", role: .cancel) {}
                }
        } else {
            switch model.state {
            case .loading:
                VStack(spacing: 16) {
                    ProgressView()
                    Text("Loading the card data…").foregroundStyle(Theme.muted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.ink)
            case .failed(let message):
                VStack(spacing: 16) {
                    Text("The card data couldn't be loaded").font(.headline)
                    Text(message).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                    Button("Try again") { Task { await model.load() } }
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.ink)
            case .ready:
                TabView {
                    if let scanner = model.scanner {
                        ScannerScreen(model: scanner)
                            .tabItem { Label("Scanner", systemImage: "camera.viewfinder") }
                    }
                    SearchView()
                        .tabItem { Label("Search", systemImage: "magnifyingglass") }
                    if account.has("wanted"), let catalog = model.catalog {
                        NavigationStack { MissingCardsView(account: account, catalog: catalog) }
                            .tabItem { Label("Missing", systemImage: "star") }
                    }
                    NavigationStack { AccountView(account: account, store: model.store) }
                        .tabItem { Label("Account", systemImage: "person.crop.circle") }
                }
                .alert(account.message ?? "", isPresented: Binding(get: { account.message != nil }, set: { if !$0 { account.message = nil } })) {
                    Button("OK", role: .cancel) {}
                }
            }
        }
    }
}
