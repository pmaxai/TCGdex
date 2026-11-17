import AuthenticationServices
import CoreImage.CIFilterBuiltins
import StoreKit
import SwiftUI

/**
 * Before the app can be used: sign in (Sign in with Apple, an emailed code, or a pairing code of an
 * existing account), choose a plan, "Your subscription has ended", "Connect to the internet once…",
 * and right after connecting, the account and plan.
 */
struct AccountGateView: View {
    @ObservedObject var account: AccountModel
    @ObservedObject var store: StoreModel

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                let st = account.status
                if account.justConnected, let st {
                    Text("Connected").font(.title.bold()).foregroundStyle(Theme.sure)
                    AccountSummary(st: st)
                    Button("Continue") { account.justConnected = false }.buttonStyle(.borderedProminent).foregroundStyle(Theme.ink).padding(.top, 12)
                } else {
                    switch account.gate {
                    case .ended: PlansView(account: account, store: store)
                    case .checkOnline:
                        Text("Connect to the internet once to check your subscription").font(.title3.bold()).foregroundStyle(Theme.warn).multilineTextAlignment(.center)
                        if let st { Text("Last checked \(account.lastCheck.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "–") · \(st.email ?? "")").font(.footnote).foregroundStyle(Theme.muted) }
                        Button { account.check(force: true, manual: true) } label: { if account.busy { ProgressView() } else { Text("Check again") } }
                            .buttonStyle(.bordered).disabled(account.busy).padding(.top, 8)
                    default: SignInView(account: account)
                    }
                }
                if let e = account.error { Text("⚠ \(e)").font(.subheadline).foregroundStyle(Theme.warn).multilineTextAlignment(.center).padding(.top, 8) }
            }
            .padding(20)
            .frame(maxWidth: 460)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.ink)
    }
}

struct AccountSummary: View {
    let st: AccountStatus
    var body: some View {
        VStack(spacing: 2) {
            Text(st.email ?? "").font(.headline).foregroundStyle(.white)
            Text("\(st.planName) · \(st.stateText)").font(.subheadline).foregroundStyle(st.state == "past_due" ? Theme.warn : Theme.muted)
        }
    }
}

/// Sign in to choose a plan, or to use the one you have.
struct SignInView: View {
    @ObservedObject var account: AccountModel
    @State private var email = ""
    @State private var code = ""
    @State private var pairing = false
    @State private var pairCode = ""

    var body: some View {
        VStack(spacing: 14) {
            Image("Logo").resizable().scaledToFit().frame(width: 84, height: 84).clipShape(RoundedRectangle(cornerRadius: 18))
            Text("Overpriced Cardboard").font(.title.bold()).foregroundStyle(.white)
            if let n = account.notice { Text(n).font(.footnote).foregroundStyle(Theme.warn).multilineTextAlignment(.center) }
            if pairing {
                Text("Connect with an existing account").font(.headline).foregroundStyle(.white)
                Text("In your account on overpricedcardboard.com: Devices › Connect a device. Type the code shown under the QR code.")
                    .font(.footnote).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                TextField("K7Q2-9MZ4-D01A", text: $pairCode)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    .font(.title3.monospaced()).multilineTextAlignment(.center)
                    .padding(10).background(Theme.panel, in: RoundedRectangle(cornerRadius: 10))
                Button("Connect") { account.pair(pairCode) }.buttonStyle(.borderedProminent).foregroundStyle(Theme.ink)
                    .disabled(account.busy || pairCode.trimmingCharacters(in: .whitespaces).isEmpty)
                Text("12 characters, valid for 10 minutes.").font(.caption).foregroundStyle(Theme.muted)
                Button("‹ Back to sign-in") { pairing = false }.padding(.top, 6)
            } else if let sentTo = account.emailSentTo {
                Text("We sent a 6-digit code to \(sentTo).").font(.subheadline).foregroundStyle(.white).multilineTextAlignment(.center)
                TextField("Code", text: $code)
                    .keyboardType(.numberPad).textContentType(.oneTimeCode)
                    .font(.title2.monospaced()).multilineTextAlignment(.center)
                    .padding(10).background(Theme.panel, in: RoundedRectangle(cornerRadius: 10))
                Button("Sign in") { account.emailVerify(code) }.buttonStyle(.borderedProminent).foregroundStyle(Theme.ink)
                    .disabled(account.busy || code.filter(\.isNumber).count != 6)
                HStack(spacing: 16) {
                    Button("Another address") { code = ""; account.emailBack() }
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        Button("Send again") { account.emailCode(sentTo) }.disabled(account.busy || ctx.date < account.resendAt)
                    }
                }
                .font(.footnote)
            } else {
                Text("Sign in to choose a plan, or to use the one you have.").font(.subheadline).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                SignInWithAppleButton(.continue) { req in
                    req.requestedScopes = [.email]
                } onCompletion: { result in
                    switch result {
                    case .success(let auth):
                        guard let c = auth.credential as? ASAuthorizationAppleIDCredential, let t = c.identityToken.flatMap({ String(data: $0, encoding: .utf8) }) else {
                            account.signInFailed("Apple didn't give a sign-in. Try again."); return
                        }
                        account.signInApple(identityToken: t, authorizationCode: c.authorizationCode.flatMap { String(data: $0, encoding: .utf8) })
                    case .failure(let e):
                        if (e as? ASAuthorizationError)?.code != .canceled { account.signInFailed(e.localizedDescription) }
                    }
                }
                .signInWithAppleButtonStyle(.white)
                .frame(height: 48)
                .disabled(account.busy)
                Text("or").font(.footnote).foregroundStyle(Theme.muted)
                TextField("Email", text: $email)
                    .keyboardType(.emailAddress).textContentType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .padding(10).background(Theme.panel, in: RoundedRectangle(cornerRadius: 10))
                Button("Send me a code") { account.emailCode(email) }.buttonStyle(.bordered)
                    .disabled(account.busy || !email.contains("@"))
                Button("Have a code from your account? Connect with it") { pairing = true }.font(.footnote).padding(.top, 10)
            }
            if account.busy { ProgressView() }
            Link("Privacy policy", destination: account.privacyUrl).font(.footnote).foregroundStyle(Theme.muted).padding(.top, 8)
        }
    }
}

/// Signed in, no running subscription: the plans with the App Store's prices.
struct PlansView: View {
    @ObservedObject var account: AccountModel
    @ObservedObject var store: StoreModel
    @State private var redeem = false

    var body: some View {
        let st = account.status
        VStack(spacing: 10) {
            Text(st == nil || st!.none ? "Choose your plan" : "Your subscription has ended").font(.title2.bold())
                .foregroundStyle(st?.none == false ? Theme.warn : .white).multilineTextAlignment(.center)
            if let e = st?.email { Text("Signed in as \(e)").font(.footnote).foregroundStyle(Theme.muted) }
            if let st, !st.emailVerified { Text("Confirm your email: open the link we sent you.").font(.footnote).foregroundStyle(Theme.gold) }
            if store.loading { ProgressView().padding(.top, 8) }
            CreatorCodeView(store: store, redeem: $redeem)
            ForEach(store.onSale) { plan in
                PlanCard(plan: plan, store: store, enabled: !store.busy && !account.busy) { period in store.buy(plan, period) }
            }
            if !store.loading && store.onSale.isEmpty && store.error == nil {
                Text("The plans aren't on sale in the App Store yet.").font(.subheadline).foregroundStyle(Theme.muted).padding(.top, 12)
            }
            StoreLines(store: store)
            if store.busy { ProgressView() }
            Text("A subscription renews automatically until you cancel it in the App Store (Settings › Apple Account › Subscriptions), at least 24 hours before the end of the period.")
                .font(.caption).foregroundStyle(Theme.muted).multilineTextAlignment(.center).padding(.top, 8)
            HStack(spacing: 16) {
                Link("Terms of Use", destination: StoreModel.termsUrl)
                Link("Privacy policy", destination: account.privacyUrl)
            }
            .font(.caption)
            HStack(spacing: 16) {
                Button("Restore purchases") { store.restore() }.disabled(store.busy)
                Button("Check again") { account.check(force: true, manual: true) }.disabled(account.busy)
            }
            .font(.footnote)
            .padding(.top, 4)
            Button("Sign out") { account.disconnect() }.font(.footnote).foregroundStyle(Theme.muted).disabled(account.busy)
        }
        .task(id: st?.accountId) { store.refresh() }
        .offerCodeRedemption(isPresented: $redeem) { _ in Task { await store.sendWaiting(); account.check(force: true) } }
    }
}

struct StoreLines: View {
    @ObservedObject var store: StoreModel
    var body: some View {
        if let e = store.error { Text("⚠ \(e)").font(.footnote).foregroundStyle(Theme.warn).multilineTextAlignment(.center) }
        if let n = store.note { Text(n).font(.footnote).foregroundStyle(Theme.sure).multilineTextAlignment(.center) }
        if store.waiting > 0 {
            Text("A purchase waits to be confirmed: it is sent again as soon as there is a connection.")
                .font(.caption).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
        }
    }
}

/// The creator code: the account's (with its discount and Apple's code to redeem), or a field to type one in.
struct CreatorCodeView: View {
    @ObservedObject var store: StoreModel
    @Binding var redeem: Bool
    @State private var open = false
    @State private var code = ""

    var body: some View {
        if let a = store.affiliate {
            Text("Creator code \(a.code)" + (a.discountText.map { " · \($0)" } ?? "")).font(.subheadline.bold()).foregroundStyle(Theme.sure)
            if a.appleOfferCode != nil {
                Button("Redeem the discount in the App Store") { redeem = true }.font(.footnote)
                Text("Enter \(a.appleOfferCode!) in the App Store's sheet.").font(.caption).foregroundStyle(Theme.muted)
            }
        } else if !open {
            Button("Have a creator code?") { open = true }.font(.footnote)
        } else {
            HStack {
                TextField("Creator code", text: $code)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    .padding(10).background(Theme.panel, in: RoundedRectangle(cornerRadius: 10))
                Button("Apply") { store.applyCode(code) }.buttonStyle(.borderedProminent).foregroundStyle(Theme.ink)
                    .disabled(store.busy || code.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }
}

/// a plan: what it unlocks, and a button per period with the App Store's price
struct PlanCard: View {
    let plan: PlanOffer
    @ObservedObject var store: StoreModel
    let enabled: Bool
    let onBuy: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(plan.name).font(.title3.bold()).foregroundStyle(Theme.gold)
            ForEach(plan.features, id: \.self) { f in Label(featureLabel(f), systemImage: "checkmark").font(.footnote).foregroundStyle(Theme.muted) }
            // one under the other: "€75.60 / year" doesn't fit two to a row on a phone
            if let p = store.price(plan, "monthly") {
                Button { onBuy("monthly") } label: { Text(Self.text(p, "month")).frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).foregroundStyle(Theme.ink).disabled(!enabled).padding(.top, 6)
            }
            if let p = store.price(plan, "yearly") {
                Button { onBuy("yearly") } label: { Text(Self.text(p, "year")).frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).disabled(!enabled)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 14))
    }

    /// "€6.99 / month", "€3.99 / month for 3 months, then €6.99 / month"
    static func text(_ p: StoreProduct, _ unit: String) -> String {
        if let intro = p.intro { return "\(intro), then \(p.displayPrice) / \(unit)" }
        return "\(p.displayPrice) / \(unit)"
    }
}

/// ⚙ Account: who, the plan, devices, manage, plan changes, extra devices, move, sign out, delete, waiting changes.
struct AccountView: View {
    @ObservedObject var account: AccountModel
    @ObservedObject var store: StoreModel
    @State private var manage = false
    @State private var confirmDelete = false
    @State private var redeem = false

    var body: some View {
        Form {
            if let st = account.status {
                Section {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(st.email ?? "").font(.headline)
                        Text("\(st.planName) · \(st.stateText)").font(.subheadline).foregroundStyle(st.state == "past_due" ? Theme.warn : Theme.muted)
                        Text("Devices: \(st.devicesText)").font(.footnote).foregroundStyle(Theme.muted)
                        Text("Last checked \(account.lastCheck.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "–")").font(.caption).foregroundStyle(Theme.muted)
                    }
                    if !st.emailVerified { Text("Confirm your email: open the link we sent to \(st.email ?? "you").").font(.footnote).foregroundStyle(Theme.gold) }
                    Button { account.check(force: true, manual: true) } label: { if account.busy { ProgressView() } else { Text("Check again") } }.disabled(account.busy)
                }
                Section("Subscription") {
                    switch st.source {
                    case "app_store": Button("Manage subscription") { manage = true }
                    case "web": Text("This subscription is managed on the website.").foregroundStyle(Theme.muted)
                    case "google_play": Text("This subscription is managed in Google Play.").foregroundStyle(Theme.muted)
                    default: EmptyView()
                    }
                    if let plan = store.runningPlan {
                        ForEach(store.offers.filter { $0.plan != plan.plan && store.price($0, store.runningPeriod) != nil }) { o in
                            Button("Switch to \(o.name)") { store.changePlan(to: o) }.disabled(store.busy)
                        }
                        Text("A bigger plan starts at once; a smaller one from the next renewal.").font(.caption).foregroundStyle(Theme.muted)
                    }
                    if let d = store.devices {
                        HStack {
                            Button("+ Add a device") { store.changeDevices(by: 1) }.disabled(store.busy || d.have >= d.max)
                            Spacer()
                            Button("Remove one") { store.changeDevices(by: -1) }.disabled(store.busy || d.have == 0)
                        }
                        Text("1 device is included. \(d.have) of \(d.max) extra devices bought in the app.").font(.caption).foregroundStyle(Theme.muted)
                    }
                    Button("Restore purchases") { store.restore() }.disabled(store.busy)
                    StoreLines(store: store)
                }
                Section {
                    if let t = account.transfer {
                        if let img = qrImage(t.url) { Image(uiImage: img).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 220).frame(maxWidth: .infinity) }
                        Text(t.code).font(.title3.monospaced()).frame(maxWidth: .infinity)
                        if let exp = t.expiresAt { Text("Valid until \(exp.formatted(date: .omitted, time: .shortened))").font(.caption).foregroundStyle(Theme.muted) }
                        Button("Hide") { account.stopTransfer() }
                    } else {
                        Button("Move to a new phone") { account.startTransfer() }.disabled(account.transferBusy)
                        if let e = account.transferError { Text("⚠ \(e)").font(.footnote).foregroundStyle(Theme.warn) }
                    }
                } header: { Text("Move to a new phone") } footer: {
                    Text("On the new phone, open the app and connect with this code. It takes over this phone's place; this one is disconnected then.")
                }
            }
            if !account.pending.isEmpty {
                Section {
                    ForEach(account.pending, id: \.clientRef) { op in
                        HStack {
                            Text(op.label).font(.footnote)
                            Spacer()
                            Button { account.dropPending(op.clientRef) } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
                        }
                    }
                } header: { Text("Waiting to sync") } footer: { Text("Sent as soon as there is a connection.") }
            }
            Section {
                Link("Privacy policy", destination: account.privacyUrl)
                Button("Sign out on this phone") { account.disconnect() }.disabled(account.busy)
                Button("Delete account…", role: .destructive) { confirmDelete = true }.disabled(account.busy)
            } footer: { Text("Deleting removes the account with its collection and missing cards, and signs out every device.") }
            if let e = account.error { Section { Text("⚠ \(e)").foregroundStyle(Theme.warn) } }
        }
        .navigationTitle("Account")
        .task { store.refresh() }
        .manageSubscriptionsSheet(isPresented: $manage)
        .offerCodeRedemption(isPresented: $redeem) { _ in Task { await store.sendWaiting() } }
        .confirmationDialog("Delete your account?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { account.deleteAccount() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your collection, missing cards and sign-ins are deleted at once, and every device is signed out. An App Store subscription you cancel in the App Store yourself.")
        }
    }

    private func qrImage(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// + Collection and ☆ Missing for the shown card, with what the collection already holds.
struct CollectionBar: View {
    @ObservedObject var account: AccountModel
    let s: Shown
    /// the condition + Collection adds the copy in (Trading: the one chosen)
    var condition = "NM"
    @State private var sheet = false

    var body: some View {
        let coll = account.has("collection"), want = account.has("wanted")
        let cardId = s.version.id
        let lang = ShopTerms.languageOf(s.printedOnly ?? s.version.lang)
        let finish = ShopTerms.finishOf(s.printing)
        let label = "\(s.version.name) · \(s.version.setName) \(s.version.numberText)"
        let wanted = account.wanted.first { $0.matches(cardId, lang, finish) }
        if coll || want {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    if coll {
                        Button { account.addToCollection(cardId: cardId, language: lang, finish: finish, condition: condition, label: label) } label: {
                            Text("+ Collection").font(.subheadline.bold()).foregroundStyle(Theme.ink)
                                .padding(.horizontal, 14).padding(.vertical, 8).background(Theme.gold, in: Capsule())
                        }
                        .simultaneousGesture(LongPressGesture().onEnded { _ in account.loadBinders(); sheet = true })
                    }
                    if want {
                        Button { account.toggleWanted(cardId: cardId, language: lang, finish: finish, label: label) } label: {
                            Text(wanted != nil ? "★ Missing" : "☆ Missing").font(.subheadline.bold()).foregroundStyle(wanted != nil ? Theme.gold : .white)
                                .padding(.horizontal, 14).padding(.vertical, 7).overlay(Capsule().stroke(wanted != nil ? Theme.gold : Theme.muted))
                        }
                    }
                }
                if coll {
                    let lines = account.collection[cardId]
                    let waiting = account.pending.filter { $0.kind == "collection_add" && $0.cardId == cardId }
                        .reduce(0) { $0 + ((($1.bodyJSON?["quantity"] as? NSNumber)?.intValue) ?? 1) }
                    let have = lines?.reduce(0) { $0 + $1.quantity } ?? 0
                    let text = (lines != nil || waiting > 0 ? "In collection: \(have)" : "")
                        + (have > 0 ? " (" + (lines ?? []).map { "\($0.quantity) × \($0.language) \(ShopTerms.finishLabel($0.finish)) \($0.condition)" + ($0.graded.map { " \($0)" } ?? "") }.joined(separator: " · ") + ")" : "")
                        + (waiting > 0 ? " · +\(waiting) waiting to sync" : "")
                    if !text.isEmpty { Text(text).font(.caption).foregroundStyle(Theme.muted) }
                }
                if wanted?.pending == true { Text("☆ waiting to sync").font(.caption).foregroundStyle(Theme.muted) }
                else if wanted?.state == "offer" { Link("Offer from the shop ›", destination: URL(string: account.portalUrl + "/missing")!).font(.footnote.bold()).foregroundStyle(Theme.gold) }
                else if wanted?.state == "available" { Link("In the shop now ›", destination: URL(string: account.portalUrl + "/missing")!).font(.footnote.bold()).foregroundStyle(Theme.sure) }
            }
            .padding(.top, 8)
            .task(id: cardId) { account.loadCollection(cardId) }
            .sheet(isPresented: $sheet) {
                CollectionSheet(account: account) { cond, qty, cents, binder, note in
                    account.addToCollection(cardId: cardId, language: lang, finish: finish, condition: cond, quantity: qty,
                                            purchaseCents: cents, binderId: binder, note: note, label: label)
                }
                .presentationDetents([.medium, .large])
            }
        }
    }
}

/// Long press on + Collection: the details of the copy.
struct CollectionSheet: View {
    @ObservedObject var account: AccountModel
    let onAdd: (String, Int, Int64?, Int64?, String?) -> Void
    @State private var cond = "NM"
    @State private var qty = 1
    @State private var price = ""
    @State private var binder: Int64?
    @State private var note = ""
    @Environment(\.dismiss) private var dismiss

    private var cents: Int64? {
        Double(price.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")).flatMap { $0 >= 0 ? Int64(($0 * 100).rounded()) : nil }
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("Condition", selection: $cond) { ForEach(ShopTerms.conditions) { Text("\($0.code) · \($0.label)").tag($0.code) } }
                Stepper("Quantity: \(qty)", value: $qty, in: 1...999)
                TextField("Purchase price per copy (€)", text: $price).keyboardType(.decimalPad)
                if !account.binders.isEmpty {
                    Picker("Binder", selection: $binder) {
                        Text("Main collection").tag(Int64?.none)
                        ForEach(account.binders) { b in Text(b.name).tag(Int64?.some(b.id)) }
                    }
                }
                TextField("Note", text: $note, axis: .vertical)
            }
            .navigationTitle("Add to the collection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { onAdd(cond, qty, cents, binder, note.isEmpty ? nil : String(note.prefix(500))); dismiss() }
                        .disabled(!price.isEmpty && cents == nil)
                }
            }
        }
    }
}

/// The missing cards list: what the shop looks for, with offers.
struct MissingCardsView: View {
    @ObservedObject var account: AccountModel
    let catalog: Catalog

    var body: some View {
        List {
            if account.wanted.isEmpty {
                Text("No missing cards yet. Tap ☆ Missing on a scanned card to add it.").foregroundStyle(Theme.muted)
            }
            ForEach(account.wanted) { w in
                let card = catalog.card(w.cardId, "en")
                HStack(spacing: 12) {
                    if let card { CardPicture(card: card).frame(width: 40) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(card?.name ?? w.cardId).font(.body.weight(.semibold))
                        Text([card?.setLine(withLang: false), w.language, w.finish.map(ShopTerms.finishLabel)].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(Theme.muted)
                    }
                    Spacer()
                    Text(w.pending ? "waiting to sync" : w.state == "offer" ? "Offer ›" : w.state == "available" ? "In the shop ›" : "searching")
                        .font(.caption).foregroundStyle(w.state == "offer" ? Theme.gold : w.state == "available" ? Theme.sure : Theme.muted)
                }
                .swipeActions {
                    Button("Remove", role: .destructive) {
                        account.toggleWanted(cardId: w.cardId, language: w.language, finish: w.finish, label: card?.name ?? w.cardId)
                    }
                }
            }
        }
        .navigationTitle("Missing cards")
        .refreshable { account.check(force: true) }
    }
}
