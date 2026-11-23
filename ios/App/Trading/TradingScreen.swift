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

/// What the Add to cart popup prices: a scanned card, or one opened in the search.
struct DealCard: Identifiable {
    let id = UUID()
    let version: CardInfo
    let priceCard: CardInfo
    let printing: Printing?
    let group: String
    let image: String?
    let scan: UIImage?
    let pricedFromEnglish: Bool

    init(_ s: Shown) {
        version = s.version; priceCard = s.priceCard; printing = s.selected; group = s.group
        image = s.imageCard?.thumbnailUrl; scan = s.scan; pricedFromEnglish = s.pricedFromEnglish
    }

    /// a card opened in the search (priced from its English version when it has no price of its own)
    init(card: CardInfo, priceCard: CardInfo, printing: Printing?) {
        version = card; self.priceCard = priceCard; self.printing = printing; group = card.id
        image = card.thumbnailUrl; scan = nil; pricedFromEnglish = priceCard.id != card.id || priceCard.lang != card.lang
    }
}

/**
 * Add to cart: the card priced for its condition (in the default price source), I get or I give,
 * the vendor's price with the verdict and what to offer – then into the active cart.
 */
struct DealSheet: View {
    let d: DealCard
    @ObservedObject var trading: TradingModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage(PriceSource.key) private var sourceName = PriceSource.cmTrend.rawValue

    var body: some View {
        let source = PriceSource(rawValue: sourceName) ?? .cmTrend
        let p = d.printing
        let shown = p?.shown(source)
        let ref = TradingMath.reference(p, source)
        let market = ref.map { TradingMath.market($0, trading.condition) }
        let give = trading.side == .GIVE
        let check = d.priceCard.priceCheck
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .top, spacing: 12) {
                        CardPicture(card: d.version, fallback: d.scan).frame(width: 64)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.version.name).font(.title3.bold()).foregroundStyle(.white).lineLimit(2)
                            Text(d.version.setLine()).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                            if let p { Text(p.kind.rawValue).font(.footnote).foregroundStyle(.white) }
                        }
                    }
                    Picker("Side", selection: $trading.side) {
                        Text("I get · buy").tag(Side.GET)
                        Text("I give · sell or trade").tag(Side.GIVE)
                    }
                    .pickerStyle(.segmented)
                    Text("Condition").font(.caption).foregroundStyle(Theme.muted)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) { ForEach(Condition.allCases) { c in Chip(text: c.label, selected: c == trading.condition) { trading.condition = c } } }
                    }
                    // market value for this condition, and where it comes from
                    if let market, let ref, let shown {
                        Text(Money.eur(market)).font(.system(size: 30, weight: .black)).foregroundStyle(Theme.gold)
                        Text("market value · \(trading.condition.label) = \(Int(trading.condition.share * 100)) % of the \(shown.source.label) \(Money.eur(ref))")
                            .font(.caption2).foregroundStyle(Theme.muted)
                    } else {
                        Text(check?.status == .dropped ? "No reliable price to judge by" : "No price to judge by").font(.subheadline.bold()).foregroundStyle(Theme.warn)
                    }
                    // warnings: a thin price is not to be trusted blindly
                    let warnings = [check?.message, TradingMath.fewSales(p) ? "Few sales – check Cardmarket before you pay" : nil,
                                    d.pricedFromEnglish ? "Price of the English version (none for this language)" : nil, ageWarning].compactMap { $0 }
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
                    if let budget = trading.book.show?.budget, budget > 0 { BudgetBar(book: trading.book, budget: budget).padding(.top, 6) }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Theme.ink)
            .navigationTitle("Add to cart")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .safeAreaInset(edge: .bottom) {
                Button {
                    trading.addToCart(d)
                    dismiss()
                } label: {
                    Text((give ? "Add to I give" : "Add to cart") + " · " + (trading.book.active?.name ?? "new cart"))
                        .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .tint(give ? giveBlue : Theme.gold)
                .foregroundStyle(Theme.ink)
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(Theme.ink)
            }
        }
    }

    private var ageWarning: String? {
        guard let u = d.priceCard.prices?.updated, let date = ISO8601DateFormatter().date(from: u) ?? Self.day.date(from: String(u.prefix(10))) else { return nil }
        let days = Int(Date().timeIntervalSince(date) / 86_400)
        return days > 3 ? "Prices are \(days) days old – update the app before a show" : nil
    }

    private static let day: DateFormatter = { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); return f }()

    private func badge(market: Double?, give: Bool, doubt: Bool) -> (String, Color)? {
        guard let a = trading.asking, let market else { return nil }
        if doubt { return ("Check price", Theme.muted) }          // no deal verdict on a doubtful price
        if give { return TradingMath.sellVerdict(a, market).map { ($0.label, $0.color) } }
        return TradingMath.verdict(a, market).map { ($0.label, $0.color) }
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
    @State private var dealsOpen = false
    @State private var showOpen = false

    var body: some View {
        NavigationStack {
            let book = trading.book
            Group {
            if let c = book.active {
                List {
                    if let budget = book.show?.budget, budget > 0 {
                        Section(book.show?.name ?? "Show") { BudgetBar(book: book, budget: budget) }
                    }
                    if c.lines.isEmpty {
                        Text("Nothing in this cart yet. Scan a card and tap Add to cart. For a trade, choose I give there and add your cards.")
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
                                Text("Scan your cards, tap Add to cart and choose I give.").font(.footnote).foregroundStyle(Theme.muted)
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
                    Text("Scan a card and tap Add to cart.").foregroundStyle(Theme.muted)
                    Button("New cart") { trading.newCart() }.buttonStyle(.bordered)
                }
            }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button { dealsOpen = true } label: { Label("Deal history", systemImage: "clock.arrow.circlepath") }
                        Button { showOpen = true } label: { Label(book.show.map { "Show: \($0.name)" } ?? "Show & budget…", systemImage: "calendar") }
                        Button { trading.newCart() } label: { Label("New cart (next booth)", systemImage: "cart.badge.plus") }
                    } label: { Image(systemName: "ellipsis.circle") }
                    .accessibilityLabel("Deals, show and carts")
                }
                ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } }
            }
            .sheet(isPresented: $dealsOpen) { DealsSheet(trading: trading) }
            .sheet(isPresented: $showOpen) { ShowSheet(trading: trading) }
        }
    }
}

struct LineRow: View {
    let l: CartLine
    @Environment(\.cardArtStandIn) private var standIn
    var body: some View {
        HStack(spacing: 10) {
            Group {
                if standIn { DrawnCard(seed: l.cardId) }
                else {
                    AsyncImage(url: l.image.flatMap(URL.init(string:))) { phase in
                        switch phase {
                        case .success(let img): img.resizable().scaledToFit()
                        default: RoundedRectangle(cornerRadius: 3).fill(Theme.panel)
                        }
                    }
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

/// Trading's settings: which Cardmarket price the market value starts from, the start condition, the price step.
/// Settings › Trading (the market value comes from the default price source, Settings › Prices).
struct TradingSettingsForm: View {
    @ObservedObject var trading: TradingModel

    var body: some View {
        Form {
            Picker("A new card starts as", selection: $trading.startCondition) { ForEach(Condition.allCases) { Text($0.label).tag($0) } }
            Picker("− / + changes a price by", selection: $trading.priceStep) {
                ForEach([0.1, 0.5, 1.0, 5.0], id: \.self) { Text(Money.eur($0)).tag($0) }
            }
            Section {
                Text("The market value is the default price (Settings › Prices) for the card's condition. Conditions count as Cardmarket's scale: NM 100 %, EX 85 %, GD 75 %, LP 65 %, PL 50 %, PO 30 % of the price. Verdicts: up to 80 % of the market value a great deal, 95 % good, 110 % fair, above that expensive.")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
        }
    }
}
