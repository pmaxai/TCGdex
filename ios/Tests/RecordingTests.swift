import AVFoundation
import CryptoKit
import ReplayKit
import XCTest
@testable import OverpricedCardboard

/// An S3 in memory behind URLProtocol: objects, multipart uploads, and failures to hand out first (as the Android tests' FakeS3).
final class FakeS3: URLProtocol {
    static let lock = NSLock()
    nonisolated(unsafe) static var objects: [String: Data] = [:]
    nonisolated(unsafe) static var uploads: [String: [Int: Data]] = [:]
    nonisolated(unsafe) static var log: [String] = []
    /// "PUT video.mp4 part 3" -> answers (status, code) to give before the normal one
    nonisolated(unsafe) static var failures: [String: [(Int, String)]] = [:]
    nonisolated(unsafe) static var nextId = 0

    static func reset() {
        lock.lock(); defer { lock.unlock() }
        objects = [:]; uploads = [:]; log = []; failures = [:]; nextId = 0
    }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeS3.self]
        return URLSession(configuration: c)
    }

    static var config: S3Config {
        S3Config(endpoint: "https://s3.test", region: "us-east-1", bucket: "bucket", prefix: "acc/", pathStyle: true, accessKeyId: "key", secret: "secret")
    }

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "s3.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    private static func md5(_ d: Data) -> Data { Data(Insecure.MD5.hash(data: d)) }

    override func startLoading() {
        let r = request
        var body = Data()
        if let s = r.httpBodyStream {
            s.open()
            var buf = [UInt8](repeating: 0, count: 65536)
            while s.hasBytesAvailable { let n = s.read(&buf, maxLength: buf.count); if n <= 0 { break }; body.append(buf, count: n) }
            s.close()
        } else if let d = r.httpBody { body = d }
        let url = r.url!
        let q = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        let key = String(url.path.dropFirst("/bucket/".count))
        let method = r.httpMethod ?? "GET"
        let part = q["partNumber"]
        var label = "\(method) \(key.split(separator: "/").last.map(String.init) ?? key)"
        if let part { label += " part \(part)" }
        if q["uploads"] != nil { label += " create" }
        if method == "POST" && q["uploadId"] != nil { label += " complete" }
        if method == "GET" && q["uploadId"] != nil { label += " list" }
        if method == "DELETE" && q["uploadId"] != nil { label += " abort" }
        let (status, headers, answer) = Self.answer(label, method, key, q, part, body, r)
        let res = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: res, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(answer.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func error(_ status: Int, _ code: String) -> (Int, [String: String], String) {
        (status, ["Date": "Sun, 22 Nov 2026 10:00:00 GMT"], "<Error><Code>\(code)</Code><Message>\(code)</Message></Error>")
    }

    private static func answer(_ label: String, _ method: String, _ key: String, _ q: [String: String], _ part: String?, _ body: Data,
                               _ r: URLRequest) -> (Int, [String: String], String) {
        lock.lock(); defer { lock.unlock() }
        log.append(label)
        if var f = failures[label], !f.isEmpty {
            let (s, c) = f.removeFirst()
            failures[label] = f
            return error(s, c)
        }
        guard r.value(forHTTPHeaderField: "Authorization")?.hasPrefix("AWS4-HMAC-SHA256 Credential=key/") == true else { return error(403, "AccessDenied") }
        guard r.value(forHTTPHeaderField: "x-amz-content-sha256") == SigV4.sha256Hex(body) else { return error(400, "XAmzContentSHA256Mismatch") }
        if !body.isEmpty && r.value(forHTTPHeaderField: "Content-MD5") != md5(body).base64EncodedString() { return error(400, "BadDigest") }
        let uploadId = q["uploadId"]
        switch method {
        case "POST" where q["uploads"] != nil:
            nextId += 1
            let id = "up\(nextId)"
            uploads[id] = [:]
            return (200, [:], "<InitiateMultipartUploadResult><UploadId>\(id)</UploadId></InitiateMultipartUploadResult>")
        case "PUT" where part != nil:
            guard let id = uploadId, uploads[id] != nil, let n = Int(part!) else { return error(404, "NoSuchUpload") }
            uploads[id]![n] = body
            return (200, ["ETag": "\"\(SigV4.hex(md5(body)))\""], "")
        case "GET" where uploadId != nil:
            guard let parts = uploads[uploadId!] else { return error(404, "NoSuchUpload") }
            let xml = parts.keys.sorted().map { n in
                "<Part><PartNumber>\(n)</PartNumber><ETag>&quot;\(SigV4.hex(md5(parts[n]!)))&quot;</ETag><Size>\(parts[n]!.count)</Size></Part>"
            }.joined()
            return (200, [:], "<ListPartsResult><IsTruncated>false</IsTruncated>\(xml)</ListPartsResult>")
        case "POST" where uploadId != nil:
            guard let parts = uploads.removeValue(forKey: uploadId!) else { return error(404, "NoSuchUpload") }
            let text = String(decoding: body, as: UTF8.self)
            let numbers = S3Client.all(text, "PartNumber").compactMap { Int($0) }
            guard numbers == Array(1...max(1, parts.count)) else { return error(400, "InvalidPart") }
            objects[key] = numbers.reduce(into: Data()) { $0.append(parts[$1]!) }
            return (200, [:], "<CompleteMultipartUploadResult><Key>\(key)</Key></CompleteMultipartUploadResult>")
        case "DELETE" where uploadId != nil:
            uploads[uploadId!] = nil
            return (204, [:], "")
        case "PUT":
            objects[key] = body
            return (200, ["ETag": "\"\(SigV4.hex(md5(body)))\""], "")
        case "GET":
            guard let o = objects[key] else { return error(404, "NoSuchKey") }
            return (200, [:], String(decoding: o, as: UTF8.self))
        case "DELETE":
            objects[key] = nil
            return (204, [:], "")
        default:
            return error(400, "Unsupported")
        }
    }
}

/// Recording (YouTube Mode): the S3 signature (AWS's examples), the uploader, the event log, the templates and inputs, recovery.
final class RecordingTests: XCTestCase {
    private let s3Key = "AKIAIOSFODNN7EXAMPLE"
    private let s3Secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

    // ------------------------------------------------------------------ signature: AWS's examples
    func testSignatureMatchesAwsTestSuiteGetVanilla() {
        let s = SigV4.sign(method: "GET", canonicalUri: "/", query: [], headers: ["Host": "example.amazonaws.com", "X-Amz-Date": "20150830T123600Z"],
                           payloadHash: SigV4.emptySha256, amzDate: "20150830T123600Z", region: "us-east-1", service: "service",
                           accessKey: "AKIDEXAMPLE", secret: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
        XCTAssertEqual(s.authorization, "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                       + "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31")
    }

    func testSignatureMatchesS3GetObjectExample() {
        let s = SigV4.sign(method: "GET", canonicalUri: "/test.txt", query: [],
                           headers: ["host": "examplebucket.s3.amazonaws.com", "range": "bytes=0-9", "x-amz-content-sha256": SigV4.emptySha256,
                                     "x-amz-date": "20130524T000000Z"],
                           payloadHash: SigV4.emptySha256, amzDate: "20130524T000000Z", region: "us-east-1", service: "s3", accessKey: s3Key, secret: s3Secret)
        XCTAssertTrue(s.authorization.hasSuffix("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), s.authorization)
    }

    func testSignatureMatchesS3PutObjectExample() {
        let body = Data("Welcome to Amazon S3.".utf8)
        let hash = SigV4.sha256Hex(body)
        XCTAssertEqual(hash, "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072")
        let s = SigV4.sign(method: "PUT", canonicalUri: SigV4.uriEncode("/test$file.text", keepSlash: true), query: [],
                           headers: ["date": "Fri, 24 May 2013 00:00:00 GMT", "host": "examplebucket.s3.amazonaws.com", "x-amz-content-sha256": hash,
                                     "x-amz-date": "20130524T000000Z", "x-amz-storage-class": "REDUCED_REDUNDANCY"],
                           payloadHash: hash, amzDate: "20130524T000000Z", region: "us-east-1", service: "s3", accessKey: s3Key, secret: s3Secret)
        XCTAssertTrue(s.canonicalRequest.hasPrefix("PUT\n/test%24file.text\n\n"), s.canonicalRequest)
        XCTAssertTrue(s.authorization.hasSuffix("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"), s.authorization)
    }

    func testSignatureMatchesS3ListObjectsExample() {
        let s = SigV4.sign(method: "GET", canonicalUri: "/", query: [("prefix", "J"), ("max-keys", "2")],
                           headers: ["host": "examplebucket.s3.amazonaws.com", "x-amz-content-sha256": SigV4.emptySha256, "x-amz-date": "20130524T000000Z"],
                           payloadHash: SigV4.emptySha256, amzDate: "20130524T000000Z", region: "us-east-1", service: "s3", accessKey: s3Key, secret: s3Secret)
        XCTAssertTrue(s.canonicalRequest.contains("\nmax-keys=2&prefix=J\n"))
        XCTAssertTrue(s.authorization.hasSuffix("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"), s.authorization)
    }

    func testPrefixesAndEncoding() throws {
        XCTAssertEqual(S3Config.normalizePrefix("tcgdex"), "tcgdex/")
        XCTAssertEqual(S3Config.normalizePrefix("/a/b/"), "a/b/")
        XCTAssertEqual(S3Config.normalizePrefix("  "), "")
        XCTAssertEqual(SigV4.uriEncode("recordings/20261109T140322Z-7f3a/video.mp4", keepSlash: true), "recordings/20261109T140322Z-7f3a/video.mp4")
        XCTAssertEqual(SigV4.uriEncode("a b/c", keepSlash: false), "a%20b%2Fc")
        // virtual-hosted: the bucket in the host; path-style: in the path
        var cfg = FakeS3.config
        cfg.pathStyle = false
        cfg.endpoint = "https://sos-at-vie-1.exo.io"
        let (u, path) = try S3Client(cfg: cfg).url("acc/recordings/x/video.mp4", [("partNumber", "2"), ("uploadId", "a b")])
        XCTAssertEqual(u.absoluteString, "https://bucket.sos-at-vie-1.exo.io/acc/recordings/x/video.mp4?partNumber=2&uploadId=a%20b")
        XCTAssertEqual(path, "/acc/recordings/x/video.mp4")
        cfg.secret = "do-not-print-me"
        XCTAssertFalse(String(describing: cfg).contains("do-not-print-me"), "the secret is never printed")
        XCTAssertThrowsError(try S3Client(cfg: S3Config(endpoint: "http://insecure.test", region: "r", bucket: "b", prefix: "", pathStyle: true,
                                                         accessKeyId: "k", secret: "s")))
    }

    // ------------------------------------------------------------------ the uploader against the fake S3
    private func recordingDir(videoBytes: Int) throws -> RecordingDir {
        let store = RecordingStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("rec-\(UUID().uuidString)"))
        let d = store.create()
        try Data("{\"schema\":\"tcgdex.recording.v1\"}".utf8).write(to: d.events)
        try Data("No,Name\r\n".utf8).write(to: d.packCsv)
        var video = Data(count: videoBytes)
        for i in 0..<videoBytes { video[i] = UInt8(truncatingIfNeeded: i &* 31 &+ 7) }
        try video.write(to: d.video)
        return d
    }

    func testUploadPutsTheFilesThenCompleteLast() async throws {
        FakeS3.reset()
        let d = try recordingDir(videoBytes: 3 * 1024 * 1024 + 500)
        let client = try S3Client(cfg: FakeS3.config, session: FakeS3.session(), sleep: { _ in })
        let up = RecordingUploader(client: client, recordingId: d.id, state: UploadState(d.uploadState), appVersion: "1.0", partSize: 1024 * 1024, parallel: 2)
        try await up.run(d.uploadFiles())
        let folder = "acc/recordings/\(d.id)/"
        FakeS3.lock.lock()
        let objects = FakeS3.objects, log = FakeS3.log
        FakeS3.lock.unlock()
        XCTAssertEqual(objects[folder + "video.mp4"], try Data(contentsOf: d.video), "the parts make the video again")
        XCTAssertNotNil(objects[folder + "events.json"])
        XCTAssertNotNil(objects[folder + "pack.csv"])
        XCTAssertEqual(log.last, "PUT _complete.json", "the pipeline's trigger comes last")
        XCTAssertEqual(log.filter { $0.hasPrefix("PUT video.mp4 part") }.count, 4)
        let marker = try XCTUnwrap(objects[folder + "_complete.json"].flatMap { try JSONSerialization.jsonObject(with: $0) as? JSON })
        XCTAssertEqual(marker["schema"] as? String, Recording.schema)
        let files = try XCTUnwrap(marker["files"] as? JSON)
        let v = try XCTUnwrap(files["video.mp4"] as? JSON)
        XCTAssertEqual(v["sha256"] as? String, try RecordingUploader.sha256Hex(d.video))
        XCTAssertEqual((v["bytes"] as? NSNumber)?.int64Value, RecordingDir.size(d.video))
        XCTAssertTrue(UploadState(d.uploadState).complete)
    }

    func testUploadGoesOnWhereItStopped() async throws {
        FakeS3.reset()
        let d = try recordingDir(videoBytes: 3 * 1024 * 1024)
        // part 2 fails for good (403 three times would be permanent; here the connection to it is lost: 500 × 3)
        FakeS3.failures["PUT video.mp4 part 2"] = [(500, "InternalError"), (500, "InternalError"), (500, "InternalError")]
        let client = try S3Client(cfg: FakeS3.config, session: FakeS3.session(), sleep: { _ in })
        let first = RecordingUploader(client: client, recordingId: d.id, state: UploadState(d.uploadState), appVersion: "1.0", partSize: 1024 * 1024, parallel: 1)
        do { try await first.run(d.uploadFiles()); XCTFail("part 2 failed") } catch let e as S3Error { XCTAssertFalse(e.permanent) }
        let state = UploadState(d.uploadState)
        XCTAssertTrue(state.entry("events.json")["done"] as? Bool == true)
        XCTAssertEqual(Set(state.parts("video.mp4").keys), [1], "part 1 is kept")
        // again: S3's list of parts counts, only what's missing goes up
        let again = RecordingUploader(client: client, recordingId: d.id, state: state, appVersion: "1.0", partSize: 1024 * 1024, parallel: 1)
        try await again.run(d.uploadFiles())
        FakeS3.lock.lock()
        let log = FakeS3.log, objects = FakeS3.objects
        FakeS3.lock.unlock()
        XCTAssertEqual(log.filter { $0 == "PUT video.mp4 part 1" }.count, 1, "part 1 isn't sent twice")
        XCTAssertTrue(log.contains("GET video.mp4 list"))
        XCTAssertEqual(log.filter { $0 == "PUT events.json" }.count, 1)
        XCTAssertEqual(objects["acc/recordings/\(d.id)/video.mp4"], try Data(contentsOf: d.video))
    }

    func testWrongKeyIsPermanentAndAGoneUploadStartsAgain() async throws {
        FakeS3.reset()
        let d = try recordingDir(videoBytes: 2 * 1024 * 1024 + 1)
        var cfg = FakeS3.config
        cfg.accessKeyId = "other"
        let bad = try S3Client(cfg: cfg, session: FakeS3.session(), sleep: { _ in })
        do { try await RecordingUploader(client: bad, recordingId: d.id, state: UploadState(d.uploadState), appVersion: "1").run(d.uploadFiles()); XCTFail() }
        catch let e as S3Error {
            XCTAssertTrue(e.permanent)
            XCTAssertEqual(e.friendly, "This key may not write to the bucket/prefix")
        }
        // an upload id S3 no longer knows: a new multipart upload
        let state = UploadState(d.uploadState)
        state.update("video.mp4") { $0["uploadId"] = "gone"; $0["parts"] = ["1": "\"x\""] as JSON }
        let good = try S3Client(cfg: FakeS3.config, session: FakeS3.session(), sleep: { _ in })
        try await RecordingUploader(client: good, recordingId: d.id, state: state, appVersion: "1", partSize: 1024 * 1024).run(d.uploadFiles())
        FakeS3.lock.lock()
        let video = FakeS3.objects["acc/recordings/\(d.id)/video.mp4"]
        FakeS3.lock.unlock()
        XCTAssertEqual(video, try Data(contentsOf: d.video))
        // deleting during an upload: open multipart uploads are aborted
        let open = UploadState(d.uploadState.deletingLastPathComponent().appendingPathComponent("other.json"))
        open.update("camera.mp4") { $0["uploadId"] = "up99" }
        await RecordingUploader(client: good, recordingId: d.id, state: open, appVersion: "1").abortOpenUploads()
        FakeS3.lock.lock()
        XCTAssertTrue(FakeS3.log.contains("DELETE camera.mp4 abort"))
        FakeS3.lock.unlock()
    }

    func testConnectionTestAndTemplatesFromTheBucket() async throws {
        FakeS3.reset()
        let client = try S3Client(cfg: FakeS3.config, session: FakeS3.session(), sleep: { _ in })
        let ok = try await client.connectionTest()
        XCTAssertTrue(ok.hasPrefix("Connected · bucket · write OK"), ok)
        let cache = FileManager.default.temporaryDirectory.appendingPathComponent("tpl-\(UUID().uuidString)/index.json")
        // no list in the bucket, nothing saved: the built-in default
        var l = await Templates.load(fetch: { try await client.getObject(Templates.key(FakeS3.config)) }, cache: cache)
        XCTAssertEqual(l.source, .builtIn)
        XCTAssertEqual(l.error, "no template list in the bucket")
        XCTAssertEqual(l.index.templates.map(\.id), ["longform-smooth"])
        FakeS3.lock.lock()
        FakeS3.objects["acc/templates/index.json"] = Data(Self.templatesJson.utf8)
        FakeS3.lock.unlock()
        l = await Templates.load(fetch: { try await client.getObject(Templates.key(FakeS3.config)) }, cache: cache)
        XCTAssertEqual(l.source, .bucket)
        XCTAssertEqual(l.index.templates.count, 2)
        // offline: the saved copy
        l = await Templates.load(fetch: { throw URLError(.notConnectedToInternet) }, cache: cache)
        XCTAssertEqual(l.source, .saved)
        XCTAssertEqual(l.error, "offline")
        XCTAssertEqual(l.index.templates.count, 2)
    }

    static let templatesJson = """
    {"schema":"pokepov.templates.v1",
     "inputs":{"title":{"type":"text","label":"Video title","max":60},
               "length":{"type":"number","label":"Length (minutes)","min":2,"max":30,"default":10},
               "music":{"type":"toggle","label":"Music","default":true},
               "voice":{"type":"choice","label":"Voice","options":["none","ai","own"],"default":"ai"},
               "always":{"type":"cards","label":"Always show"},
               "never":{"type":"cards","label":"Never show"},
               "future":{"type":"colour","label":"Something new"}},
     "templates":[{"id":"longform-smooth","name":"Long Form · Smooth","description":"The whole pack, calm cuts","default":true,"inputs":["title","length","music","voice"]},
                  {"id":"shorts-hits","name":"Shorts · Hits","description":"The hits in 60 s","inputs":["title","always","never","future"]}],
     "extra":"ignored"}
    """

    func testTemplatesAndTheInputsForm() throws {
        let t = try TemplatesIndex.parse(Data(Self.templatesJson.utf8))
        XCTAssertEqual(t.templates.map(\.id), ["longform-smooth", "shorts-hits"])
        XCTAssertEqual(t.templates.filter(\.isDefault).map(\.id), ["longform-smooth"])
        XCTAssertEqual(t.fields(for: ["longform-smooth", "shorts-hits"]).map(\.id), ["title", "length", "music", "voice", "always", "never"],
                       "each input once, unknown types left out")
        XCTAssertThrowsError(try TemplatesIndex.parse(Data("{\"schema\":\"other.v1\",\"templates\":[{\"id\":\"a\"}]}".utf8)))
        XCTAssertThrowsError(try TemplatesIndex.parse(Data("{\"schema\":\"pokepov.templates.v1\",\"templates\":[]}".utf8)))
        let f = t.fields(for: ["longform-smooth", "shorts-hits"])
        let length = f.first { $0.id == "length" }!
        XCTAssertEqual(SubmissionForm.problem(length, .text("40")), "Between 2 and 30")
        XCTAssertEqual(SubmissionForm.problem(length, .text("abc")), "Not a number")
        XCTAssertNil(SubmissionForm.problem(length, .text("12,5")))
        XCTAssertEqual(SubmissionForm.initial(f.first { $0.id == "music" }!), .bool(true))
        XCTAssertEqual(SubmissionForm.initial(f.first { $0.id == "voice" }!), .text("ai"))
        var raw: [String: FieldValue] = ["title": .text("  My pack opening  "), "length": .text("12"), "music": .bool(true), "voice": .text("own"),
                                         "always": .cards([3])]
        // a card ticked in Never leaves Always
        raw = SubmissionForm.tickCard(f, raw, fieldId: "never", entry: 3, on: true)
        XCTAssertEqual(raw["always"], .cards([]))
        XCTAssertEqual(raw["never"], .cards([3]))
        let inputs = SubmissionForm.inputs(f, raw)
        XCTAssertEqual(inputs["title"] as? String, "My pack opening")
        XCTAssertEqual((inputs["length"] as? NSNumber)?.int64Value, 12)
        XCTAssertNil(inputs["music"], "unchanged from its default: left out")
        XCTAssertEqual(inputs["voice"] as? String, "own")
        XCTAssertNil(inputs["always"])
        XCTAssertEqual(inputs["never"] as? [Int], [3])
        let sub = Submission.build(templates: ["longform-smooth"], inputs: inputs, appVersion: "1.0", now: Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertEqual(sub["schema"] as? String, "tcgdex.submission.v1")
        XCTAssertEqual(sub["submitted_at"] as? String, "2027-01-15T08:00:00Z")
        XCTAssertEqual((sub["templates"] as? [JSON])?.first?["id"] as? String, "longform-smooth")
    }

    // ------------------------------------------------------------------ the event log
    private func card(_ id: String, _ name: String, _ eur: Double, holo: Double? = nil) -> CardInfo {
        var printings = [Printing(kind: .normal, eur: eur, eur30: eur * 0.9, eurLow: eur * 0.5, usd: eur * 1.1)]
        if let holo { printings.append(Printing(kind: .holo, eur: holo, eur30: nil, eurLow: nil, usd: nil)) }
        return CardInfo(id: id, lang: "en", name: name, number: "1", setName: "Test Set", setTotal: 100, series: "Test", rarity: "Rare",
                        imageUrl: nil, prices: Prices(updated: "2026-11-20T00:00:00Z"), printings: printings)
    }

    private func shown(_ c: CardInfo, _ serial: Int, printing: Printing.Kind = .normal) -> Shown {
        Shown(group: c.id, version: c, english: nil, priceCard: c, printing: printing, confidence: .sure, alternatives: [], languages: [],
              text: nil, locked: false, fromPhoto: false, serial: serial)
    }

    private func item(_ s: Shown) -> SessionItem { SessionItem(serial: s.serial, time: Date(), shown: s) }

    func testTheLogFollowsThePackAndTheShownCard() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("log-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var clock: Int64 = 1_000_000_000_000
        let log = EventLog(recordingId: "rec1", jsonl: dir.appendingPathComponent("events.jsonl"), threshold: 5, nanos: { clock },
                           wall: { Date(timeIntervalSince1970: 1_800_000_000) })
        let pika = card("t-1", "Pikachu", 2), zard = card("t-4", "Charizard", 300, holo: 900)
        let before = shown(card("t-9", "Bulbasaur", 1), 1)
        log.start(firstFrameNanos: clock, packAtStart: [item(before)], shown: before)
        clock += 1_500_000_000
        // a new card from the camera: into the pack
        let a = shown(pika, 2)
        log.observe(.shown, source: "camera", shown: a, session: [item(before), item(a)], quad: [CGPoint(x: 0.1, y: 0.2), CGPoint(x: 0.9, y: 0.2),
                                                                                              CGPoint(x: 0.9, y: 0.8), CGPoint(x: 0.1, y: 0.8)])
        clock += 500_000_000
        // its printing changed: updated
        var b = shown(zard, 3)
        log.observe(.shown, source: "camera", shown: b, session: [item(before), item(a), item(b)], quad: nil)
        b.printing = .holo
        b.printingByUser = true
        log.observe(.printing, source: nil, shown: b, session: [item(before), item(a), item(b)], quad: nil)
        // +1, then swiped away, then remove last
        let c = shown(zard, 4, printing: .holo)
        log.observe(.addAgain, source: nil, shown: c, session: [item(before), item(a), item(b), item(c)], quad: nil)
        log.observe(.swipe, source: nil, shown: nil, session: [item(before), item(a), item(b)], quad: nil)
        log.observe(.removeLast, source: nil, shown: nil, session: [item(before), item(a)], quad: nil)
        // looked up: shown, not in the pack; Next card: cleared
        let d = shown(card("t-7", "Squirtle", 3), 5)
        log.observe(.shown, source: "search", shown: d, session: [item(before), item(a)], quad: nil)
        log.observe(.next, source: nil, shown: nil, session: [item(before), item(a)], quad: nil)
        log.observe(.reset, source: nil, shown: nil, session: [], quad: nil)
        clock += 1_000_000_000
        log.stop(reason: "user")
        let text = log.finish(header: ["video": ["file": "video.mp4"] as JSON], finalPack: [], status: "complete")
        let o = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? JSON)
        XCTAssertEqual(o["schema"] as? String, "tcgdex.recording.v1")
        let events = try XCTUnwrap(o["events"] as? [JSON])
        XCTAssertEqual(events.map { $0["type"] as? String ?? "" }, ["recording_started", "card_shown", "card_shown", "card_updated", "card_added_again",
                                                                    "card_removed", "card_removed", "card_shown", "card_cleared", "pack_reset", "recording_stopped"])
        XCTAssertEqual(events.map { ($0["seq"] as? Int) ?? 0 }, Array(1...11))
        XCTAssertEqual((events[0]["pack_at_start"] as? [JSON])?.first?["entry"] as? Int, 1)
        XCTAssertEqual(events[1]["entry"] as? Int, 2)
        XCTAssertEqual(events[1]["t_ms"] as? Int, 1500)
        XCTAssertEqual(events[1]["hit"] as? Bool, false)
        XCTAssertEqual((events[1]["card_quad"] as? [[Double]])?.first, [0.1, 0.2])
        XCTAssertEqual(events[2]["hit"] as? Bool, true, "Charizard at 300 € is a hit at 5 €")
        XCTAssertEqual(events[3]["reason"] as? String, "printing")
        XCTAssertEqual((events[3]["card"] as? JSON)?["printing"] as? String, "holo")
        XCTAssertEqual((events[3]["price"] as? JSON)?["value"] as? Double, 900)
        XCTAssertEqual(events[4]["copy_of"] as? Int, 3, "the copy of Charizard's entry")
        XCTAssertEqual(events[5]["reason"] as? String, "swipe")
        XCTAssertEqual(events[6]["reason"] as? String, "remove_last")
        XCTAssertEqual(events[7]["in_pack"] as? Bool, false)
        XCTAssertEqual(events[7]["source"] as? String, "search")
        XCTAssertEqual(events[10]["reason"] as? String, "user")
        XCTAssertEqual(events[10]["t_ms"] as? Int, 3000)
        let price = try XCTUnwrap(events[1]["price"] as? JSON)
        XCTAssertEqual(price["basis"] as? String, "cardmarket")
        XCTAssertEqual(price["currency"] as? String, "EUR")
        XCTAssertEqual((price["cardmarket"] as? JSON)?["trend"] as? Double, 2)
        XCTAssertEqual((o["summary"] as? JSON)?["cards"] as? Int, 0)
    }

    func testARevealTakesTheTimeOfItsFirstFrame() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("log-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var clock: Int64 = 5_000_000_000
        let log = EventLog(recordingId: "rec2", jsonl: dir.appendingPathComponent("events.jsonl"), threshold: 5, nanos: { clock }, revealTiming: true)
        log.start(firstFrameNanos: clock, packAtStart: [], shown: nil)
        clock += 1_000_000_000
        let a = shown(card("t-1", "Pikachu", 7), 1)
        log.observe(.shown, source: "camera", shown: a, session: [item(a)], quad: nil)
        // drawn 120 ms later
        log.revealDrawn(1, drawNanos: clock + 120_000_000)
        let text = log.finish(header: [:], finalPack: [item(a)], status: "complete")
        let o = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? JSON)
        let events = try XCTUnwrap(o["events"] as? [JSON])
        XCTAssertEqual(events[1]["t_ms"] as? Int, 1120)
        let cards = try XCTUnwrap(o["cards"] as? [JSON])
        XCTAssertEqual(cards.first?["first_t_ms"] as? Int, 1120)
        XCTAssertEqual(cards.first?["entry"] as? Int, 1)
        let summary = try XCTUnwrap(o["summary"] as? JSON)
        XCTAssertEqual(summary["hits"] as? Int, 1)
        XCTAssertEqual(summary["pack_value_eur"] as? Double, 7)
    }

    // ------------------------------------------------------------------ after a crash
    func testARecordingIsRecoveredFromItsLog() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rcv-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let jsonl = dir.appendingPathComponent("events.jsonl")
        let card: (String) -> JSON = { ["id": $0, "name": $0 == "a" ? "Pikachu, \"the\" mouse" : "Charizard", "set": "Test", "number_text": "1/100",
                                        "lang": "en", "printing": "normal"] }
        let lines: [JSON] = [
            ["seq": 1, "t_ms": 0, "type": "recording_started", "pack_at_start": [["entry": 1, "before_recording": true, "hit": false, "card": card("a"),
                                                                                    "price": ["value": 2, "currency": "EUR"]]]],
            ["seq": 2, "t_ms": 900, "type": "card_shown", "entry": 2, "in_pack": true, "hit": true, "card": card("b"), "price": ["value": 300, "currency": "EUR"]],
            ["seq": 3, "t_ms": 1500, "type": "card_shown", "entry": NSNull(), "in_pack": false, "hit": false, "card": card("c"), "price": ["value": 1, "currency": "EUR"]],
            ["seq": 4, "t_ms": 2000, "type": "card_updated", "entry": 2, "hit": true, "card": card("b"), "price": ["value": 40, "currency": "USD"]],
        ]
        try (lines.map { RecJSON.text($0, pretty: false) }.joined(separator: "\n") + "\n").write(to: jsonl, atomically: true, encoding: .utf8)
        let (text, cards) = RecordingStore.recoverEvents(header: ["video": ["file": "video.mp4", "duration_ms": NSNull()] as JSON], jsonl: jsonl, id: "r", durationMs: 4200)
        XCTAssertEqual(cards.count, 2)
        let o = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? JSON)
        XCTAssertEqual(o["status"] as? String, "recovered")
        XCTAssertEqual(((o["video"] as? JSON)?["duration_ms"] as? NSNumber)?.int64Value, 4200)
        let events = try XCTUnwrap(o["events"] as? [JSON])
        XCTAssertEqual(events.last?["type"] as? String, "recording_stopped")
        XCTAssertEqual(events.last?["reason"] as? String, "recovered")
        let summary = try XCTUnwrap(o["summary"] as? JSON)
        XCTAssertEqual(summary["pack_value_eur"] as? Double, 2)
        XCTAssertEqual(summary["pack_value_usd"] as? Double, 40)
        XCTAssertEqual(summary["hits"] as? Int, 1)
        let csv = RecordingStore.csvOf(cards)
        XCTAssertTrue(csv.hasPrefix("\u{FEFF}No,Name,"))
        XCTAssertTrue(csv.contains("1,\"Pikachu, \"\"the\"\" mouse\""), csv)
        XCTAssertTrue(csv.contains("2,Charizard,"))
    }

    func testRecordingFoldersAndTheirStatus() throws {
        let store = RecordingStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("st-\(UUID().uuidString)"))
        let d = store.create(now: Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertTrue(d.id.hasPrefix("20270115T080000Z-"), d.id)
        XCTAssertEqual(d.id.count, 21)
        XCTAssertEqual(d.status, .LOCAL)
        d.updateMeta { $0["status"] = RecStatus.QUEUED.rawValue; $0["duration_ms"] = 1_834_000; $0["cards"] = 38; $0["hits"] = 3; $0["waiting_wifi"] = true }
        XCTAssertEqual(d.status, .QUEUED)
        XCTAssertEqual(d.statusText(), "waiting for Wi-Fi")
        XCTAssertTrue(d.summary().hasPrefix("Recording 30:34 · 38 cards · 3 hits · "), d.summary())
        XCTAssertEqual(store.list().map(\.id), [d.id])
        XCTAssertEqual(RecordingDir.durationText(3_723_000), "1:02:03")
        XCTAssertEqual(RecordingDir.sizeText(1_800_000_000), "1.8 GB")
        XCTAssertEqual(RecordingDir.sizeText(412_000_000), "412 MB")
        // the video's size: the screen's shape, the long side as chosen
        let (w, h) = ScreenRecorder.outputSize(screen: CGSize(width: 1179, height: 2556), longSide: 1920)
        XCTAssertEqual(h, 1920)
        XCTAssertEqual(w % 2, 0)
        XCTAssertEqual(w, 886)
        XCTAssertEqual(YouTubeScreen.fitted(CGSize(width: 393, height: 852)).height, 393 * 16 / 9, accuracy: 0.01)
    }

    func testManagedStorageSettings() {
        let suite = UserDefaults(suiteName: "rec-\(UUID().uuidString)")!
        let vault = MemoryVault()
        let prefs = RecordingPrefs(defaults: suite, vault: vault)
        XCTAssertNil(prefs.s3Config())
        XCTAssertTrue(prefs.load().readyToRecord, "saving in Photos needs nothing")
        var s = prefs.load()
        s.uploadS3 = true
        prefs.save(s)
        XCTAssertFalse(prefs.load().readyToRecord, "uploading needs the storage")
        prefs.saveManaged(["provider": "exoscale", "endpoint": "https://sos-at-vie-1.exo.io", "region": "at-vie-1", "bucket": "oc-recordings",
                           "prefix": "acc_7h2k9q0x3m", "access_key_id": "EXO123", "secret_access_key": "top-secret", "updated_at": "2026-11-15T09:30:00.000Z"])
        let m = prefs.load()
        XCTAssertTrue(m.managed)
        XCTAssertTrue(m.readyToRecord)
        XCTAssertEqual(m.prefix, "acc_7h2k9q0x3m/")
        XCTAssertFalse(m.pathStyle)
        XCTAssertEqual(prefs.s3Config()?.secret, "top-secret")
        XCTAssertNil(suite.dictionaryRepresentation().values.first { ($0 as? String) == "top-secret" }, "the secret is only in the Keychain")
        prefs.clearManaged()
        XCTAssertNil(prefs.s3Config())
        XCTAssertFalse(prefs.load().managed)
        XCTAssertNil(vault.read("s3_secret"))
    }

    // ------------------------------------------------------------------ a video that stops working
    func testErrorsKeepTheirCodes() {
        let inner = NSError(domain: NSOSStatusErrorDomain, code: -16364, userInfo: nil)
        let e = NSError(domain: "AVFoundationErrorDomain", code: -11800, userInfo: [
            NSLocalizedDescriptionKey: "The operation could not be completed",
            NSLocalizedFailureReasonErrorKey: "An unknown error occurred (-16364)",
            NSUnderlyingErrorKey: inner,
        ])
        XCTAssertEqual(ScreenWriter.describe(e),
                       "The operation could not be completed – An unknown error occurred (-16364) (AVFoundationErrorDomain -11800 › NSOSStatusErrorDomain -16364)")
        XCTAssertEqual(ScreenWriter.describe(nil), "the video stopped working")
    }

    private func screenFrame(_ pts: CMTime) -> CMSampleBuffer? {
        var pb: CVPixelBuffer?
        let attrs: [String: Any] = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]]
        guard CVPixelBufferCreate(nil, 1179, 2556, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, attrs as CFDictionary, &pb) == kCVReturnSuccess,
              let pb else { return nil }
        CVPixelBufferLockBaseAddress(pb, [])
        for p in 0..<CVPixelBufferGetPlaneCount(pb) {
            memset(CVPixelBufferGetBaseAddressOfPlane(pb, p), p == 0 ? Int32(40 + Int(CMTimeGetSeconds(pts) * 30) % 160) : 128,
                   CVPixelBufferGetBytesPerRowOfPlane(pb, p) * CVPixelBufferGetHeightOfPlane(pb, p))
        }
        CVPixelBufferUnlockBaseAddress(pb, [])
        var desc: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pb, formatDescriptionOut: &desc)
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 30), presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sb: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: pb, formatDescription: desc!, sampleTiming: &timing, sampleBufferOut: &sb)
        return sb
    }

    /// What "keep what was recorded" stands on: a screen video the writer never finished is readable up to its last whole fragment.
    func testAnUnfinishedVideoIsReadableUpToItsLastFragment() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("frag-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("video.mp4")
        let w = ScreenWriter()
        XCTAssertNil(w.open(url, size: ScreenRecorder.outputSize(screen: CGSize(width: 1179, height: 2556), longSide: 1280), bitrate: 4_000_000, mic: false))
        let start = CACurrentMediaTime()
        // 12 seconds of frames, never finished (as when the writer fails or the app is stopped)
        for i in 0..<360 {
            if let sb = screenFrame(CMTime(seconds: start + Double(i) / 30, preferredTimescale: 1_000_000_000)) { w.enqueue(sb, .video) }
            if i % 10 == 0 { try await Task.sleep(nanoseconds: 50_000_000) }
        }
        try await Task.sleep(nanoseconds: 2_000_000_000)
        let ms = await RecordingStore.playableMs(url)
        print("StoreTest: RecordingTest: unfinished video \(RecordingDir.size(url)) bytes, playable \(ms) ms")
        XCTAssertGreaterThan(RecordingDir.size(url), 0)
        XCTAssertGreaterThanOrEqual(ms, Int64(ScreenWriter.fragmentSeconds * 1000), "at least the first fragment")
        _ = await w.close()
    }
}
