import Foundation
import SwiftUI

/// A price revealed in the free version: which card, and when (10 in any 60 minutes).
struct FreeReveal: Codable, Equatable {
    let cardId: String
    let at: Date
}

/// A copy (or several) in the free version's collection, kept on this phone.
struct LocalItem: Codable, Equatable, Identifiable {
    var id = UUID().uuidString.lowercased()
    let cardId: String
    /// the shop's card_language and finish, as + Collection sends them to an account
    var language: String
    var finish: String
    var condition: String
    var quantity: Int
    var purchaseCents: Int64?
    var note: String?
    /// "PSA", "10" when graded
    var gradingCompany: String?
    var gradingGrade: String?
    /// "Glurak-ex · 151 199/165"
    let label: String
    var added = Date()

    /// "glurak-ex · 151 199/165 de holo nm" for the filter
    var searchText: String { "\(label) \(cardId) \(language) \(ShopTerms.finishLabel(finish)) \(condition)".lowercased() }
}

/**
 * The free version: no account or no running subscription (never subscribed, ended, signed out,
 * or the saved answer ran out offline). The Scanner only; 10 prices revealed in any 60 minutes (a
 * card revealed stays revealed for that hour); a collection of 50 different cards on this phone,
 * moved into the account's collection as soon as a plan with a collection is running.
 */
extension AccountModel {
    static let freeRevealsPerHour = 10
    static let freeMaxCards = 50

    /// no running subscription: the free version's limits apply
    var isFree: Bool { gate != .open }

    private var recentReveals: [FreeReveal] {
        let from = now().addingTimeInterval(-3600)
        return reveals.filter { $0.at > from }
    }

    /// this card's price may be shown (always with a subscription)
    func revealed(_ cardId: String) -> Bool { !isFree || recentReveals.contains { $0.cardId == cardId } }

    /// prices that may still be revealed in this hour
    var revealsLeft: Int { max(0, Self.freeRevealsPerHour - recentReveals.count) }

    /// all used up: when the next one may be revealed
    var nextRevealAt: Date? { revealsLeft > 0 ? nil : recentReveals.map(\.at).min().map { $0.addingTimeInterval(3600) } }

    /// Shows this card's price; false: the hour's 10 are used up.
    @discardableResult
    func reveal(_ cardId: String) -> Bool {
        if revealed(cardId) { return true }
        guard revealsLeft > 0 else { return false }
        reveals = recentReveals + [FreeReveal(cardId: cardId, at: now())]
        store.saveFreeReveals(reveals)
        return true
    }

    // ---------------------------------------------------------------- the collection on this phone
    /// different cards in it (the limit counts these, not copies)
    var localCardCount: Int { Set(localItems.map(\.cardId)).count }

    /// "In collection: 2 (2 × DE Holo NM)" for a card
    func localLines(_ cardId: String) -> [CollectionLine] {
        localItems.filter { $0.cardId == cardId }
            .map { CollectionLine(language: $0.language, finish: $0.finish, condition: $0.condition, quantity: $0.quantity, graded: nil) }
    }

    /// + Collection in the free version: kept on this phone. False: 50 different cards are in it already
    /// (more copies of one of them still go in).
    @discardableResult
    func addLocally(cardId: String, language: String, finish: String, condition: String, quantity: Int = 1,
                    purchaseCents: Int64? = nil, note: String? = nil, label: String) -> Bool {
        let known = localItems.contains { $0.cardId == cardId }
        if !known && localCardCount >= Self.freeMaxCards { return false }
        let q = max(1, min(999, quantity))
        let note = note.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : String($0.prefix(500)) }
        var items = localItems
        // another copy of the same: one line more in number (as the account's collection merges them)
        if purchaseCents == nil, note == nil,
           let i = items.firstIndex(where: { $0.cardId == cardId && $0.language == language && $0.finish == finish && $0.condition == condition
               && $0.purchaseCents == nil && $0.note == nil }) {
            items[i].quantity = min(999, items[i].quantity + q)
            items[i].added = now()
            let item = items.remove(at: i)
            items.insert(item, at: 0)
        } else {
            items.insert(LocalItem(cardId: cardId, language: language, finish: finish, condition: condition, quantity: q,
                                   purchaseCents: purchaseCents, note: note, label: label, added: now()), at: 0)
        }
        localItems = items
        store.saveLocalCollection(items)
        return true
    }

    /// The edit sheet's details for a card on the phone.
    func updateLocally(_ id: String, _ d: CopyDetails) {
        guard let i = localItems.firstIndex(where: { $0.id == id }) else { return }
        var item = localItems[i]
        item.quantity = max(1, min(999, d.quantity))
        item.condition = d.condition
        item.finish = d.finish
        item.language = d.language
        item.gradingCompany = d.grading?["company"] as? String
        item.gradingGrade = d.grading?["grade"] as? String
        item.purchaseCents = d.purchaseCents
        item.note = d.trimmedNote
        localItems[i] = item
        store.saveLocalCollection(localItems)
    }

    func removeLocally(_ id: String) {
        localItems.removeAll { $0.id == id }
        store.saveLocalCollection(localItems)
    }

    /// A plan with a collection is running: the cards kept on this phone go into the account's
    /// collection (queued, so they are sent later when offline) and leave the phone's list.
    func moveLocalCollection() {
        let items = localItems
        guard !items.isEmpty else { return }
        let cards = Set(items.map(\.cardId)).count
        // oldest first, so the account's collection keeps their order
        for i in items.reversed() {
            let grading: JSON? = i.gradingCompany.map { ["company": $0, "grade": i.gradingGrade ?? ""] }
            addToCollection(cardId: i.cardId, language: i.language, finish: i.finish, condition: i.condition, quantity: i.quantity,
                            purchaseCents: i.purchaseCents, note: i.note, grading: grading, label: i.label)
        }
        localItems = []
        store.saveLocalCollection([])
        message = cards == 1 ? "The card from this phone is now in your account's collection."
            : "The \(cards) cards from this phone are now in your account's collection."
    }
}

/// A feature the free version shows but doesn't include.
enum LockedFeature: String, Identifiable {
    case search, trading, missing, photo, alert, collectionFull, values, share, psa, youtube, recording, webshop

    var id: String { rawValue }

    var title: String {
        switch self {
        case .search: return "Search needs a subscription"
        case .trading: return "Carts and deals need a subscription"
        case .missing: return "Missing cards need a subscription"
        case .photo: return "Scanning photos needs a subscription"
        case .alert: return "Price alerts need a subscription"
        case .collectionFull: return "The free collection is full"
        case .values: return "Collection values need a subscription"
        case .share: return "Sharing needs a subscription"
        case .psa: return "PSA prices are an add-on"
        case .youtube: return "YouTube Mode comes with the Creator plan"
        case .recording: return "Recording comes with the Creator plan"
        case .webshop: return "Webshop Mode needs a connected webshop"
        }
    }

    var text: String {
        switch self {
        case .search: return "Find any card by name, number or set, in any language, with its prices."
        case .trading: return "Carts for buying, selling and trading at shows, with conditions, asking prices and your deal history."
        case .missing: return "Keep a list of the cards you're looking for – the shop tells you when one comes in."
        case .photo: return "Recognise cards on photos from your library."
        case .alert: return "The phone vibrates when a scanned card is worth at least your alert price."
        case .collectionFull: return "The free version keeps \(AccountModel.freeMaxCards) different cards on this phone. With a subscription your collection has no limit, is saved in your account, and these cards move into it."
        case .values: return "See what each card and your whole collection is worth."
        case .share: return "Send friends a link to your collection – they see your cards and what they're worth."
        case .psa: return "What PSA 10, 9 and 8 copies of a card sold for on eBay – an add-on for Collector and Creator, monthly or yearly."
        case .youtube: return "Full screen for unboxing and price-check videos: an animated price reveal for every card, a HIT at your alert price, the pack's running value."
        case .recording: return "● REC records YouTube Mode with a log of every card, saved in Photos or uploaded for the video pipeline."
        case .webshop: return "Book scanned cards into your webshop's stock with price, condition and box, and sell a customer's order at your table. It appears once a webshop is connected to your account."
        }
    }

    /// the alert's way forward: the plans, or (an add-on) the price data page
    var isAddon: Bool { self == .psa }
    /// no plan brings it: the shop connects a webshop to the account (Webshop as a Service)
    var byTheShop: Bool { self == .webshop }
}

extension Notification.Name {
    /// a locked feature's "See the plans": Settings › Account with sign-in and the plans
    static let showPlans = Notification.Name("showPlans")
    /// a locked add-on's "See the add-on": Settings › Price data
    static let showAddons = Notification.Name("showAddons")
}

extension View {
    /// The alert of a locked feature: what it does, and the way to the plans.
    func lockedAlert(_ feature: Binding<LockedFeature?>) -> some View {
        alert(feature.wrappedValue?.title ?? "", isPresented: Binding(get: { feature.wrappedValue != nil }, set: { if !$0 { feature.wrappedValue = nil } }),
              presenting: feature.wrappedValue) { f in
            if f.byTheShop {
                Button("OK", role: .cancel) {}
            } else {
                Button(f.isAddon ? "See the add-on" : "See the plans") {
                    NotificationCenter.default.post(name: f.isAddon ? .showAddons : .showPlans, object: nil)
                }
                Button("Not now", role: .cancel) {}
            }
        } message: { f in Text(f.text) }
    }
}

/// A page the free version shows locked: what it is, and the way to the plans.
struct LockedPage: View {
    let feature: LockedFeature
    var icon = "lock.fill"

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 40)).foregroundStyle(Theme.gold)
            Text(feature.title).font(.title3.bold()).foregroundStyle(.white).multilineTextAlignment(.center)
            Text(feature.text).font(.subheadline).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            if !feature.byTheShop {
                Button("See the plans") { NotificationCenter.default.post(name: .showPlans, object: nil) }
                    .buttonStyle(.borderedProminent).foregroundStyle(Theme.ink).padding(.top, 6)
            }
        }
        .padding(28)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.ink)
    }
}

/// In the free version, instead of a card's prices: reveal them (10 in any 60 minutes).
struct RevealPrice: View {
    @ObservedObject var account: AccountModel
    let cardId: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if account.revealsLeft > 0 {
                Button { account.reveal(cardId) } label: {
                    Label("Reveal the price", systemImage: "eye")
                        .font(.headline).foregroundStyle(Theme.ink)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(Theme.gold, in: Capsule())
                }
                .buttonStyle(.plain)
                Text("\(account.revealsLeft) of \(AccountModel.freeRevealsPerHour) left this hour in the free version")
                    .font(.caption).foregroundStyle(Theme.muted)
            } else {
                Text("You've seen \(AccountModel.freeRevealsPerHour) prices this hour.").font(.subheadline.bold()).foregroundStyle(.white)
                if let next = account.nextRevealAt {
                    Text("The next one at \(next.formatted(date: .omitted, time: .shortened)) – or every price at once with a subscription.")
                        .font(.caption).foregroundStyle(Theme.muted)
                }
                Button("See the plans") { NotificationCenter.default.post(name: .showPlans, object: nil) }
                    .font(.subheadline.bold())
            }
        }
        .padding(.top, 4)
    }
}

/// Settings › Account in the free version, above the sign-in or the plans: what Free includes and what a plan adds.
struct FreeNote: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Free version").font(.headline).foregroundStyle(Theme.gold)
            Label("Scanner: scan as many cards as you like, \(AccountModel.freeRevealsPerHour) prices per hour", systemImage: "checkmark")
            Label("A collection of \(AccountModel.freeMaxCards) cards on this phone", systemImage: "checkmark")
            Label("Search, carts and deals, missing cards, photo scans, price alerts and collection values come with a plan; PSA prices as an add-on", systemImage: "lock")
            Text("Sign in and choose a plan for everything; your cards move into your account's collection.")
                .font(.caption).foregroundStyle(Theme.muted).padding(.top, 2)
        }
        .font(.footnote)
        .foregroundStyle(.white)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 14))
    }
}
