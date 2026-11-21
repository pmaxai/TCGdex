import Foundation

/// The kind of S3 store the account's storage is (from the shop's /services/s3).
enum S3Provider: String, CaseIterable {
    case aws, exoscale, other

    var label: String {
        switch self {
        case .aws: return "AWS S3"
        case .exoscale: return "Exoscale SOS"
        case .other: return "Other S3-compatible"
        }
    }
}

/**
 * How YouTube Mode records, and where the recordings go (the Android app's RecordingSettings).
 * On the iPhone the S3 storage is always the account's, set up by Overpriced Cardboard
 * (GET /services/s3): shown, never typed in.
 */
struct RecordingSettings: Equatable {
    var provider = S3Provider.aws
    var endpoint = ""
    var region = ""
    var bucket = ""
    var prefix = ""
    var pathStyle = false
    var accessKeyId = ""
    var hasSecret = false
    var wifiOnly = true
    var deleteAfterUpload = true
    /// 1080p (else 720p): the screen video's long side
    var hd = true
    var bitrateMbps = 8
    var recordCamera = true
    /// lays YouTube Mode out in a 16:9 area (9:16 upright) while recording, black around it
    var fit169 = true
    var mic = false
    /// the red dot and the time beside the 16:9 area while recording, never in the video
    var showTimer = true
    /// recordings go to the S3 bucket; off (the default): saved on this phone, in Photos
    var uploadS3 = false
    /// the S3 settings came from the account (always so on the iPhone once set up)
    var managed = false

    /// everything the upload needs is there
    var uploadReady: Bool { !endpoint.isEmpty && !region.isEmpty && !bucket.isEmpty && !accessKeyId.isEmpty && hasSecret }
    /// ● REC can start: saving on the phone needs nothing, uploading needs the bucket
    var readyToRecord: Bool { !uploadS3 || uploadReady }
    var longSide: Int { hd ? 1920 : 1280 }
}

/// The recording settings on this phone; the S3 secret in the Keychain, never shown again, never in a log.
final class RecordingPrefs {
    private let d: UserDefaults
    private let vault: SecretVault

    init(defaults: UserDefaults = .standard, vault: SecretVault = Keychain(service: "com.overpricedcardboard.recording")) {
        d = defaults
        self.vault = vault
    }

    private func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: "rec." + k) == nil ? def : d.bool(forKey: "rec." + k) }
    private func str(_ k: String) -> String { d.string(forKey: "rec." + k) ?? "" }

    func load() -> RecordingSettings {
        var s = RecordingSettings()
        s.provider = S3Provider(rawValue: str("provider")) ?? .aws
        s.endpoint = str("endpoint")
        s.region = str("region")
        s.bucket = str("bucket")
        s.prefix = str("prefix")
        s.pathStyle = bool("path_style", false)
        s.accessKeyId = str("access_key")
        s.hasSecret = vault.read("s3_secret") != nil
        s.wifiOnly = bool("wifi_only", true)
        s.deleteAfterUpload = bool("delete_after", true)
        s.hd = bool("hd", true)
        if d.object(forKey: "rec.bitrate") != nil { s.bitrateMbps = d.integer(forKey: "rec.bitrate") }
        s.recordCamera = bool("camera", true)
        s.fit169 = bool("fit169", true)
        s.mic = bool("mic", false)
        s.showTimer = bool("timer", true)
        s.uploadS3 = bool("upload_s3", false)
        s.managed = bool("s3_managed", false)
        return s
    }

    /// Saves the settings (the S3 fields only come from the account: saveManaged).
    func save(_ s: RecordingSettings) {
        d.set(s.wifiOnly, forKey: "rec.wifi_only")
        d.set(s.deleteAfterUpload, forKey: "rec.delete_after")
        d.set(s.hd, forKey: "rec.hd")
        d.set(s.bitrateMbps, forKey: "rec.bitrate")
        d.set(s.recordCamera, forKey: "rec.camera")
        d.set(s.fit169, forKey: "rec.fit169")
        d.set(s.mic, forKey: "rec.mic")
        d.set(s.showTimer, forKey: "rec.timer")
        d.set(s.uploadS3, forKey: "rec.upload_s3")
    }

    /// The S3 settings Overpriced Cardboard set up for the account (GET /api/app/v1/services/s3).
    func saveManaged(_ o: JSON) {
        func s(_ k: String) -> String { (o[k] as? String) ?? "" }
        let provider = S3Provider(rawValue: s("provider")) ?? .other
        d.set(provider.rawValue, forKey: "rec.provider")
        d.set(s("endpoint").trimmingCharacters(in: .whitespaces), forKey: "rec.endpoint")
        d.set(s("region").trimmingCharacters(in: .whitespaces), forKey: "rec.region")
        d.set(s("bucket").trimmingCharacters(in: .whitespaces), forKey: "rec.bucket")
        d.set(S3Config.normalizePrefix(s("prefix")), forKey: "rec.prefix")
        d.set(s("access_key_id").trimmingCharacters(in: .whitespaces), forKey: "rec.access_key")
        // MinIO and others may need path-style addressing; AWS and Exoscale take the bucket in the host
        d.set(provider == .other, forKey: "rec.path_style")
        d.set(true, forKey: "rec.s3_managed")
        let secret = s("secret_access_key").trimmingCharacters(in: .whitespaces)
        if !secret.isEmpty { vault.write("s3_secret", secret) }
    }

    /// The account's storage is gone (service off, disconnected): its bucket and key leave the phone.
    func clearManaged() {
        guard bool("s3_managed", false) else { return }
        for k in ["bucket", "access_key", "prefix", "endpoint", "region"] { d.removeObject(forKey: "rec." + k) }
        vault.delete("s3_secret")
        d.set(false, forKey: "rec.s3_managed")
    }

    /// The bucket connection, or nil when something is missing.
    func s3Config(_ s: RecordingSettings? = nil) -> S3Config? {
        let s = s ?? load()
        guard s.uploadReady, let secret = vault.read("s3_secret") else { return nil }
        return S3Config(endpoint: s.endpoint, region: s.region, bucket: s.bucket, prefix: S3Config.normalizePrefix(s.prefix),
                        pathStyle: s.pathStyle, accessKeyId: s.accessKeyId, secret: secret)
    }
}
