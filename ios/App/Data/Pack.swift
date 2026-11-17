import Accelerate
import Foundation

/**
 * The data pack built by the pipeline (`python -m tcgscan export`): fingerprint model, index of
 * every reference image, catalog with prices. It ships inside the app (folder "pack") and the
 * nightly one is downloaded later (as on Android's Play edition).
 */
final class Pack {
    let dir: URL
    let version: String
    let meta: [String: String]
    let db: SQLiteDB
    /// n x dim float32 fingerprints, row-major
    let index: Data
    let n: Int
    let dim: Int
    let entryCard: [String]
    let entryLang: [String]
    /// artwork group of each entry as a number, and the groups' names (card ids)
    let entryGroup: [Int32]
    let groupNames: [String]
    /// foil check: n x 72 texture cells of each reference image (nil in older packs)
    let texture: Data?

    var modelPath: String { dir.appendingPathComponent("model.onnx").path }

    func metaInt(_ key: String) -> Int { Int(meta[key] ?? "") ?? 0 }
    func metaFloat(_ key: String, _ def: Float) -> Float { meta[key].flatMap { Float($0) } ?? def }

    /// the pack inside the app
    static var bundledDir: URL? { Bundle.main.url(forResource: "pack", withExtension: nil) }

    /// Slow (converts the 40 MB index): call off the main thread.
    init(dir: URL) throws {
        self.dir = dir
        let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("pack.json"))) as? [String: Any]
        version = manifest?["version"] as? String ?? "?"
        let database = try SQLiteDB(path: dir.appendingPathComponent("catalog.sqlite").path)
        db = database
        var m: [String: String] = [:]
        for r in database.query("SELECT key, value FROM meta") { if let k = r[0].string, let v = r[1].string { m[k] = v } }
        meta = m
        let dim = Int(m["dim"] ?? "") ?? 0
        let n = Int(m["entries"] ?? "") ?? 0
        self.dim = dim
        self.n = n
        let hasGroups = database.columns("entries").contains("grp")
        var cards = [String](repeating: "", count: n)
        var langs = [String](repeating: "", count: n)
        var groups = [Int32](repeating: 0, count: n)
        var names: [String] = []
        var numberOf: [String: Int32] = [:]
        for r in database.query("SELECT idx, card_id, lang, \(hasGroups ? "grp" : "card_id") FROM entries ORDER BY idx") {
            guard let i = r[0].int, i >= 0, i < n else { continue }
            cards[i] = r[1].string ?? ""
            langs[i] = r[2].string ?? ""
            let g = r[3].string ?? cards[i]
            if let k = numberOf[g] { groups[i] = k } else {
                let k = Int32(names.count); numberOf[g] = k; names.append(g); groups[i] = k
            }
        }
        entryCard = cards; entryLang = langs; entryGroup = groups; groupNames = names

        // the index: float16 on disk, float32 for the search
        let half = try Data(contentsOf: dir.appendingPathComponent("index.f16"), options: .mappedIfSafe)
        guard half.count == n * dim * 2 else {
            throw NSError(domain: "Pack", code: 2, userInfo: [NSLocalizedDescriptionKey: "index.f16 has \(half.count) bytes, expected \(n * dim * 2)"])
        }
        var out = Data(count: n * dim * 4)
        half.withUnsafeBytes { src in
            out.withUnsafeMutableBytes { dst in
                var s = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: src.baseAddress!), height: vImagePixelCount(n),
                                      width: vImagePixelCount(dim), rowBytes: dim * 2)
                var d = vImage_Buffer(data: dst.baseAddress!, height: vImagePixelCount(n), width: vImagePixelCount(dim), rowBytes: dim * 4)
                _ = vImageConvert_Planar16FtoPlanarF(&s, &d, vImage_Flags(kvImageNoFlags))
            }
        }
        index = out
        let tex = try? Data(contentsOf: dir.appendingPathComponent("texture.u8"))
        texture = (tex?.count == n * 72) ? tex : nil
    }

    /// the entries' group numbers as int32 bytes (for CardCore)
    var groupData: Data { entryGroup.withUnsafeBufferPointer { Data(buffer: $0) } }

    /// The artwork group (its name) of a card, or the card itself when it has no picture.
    func groupOf(card: String) -> String {
        if let i = entryCard.firstIndex(of: card) { return groupNames[Int(entryGroup[i])] }
        return card
    }

    /// "40567 card images · data 2026-11-16"
    var info: String { "\(n) card images · data \(version.prefix(10))" }
}
