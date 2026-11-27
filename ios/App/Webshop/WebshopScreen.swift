import SwiftUI
import UIKit

private let sellBlue = giveBlue
private let skyBlue = Color(red: 0x87 / 255, green: 0xCE / 255, blue: 0xEB / 255)
private let lineGrey = Color(red: 0x2E / 255, green: 0x31 / 255, blue: 0x38 / 255)
private let keyGrey = Color(red: 0x2A / 255, green: 0x2D / 255, blue: 0x34 / 255)

/// N / H / RH / 1st
private func typeShort(_ k: Printing.Kind) -> String {
    switch k {
    case .normal: return "N"
    case .holo: return "H"
    case .reverse: return "RH"
    case .firstEdition: return "1st"
    }
}

private func kindOf(_ finish: String) -> Printing.Kind? {
    switch finish {
    case "normal": return .normal
    case "holo": return .holo
    case "reverse_holo": return .reverse
    case "first_edition": return .firstEdition
    default: return nil
    }
}

/**
 * Webshop Mode: scan or search a card, see its prices and what the shop holds of it, and book
 * copies into the shop (language, condition, price, box) – or, in Sell, collect a customer's
 * order and take it out of stock. A scan changes nothing: stock changes only with a button.
 * The same screen as the Android app's: the top bar, the camera, the panel below it.
 */
struct WebshopScreen: View {
    @ObservedObject var web: WebshopModel
    /// not observed here: the camera follows it on its own, the panel follows web.shown (no redraw with every frame)
    let scanner: ScannerModel
    @ObservedObject var trading: TradingModel
    @EnvironmentObject var account: AccountModel
    @State private var torch = false
    @State private var search: SearchUse?
    @State private var orderOpen = false
    @State private var cameraAllowed: Bool?

    enum SearchUse: String, Identifiable { case lookUp, replace; var id: String { rawValue } }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            GeometryReader { g in
                VStack(spacing: 0) {
                    camera.frame(height: g.size.height * 0.375)
                    panel.frame(height: g.size.height * 0.625)
                }
            }
        }
        .background(Theme.ink)
        .cameraLifecycle(model: scanner, allowed: $cameraAllowed)
        .onAppear { scanner.camera.enableStills() }
        .onDisappear { if torch { torch = false } }
        .onChange(of: torch) { _, on in scanner.camera.setTorch(on) }
        .sheet(item: $search) { use in
            CardSearchSheet(resolver: scanner.resolver) { c in
                search = nil
                if use == .replace && scanner.shown != nil { scanner.replaceWith(c) } else { scanner.lookUp(c) }
            }
        }
        .sheet(isPresented: $orderOpen) { OrderSheet(web: web).presentationDetents([.medium, .large]) }
        .sheet(item: $web.unknownCard) { u in UnknownCardSheet(card: u, web: web) }
        .background(ScannerMessages(model: scanner))
        .overlay(alignment: .bottom) {
            if let t = web.message {
                Text(t).font(.footnote).foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 8)
                    .background(.black.opacity(0.8), in: Capsule()).padding(.bottom, 12)
                    .task(id: t) { try? await Task.sleep(nanoseconds: 1_800_000_000); web.message = nil }
            }
        }
    }

    // ------------------------------------------------------------------ top bar

    private var topBar: some View {
        HStack(spacing: 6) {
            Button { search = .lookUp } label: {
                Image(systemName: "magnifyingglass").font(.system(size: 17, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 44, height: 36).background(Theme.panel, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Search a card")
            // Stock in: book copies into the shop · Sell: collect a customer's order, take it out of stock
            HStack(spacing: 0) {
                ForEach([false, true], id: \.self) { sell in
                    let on = web.selling == sell
                    Button { web.setSelling(sell) } label: {
                        Text(sell ? "Sell" : "Stock in").font(.subheadline.weight(on ? .bold : .regular)).lineLimit(1)
                            .foregroundStyle(on ? Theme.ink : .white)
                            .frame(maxWidth: .infinity).padding(.vertical, 7)
                            .background(on ? (sell ? sellBlue : Theme.gold) : .clear, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .padding(3)
            .background(Theme.panel, in: Capsule())
            if !web.order.isEmpty || web.selling {
                Button { orderOpen = true } label: {
                    Text("🧾 \(web.orderCopies)" + (web.orderTotal > 0 ? " · \(Money.eur(web.orderTotal))" : ""))
                        .font(.subheadline.bold()).foregroundStyle(.white).lineLimit(1)
                        .padding(.horizontal, 10).frame(height: 36)
                        .background(sellBlue.opacity(web.order.isEmpty ? 0.25 : 0.6), in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Customer's order, \(web.orderCopies) cards")
            }
            WebshopMenu(hasTorch: scanner.camera.hasTorch, torch: torch, hasCard: web.shown != nil, act: menuAction).equatable()
        }
        .padding(.horizontal, 8).padding(.vertical, 6)
    }

    private func menuAction(_ a: WebshopMenu.Action) {
        switch a {
        case .light: torch.toggle()
        case .lookUpAgain: web.lookup()
        case .order: orderOpen = true
        case .settings: NotificationCenter.default.post(name: .openSettingsPage, object: SettingsPage.webshop)
        }
    }

    // ------------------------------------------------------------------ camera

    private var camera: some View {
        ZStack {
            if cameraAllowed == false {
                VStack(spacing: 12) {
                    Text("Webshop Mode needs the camera.").foregroundStyle(.white)
                    Button("Open Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                }
            } else {
                CameraArea(model: scanner, webshop: true, hintLift: web.photoWanted ? 52 : 0)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .bottom) {
            if web.photoWanted { ProductPhotoButton(web: web).padding(10) }
        }
        .clipped()
    }

    // ------------------------------------------------------------------ panel

    private var panel: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    WebshopPanel(web: web, scanner: scanner, step: trading.priceStep, showPsa: scanner.settings.showPsa && account.has("psa_prices"),
                                 onSearch: { search = .replace })
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollDismissesKeyboard(.interactively)
            // a card detected: scroll down so the button (the last line) is in view, also once the shop's products have loaded
            .onChange(of: web.shown?.serial) { _, _ in toEnd(proxy) }
            .onChange(of: web.loading) { _, l in if !l { toEnd(proxy) } }
        }
    }

    private func toEnd(_ proxy: ScrollViewProxy) {
        guard web.shown != nil else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { withAnimation(.easeOut(duration: 0.3)) { proxy.scrollTo("end", anchor: .bottom) } }
    }
}

/// The scanner's notes (e.g. "No card found in the frame" after a double tap), in an alert.
private struct ScannerMessages: View {
    @ObservedObject var model: ScannerModel
    var body: some View {
        Color.clear
            .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
                Button("OK", role: .cancel) {}
            }
    }
}

/**
 * ⚙: Webshop Mode's menu. It is drawn again only when what it shows changes – an open menu that
 * is rebuilt under the finger loses the tap (as YouTube Mode's).
 */
struct WebshopMenu: View, Equatable {
    enum Action { case light, lookUpAgain, order, settings }

    let hasTorch: Bool
    let torch: Bool
    let hasCard: Bool
    let act: (Action) -> Void

    static func == (a: WebshopMenu, b: WebshopMenu) -> Bool { a.hasTorch == b.hasTorch && a.torch == b.torch && a.hasCard == b.hasCard }

    var body: some View {
        Menu {
            if hasTorch { Button(torch ? "Light off" : "Light on") { act(.light) } }
            Button("Look up again in the shop") { act(.lookUpAgain) }.disabled(!hasCard)
            Button("Customer's order…") { act(.order) }
            Divider()
            Button("Settings…") { act(.settings) }
        } label: {
            Image(systemName: "gearshape.fill").font(.system(size: 19)).foregroundStyle(.white)
                .frame(width: 40, height: 36)
        }
        .menuOrder(.fixed)
        .accessibilityLabel("Webshop Mode menu")
    }
}

/// The panel under the camera: the connection, the card with its prices, the shop's products, and the form (or Sell).
struct WebshopPanel: View {
    @ObservedObject var web: WebshopModel
    let scanner: ScannerModel
    let step: Double
    let showPsa: Bool
    let onSearch: () -> Void

    var body: some View {
        if !web.configured {
            Text("Connect your webshop first: host, key id and secret (from the shop's admin → API-Schlüssel).").foregroundStyle(Theme.muted)
            Button("Connect the shop") { NotificationCenter.default.post(name: .openSettingsPage, object: SettingsPage.webshop) }
                .buttonStyle(.borderedProminent).foregroundStyle(Theme.ink).padding(.top, 8).padding(.bottom, 12)
        } else {
            Button { NotificationCenter.default.post(name: .openSettingsPage, object: SettingsPage.webshop) } label: {
                Text("🏪 " + web.host.replacingOccurrences(of: "https://", with: "") + (web.selling ? "  ·  Sell" : "  ·  Stock in"))
                    .font(.caption).foregroundStyle(web.connectionOk == false ? Theme.warn : Theme.muted).lineLimit(1)
            }
            .buttonStyle(.plain)
            .padding(.bottom, 4)
        }
        if let s = web.shown {
            CardAndPrices(s: s, web: web, scanner: scanner, showPsa: showPsa, onSearch: onSearch)
            if web.configured {
                ShopEntries(web: web)
                if web.selling { SellBlock(web: web) } else { ShopFormBlock(s: s, web: web, step: step) }
            }
        } else {
            Text(web.selling ? "Scan or search each card the customer wants: it goes into the order. Nothing leaves the stock until you complete the sale."
                 : "Show a card, or search one: its prices and what your shop holds of it appear here. Nothing is booked until you press the button.")
                .foregroundStyle(Theme.muted)
        }
    }
}

/// The card, its printing (Normal / Holo / Reverse) and its prices: market value, trend, TCGplayer, PSA.
private struct CardAndPrices: View {
    let s: Shown
    @ObservedObject var web: WebshopModel
    let scanner: ScannerModel
    let showPsa: Bool
    let onSearch: () -> Void

    var body: some View {
        let p = s.pickedPrinting
        let condition = web.form.condition
        HStack(alignment: .top, spacing: 12) {
            ShopCardPicture(s: s, language: web.form.language).frame(width: 76)
            VStack(alignment: .leading, spacing: 2) {
                Text(s.version.name).font(.title3.bold()).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.65)
                Text(s.version.setLine()).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1).minimumScaleFactor(0.8)
                if let note = s.printedOnlyNote { Text(note).font(.caption).foregroundStyle(Theme.warn) }
                if let note = web.pictureNote { Text(note).font(.caption).foregroundStyle(Theme.warn) }
                if let e = s.english { Text("English: \(e.name)").font(.caption).foregroundStyle(.white).lineLimit(1) }
                if let market = (p?.eur ?? p?.usdEur).map({ $0 * ShopTerms.share(condition) }) {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(Money.eur(market)).font(.system(size: 24, weight: .black)).foregroundStyle(Theme.gold)
                        Text("market · \(condition)" + (p?.eur == nil ? " (TCGplayer)" : "")).font(.caption).foregroundStyle(Theme.muted)
                    }
                    .padding(.top, 4)
                } else {
                    Text("No market price" + (p == nil && s.printing != nil ? " for \(ShopTerms.finishLabel(ShopTerms.finishOf(s.printing)))" : ""))
                        .font(.subheadline).foregroundStyle(Theme.warn).padding(.top, 4)
                }
            }
        }
        if s.priceCard.printings.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(s.priceCard.printings, id: \.kind) { pr in
                        Chip(text: "\(pr.kind.rawValue) \(pr.priceText)", selected: pr.kind == p?.kind, repeats: web.printingLock == pr.kind,
                             onHold: { web.togglePrintingLock(pr.kind) }) { scanner.choosePrinting(pr.kind) }
                    }
                }
            }
            .padding(.top, 6)
        }
        if let k = web.printingLock { PrintingLockNote(kind: k, has: s.priceCard.printings.contains { $0.kind == k }).padding(.top, 2) }
        let parts = [p?.eur.map { "trend \(Money.eur($0))" }, p?.eur30.map { "30-day \(Money.eur($0))" }, p?.eurLow.map { "from \(Money.eur($0))" },
                     p?.usd.map { "TCGplayer \(Money.usd($0))" }].compactMap { $0 }
        if !parts.isEmpty { Text(parts.joined(separator: " · ")).font(.caption).foregroundStyle(.white).padding(.top, 4) }
        // PSA prices are an add-on
        if showPsa, let g = s.graded { PsaLine(g: g, printing: s.priceCard.printings.count > 1 ? p?.kind : nil) }
        if let check = s.priceCard.priceCheck { Text("⚠ \(check.message)").font(.caption).foregroundStyle(Theme.warn) }
        // the same card in another language or set (as in the Scanner): the product's language follows
        let others = s.versions.filter { $0.id != s.version.id || $0.lang != s.version.lang }
        if !others.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    Text("Other version or language?").font(.caption).foregroundStyle(Theme.muted)
                    ForEach(others, id: \.versionKey) { v in
                        Chip(text: v.id == s.version.id ? "\(languageNames[v.lang] ?? v.lang): \(v.name)" : v.setLine(), selected: false) { web.chooseVersion(v) }
                    }
                }
            }
            .padding(.top, 4)
        }
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                Text("Not this card?").font(.caption).foregroundStyle(Theme.muted)
                if !s.fromSearch {
                    ForEach(s.alternatives) { alt in Chip(text: alt.info.name, selected: false) { scanner.choose(alt) } }
                }
                Chip(text: "🔍 Search…", selected: false, action: onSearch)
            }
        }
        .padding(.top, 4)
    }
}

/// The card's picture as the shop gets it: in the product's language, else the English card's, marked symbolic.
private struct ShopCardPicture: View {
    let s: Shown
    let language: String
    var body: some View {
        if let p = s.shopPicture(language: language) {
            CardPicture(card: p.card, fallback: s.scan)
                .overlay {
                    if p.band != nil {
                        GeometryReader { g in
                            Text("Symbolic").font(.system(size: 10, weight: .bold)).foregroundStyle(.white)
                                .frame(width: g.size.width, height: g.size.height * 0.13)
                                .background(Color(red: 0xB7 / 255, green: 0x1C / 255, blue: 0x1C / 255).opacity(0.93))
                                .position(x: g.size.width / 2, y: g.size.height * 0.645)
                        }
                    }
                }
        } else {
            ShownPicture(s: s)
        }
    }
}

private struct SectionTitle: View {
    let text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Rectangle().fill(keyGrey).frame(height: 1).padding(.top, 12)
            Text(text).font(.headline).foregroundStyle(.white)
        }
    }
}

private struct FieldLabel: View {
    let text: String
    var top: CGFloat = 6
    var body: some View { Text(text).font(.caption).foregroundStyle(Theme.muted).padding(.top, top) }
}

/// What the shop holds of this card: one line per product (language · condition · finish). Tap one to book into it (or sell from it).
private struct ShopEntries: View {
    @ObservedObject var web: WebshopModel

    var body: some View {
        SectionTitle(text: "In your shop")
        if web.loading {
            HStack(spacing: 8) { ProgressView(); Text("Looking it up…").font(.subheadline).foregroundStyle(Theme.muted) }.padding(.top, 4)
        } else if let e = web.lookupError {
            HStack {
                Text(e).font(.subheadline).foregroundStyle(Theme.warn)
                Spacer()
                Button("Retry") { web.lookup() }
            }
        } else if (web.products ?? []).isEmpty {
            Text("Not in the shop yet").font(.subheadline).foregroundStyle(Theme.muted).padding(.top, 2)
        } else {
            let m = web.match
            let list = (web.products ?? []).sorted { a, b in
                (a.cardLanguage != web.form.language ? 1 : 0, a.condition) < (b.cardLanguage != web.form.language ? 1 : 0, b.condition)
            }
            ForEach(list) { p in
                ProductRow(p: p, selected: web.selling ? p.sku == web.sellSku : p.sku == m?.sku) {
                    if web.selling { web.selectSellProduct(p.sku) }
                    else {
                        web.setLanguage(p.cardLanguage)
                        web.setCondition(p.condition)
                        if let k = kindOf(p.finish) { web.setFinish(k) }
                    }
                }
            }
        }
    }
}

private struct ProductRow: View {
    let p: ShopProduct
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text("\(p.cardLanguage) · \(p.condition) · \(ShopTerms.finishLabel(p.finish))" + (p.grading.map { " · \($0)" } ?? ""))
                        .font(.subheadline.bold()).foregroundStyle(.white)
                    Spacer()
                    Text(p.price.map { Money.eur($0.euros) } ?? "no price").font(.subheadline.bold()).foregroundStyle(p.price != nil ? Theme.gold : Theme.warn)
                }
                let boxes = p.stock.boxes.filter { $0.quantity > 0 }.map { "box \($0.box): \($0.quantity)" }.joined(separator: ", ")
                Text("\(p.stock.quantity) in stock" + (p.stock.reserved > 0 ? " (\(p.stock.reserved) in checkouts)" : "") +
                     (boxes.isEmpty ? "" : " · \(boxes)") + " · \(ShopTerms.statusLabel(p.status))")
                    .font(.caption).foregroundStyle(p.status == "active" ? Theme.muted : Theme.warn)
                Text(p.sku).font(.caption2).foregroundStyle(Color(red: 0x6B / 255, green: 0x71 / 255, blue: 0x7C / 255))
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(selected ? Theme.gold.opacity(0.08) : .clear, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? Theme.gold : lineGrey))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 6)
    }
}

/// A storage box, 1–999 (digits only).
private struct BoxField: View {
    let box: Int?
    let onChange: (Int?) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        TextField("e.g. 14", text: $text)
            .keyboardType(.numberPad)
            .focused($focused)
            .font(.title3)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.muted.opacity(0.4)))
            .onChange(of: text) { _, t in
                let d = String(t.filter { "0123456789".contains($0) }.prefix(3))
                if d != t { text = d; return }
                if focused { onChange(Int(d)) }
            }
            .onChange(of: box) { _, b in if !focused { text = b.map(String.init) ?? "" } }
            .onAppear { text = box.map(String.init) ?? "" }
            .toolbar {
                if focused { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { focused = false } } }
            }
    }
}

/// Language, (a comment for a new product,) type (N / H / RH), box, price, condition, then the one button that books one copy (Undo beside it).
private struct ShopFormBlock: View {
    let s: Shown
    @ObservedObject var web: WebshopModel
    let step: Double

    var body: some View {
        let f = web.form
        let m = web.match
        let finish = ShopTerms.finishOf(s.printing)
        SectionTitle(text: m != nil ? "Add copies" : "Add to the shop")
        // language: from the scan, any of the shop's languages can be picked
        HStack {
            FieldLabel(text: "Language", top: 0)
            Menu {
                ForEach(ShopTerms.languages) { l in
                    Button { web.setLanguage(l.code) } label: {
                        if l.code == f.language { Label("\(l.code) · \(l.name)", systemImage: "checkmark") } else { Text("\(l.code) · \(l.name)") }
                    }
                }
            } label: {
                Text("\(f.language) · \(ShopTerms.languageName(f.language)) ▾").font(.subheadline).foregroundStyle(.white)
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.muted.opacity(0.5)))
            }
        }
        .padding(.top, 6)
        if m == nil {
            TextField("Comment (optional, on the product page)", text: Binding(get: { web.form.comment }, set: { web.setComment($0) }), axis: .vertical)
                .lineLimit(1...4)
                .padding(.horizontal, 10).padding(.vertical, 8)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.muted.opacity(0.4)))
                .padding(.top, 6)
        }
        HStack(alignment: .bottom, spacing: 10) {
            // the card type: N / H / RH (1st when the card has a 1st edition)
            VStack(alignment: .leading, spacing: 4) {
                FieldLabel(text: "Type")
                let kinds: [Printing.Kind] = [.normal, .holo, .reverse] + (s.priceCard.printings.contains { $0.kind == .firstEdition } ? [.firstEdition] : [])
                HStack(spacing: 6) {
                    ForEach(kinds, id: \.self) { k in
                        let sel = ShopTerms.finishOf(k) == finish
                        let priced = s.priceCard.printings.contains { $0.kind == k }
                        let repeats = web.printingLock == k
                        Button { web.setFinish(k) } label: {
                            Text(typeShort(k)).font(.system(size: 16, weight: .bold))
                                .foregroundStyle(sel ? Theme.ink : priced ? .white : Theme.muted)
                                .frame(minWidth: 44, minHeight: 44)
                                .background(sel ? Theme.gold : keyGrey, in: RoundedRectangle(cornerRadius: 8))
                                // held for a second: kept for the next cards
                                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(repeats ? Theme.gold : .clear, lineWidth: 2))
                                .overlay(alignment: .topTrailing) {
                                    if repeats {
                                        Image(systemName: "repeat").font(.system(size: 10, weight: .bold)).foregroundStyle(sel ? Theme.ink : Theme.gold).padding(3)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                        .holdForASecond { web.togglePrintingLock(k) }
                        .accessibilityLabel(k.rawValue + (repeats ? ", kept for the next cards" : ""))
                        .accessibilityHint("Hold for a second to keep this type for the next cards")
                        .accessibilityAddTraits(sel ? .isSelected : [])
                    }
                }
            }
            Spacer(minLength: 0)
            // storage box 1–999
            VStack(alignment: .leading, spacing: 4) {
                FieldLabel(text: "Box (1–999)")
                BoxField(box: f.box) { web.setBox($0) }.frame(width: 96)
            }
        }
        .padding(.top, 4)
        // the selling price: − / + and a field to type it
        FieldLabel(text: "Selling price")
        PriceField(title: "tap to set", value: Binding(get: { web.form.price }, set: { web.setPrice($0) }), step: step)
            .padding(.top, 4)
        let market = web.marketValue(s, f.condition)
        Text(priceSource(m, market)).font(.caption).foregroundStyle(Theme.muted).frame(maxWidth: .infinity).padding(.top, 2)
        FieldLabel(text: "Condition", top: 2)
        // sky blue: the shop has copies of this card (language and type as chosen) in stock in that condition
        let inShop = Set((web.products ?? []).filter { $0.cardLanguage == f.language && $0.finish == finish && $0.grading == nil && $0.stock.quantity > 0 }
            .map(\.condition))
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(ShopTerms.conditions) { g in
                    let sel = g.code == f.condition
                    let there = inShop.contains(g.code)
                    Button { web.setCondition(g.code) } label: {
                        Text(g.code).font(.subheadline.weight(sel || there ? .bold : .regular))
                            .foregroundStyle(sel || there ? Theme.ink : .white)
                            .padding(.horizontal, 11).padding(.vertical, 7)
                            .background(sel ? Theme.gold : there ? skyBlue : keyGrey, in: RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(g.label + (there ? ", in stock" : ""))
                    .accessibilityAddTraits(sel ? .isSelected : [])
                }
            }
            .padding(.vertical, 2)
        }
        // the card and its stock, or what the button creates
        Text(summary(m, f, finish)).font(.footnote).foregroundStyle(.white).padding(.top, 8)
        if let b = web.booking, !b.undone {
            ForEach(b.warnings, id: \.self) { w in Text("⚠ \(w)").font(.caption).foregroundStyle(Theme.warn) }
        }
        if let n = web.photoNote { Text("⚠ \(n)").font(.caption).foregroundStyle(Theme.warn) }
        if let e = web.error {
            HStack {
                Text("⚠ \(e)").font(.footnote).foregroundStyle(Theme.warn)
                Spacer()
                if web.retryable { Button("Retry") { web.retry() } }
            }
            .padding(.top, 4)
        }
        if web.showStatus, let b = web.booking {
            Text((b.undone ? "↩ " : "✓ ") + b.text).font(.caption).foregroundStyle(b.undone ? Theme.muted : Theme.sure).padding(.top, 4)
        }
        // the button is the last thing; Undo (the last booking) beside it
        HStack(spacing: 8) {
            Button { web.book() } label: {
                Group {
                    if web.busy { ProgressView().tint(Theme.ink) }
                    else { Text(m != nil ? "Add to stock" : "Create in the shop").font(.headline).lineLimit(1) }
                }
                .frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.gold)
            .foregroundStyle(Theme.ink)
            .disabled(web.busy || f.box == nil || web.loading)
            if let b = web.booking, !b.undone {
                Button(b.sku.hasPrefix("\(web.cardRef ?? "")-") ? "Undo" : "Undo last") { web.undoBooking() }
                    .buttonStyle(.bordered).frame(minHeight: 36).disabled(web.busy)
            }
        }
        .padding(.top, 6)
    }

    /// where the price comes from, under the field
    private func priceSource(_ m: ShopProduct?, _ market: Double?) -> String {
        let f = web.form
        let marketText = market.map { " · market \(Money.eur($0))" } ?? ""
        if m != nil && !f.priceByUser { return "the shop's price" + marketText }
        if let m { return "replaces the shop's \(m.price.map { Money.eur($0.euros) } ?? "")" + marketText }
        if f.priceByUser { return "your price" + marketText }
        if let market, let min = web.minPrice, market < min { return "your minimum (market \(Money.eur(market)))" }
        return "market value for \(f.condition)"
    }

    private func summary(_ m: ShopProduct?, _ f: ShopForm, _ finish: String) -> String {
        if let m {
            let boxes = m.stock.boxes.filter { $0.quantity > 0 }.map(\.box)
            return "\(s.version.name) \(s.version.numberText) \(m.cardLanguage) - \(m.stock.quantity) in stock" + (boxes.isEmpty ? "" : " in boxes " + boxes.joined(separator: ", "))
        }
        let parts = [f.language, f.condition, ShopTerms.finishLabel(finish), s.version.year, s.version.numberText].compactMap { $0 }
        return "\(s.version.name) (" + parts.joined(separator: " · ") + ") is new" + (f.price == nil ? " – without a price it stays a draft" : "")
    }
}

/// Sell: which product and box the copies come from, how many, then into the customer's order.
private struct SellBlock: View {
    @ObservedObject var web: WebshopModel

    var body: some View {
        SectionTitle(text: "Sell")
        if !web.loading {
            if let p = web.products?.first(where: { $0.sku == web.sellSku }) { picked(p) }
            else {
                Text((web.products ?? []).isEmpty ? "Not in the shop – nothing to take out of stock" : "None of these has copies in stock")
                    .font(.subheadline).foregroundStyle(Theme.muted)
            }
        }
    }

    @ViewBuilder private func picked(_ p: ShopProduct) -> some View {
        Text("\(p.cardLanguage) · \(p.condition) · \(ShopTerms.finishLabel(p.finish)) · \(p.price.map { Money.eur($0.euros) } ?? "no price")")
            .font(.subheadline.bold()).foregroundStyle(.white)
        if p.stock.quantity <= 0 {
            Text("No copies of this one in stock").font(.subheadline).foregroundStyle(Theme.muted)
        } else {
            FieldLabel(text: "Out of box")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(p.stock.boxes.filter { $0.quantity > 0 }, id: \.box) { b in
                        Chip(text: "box \(b.box) · \(b.quantity)" + (b.reserved > 0 ? " (\(b.reserved) held)" : ""), selected: b.box == web.sellBox) { web.setSellBox(b.box) }
                    }
                }
            }
            let box = p.stock.boxes.first { $0.box == web.sellBox }
            let inOrder = web.order.filter { $0.sku == p.sku && $0.box == web.sellBox }.reduce(0) { $0 + $1.quantity }
            HStack(spacing: 10) {
                FieldLabel(text: "Copies", top: 0)
                StepButton(label: "−", enabled: web.sellQty > 1) { web.setSellQty(web.sellQty - 1) }
                Text("\(web.sellQty)").font(.title3.bold()).foregroundStyle(.white).frame(minWidth: 28)
                StepButton(label: "+", enabled: box.map { web.sellQty < $0.quantity } ?? false) { web.setSellQty(web.sellQty + 1) }
                if let price = p.price { Text("= \(Money.eur(price.euros * Double(web.sellQty)))").font(.headline).foregroundStyle(Theme.gold) }
            }
            .padding(.top, 6)
            if let box, box.reserved > 0, web.sellQty + inOrder > box.quantity - box.reserved {
                Text("⚠ \(box.reserved) of box \(box.box) is held by an online checkout – selling it shows the product as sold out and the shop tells the admin")
                    .font(.caption).foregroundStyle(Theme.warn)
            }
            if inOrder > 0 { Text("\(inOrder) already in the order from box \(web.sellBox ?? "")").font(.caption).foregroundStyle(Theme.muted) }
            Text("Nothing leaves the stock yet: the order is taken out of stock when you complete the sale (🧾 at the top).")
                .font(.caption).foregroundStyle(Theme.muted).padding(.top, 4)
            if let e = web.orderError, web.sale?.undone == false { Text("⚠ \(e)").font(.footnote).foregroundStyle(Theme.warn) }
            HStack(spacing: 8) {
                Button { web.addToOrder() } label: {
                    Text("Add to the order").font(.headline).lineLimit(1).frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                .tint(sellBlue)
                .foregroundStyle(Theme.ink)
                .disabled(box.map { inOrder >= $0.quantity } ?? true)
                if let sale = web.sale, !sale.undone {
                    Button("Undo sale") { web.undoSale() }.buttonStyle(.bordered).frame(minHeight: 36).disabled(web.orderBusy)
                }
            }
            .padding(.top, 8)
        }
    }
}

private struct StepButton: View {
    let label: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label).font(.system(size: 22, weight: .bold)).foregroundStyle(enabled ? .white : Theme.muted)
                .frame(width: 38, height: 38)
                .background(enabled ? keyGrey : Theme.panel, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// The customer's order: its lines, the total, and the one button that takes it out of stock.
struct OrderSheet: View {
    @ObservedObject var web: WebshopModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if web.order.isEmpty {
                        Text("Empty. In Sell, scan each card the customer wants and add it.").font(.subheadline).foregroundStyle(Theme.muted)
                    }
                    ForEach(web.order) { l in line(l) }
                    if !web.order.isEmpty {
                        HStack(alignment: .lastTextBaseline) {
                            Text("\(web.orderCopies) cards").font(.subheadline).foregroundStyle(Theme.muted)
                            Spacer()
                            Text(Money.eur(web.orderTotal)).font(.system(size: 26, weight: .black)).foregroundStyle(Theme.gold)
                        }
                        .padding(.top, 6)
                        if web.order.contains(where: { $0.price == nil }) { Text("Cards without a price aren't in the total").font(.caption).foregroundStyle(Theme.warn) }
                        Text("Takes every line out of its box in the shop, at once").font(.caption).foregroundStyle(Theme.muted)
                        Button { web.completeSale() } label: {
                            Group {
                                if web.orderBusy { ProgressView().tint(Theme.ink) }
                                else { Text("Complete sale (\(web.orderCopies) cards)").font(.headline).lineLimit(1) }
                            }
                            .frame(maxWidth: .infinity, minHeight: 40)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(sellBlue)
                        .foregroundStyle(Theme.ink)
                        .disabled(web.orderBusy)
                        if let e = web.orderError {
                            HStack {
                                Text("⚠ \(e)").font(.footnote).foregroundStyle(Theme.warn)
                                Spacer()
                                if web.orderRetryable { Button("Retry") { web.retrySale() } }
                            }
                        }
                        if confirmClear {
                            HStack {
                                Text("Empty the order? Nothing changes in the shop.").font(.footnote).foregroundStyle(.white)
                                Spacer()
                                Button("Empty", role: .destructive) { web.clearOrder(); confirmClear = false }
                                Button("Keep") { confirmClear = false }
                            }
                        } else {
                            Button("Empty the order") { confirmClear = true }.font(.subheadline)
                        }
                    }
                    if let sale = web.sale {
                        HStack {
                            Text((sale.undone ? "↩ " : "✓ ") + sale.text).font(.footnote).foregroundStyle(sale.undone ? Theme.muted : sellBlue)
                            Spacer()
                            if !sale.undone { Button("Undo") { web.undoSale() }.disabled(web.orderBusy) }
                        }
                        .padding(10)
                        .background(Color(red: 0x1E / 255, green: 0x26 / 255, blue: 0x33 / 255), in: RoundedRectangle(cornerRadius: 10))
                        .padding(.top, 10)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Theme.panel)
            .navigationTitle("Customer's order")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
        }
    }

    private func line(_ l: ShopOrderLine) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(l.name).font(.subheadline.bold()).foregroundStyle(.white).lineLimit(1)
                    Text("\(l.detail) · box \(l.box)").font(.caption).foregroundStyle(Theme.muted).lineLimit(2)
                }
                Spacer()
                Text(l.total.map { Money.eur($0) } ?? "no price").font(.subheadline.bold()).foregroundStyle(l.price != nil ? Theme.gold : Theme.warn)
            }
            HStack(spacing: 10) {
                StepButton(label: "−", enabled: l.quantity > 1) { web.setOrderLineQty(l.id, l.quantity - 1) }
                Text("\(l.quantity)").font(.headline).foregroundStyle(.white).frame(minWidth: 24)
                StepButton(label: "+", enabled: true) { web.setOrderLineQty(l.id, l.quantity + 1) }
                if let p = l.price { Text("× \(Money.eur(p))").font(.footnote).foregroundStyle(Theme.muted) }
                Spacer()
                Button("Remove", role: .destructive) { web.removeOrderLine(l.id) }.font(.subheadline)
            }
            if let e = l.error { Text("⚠ \(e)").font(.caption).foregroundStyle(Theme.warn) }
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(l.error != nil ? Theme.warn : lineGrey))
    }
}

/// The shop doesn't know the card: send its names along (from the card database, editable).
private struct UnknownCardSheet: View {
    let card: UnknownCard
    @ObservedObject var web: WebshopModel
    @State private var nameDe = ""
    @State private var nameEn = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("German name", text: $nameDe)
                    TextField("English name", text: $nameEn)
                } footer: { Text("The shop's card list doesn't have this card yet. Check its names, then it is created with them.") }
            }
            .navigationTitle("New card for the shop")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        let de = nameDe, en = nameEn
                        dismiss()
                        web.book(nameDe: de, nameEn: en)
                    }
                    .disabled(nameDe.trimmingCharacters(in: .whitespaces).isEmpty && nameEn.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear { nameDe = card.de; nameEn = card.en }
        }
        .presentationDetents([.medium])
    }
}

/// The button on the camera: takes the picture; afterwards it shows it small (a tap takes it again).
private struct ProductPhotoButton: View {
    @ObservedObject var web: WebshopModel

    var body: some View {
        let photo = web.photo.flatMap { $0.cardRef == web.shown?.version.id ? $0 : nil }
        Button { web.takeProductPhoto() } label: {
            HStack(spacing: 6) {
                if web.photoBusy {
                    ProgressView().tint(Theme.gold)
                    Text("Taking the picture…").foregroundStyle(.white)
                } else if let photo {
                    Image(uiImage: photo.thumb).resizable().scaledToFit().frame(height: 36).clipShape(RoundedRectangle(cornerRadius: 4))
                    Text("Product image ✓ · retake").foregroundStyle(.white)
                } else {
                    Text("Create Product Image").bold().foregroundStyle(Theme.gold)
                }
            }
            .font(.subheadline)
            .padding(.leading, photo != nil ? 4 : 14).padding(.trailing, 14)
            .frame(height: 44)
            .background(.black.opacity(0.8), in: Capsule())
            .overlay(Capsule().stroke(Theme.gold, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .disabled(web.photoBusy)
    }
}

/// Settings' list: the shop connected, or not.
struct WebshopConnectionDetail: View {
    @ObservedObject var web: WebshopModel
    var body: some View {
        Text(web.configured ? web.host : "not connected").font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
    }
}

/// Settings › Webshop: the shop's address, key id and secret (kept in the Keychain), the minimum selling price, how it works.
struct WebshopSettingsView: View {
    @ObservedObject var web: WebshopModel
    /// the − / + step lives with Carts & deals (one setting for both)
    @ObservedObject var trading: TradingModel
    @State private var host = ""
    @State private var keyId = ""
    /// never shown back, only replaced
    @State private var secret = ""
    @State private var minPrice = ""
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                Text("The webshop Webshop Mode books into. Create a key in the shop's admin: Admin → API-Schlüssel (one key per phone, no IP allowlist). Key id and secret are shown there once.")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
            Section {
                TextField("Host", text: $host)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
            } header: { Text("Shop") } footer: { Text("Its address, e.g. overpricedcardboard.com, or staging.overpricedcardboard.com for the test shop.") }
            Section("Key id") {
                TextField("kid_…", text: $keyId).textInputAutocapitalization(.never).autocorrectionDisabled()
            }
            Section {
                SecureField(web.hasSecret ? "•••••••• (saved)" : "sk_…", text: $secret).textInputAutocapitalization(.never).autocorrectionDisabled()
            } header: { Text("Secret") } footer: {
                Text(web.hasSecret ? "Saved in this iPhone's Keychain – type a new one only to replace it." : "Kept in this iPhone's Keychain, never shown again.")
            }
            Section {
                Button("Save & test") {
                    web.save(host: host, keyId: keyId, secret: secret)
                    secret = ""
                }
                .disabled(host.trimmingCharacters(in: .whitespaces).isEmpty || keyId.trimmingCharacters(in: .whitespaces).isEmpty
                          || (secret.trimmingCharacters(in: .whitespaces).isEmpty && !web.hasSecret))
                if web.configured { Button("Test again") { web.test() } }
                if web.testing {
                    HStack(spacing: 8) { ProgressView(); Text("Testing the connection…").foregroundStyle(Theme.muted) }
                } else if let c = web.connection {
                    Text((web.connectionOk == true ? "✓ " : "⚠ ") + c).font(.footnote).foregroundStyle(web.connectionOk == true ? Theme.sure : Theme.warn)
                }
            }
            if web.configured || web.hasSecret {
                Section {
                    Button("Disconnect (delete the key from this iPhone)", role: .destructive) {
                        web.disconnect()
                        host = ""; keyId = ""; secret = ""
                    }
                }
            }
            Section {
                HStack {
                    Text("Minimum selling price")
                    Spacer()
                    TextField("e.g. 0.50", text: $minPrice)
                        .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                        .onChange(of: minPrice) { _, t in
                            guard loaded else { return }
                            web.setMinPrice(Double(t.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces)))
                        }
                    Text("€").foregroundStyle(Theme.muted)
                }
            } header: { Text("Prices") } footer: {
                Text("A new product is suggested at least at this price, even if the card is worth only a few cents (empty: no minimum). Your own price and the shop's price of a product stay as they are.")
            }
            Section {
                Picker("− / + changes a price by", selection: $trading.priceStep) {
                    ForEach(TradingModel.priceSteps, id: \.self) { Text(Money.eur($0)).tag($0) }
                }
            } footer: { Text("One tap on − or + beside the selling price. The same setting as in Carts & deals.") }
            Section {
                Toggle("Show Status after Submit", isOn: Binding(get: { web.showStatus }, set: { web.setShowStatus($0) }))
            } footer: { Text("The line above the button after a booking: what was added, the stock now, the price. Warnings and errors always show.") }
            Section("How booking works") {
                Text("Scanning a card only looks it up: you see its prices and what the shop holds of it. Stock changes only when you press Add to stock / Create in the shop, after choosing language, condition, price and box. Undo takes the copies out again.")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
            Section("How selling works") {
                Text("Switch to Sell at the top of Webshop Mode. Scan each card the customer wants and add it to the order (🧾): nothing leaves the stock yet. Complete sale takes the whole order out of stock at once, with the reason \"sold in person\"; Undo puts it back. The shop has no orders for this: the sale shows there as stock changes.")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
        }
        .onAppear {
            host = web.host
            keyId = web.keyId
            minPrice = web.minPrice.map { String(format: "%.2f", $0) } ?? ""
            DispatchQueue.main.async { loaded = true }
        }
    }
}
