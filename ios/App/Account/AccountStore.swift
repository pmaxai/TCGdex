import Foundation
import Security
import UIKit

/// A change to the collection or the missing cards that waits to be sent (offline, or the server busy).
struct PendingOp: Codable, Equatable {
    /// collection_add, wanted_add, wanted_delete
    var kind: String
    /// the same on every try, so a repeat creates nothing twice
    var clientRef: String
    /// the account it belongs to (another account never gets it)
    var accountId: String?
    var cardId: String
    /// what is sent (adds), as JSON text
    var body: String?
    /// the missing card to remove (wanted_delete)
    var wantedId: Int64?
    /// "Glurak-ex · 151 199/165", for "waiting to sync"
    var label: String
    var added: Date = Date()

    var bodyJSON: JSON? { body.flatMap { (try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? JSON } }

    static func encode(_ o: JSON) -> String? {
        (try? JSONSerialization.data(withJSONObject: o)).flatMap { String(data: $0, encoding: .utf8) }
    }
}

/**
 * The account connection on this phone: the server, the device token (in the Keychain, this device
 * only; never in a log or export), the last status answer (offline it counts until valid_until),
 * the missing cards and what waits to be sent. The device id is a random one made once.
 */
final class AccountStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let dir: URL
    private let vault: SecretVault
    private let lock = NSLock()

    init(defaults: UserDefaults = .standard, dir: URL? = nil, vault: SecretVault = Keychain(service: "com.overpricedcardboard.account")) {
        self.defaults = defaults
        self.vault = vault
        self.dir = dir ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("account")
        try? FileManager.default.createDirectory(at: self.dir, withIntermediateDirectories: true)
    }

    var deviceId: String {
        if let id = defaults.string(forKey: "account.device_id") { return id }
        let id = UUID().uuidString.lowercased()
        defaults.set(id, forKey: "account.device_id")
        return id
    }

    /// the server this phone is connected to
    var base: String? { defaults.string(forKey: "account.base") }
    var token: String? { vault.read("token") }
    var connected: Bool { token != nil }

    /// the last status answer
    func status() -> AccountStatus? {
        guard let d = try? Data(contentsOf: dir.appendingPathComponent("status.json")),
              let o = (try? JSONSerialization.jsonObject(with: d)) as? JSON else { return nil }
        return AccountStatus.parse(o)
    }

    var lastCheck: Date? { defaults.object(forKey: "account.last_check") as? Date }

    /// why this phone was disconnected (shown on the connect screen until it is connected again)
    var notice: String? { defaults.string(forKey: "account.notice") }

    func connected(base: String, token: String, status: JSON, now: Date = Date()) {
        vault.write("token", token)
        defaults.set(base, forKey: "account.base")
        defaults.removeObject(forKey: "account.notice")
        saveStatus(status, now: now)
    }

    func saveStatus(_ status: JSON, now: Date = Date()) {
        if let d = try? JSONSerialization.data(withJSONObject: status) {
            try? d.write(to: dir.appendingPathComponent("status.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
        defaults.set(now, forKey: "account.last_check")
    }

    /// Not connected any more: token and saved answer go (the queue stays for this account if it comes back).
    func forget(notice: String?) {
        vault.delete("token")
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("status.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("wanted.json"))
        defaults.removeObject(forKey: "account.last_check")
        if let notice { defaults.set(notice, forKey: "account.notice") } else { defaults.removeObject(forKey: "account.notice") }
    }

    /// a creator code from a link (overpricedcardboard.com/r/CODE), sent with the first sign-in
    var pendingAffiliate: String? {
        get { defaults.string(forKey: "account.affiliate_code") }
        set { if let newValue { defaults.set(newValue, forKey: "account.affiliate_code") } else { defaults.removeObject(forKey: "account.affiliate_code") } }
    }

    // ---------------------------------------------------------------- queue and caches
    func queue() -> [PendingOp] {
        lock.lock(); defer { lock.unlock() }
        guard let d = try? Data(contentsOf: dir.appendingPathComponent("queue.json")) else { return [] }
        return (try? JSONDecoder().decode([PendingOp].self, from: d)) ?? []
    }

    func saveQueue(_ ops: [PendingOp]) {
        lock.lock(); defer { lock.unlock() }
        if let d = try? JSONEncoder().encode(ops) { try? d.write(to: dir.appendingPathComponent("queue.json"), options: .atomic) }
    }

    func wanted() -> [JSON]? {
        guard let d = try? Data(contentsOf: dir.appendingPathComponent("wanted.json")) else { return nil }
        return (try? JSONSerialization.jsonObject(with: d)) as? [JSON]
    }

    func saveWanted(_ items: [JSON]) {
        if let d = try? JSONSerialization.data(withJSONObject: items) { try? d.write(to: dir.appendingPathComponent("wanted.json"), options: .atomic) }
    }

    // ---------------------------------------------------------------- the free version
    /// the prices revealed (the last hour's count)
    func freeReveals() -> [FreeReveal] {
        guard let d = defaults.data(forKey: "free.reveals") else { return [] }
        return (try? JSONDecoder().decode([FreeReveal].self, from: d)) ?? []
    }

    func saveFreeReveals(_ r: [FreeReveal]) {
        if let d = try? JSONEncoder().encode(r) { defaults.set(d, forKey: "free.reveals") }
    }

    /// the collection kept on this phone (the free version's); it stays when the account goes
    func localCollection() -> [LocalItem] {
        lock.lock(); defer { lock.unlock() }
        guard let d = try? Data(contentsOf: dir.appendingPathComponent("local-collection.json")) else { return [] }
        return (try? JSONDecoder().decode([LocalItem].self, from: d)) ?? []
    }

    func saveLocalCollection(_ items: [LocalItem]) {
        lock.lock(); defer { lock.unlock() }
        if let d = try? JSONEncoder().encode(items) { try? d.write(to: dir.appendingPathComponent("local-collection.json"), options: .atomic) }
    }

    /// "iPhone 15 Pro": shown in the account with the device
    static var deviceName: String {
        var info = utsname()
        uname(&info)
        let model = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return Self.marketingName(model) ?? UIDevice.current.model
    }

    /// the few identifiers whose names people know best; others show as "iPhone"
    private static func marketingName(_ id: String) -> String? {
        let names = ["iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max",
                     "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus", "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max",
                     "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus", "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
                     "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus", "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max",
                     "iPhone17,5": "iPhone 16e", "iPhone14,6": "iPhone SE (3rd generation)"]
        return names[id]
    }
}

/// Where secrets are kept (the device token): the Keychain; tests use one in memory.
protocol SecretVault: AnyObject {
    func read(_ key: String) -> String?
    func write(_ key: String, _ value: String)
    func delete(_ key: String)
}

/// The Keychain, this device only, readable after the first unlock (for checks in the background).
final class Keychain: SecretVault {
    private let service: String
    init(service: String) { self.service = service }

    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }

    func read(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    func write(_ key: String, _ value: String) {
        delete(key)
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    func delete(_ key: String) { SecItemDelete(query(key) as CFDictionary) }
}

/// Secrets in memory only (tests, previews).
final class MemoryVault: SecretVault {
    private var items: [String: String] = [:]
    private let lock = NSLock()
    func read(_ key: String) -> String? { lock.lock(); defer { lock.unlock() }; return items[key] }
    func write(_ key: String, _ value: String) { lock.lock(); items[key] = value; lock.unlock() }
    func delete(_ key: String) { lock.lock(); items[key] = nil; lock.unlock() }
}
