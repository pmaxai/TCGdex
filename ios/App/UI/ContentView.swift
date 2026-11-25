import Combine
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


/// The bottom bar's entries: the Scanner (with carts and deals, or YouTube Mode), Search, Collection, Settings (with the account).
enum AppTab: Hashable { case scanner, search, collection, settings }

/// The Scanner tab's modes: hold Scanner in the bottom bar to pick one.
enum ScanMode: String, CaseIterable, Identifiable {
    case scanner, webshop, youtube
    var id: String { rawValue }
    var title: String {
        switch self { case .scanner: return "Scanner"; case .webshop: return "Webshop Mode"; case .youtube: return "YouTube Mode" }
    }
    var short: String {
        switch self { case .scanner: return "Scanner"; case .webshop: return "Webshop"; case .youtube: return "YouTube" }
    }
    var icon: String {
        switch self { case .scanner: return "camera.viewfinder"; case .webshop: return "storefront"; case .youtube: return "play.rectangle" }
    }
    /// what a mode the account lacks needs (listed with a lock)
    var needs: String { self == .webshop ? "with a connected webshop" : "Creator plan" }

    /// the mode the scanner's settings are in
    static func of(_ s: ScannerSettings) -> ScanMode { s.youtube ? .youtube : s.webshop ? .webshop : .scanner }
}

/// The app's pages over a bottom bar. Every page stays alive while another is shown (as in a tab
/// bar), so the search, the lists and the scanner keep their state; the camera runs only while the
/// Scanner is the page shown. YouTube Mode fills the whole screen (no bar, no status bar).
struct MainView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var account: AccountModel
    @State private var tab: AppTab = .scanner
    @State private var settingsPath: [SettingsPage] = []
    @State private var keyboard = false
    @State private var mode: ScanMode = .scanner
    @State private var locked: LockedFeature?

    /// the free version has its collection on this phone
    private var hasCollection: Bool { account.isFree || account.has("collection") || account.has("wanted") }
    private var fullScreen: Bool { tab == .scanner && mode == .youtube }
    /// modes the account lacks: listed with a lock
    private var lockedModes: Set<ScanMode> {
        Set([account.has("youtube") ? nil : ScanMode.youtube, account.has("webshop") ? nil : ScanMode.webshop].compactMap { $0 })
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                page(.scanner) {
                    if let scanner = model.scanner {
                        ScannerTab(model: scanner, trading: model.trading, rec: model.recording, webshop: model.webshop)
                    }
                }
                page(.search) { if account.isFree { LockedPage(feature: .search, icon: "magnifyingglass") } else { SearchView() } }
                if hasCollection { page(.collection) { CollectionScreen(account: account, catalog: model.catalog) } }
                page(.settings) {
                    SettingsScreen(account: account, store: model.store, trading: model.trading, scanner: model.scanner,
                                   catalog: model.catalog, recording: model.recording, webshop: model.webshop, path: $settingsPath)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // the keyboard covers the bar, as it does a tab bar
            if !keyboard && !fullScreen {
                BottomBar(tab: $tab, hasCollection: hasCollection, mode: mode, lockedModes: lockedModes,
                          onLocked: { m in locked = m == .webshop ? LockedFeature.webshop : LockedFeature.youtube }, onMode: { setMode($0) })
            }
        }
        .statusBarHidden(fullScreen)
        .persistentSystemOverlays(fullScreen ? .hidden : .automatic)
        .lockedAlert($locked)
        // a locked feature's "See the plans": Settings › Account (sign-in and the plans); an add-on's: Settings › Price data
        .onReceive(NotificationCenter.default.publisher(for: .showPlans)) { _ in tab = .settings; settingsPath = [.account] }
        .onReceive(NotificationCenter.default.publisher(for: .showAddons)) { _ in tab = .settings; settingsPath = [.priceData] }
        .onReceive(NotificationCenter.default.publisher(for: .openSettingsPage)) { n in
            if let p = n.object as? SettingsPage { tab = .settings; settingsPath = [p] }
        }
        .onReceive(model.scanner?.$settings.map(ScanMode.of).removeDuplicates().eraseToAnyPublisher() ?? Just(.scanner).eraseToAnyPublisher()) { mode = $0 }
        .onChange(of: tab) { _, _ in UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
        .onChange(of: hasCollection) { _, has in if !has && tab == .collection { tab = .scanner } }
        // a mode that is no longer included falls back to the Scanner (not while it records)
        .onChange(of: account.has("youtube"), initial: true) { _, has in
            if !has, mode == .youtube, !model.recording.recording { model.scanner?.settings.youtube = false }
        }
        .onChange(of: account.has("webshop"), initial: true) { _, has in
            if !has, mode == .webshop { model.scanner?.settings.webshop = false }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboard = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboard = false }
    }

    private func setMode(_ m: ScanMode) {
        guard let s = model.scanner else { return }
        var st = s.settings
        st.youtube = m == .youtube
        st.webshop = m == .webshop
        if st != s.settings { s.settings = st }
        tab = .scanner
    }

    private func page<V: View>(_ t: AppTab, @ViewBuilder _ content: () -> V) -> some View {
        content()
            .opacity(tab == t ? 1 : 0)
            .allowsHitTesting(tab == t)
            .accessibilityHidden(tab != t)
            .environment(\.cameraActive, tab == t)
    }
}

/// The Scanner tab: the scanner with carts, YouTube Mode or Webshop Mode.
struct ScannerTab: View {
    @ObservedObject var model: ScannerModel
    @ObservedObject var trading: TradingModel
    @ObservedObject var rec: RecordingModel
    var webshop: WebshopModel?

    var body: some View {
        if model.settings.youtube { YouTubeScreen(model: model, rec: rec) }
        else if model.settings.webshop, let webshop { WebshopScreen(web: webshop, scanner: model, trading: trading) }
        else { ScannerScreen(model: model, trading: trading) }
    }
}

/// The bottom bar: Scanner (hold it for the modes), Search, Collection, Settings.
struct BottomBar: View {
    @Binding var tab: AppTab
    let hasCollection: Bool
    var mode: ScanMode = .scanner
    /// listed with a lock: picking one says what it needs
    var lockedModes: Set<ScanMode> = []
    var onLocked: (ScanMode) -> Void = { _ in }
    var onMode: (ScanMode) -> Void = { _ in }

    var body: some View {
        HStack(spacing: 0) {
            modeItem
            item(.search, "Search", "magnifyingglass")
            if hasCollection { item(.collection, "Collection", "square.stack.3d.up") }
            item(.settings, "Settings", "gearshape")
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

    /// tap: the mode in use; hold: the modes to pick
    private var modeItem: some View {
        Menu {
            ForEach(ScanMode.allCases) { m in
                if lockedModes.contains(m) {
                    Button { onLocked(m) } label: { Label("\(m.title) · \(m.needs)", systemImage: "lock.fill") }
                } else {
                    Button { onMode(m) } label: { Label(m.title, systemImage: m == mode ? "checkmark" : m.icon) }
                }
            }
        } label: {
            barLabel(mode.short, mode.icon, selected: tab == .scanner, more: true)
        } primaryAction: {
            tab = .scanner
        }
        .frame(maxWidth: .infinity)
        .accessibilityLabel(mode.short)
        .accessibilityHint("Hold to choose the mode")
        .accessibilityAddTraits(tab == .scanner ? .isSelected : [])
    }
}

extension Notification.Name {
    /// a page of Settings to open (the object is its SettingsPage): YouTube Mode's ⚙ → Settings…, ● REC without storage
    static let openSettingsPage = Notification.Name("openSettingsPage")
}
