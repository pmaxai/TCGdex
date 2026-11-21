import SwiftUI

/// The pages of Settings (as on Android); more come later.
enum SettingsPage: String, Hashable, CaseIterable, Identifiable {
    case account, prices, priceData, scanner, trading, about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .account: return "Account"
        case .prices: return "Prices"
        case .priceData: return "Price data & add-ons"
        case .scanner: return "Scanner"
        case .trading: return "Carts & deals"
        case .about: return "About"
        }
    }

    var icon: String {
        switch self {
        case .account: return "person.crop.circle"
        case .prices: return "eurosign.circle"
        case .priceData: return "chart.bar.doc.horizontal"
        case .scanner: return "camera.viewfinder"
        case .trading: return "cart"
        case .about: return "info.circle"
        }
    }
}

/// Settings: the account (sign-in, plan, devices) and every setting, page by page.
struct SettingsScreen: View {
    @ObservedObject var account: AccountModel
    @ObservedObject var store: StoreModel
    @ObservedObject var trading: TradingModel
    let scanner: ScannerModel?
    var catalog: Catalog? = nil
    @Binding var path: [SettingsPage]
    @AppStorage(PriceSource.key) private var sourceName = PriceSource.cmTrend.rawValue

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    NavigationLink(value: SettingsPage.account) { accountRow }
                }
                Section("Prices") {
                    row(.prices, (PriceSource(rawValue: sourceName) ?? .cmTrend).label)
                    row(.priceData, account.has("psa_prices") ? "PSA prices added" : "Cardmarket, TCGplayer")
                }
                Section("Scanner and deals") {
                    row(.scanner, nil)
                    row(.trading, account.has("trading") ? nil : "needs a subscription")
                }
                Section { row(.about, AccountApi.version) }
            }
            .navigationTitle("Settings")
            .navigationDestination(for: SettingsPage.self) { page in
                destination(page)
                    .navigationTitle(page.title)
                    .navigationBarTitleDisplayMode(.inline)
            }
        }
    }

    private var accountRow: some View {
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle.fill").font(.system(size: 34)).foregroundStyle(Theme.gold)
            VStack(alignment: .leading, spacing: 2) {
                if account.isFree {
                    Text("Free version").font(.headline)
                    Text(account.status?.email ?? "Sign in and choose a plan").font(.caption).foregroundStyle(Theme.muted)
                } else if let st = account.status {
                    Text(st.email ?? "Account").font(.headline).lineLimit(1)
                    Text("\(st.planName) · \(st.stateText)").font(.caption).foregroundStyle(st.state == "past_due" ? Theme.warn : Theme.muted)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func row(_ page: SettingsPage, _ detail: String?) -> some View {
        NavigationLink(value: page) {
            HStack {
                Label(page.title, systemImage: page.icon)
                Spacer()
                if let detail { Text(detail).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1) }
            }
        }
    }

    @ViewBuilder private func destination(_ page: SettingsPage) -> some View {
        switch page {
        case .account:
            if account.isFree { AccountGateView(account: account, store: store, free: true) }
            else { AccountView(account: account, store: store) }
        case .prices: PricesSettingsView(scanner: scanner, alertsLocked: account.isFree)
        case .priceData: PriceDataView(account: account, store: store)
        case .scanner:
            if let scanner { ScannerSettingsForm(model: scanner) }
        case .trading:
            if account.has("trading") { TradingSettingsForm(trading: trading) } else { LockedPage(feature: .trading, icon: "cart") }
        case .about: AboutView(account: account, catalog: catalog)
        }
    }
}

/// Settings › Prices: the price shown first, and the price alert.
struct PricesSettingsView: View {
    let scanner: ScannerModel?
    var alertsLocked = false
    @AppStorage(PriceSource.key) private var sourceName = PriceSource.cmTrend.rawValue
    @AppStorage("show_psa") private var showPsa = true

    var body: some View {
        Form {
            ForEach(PriceSourcesTable.providers, id: \.name) { provider in
                Section {
                    ForEach(provider.sources) { src in
                        Button { sourceName = src.rawValue } label: {
                            HStack {
                                Text(src.short.prefix(1).uppercased() + src.short.dropFirst()).foregroundStyle(.white)
                                Spacer()
                                if src.rawValue == sourceName { Image(systemName: "checkmark").foregroundStyle(Theme.gold) }
                            }
                        }
                        .accessibilityAddTraits(src.rawValue == sourceName ? .isSelected : [])
                    }
                } header: {
                    Text(provider.name == "Cardmarket" ? "Default price · Cardmarket (€)" : "TCGplayer ($, shown in € as well)")
                } footer: {
                    if provider.name == "TCGplayer" {
                        Text("The default price is shown first for every card and in the lists, sets off the price alert and is the market value in Add to cart. A card without it shows the next price it has. Every price stays in the card's price table.")
                    }
                }
            }
            if let scanner { AlertSection(model: scanner, locked: alertsLocked) }
            Section {
                Toggle("Show PSA and gem rate", isOn: $showPsa)
            } footer: { Text("PSA sold prices are an add-on (Price data & add-ons); the gem rate is shown with every plan.") }
        }
    }
}

/// The price alert: the phone vibrates when a scanned card is worth at least this (in the default price).
private struct AlertSection: View {
    @ObservedObject var model: ScannerModel
    let locked: Bool
    @State private var lockedFeature: LockedFeature?

    var body: some View {
        Section {
            if locked {
                Button { lockedFeature = .alert } label: {
                    Label("Vibrate at a price: needs a subscription", systemImage: "lock.fill").foregroundStyle(Theme.muted)
                }
            } else {
                Picker("Vibrate at", selection: $model.settings.threshold) {
                    Text("off").tag(0.0)
                    ForEach([1.0, 2, 5, 10, 20, 50, 100], id: \.self) { Text("≥ \(Int($0)) €").tag($0) }
                }
            }
        } header: { Text("Price alert") }
        .lockedAlert($lockedFeature)
    }
}

/// Settings › Scanner: how a card is taken.
struct ScannerSettingsForm: View {
    @ObservedObject var model: ScannerModel

    var body: some View {
        Form {
            Section {
                Picker("Wait before the shot", selection: $model.settings.triggerDelayMs) {
                    ForEach([0, 300, 600, 1000, 1500, 2000], id: \.self) { Text($0 == 0 ? "instant" : String(format: "%.1f s", Double($0) / 1000)).tag($0) }
                }
                Picker("Capture frame", selection: $model.settings.guideSize) {
                    ForEach([50, 60, 70, 80, 90], id: \.self) { Text("\($0) %").tag($0) }
                }
                Picker("Energy cards", selection: $model.settings.energyFilter) {
                    Text("recognise").tag(0)
                    Text("skip common energy").tag(1)
                    Text("skip all energy").tag(2)
                }
            } footer: {
                Text("The frame turns green when the card is sure; it is taken once it stayed green this long.")
            }
            Section("Details") {
                Toggle("Show what the scanner sees", isOn: $model.settings.showDebug)
            }
        }
    }
}

/**
 * Settings › Price data & add-ons: Cardmarket and TCGplayer come with every plan; more price data
 * (PSA prices now, eBay listings and others later) is an add-on, its own App Store subscription
 * beside the plan.
 */
struct PriceDataView: View {
    @ObservedObject var account: AccountModel
    @ObservedObject var store: StoreModel

    /// what an add-on brings (the shop's name for it comes with the offer)
    static func about(_ id: String) -> String {
        switch id {
        case "psa_prices": return "What PSA 10, 9 and 8 copies of a card sold for on eBay, per printing, for every card that has sales."
        default: return "More price data for every card."
        }
    }

    var body: some View {
        Form {
            Section {
                included("Cardmarket", "Trend, 30-day average, average sell price and lowest offer (€)")
                included("TCGplayer", "Market, mid and low price ($, shown in € as well)")
            } header: { Text("In every plan") }
            Section {
                if store.addons.isEmpty {
                    // the shop sells none yet (or the list isn't loaded): PSA as it will come
                    addonInfo(name: "PSA prices", id: "psa_prices", note: store.loading ? "Loading…" : (account.isFree ? nil : "Not on sale in the app yet."))
                }
                ForEach(store.addons) { a in AddonRow(addon: a, account: account, store: store) }
                if account.isFree {
                    Button("See the plans") { NotificationCenter.default.post(name: .showPlans, object: nil) }
                }
            } header: { Text("Add-ons") } footer: {
                Text("An add-on is for Collector and Creator and runs as its own subscription beside the plan, monthly or yearly. eBay listings and more price data will follow.")
            }
            if !account.isFree { Section { StoreLines(store: store) } }
        }
        .task { store.refresh() }
    }

    private func included(_ name: String, _ what: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.sure)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.headline)
                Text(what).font(.caption).foregroundStyle(Theme.muted)
            }
        }
    }

    private func addonInfo(name: String, id: String, note: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(name, systemImage: account.has(id) ? "checkmark.circle.fill" : "lock.fill").font(.headline)
                .foregroundStyle(account.has(id) ? Theme.sure : .white)
            Text(Self.about(id)).font(.caption).foregroundStyle(Theme.muted)
            if let note { Text(note).font(.caption).foregroundStyle(Theme.muted) }
        }
        .padding(.vertical, 2)
    }
}

/// One add-on: what it brings, and its state or its prices to buy.
private struct AddonRow: View {
    let addon: AddonOffer
    @ObservedObject var account: AccountModel
    @ObservedObject var store: StoreModel

    var body: some View {
        let has = account.has(addon.feature)
        let state = store.state(addon)
        let blocked = store.addonBlocked(addon)
        VStack(alignment: .leading, spacing: 6) {
            Label(addon.name, systemImage: has ? "checkmark.circle.fill" : "plus.circle").font(.headline)
                .foregroundStyle(has ? Theme.sure : .white)
            Text(PriceDataView.about(addon.id)).font(.caption).foregroundStyle(Theme.muted)
            if let state, state.running {
                Text("Running · \(state.stateText)" + (state.source == "app_store" ? "" : state.source == "web" ? " · managed on the website" : ""))
                    .font(.footnote).foregroundStyle(Theme.sure)
                // the plan ended, the add-on's own subscription still runs (and bills) in the App Store
                if !has && state.source == "app_store" && state.state != "canceled" {
                    Text("It works only with a running plan. Without one, cancel it in the App Store's subscriptions (Settings › Account › Manage subscription).")
                        .font(.caption).foregroundStyle(Theme.warn)
                }
            } else if let blocked {
                Text(blocked).font(.footnote).foregroundStyle(Theme.muted)
            } else {
                // borderless: two buttons in one Form row would otherwise both fire on a tap
                HStack(spacing: 10) {
                    ForEach(["monthly", "yearly"], id: \.self) { period in
                        if let p = store.price(addon, period) {
                            Button { store.buyAddon(addon, period) } label: {
                                Text(PlanCard.text(p, period == "yearly" ? "year" : "month"))
                                    .font(.subheadline.bold()).foregroundStyle(Theme.ink)
                                    .padding(.horizontal, 12).padding(.vertical, 7)
                                    .background(Theme.gold, in: Capsule())
                            }
                            .buttonStyle(.borderless)
                            .disabled(store.busy)
                        }
                    }
                }
                if store.price(addon, "monthly") == nil && store.price(addon, "yearly") == nil {
                    Text("The App Store doesn't sell it right now.").font(.footnote).foregroundStyle(Theme.muted)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

/// Settings › About: the app's version, the prices' date, the privacy policy.
struct AboutView: View {
    @ObservedObject var account: AccountModel
    let catalog: Catalog?

    var body: some View {
        Form {
            Section {
                LabeledContent("Version", value: AccountApi.version ?? "–")
                LabeledContent("Prices of", value: catalog?.pricesDate.map { String($0.prefix(10)) } ?? "–")
            }
            Section { Link("Privacy policy", destination: account.privacyUrl) }
        }
    }
}
