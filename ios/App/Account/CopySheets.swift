import SwiftUI
import UIKit

/// The collection page's Edit: every detail the shop allows (quantity, condition, printing, language,
/// grading, purchase price, binder, note), and Delete. For a copy in the account's collection or on the phone.
struct EditCopySheet: View {
    let title: String
    /// binders to choose from (none on the phone)
    let binders: [Binder]
    let original: CopyDetails
    /// nil: saved, else why not
    let onSave: (CopyDetails) async -> String?
    let onDelete: () async -> String?

    @State private var d: CopyDetails
    @State private var price: String
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false
    @Environment(\.dismiss) private var dismiss

    init(title: String, binders: [Binder], details: CopyDetails, onSave: @escaping (CopyDetails) async -> String?,
         onDelete: @escaping () async -> String?) {
        self.title = title
        self.binders = binders
        original = details
        self.onSave = onSave
        self.onDelete = onDelete
        _d = State(initialValue: details)
        _price = State(initialValue: details.purchaseCents.map { String(format: "%.2f", Double($0) / 100) } ?? "")
    }

    /// the purchase price in cents: .some(nil) when empty, nil when it isn't a price
    private var cents: Int64?? {
        let t = price.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        if t.isEmpty { return .some(nil) }
        guard let v = Double(t), v >= 0, v < 10_000_000 else { return nil }
        return .some(Int64((v * 100).rounded()))
    }

    private var languages: [ShopTerms.Lang] {
        ShopTerms.languages.contains { $0.code == d.language } ? ShopTerms.languages : [ShopTerms.Lang(code: d.language, name: d.language)] + ShopTerms.languages
    }

    private var finishes: [String] { ShopTerms.finishes.contains(d.finish) ? ShopTerms.finishes : [d.finish] + ShopTerms.finishes }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Stepper("Quantity: \(d.quantity)", value: $d.quantity, in: 1...999)
                    Picker("Condition", selection: $d.condition) {
                        ForEach(ShopTerms.conditions) { Text("\($0.code) · \($0.label)").tag($0.code) }
                    }
                    Picker("Printing", selection: $d.finish) {
                        ForEach(finishes, id: \.self) { Text(ShopTerms.finishLabel($0)).tag($0) }
                    }
                    Picker("Language", selection: $d.language) {
                        ForEach(languages) { Text($0.name).tag($0.code) }
                    }
                }
                Section("Grading") {
                    Toggle("Graded", isOn: $d.graded.animation())
                    if d.graded {
                        TextField("Company, e.g. PSA", text: $d.company).textInputAutocapitalization(.characters)
                        TextField("Grade, e.g. 10", text: $d.grade)
                    }
                }
                Section {
                    TextField("Purchase price per copy (€)", text: $price).keyboardType(.decimalPad)
                    if cents == nil { Text("A price like 12.50").font(.caption).foregroundStyle(Theme.warn) }
                    if !binders.isEmpty {
                        Picker("Binder", selection: $d.binderId) {
                            Text("Main collection").tag(Int64?.none)
                            ForEach(binders) { b in Text(b.name).tag(Int64?.some(b.id)) }
                        }
                    }
                    TextField("Note", text: $d.note, axis: .vertical).lineLimit(1...4)
                }
                if let e = error ?? d.problem {
                    Section { Text(e).foregroundStyle(Theme.warn) }
                }
                Section {
                    Button("Delete from the collection", role: .destructive) { confirmDelete = true }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.ink)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button("Save") { save() }.disabled(cents == nil || d.problem != nil)
                    }
                }
            }
            .confirmationDialog("Delete \(original.quantity) × \(title)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    busy = true
                    Task {
                        error = await onDelete()
                        busy = false
                        if error == nil { dismiss() }
                    }
                }
            } message: { Text("It leaves your collection.") }
            .interactiveDismissDisabled(busy)
        }
    }

    private func save() {
        guard let c = cents else { return }
        var new = d
        new.purchaseCents = c
        busy = true
        Task {
            error = await onSave(new)
            busy = false
            if error == nil { dismiss() }
        }
    }
}

/// The collection's Share: a public link of the whole collection with prices to send to friends, and
/// Make private (every public link stops; personal links sent by email from the website keep running).
struct ShareCollectionSheet: View {
    @ObservedObject var account: AccountModel
    @State private var loading = true
    @State private var busy = false
    @State private var error: String?
    @State private var copied = false
    @State private var confirmPrivate = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                if loading {
                    Section { HStack { Spacer(); ProgressView(); Spacer() } }
                } else if let link = account.publicLink, let url = URL(string: link.url) {
                    Section {
                        Label("Public", systemImage: "globe").font(.headline).foregroundStyle(Theme.sure)
                        Text("Anyone with the link sees your whole collection and what the cards are worth.")
                            .font(.subheadline).foregroundStyle(Theme.muted)
                        Text(link.url).font(.footnote.monospaced()).textSelection(.enabled)
                        Text(viewsLine(link)).font(.caption).foregroundStyle(Theme.muted)
                    }
                    Section {
                        ShareLink(item: url, subject: Text("My Pokémon card collection"),
                                  message: Text("My collection on Overpriced Cardboard")) {
                            Label("Send the link", systemImage: "square.and.arrow.up")
                        }
                        Button {
                            UIPasteboard.general.string = link.url
                            copied = true
                        } label: { Label(copied ? "Copied" : "Copy the link", systemImage: copied ? "checkmark" : "doc.on.doc") }
                    }
                    privateSection
                } else {
                    Section {
                        Label("Private", systemImage: "lock.fill").font(.headline).foregroundStyle(.white)
                        Text("Only you see your collection.").font(.subheadline).foregroundStyle(Theme.muted)
                    }
                    if error != PublicLink.notYet {
                        Section {
                            Button { run { await account.makePublic() } } label: { Label("Create a public link", systemImage: "link") }
                                .disabled(busy)
                        } footer: {
                            Text("Anyone with the link sees your whole collection and what the cards are worth. You can make it private again at any time.")
                        }
                    }
                    if account.otherPublicLinks > 0 { privateSection }
                }
                if let error {
                    Section { Text(error).foregroundStyle(Theme.warn) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.ink)
            .navigationTitle("Share your collection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else { Button("Done") { dismiss() } }
                }
            }
            .task {
                error = await account.loadShare()
                loading = false
            }
            .confirmationDialog("Make your collection private?", isPresented: $confirmPrivate, titleVisibility: .visible) {
                Button("Make private", role: .destructive) { run { await account.makePrivate() } }
            } message: {
                Text("Every public link stops working at once. Personal links you sent by email from the website keep running until they end.")
            }
        }
    }

    @ViewBuilder private var privateSection: some View {
        Section {
            Button("Make private", role: .destructive) { confirmPrivate = true }.disabled(busy)
        } footer: {
            if account.otherPublicLinks > 0 {
                Text(account.otherPublicLinks == 1 ? "1 more public link made on the website is on too; it stops as well."
                     : "\(account.otherPublicLinks) more public links made on the website are on too; they stop as well.")
            }
        }
    }

    private func viewsLine(_ link: PublicLink) -> String {
        let views = link.views == 1 ? "1 view" : "\(link.views) views"
        guard let d = link.createdAt else { return views }
        return "\(views) · since \(d.formatted(date: .abbreviated, time: .omitted))"
    }

    private func run(_ call: @escaping () async -> String?) {
        busy = true
        copied = false
        Task {
            error = await call()
            busy = false
        }
    }
}
