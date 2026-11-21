import Foundation
import SwiftUI

/// A card on the missing cards list (`GET /wanted`); pending: added here, not sent yet.
struct WantedItem: Equatable, Identifiable {
    let id: Int64?
    let clientRef: String?
    let cardId: String
    let language: String?
    let finish: String?
    /// searching, offer, available
    let state: String
    var pending = false

    var key: String { id.map(String.init) ?? clientRef ?? cardId }

    func matches(_ cardId: String, _ language: String?, _ finish: String?) -> Bool {
        self.cardId == cardId && (self.language == nil || language == nil || self.language == language)
            && (self.finish == nil || finish == nil || self.finish == finish)
    }
}

/// One of the customer's binders.
struct Binder: Identifiable, Equatable { let id: Int64; let name: String }

/// A line of the collection for one card: "2 × DE Holo NM".
struct CollectionLine: Equatable {
    let language: String, finish: String, condition: String
    let quantity: Int
    let graded: String?
}

/// A copy (or several) in the collection, as the collection page lists it.
struct CollectionItem: Equatable, Identifiable {
    let id: Int64
    let cardId: String
    let language: String, finish: String, condition: String
    let graded: String?
    let quantity: Int
    let name: String, setName: String, number: String
    /// the server's value of one copy (nil: no price)
    let valueCents: Int64?
    let updatedAt: String

    init?(_ o: JSON) {
        guard (o["deleted"] as? Bool) != true, let id = (o["id"] as? NSNumber)?.int64Value, let card = AccountStatus.str(o, "card_id") else { return nil }
        self.id = id
        cardId = card
        language = AccountStatus.str(o, "language") ?? ""
        finish = AccountStatus.str(o, "finish") ?? ""
        condition = AccountStatus.str(o, "condition") ?? ""
        graded = (o["grading"] as? JSON).map { g in "\(AccountStatus.str(g, "company") ?? "") \(AccountStatus.str(g, "grade") ?? "")".trimmingCharacters(in: .whitespaces) }
        quantity = (o["quantity"] as? NSNumber)?.intValue ?? 1
        name = AccountStatus.str(o, "name") ?? card
        setName = AccountStatus.str(o, "set_name") ?? ""
        number = AccountStatus.str(o, "number") ?? ""
        valueCents = (o["value_cents"] as? NSNumber)?.int64Value
        updatedAt = AccountStatus.str(o, "updated_at") ?? ""
    }

    /// "Glurak-ex 151 199/165 de holo …" for the filter
    var searchText: String { "\(name) \(setName) \(number) \(cardId) \(language) \(ShopTerms.finishLabel(finish)) \(condition)".lowercased() }
}

/// The shop's words: card languages, conditions and finishes (Shop API v1 enums).
enum ShopTerms {
    struct Grade: Identifiable { let code: String; let label: String; var id: String { code } }
    static let conditions: [Grade] = [Grade(code: "M", label: "Mint"), Grade(code: "NM", label: "Near Mint"), Grade(code: "EX", label: "Excellent"),
                                      Grade(code: "GD", label: "Good"), Grade(code: "LP", label: "Light Played"), Grade(code: "PL", label: "Played"),
                                      Grade(code: "PO", label: "Poor")]

    /// The shop's card_language for the language the app read off the card.
    static func languageOf(_ appLang: String?) -> String {
        switch appLang?.lowercased() {
        case "de": return "DE"
        case "en": return "EN"
        case "fr": return "FR"
        case "it": return "IT"
        case "es", "es-mx": return "ES"
        case "pt", "pt-br", "pt-pt": return "PT"
        case "nl": return "NL"
        case "pl": return "PL"
        case "ru": return "RU"
        case "ja": return "JA"
        case "ko": return "KO"
        case "zh-tw", "zh-hant": return "ZH-HANT"
        case "zh-cn", "zh-hans": return "ZH-HANS"
        case "id": return "ID"
        case "th": return "TH"
        default: return "EN"
        }
    }

    /// The data pack's language for the shop's card_language (the other way round).
    static func appLang(_ shopLang: String?) -> String {
        let l = (shopLang ?? "").uppercased()
        switch l {
        case "": return "en"
        case "ZH-HANT": return "zh-tw"
        case "ZH-HANS": return "zh-cn"
        default: return l.lowercased()
        }
    }

    /// The shop's finish for a printing the app knows.
    static func finishOf(_ kind: Printing.Kind?) -> String {
        switch kind {
        case .holo: return "holo"
        case .reverse: return "reverse_holo"
        case .firstEdition: return "first_edition"
        case .normal, nil: return "normal"
        }
    }

    static func finishLabel(_ f: String) -> String {
        ["normal": "Normal", "holo": "Holo", "reverse_holo": "Reverse Holo", "first_edition": "1st Edition", "shadowless": "Shadowless",
         "stamped": "Stamped", "pokeball": "Poké Ball", "masterball": "Master Ball"][f] ?? f
    }
}

/**
 * The account in the app (port of the Android app's AccountCoordinator): Sign in with Apple, an
 * emailed code, pairing by code, the status check (at start, at most hourly), Move to a new phone,
 * Disconnect, Delete account, and the collection and missing cards with their offline queue.
 */
@MainActor
final class AccountModel: ObservableObject {
    @Published private(set) var gate: AccountGate = .connect
    @Published private(set) var status: AccountStatus?
    @Published private(set) var lastCheck: Date?
    /// why this phone was disconnected (the connect screen says it)
    @Published private(set) var notice: String?
    /// signing in or checking runs
    @Published private(set) var busy = false
    @Published var error: String?
    /// just connected: the account and plan, then Continue
    @Published var justConnected = false
    @Published private(set) var transfer: TransferCode?
    @Published private(set) var transferBusy = false
    @Published var transferError: String?
    /// changes waiting to be sent
    @Published private(set) var pending: [PendingOp] = []
    @Published private(set) var wanted: [WantedItem] = []
    /// the collection's lines per card id (cards looked at so far)
    @Published private(set) var collection: [String: [CollectionLine]] = [:]
    @Published private(set) var binders: [Binder] = []
    /// the whole collection, for the collection page (newest first; nil: not loaded yet)
    @Published private(set) var items: [CollectionItem]?
    @Published private(set) var itemsLoading = false
    @Published private(set) var itemsError: String?
    /// goes up when a copy reached the collection (the collection page loads again)
    @Published private(set) var collectionEdits = 0
    /// Use another email: where the code went, and from when another may be asked for
    @Published private(set) var emailSentTo: String?
    @Published private(set) var resendAt = Date.distantPast
    /// a short message for the person (an alert)
    @Published var message: String?

    let store: AccountStore
    /// the app's own server: sign-ins go there (a pairing link names its own); tests: a stand-in
    var home: String
    /// tests: the stand-in's session and staging's access header
    var session: URLSession?
    var stagingAccess: String?

    /// after connecting: the store's part looks at the purchases
    var onConnected: (() -> Void)?
    /// what became of a creator code sent with the first sign-in, and the account's code
    var onAffiliate: ((String?, JSON?) -> Void)?
    /// a new status answer (the store's part, the screens)
    var onStatus: ((AccountStatus?) -> Void)?

    private var checking = false
    private var checkAgain = false
    private var checkAgainManual = false
    private(set) var syncing = false
    private var syncAgain = false
    private var transferTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var hourlyTask: Task<Void, Never>?
    private var wantedEdits = 0

    static let defaultHome = (Bundle.main.object(forInfoDictionaryKey: "AccountURL") as? String).flatMap { $0.isEmpty ? nil : $0 }
        ?? "https://overpricedcardboard.com"

    init(store: AccountStore = AccountStore(), home: String = AccountModel.defaultHome) {
        self.store = store
        self.home = home.hasSuffix("/") ? String(home.dropLast()) : home
    }

    func api(_ base: String? = nil) -> AccountApi? {
        guard let b = base ?? store.base else { return nil }
        let a = AccountApi(base: b, session: session)
        a.stagingAccess = stagingAccess
        return a
    }

    /// a feature unlocked now
    func has(_ feature: String) -> Bool { gate == .open && status?.has(feature) == true }
    var locked: Bool { gate != .open }
    var portalUrl: String { status?.portalUrl ?? home + "/account" }
    var privacyUrl: URL { URL(string: home + "/app/privacy")! }

    /// At the app's start: the saved state, then a check (and one at most every hour while the app is open).
    func start() {
        load()
        publishWanted()
        check(force: true)
        hourlyTask?.cancel()
        hourlyTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_600_000_000_000)
                self?.check()
            }
        }
    }

    /// the saved state: connected?, the last answer, the gate
    func load() {
        let st = store.status()
        status = st
        lastCheck = store.lastCheck
        notice = store.notice
        gate = AccountGate.of(connected: store.connected, status: st, now: Date())
        onStatus?(store.connected ? st : nil)
    }

    /// an answer that carries the status (a purchase report): kept like a check's
    func applyStatus(_ o: JSON) {
        store.saveStatus(o)
        load()
    }

    // ---------------------------------------------------------------- pairing and sign-in
    /// A typed pairing code (or a scanned link): connect this phone to an existing account.
    func pair(_ text: String) {
        if busy { return }
        guard let target = PairCode.parse(text, defaultBase: home) else {
            error = "That isn't a pairing code: 12 characters, e.g. K7Q2-9MZ4-D01A."
            return
        }
        run("connecting needs the internet. Try again when you're online.") { [self] in
            let p = try await api(target.base)!.pair(code: target.code, deviceId: store.deviceId, deviceName: AccountStore.deviceName,
                                                     affiliateCode: store.pendingAffiliate)
            connectedWith(target.base, p)
        }
    }

    /// Sign in with Apple: the identity token and the authorization code Apple gave.
    func signInApple(identityToken: String, authorizationCode: String?) {
        run("signing in needs the internet.") { [self] in
            let p = try await api(home)!.loginApple(identityToken: identityToken, authorizationCode: authorizationCode,
                                                    deviceId: store.deviceId, deviceName: AccountStore.deviceName, affiliateCode: store.pendingAffiliate)
            connectedWith(home, p)
        }
    }

    /// Use another email: the code is emailed.
    func emailCode(_ email: String) {
        let e = email.trimmingCharacters(in: .whitespaces)
        if busy || e.isEmpty { return }
        run("signing in needs the internet.") { [self] in
            let wait = try await api(home)!.loginEmail(e)
            emailSentTo = e
            resendAt = Date().addingTimeInterval(TimeInterval(wait))
        }
    }

    /// the code from the email
    func emailVerify(_ code: String) {
        guard let email = emailSentTo else { return }
        let digits = code.filter(\.isNumber)
        run("signing in needs the internet.") { [self] in
            let p = try await api(home)!.loginEmailVerify(email: email, code: digits, deviceId: store.deviceId,
                                                          deviceName: AccountStore.deviceName, affiliateCode: store.pendingAffiliate)
            connectedWith(home, p)
        }
    }

    /// another address (back from the code)
    func emailBack() { emailSentTo = nil; error = nil }

    func signInFailed(_ why: String) { busy = false; error = why }

    /// One call with the busy flag, its error shown (AccountError friendly, else offline).
    private func run(_ what: String, _ body: @escaping () async throws -> Void) {
        if busy { return }
        busy = true
        error = nil
        Task { [self] in
            do { try await body() } catch let e as AccountError { error = e.friendly } catch { self.error = offline(error, what) }
            busy = false
        }
    }

    /// paired or signed in: keep the token, show who (a new account without a plan goes straight to the plans)
    private func connectedWith(_ base: String, _ p: Paired) {
        store.connected(base: base, token: p.token, status: p.status)
        // the creator code went with it: once is enough, whatever became of it
        if p.affiliateResult != nil { store.pendingAffiliate = nil }
        onAffiliate?(p.affiliateResult, p.affiliate)
        justConnected = AccountStatus.parse(p.status).active
        emailSentTo = nil
        load()
        // a queue of another account is not sent to this one
        let acc = store.status()?.accountId
        store.saveQueue(store.queue().filter { $0.accountId == nil || $0.accountId == acc })
        onConnected?()
        check(force: true)
    }

    // ---------------------------------------------------------------- status
    /**
     * Asks the server what is unlocked: force at start, after signing in, on Check again;
     * otherwise at most once an hour. Offline the saved answer counts until valid_until.
     */
    func check(force: Bool = false, manual: Bool = false) {
        guard let token = store.token else { return }
        if !force, let last = store.lastCheck, Date().timeIntervalSince(last) < 3600 { return }
        // one at a time; asked again meanwhile: once more afterwards
        if checking {
            if force { checkAgain = true; checkAgainManual = checkAgainManual || manual }
            return
        }
        checking = true
        if manual { busy = true; error = nil }
        Task { [self] in
            do {
                guard let api = api() else { return }
                let o = try await api.status(token: token)
                store.saveStatus(o)
                load()
                if manual { busy = false; error = nil }
                if store.status()?.has("wanted") == true { await refreshWanted() }
                sync()
            } catch let e as AccountError {
                // a check nobody asked for leaves the screen's message alone
                if e.lostToken { lost(e) } else if manual { busy = false; error = e.friendly }
            } catch {
                load()      // valid_until may have passed meanwhile
                if manual { busy = false; self.error = offline(error, "the subscription can't be checked now.") }
            }
            checking = false
            if checkAgain {
                let m = checkAgainManual
                checkAgain = false; checkAgainManual = false
                check(force: true, manual: m)
            }
        }
    }

    /// The token doesn't count any more: forget it and the saved answer, say why, back to Connect.
    private func lost(_ e: AccountError) {
        let why = e.error == "unpaired" ? unpairedText(reason: e.reason, since: e.since, deviceName: e.deviceName) : "This phone is no longer connected to an account."
        stopTransfer()
        store.forget(notice: why)
        load()
        busy = false
        error = nil
        wanted = []; collection = [:]; binders = []; items = nil
    }

    /// the app came to the front: after more than an hour away, check
    func onForeground() { check() }

    // ---------------------------------------------------------------- move, disconnect, delete
    /// Move to a new phone: a transfer code as a QR code; asks every 5 s whether the new phone took it.
    func startTransfer() {
        guard let token = store.token, let api = api() else { return }
        transferBusy = true
        transferError = nil
        transferTask?.cancel()
        transferTask = Task { [self] in
            do {
                let t = try await api.transfer(token: token)
                transfer = t
                transferBusy = false
                while !Task.isCancelled, transfer != nil {
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    if Task.isCancelled { break }
                    do { store.saveStatus(try await api.status(token: token)) } catch let e as AccountError {
                        if e.lostToken { lost(e); message = store.notice ?? "Moved"; break }
                    } catch {}
                    if let exp = t.expiresAt, Date() > exp { transfer = nil; transferError = "The code has expired. Show a new one."; break }
                }
            } catch let e as AccountError {
                transferBusy = false
                if e.lostToken { lost(e) } else { transferError = e.friendly }
            } catch {
                transferBusy = false
                transferError = offline(error, "moving needs the internet.")
            }
        }
    }

    func stopTransfer() {
        transferTask?.cancel()
        transferTask = nil
        transfer = nil
        transferBusy = false
    }

    /// Disconnect this phone: its slot is free again; back to Connect.
    func disconnect() {
        guard let token = store.token, let api = api() else { return }
        run("disconnecting needs the internet.") { [self] in
            do { try await api.disconnect(token: token) } catch let e as AccountError where e.lostToken { lost(e); return }
            store.forget(notice: "This phone was disconnected from the account.")
            load()
            wanted = []; collection = [:]; binders = []; items = nil
        }
    }

    /**
     * Delete the account (asked first): every device is signed out, the data goes. An App Store
     * subscription the person cancels themselves (Apple doesn't let the server do it).
     */
    func deleteAccount() {
        guard let token = store.token, let api = api() else { return }
        run("deleting needs the internet.") { [self] in
            let o: JSON
            do { o = try await api.deleteAccount(token: token) } catch let e as AccountError {
                if e.lostToken { lost(e); return }
                if e.error == "internal" { throw AccountError(status: e.status, error: "internal", message: "The subscription couldn't be stopped just now, so nothing was deleted. Try again later.") }
                throw e
            }
            store.forget(notice: "The account was deleted.")
            store.saveQueue([])
            load()
            wanted = []; collection = [:]; binders = []; items = nil
            if let url = AccountStatus.str(o, "manage_url") {
                message = "The account is deleted. Cancel the subscription in the App Store too (Settings › Apple Account › Subscriptions): \(url)"
            } else {
                message = "The account is deleted."
            }
        }
    }

    // ---------------------------------------------------------------- collection
    /// The collection's lines of a card, for "In collection: 2" (cached; force: again).
    func loadCollection(_ cardId: String, force: Bool = false) {
        guard has("collection"), force || collection[cardId] == nil, let token = store.token, let api = api() else { return }
        Task { [self] in
            do {
                let items = try await api.collectionCard(token: token, cardId: cardId)
                collection[cardId] = items.filter { ($0["deleted"] as? Bool) != true }.map { o in
                    let g = (o["grading"] as? JSON).map { g in "\(AccountStatus.str(g, "company") ?? "") \(AccountStatus.str(g, "grade") ?? "")".trimmingCharacters(in: .whitespaces) }
                    return CollectionLine(language: AccountStatus.str(o, "language") ?? "", finish: AccountStatus.str(o, "finish") ?? "",
                                          condition: AccountStatus.str(o, "condition") ?? "", quantity: (o["quantity"] as? NSNumber)?.intValue ?? 1, graded: g)
                }
            } catch let e as AccountError { if e.lostToken { lost(e) } } catch {}
        }
    }

    func loadBinders() {
        guard let token = store.token, let api = api() else { return }
        Task { [self] in
            if let list = try? await api.binders(token: token) {
                binders = list.compactMap { b in (b["id"] as? NSNumber).map { Binder(id: $0.int64Value, name: AccountStatus.str(b, "name") ?? "Binder") } }
            }
        }
    }

    /// The whole collection, page by page (the collection page; again after each add).
    func loadItems() async {
        guard has("collection"), !itemsLoading, let token = store.token, let api = api() else { return }
        itemsLoading = true
        defer { itemsLoading = false }
        do {
            var all: [CollectionItem] = []
            var cursor: String?
            repeat {
                let page = try await api.collectionItems(token: token, cursor: cursor)
                all += page.items.compactMap(CollectionItem.init)
                cursor = page.next
            } while cursor != nil && all.count < 100_000
            items = all.sorted { ($0.updatedAt, $0.id) > ($1.updatedAt, $1.id) }
            itemsError = nil
        } catch let e as AccountError {
            if e.lostToken { lost(e) } else { itemsError = e.friendly }
        } catch {
            itemsError = offline(error, "the collection couldn't be loaded.")
        }
    }

    /// A missing card turned up elsewhere: into the collection, and off the missing cards.
    func foundElsewhere(_ w: WantedItem, condition: String, quantity: Int = 1, purchaseCents: Int64? = nil, binderId: Int64? = nil,
                        note: String? = nil, defaultLanguage: String, defaultFinish: String, label: String) {
        addToCollection(cardId: w.cardId, language: w.language ?? defaultLanguage, finish: w.finish ?? defaultFinish, condition: condition,
                        quantity: quantity, purchaseCents: purchaseCents, binderId: binderId, note: note, label: label)
        toggleWanted(cardId: w.cardId, language: w.language, finish: w.finish, label: label)
    }

    /// + Collection: queued with its own client_ref and sent at once (later when offline).
    func addToCollection(cardId: String, language: String, finish: String, condition: String, quantity: Int = 1,
                         purchaseCents: Int64? = nil, binderId: Int64? = nil, note: String? = nil, label: String) {
        let body: JSON = ["card_id": cardId, "language": language, "finish": finish, "condition": condition,
                          "quantity": max(1, min(999, quantity)), "grading": NSNull(),
                          "purchase_price_cents": purchaseCents.map { $0 as Any } ?? NSNull(),
                          "note": note.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : String($0.prefix(500)) as Any } ?? NSNull(),
                          "binder_id": binderId.map { $0 as Any } ?? NSNull(), "merge": true]
        enqueue(PendingOp(kind: "collection_add", clientRef: UUID().uuidString.lowercased(), accountId: status?.accountId, cardId: cardId,
                          body: PendingOp.encode(body), label: label))
    }

    // ---------------------------------------------------------------- missing cards
    private func savedWanted() -> [WantedItem] {
        (store.wanted() ?? []).map { o in
            WantedItem(id: (o["id"] as? NSNumber)?.int64Value, clientRef: AccountStatus.str(o, "client_ref"), cardId: AccountStatus.str(o, "card_id") ?? "",
                       language: AccountStatus.str(o, "language"), finish: AccountStatus.str(o, "finish"), state: AccountStatus.str(o, "state") ?? "searching")
        }
    }

    private func refreshWanted() async {
        guard let token = store.token, let api = api() else { return }
        do {
            let before = wantedEdits
            let list = try await api.wanted(token: token)
            if before == wantedEdits && !store.queue().contains(where: { $0.kind.hasPrefix("wanted") }) { store.saveWanted(list) }
            publishWanted()
        } catch let e as AccountError { if e.lostToken { lost(e) } } catch {}
    }

    /// the list as the server has it, plus what waits to be sent (adds shown, removes left out)
    private func publishWanted() {
        let q = store.queue()
        let removing = Set(q.filter { $0.kind == "wanted_delete" }.compactMap(\.wantedId))
        let adding = q.filter { $0.kind == "wanted_add" }.map { op in
            WantedItem(id: nil, clientRef: op.clientRef, cardId: op.cardId, language: op.bodyJSON.flatMap { AccountStatus.str($0, "language") },
                       finish: op.bodyJSON.flatMap { AccountStatus.str($0, "finish") }, state: "searching", pending: true)
        }
        wanted = savedWanted().filter { w in w.id.map { !removing.contains($0) } ?? true } + adding
        pending = q
    }

    /// ☆ Missing: on the list, or off it again (a second tap).
    func toggleWanted(cardId: String, language: String?, finish: String?, label: String) {
        let hit = wanted.first { $0.matches(cardId, language, finish) }
        let acc = status?.accountId
        if let hit {
            if hit.pending {
                // added here and not sent yet: it simply goes
                store.saveQueue(store.queue().filter { $0.clientRef != hit.clientRef })
                publishWanted()
            } else if let id = hit.id {
                enqueue(PendingOp(kind: "wanted_delete", clientRef: UUID().uuidString.lowercased(), accountId: acc, cardId: cardId, wantedId: id, label: label))
            }
        } else {
            let body: JSON = ["card_id": cardId, "language": language.map { $0 as Any } ?? NSNull(), "finish": finish.map { $0 as Any } ?? NSNull()]
            enqueue(PendingOp(kind: "wanted_add", clientRef: UUID().uuidString.lowercased(), accountId: acc, cardId: cardId, body: PendingOp.encode(body), label: label))
        }
    }

    // ---------------------------------------------------------------- the queue
    private func enqueue(_ op: PendingOp) {
        store.saveQueue(store.queue() + [op])
        publishWanted()
        sync()
    }

    /// The person deletes a change that waits to be sent.
    func dropPending(_ clientRef: String) {
        store.saveQueue(store.queue().filter { $0.clientRef != clientRef })
        publishWanted()
    }

    /**
     * Sends what waits, in order. No connection, 5xx, too many calls: it stays (and is tried again
     * in a minute while the app is open). A 400: dropped, with why.
     */
    func sync() {
        guard store.token != nil else { return }
        if syncing { syncAgain = true; return }
        syncing = true
        Task { [self] in
            var stuck = false
            while let token = store.token, let op = store.queue().first {
                if let a = op.accountId, let b = status?.accountId, a != b { done(op); continue }
                do {
                    try await send(token, op)
                    done(op)
                } catch let e as AccountError {
                    if e.lostToken { lost(e); stuck = true }
                    else if e.status == 400 {
                        done(op)
                        let fields = (e.json?["fields"] as? JSON).map { f in f.map { "\($0.key): \($0.value)" }.joined(separator: ", ") }
                        message = "Not \(op.kind == "collection_add" ? "added to the collection" : "changed in the missing cards"): \(op.label) (\(fields ?? e.friendly))"
                    } else if e.status == 404 && op.kind == "wanted_delete" { done(op) }
                    else { stuck = true }
                    if stuck { break }
                } catch {
                    // no connection, the server's trouble: try again later
                    stuck = true
                    break
                }
            }
            syncing = false
            let again = syncAgain
            syncAgain = false
            publishWanted()
            if again && !stuck { sync(); return }
            if stuck && store.token != nil && !store.queue().isEmpty {
                retryTask?.cancel()
                retryTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 60_000_000_000)
                    if !Task.isCancelled { self?.sync() }
                }
            }
        }
    }

    private func send(_ token: String, _ op: PendingOp) async throws {
        guard let api = api() else { throw URLError(.notConnectedToInternet) }
        var body = op.bodyJSON ?? [:]
        body["client_ref"] = op.clientRef
        switch op.kind {
        case "collection_add":
            _ = try await api.addToCollection(token: token, body: body)
            loadCollection(op.cardId, force: true)
            collectionEdits += 1
        case "wanted_add":
            wantedEdits += 1
            let o = try await api.addWanted(token: token, body: body)
            var list = store.wanted() ?? []
            let id = (o["id"] as? NSNumber)?.int64Value
            if !list.contains(where: { ($0["id"] as? NSNumber)?.int64Value == id }) { list.append(o) }
            store.saveWanted(list)
        case "wanted_delete":
            wantedEdits += 1
            if let id = op.wantedId {
                try await api.deleteWanted(token: token, id: id)
                store.saveWanted((store.wanted() ?? []).filter { ($0["id"] as? NSNumber)?.int64Value != id })
            }
        default: break
        }
    }

    private func done(_ op: PendingOp) { store.saveQueue(store.queue().filter { $0.clientRef != op.clientRef }) }

    /// "No connection: …" – or, when the server is there but not answering as the API, that.
    func offline(_ e: Error, _ what: String) -> String {
        if let s = e as? ServerUnavailable { return "overpricedcardboard.com isn't answering as expected right now (HTTP \(s.code)) – try again later." }
        return "No connection: \(what)"
    }
}
