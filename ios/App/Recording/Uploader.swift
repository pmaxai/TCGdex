import CryptoKit
import Foundation

/**
 * What has been uploaded of one recording, saved on the phone after every step (a part, a file),
 * so an upload continues where it stopped: after a restart or a lost connection. S3's own list of
 * parts is asked as well, in case the last step wasn't saved. The same upload.json as on Android.
 */
final class UploadState: @unchecked Sendable {
    private let file: URL
    private var o: JSON
    private let lock = NSLock()

    init(_ file: URL) {
        self.file = file
        o = RecJSON.read(file) ?? [:]
    }

    private var files: JSON {
        get { (o["files"] as? JSON) ?? [:] }
        set { o["files"] = newValue }
    }

    func entry(_ name: String) -> JSON { lock.lock(); defer { lock.unlock() }; return (files[name] as? JSON) ?? [:] }

    func update(_ name: String, _ f: (inout JSON) -> Void) {
        lock.lock(); defer { lock.unlock() }
        var all = files
        var e = (all[name] as? JSON) ?? [:]
        f(&e)
        all[name] = e
        files = all
        save()
    }

    func parts(_ name: String) -> [Int: String] {
        var out: [Int: String] = [:]
        for (k, v) in (entry(name)["parts"] as? JSON) ?? [:] { if let n = Int(k), let t = v as? String { out[n] = t } }
        return out
    }

    func addPart(_ name: String, _ number: Int, _ etag: String) {
        update(name) { e in var p = (e["parts"] as? JSON) ?? [:]; p[String(number)] = etag; e["parts"] = p }
    }

    func setParts(_ name: String, _ parts: [Int: String]) {
        update(name) { e in e["parts"] = Dictionary(uniqueKeysWithValues: parts.map { (String($0.key), $0.value as Any) }) }
    }

    /// multipart uploads that were started and not finished (to abort them when the recording is deleted)
    func openUploads() -> [(String, String)] {
        lock.lock(); defer { lock.unlock() }
        return files.compactMap { (n, v) in
            guard let e = v as? JSON, (e["done"] as? Bool) != true, let id = e["uploadId"] as? String else { return nil }
            return (n, id)
        }
    }

    var complete: Bool { lock.lock(); defer { lock.unlock() }; return (o["complete"] as? Bool) == true }

    func markComplete() { lock.lock(); defer { lock.unlock() }; o["complete"] = true; save() }

    private func save() { try? RecJSON.data(o, pretty: false).write(to: file, options: .atomic) }
}

/**
 * Uploads a recording into `<prefix>recordings/<id>/`: the files in the given order (events.json,
 * pack.csv, submission.json, video.mp4, camera.mp4), then `_complete.json` with every file's size
 * and SHA-256, last: the pipeline's only trigger, so it never sees half a recording. Big files go up
 * as multipart uploads in fixed parts, several at once; every part and file carries Content-MD5.
 * Errors are thrown: the caller decides between waiting and trying again (temporary) and stopping
 * (an S3Error that is permanent).
 */
final class RecordingUploader: @unchecked Sendable {
    private let client: S3Client
    private let recordingId: String
    private let state: UploadState
    private let appVersion: String
    private let partSize: Int
    private let parallel: Int
    private let progress: @Sendable (Int64, Int64) -> Void
    private let lock = NSLock()
    private var done: Int64 = 0

    var folder: String { "\(client.cfg.prefix)recordings/\(recordingId)/" }

    init(client: S3Client, recordingId: String, state: UploadState, appVersion: String, partSize: Int = 16 * 1024 * 1024,
         parallel: Int = 3, progress: @escaping @Sendable (Int64, Int64) -> Void = { _, _ in }) {
        self.client = client
        self.recordingId = recordingId
        self.state = state
        self.appVersion = appVersion
        self.partSize = partSize
        self.parallel = parallel
        self.progress = progress
    }

    private func add(_ n: Int64, _ total: Int64) {
        lock.lock(); done += n; let d = done; lock.unlock()
        progress(d, total)
    }

    func run(_ files: [UploadFile]) async throws {
        let total = files.reduce(Int64(0)) { $0 + RecordingDir.size($1.url) }
        for f in files {
            try Task.checkCancellation()
            let size = RecordingDir.size(f.url)
            // size and checksum for _complete.json, worked out once
            if state.entry(f.name)["sha256"] == nil {
                let sha = try Self.sha256Hex(f.url)
                state.update(f.name) { $0["sha256"] = sha; $0["bytes"] = size }
            }
            if (state.entry(f.name)["done"] as? Bool) == true { add(size, total); continue }
            if size <= Int64(partSize) {
                try await client.putObject(folder + f.name, try Data(contentsOf: f.url), contentType: f.contentType)
                add(size, total)
            } else {
                try await multipart(f, size: size, total: total)
            }
            state.update(f.name) { $0["done"] = true; $0["uploadId"] = nil }
        }
        try Task.checkCancellation()
        var list: JSON = [:]
        for f in files {
            let e = state.entry(f.name)
            list[f.name] = ["bytes": e["bytes"] ?? 0, "sha256": e["sha256"] ?? ""] as JSON
        }
        let marker: JSON = ["recording_id": recordingId, "schema": Recording.schema, "app_version": appVersion,
                            "uploaded_at": RecJSON.iso(Date()), "files": list]
        try await client.putObject(folder + "_complete.json", RecJSON.data(marker), contentType: "application/json")
        state.markComplete()
    }

    private func partLength(_ n: Int, _ size: Int64) -> Int64 { min(Int64(partSize), size - Int64(n - 1) * Int64(partSize)) }

    private func multipart(_ f: UploadFile, size: Int64, total: Int64) async throws {
        let key = folder + f.name
        let count = Int((size + Int64(partSize) - 1) / Int64(partSize))
        var uploadId = (state.entry(f.name)["uploadId"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        if let id = uploadId {
            // what S3 has is what counts (the last part may have finished without being saved here)
            do {
                let have = try await client.listParts(key, uploadId: id)
                state.setParts(f.name, Dictionary(have.filter { $0.size < 0 || $0.size == partLength($0.number, size) }.map { ($0.number, $0.etag) },
                                                  uniquingKeysWith: { a, _ in a }))
            } catch let e as S3Error where e.code == "NoSuchUpload" {
                uploadId = nil
                state.setParts(f.name, [:])
            }
        }
        if uploadId == nil {
            let id = try await client.createMultipartUpload(key, contentType: f.contentType)
            uploadId = id
            state.update(f.name) { $0["uploadId"] = id; $0["parts"] = [:] as JSON }
        }
        let id = uploadId!
        let have = state.parts(f.name)
        add(have.keys.reduce(Int64(0)) { $0 + partLength($1, size) }, total)
        let missing = (1...count).filter { have[$0] == nil }
        // a few parts at once, each read from the file when its turn comes
        try await withThrowingTaskGroup(of: Void.self) { group in
            var next = 0
            func launch() {
                guard next < missing.count else { return }
                let n = missing[next]
                next += 1
                group.addTask { [self] in
                    try Task.checkCancellation()
                    let len = Int(partLength(n, size))
                    let h = try FileHandle(forReadingFrom: f.url)
                    defer { try? h.close() }
                    try h.seek(toOffset: UInt64(n - 1) * UInt64(partSize))
                    let data = try h.read(upToCount: len) ?? Data()
                    guard data.count == len else { throw S3Error(status: 0, code: "ShortRead", message: "the file changed while uploading") }
                    let etag = try await client.uploadPart(key, uploadId: id, number: n, body: data)
                    state.addPart(f.name, n, etag)
                    add(Int64(len), total)
                }
            }
            for _ in 0..<max(1, parallel) { launch() }
            while try await group.next() != nil { launch() }
        }
        let parts = state.parts(f.name).map { S3Part(number: $0.key, etag: $0.value, size: partLength($0.key, size)) }
        guard parts.count == count else { throw S3Error(status: 0, code: "PartsMissing", message: "parts missing: \(count - parts.count)") }
        try await client.completeMultipartUpload(key, uploadId: id, parts: parts)
    }

    /// Deleting a recording during its upload: no orphaned parts stay in the bucket.
    func abortOpenUploads() async {
        for (name, id) in state.openUploads() { try? await client.abortMultipartUpload(folder + name, uploadId: id) }
    }

    static func sha256Hex(_ u: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: u)
        defer { try? h.close() }
        var sha = SHA256()
        while let chunk = try h.read(upToCount: 1 << 20), !chunk.isEmpty { sha.update(data: chunk) }
        return SigV4.hex(sha.finalize())
    }
}
