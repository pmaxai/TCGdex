import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var account: AccountModel

    var body: some View {
        // without a running subscription the app opens in the free version (sign-in and plans in Settings › Account)
        if account.justConnected {
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
                MainView(account: account)
                .alert(account.message ?? "", isPresented: Binding(get: { account.message != nil }, set: { if !$0 { account.message = nil } })) {
                    Button("OK", role: .cancel) {}
                }
            }
        }
    }
}


/// The bottom bar's entries: the Scanner (with carts and deals), Search, Collection, Settings (with the account).
enum AppTab: Hashable { case scanner, search, collection, settings }

/// The app's pages over a bottom bar. Every page stays alive while another is shown (as in a tab
/// bar), so the search, the lists and the scanner keep their state; the camera runs only while the
/// Scanner is the page shown.
struct MainView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var account: AccountModel
    @State private var tab: AppTab = .scanner
    @State private var settingsPath: [SettingsPage] = []
    @State private var keyboard = false

    /// the free version has its collection on this phone
    private var hasCollection: Bool { account.isFree || account.has("collection") || account.has("wanted") }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                page(.scanner) { if let scanner = model.scanner { ScannerScreen(model: scanner, trading: model.trading) } }
                page(.search) { if account.isFree { LockedPage(feature: .search, icon: "magnifyingglass") } else { SearchView() } }
                if hasCollection { page(.collection) { CollectionScreen(account: account, catalog: model.catalog) } }
                page(.settings) {
                    SettingsScreen(account: account, store: model.store, trading: model.trading, scanner: model.scanner,
                                   catalog: model.catalog, path: $settingsPath)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // the keyboard covers the bar, as it does a tab bar
            if !keyboard { BottomBar(tab: $tab, hasCollection: hasCollection) }
        }
        // a locked feature's "See the plans": Settings › Account (sign-in and the plans); an add-on's: Settings › Price data
        .onReceive(NotificationCenter.default.publisher(for: .showPlans)) { _ in tab = .settings; settingsPath = [.account] }
        .onReceive(NotificationCenter.default.publisher(for: .showAddons)) { _ in tab = .settings; settingsPath = [.priceData] }
        .onChange(of: tab) { _, _ in UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
        .onChange(of: hasCollection) { _, has in if !has && tab == .collection { tab = .scanner } }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboard = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboard = false }
    }

    private func page<V: View>(_ t: AppTab, @ViewBuilder _ content: () -> V) -> some View {
        content()
            .opacity(tab == t ? 1 : 0)
            .allowsHitTesting(tab == t)
            .accessibilityHidden(tab != t)
            .environment(\.cameraActive, tab == t)
    }
}

/// The bottom bar: Scanner, Search, Collection, Settings.
struct BottomBar: View {
    @Binding var tab: AppTab
    let hasCollection: Bool

    var body: some View {
        HStack(spacing: 0) {
            item(.scanner, "Scanner", "camera.viewfinder")
            item(.search, "Search", "magnifyingglass")
            if hasCollection { item(.collection, "Collection", "square.stack.3d.up") }
            item(.settings, "Settings", "gearshape")
        }
        .padding(.top, 6).padding(.bottom, 2)
        .background(.bar, ignoresSafeAreaEdges: .bottom)
        .overlay(alignment: .top) { Divider() }
    }

    private func item(_ t: AppTab, _ title: String, _ icon: String) -> some View {
        Button { tab = t } label: {
            VStack(spacing: 2) {
                Image(systemName: icon).font(.system(size: 20)).frame(height: 26)
                Text(title).font(.caption2)
            }
            .foregroundStyle(tab == t ? Theme.gold : Theme.muted)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(tab == t ? .isSelected : [])
    }
}
