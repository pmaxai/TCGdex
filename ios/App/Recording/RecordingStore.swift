import AVFoundation
import Foundation

/// Where a recording stands.
enum RecStatus: String {
    case RECORDING, QUEUED, UPLOADING, UPLOADED, FAILED, LOCAL
    /// its videos were moved into Photos; not uploaded
    case SAVED

    var label: String {
        switch self {
        case .RECORDING: return "recording"
        case .QUEUED: return "queued"
        case .UPLOADING: return "uploading"
        case .UPLOADED: return "uploaded"
        case .FAILED: return "failed"
        case .LOCAL: return "on this phone"
        case .SAVED: return "saved in Photos"
        }
    }
}

/// One file of a recording, in upload order. Big ones (the videos) go up in parts.
struct UploadFile { let name: String; let url: URL; let contentType: String }

/// One recording: a folder on the phone with its files and meta.json (status, counts, sizes) – as on Android.
final class RecordingDir: @unchecked Sendable {
    let dir: URL
    private let lock = NSLock()

    init(_ dir: URL) { self.dir = dir }

    var id: String { dir.lastPathComponent }
    var video: URL { dir.appendingPathComponent("video.mp4") }
    var camera: URL { dir.appendingPathComponent("camera.mp4") }
    var eventsJsonl: URL { dir.appendingPathComponent("events.jsonl") }
    var events: URL { dir.appendingPathComponent("events.json") }
    var packCsv: URL { dir.appendingPathComponent("pack.csv") }
    var header: URL { dir.appendingPathComponent("header.json") }
    /// which video templates to make, and the user's inputs (written on Submit)
    var submission: URL { dir.appendingPathComponent(Submission.file) }
    var uploadState: URL { dir.appendingPathComponent("upload.json") }
    private var metaFile: URL { dir.appendingPathComponent("meta.json") }

    func meta() -> JSON { lock.lock(); defer { lock.unlock() }; return RecJSON.read(metaFile) ?? [:] }

    func updateMeta(_ f: (inout JSON) -> Void) {
        lock.lock(); defer { lock.unlock() }
        var m = RecJSON.read(metaFile) ?? [:]
        f(&m)
        try? RecJSON.data(m, pretty: false).write(to: metaFile, options: .atomic)
    }

    var status: RecStatus { (meta()["status"] as? String).flatMap(RecStatus.init) ?? .LOCAL }

    static func size(_ u: URL) -> Int64 { ((try? FileManager.default.attributesOfItem(atPath: u.path))?[.size] as? NSNumber)?.int64Value ?? 0 }
    static func exists(_ u: URL) -> Bool { FileManager.default.fileExists(atPath: u.path) }

    var bytes: Int64 { [video, camera, events, packCsv].reduce(0) { $0 + Self.size($1) } }

    /// the size to show: for a recording saved in Photos the size of its videos (they left the app)
    var shownBytes: Int64 { status == .SAVED ? ((meta()["saved_bytes"] as? NSNumber)?.int64Value ?? bytes) : bytes }

    /**
     * The files to upload, in order: events.json, pack.csv, submission.json (if submitted with
     * templates), video.mp4, camera.mp4 (if recorded). The uploader puts `_complete.json` last.
     */
    func uploadFiles() -> [UploadFile] {
        var out = [UploadFile(name: "events.json", url: events, contentType: "application/json"),
                   UploadFile(name: "pack.csv", url: packCsv, contentType: "text/csv; charset=utf-8")]
        if Self.exists(submission) { out.append(UploadFile(name: Submission.file, url: submission, contentType: "application/json")) }
        out.append(UploadFile(name: "video.mp4", url: video, contentType: "video/mp4"))
        if Self.exists(camera) && Self.size(camera) > 0 { out.append(UploadFile(name: "camera.mp4", url: camera, contentType: "video/mp4")) }
        return out
    }

    static func sizeText(_ b: Int64) -> String {
        b >= 1_000_000_000 ? String(format: "%.1f GB", Double(b) / 1e9) : "\(b / 1_000_000) MB"
    }

    static func durationText(_ ms: Int64) -> String {
        let d = ms / 1000
        return d >= 3600 ? String(format: "%d:%02d:%02d", d / 3600, d / 60 % 60, d % 60) : String(format: "%d:%02d", d / 60, d % 60)
    }

    /// "Recording 30:34 · 38 cards · 3 hits · 1.8 GB · queued"
    func summary(uploadS3: Bool = true) -> String {
        let m = meta()
        return "Recording \(Self.durationText(Self.int64(m["duration_ms"]))) · \(Self.int(m["cards"])) cards · \(Self.int(m["hits"])) hits · "
            + "\(Self.sizeText(shownBytes)) · \(statusText(uploadS3: uploadS3))"
    }

    /// uploadS3: recordings go to S3 (one not sent yet is "not submitted"; else "not saved")
    func statusText(uploadS3: Bool = true) -> String {
        let m = meta()
        switch status {
        case .UPLOADING: return "uploading \(Self.int(m["progress"])) %"
        case .FAILED: return "failed: \((m["error"] as? String) ?? "")"
        case .QUEUED: return (m["waiting_wifi"] as? Bool) == true ? "waiting for Wi-Fi" : "queued"
        case .LOCAL: return uploadS3 ? "not submitted yet" : "not saved yet"
        case .SAVED: return "saved in Photos"
        case .UPLOADED: return (m["from_photos"] as? Bool) == true ? "uploaded · the videos stay in Photos" : status.label
        case .RECORDING: return status.label
        }
    }

    static func int(_ v: Any?) -> Int { (v as? NSNumber)?.intValue ?? 0 }
    static func int64(_ v: Any?) -> Int64 { (v as? NSNumber)?.int64Value ?? 0 }
}

/// The recordings on this phone: Application Support/recordings/<id>/ (left out of iCloud backups).
final class RecordingStore: @unchecked Sendable {
    let root: URL

    init(root: URL? = nil) {
        let r = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("recordings")
        try? FileManager.default.createDirectory(at: r, withIntermediateDirectories: true)
        var u = r
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? u.setResourceValues(values)
        self.root = r
    }

    func list() -> [RecordingDir] {
        let items = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
        return items.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }.map(RecordingDir.init)
    }

    func get(_ id: String) -> RecordingDir? {
        let u = root.appendingPathComponent(id)
        var dir: ObjCBool = false
        return FileManager.default.fileExists(atPath: u.path, isDirectory: &dir) && dir.boolValue ? RecordingDir(u) : nil
    }

    /// A new folder: id = UTC start time + 4 random hex characters ("20261109T140322Z-7f3a").
    func create(now: Date = Date()) -> RecordingDir {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        let id = f.string(from: now) + "-" + String(format: "%04x", Int.random(in: 0..<0x10000))
        let u = root.appendingPathComponent(id)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return RecordingDir(u)
    }

    func freeBytes() -> Int64 {
        let v = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage ?? Int64.max
    }

    /**
     * events.json from what a recording left behind after a crash: the header written at Start,
     * the events written until then; the pack rebuilt from the events.
     */
    static func recoverEvents(header: JSON, jsonl: URL, id: String, durationMs: Int64) -> (String, [JSON]) {
        var events: [JSON] = []
        var pack: [(Int, JSON)] = []
        func put(_ entry: Int, _ c: JSON) { if let i = pack.firstIndex(where: { $0.0 == entry }) { pack[i].1 = c } else { pack.append((entry, c)) } }
        let lines = ((try? String(contentsOf: jsonl, encoding: .utf8)) ?? "").split(separator: "\n")
        for l in lines {
            guard let e = (try? JSONSerialization.jsonObject(with: Data(l.utf8))) as? JSON else { continue }
            events.append(e)
            switch e["type"] as? String {
            case "recording_started":
                for c in (e["pack_at_start"] as? [JSON]) ?? [] {
                    guard let n = c["entry"] as? Int else { continue }
                    var c2 = c; c2["first_t_ms"] = NSNull()
                    put(n, c2)
                }
            case "card_shown", "card_added_again":
                if let n = e["entry"] as? Int, (e["in_pack"] as? Bool) ?? true {
                    put(n, ["entry": n, "first_t_ms": e["t_ms"] ?? NSNull(), "before_recording": false, "hit": (e["hit"] as? Bool) ?? false,
                            "card": e["card"] ?? [:], "price": e["price"] ?? [:]])
                }
            case "card_updated":
                if let n = e["entry"] as? Int, let i = pack.firstIndex(where: { $0.0 == n }) {
                    pack[i].1["hit"] = (e["hit"] as? Bool) ?? false
                    pack[i].1["card"] = e["card"] ?? [:]
                    pack[i].1["price"] = e["price"] ?? [:]
                }
            case "card_removed":
                if let n = e["entry"] as? Int { pack.removeAll { $0.0 == n } }
            case "pack_reset":
                pack.removeAll()
            default: break
            }
        }
        let lastT = events.map { RecordingDir.int64($0["t_ms"]) }.max() ?? 0
        events.append(["seq": events.count + 1, "t_ms": max(lastT, durationMs), "at": NSNull(), "type": "recording_stopped", "reason": "recovered"])
        let cards = pack.map(\.1)
        var out: JSON = ["schema": Recording.schema, "recording_id": id, "status": "recovered"]
        for (k, v) in header { out[k] = v }
        if var video = out["video"] as? JSON { video["duration_ms"] = durationMs; out["video"] = video }
        func value(_ c: JSON, _ currency: String) -> Double {
            guard let p = c["price"] as? JSON, (p["currency"] as? String) == currency else { return 0 }
            return (p["value"] as? NSNumber)?.doubleValue ?? 0
        }
        out["events"] = events
        out["cards"] = cards
        out["summary"] = ["cards": cards.count, "hits": cards.filter { ($0["hit"] as? Bool) == true }.count,
                          "pack_value_eur": RecJSON.round2(cards.reduce(0) { $0 + value($1, "EUR") }),
                          "pack_value_usd": RecJSON.round2(cards.reduce(0) { $0 + value($1, "USD") })] as JSON
        return (RecJSON.text(out), cards)
    }

    /// A simple pack CSV from the log's cards (after a crash the app's own pack is gone).
    static func csvOf(_ cards: [JSON]) -> String {
        func cell(_ v: Any?) -> String {
            let t: String
            switch v {
            case nil, is NSNull: t = ""
            case let s as String: t = s
            case let n as NSNumber: t = n.stringValue
            default: t = "\(v!)"
            }
            return t.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" }) ? "\"" + t.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : t
        }
        var out = "\u{FEFF}No,Name,English name,Set,Number,Language,Card ID,Printing,Value,Currency,Hit\r\n"
        for (i, c) in cards.enumerated() {
            let card = (c["card"] as? JSON) ?? [:], p = (c["price"] as? JSON) ?? [:]
            let row: [Any?] = [i + 1, card["name"], card["name_en"], card["set"], card["number_text"], card["lang"], card["id"], card["printing"],
                               p["value"], p["currency"], (c["hit"] as? Bool) == true ? "yes" : ""]
            out += row.map(cell).joined(separator: ",") + "\r\n"
        }
        return out
    }

    /// The video's length up to its last whole fragment (a recording the app didn't finish), in ms.
    static func playableMs(_ u: URL) async -> Int64 {
        let asset = AVURLAsset(url: u)
        guard let d = try? await asset.load(.duration), d.isNumeric else { return 0 }
        return Int64(CMTimeGetSeconds(d) * 1000)
    }
}
