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
                MainView(account: account)
                .alert(account.message ?? "", isPresented: Binding(get: { account.message != nil }, set: { if !$0 { account.message = nil } })) {
                    Button("OK", role: .cancel) {}
                }
            }
        }
    }
}


/// The bottom bar's entries. The modes (Scanner, Trading) share one: a tap opens the mode in use,
/// holding it shows the modes to pick.
enum AppTab: Hashable { case mode, search, collection, account }

enum AppMode: String, CaseIterable, Identifiable {
    case scanner, trading
    var id: String { rawValue }
    var title: String { self == .scanner ? "Scanner" : "Trading" }
    var icon: String { self == .scanner ? "camera.viewfinder" : "arrow.left.arrow.right" }
}

/// The app's pages over a bottom bar. Every page stays alive while another is shown (as in a tab
/// bar), so the search, the lists and the scanner keep their state; the camera runs only while the
/// mode page is the one shown.
struct MainView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var account: AccountModel
    @State private var tab: AppTab = .mode
    @State private var keyboard = false
    @AppStorage("ui.mode") private var modeName = AppMode.scanner.rawValue

    private var modes: [AppMode] { account.has("trading") ? [.scanner, .trading] : [.scanner] }
    private var mode: AppMode { modes.first { $0.rawValue == modeName } ?? .scanner }
    private var hasCollection: Bool { account.has("collection") || account.has("wanted") }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                page(.mode) { modePage }
                page(.search) { SearchView() }
                if hasCollection { page(.collection) { CollectionScreen(account: account, catalog: model.catalog) } }
                page(.account) { NavigationStack { AccountView(account: account, store: model.store) } }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // the keyboard covers the bar, as it does a tab bar
            if !keyboard {
                BottomBar(tab: $tab, modes: modes, mode: mode, hasCollection: hasCollection) { modeName = $0.rawValue }
            }
        }
        .onChange(of: tab) { _, _ in UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
        .onChange(of: hasCollection) { _, has in if !has && tab == .collection { tab = .mode } }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboard = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboard = false }
    }

    @ViewBuilder private var modePage: some View {
        if let scanner = model.scanner {
            switch mode {
            case .scanner: ScannerScreen(model: scanner)
            case .trading: TradingScreen(scanner: scanner, trading: model.trading)
            }
        }
    }

    private func page<V: View>(_ t: AppTab, @ViewBuilder _ content: () -> V) -> some View {
        content()
            .opacity(tab == t ? 1 : 0)
            .allowsHitTesting(tab == t)
            .accessibilityHidden(tab != t)
            .environment(\.cameraActive, tab == t)
    }
}

/// The bottom bar: the mode (hold: pick another), Search, Collection, Account.
struct BottomBar: View {
    @Binding var tab: AppTab
    let modes: [AppMode]
    let mode: AppMode
    let hasCollection: Bool
    let onMode: (AppMode) -> Void

    var body: some View {
        HStack(spacing: 0) {
            modeItem
            item(.search, "Search", "magnifyingglass")
            if hasCollection { item(.collection, "Collection", "square.stack.3d.up") }
            item(.account, "Account", "person.crop.circle")
        }
        .padding(.top, 6).padding(.bottom, 2)
        .background(.bar, ignoresSafeAreaEdges: .bottom)
        .overlay(alignment: .top) { Divider() }
    }

    private func item(_ t: AppTab, _ title: String, _ icon: String) -> some View {
        Button { tab = t } label: { barLabel(title, icon, selected: tab == t) }
            .buttonStyle(.plain)
            .accessibilityAddTraits(tab == t ? .isSelected : [])
    }

    private func barLabel(_ title: String, _ icon: String, selected: Bool, more: Bool = false) -> some View {
        VStack(spacing: 2) {
            Image(systemName: icon).font(.system(size: 20)).frame(height: 26)
            HStack(spacing: 2) {
                Text(title)
                if more { Image(systemName: "chevron.up").font(.system(size: 7, weight: .bold)) }
            }
            .font(.caption2)
        }
        .foregroundStyle(selected ? Theme.gold : Theme.muted)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
    }

    /// tap: the mode in use; hold: the modes to pick (one mode only: a plain entry)
    @ViewBuilder private var modeItem: some View {
        if modes.count > 1 {
            Menu {
                ForEach(modes) { m in
                    Button { onMode(m); tab = .mode } label: { Label(m.title, systemImage: m == mode ? "checkmark" : m.icon) }
                }
            } label: {
                barLabel(mode.title, mode.icon, selected: tab == .mode, more: true)
            } primaryAction: {
                tab = .mode
            }
            .frame(maxWidth: .infinity)
            .accessibilityHint("Hold to choose the mode")
            .accessibilityAddTraits(tab == .mode ? .isSelected : [])
        } else {
            item(.mode, mode.title, mode.icon)
        }
    }
}
