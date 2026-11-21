import Foundation

typealias JSON = [String: Any]

/// The account server is there but doesn't answer as the API does (down, not deployed, a proxy's page). Counts as offline.
struct ServerUnavailable: Error, LocalizedError {
    let code: Int
    let message: String
    var errorDescription: String? { message }
}

/// A sign-in's (or pairing's) answer: the device token (secret) and the status.
struct Paired {
    let token: String
    let status: JSON
    var created = false
    /// what became of a creator code sent with it (applied, unknown_code, already_registered, already_customer)
    var affiliateResult: String?
    var affiliate: JSON?
}

/// Move to a new phone: the code the new phone scans (as a QR code of url).
struct TransferCode: Equatable {
    let code: String
    let url: String
    let expiresAt: Date?
}

/**
 * The app endpoints of the account API (`/api/app/v1`, pokeshop docs/app-account-api.md), JSON both
 * ways. An error answer throws AccountError; no connection throws a URLError. The token goes only
 * into the Authorization header, never into a log.
 */
final class AccountApi: @unchecked Sendable {
    let base: String
    private let appVersion: String?
    private let session: URLSession
    /// staging's access header (tests against staging only; never in the app)
    var stagingAccess: String?

    init(base: String, appVersion: String? = AccountApi.version, session: URLSession? = nil) {
        self.base = base.hasSuffix("/") ? String(base.dropLast()) : base
        self.appVersion = appVersion
        if let session { self.session = session } else {
            let c = URLSessionConfiguration.ephemeral
            c.timeoutIntervalForRequest = 15
            c.timeoutIntervalForResource = 25
            c.httpCookieStorage = nil
            c.urlCache = nil
            self.session = URLSession(configuration: c)
        }
    }

    static var version: String? { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String }

    // ---------------------------------------------------------------- pairing and sign-in
    func pair(code: String, deviceId: String, deviceName: String?, affiliateCode: String? = nil) async throws -> Paired {
        try signedIn(await call("POST", ["pair"], nil, device(deviceId, deviceName, affiliateCode).merging(["code": code]) { $1 }))
    }

    func status(token: String) async throws -> JSON { try need(await call("GET", ["status"], token)) }

    /** Sign in with Apple: the identity token, and the authorization code (the server keeps Apple's refresh token to revoke it on deletion). */
    func loginApple(identityToken: String, authorizationCode: String?, deviceId: String, deviceName: String?, affiliateCode: String? = nil) async throws -> Paired {
        var body = device(deviceId, deviceName, affiliateCode)
        body["identity_token"] = identityToken
        if let authorizationCode { body["authorization_code"] = authorizationCode }
        return try signedIn(await call("POST", ["login", "apple"], nil, body))
    }

    /** Use another email: a 6-digit code is emailed. Returns the seconds until another may be asked for. */
    func loginEmail(_ email: String) async throws -> Int {
        let o = try need(await call("POST", ["login", "email"], nil, ["email": email]))
        return (o["resend_after"] as? NSNumber)?.intValue ?? 60
    }

    func loginEmailVerify(email: String, code: String, deviceId: String, deviceName: String?, affiliateCode: String? = nil) async throws -> Paired {
        var body = device(deviceId, deviceName, affiliateCode)
        body["email"] = email
        body["code"] = code
        return try signedIn(await call("POST", ["login", "email", "verify"], nil, body))
    }

    private func device(_ deviceId: String, _ deviceName: String?, _ affiliateCode: String?) -> JSON {
        var o: JSON = ["device_id": deviceId, "platform": "ios"]
        o["device_name"] = deviceName.map { String($0.prefix(80)) as Any } ?? NSNull()
        o["app_version"] = appVersion.map { String($0.prefix(40)) as Any } ?? NSNull()
        if let affiliateCode { o["affiliate_code"] = affiliateCode }
        return o
    }

    private func signedIn(_ answer: JSON?) throws -> Paired {
        let o = try need(answer)
        guard let token = o["token"] as? String else { throw ServerUnavailable(code: 200, message: "account server: no token in the answer") }
        return Paired(token: token, status: o["status"] as? JSON ?? [:], created: (o["created"] as? Bool) ?? false,
                      affiliateResult: AccountStatus.str(o, "affiliate_code"), affiliate: o["affiliate"] as? JSON)
    }

    // ---------------------------------------------------------------- creator codes, buying in the App Store
    func affiliate(token: String, code: String) async throws -> JSON { try need(await call("POST", ["affiliate"], token, ["code": code])) }

    /// the plans on sale with their store product ids (`offers`), and the account's creator code (`affiliate`)
    func offers(token: String) async throws -> JSON { try need(await call("GET", ["store", "offers"], token)) }

    /// reports an App Store transaction (the JWS from StoreKit 2); answers the status
    func storeApple(token: String, signedTransaction: String) async throws -> JSON {
        try need(await call("POST", ["store", "apple"], token, ["signed_transaction": signedTransaction]))
    }

    /// Deletes the account (asked first in the app): `{ "deleted": true, "manage_url": … }`
    func deleteAccount(token: String) async throws -> JSON { try await call("POST", ["account", "delete"], token, ["confirm": true]) ?? [:] }

    /// the newest data pack: version and files with their download addresses (valid 1 hour)
    func pack(token: String) async throws -> JSON { try need(await call("GET", ["pack"], token)) }

    func transfer(token: String) async throws -> TransferCode {
        let o = try need(await call("POST", ["transfer"], token, [:]))
        guard let code = o["code"] as? String, let url = o["url"] as? String else { throw ServerUnavailable(code: 200, message: "account server: no transfer code") }
        return TransferCode(code: code, url: url, expiresAt: AccountStatus.time(o, "expires_at"))
    }

    /// Disconnects this device (its slot is free again; the token stops working).
    func disconnect(token: String) async throws { _ = try await call("DELETE", ["device"], token) }

    // ---------------------------------------------------------------- collection and missing cards
    func collectionCard(token: String, cardId: String) async throws -> [JSON] {
        try need(await call("GET", ["collection", "cards", cardId], token))["items"] as? [JSON] ?? []
    }

    func addToCollection(token: String, body: JSON) async throws -> JSON { try need(await call("POST", ["collection", "items"], token, body)) }

    /// One page of the whole collection (newest change last); next: the cursor of the next page, nil at the end.
    func collectionItems(token: String, cursor: String?, limit: Int = 500) async throws -> (items: [JSON], next: String?) {
        var q = [URLQueryItem(name: "limit", value: String(limit))]
        if let cursor { q.append(URLQueryItem(name: "cursor", value: cursor)) }
        let o = try need(await call("GET", ["collection", "items"], token, query: q))
        return (o["items"] as? [JSON] ?? [], AccountStatus.str(o, "next_cursor"))
    }

    /// Changes a copy: only the fields given (`null` clears an optional one); the shop's item comes back.
    func updateItem(token: String, id: Int64, changes: JSON) async throws -> JSON {
        try need(await call("PATCH", ["collection", "items", String(id)], token, changes))
    }

    /// Deletes a copy (204, also when it was gone already).
    func deleteItem(token: String, id: Int64) async throws { _ = try await call("DELETE", ["collection", "items", String(id)], token) }

    /// The collection's public link: `{ "public": {url, views, …} | null, "other_public": n }` (shop doc: sharing from the app).
    func share(token: String) async throws -> JSON { try need(await call("GET", ["collection", "share"], token)) }

    /// A public link of the whole collection with prices (the existing one if there is one); the same answer as share().
    func makePublic(token: String) async throws -> JSON { try need(await call("POST", ["collection", "share"], token, ["show_prices": true])) }

    /// Switches off every public link of the collection (personal ones sent by email stay).
    func makePrivate(token: String) async throws { _ = try await call("DELETE", ["collection", "share"], token) }

    func binders(token: String) async throws -> [JSON] { try need(await call("GET", ["collection", "binders"], token))["binders"] as? [JSON] ?? [] }

    /// The account's S3 storage for recordings, set up by Overpriced Cardboard (Creator, the s3_upload service).
    func s3(token: String) async throws -> JSON { try need(await call("GET", ["services", "s3"], token)) }

    func wanted(token: String) async throws -> [JSON] { try need(await call("GET", ["wanted"], token))["items"] as? [JSON] ?? [] }

    func addWanted(token: String, body: JSON) async throws -> JSON { try need(await call("POST", ["wanted"], token, body)) }

    func deleteWanted(token: String, id: Int64) async throws { _ = try await call("DELETE", ["wanted", String(id)], token) }

    // ---------------------------------------------------------------- plumbing

    /// an answer that must have a JSON body
    private func need(_ o: JSON?) throws -> JSON {
        guard let o else { throw ServerUnavailable(code: 200, message: "account server: an empty answer") }
        return o
    }

    func url(_ segments: [String]) -> URL {
        var u = URL(string: base)!.appendingPathComponent("api").appendingPathComponent("app").appendingPathComponent("v1")
        for s in segments { u = u.appendingPathComponent(s) }
        return u
    }

    /// One call; the answer's JSON (nil for 204).
    private func call(_ method: String, _ segments: [String], _ token: String?, _ body: JSON? = nil,
                      query: [URLQueryItem] = []) async throws -> JSON? {
        var u = url(segments)
        if !query.isEmpty, var c = URLComponents(url: u, resolvingAgainstBaseURL: false) {
            c.queryItems = query
            if let withQuery = c.url { u = withQuery }
        }
        var req = URLRequest(url: u)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let appVersion { req.setValue(appVersion, forHTTPHeaderField: "X-App-Version") }
        if let stagingAccess { req.setValue(stagingAccess, forHTTPHeaderField: "X-Staging-Access") }
        if method != "GET" {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body ?? [:])
        }
        let (data, response) = try await session.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? JSON
        if !(200..<300).contains(code) {
            let error = json.flatMap { AccountStatus.str($0, "error") }
            // a proxy's page instead of the API's answer: no connection to the API
            if error == nil && (code >= 500 || (code == 404 && json == nil)) { throw ServerUnavailable(code: code, message: "account server: HTTP \(code)") }
            throw AccountError(status: code, error: error ?? "http_\(code)", message: json.flatMap { AccountStatus.str($0, "message") }, json: json)
        }
        if code == 204 || data.isEmpty { return nil }
        guard let json else { throw ServerUnavailable(code: code, message: "account server: not JSON") }
        return json
    }
}
