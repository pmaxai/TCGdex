import Combine
import Foundation
import UIKit

/// Webshop Mode: what goes into the shop for the shown card (the person fills it in; nothing is booked by itself).
struct ShopForm: Equatable {
    var language = "EN"
    var languageByUser = false
    var condition = "NM"
    var quantity = 1
    /// selling price, EUR; suggested from the market value (or the shop's price) until the person types one
    var price: Double?
    var priceByUser = false
    /// storage box 1–999
    var box: Int?
    /// shown on the product page (description) of a new product
    var comment = ""
}

/**
 * The price a new product is suggested at: its market value, but never below the minimum selling
 * price. Without a market value there is no suggestion (the card may be worth anything).
 */
func suggestedPrice(_ market: Double?, _ minPrice: Double?) -> Double? {
    market.map { m in if let minPrice, m < minPrice { return minPrice }; return m }
}

/// Webshop Mode, Sell: one line of a customer's order (copies of one product out of one box).
struct ShopOrderLine: Equatable, Codable, Identifiable {
    let sku: String
    let name: String
    /// "DE · NM · Holo · 199/165"
    let detail: String
    let box: String
    var quantity: Int
    /// the shop's price of one copy, EUR (nil: none)
    let price: Double?
    /// the last attempt to take it out failed: why (not kept)
    var error: String? = nil

    var id: String { sku + "|" + box }
    var total: Double? { price.map { $0 * Double(quantity) } }

    enum CodingKeys: String, CodingKey { case sku, name, detail, box, quantity, price }
}

/// A completed sale: what was taken out (for Undo).
struct ShopSale: Equatable {
    var text: String
    var lines: [ShopOrderLine]
    var undone = false
}

/// A product picture taken with the camera: for cardRef, as JPEG, and small for the screen.
struct ShopPhoto {
    let cardRef: String
    let jpeg: Data
    let thumb: UIImage
}

/// A booking done in this session, for its confirmation and Undo.
struct ShopBooking: Equatable {
    var text: String
    var warnings: [String]
    let sku: String
    let delta: Int
    let box: String
    var undone = false
}

/// The shop doesn't know the card: the names to send along (German, English), for the person to confirm.
struct UnknownCard: Identifiable, Equatable {
    let de: String
    let en: String
    var id: String { de + "|" + en }
}

extension Shown {
    /// the printing picked (also one the price data doesn't have: then nil)
    var pickedPrinting: Printing? { printing == nil ? selected : priceCard.printings.first { $0.kind == printing } }
    /// a picture of this card as printed in its language (TCGdex's are per language; the second source's are English)
    var hasPictureInItsLanguage: Bool { printedOnly == nil && (version.imageUrl != nil || (version.lang == "en" && version.highUrl != nil)) }
    /// the large picture of this card in its language, as the app shows it; for the shop (nil: none, a photo is needed)
    var pictureUrl: String? { hasPictureInItsLanguage ? version.highUrl : nil }
    /// "Printed in Korean – …": the print isn't in the card data, another one is shown
    var printedOnlyNote: String? {
        guard let lang = printedOnly else { return nil }
        let name = ["ja": "Japanese", "ko": "Korean", "zh-tw": "Chinese (traditional)", "zh-cn": "Chinese (simplified)"][lang] ?? lang
        let shownText = CardResolver.asian.contains(version.lang) ? "its Japanese original (same set and number)" : "the \(version.lang.uppercased()) card of the same picture"
        return "Printed in \(name) – this print isn't in the card data; shown: \(shownText)"
    }
}

/**
 * Webshop Mode (the Scanner tab's third mode, with a connected webshop): scan or search a card,
 * see its prices and what the shop holds of it, and book copies into the shop – or, in Sell,
 * collect a customer's order and take it out of stock. A scan changes nothing: stock changes only
 * with a button. (Port of the Android app's ScannerViewModel, Webshop Mode.)
 */
@MainActor
final class WebshopModel: ObservableObject {
    // the connection
    @Published private(set) var configured = false
    @Published private(set) var host = ""
    @Published private(set) var keyId = ""
    @Published private(set) var hasSecret = false
    /// the lowest price a new product is suggested at, EUR (nil: none)
    @Published private(set) var minPrice: Double?
    /// the last connection test: its text, and whether it worked (nil: not tested)
    @Published private(set) var connection: String?
    @Published private(set) var connectionOk: Bool?
    @Published private(set) var testing = false
    // the card in the shop
    /// the scanner's card (kept here, so the panel isn't drawn again with every camera frame)
    @Published private(set) var shown: Shown?
    @Published private(set) var cardRef: String?
    @Published private(set) var loading = false
    @Published private(set) var products: [ShopProduct]?
    /// the shop's card catalogue has a picture of the card (nil: not known)
    @Published private(set) var catalogPicture: Bool?
    @Published private(set) var lookupError: String?
    /// a product picture taken with the camera for the shown card; it goes up with the next booking
    @Published private(set) var photo: ShopPhoto?
    @Published private(set) var photoBusy = false
    /// about the product picture: why it couldn't be taken or didn't go up
    @Published private(set) var photoNote: String?
    @Published private(set) var form = ShopForm()
    /// a booking is on its way
    @Published private(set) var busy = false
    /// the last booking failed; retryable: sending it again (same idempotency key) may work
    @Published private(set) var error: String?
    @Published private(set) var retryable = false
    @Published private(set) var booking: ShopBooking?
    @Published var unknownCard: UnknownCard?
    // Sell
    /// a customer's order is collected (copies come out of stock), instead of Stock in
    @Published private(set) var selling = false
    @Published private(set) var sellSku: String?
    @Published private(set) var sellBox: String?
    @Published private(set) var sellQty = 1
    /// the customer's order so far (kept on the phone until it's sold or cleared)
    @Published private(set) var order: [ShopOrderLine] = []
    @Published private(set) var orderBusy = false
    /// the sale failed as a whole; orderRetryable: sending it again (same key) may work
    @Published private(set) var orderError: String?
    @Published private(set) var orderRetryable = false
    /// the last completed sale, for its confirmation and Undo
    @Published private(set) var sale: ShopSale?
    /// a short note for the person (a toast)
    @Published var message: String?

    var orderCopies: Int { order.reduce(0) { $0 + $1.quantity } }
    var orderTotal: Double { order.reduce(0) { $0 + ($1.total ?? 0) } }

    let scanner: ScannerModel
    private let store: ShopStore
    private let clock = ServerClock()
    private let session: URLSession?
    private let sleep: @Sendable (UInt64) async -> Void
    private let orderFile: URL
    private var shopClient: (tag: String, client: ShopClient)?
    /// the booking last sent: a retry sends the same bytes with the same idempotency key
    private var pendingShop: (write: PendingWrite, label: String, delta: Int, box: String)?
    /// the sale last sent: a retry sends the same batch with the same idempotency key
    private var pendingSale: (write: PendingWrite, lines: [ShopOrderLine])?
    /// the undo last sent, for its sale: pressing Undo again after a failure reuses its key
    private var pendingUndo: (sale: ShopSale, write: PendingWrite)?
    private var lookupTask: Task<Void, Never>?
    private var subscriptions = Set<AnyCancellable>()

    init(scanner: ScannerModel, store: ShopStore = ShopStore(), session: URLSession? = nil,
         sleep: @escaping @Sendable (UInt64) async -> Void = { ms in try? await Task.sleep(nanoseconds: ms * 1_000_000) },
         orderFile: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("shop-order.json")) {
        self.scanner = scanner
        self.store = store
        self.session = session
        self.sleep = sleep
        self.orderFile = orderFile
        loadConnection()
        loadOrder()
        scanner.$shown.assign(to: &$shown)
        // a new card (or language, printing) in Webshop Mode: look it up in the shop, fill in the form
        Publishers.CombineLatest(scanner.$shown, scanner.$settings.map(\.webshop).removeDuplicates())
            .map { pair -> (key: [String]?, shown: Shown?) in
                let (s, on) = pair
                return (on ? s.map { [$0.version.id, $0.version.lang, $0.printing?.rawValue ?? "", $0.printedOnly ?? ""] } : nil, s)
            }
            .removeDuplicates { $0.key == $1.key }
            .sink { [weak self] v in if v.key != nil, let s = v.shown { self?.onShopCard(s) } }
            .store(in: &subscriptions)
    }

    // ------------------------------------------------------------------ the connection

    private func client() -> ShopClient? {
        guard let c = store.credentials() else { return nil }
        let tag = "\(c.baseUrl)|\(c.keyId)|\(c.secret.hashValue)"
        if let sc = shopClient, sc.tag == tag { return sc.client }
        let client = ShopClient(c, clock: clock, session: session, sleep: sleep)
        shopClient = (tag, client)
        return client
    }

    private func loadConnection() {
        configured = store.credentials() != nil
        host = store.host
        keyId = store.keyId
        hasSecret = store.hasSecret
        minPrice = store.minPrice
    }

    /// The account's connected webshop: its host fills in an empty connection (key and secret are still typed in).
    func applyAccount(has: Bool, host accountHost: String?) {
        guard has, store.host.trimmingCharacters(in: .whitespaces).isEmpty, let h = accountHost, !h.isEmpty else { return }
        store.save(host: h, keyId: store.keyId, secret: nil)
        loadConnection()
    }

    /// Settings › Webshop: the minimum selling price (nil or 0: none). The form's suggestion follows at once.
    func setMinPrice(_ v: Double?) {
        store.minPrice = v.map(TradingMath.round2).flatMap { $0 > 0 ? $0 : nil }
        minPrice = store.minPrice
        if let s = shown { form = withSuggestedPrice(form, s, products) }
    }

    /// Saves the connection (an empty secret keeps the stored one) and tests it.
    func save(host h: String, keyId k: String, secret: String) {
        guard ShopCredentials.baseUrlOf(h) != nil else {
            connection = "Not a valid host – e.g. overpricedcardboard.com or staging.overpricedcardboard.com"
            connectionOk = false
            return
        }
        store.save(host: h, keyId: k, secret: secret.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : secret)
        shopClient = nil
        loadConnection()
        test()
    }

    /// GET /health: key, secret and clock work.
    func test() {
        guard let c = client() else { connection = "Host, key id and secret are needed"; connectionOk = false; return }
        testing = true
        Task { [weak self] in
            do {
                let h = try await c.health()
                guard let self else { return }
                testing = false
                connectionOk = true
                connection = "Connected as \(h.keyId)" + (h.serverTime.map { " · shop time \(String($0.prefix(16)).replacingOccurrences(of: "T", with: " ")) UTC" } ?? "")
                if scanner.settings.webshop, let s = shown { onShopCard(s, force: true) }
            } catch {
                guard let self else { return }
                testing = false
                connectionOk = false
                connection = Self.text(error)
            }
        }
    }

    /// Deletes the key from this phone (the minimum price is a setting, not part of the key).
    func disconnect() {
        store.clear()
        shopClient = nil
        connection = nil
        connectionOk = nil
        products = nil
        cardRef = nil
        lookupError = nil
        loadConnection()
    }

    /// for the person: the shop's answer in words
    static func text(_ e: Error) -> String { (e as? ShopError)?.friendly ?? e.localizedDescription }

    // ------------------------------------------------------------------ the card

    /// The shown card changed (Webshop Mode): fill in the form and look the card up in the shop.
    func onShopCard(_ s: Shown, force: Bool = false) {
        let newCard = force || cardRef != s.version.id
        // an Asian print not in the data yet: the shop gets its language (the card is the western one of the same picture)
        let lang = ShopTerms.languageOf(s.printedOnly ?? s.version.lang)
        var f: ShopForm
        if newCard { f = ShopForm(language: lang, condition: store.lastCondition, box: store.lastBox) }
        else { f = form; if !f.languageByUser { f.language = lang } }
        form = withSuggestedPrice(f, s, newCard ? nil : products)
        unknownCard = nil
        if photo?.cardRef != s.version.id { photo = nil }
        if newCard {
            error = nil
            // the last booking's warnings belong to that card (the booking itself stays, for Undo)
            if booking != nil { booking?.warnings = [] }
            photoNote = nil
            sellSku = nil
            sellBox = nil
            lookup(s.version.id)
        }
    }

    /// Looks the card up in the shop: every product of it (languages, conditions, finishes).
    func lookup(_ ref: String? = nil) {
        guard let ref = ref ?? shown?.version.id else { return }
        lookupTask?.cancel()
        guard let c = client() else { cardRef = ref; products = nil; loading = false; lookupError = nil; return }
        cardRef = ref
        loading = true
        lookupError = nil
        products = nil
        catalogPicture = nil
        lookupTask = Task { [weak self] in
            // does the shop's card catalogue have a picture? if not, a booking sends the app's own
            async let picture = Self.catalogHasPicture(c, ref)
            let r: Result<[ShopProduct], Error>
            do { r = .success(try await c.productsOfCard(ref)) } catch { r = .failure(error) }
            let hasPicture = await picture
            guard !Task.isCancelled, let self, self.cardRef == ref else { return }
            loading = false
            switch r {
            case .success(let list):
                products = list
                catalogPicture = hasPicture
                if let s = shown { form = withSuggestedPrice(form, s, list) }
                applySellDefaults()
            case .failure(let e):
                lookupError = Self.text(e)
            }
        }
    }

    private nonisolated static func catalogHasPicture(_ c: ShopClient, _ ref: String) async -> Bool? {
        do { return try await c.masterCards(cardRef: ref).contains { $0.imageUrl != nil } } catch { return nil }
    }

    /// The shop's product for the shown card with the form's language, condition and finish (nil: none yet).
    var match: ShopProduct? {
        guard let s = shown else { return nil }
        let finish = ShopTerms.finishOf(s.printing)
        return products?.first { $0.cardLanguage == form.language && $0.condition == form.condition && $0.finish == finish && $0.grading == nil }
    }

    /// The price the form starts with: the shop's price of that product, else the market value for the condition.
    private func withSuggestedPrice(_ f: ShopForm, _ s: Shown, _ list: [ShopProduct]?) -> ShopForm {
        if f.priceByUser { return f }
        let finish = ShopTerms.finishOf(s.printing)
        let existing = list?.first { $0.cardLanguage == f.language && $0.condition == f.condition && $0.finish == finish && $0.grading == nil }
        var g = f
        g.price = existing?.price?.euros ?? suggestedPrice(marketValue(s, f.condition), minPrice)
        return g
    }

    /// The market value of the shown printing in this condition (Cardmarket, else TCGplayer in euros).
    func marketValue(_ s: Shown, _ condition: String) -> Double? {
        s.pickedPrinting.flatMap { p in (p.eur ?? p.usdEur).map { TradingMath.round2($0 * ShopTerms.share(condition)) } }
    }

    /// The card type (Normal / Holo / Reverse Holo …), also one the price data doesn't have.
    func setFinish(_ kind: Printing.Kind) {
        scanner.hold()
        scanner.choosePrinting(kind)
    }

    func setLanguage(_ code: String) {
        guard let s = shown else { return }
        scanner.hold()
        var f = form
        f.language = code
        f.languageByUser = true
        form = withSuggestedPrice(f, s, products)
    }

    func setCondition(_ code: String) {
        guard let s = shown else { return }
        scanner.hold()
        var f = form
        f.condition = code
        form = withSuggestedPrice(f, s, products)
    }

    func setPrice(_ v: Double?) {
        scanner.hold()
        form.price = v.map(TradingMath.round2)
        form.priceByUser = true
    }

    func setBox(_ n: Int?) {
        scanner.hold()
        form.box = n.flatMap { (1...999).contains($0) ? $0 : nil }
    }

    func setComment(_ t: String) {
        scanner.hold()
        form.comment = String(t.prefix(5000))
    }

    // ------------------------------------------------------------------ Stock in

    /**
     * The person's go: book the copies (POST /stock/intake). Creates the product if the shop has
     * none for this language, condition and finish, else adds the copies to it. Never automatic.
     */
    func book(nameDe: String? = nil, nameEn: String? = nil) {
        guard let s = shown, !busy else { return }
        let f = form
        guard let box = f.box else { error = "Pick a storage box (1–999)"; retryable = false; return }
        let m = match
        let finish = ShopTerms.finishOf(s.printing)
        // the price goes along for a new product, or when the person typed one (it then replaces the shop's)
        let price = f.price.flatMap { m == nil || f.priceByUser ? ShopMoney.ofEuros($0) : nil }
        // no picture in the shop (its card catalogue has none, the product none of its own): the app's picture of
        // the card goes along, so the product can go live; a product left a draft for that alone is made live now
        let ownPicture = m.map { $0.images.contains { $0.status != "failed" } } ?? false
        // a photo taken with the camera goes up after the booking instead
        let ph = photo?.cardRef == s.version.id ? photo : nil
        let picture = (ph == nil && catalogPicture != true && !ownPicture) ? s.pictureUrl : nil
        let activate = picture != nil && m != nil && m?.status == "draft" && (m?.price != nil || price != nil)
        // an Asian print with no picture of its own (Korean ones never have one): another print's picture goes up,
        // with a band across it saying in which language this one is printed
        let printed = s.printedOnly ?? s.version.lang
        var substitute: (url: String, label: String)?
        if ph == nil, CardResolver.asian.contains(printed), !s.hasPictureInItsLanguage, catalogPicture != true, !ownPicture,
           let url = s.imageCard?.highUrl {
            substitute = (url, "Printed in " + Self.languageName(printed))
        }
        // an Asian print: the shop doesn't know it; its printed name, and the names of the western card of the same picture
        let asian = CardResolver.asian.contains(s.version.lang)
        let west = asian ? s.english : nil
        let body = ShopClient.intakeBody(
            cardRef: s.version.id, language: f.language, condition: f.condition, finish: finish, delta: f.quantity, box: ShopClient.box(box),
            price: price, descriptionDe: m == nil ? f.comment : nil,
            nameDe: nameDe ?? west.map { scanner.catalog.cardExact($0.id, "de")?.name ?? $0.name },
            nameEn: nameEn ?? west?.name,
            imageUrl: picture, status: activate ? "active" : nil,
            nameLocal: s.printedOnly != nil ? s.text?.title : (asian ? s.version.name : nil))
        let w = PendingWrite.json("POST", "/stock/intake", body)
        let label = "\(s.version.name) (\(f.language) · \(f.condition) · \(ShopTerms.finishLabel(finish)))"
        pendingShop = (w, label, f.quantity, ShopClient.box(box))
        store.lastBox = box
        store.lastCondition = f.condition
        send(w, label: label, delta: f.quantity, box: ShopClient.box(box), substitute: substitute)
    }

    static func languageName(_ code: String) -> String {
        switch code {
        case "ja": return "Japanese"
        case "ko": return "Korean"
        case "zh-tw", "zh-cn": return "Chinese"
        case "th": return "Thai"
        default: return code
        }
    }

    /// Sends the failed booking again: the same bytes, the same idempotency key (never booked twice).
    func retry() {
        guard let p = pendingShop else { return }
        send(p.write, label: p.label, delta: p.delta, box: p.box)
    }

    /// substitute: another print's picture and the label to put across it, when the product has none
    private func send(_ w: PendingWrite, label: String, delta: Int, box: String, substitute: (url: String, label: String)? = nil) {
        guard let c = client() else { error = "Connect the shop first (Settings › Webshop)"; retryable = false; return }
        busy = true
        error = nil
        Task { [weak self] in
            do {
                let r = try await c.intake(w)
                guard let self else { return }
                pendingShop = nil
                var p = r.product
                var warnings = r.warnings
                // the product picture taken with the camera (or another print's, labelled): up to the product; a draft with a price goes live
                let ph = photo?.cardRef == p.cardRef ? photo : nil
                var note: String?
                if let ph {
                    do { (p, warnings) = try await Self.upload(c, p, warnings, ph.jpeg) }
                    catch { note = "The picture didn't go up (\(Self.text(error))) – it goes along with the next booking" }
                }
                var labelled = false
                if ph == nil, let substitute, !p.images.contains(where: { $0.status != "failed" }) {
                    do {
                        guard let src = await ProductPhoto.download(substitute.url),
                              let jpeg = ProductPhoto.labeled(src, label: substitute.label).jpegData(compressionQuality: 0.92) else {
                            throw ShopError(status: 0, code: "picture", serverMessage: "the picture couldn't be loaded")
                        }
                        (p, warnings) = try await Self.upload(c, p, warnings, jpeg)
                        labelled = true
                    } catch {
                        let why: String
                        if let e = error as? ShopError { why = e.code == "picture" ? (e.serverMessage ?? "") : e.friendly } else { why = error.localizedDescription }
                        note = "No picture went up (\(why)) – Create Product Image takes one"
                    }
                }
                let uploaded = ph != nil && note == nil
                let inBox = p.stock.boxes.first { $0.box == box }?.quantity
                var text = (r.created ? "New in the shop: " : "Added: ") + "\(delta) × \(label) to box \(box)"
                text += " · now \(p.stock.quantity) in stock"
                if let inBox { text += " (\(inBox) in box \(box))" }
                if let price = p.price { text += " · " + Money.eur(price.euros) }
                text += " · " + ShopTerms.statusLabel(p.status)
                if uploaded { text += " · with your picture" }
                if labelled, let marked = substitute?.label { text += " · with a picture marked \"\(marked)\"" }
                if r.replayed { text += " (already booked before)" }
                busy = false
                booking = ShopBooking(text: text, warnings: warnings.map(shopWarningText), sku: p.sku, delta: delta, box: box)
                if uploaded { photo = nil }
                photoNote = note
                products = (products ?? []).filter { $0.sku != p.sku } + [p]
                form.quantity = 1
                form.priceByUser = false
                form.comment = ""
                unknownCard = nil
                // booked: on to the next card
                if scanner.paused && shown?.fromPhoto != true { scanner.resume() }
            } catch let e as ShopError {
                guard let self else { return }
                busy = false
                if e.code == "unknown_card" {
                    pendingShop = nil
                    unknownCard = unknownNames()
                } else {
                    if (400...499).contains(e.status) { pendingShop = nil }      // the shop said no: a corrected booking gets a new key
                    error = e.friendly
                    retryable = e.retryable
                }
            } catch {
                guard let self else { return }
                busy = false
                self.error = error.localizedDescription
                retryable = true
            }
        }
    }

    /// up to the product; a draft with a price goes live; the product as it is then
    private nonisolated static func upload(_ c: ShopClient, _ product: ShopProduct, _ warnings: [String], _ jpeg: Data) async throws -> (ShopProduct, [String]) {
        var p = product, w = warnings
        try await c.uploadImage(PendingWrite.image(sku: p.sku, jpeg: jpeg))
        if p.status == "draft" && p.price != nil {
            (p, w) = try await c.patchProduct(PendingWrite.json("PATCH", "/products/\(p.sku)", ["status": "active"]))
        } else {
            w.removeAll { $0 == "not_activated_no_image" }
        }
        if let fresh = try? await c.product(p.sku) { p = fresh }      // with its new picture
        return (p, w)
    }

    /// The names a card the shop doesn't know is created with (from the card database, editable).
    private func unknownNames() -> UnknownCard {
        guard let s = shown else { return UnknownCard(de: "", en: "") }
        let cat = scanner.catalog
        let de = s.version.lang == "de" ? s.version.name
            : cat.cardExact(s.version.id, "de")?.name ?? s.english.flatMap { cat.cardExact($0.id, "de")?.name } ?? ""
        let en = s.english?.name ?? (s.version.lang == "en" ? s.version.name : cat.cardExact(s.version.id, "en")?.name ?? "")
        return UnknownCard(de: de, en: en)
    }

    /// Undo the last booking: take the copies out of the box again (a stock correction).
    func undoBooking() {
        guard let b = booking, !b.undone, let c = client() else { return }
        let w = PendingWrite.json("POST", "/products/\(b.sku)/stock", ["delta": -b.delta, "storage_location": b.box, "reason": "undone in the scanner app"])
        busy = true
        error = nil
        Task { [weak self] in
            do {
                let stock = try await c.stock(w)
                guard let self else { return }
                busy = false
                booking = ShopBooking(text: "Undone: \(b.delta) × out of box \(b.box) · now \(stock.quantity) in stock", warnings: b.warnings,
                                      sku: b.sku, delta: b.delta, box: b.box, undone: true)
                products = products?.map { p in var q = p; if q.sku == b.sku { q.stock = stock }; return q }
            } catch {
                guard let self else { return }
                busy = false
                self.error = "Undo failed: " + Self.text(error)
                retryable = false
            }
        }
    }

    // ------------------------------------------------------------------ Sell

    private func loadOrder() {
        struct File: Codable { let lines: [ShopOrderLine] }
        let lines = (try? Data(contentsOf: orderFile)).flatMap { try? JSONDecoder().decode(File.self, from: $0) }?.lines ?? []
        order = lines
        selling = store.selling
    }

    private func saveOrder(_ lines: [ShopOrderLine]) {
        struct File: Codable { let lines: [ShopOrderLine] }
        do {
            try FileManager.default.createDirectory(at: orderFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(File(lines: lines)).write(to: orderFile, options: .atomic)
        } catch {
            message = "The order couldn't be saved on the phone: \(error.localizedDescription)"
        }
    }

    /// Stock in (book copies into the shop) or Sell (collect a customer's order, take it out of stock).
    func setSelling(_ on: Bool) {
        store.selling = on
        selling = on
        applySellDefaults()
    }

    /// The product and box a sale takes from, picked for the shown card: the scanned language and printing first, a box with free copies.
    private func applySellDefaults() {
        let inStock = (products ?? []).filter { $0.stock.quantity > 0 }
        guard !inStock.isEmpty else { sellSku = nil; sellBox = nil; sellQty = 1; return }
        if let keep = inStock.first(where: { $0.sku == sellSku }), keep.stock.boxes.contains(where: { $0.box == sellBox && $0.quantity > 0 }) { return }
        let lang = shown.map { ShopTerms.languageOf($0.version.lang) }
        let finish = shown.map { ShopTerms.finishOf($0.printing) }
        let p = inStock.sorted { a, b in
            let ka = (a.cardLanguage != lang ? 1 : 0, a.finish != finish ? 1 : 0, -a.stock.available)
            let kb = (b.cardLanguage != lang ? 1 : 0, b.finish != finish ? 1 : 0, -b.stock.available)
            return ka < kb
        }[0]
        sellSku = p.sku
        sellBox = Self.bestBox(p)
        sellQty = 1
    }

    /// the box with the most free copies (not held by online checkouts), else the fullest
    static func bestBox(_ p: ShopProduct) -> String? {
        p.stock.boxes.filter { $0.quantity > 0 }.max { ($0.quantity - $0.reserved, $0.quantity) < ($1.quantity - $1.reserved, $1.quantity) }?.box
    }

    func selectSellProduct(_ sku: String) {
        scanner.hold()
        guard let p = products?.first(where: { $0.sku == sku }) else { return }
        sellSku = sku
        sellBox = Self.bestBox(p)
        sellQty = 1
    }

    func setSellBox(_ box: String) {
        scanner.hold()
        sellBox = box
        sellQty = 1
    }

    func setSellQty(_ n: Int) {
        scanner.hold()
        let max = products?.first { $0.sku == sellSku }?.stock.boxes.first { $0.box == sellBox }?.quantity ?? 1
        sellQty = min(Swift.max(1, max), Swift.max(1, n))
    }

    /// The picked copies go into the customer's order (nothing leaves the stock yet).
    func addToOrder() {
        guard let p = products?.first(where: { $0.sku == sellSku }), let box = sellBox else { return }
        let inBox = p.stock.boxes.first { $0.box == box }?.quantity ?? 0
        let name = (p.cardLanguage == "DE" ? p.nameDe : p.nameEn).isEmpty ? (shown?.version.name ?? p.sku) : (p.cardLanguage == "DE" ? p.nameDe : p.nameEn)
        let detail = "\(p.cardLanguage) · \(p.condition) · \(ShopTerms.finishLabel(p.finish))" + (p.grading.map { " · \($0)" } ?? "") + " · \(p.cardNumber)"
        var lines = order
        if let i = lines.firstIndex(where: { $0.sku == p.sku && $0.box == box }) {
            lines[i].quantity = min(lines[i].quantity + sellQty, inBox)
            lines[i].error = nil
        } else {
            lines.append(ShopOrderLine(sku: p.sku, name: name, detail: detail, box: box, quantity: min(sellQty, inBox), price: p.price?.euros))
        }
        saveOrder(lines)
        order = lines
        sellQty = 1
        orderError = nil
        message = "In the order: \(orderCopies) cards"
        // on to the next card the customer wants
        if scanner.paused && shown?.fromPhoto != true { scanner.resume() }
    }

    func setOrderLineQty(_ id: String, _ n: Int) {
        guard let i = order.firstIndex(where: { $0.id == id }) else { return }
        var lines = order
        lines[i].quantity = min(999, max(1, n))
        saveOrder(lines)
        order = lines
    }

    func removeOrderLine(_ id: String) {
        let lines = order.filter { $0.id != id }
        saveOrder(lines)
        order = lines
    }

    func clearOrder() {
        pendingSale = nil
        saveOrder([])
        order = []
        orderError = nil
    }

    /**
     * The person's go: take the whole order out of stock (one POST /batch, reason "sold in person").
     * Lines the shop refuses (e.g. the box no longer holds them) stay in the order with the reason.
     */
    func completeSale() {
        guard !orderBusy, !order.isEmpty else { return }
        let lines = order.map { var l = $0; l.error = nil; return l }
        // the same order after a failed attempt: the same key, so it can't be taken out twice
        let w = pendingSale.flatMap { $0.lines == lines ? $0.write : nil }
            ?? PendingWrite.json("POST", "/batch", ShopClient.stockBatchBody(lines.map { (sku: $0.sku, box: $0.box, delta: -$0.quantity) }, reason: "sold in person (scanner app)"))
        pendingSale = (w, lines)
        sendSale(w, lines)
    }

    /// Sends the failed sale again: the same batch, the same key (nothing is taken out twice).
    func retrySale() { if let p = pendingSale { sendSale(p.write, p.lines) } }

    private func sendSale(_ w: PendingWrite, _ lines: [ShopOrderLine]) {
        guard let c = client() else { orderError = "Connect the shop first (Settings › Webshop)"; orderRetryable = false; return }
        orderBusy = true
        orderError = nil
        Task { [weak self] in
            do {
                let results = try await c.batch(w)
                guard let self else { return }
                pendingSale = nil
                var sold: [ShopOrderLine] = [], left: [ShopOrderLine] = []
                for (i, l) in lines.enumerated() {
                    let r = i < results.count ? results[i] : nil
                    if r?.ok == true { sold.append(l) } else { var x = l; x.error = r?.error?.friendly ?? "no answer for this card"; left.append(x) }
                }
                let total = sold.reduce(0) { $0 + ($1.total ?? 0) }
                let copies = sold.reduce(0) { $0 + $1.quantity }
                var text = "Nothing was taken out"
                if !sold.isEmpty {
                    text = "Sold \(copies) cards"
                    if total > 0 { text += " · " + Money.eur(total) }
                    text += " – taken out of stock"
                    if !left.isEmpty { text += " · \(left.count) line(s) failed, see the order" }
                }
                saveOrder(left)
                orderBusy = false
                order = left
                if !sold.isEmpty { sale = ShopSale(text: text, lines: sold) }
                orderError = sold.isEmpty && !left.isEmpty ? "Nothing was taken out – see the lines" : nil
                lookup()                                       // the shown card's stock, fresh
            } catch {
                guard let self else { return }
                if let e = error as? ShopError, (400...499).contains(e.status) { pendingSale = nil }
                orderBusy = false
                orderError = Self.text(error)
                orderRetryable = (error as? ShopError)?.retryable ?? true
            }
        }
    }

    /// Undo the last sale: the copies go back into their boxes.
    func undoSale() {
        guard let s = sale, !s.undone, let c = client() else { return }
        let w = pendingUndo.flatMap { $0.sale == s ? $0.write : nil }
            ?? PendingWrite.json("POST", "/batch", ShopClient.stockBatchBody(s.lines.map { (sku: $0.sku, box: $0.box, delta: $0.quantity) }, reason: "sale undone (scanner app)"))
        pendingUndo = (s, w)
        orderBusy = true
        orderError = nil
        Task { [weak self] in
            var results: [ShopClient.BatchResult]?
            var failure: Error?
            do { results = try await c.batch(w) } catch { failure = error }
            guard let self else { return }
            if results != nil { pendingUndo = nil }
            // lines put back are done; only the others are left to undo
            let left = results.map { r in s.lines.enumerated().filter { !($0.offset < r.count && r[$0.offset].ok) }.map(\.element) } ?? s.lines
            orderBusy = false
            if left.isEmpty {
                sale = ShopSale(text: "Undone: the cards are back in their boxes", lines: s.lines, undone: true)
            } else {
                sale = ShopSale(text: s.text, lines: left)
                let why = failure.map { Self.text($0) } ?? results?.compactMap { $0.error?.friendly }.first ?? ""
                orderError = "Undo failed for \(left.count) line(s): " + why
            }
            lookup()
        }
    }

    // ------------------------------------------------------------------ Create Product Image

    /**
     * "Create Product Image" shows when the shown card has no picture in its language and its
     * product in the shop has none of its own (Stock in, shop connected); or while one is taken / kept.
     */
    var photoWanted: Bool {
        guard let s = shown, configured, !selling, !s.fromPhoto else { return false }
        if photoBusy || photo?.cardRef == s.version.id { return true }
        if s.hasPictureInItsLanguage { return false }
        return match.map { $0.images.allSatisfy { $0.status == "failed" } } ?? true
    }

    /**
     * A photo of the shown card for its shop product, when no picture of the card in its language
     * exists. Only what the camera box shows; the card's outer edge found and straightened (the
     * guide frame cut out if no edge is found). It goes up with the next booking.
     */
    func takeProductPhoto() {
        guard let s = shown, !photoBusy else { return }
        scanner.hold()
        photoBusy = true
        photoNote = nil
        let sc = scanner, engine = scanner.engine
        let aspect = scanner.viewAspect, guide = scanner.settings.guideFraction
        Task { [weak self] in
            let still = await sc.camera.takeStill()
            let frame = still == nil ? await sc.grabFrame() : nil
            let made = await Task.detached(priority: .userInitiated) {
                Self.makePhoto(still: still, frame: frame, aspect: aspect, guide: guide, group: s.group, engine: engine)
            }.value
            guard let self else { return }
            photoBusy = false
            guard shown?.version.id == s.version.id else { return }       // another card meanwhile
            if let made { photo = ShopPhoto(cardRef: s.version.id, jpeg: made.jpeg, thumb: made.thumb); photoNote = nil }
            else { photoNote = "No picture taken: " + (still == nil && frame == nil ? "the camera gave no picture" : "the picture couldn't be made") }
        }
    }

    /// The still (or the camera's frame) → the card cut out and straightened, as JPEG and small.
    nonisolated static func makePhoto(still: Data?, frame: RGBImage?, aspect: Double, guide: Double, group: String,
                                      engine: ScannerEngine) -> (jpeg: Data, thumb: UIImage, outlined: Bool)? {
        guard var full = still.flatMap({ RGBImage(imageData: $0) }) ?? frame else { return nil }
        if still != nil { full = ProductPhoto.scaled(full, maxSide: 3200).0 }
        // what the camera box shows
        let vis = full.visiblePart(aspect: aspect) ?? full
        let prior = ScannerEngine.guidePrior(width: vis.width, height: vis.height, size: guide)
        // the card's outer edge: found on a smaller copy, used at full size
        let (small, k) = ProductPhoto.scaled(vis, maxSide: 1600)
        let r = engine.recognize(small, prior: prior, manual: true)
        let hit = r.hits.first { $0.group == group } ?? r.best
        let quad = (hit.flatMap { engine.cardQuad(r, $0, margin: 0) } ?? r.quad)?.map { CGPoint(x: $0.x / k, y: $0.y / k) }
        let card = ProductPhoto.cardImage(vis, quad: quad, prior: prior)
        guard let encoded = ProductPhoto.encode(card) else { return nil }
        return (encoded.0, encoded.1, quad != nil)
    }
}
