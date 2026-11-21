import CryptoKit
import Foundation

/**
 * Uploads to any S3-compatible store (AWS S3, Exoscale SOS, MinIO, R2, Wasabi) with a hand-written
 * Signature Version 4 (CryptoKit), no SDK – the Android app's S3 client. Only what recording needs:
 * put, get and delete an object, and multipart uploads (create, part, list, complete, abort).
 */
enum SigV4 {
    static func hex<D: Sequence>(_ d: D) -> String where D.Element == UInt8 { d.map { String(format: "%02x", $0) }.joined() }
    static func sha256Hex(_ d: Data) -> String { hex(SHA256.hash(data: d)) }
    static func md5Base64(_ d: Data) -> String { Data(Insecure.MD5.hash(data: d)).base64EncodedString() }
    static let emptySha256 = sha256Hex(Data())

    private static func hmac(_ key: Data, _ s: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data(s.utf8), using: SymmetricKey(data: key)))
    }

    /// AWS's URI encoding: unreserved characters stay, everything else %XX (upper case); '/' stays in paths.
    static func uriEncode(_ s: String, keepSlash: Bool) -> String {
        var out = ""
        for b in Array(s.utf8) {
            switch b {
            case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "-"), UInt8(ascii: "_"), UInt8(ascii: "."), UInt8(ascii: "~"):
                out.append(Character(UnicodeScalar(b)))
            case UInt8(ascii: "/") where keepSlash:
                out.append("/")
            default:
                out += String(format: "%%%02X", b)
            }
        }
        return out
    }

    /// "a=1&b=" from raw (unencoded) pairs, sorted as AWS wants.
    static func canonicalQuery(_ params: [(String, String)]) -> String {
        params.map { (uriEncode($0.0, keepSlash: false), uriEncode($0.1, keepSlash: false)) }
            .sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }
            .map { "\($0.0)=\($0.1)" }.joined(separator: "&")
    }

    struct Signed { let authorization: String; let canonicalRequest: String; let stringToSign: String }

    /**
     * Signs a request. canonicalUri is the already encoded path, headers the headers to sign (names
     * any case; host and x-amz-date must be among them), payloadHash the hex SHA-256 of the body.
     */
    static func sign(method: String, canonicalUri: String, query: [(String, String)], headers: [String: String], payloadHash: String,
                     amzDate: String, region: String, service: String, accessKey: String, secret: String) -> Signed {
        var h: [String: String] = [:]
        for (k, v) in headers {
            h[k.lowercased().trimmingCharacters(in: .whitespaces)] =
                v.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        }
        let names = h.keys.sorted()
        let signedHeaders = names.joined(separator: ";")
        let canonical = [method, canonicalUri.isEmpty ? "/" : canonicalUri, canonicalQuery(query),
                         names.map { "\($0):\(h[$0]!)\n" }.joined(), signedHeaders, payloadHash].joined(separator: "\n")
        let day = String(amzDate.prefix(8))
        let scope = "\(day)/\(region)/\(service)/aws4_request"
        let toSign = "AWS4-HMAC-SHA256\n\(amzDate)\n\(scope)\n\(sha256Hex(Data(canonical.utf8)))"
        var key = hmac(Data("AWS4\(secret)".utf8), day)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        let signature = hex(hmac(key, toSign))
        return Signed(authorization: "AWS4-HMAC-SHA256 Credential=\(accessKey)/\(scope), SignedHeaders=\(signedHeaders), Signature=\(signature)",
                      canonicalRequest: canonical, stringToSign: toSign)
    }

    static func amzDate(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return f.string(from: d)
    }
}

/// Where recordings go. prefix is "" or ends with "/".
struct S3Config: CustomStringConvertible, Equatable {
    var endpoint: String
    var region: String
    var bucket: String
    var prefix: String
    var pathStyle: Bool
    var accessKeyId: String
    var secret: String
    var allowHttp = false

    var description: String { "S3Config(\(endpoint), \(region), \(bucket), prefix=\(prefix), pathStyle=\(pathStyle), key=\(accessKeyId), secret=…)" }

    /// "tcgdex" -> "tcgdex/", "/a/b/" -> "a/b/", "" -> ""
    static func normalizePrefix(_ p: String) -> String {
        let t = p.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return t.isEmpty ? "" : t + "/"
    }
}

struct S3Error: Error, LocalizedError {
    let status: Int
    let code: String
    let message: String?
    /// the server's clock (its Date header), for a phone whose clock is off
    var serverDate: Date? = nil

    /// a setting is wrong: retrying won't help until it changes
    var permanent: Bool { [400, 401, 403, 404, 405].contains(status) && code != "RequestTimeTooSkewed" && code != "NoSuchUpload" }

    var friendly: String {
        switch code {
        case "InvalidAccessKeyId": return "Access key ID unknown"
        case "SignatureDoesNotMatch": return "Secret key is wrong"
        case "AccessDenied": return "This key may not write to the bucket/prefix"
        case "NoSuchBucket": return "Bucket not found"
        case "RequestTimeTooSkewed": return "Phone clock is off"
        case "AuthorizationHeaderMalformed": return "Region doesn't fit this endpoint" + (message.map { " (\($0))" } ?? "")
        case "PermanentRedirect": return "The bucket is in another region or needs another endpoint"
        default: return "S3 answered \(status) (\(code))" + (message.flatMap { $0 == code ? nil : ": \($0)" } ?? "")
        }
    }

    var errorDescription: String? { friendly }
}

struct S3Part: Equatable { let number: Int; let etag: String; let size: Int64 }

/// One S3 bucket; every request signed, 5xx and dropped connections tried 3 times, a wrong phone clock corrected once.
final class S3Client: @unchecked Sendable {
    let cfg: S3Config
    private let session: URLSession
    private let base: URLComponents
    private let lock = NSLock()
    private var clockOffset: TimeInterval = 0
    private let sleep: (Double) async -> Void

    /// the phone's clock against S3's (seconds, positive: S3 is ahead)
    var offset: TimeInterval { lock.lock(); defer { lock.unlock() }; return clockOffset }

    init(cfg: S3Config, session: URLSession? = nil, sleep: ((Double) async -> Void)? = nil) throws {
        self.cfg = cfg
        if let session { self.session = session } else {
            let c = URLSessionConfiguration.default
            c.timeoutIntervalForRequest = 90
            c.waitsForConnectivity = false
            self.session = URLSession(configuration: c)
        }
        self.sleep = sleep ?? { s in try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        let e = cfg.endpoint.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let c = URLComponents(string: e), let scheme = c.scheme, c.host?.isEmpty == false else {
            throw S3Error(status: 0, code: "BadEndpoint", message: "not an endpoint URL: \(cfg.endpoint)")
        }
        guard scheme == "https" || (scheme == "http" && cfg.allowHttp) else {
            throw S3Error(status: 0, code: "BadEndpoint", message: "only https")
        }
        base = c
    }

    @discardableResult
    func putObject(_ key: String, _ body: Data, contentType: String = "application/octet-stream") async throws -> String {
        try await call("PUT", key, [], body, contentType).etag
    }

    func deleteObject(_ key: String) async throws { _ = try await call("DELETE", key, [], nil, nil) }

    func getObject(_ key: String) async throws -> Data { try await call("GET", key, [], nil, nil).body }

    func createMultipartUpload(_ key: String, contentType: String) async throws -> String {
        let r = try await call("POST", key, [("uploads", "")], Data(), contentType)
        guard let id = Self.xml(r.text, "UploadId") else { throw S3Error(status: r.status, code: "NoUploadId", message: "no UploadId in the answer") }
        return id
    }

    func uploadPart(_ key: String, uploadId: String, number: Int, body: Data) async throws -> String {
        try await call("PUT", key, [("partNumber", String(number)), ("uploadId", uploadId)], body, nil).etag
    }

    /// The parts S3 already has (paged).
    func listParts(_ key: String, uploadId: String) async throws -> [S3Part] {
        var out: [S3Part] = []
        var marker: String?
        repeat {
            var q = [("uploadId", uploadId), ("max-parts", "1000")]
            if let marker { q.append(("part-number-marker", marker)) }
            let text = try await call("GET", key, q, nil, nil).text
            for p in Self.all(text, "Part") {
                guard let n = Self.xml(p, "PartNumber").flatMap(Int.init), let tag = Self.xml(p, "ETag") else { continue }
                out.append(S3Part(number: n, etag: tag.replacingOccurrences(of: "&quot;", with: "\""), size: Self.xml(p, "Size").flatMap { Int64($0) } ?? -1))
            }
            marker = Self.xml(text, "IsTruncated") == "true" ? Self.xml(text, "NextPartNumberMarker") : nil
        } while marker != nil
        return out
    }

    func completeMultipartUpload(_ key: String, uploadId: String, parts: [S3Part]) async throws {
        let body = "<CompleteMultipartUpload>" + parts.sorted { $0.number < $1.number }
            .map { "<Part><PartNumber>\($0.number)</PartNumber><ETag>\($0.etag)</ETag></Part>" }.joined() + "</CompleteMultipartUpload>"
        let r = try await call("POST", key, [("uploadId", uploadId)], Data(body.utf8), "application/xml")
        // S3 may answer 200 and still report an error in the body
        if r.text.contains("<Error>") { throw S3Error(status: 500, code: Self.xml(r.text, "Code") ?? "InternalError", message: Self.xml(r.text, "Message")) }
    }

    func abortMultipartUpload(_ key: String, uploadId: String) async throws {
        do { _ = try await call("DELETE", key, [("uploadId", uploadId)], nil, nil) }
        catch let e as S3Error where e.code == "NoSuchUpload" {}
    }

    /// Test the connection: writes a small object under the prefix and deletes it again.
    func connectionTest() async throws -> String {
        let key = cfg.prefix + "_connection-test/" + UUID().uuidString.lowercased() + ".txt"
        try await putObject(key, Data("connection test from the scanner app".utf8), contentType: "text/plain")
        let deleted = (try? await deleteObject(key)) != nil
        return "Connected · \(cfg.bucket) · write OK" + (deleted ? "" : " (the test file couldn't be deleted: the key may only write)")
    }

    private struct Answer { let status: Int; let etag: String; let body: Data; var text: String { String(decoding: body, as: UTF8.self) } }

    /// the request's URL and its canonical (encoded) path
    func url(_ key: String, _ query: [(String, String)]) -> (URL, String) {
        let enc = SigV4.uriEncode(key, keepSlash: true)
        let path = cfg.pathStyle ? "/\(SigV4.uriEncode(cfg.bucket, keepSlash: false))/\(enc)" : "/\(enc)"
        let host = cfg.pathStyle ? base.host! : "\(cfg.bucket).\(base.host!)"
        let prefixPath = base.percentEncodedPath.hasSuffix("/") ? String(base.percentEncodedPath.dropLast()) : base.percentEncodedPath
        var c = URLComponents()
        c.scheme = base.scheme
        c.host = host
        c.port = base.port
        c.percentEncodedPath = prefixPath + path
        if !query.isEmpty { c.percentEncodedQuery = SigV4.canonicalQuery(query) }
        return (c.url!, prefixPath + path)
    }

    private func call(_ method: String, _ key: String, _ query: [(String, String)], _ body: Data?, _ contentType: String?) async throws -> Answer {
        var attempt = 0
        var clockFixed = false
        while true {
            attempt += 1
            do {
                return try await once(method, key, query, body, contentType)
            } catch let e as S3Error {
                if e.code == "RequestTimeTooSkewed" && !clockFixed {
                    clockFixed = true
                    if let d = e.serverDate { lock.withLock { clockOffset = d.timeIntervalSinceNow } }
                    continue
                }
                if (e.status >= 500 || e.status == 429) && attempt < 3 { await sleep(Double(1 << (attempt - 1))); continue }
                throw e
            } catch let e as URLError {
                if e.code == .cancelled { throw e }
                if attempt < 3 { await sleep(Double(1 << (attempt - 1))); continue }
                throw e
            }
        }
    }

    private func once(_ method: String, _ key: String, _ query: [(String, String)], _ body: Data?, _ contentType: String?) async throws -> Answer {
        let (url, canonicalUri) = url(key, query)
        let payloadHash = body.map { SigV4.sha256Hex($0) } ?? SigV4.emptySha256
        let amzDate = SigV4.amzDate(Date().addingTimeInterval(offset))
        let defaultPort = url.scheme == "https" ? 443 : 80
        let hostHeader = (url.port == nil || url.port == defaultPort) ? url.host! : "\(url.host!):\(url.port!)"
        var headers = ["host": hostHeader, "x-amz-date": amzDate, "x-amz-content-sha256": payloadHash]
        if let body, !body.isEmpty { headers["content-md5"] = SigV4.md5Base64(body) }
        if let contentType { headers["content-type"] = contentType }
        let signed = SigV4.sign(method: method, canonicalUri: canonicalUri, query: query, headers: headers, payloadHash: payloadHash,
                                amzDate: amzDate, region: cfg.region, service: "s3", accessKey: cfg.accessKeyId, secret: cfg.secret)
        var req = URLRequest(url: url)
        req.httpMethod = method
        for (k, v) in headers where k != "host" { req.setValue(v, forHTTPHeaderField: k) }
        req.setValue(signed.authorization, forHTTPHeaderField: "Authorization")
        if let body, method == "PUT" || method == "POST" { req.httpBody = body }
        let (data, response) = try await session.data(for: req)
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let text = String(decoding: data, as: UTF8.self)
            let date = (http?.value(forHTTPHeaderField: "Date")).flatMap(Self.httpDate)
            throw S3Error(status: status, code: Self.xml(text, "Code") ?? "http_\(status)", message: Self.xml(text, "Message"), serverDate: date)
        }
        return Answer(status: status, etag: http?.value(forHTTPHeaderField: "ETag") ?? "", body: data)
    }

    static func httpDate(_ s: String) -> Date? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f.date(from: s)
    }

    static func xml(_ text: String, _ tag: String) -> String? {
        guard let a = text.range(of: "<\(tag)>"), let b = text.range(of: "</\(tag)>", range: a.upperBound..<text.endIndex) else { return nil }
        return String(text[a.upperBound..<b.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func all(_ text: String, _ tag: String) -> [String] {
        var out: [String] = []
        var from = text.startIndex
        while let a = text.range(of: "<\(tag)>", range: from..<text.endIndex), let b = text.range(of: "</\(tag)>", range: a.upperBound..<text.endIndex) {
            out.append(String(text[a.upperBound..<b.lowerBound]))
            from = b.upperBound
        }
        return out
    }
}
