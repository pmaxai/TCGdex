import AVFoundation
import SwiftUI
import UIKit

/// One recording in the Recordings list.
struct RecInfo: Identifiable, Equatable {
    let id: String
    let date: String
    let durationMs: Int64
    let cards: Int
    let hits: Int
    let bytes: Int64
    let status: RecStatus
    let statusText: String
    let error: String?
    /// being saved into Photos or read back from there: no buttons meanwhile
    var busy = false

    /// "4:53 · 12 cards · 2 hits · 412 MB"
    var line: String { "\(RecordingDir.durationText(durationMs)) · \(cards) cards · \(hits) hits · \(RecordingDir.sizeText(bytes))" }
}

/**
 * Recording in YouTube Mode (the Android app's RecordingCoordinator): starts and finishes
 * recordings (folder, header, event log, pack CSV), recovers one the app didn't finish, queues
 * uploads or saves into Photos, and keeps the Recordings list. The capture runs in ScreenRecorder.
 */
@MainActor
final class RecordingModel: ObservableObject {
    @Published private(set) var phase: ScreenRecorder.Phase = .idle
    @Published private(set) var settings = RecordingSettings()
    @Published private(set) var recordings: [RecInfo] = []
    /// "Recording 30:34 · 38 cards · 3 hits · 1.8 GB · queued" after Stop
    @Published var lastSummary: String?
    /// a finished recording waiting for the person: submit (upload), save, or delete; with its line
    @Published private(set) var toDecide: RecInfo?
    @Published private(set) var toDecideText: String?
    @Published private(set) var connection: String?
    @Published private(set) var connectionOk: Bool?
    @Published private(set) var testing = false
    @Published var error: String?
    @Published var notice: String?

    var recording: Bool { phase != .idle }
    /// the Fit to 16:9 area on the screen (points) while recording
    var contentRect: CGRect?

    let store: RecordingStore
    let prefs: RecordingPrefs
    let uploads: UploadQueue
    private let recorder: ScreenRecorder
    private let cameraFile = CameraFileWriter()
    private weak var scanner: ScannerModel?
    private var log: EventLog?
    private var saving: Set<String> = []
    private var fetching: Set<String> = []
    private let templatesCache: URL
    /// Uploading is possible (the account's S3 storage and Automatic Content Processing); without it recordings go to Photos.
    var uploadAllowed: () -> Bool = { false }
    /// the data pack's version and price day (header.json)
    var packInfo: () -> (String?, String?) = { (nil, nil) }

    init(store: RecordingStore = RecordingStore(), prefs: RecordingPrefs = RecordingPrefs(), uploads: UploadQueue? = nil,
         templatesCache: URL? = nil) {
        self.store = store
        self.prefs = prefs
        self.uploads = uploads ?? UploadQueue.shared
        recorder = ScreenRecorder(store: store)
        self.templatesCache = templatesCache ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("templates/index.json")
        settings = loadSettings()
        recorder.onPhase = { [weak self] p in self?.phase = p }
        recorder.onFinished = { [weak self] f in Task { await self?.finish(f) } }
        self.uploads.onChange = { [weak self] in self?.refresh() }
    }

    /// The scanner whose pack and shown card the log follows.
    func attach(_ s: ScannerModel) {
        scanner = s
        s.packObserver = { [weak self, weak s] reason, source in
            guard let self, let s, let log = self.log else { return }
            log.observe(reason, source: source, shown: s.shown, session: s.session, quad: s.cameraQuad())
        }
        s.camera.recordSink = { [cameraFile] sample in if cameraFile.active { cameraFile.append(sample) } }
        Task { await recover(); refresh(); uploads.kick() }
    }

    /// the settings as they count now (Upload to S3 only where it is allowed)
    private func loadSettings() -> RecordingSettings {
        var s = prefs.load()
        if s.uploadS3 && !uploadAllowed() { s.uploadS3 = false }
        return s
    }

    /// The account changed what is allowed: the settings shown again.
    func reload() { settings = loadSettings(); refresh() }

    /// The S3 settings Overpriced Cardboard set up for the account (nil: none, or gone).
    func applyManagedS3(_ o: JSON?) {
        if let o { prefs.saveManaged(o) } else { prefs.clearManaged() }
        reload()
        uploads.kick()
    }

    func saveSettings(_ s: RecordingSettings) {
        prefs.save(s)
        settings = loadSettings()
    }

    // ------------------------------------------------------------------ start and stop
    /**
     * A new folder, the header, the log; the screen takes the recording layout first (no ● REC
     * in the bar, the 16:9 area), then ReplayKit starts (iOS asks the first time).
     */
    func start(newPack: Bool, mic: Bool) {
        guard !recording, let sc = scanner else { return }
        if newPack { sc.resetSession() }
        if mic != settings.mic { var s = settings; s.mic = mic; saveSettings(s) }
        let s = settings
        let dir = store.create()
        let (packVersion, pricesDate) = packInfo()
        let header: JSON = [
            "app": ["version": RecJSON.s(AccountApi.version), "data_pack": RecJSON.s(packVersion), "prices_date": RecJSON.s(pricesDate)] as JSON,
            "device": ["model": AccountStore.deviceName, "ios": UIDevice.current.systemVersion] as JSON,
            "video": ["file": "video.mp4", "started_at": NSNull(), "duration_ms": NSNull(), "width": NSNull(), "height": NSNull(), "fps": 30,
                      "orientation": "portrait", "audio": s.mic ? "mic" : "none", "content_rect": NSNull()] as JSON,
            "camera": ["file": NSNull(), "width": NSNull(), "height": NSNull(), "fps": NSNull(), "offset_ms": NSNull(), "error": "off"] as JSON,
            "settings": ["hit_threshold_eur": sc.settings.threshold, "title": sc.settings.title, "show_psa": sc.settings.showPsa] as JSON,
            "ui": ["count_up_ms": Recording.countUpMs, "hit_burst_ms": Recording.hitBurstMs] as JSON,
        ]
        try? RecJSON.data(header).write(to: dir.header, options: .atomic)
        dir.updateMeta { $0["status"] = RecStatus.RECORDING.rawValue; $0["created"] = Int64(Date().timeIntervalSince1970 * 1000) }
        contentRect = nil
        lastSummary = nil
        let log = EventLog(recordingId: dir.id, jsonl: dir.eventsJsonl, threshold: sc.settings.threshold, revealTiming: true)
        recorder.onFirstFrame = { [weak self, weak sc] nanos in
            guard let self, let sc else { return }
            log.start(firstFrameNanos: nanos, packAtStart: sc.session, shown: sc.shown)
            self.log = log
        }
        // the clean camera file
        if s.recordCamera { cameraFile.prepare(dir.camera, bitrate: s.bitrateMbps * 1_000_000) }
        phase = .starting
        // the recording layout is drawn before the first frame
        Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            recorder.start(dir: dir, longSide: s.longSide, bitrate: s.bitrateMbps * 1_000_000, mic: s.mic)
        }
    }

    func stop(reason: String = "user") { recorder.stop(reason: reason) }

    /// YouTube Mode drew the first frame of the reveal of this card: its event takes this time.
    func revealDrawn(_ serial: Int) { log?.revealDrawn(serial, drawNanos: EventLog.hostNanos()) }

    /// The capture ended: events.json, pack.csv, the status; then the person decides.
    private func finish(_ f: FinishedCapture) async {
        let log = self.log
        self.log = nil
        recorder.onFirstFrame = nil
        let dir = f.dir
        let s0 = loadSettings()
        await cameraFile.stop()
        // the video stopped working during the recording: what was written until then is kept
        var durationMs = f.durationMs
        if f.failedMidway && RecordingDir.size(dir.video) > 0 {
            let playable = await RecordingStore.playableMs(dir.video)
            durationMs = playable > 0 ? playable : f.appendedMs
        }
        guard let log, durationMs > 0, let sc = scanner else {
            try? FileManager.default.removeItem(at: dir.dir)
            error = (f.failedMidway ? "Recording stopped: the video failed before any of it could be kept" : "Recording could not start")
                + (f.error.map { ": \($0)" } ?? "")
            refresh()
            return
        }
        log.stop(reason: f.reason, error: f.reason == "error" ? f.error : nil)
        var header = RecJSON.read(dir.header) ?? [:]
        if var v = header["video"] as? JSON {
            v["started_at"] = RecJSON.iso(f.startedAt)
            v["duration_ms"] = durationMs
            v["width"] = f.width
            v["height"] = f.height
            v["audio"] = f.hasAudio ? "mic" : "none"
            v["content_rect"] = contentRectJson(f, fit: s0.fit169)
            header["video"] = v
        }
        header["camera"] = cameraFile.headerJson(enabled: s0.recordCamera, file: dir.camera, screenZeroNanos: log.zeroNanos)
        let session = sc.session
        let events = log.finish(header: header, finalPack: session, status: "complete")
        try? events.write(to: dir.events, atomically: true, encoding: .utf8)
        try? PackCsv.build(session, threshold: sc.settings.threshold, title: sc.settings.title).write(to: dir.packCsv, atomically: true, encoding: .utf8)
        let hits = session.filter { isHit($0.value, sc.settings.threshold) }.count
        // kept on the phone until the person says: submit (upload), save or delete
        dir.updateMeta {
            $0["status"] = RecStatus.LOCAL.rawValue; $0["duration_ms"] = durationMs; $0["cards"] = session.count; $0["hits"] = hits
            $0["stop_reason"] = f.reason; $0["decide"] = true
            if let e = f.error, f.reason == "error" { $0["stop_error"] = e }
            if f.failedMidway { $0["video_failed"] = true }
        }
        contentRect = nil
        refresh()
    }

    /// The 16:9 area in video pixels (the whole frame when Fit to 16:9 is off).
    private func contentRectJson(_ f: FinishedCapture, fit: Bool) -> JSON {
        guard fit, let r = contentRect, f.screen.width > 0 else { return ["x": 0, "y": 0, "width": f.width, "height": f.height] }
        let sx = Double(f.width) / Double(f.screen.width), sy = Double(f.height) / Double(f.screen.height)
        return ["x": Int((r.minX * sx).rounded()), "y": Int((r.minY * sy).rounded()),
                "width": Int((r.width * sx).rounded()), "height": Int((r.height * sy).rounded())]
    }

    /// A recording the app didn't finish (stopped, crashed): kept up to its last whole fragment, log rebuilt, waits for the person.
    private func recover() async {
        guard !recording else { return }
        for dir in store.list() where dir.status == .RECORDING {
            let duration = RecordingDir.exists(dir.video) ? await RecordingStore.playableMs(dir.video) : 0
            if duration <= 0 { try? FileManager.default.removeItem(at: dir.dir); continue }
            try? FileManager.default.removeItem(at: dir.camera)      // the camera file is only complete after Stop
            var header = RecJSON.read(dir.header) ?? [:]
            if var c = header["camera"] as? JSON { c["file"] = NSNull(); c["error"] = "lost"; header["camera"] = c }
            let (events, cards) = RecordingStore.recoverEvents(header: header, jsonl: dir.eventsJsonl, id: dir.id, durationMs: duration)
            try? events.write(to: dir.events, atomically: true, encoding: .utf8)
            try? RecordingStore.csvOf(cards).write(to: dir.packCsv, atomically: true, encoding: .utf8)
            dir.updateMeta {
                $0["status"] = RecStatus.LOCAL.rawValue; $0["duration_ms"] = duration; $0["cards"] = cards.count
                $0["hits"] = cards.filter { ($0["hit"] as? Bool) == true }.count; $0["recovered"] = true; $0["decide"] = true
            }
        }
    }

    // ------------------------------------------------------------------ the list
    func refresh() {
        let df = DateFormatter()
        df.dateStyle = .medium
        df.timeStyle = .short
        let s = settings
        let list: [RecInfo] = store.list().filter { $0.status != .RECORDING || recording }.map { d in
            let m = d.meta()
            let created = (m["created"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) } ?? Date()
            let text = saving.contains(d.id) ? "saving to Photos…" : fetching.contains(d.id) ? "getting the videos from Photos…" : d.statusText(uploadS3: s.uploadS3)
            return RecInfo(id: d.id, date: df.string(from: created), durationMs: RecordingDir.int64(m["duration_ms"]), cards: RecordingDir.int(m["cards"]),
                           hits: RecordingDir.int(m["hits"]), bytes: d.shownBytes, status: d.status, statusText: text,
                           error: (m["error"] as? String).flatMap { $0.isEmpty ? nil : $0 }, busy: saving.contains(d.id) || fetching.contains(d.id))
        }
        recordings = list
        // the newest recording still waiting for submit, save or delete (not while one runs)
        let decide = recording ? nil : store.list().first { $0.status == .LOCAL && ($0.meta()["decide"] as? Bool) == true }
        toDecide = decide.flatMap { d in list.first { $0.id == d.id } }
        toDecideText = decide.map(decideLine)
    }

    /// "4:53 · 12 cards · 2 hits · 412 MB", and why it stopped when it wasn't the person
    private func decideLine(_ d: RecordingDir) -> String {
        let m = d.meta()
        let why: String
        if (m["recovered"] as? Bool) == true { why = " · recovered after the app stopped" }
        else {
            switch m["stop_reason"] as? String {
            case "low_storage": why = " · stopped: storage low"
            case "background": why = " · stopped: the app left the screen"
            case "error":
                let e = (m["stop_error"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "encoder error"
                why = (m["video_failed"] as? Bool) == true ? " · stopped by a video error, kept until then: " + e : " · stopped: " + e
            default: why = ""
            }
        }
        return "\(RecordingDir.durationText(RecordingDir.int64(m["duration_ms"]))) · \(RecordingDir.int(m["cards"])) cards · "
            + "\(RecordingDir.int(m["hits"])) hits · \(RecordingDir.sizeText(d.bytes))" + why
    }

    // ------------------------------------------------------------------ submit: templates and inputs
    /// The templates list for the submit screen (R2): the bucket's (saved on the phone), else the last saved one, else the built-in default.
    func loadTemplates() async -> LoadedTemplates {
        let fetch: (() async throws -> Data)? = prefs.s3Config().map { cfg in
            { [session = uploads.session] in
                let c = URLSessionConfiguration.default
                c.timeoutIntervalForRequest = 8
                let client = try S3Client(cfg: cfg, session: session ?? URLSession(configuration: c), sleep: { _ in })
                return try await client.getObject(Templates.key(cfg))
            }
        }
        return await Templates.load(fetch: fetch, cache: templatesCache)
    }

    /// The recording's pack at Stop, for the "cards" inputs.
    func packCards(_ id: String) -> [PackCard] { store.get(id).map { Submission.packOf($0.events) } ?? [] }

    /// Whether the recording has the microphone's sound (the voice option "own" needs it).
    func hasMicSound(_ id: String) -> Bool { store.get(id).map { Submission.hasMicSound(events: $0.events, header: $0.header) } ?? false }

    /**
     * Submit (R1, R5): submission.json with the ticked templates and the filled-in inputs goes into
     * the recording's folder; then the recording goes into the upload queue. A recording saved in
     * Photos before gets its videos back from there first.
     */
    func submit(_ id: String, templates: [String], inputs: JSON) {
        guard let d = store.get(id) else { return }
        let fromPhotos = d.status == .SAVED
        if fromPhotos && fetching.contains(id) { return }
        d.updateMeta { $0["decide"] = false }
        toDecide = nil; toDecideText = nil
        Task {
            if fromPhotos {
                fetching.insert(id)
                refresh()
                let m = d.meta()
                let ids = (m["photos"] as? [String]) ?? []
                let hadCamera = ((RecJSON.read(d.events)?["camera"] as? JSON)?["file"] as? String) == "camera.mp4"
                do { try await PhotosSaver.restore(ids: ids, video: d.video, camera: hadCamera ? d.camera : nil) }
                catch {
                    fetching.remove(id)
                    self.error = "Could not get the videos from Photos: \(error.localizedDescription)"
                    refresh()
                    return
                }
            }
            do { try Submission.write(d.dir, Submission.build(templates: templates, inputs: inputs, appVersion: AccountApi.version)) }
            catch {
                if fromPhotos { try? FileManager.default.removeItem(at: d.video); try? FileManager.default.removeItem(at: d.camera); fetching.remove(id) }
                self.error = "Could not save the submission: \(error.localizedDescription)"
                refresh()
                return
            }
            d.updateMeta {
                $0["status"] = RecStatus.QUEUED.rawValue; $0["error"] = ""; $0["templates"] = templates
                if fromPhotos { $0["from_photos"] = true }
            }
            fetching.remove(id)
            refresh()
            lastSummary = d.summary(uploadS3: true)
            uploads.kick()
        }
    }

    /**
     * Save (uploading to S3 is off): the recording's videos into Photos – the screen video
     * rewritten as a plain MP4, the camera video as it is – then the app's copies are gone; the
     * card log stays in the app's list.
     */
    func saveToPhotos(_ id: String) {
        guard let d = store.get(id), !saving.contains(id) else { return }
        d.updateMeta { $0["decide"] = false }
        toDecide = nil; toDecideText = nil
        saving.insert(id)
        refresh()
        Task {
            defer { saving.remove(id); refresh() }
            guard await PhotosSaver.mayAdd() else { error = "Saving into Photos needs your permission (Settings › Privacy › Photos)."; return }
            let taken = (d.meta()["created"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) } ?? Date()
            let videos = RecordingDir.size(d.video) + RecordingDir.size(d.camera)
            do {
                guard RecordingDir.exists(d.video) else { throw PhotosSaver.Failure(text: "this recording has no video") }
                let plain = try await PhotosSaver.plainCopy(d.video)
                var files = [plain]
                if RecordingDir.exists(d.camera) && RecordingDir.size(d.camera) > 0 { files.append(d.camera) }
                let ids = try await PhotosSaver.save(files, taken: taken)
                d.updateMeta { $0["status"] = RecStatus.SAVED.rawValue; $0["error"] = ""; $0["saved_bytes"] = videos; $0["photos"] = ids }
                try? FileManager.default.removeItem(at: d.video)
                try? FileManager.default.removeItem(at: d.camera)
                notice = "Saved in Photos"
                lastSummary = d.summary(uploadS3: false)
            } catch {
                try? FileManager.default.removeItem(at: d.dir.appendingPathComponent("photos-video.mp4"))
                self.error = "Could not save in Photos: \(error.localizedDescription)"
            }
        }
    }

    /// Leaving the submit screen without choosing: the recording stays on the phone, unsubmitted (in the list).
    func later(_ id: String) {
        store.get(id)?.updateMeta { $0["decide"] = false }
        toDecide = nil; toDecideText = nil
        refresh()
    }

    func decideLater() { if let id = toDecide?.id { later(id) } }

    /// Delete: the finished recording is thrown away.
    func discard(_ id: String) {
        store.get(id)?.updateMeta { $0["decide"] = false }
        toDecide = nil; toDecideText = nil; lastSummary = nil
        delete(id)
        notice = "Recording deleted"
    }

    func retry(_ id: String) {
        store.get(id)?.updateMeta { $0["status"] = RecStatus.QUEUED.rawValue; $0["error"] = "" }
        refresh()
        uploads.kick()
    }

    /// Upload now: also over mobile data, this one time.
    func uploadNow(_ id: String) {
        store.get(id)?.updateMeta { $0["status"] = RecStatus.QUEUED.rawValue; $0["error"] = ""; $0["any_network"] = true }
        refresh()
        uploads.kick()
    }

    /// Deletes the recording on the phone; an upload in progress is stopped (no orphaned parts stay in the bucket).
    func delete(_ id: String) {
        guard let d = store.get(id) else { return }
        uploads.cancel(id)
        let cfg = prefs.s3Config()
        Task {
            if let cfg, let client = try? S3Client(cfg: cfg, session: uploads.session) {
                await RecordingUploader(client: client, recordingId: id, state: UploadState(d.uploadState), appVersion: "").abortOpenUploads()
            }
            try? FileManager.default.removeItem(at: d.dir)
            refresh()
            uploads.kick()
        }
    }

    /// The video to share with another app (a recording saved in Photos is shared from there).
    func shareURL(_ id: String) -> URL? { store.get(id).flatMap { RecordingDir.exists($0.video) ? $0.video : nil } }

    /// Test the connection: writes a small file into the bucket and deletes it again.
    func testConnection() {
        guard let cfg = prefs.s3Config() else { connection = "The storage isn't set up yet"; connectionOk = false; return }
        testing = true
        connection = nil
        Task {
            do {
                let c = try S3Client(cfg: cfg, session: uploads.session)
                connection = try await c.connectionTest()
                connectionOk = true
            } catch let e as S3Error {
                connection = e.code == "RequestTimeTooSkewed" ? "Phone clock is off" : e.friendly
                connectionOk = false
            } catch is URLError {
                connection = "Can't reach the storage"
                connectionOk = false
            } catch {
                connection = error.localizedDescription
                connectionOk = false
            }
            testing = false
        }
    }

    /// free space for about how many minutes at these settings (the camera file about as much again)
    func minutesLeft() -> Int64 {
        let perMinute = Int64(settings.bitrateMbps) * 1_000_000 / 8 * 60 * (settings.recordCamera ? 2 : 1)
        return max(0, (store.freeBytes() - ScreenRecorder.lowStorage) / max(1, perMinute))
    }

    var bytesPerHour: Int64 { Int64(settings.bitrateMbps) * 1_000_000 / 8 * 3600 * (settings.recordCamera ? 2 : 1) }
}
