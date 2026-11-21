import BackgroundTasks
import Foundation
import Network
import UIKit

/**
 * The recordings' uploads (the Android app's UploadWorker): one after another, oldest first, while
 * the network fits (Wi-Fi only, unless Upload now). A temporary failure waits (5 s, 10 s, … up to
 * 10 min) and goes on where it stopped; a permanent one (wrong key, no bucket) stops as Failed until
 * Retry. Leaving the app, the running part is finished in the time iOS gives; the rest goes on when
 * the app is back, or in a background task iOS runs later (often at night, on the charger).
 */
@MainActor
final class UploadQueue {
    static let shared = UploadQueue()
    nonisolated static let taskId = "com.overpricedcardboard.app.upload"
    /// S3 answers that mean the key is wrong or no longer allowed
    nonisolated static let accessErrors: Set<String> = ["AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch"]

    var store = RecordingStore()
    var prefs = RecordingPrefs()
    /// a recording's status changed (the Recordings list shows it)
    var onChange: (() -> Void)?
    /// uploads failed with an access error: the account's S3 settings fetched again; true when read
    var refreshManaged: () async -> Bool = { await ManagedStorage.refresh() }
    /// tests: the stand-in's session, no waiting
    var session: URLSession?
    var retryWait: Double = 5
    var appVersion = AccountApi.version ?? "?"

    private var job: Task<Void, Never>?
    private var current: String?
    private let monitor = NWPathMonitor()
    private(set) var connected = true
    private(set) var expensive = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    /// in a background task: no waiting for the network (the task ends instead)
    private var background = false

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let ok = path.status == .satisfied, exp = path.isExpensive
            Task { @MainActor in self?.network(ok, exp) }
        }
        monitor.start(queue: DispatchQueue(label: "uploads.network"))
    }

    var onWifi: Bool { connected && !expensive }

    private func network(_ ok: Bool, _ exp: Bool) {
        connected = ok
        expensive = exp
        let w = waiters
        waiters = []
        w.forEach { $0.resume() }
        if ok { kick() }
    }

    /// something may be waiting: the queue runs (once)
    func kick() {
        guard job == nil else { return }
        job = Task { [weak self] in
            await self?.runAll()
            self?.job = nil
        }
    }

    /// stops the upload of this recording (it is being deleted)
    func cancel(_ id: String) {
        if current == id { job?.cancel(); job = nil }
    }

    /// every upload pauses (the background task's time is up)
    func pause() { job?.cancel(); job = nil }

    func idle() async { await job?.value }

    private func mayGo(_ d: RecordingDir, _ s: RecordingSettings) -> Bool {
        connected && (!s.wifiOnly || !expensive || (d.meta()["any_network"] as? Bool) == true)
    }

    private func runAll() async {
        while !Task.isCancelled {
            let s = prefs.load()
            let queued = store.list().reversed().filter { $0.status == .QUEUED || $0.status == .UPLOADING }
            if queued.isEmpty { return }
            guard let d = queued.first(where: { mayGo($0, s) }) else {
                for q in queued where q.status != .QUEUED || (q.meta()["waiting_wifi"] as? Bool) != (s.wifiOnly && !onWifi) {
                    q.updateMeta { $0["status"] = RecStatus.QUEUED.rawValue; $0["waiting_wifi"] = s.wifiOnly && !self.onWifi }
                }
                onChange?()
                if background { return }
                await withCheckedContinuation { waiters.append($0) }
                continue
            }
            await upload(d, s)
        }
    }

    private final class Progress: @unchecked Sendable {
        let lock = NSLock()
        var last = -1
        func changed(_ pct: Int) -> Bool { lock.lock(); defer { lock.unlock() }; if pct == last { return false }; last = pct; return true }
    }

    private func upload(_ d: RecordingDir, _ s: RecordingSettings) async {
        guard var cfg = prefs.s3Config(s) else {
            d.updateMeta { $0["status"] = RecStatus.FAILED.rawValue; $0["error"] = "Upload settings are incomplete" }
            onChange?()
            return
        }
        current = d.id
        defer { current = nil }
        let bg = UIApplication.shared.beginBackgroundTask(withName: "upload") { [weak self] in
            MainActor.assumeIsolated { self?.pause() }
        }
        defer { if bg != .invalid { UIApplication.shared.endBackgroundTask(bg) } }
        d.updateMeta { $0["status"] = RecStatus.UPLOADING.rawValue; $0["error"] = ""; $0["waiting_wifi"] = false }
        onChange?()
        var refetched = false
        var wait = retryWait
        let progress = Progress()
        while !Task.isCancelled {
            do {
                let client = try S3Client(cfg: cfg, session: session)
                let uploader = RecordingUploader(client: client, recordingId: d.id, state: UploadState(d.uploadState), appVersion: appVersion) { done, total in
                    let pct = total > 0 ? Int(done * 100 / total) : 0
                    guard progress.changed(pct) else { return }
                    d.updateMeta { $0["progress"] = pct }
                    Task { @MainActor [weak self] in self?.onChange?() }
                }
                try await uploader.run(d.uploadFiles())
                d.updateMeta {
                    $0["status"] = RecStatus.UPLOADED.rawValue; $0["progress"] = 100; $0["any_network"] = nil
                    $0["uploaded_at"] = Int64(Date().timeIntervalSince1970 * 1000)
                }
                // read back from Photos only for this upload: they stay there, the app's copies go
                if s.deleteAfterUpload || (d.meta()["from_photos"] as? Bool) == true {
                    try? FileManager.default.removeItem(at: d.video)
                    try? FileManager.default.removeItem(at: d.camera)
                }
                onChange?()
                return
            } catch let e as S3Error {
                // the account's storage: the shop may have changed the key – fetch it again once, then go on
                if s.managed && !refetched && Self.accessErrors.contains(e.code) {
                    refetched = true
                    if await refreshManaged(), let again = prefs.s3Config() { cfg = again; continue }
                }
                if e.permanent || e.code == "BadEndpoint" {
                    d.updateMeta { $0["status"] = RecStatus.FAILED.rawValue; $0["error"] = e.friendly }
                    onChange?()
                    return
                }
            } catch {
                if Task.isCancelled { break }
            }
            if Task.isCancelled { break }
            d.updateMeta { $0["status"] = RecStatus.QUEUED.rawValue }
            onChange?()
            // the network went away: back to the queue, which waits for it
            if !mayGo(d, prefs.load()) { return }
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            wait = min(wait * 2, 600)
            if Task.isCancelled { break }
            d.updateMeta { $0["status"] = RecStatus.UPLOADING.rawValue }
        }
        // paused (the app left, the background time ran out): it goes on later
        d.updateMeta { $0["status"] = RecStatus.QUEUED.rawValue }
        onChange?()
    }

    // ---------------------------------------------------------------- the background task

    nonisolated static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let task = task as? BGProcessingTask else { return }
            Task { @MainActor in
                let q = UploadQueue.shared
                task.expirationHandler = { Task { @MainActor in q.pause() } }
                q.background = true
                q.kick()
                await q.idle()
                q.background = false
                task.setTaskCompleted(success: true)
                schedule()
            }
        }
    }

    /// The app leaves the screen: uploads still waiting get a background task.
    static func schedule() {
        let waiting = shared.store.list().contains { $0.status == .QUEUED || $0.status == .UPLOADING }
        guard waiting else { return }
        let r = BGProcessingTaskRequest(identifier: taskId)
        r.requiresNetworkConnectivity = true
        r.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(r)
    }
}

/// The account's S3 storage, for uploads that run without the screens (Android's ManagedStorage).
enum ManagedStorage {
    static let updatedKey = "account.s3_updated_at"

    /// Uploads failed with an access error: the S3 settings fetched again (the shop may have changed the key). True when read.
    static func refresh() async -> Bool {
        let store = AccountStore()
        guard let token = store.token, let base = store.base,
              let o = try? await AccountApi(base: base).s3(token: token) else { return false }
        RecordingPrefs().saveManaged(o)
        UserDefaults.standard.set(o["updated_at"] as? String, forKey: updatedKey)
        return true
    }
}
