import SwiftUI

extension Verdict {
    var color: Color {
        switch self {
        case .GREAT: return Theme.sure
        case .GOOD: return Theme.good
        case .FAIR: return .white
        case .EXPENSIVE: return Theme.warn
        }
    }
}

extension SellVerdict {
    var color: Color {
        switch self {
        case .GOOD: return Theme.sure
        case .USUAL: return .white
        case .LOW: return Theme.warn
        }
    }
}

let giveBlue = Color(red: 0x6F / 255, green: 0xA8 / 255, blue: 0xFF / 255)

/**
 * Trading Mode (card shows): scan a card, see its market value for its condition, type the vendor's
 * price for a verdict, collect cards per booth in a cart with the negotiated price.
 */
struct TradingScreen: View {
    @ObservedObject var scanner: ScannerModel
    @ObservedObject var trading: TradingModel
    @EnvironmentObject var account: AccountModel
    @State private var torch = false
    @State private var cameraAllowed: Bool?
    @State private var cartOpen = false
    @State private var dealsOpen = false
    @State private var showOpen = false
    @State private var search = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ZStack(alignment: .top) {
                if cameraAllowed == false {
                    Text("Trading needs the camera.").foregroundStyle(.white).frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    CameraArea(model: scanner)
                }
                // which side + Cart puts the next card on: the vendor's cards, or yours for a trade or sale
                Button { trading.side = trading.side == .GET ? .GIVE : .GET } label: {
                    Text(trading.side == .GIVE ? "Scanning: I give ⇄" : "Scanning: I get ⇄")
                        .font(.footnote.bold()).foregroundStyle(trading.side == .GIVE ? Theme.ink : .white)
                        .padding(.horizontal, 12).padding(.vertical, 5)
                        .background(trading.side == .GIVE ? giveBlue : Color.black.opacity(0.6), in: Capsule())
                }
                .padding(8)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    if let s = scanner.shown {
                        CardPrice(s: s, scanner: scanner, trading: trading, onSearch: { search = true })
                    } else {
                        Text("Show a card: its market value for its condition appears here. Type the vendor's price for a verdict, then + Cart. Not recognised? Double-tap the picture, or search.")
                            .foregroundStyle(Theme.muted)
                    }
                    if trading.tray.count > 1 { Tray(scanner: scanner, trading: trading) }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Theme.ink)
        .cameraLifecycle(model: scanner, allowed: $cameraAllowed)
        .onChange(of: torch) { _, on in scanner.camera.setTorch(on) }
        .onChange(of: scanner.shown?.serial) { _, _ in if let s = scanner.shown { trading.shown(s) } }
        .sheet(isPresented: $cartOpen) { CartSheet(trading: trading) }
        .sheet(isPresented: $dealsOpen) { DealsSheet(trading: trading) }
        .sheet(isPresented: $showOpen) { ShowSheet(trading: trading) }
        .sheet(isPresented: $search) {
            CardSearchSheet(resolver: scanner.resolver) { c in
                search = false
                if scanner.shown != nil { scanner.replaceWith(c) } else { scanner.lookUp(c) }
            }
        }
        .sheet(item: Binding(get: { scanner.picks.map { PickSheet.Items(list: $0) } }, set: { if $0 == nil { scanner.closePicks() } })) { items in
            PickSheet(list: items.list, shown: scanner.shown, onPick: scanner.pick(_:), onSearch: { scanner.closePicks(); search = true })
                .presentationDetents([.medium, .large])
        }
        .alert("Add the cards to your collection?", isPresented: Binding(get: { trading.offerCart != nil }, set: { if !$0 { trading.offerCart = nil } })) {
            Button("Add") { if let id = trading.offerCart { trading.addCartToCollection(id, account: account) } }
            Button("Not now", role: .cancel) { trading.offerCart = nil }
        } message: { Text("Each with its condition and the price you paid per copy.") }
        .overlay(alignment: .bottom) {
            if let m = trading.message {
                Text(m).font(.footnote).foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 8)
                    .background(.black.opacity(0.8), in: Capsule()).padding(.bottom, 12)
                    .task(id: m) { try? await Task.sleep(nanoseconds: 1_800_000_000); trading.message = nil }
            }
        }
    }

    private var topBar: some View {
        let b = trading.book
        return VStack(spacing: 4) {
            HStack(spacing: 10) {
                Button { showOpen = true } label: {
                    Text(b.show?.name ?? "Start a show").font(.footnote).lineLimit(1).foregroundStyle(b.show == nil ? Theme.muted : .white)
                }
                Spacer()
                Button { cartOpen = true } label: {
                    let c = b.active
                    Label(c.map { "\($0.name) · \($0.cards) · \(Money.eur($0.payTotal))" } ?? "Cart", systemImage: "cart")
                        .font(.footnote.bold()).lineLimit(1)
                }
                Button { dealsOpen = true } label: { Image(systemName: "clock.arrow.circlepath") }.accessibilityLabel("Deal history")
                Button { torch.toggle() } label: { Image(systemName: torch ? "flashlight.on.fill" : "flashlight.off.fill") }
                    .foregroundStyle(torch ? Theme.gold : .white).accessibilityLabel("Light")
            }
            if let budget = b.show?.budget, budget > 0 { BudgetBar(book: b, budget: budget) }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 10).padding(.vertical, 6)
    }
}

/// The show's budget: spent, agreed, and what is left.
struct BudgetBar: View {
    let book: TradingBook
    let budget: Double

    var body: some View {
        let used = book.spent + book.agreed
        let share = min(1, used / budget)
        VStack(alignment: .leading, spacing: 2) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.15))
                    Capsule().fill(share >= TradingMath.budgetWarn ? Theme.warn : Theme.sure).frame(width: g.size.width * share)
                }
            }
            .frame(height: 5)
            Text("Spent \(Money.eur(book.spent))" + (book.agreed > 0 ? " + agreed \(Money.eur(book.agreed))" : "") + " of \(Money.eur(budget)) · left \(Money.eur(budget - used))")
                .font(.caption2).foregroundStyle(Theme.muted)
        }
    }
}

/// A price with − / + beside it (one step each) and a field to type it.
struct PriceField: View {
    let title: String
    @Binding var value: Double?
    let step: Double
    var tint: Color = Theme.muted.opacity(0.4)
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            Button { value = max(0, TradingMath.round2((value ?? 0) - step)) } label: { Image(systemName: "minus") }
                .buttonStyle(.bordered).disabled((value ?? 0) <= 0)
            TextField(title, text: $text)
                .keyboardType(.decimalPad)
                .focused($focused)
                .font(.title3)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(tint))
                .onChange(of: text) { _, t in
                    guard focused else { return }
                    let v = Double(t.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces))
                    value = t.trimmingCharacters(in: .whitespaces).isEmpty ? nil : v.map(TradingMath.round2) ?? value
                }
                .onChange(of: value) { _, v in if !focused { text = Self.format(v) } }
                .onAppear { text = Self.format(value) }
                .toolbar {
                    if focused { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { focused = false } } }
                }
            Button { value = TradingMath.round2((value ?? 0) + step) } label: { Image(systemName: "plus") }.buttonStyle(.bordered)
        }
    }

    static func format(_ v: Double?) -> String { v.map { String(format: "%.2f", locale: .current, $0) } ?? "" }
}

/// The card shown, priced for its condition, with the vendor's price and the verdict.
struct CardPrice: View {
    let s: Shown
    @ObservedObject var scanner: ScannerModel
    @ObservedObject var trading: TradingModel
    let onSearch: () -> Void
    @EnvironmentObject var account: AccountModel

    var body: some View {
        let p = s.selected
        let ref = TradingMath.reference(p, trading.reference)
        let market = ref.map { TradingMath.market($0, trading.condition) }
        let give = trading.side == .GIVE
        let check = s.priceCard.priceCheck
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 12) {
                ShownPicture(s: s).frame(width: 84)
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.version.name).font(.title2.bold()).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.6)
                    Text(s.version.setLine()).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1).minimumScaleFactor(0.7)
                    if let e = s.english { Text("English: \(e.name)").font(.caption).foregroundStyle(.white) }
                    // condition: the camera can't judge it, so it's one tap here
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) { ForEach(Condition.allCases) { c in Chip(text: c.label, selected: c == trading.condition) { trading.condition = c } } }
                    }
                    .padding(.top, 4)
                }
            }
            if s.priceCard.printings.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(s.priceCard.printings, id: \.kind) { pr in
                            Chip(text: "\(pr.kind.rawValue) \(pr.priceText)", selected: pr.kind == p?.kind) { scanner.choosePrinting(pr.kind) }
                        }
                    }
                }
            }
            // market value for this condition, and where it comes from
            if let market, let ref {
                HStack(alignment: .lastTextBaseline, spacing: 10) {
                    Text(Money.eur(market)).font(.system(size: 30, weight: .black)).foregroundStyle(Theme.gold)
                    if let t = TradingMath.trend(p), abs(t) >= 0.005 {
                        Text("\(t > 0 ? "▲" : "▼") \(Int((abs(t) * 100).rounded())) % vs 30 days").font(.footnote).foregroundStyle(t > 0 ? Theme.sure : Theme.warn)
                    }
                }
                Text("market value · \(trading.condition.label) = \(Int(trading.condition.share * 100)) % of the \(trading.reference.label.lowercased()) \(Money.eur(ref))")
                    .font(.caption2).foregroundStyle(Theme.muted)
            } else {
                let head = check?.status == .dropped ? "No reliable Cardmarket price" : "No Cardmarket price"
                Text(head + (p?.usd.map { " · TCGplayer \(Money.usd($0))" + (p?.usdEur.map { " ≈ \(Money.eur($0))" } ?? "") } ?? ""))
                    .font(.subheadline.bold()).foregroundStyle(Theme.warn)
            }
            let parts = [p?.eur.map { "trend \(Money.eur($0))" }, p?.eur30.map { "30-day \(Money.eur($0))" }, p?.eurLow.map { "from \(Money.eur($0))" },
                         market != nil ? p?.usd.map { "TCGplayer \(Money.usd($0))" } : nil].compactMap { $0 }
            if !parts.isEmpty { Text(parts.joined(separator: " · ")).font(.caption).foregroundStyle(.white) }
            // warnings: a thin price is not to be trusted blindly
            let warnings = [check?.message, TradingMath.fewSales(p) ? "Few sales – check Cardmarket before you pay" : nil,
                            s.pricedFromEnglish ? "Price of the English version (none for this language)" : nil, ageWarning].compactMap { $0 }
            ForEach(warnings, id: \.self) { w in Text("⚠ \(w)").font(.caption).foregroundStyle(Theme.warn) }

            // the vendor's price and the verdict (your own card: the vendor's offer for it)
            HStack {
                Text(give ? "Vendor's offer for your card" : "Asking price").font(.caption).foregroundStyle(Theme.muted)
                Spacer()
                if let badge = badge(market: market, give: give, doubt: check != nil) {
                    Text(badge.0).font(.subheadline.bold()).foregroundStyle(Theme.ink)
                        .padding(.horizontal, 10).padding(.vertical, 4).background(badge.1, in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .padding(.top, 4)
            PriceField(title: give ? "Type the vendor's offer" : "Type the asking price", value: $trading.asking, step: trading.priceStep,
                       tint: give ? giveBlue : Theme.muted.opacity(0.4))
                .id(s.serial)
            if let market {
                let pct = trading.asking.map { " · \(give ? "offer" : "asking") = \(Int(($0 / market * 100).rounded())) % of market" } ?? ""
                if give {
                    let (lo, hi) = TradingMath.sellRange(market)
                    let rate = trading.book.active.flatMap { $0.isTrade ? $0.giveRate : nil } ?? TradingMath.giveRate
                    Text("Vendors usually pay \(Money.eur(lo)) – \(Money.eur(hi)) · in a trade at \(Int((rate * 100).rounded())) %: \(Money.eur(TradingMath.round2(market * rate)))\(pct)")
                        .font(.caption).foregroundStyle(Theme.muted)
                } else {
                    Text("Offer \(Money.eur(TradingMath.offer(market))) · walk away above \(Money.eur(TradingMath.walkAway(market)))\(pct)")
                        .font(.caption).foregroundStyle(Theme.muted)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    Text("Not this card?").font(.caption).foregroundStyle(Theme.muted)
                    if !s.fromSearch { ForEach(s.alternatives) { alt in Chip(text: alt.info.name, selected: false) { scanner.choose(alt) } } }
                    Chip(text: "🔍 Search…", selected: false, action: onSearch)
                }
            }
            Button { trading.addToCart(s) } label: {
                Text((give ? "+ I give" : "+ Cart") + (trading.book.active.map { " (\($0.name))" } ?? "")).font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(give ? giveBlue : Theme.gold)
            .foregroundStyle(Theme.ink)
            .padding(.top, 4)
            if !give { CollectionBar(account: account, s: s, condition: trading.condition.rawValue) }
        }
    }

    private var ageWarning: String? {
        guard let d = s.priceCard.prices?.updated, let date = ISO8601DateFormatter().date(from: d) ?? Self.day.date(from: String(d.prefix(10))) else { return nil }
        let days = Int(Date().timeIntervalSince(date) / 86_400)
        return days > 3 ? "Prices are \(days) days old – update the app before a show" : nil
    }

    private static let day: DateFormatter = { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); return f }()

    private func badge(market: Double?, give: Bool, doubt: Bool) -> (String, Color)? {
        guard let a = trading.asking, let market else { return nil }
        if doubt { return ("Check price", Theme.muted) }          // no deal verdict on a doubtful Cardmarket price
        if give { return TradingMath.sellVerdict(a, market).map { ($0.label, $0.color) } }
        return TradingMath.verdict(a, market).map { ($0.label, $0.color) }
    }
}

/// The last scanned cards; tap one to bring it back.
struct Tray: View {
    @ObservedObject var scanner: ScannerModel
    @ObservedObject var trading: TradingModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Last scans").font(.caption).foregroundStyle(Theme.muted)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(trading.tray.reversed(), id: \.serial) { item in
                        Button { scanner.show(item) } label: {
                            VStack(spacing: 2) {
                                ShownPicture(s: item).frame(width: 48)
                                    .overlay(RoundedRectangle(cornerRadius: 4).stroke(item.serial == scanner.shown?.serial ? Theme.gold : .clear, lineWidth: 2))
                                Text(item.version.name).font(.system(size: 10)).foregroundStyle(.white).lineLimit(1).frame(width: 52)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.top, 10)
    }
}

/// The active cart: its cards (I get, I give), totals, trade balance, agree a total, paid.
struct CartSheet: View {
    @ObservedObject var trading: TradingModel
    @Environment(\.dismiss) private var dismiss
    @State private var editing: CartLine?
    @State private var renaming = false
    @State private var name = ""
    @State private var paying = false
    @State private var confirmDelete = false
    @State private var agreeing = false
    @State private var total: Double?
    @State private var cashText: Double?

    var body: some View {
        NavigationStack {
            let book = trading.book
            if let c = book.active {
                List {
                    if c.lines.isEmpty {
                        Text("Nothing in this cart yet. Scan a card and tap + Cart. For a trade, switch the camera's target to \"I give\" and scan your cards.")
                            .foregroundStyle(Theme.muted)
                    }
                    if !c.getLines.isEmpty || c.isTrade {
                        Section(c.isTrade ? "I get · \(c.cards) · \(Money.eur(c.getValue)) (market \(Money.eur(c.marketTotal)))" : "Cards · \(c.cards)") {
                            ForEach(c.lines.filter { $0.side == .GET && $0.active }) { l in LineRow(l: l).onTapGesture { editing = l } }
                        }
                    }
                    if c.isTrade {
                        Section("I give · \(c.giveCards) · \(Money.eur(c.giveValue)) (market \(Money.eur(c.marketGive)))") {
                            ForEach(c.lines.filter { $0.side == .GIVE && $0.active }) { l in LineRow(l: l).onTapGesture { editing = l } }
                            if c.giveLines.isEmpty {
                                Text("Switch the camera's target to \"I give\" (top of the picture) and scan your cards.").font(.footnote).foregroundStyle(Theme.muted)
                            }
                        }
                    }
                    let dropped = c.lines.filter { !$0.active }
                    if !dropped.isEmpty {
                        Section("Dropped (\(dropped.count))") { ForEach(dropped) { l in LineRow(l: l).onTapGesture { editing = l } } }
                    }
                    Section {
                        if c.isTrade {
                            TotalLine(label: "Your cards count at", value: "\(Int((c.giveRate * 100).rounded())) %")
                            Stepper("", value: Binding(get: { c.giveRate }, set: { trading.setRates(c.id, give: TradingMath.round2($0)) }), in: 0.5...1.0, step: 0.05).labelsHidden()
                            TotalLine(label: c.balance >= 0 ? "You pay" : "The vendor pays you", value: Money.eur(abs(c.balance)), strong: true)
                            HStack {
                                Text("Cash agreed").font(.footnote).foregroundStyle(Theme.muted)
                                PriceField(title: "the balance", value: Binding(get: { c.cash }, set: { trading.setCash(c.id, $0) }), step: trading.priceStep)
                            }
                            TotalLine(label: "At market value", value: (c.netAtMarket >= 0 ? "+" : "") + Money.eur(c.netAtMarket),
                                      color: c.netAtMarket >= 0 ? Theme.sure : Theme.warn)
                        } else {
                            TotalLine(label: "Market value", value: Money.eur(c.marketTotal))
                            TotalLine(label: "Asking", value: Money.eur(c.askingTotal))
                            TotalLine(label: "You pay", value: Money.eur(c.payTotal), strong: true)
                            if c.savedVsMarket != 0 { TotalLine(label: "Saved vs market", value: Money.eur(c.savedVsMarket), color: c.savedVsMarket > 0 ? Theme.sure : Theme.warn) }
                            if c.unpriced > 0 { Text("\(c.unpriced) card\(c.unpriced > 1 ? "s" : "") without a price yet").font(.caption).foregroundStyle(Theme.warn) }
                        }
                        if c.withoutMarket > 0 { Text("\(c.withoutMarket) card\(c.withoutMarket > 1 ? "s" : "") without a market value").font(.caption).foregroundStyle(Theme.muted) }
                    }
                    Section {
                        if !c.isTrade {
                            Button("Agree a total for all cards…") { total = c.payTotal > 0 ? c.payTotal : nil; agreeing = true }.disabled(c.getLines.isEmpty)
                        }
                        Button(c.isTrade ? "No trade" : "Trade (I give cards too)") { trading.setTrade(c.id, !c.isTrade) }
                            .disabled(c.isTrade && !c.giveLines.isEmpty)
                        Button(c.isTrade ? "Done" : "Paid") { paying = true }.disabled(c.activeLines.isEmpty)
                        Button("Next booth (new cart)") { trading.newCart() }
                    }
                    Section("Carts") {
                        ForEach(book.open) { o in
                            Button { trading.selectCart(o.id) } label: {
                                HStack { Text((o.id == c.id ? "✓ " : "") + o.name); Spacer(); Text("\(o.cards) · \(Money.eur(o.payTotal))").foregroundStyle(Theme.muted) }
                            }
                        }
                        Button("Rename this cart") { name = c.name; renaming = true }
                        Button("Delete this cart", role: .destructive) { confirmDelete = true }
                    }
                }
                .navigationTitle(c.name)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
                .sheet(item: $editing) { l in LineEditor(trading: trading, cartId: c.id, line: l) }
                .alert("Name of this cart", isPresented: $renaming) {
                    TextField("e.g. Max, table 14", text: $name)
                    Button("Done") { trading.renameCart(c.id, name) }
                    Button("Cancel", role: .cancel) {}
                }
                .confirmationDialog(c.isTrade ? "Trade done?" : "Paid \(Money.eur(c.payTotal))?", isPresented: $paying, titleVisibility: .visible) {
                    ForEach(["Cash", "Card", "Other"], id: \.self) { m in Button(m) { trading.markPaid(c.id, m, collection: true); dismiss() } }
                    Button("Not yet", role: .cancel) {}
                } message: { Text("The cart goes into the deal history.") }
                .confirmationDialog("Delete \(c.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                    Button("Delete", role: .destructive) { trading.deleteCart(c.id) }
                    Button("Keep", role: .cancel) {}
                } message: { Text("Its \(c.lines.count) lines are removed from the phone.") }
                .sheet(isPresented: $agreeing) {
                    NavigationStack {
                        Form {
                            Text("One price for all cards: it is split over them by their market values, to the cent.").font(.footnote).foregroundStyle(Theme.muted)
                            PriceField(title: "Total", value: $total, step: trading.priceStep)
                        }
                        .navigationTitle("Agree a total")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { agreeing = false } }
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Agree") { if let t = total { trading.agreeTotal(c.id, c.getLines.map(\.id), t) }; agreeing = false }.disabled(total == nil)
                            }
                        }
                    }
                    .presentationDetents([.medium])
                }
            } else {
                VStack(spacing: 12) {
                    Text("No cart yet").font(.title3.bold())
                    Text("Scan a card and tap + Cart.").foregroundStyle(Theme.muted)
                    Button("New cart") { trading.newCart() }.buttonStyle(.bordered)
                }
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
            }
        }
    }
}

struct LineRow: View {
    let l: CartLine
    var body: some View {
        HStack(spacing: 10) {
            AsyncImage(url: l.image.flatMap(URL.init(string:))) { phase in
                switch phase {
                case .success(let img): img.resizable().scaledToFit()
                default: RoundedRectangle(cornerRadius: 3).fill(Theme.panel)
                }
            }
            .frame(width: 34, height: 48)
            VStack(alignment: .leading, spacing: 1) {
                Text((l.qty > 1 ? "\(l.qty)× " : "") + l.name).font(.subheadline.weight(.semibold)).strikethrough(!l.active)
                Text([l.condition.label, l.printingLabel, l.status == .CONSIDERING ? nil : l.status.label].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption2).foregroundStyle(Theme.muted)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 1) {
                Text(Money.eur(l.price)).font(.subheadline.monospacedDigit())
                Text("market \(Money.eur(l.market))").font(.caption2).foregroundStyle(Theme.muted)
                if let v = l.verdict { Text(v.label).font(.caption2.bold()).foregroundStyle(v.color) }
                if let v = l.sellVerdict { Text(v.label).font(.caption2.bold()).foregroundStyle(v.color) }
            }
        }
        .contentShape(Rectangle())
    }
}

struct TotalLine: View {
    let label: String
    let value: String
    var strong = false
    var color: Color = .white
    var body: some View {
        HStack {
            Text(label).foregroundStyle(Theme.muted)
            Spacer()
            Text(value).font(strong ? .headline : .body).foregroundStyle(color)
        }
    }
}

/// One card of the cart: condition, quantity, prices, status.
struct LineEditor: View {
    @ObservedObject var trading: TradingModel
    let cartId: Int64
    @State var line: CartLine
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(line.name).font(.headline)
                    Text(line.setLine).font(.footnote).foregroundStyle(Theme.muted)
                    Picker("Condition", selection: $line.condition) { ForEach(Condition.allCases) { Text($0.label).tag($0) } }
                    Stepper("Quantity: \(line.qty)", value: $line.qty, in: 1...999)
                    TotalLine(label: "Market value each", value: Money.eur(line.market))
                }
                Section(line.side == .GIVE ? "The vendor's offer each" : "Asking each") { PriceField(title: "–", value: $line.asking, step: trading.priceStep) }
                if line.side == .GET { Section("Negotiated each") { PriceField(title: "–", value: $line.negotiated, step: trading.priceStep) } }
                Section {
                    Picker("Status", selection: $line.status) { ForEach(LineStatus.allCases, id: \.self) { Text($0.label).tag($0) } }
                    if let v = line.verdict { Text(v.label).foregroundStyle(v.color) }
                    if let v = line.sellVerdict { Text(v.label).foregroundStyle(v.color) }
                }
            }
            .navigationTitle("Card")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { let l = line; trading.updateLine(cartId, l.id) { _ in l }; dismiss() }
                }
            }
        }
    }
}

/// A show (or a day of trading): its name and budget.
struct ShowSheet: View {
    @ObservedObject var trading: TradingModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var budget: Double?

    var body: some View {
        NavigationStack {
            Form {
                Text("Carts started from now on belong to this show; its summary is in the deal history.").font(.footnote).foregroundStyle(Theme.muted)
                TextField("e.g. Vienna Card Show", text: $name)
                Section("Budget € (optional)") { PriceField(title: "none", value: $budget, step: 10) }
                if let sh = trading.book.show {
                    let s = DealSummary.of(trading.book.paid.filter { $0.showId == sh.id })
                    Text("\(s.deals) deals so far · spent \(Money.eur(s.spent)) · market value in \(Money.eur(s.marketIn))").font(.caption).foregroundStyle(Theme.muted)
                }
            }
            .navigationTitle(trading.book.show == nil ? "Start a show" : "Show")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { name = trading.book.show?.name ?? ""; budget = trading.book.show?.budget }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(trading.book.show == nil ? "Start" : "Save") {
                        let n = name.trimmingCharacters(in: .whitespaces).isEmpty ? "Show \(Date().formatted(date: .abbreviated, time: .omitted))" : name
                        if trading.book.show == nil { trading.startShow(n, budget: budget) } else { trading.updateShow(n, budget: budget) }
                        dismiss()
                    }
                }
                if trading.book.show != nil {
                    ToolbarItem(placement: .bottomBar) {
                        Button("New show") {
                            trading.startShow(name.isEmpty ? "Show \(Date().formatted(date: .abbreviated, time: .omitted))" : name, budget: budget); dismiss()
                        }
                    }
                }
            }
        }
    }
}

/// The deal history: paid carts by show, with their totals and CSV.
struct DealsSheet: View {
    @ObservedObject var trading: TradingModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            let paid = trading.book.paid
            List {
                if paid.isEmpty { Text("No deals yet. A cart lands here when you tap Paid (or Done for a trade).").foregroundStyle(Theme.muted) }
                let groups = Dictionary(grouping: paid) { $0.showId ?? -1 }
                ForEach(groups.keys.sorted(by: >), id: \.self) { key in
                    let carts = groups[key] ?? []
                    let show = trading.book.shows.first { $0.id == key }
                    let s = DealSummary.of(carts)
                    Section {
                        Text("\(s.deals) deal\(s.deals > 1 ? "s" : "") · spent \(Money.eur(s.spent))" + (s.received > 0 ? " · received \(Money.eur(s.received))" : "")
                             + " · market value in \(Money.eur(s.marketIn))").font(.caption).foregroundStyle(Theme.muted)
                        ForEach(carts) { c in
                            VStack(alignment: .leading, spacing: 3) {
                                HStack {
                                    Text(c.name).font(.headline)
                                    Spacer()
                                    Text(Money.eur(c.settle)).font(.headline.monospacedDigit())
                                }
                                Text("\(Date(timeIntervalSince1970: TimeInterval(c.paidAt ?? 0) / 1000).formatted(date: .abbreviated, time: .shortened)) · \(c.payment ?? "") · \(c.cards) in" + (c.giveCards > 0 ? " · \(c.giveCards) out" : ""))
                                    .font(.caption).foregroundStyle(Theme.muted)
                                ForEach(c.lines.filter(\.active)) { l in
                                    Text("\(l.side == .GIVE ? "→ " : "")\(l.qty > 1 ? "\(l.qty)× " : "")\(l.name) · \(l.condition.label) · \(Money.eur(l.price))")
                                        .font(.caption2)
                                }
                                HStack {
                                    Button("Reopen") { trading.reopen(c.id); dismiss() }.buttonStyle(.bordered)
                                    if let url = trading.csvFile([c]) { ShareLink("CSV", item: url).buttonStyle(.bordered) }
                                }
                            }
                        }
                    } header: { Text(show?.name ?? "Without a show") }
                }
            }
            .navigationTitle("Deal history")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                if !paid.isEmpty, let url = trading.csvFile(paid) {
                    ToolbarItem(placement: .confirmationAction) { ShareLink("CSV", item: url) }
                }
            }
        }
    }
}
