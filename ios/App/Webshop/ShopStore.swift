import Foundation

/**
 * The webshop connection on this phone: host and key id in the app's settings, the secret in the
 * Keychain (this device only). Nothing of it is in the app, in Git or in a log. Also what Webshop
 * Mode remembers: the last box and condition, Stock in or Sell, the minimum selling price.
 * (Port of the Android app's ShopStore.kt.)
 */
final class ShopStore {
    private let defaults: UserDefaults
    private let vault: SecretVault

    init(defaults: UserDefaults = .standard, vault: SecretVault = Keychain(service: "com.overpricedcardboard.webshop")) {
        self.defaults = defaults
        self.vault = vault
    }

    var host: String { defaults.string(forKey: "webshop.host") ?? "" }
    var keyId: String { defaults.string(forKey: "webshop.key_id") ?? "" }
    var hasSecret: Bool { !(vault.read("secret") ?? "").isEmpty }

    /// the last storage box used, so the next card goes to the same one (1–999)
    var lastBox: Int? {
        get { let b = defaults.integer(forKey: "webshop.last_box"); return (1...999).contains(b) ? b : nil }
        set { defaults.set(newValue ?? 0, forKey: "webshop.last_box") }
    }

    /// The lowest price a new product is suggested at (EUR; nil: none). A card worth a few cents is still sold for this.
    var minPrice: Double? {
        get { let v = defaults.double(forKey: "webshop.min_price"); return v > 0 ? (v * 100).rounded() / 100 : nil }
        set { defaults.set(newValue.flatMap { $0 > 0 ? $0 : nil } ?? 0, forKey: "webshop.min_price") }
    }

    /// Webshop Mode was last on Sell (a customer's order) rather than Stock in
    var selling: Bool {
        get { defaults.bool(forKey: "webshop.selling") }
        set { defaults.set(newValue, forKey: "webshop.selling") }
    }

    /// the last condition picked (most cards of a batch are in the same condition)
    var lastCondition: String {
        get { defaults.string(forKey: "webshop.last_condition") ?? "NM" }
        set { defaults.set(newValue, forKey: "webshop.last_condition") }
    }

    /// Saves the connection; secret nil keeps the stored one.
    func save(host: String, keyId: String, secret: String?) {
        defaults.set(host.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "webshop.host")
        defaults.set(keyId.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "webshop.key_id")
        if let s = secret?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty { vault.write("secret", s) }
    }

    func clear() {
        defaults.removeObject(forKey: "webshop.host")
        defaults.removeObject(forKey: "webshop.key_id")
        vault.delete("secret")
    }

    /// The connection, or nil if something is missing.
    func credentials() -> ShopCredentials? {
        guard let base = ShopCredentials.baseUrlOf(host), !keyId.trimmingCharacters(in: .whitespaces).isEmpty,
              let secret = vault.read("secret"), !secret.isEmpty else { return nil }
        return ShopCredentials(baseUrl: base, keyId: keyId, secret: secret)
    }
}
