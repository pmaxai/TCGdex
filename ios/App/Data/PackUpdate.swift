import BackgroundTasks
import CryptoKit
import Foundation

/**
 * The App Store edition gets no new app every night, so it downloads the nightly data pack (prices,
 * new cards) from the shop (`GET /api/app/v1/pack`): only the files that changed, each checked
 * against its SHA-256. A complete set waits in "pack-new" and replaces the pack at the app's next
 * start. A pack made for another fingerprint model than this app's is left alone (that needs an
 * app update). Port of the Android app's PackUpdate.
 */
enum PackUpdate {
    static let readyDir = "pack-new"
    static let workDir = "pack-dl"
    static let marker = "ready"
    static let taskId = "com.overpricedcardboard.app.pack"

    /// the app's own storage for packs (not backed up: it can be downloaded again)
    static var root: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("packs")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        var v = u
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? v.setResourceValues(values)
        return u
    }

    static func manifest(_ dir: URL) -> JSON? {
        (try? Data(contentsOf: dir.appendingPathComponent("pack.json"))).flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? JSON }
    }

    static func versionOf(_ dir: URL) -> String? { manifest(dir).flatMap { AccountStatus.str($0, "version") } }

    /// a newer pack waits for the next start: its version
    static func waiting(_ root: URL) -> String? {
        let r = root.appendingPathComponent(readyDir)
        return FileManager.default.fileExists(atPath: r.appendingPathComponent(marker).path) ? versionOf(r) : nil
    }

    /// two packs work with the same app: the same fingerprint model and size
    static func fits(_ a: JSON?, _ b: JSON?) -> Bool {
        guard let a, let b else { return false }
        return AccountStatus.str(a, "model") == AccountStatus.str(b, "model") && (a["dim"] as? NSNumber)?.intValue == (b["dim"] as? NSNumber)?.intValue
    }

    /**
     * The pack to open at start: a complete newer download takes the place of the one in use; the
     * downloaded pack is used while it is at least as new as the app's own and fits it.
     */
    static func choose(root: URL = PackUpdate.root, bundled: URL?) -> URL? {
        promote(root, bundled: bundled.flatMap(manifest))
        return inUse(root: root, bundled: bundled)
    }

    /// the pack the app uses (without promoting a waiting one)
    static func inUse(root: URL = PackUpdate.root, bundled: URL?) -> URL? {
        let bundledJson = bundled.flatMap(manifest)
        let inUse = root.appendingPathComponent("pack")
        if let v = versionOf(inUse), let b = bundledJson.flatMap({ AccountStatus.str($0, "version") }), v >= b, fits(manifest(inUse), bundledJson) {
            return inUse
        }
        return bundled
    }

    static func promote(_ root: URL, bundled: JSON?) {
        let fm = FileManager.default
        let ready = root.appendingPathComponent(readyDir)
        guard fm.fileExists(atPath: ready.appendingPathComponent(marker).path) else { return }
        let inUse = root.appendingPathComponent("pack")
        if let v = versionOf(ready), fits(manifest(ready), bundled), (versionOf(inUse) ?? "") < v {
            let old = root.appendingPathComponent("pack-old")
            try? fm.removeItem(at: old)
            if fm.fileExists(atPath: inUse.path) { do { try fm.moveItem(at: inUse, to: old) } catch { try? fm.removeItem(at: ready); return } }
            do {
                try fm.moveItem(at: ready, to: inUse)
                try? fm.removeItem(at: inUse.appendingPathComponent(marker))
                try? fm.removeItem(at: old)
            } catch {
                try? fm.moveItem(at: old, to: inUse)
            }
        }
        try? fm.removeItem(at: ready)
    }

    /**
     * Downloads the newest pack when it is newer than the one in use (or waiting) and fits this
     * app. Returns its version when a new one is ready, else nil.
     */
    static func download(root: URL = PackUpdate.root, current: URL, api: AccountApi, token: String, session: URLSession = .shared) async throws -> String? {
        let fm = FileManager.default
        let currentJson = manifest(current)
        let have = [versionOf(current), waiting(root)].compactMap { $0 }.max()
        let o = try await api.pack(token: token)
        guard let version = AccountStatus.str(o, "version") else { return nil }
        if let have, version <= have { return nil }
        let files = o["files"] as? [JSON] ?? []
        let work = root.appendingPathComponent(workDir)
        // parts of another version's download don't count
        if (try? String(contentsOf: work.appendingPathComponent("version"), encoding: .utf8)) != version { try? fm.removeItem(at: work) }
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        try version.write(to: work.appendingPathComponent("version"), atomically: true, encoding: .utf8)
        // the manifest first: a pack for another model is not for this app
        let order = files.filter { AccountStatus.str($0, "name") == "pack.json" } + files.filter { AccountStatus.str($0, "name") != "pack.json" }
        for f in order {
            guard let name = AccountStatus.str(f, "name"), name.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil, !name.hasPrefix("."),
                  let size = (f["size"] as? NSNumber)?.int64Value, let sha = AccountStatus.str(f, "sha256")?.lowercased() else {
                throw ServerUnavailable(code: 200, message: "pack: an odd file entry")
            }
            let target = work.appendingPathComponent(name)
            if !(fileSize(target) == size && sha256(target) == sha) {
                try? fm.removeItem(at: target)
                let old = current.appendingPathComponent(name)
                let oldSha = ((currentJson?["files"] as? JSON)?[name] as? JSON).flatMap { AccountStatus.str($0, "sha256")?.lowercased() }
                if oldSha == sha && fileSize(old) == size {
                    try fm.copyItem(at: old, to: target)                // unchanged: from the pack in use
                } else {
                    guard let url = AccountStatus.str(f, "url").flatMap(URL.init(string:)) else { throw ServerUnavailable(code: 200, message: "pack: no address for \(name)") }
                    let (tmp, res) = try await session.download(from: url)
                    let code = (res as? HTTPURLResponse)?.statusCode ?? 0
                    guard (200..<300).contains(code) else { throw URLError(.badServerResponse) }
                    guard fileSize(tmp) == size, sha256(tmp) == sha else { try? fm.removeItem(at: tmp); throw URLError(.cannotDecodeContentData) }
                    try fm.moveItem(at: tmp, to: target)
                }
            }
            if name == "pack.json", currentJson != nil, !fits(currentJson, manifest(work)) {
                try? fm.removeItem(at: work)
                return nil
            }
        }
        // complete: it replaces what waits, and is used from the next start
        let ready = root.appendingPathComponent(readyDir)
        try? fm.removeItem(at: ready)
        try? fm.removeItem(at: work.appendingPathComponent("version"))
        try fm.moveItem(at: work, to: ready)
        try version.write(to: ready.appendingPathComponent(marker), atomically: true, encoding: .utf8)
        return version
    }

    private static func fileSize(_ u: URL) -> Int64? {
        (try? FileManager.default.attributesOfItem(atPath: u.path)[.size] as? NSNumber)?.int64Value
    }

    static func sha256(_ u: URL) -> String? {
        guard let h = try? FileHandle(forReadingFrom: u) else { return nil }
        defer { try? h.close() }
        var hasher = SHA256()
        while let chunk = try? h.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    // ---------------------------------------------------------------- in the background

    /// Registers the background download (at launch, before the app finishes starting).
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let task = task as? BGProcessingTask else { return }
            schedule()
            let job = Task {
                var ok = true
                do { _ = try await runOnce() } catch { ok = false }
                task.setTaskCompleted(success: ok)
            }
            task.expirationHandler = { job.cancel() }
        }
    }

    /// Asks iOS to run it again in about 6 hours, with a network connection.
    static func schedule() {
        let r = BGProcessingTaskRequest(identifier: taskId)
        r.requiresNetworkConnectivity = true
        r.earliestBeginDate = Date().addingTimeInterval(6 * 3600)
        try? BGTaskScheduler.shared.submit(r)
    }

    /// One check: connected, an active subscription, and at most every 6 hours unless forced.
    @discardableResult
    static func runOnce(force: Bool = false) async throws -> String? {
        let store = AccountStore()
        guard let token = store.token, let base = store.base, store.status()?.active == true else { return nil }
        let d = UserDefaults.standard
        if !force, let last = d.object(forKey: "pack.last_check") as? Date, Date().timeIntervalSince(last) < 6 * 3600 { return nil }
        guard let current = inUse(bundled: Pack.bundledDir) else { return nil }
        let v = try await download(current: current, api: AccountApi(base: base), token: token)
        d.set(Date(), forKey: "pack.last_check")
        return v
    }
}
