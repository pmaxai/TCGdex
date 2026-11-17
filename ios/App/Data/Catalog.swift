import Foundation

/// Cardmarket price guide values in EUR (nil = no data). The "holo" values are Cardmarket's reverse-holo prices.
struct Prices: Equatable {
    var updated: String?
    var trend: Double?, avg: Double?, low: Double?, avg30: Double?
    var trendHolo: Double?, avgHolo: Double?, lowHolo: Double?, avg30Holo: Double?
    var headline: Double? { trend ?? avg30 ?? avg }
    var headlineHolo: Double? { trendHolo ?? avg30Holo ?? avgHolo }
}

/// One printing of a card (normal, holo, reverse holo, 1st edition) with its prices.
struct Printing: Equatable, Hashable {
    enum Kind: String, CaseIterable { case normal = "Normal", holo = "Holo", reverse = "Reverse holo", firstEdition = "1st Edition" }
    var kind: Kind
    /// Cardmarket EUR: trend (or 30-day / average), 30-day average, lowest offer
    var eur: Double?, eur30: Double?, eurLow: Double?
    /// TCGplayer market price, USD, and in EUR at the data pack's rate
    var usd: Double?
    var usdEur: Double? = nil
    /// the price to judge this printing by: Cardmarket, else TCGplayer (in EUR where the rate is known)
    var value: Double? { eur ?? usdEur ?? usd }
}

struct CardInfo: Equatable, Identifiable {
    var id: String
    var lang: String
    var name: String
    var number: String
    var setName: String
    var setTotal: Int?
    var series: String
    var rarity: String?
    var imageUrl: String?
    var prices: Prices?
    var printings: [Printing]
    var category: String? = nil
    var releaseDate: String? = nil
    var imageAlt: String? = nil
    var imageAltLarge: String? = nil

    var year: String? { releaseDate.map { String($0.prefix(4)) }.flatMap { $0.count == 4 && $0.allSatisfy(\.isNumber) ? $0 : nil } }
    var numberText: String { if let t = setTotal, t > 0 { return "\(number)/\(t)" }; return number }
    var thumbnailUrl: String? { imageUrl.map { "\($0)/low.webp" } ?? imageAlt }
    var highUrl: String? { imageUrl.map { "\($0)/high.webp" } ?? imageAltLarge ?? imageAlt }
    var bestValue: Double? { printings.compactMap(\.value).max() }
    func printing(_ kind: Printing.Kind?) -> Printing? { printings.first { $0.kind == kind } ?? printings.first }
}

/// The card catalog of the data pack (port of Catalog.kt).
final class Catalog {
    private let db: SQLiteDB
    private var cache: [String: CardInfo?] = [:]
    private var groupCache: [String: [String]] = [:]
    private let lock = NSLock()
    private let hasInfo: Bool, hasTcg: Bool, hasReleaseDate: Bool, hasAltImages: Bool, hasGroups: Bool
    let hasCategory: Bool
    private lazy var usdPerEur: Double? = meta("usd_per_eur").flatMap(Double.init).flatMap { $0 > 0 ? $0 : nil }

    init(db: SQLiteDB) {
        self.db = db
        let info = db.tableExists("card_info")
        hasInfo = info
        hasTcg = db.tableExists("tcgplayer")
        hasCategory = info && db.columns("card_info").contains("category")
        hasReleaseDate = db.columns("sets").contains("release_date")
        hasAltImages = db.columns("cards").contains("image_alt")
        hasGroups = db.columns("entries").contains("grp")
    }

    func meta(_ key: String) -> String? { db.query("SELECT value FROM meta WHERE key = ?", [key]).first?.first?.string }

    /// the newest price date in the pack ("2026-11-15T22:16:19Z")
    var pricesDate: String? { db.query("SELECT MAX(updated) FROM prices").first?.first?.string }

    /// The artwork group of a card (its own id when it has no picture in the index).
    func groupOf(_ cardId: String) -> String {
        db.query("SELECT \(hasGroups ? "grp" : "card_id") FROM entries WHERE card_id = ? LIMIT 1", [cardId]).first?.first?.string ?? cardId
    }

    /// The card in exactly this language, or nil.
    func cardExact(_ id: String, _ lang: String) -> CardInfo? {
        lock.lock()
        if let c = cache["\(lang)/\(id)"] { lock.unlock(); return c }
        lock.unlock()
        let c = query(id, lang)
        lock.lock(); cache["\(lang)/\(id)"] = c; lock.unlock()
        return c
    }

    /// The card in this language if it exists, else English, else any language.
    func card(_ id: String, _ lang: String) -> CardInfo? { cardExact(id, lang) ?? cardExact(id, "en") ?? query(id, nil) }

    /// Card ids that share this artwork (languages with own ids, reprints).
    func groupMembers(_ group: String) -> [String] {
        lock.lock()
        if let g = groupCache[group] { lock.unlock(); return g }
        lock.unlock()
        var ids = db.query("SELECT DISTINCT card_id FROM entries WHERE \(hasGroups ? "grp" : "card_id") = ?", [group]).compactMap { $0[0].string }
        if !ids.contains(group) { ids.insert(group, at: 0) }
        lock.lock(); groupCache[group] = ids; lock.unlock()
        return ids
    }

    /// Every version of an artwork: each member card in each language the catalog has.
    func versions(_ group: String) -> [(id: String, lang: String)] {
        groupMembers(group).flatMap { id in
            db.query("SELECT lang FROM cards WHERE id = ?", [id]).compactMap { r -> (id: String, lang: String)? in r[0].string.map { (id: id, lang: $0) } }
        }
    }

    /// The English version of an artwork, for the translation line and as price fallback.
    func englishOf(_ group: String) -> CardInfo? { groupMembers(group).lazy.compactMap { self.cardExact($0, "en") }.first }

    /**
     * Cards whose name (any language), number or set matches every word of the query, best first:
     * "whimsicott 019", "elfun", "charizard base", "GG01", "sv06 34".
     */
    func search(_ query: String, limit: Int = 50) -> [CardInfo] {
        let words = query.trimmingCharacters(in: .whitespaces).lowercased().split(whereSeparator: \.isWhitespace).map(String.init).prefix(6)
        if words.isEmpty { return [] }
        func like(_ w: String) -> String { w.replacingOccurrences(of: "\\", with: "").replacingOccurrences(of: "%", with: "").replacingOccurrences(of: "_", with: "") }
        var conds: [String] = []
        var args: [String] = []
        for w in words {
            let num = String(w.split(separator: "/").first ?? Substring(w))
            var c = ["c.name LIKE ?", "s.name LIKE ?", "c.set_id LIKE ?"]
            args += ["%\(like(w))%", "%\(like(w))%", "\(like(w))%"]
            if num.contains(where: \.isNumber) { c.append("ltrim(upper(c.local_id), '0') = ltrim(upper(?), '0')"); args.append(num) }
            conds.append("(" + c.joined(separator: " OR ") + ")")
        }
        struct Row { let id, lang, name, number, setId, setName, date: String }
        let sql = """
            SELECT c.id, c.lang, c.name, c.local_id, c.set_id, s.name, \(hasReleaseDate ? "s.release_date" : "''")
            FROM cards c LEFT JOIN sets s ON s.id = c.set_id AND s.lang = c.lang
            WHERE \(conds.joined(separator: " AND "))
            ORDER BY \(hasReleaseDate ? "s.release_date" : "c.id") DESC LIMIT 800
            """
        let rows = db.query(sql, args).map { r in
            Row(id: r[0].string ?? "", lang: r[1].string ?? "", name: r[2].string ?? "", number: r[3].string ?? "",
                setId: r[4].string ?? "", setName: r[5].string ?? "", date: r[6].string ?? "")
        }
        func digits(_ s: String) -> String { String(s.drop { $0 == "0" }).uppercased() }
        func score(_ r: Row) -> Int {
            words.reduce(0) { acc, w in
                let name = r.name.lowercased()
                let num = String(w.split(separator: "/").first ?? Substring(w))
                let byName: Int
                if name == w { byName = 6 } else if name.hasPrefix(w) { byName = 5 }
                else if name.range(of: "(^|[^\\p{L}])" + NSRegularExpression.escapedPattern(for: w), options: .regularExpression) != nil { byName = 4 }
                else if name.contains(w) { byName = 2 } else { byName = 0 }
                let byNumber = num.contains(where: \.isNumber) && digits(r.number) == digits(num) ? 4 : 0
                let set = r.setId.lowercased()
                let bySet = set == w ? 4 : (set.hasPrefix(w) ? 3 : 0)
                let bySetName = r.setName.lowercased().contains(w) ? 1 : 0
                return acc + max(byName, byNumber, bySet, bySetName)
            }
        }
        let langRank = ["en": 0, "de": 1]
        var best: [String: Row] = [:]
        var order: [String] = []
        for r in rows {
            if let b = best[r.id] {
                let (sr, sb) = (score(r), score(b))
                if sr > sb || (sr == sb && (langRank[r.lang] ?? 2) < (langRank[b.lang] ?? 2)) { best[r.id] = r }
            } else { best[r.id] = r; order.append(r.id) }
        }
        let sorted = order.compactMap { best[$0] }.sorted { a, b in
            let (sa, sb) = (score(a), score(b))
            return sa != sb ? sa > sb : a.date > b.date
        }
        return sorted.prefix(limit).compactMap { cardExact($0.id, $0.lang) }
    }

    /// Card ids that are energy cards (commonOnly: just the cheap ones from booster packs).
    func energyIds(commonOnly: Bool) -> Set<String> {
        guard hasCategory else { return [] }
        var out = Set<String>()
        for r in db.query("SELECT id, rarity FROM card_info WHERE category = 'Energy'") {
            let rar = (r[1].string ?? "").trimmingCharacters(in: .whitespaces).lowercased()
            if !commonOnly || rar.isEmpty || rar == "common" || rar == "none" || rar == "c" { if let id = r[0].string { out.insert(id) } }
        }
        return out
    }

    private func query(_ id: String, _ lang: String?) -> CardInfo? {
        let sql = """
            SELECT c.id, c.lang, c.name, c.local_id, c.image_url, \(hasInfo ? "i.rarity" : "c.rarity"),
                   s.name, s.official, s.serie_name,
                   p.updated, p.trend, p.avg, p.low, p.avg30, p.trend_holo, p.avg_holo, p.low_holo, p.avg30_holo,
                   \(hasInfo ? "i.variants" : "NULL"), \(hasCategory ? "i.category" : "NULL"),
                   \(hasReleaseDate ? "s.release_date" : "NULL"),
                   \(hasAltImages ? "c.image_alt, c.image_alt_large" : "NULL, NULL")
            FROM cards c
            LEFT JOIN sets s ON s.id = c.set_id AND s.lang = c.lang
            LEFT JOIN prices p ON p.id = c.id
            \(hasInfo ? "LEFT JOIN card_info i ON i.id = c.id" : "")
            WHERE c.id = ? \(lang != nil ? "AND c.lang = ?" : "")
            ORDER BY CASE c.lang WHEN 'en' THEN 0 ELSE 1 END
            LIMIT 1
            """
        guard let c = db.query(sql, lang != nil ? [id, lang!] : [id]).first else { return nil }
        let hasPrice = !c[10].isNull || !c[11].isNull || !c[13].isNull || !c[14].isNull
        let prices: Prices? = hasPrice ? Prices(updated: c[9].string, trend: c[10].double, avg: c[11].double, low: c[12].double,
                                                avg30: c[13].double, trendHolo: c[14].double, avgHolo: c[15].double,
                                                lowHolo: c[16].double, avg30Holo: c[17].double) : nil
        let cardId = c[0].string ?? id
        let rarity = c[5].string
        let rate = usdPerEur
        let printings = Catalog.printings(variants: c[18].string, rarity: rarity, p: prices, tcg: tcgplayer(cardId)).map { pr -> Printing in
            var p = pr
            if let u = p.usd, let r = rate { p.usdEur = (u / r * 100).rounded() / 100 }
            return p
        }
        return CardInfo(id: cardId, lang: c[1].string ?? "", name: c[2].string ?? "?", number: c[3].string ?? "",
                        setName: c[6].string ?? "", setTotal: c[7].int, series: c[8].string ?? "", rarity: rarity,
                        imageUrl: c[4].string, prices: prices, printings: printings, category: c[19].string,
                        releaseDate: c[20].string.flatMap { $0.isEmpty ? nil : $0 }, imageAlt: c[21].string, imageAltLarge: c[22].string)
    }

    /// TCGplayer market price (USD) per printing key, e.g. normal, holofoil, reverse-holofoil, 1st-edition.
    private func tcgplayer(_ id: String) -> [String: Double] {
        guard hasTcg, let json = db.query("SELECT data FROM tcgplayer WHERE id = ?", [id]).first?.first?.string,
              let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return [:] }
        var out: [String: Double] = [:]
        for (k, v) in o {
            var p: Double?
            if let nested = v as? [String: Any] {
                p = ["marketPrice", "midPrice", "lowPrice"].lazy.compactMap { (nested[$0] as? NSNumber)?.doubleValue }.first { $0 > 0 }
            } else if let n = v as? NSNumber, n.doubleValue > 0 { p = n.doubleValue }
            if let p { out[k.lowercased()] = p }
        }
        return out
    }

    /**
     * Which printings exist and their prices. TCGplayer lists every printing it sells separately,
     * the more reliable "does a reverse holo exist" source; TCGdex variants fill in where it has none.
     */
    static func printings(variants: String?, rarity: String?, p: Prices?, tcg: [String: Double]) -> [Printing] {
        let v = variants.flatMap { (try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? [String: Any] }
        func has(_ k: String) -> Bool { (v?[k] as? Bool) == true || (v?[k] as? NSNumber)?.boolValue == true }
        let holoRarity = rarity?.range(of: "holo", options: .caseInsensitive) != nil
        let tcgNormal = tcg["normal"] ?? tcg["unlimited"]
        let tcgHolo = tcg["holofoil"] ?? tcg["unlimited-holofoil"]
        let tcgReverse = tcg["reverse-holofoil"] ?? tcg["reverseholofoil"]
        let tcgFirst = tcg["1st-edition-holofoil"] ?? tcg["1st-edition"] ?? tcg["1stedition"] ?? tcg["1st-edition-normal"]
        let fromTcg = !tcg.isEmpty
        let normal = fromTcg ? (tcgNormal != nil || (has("normal") && tcgHolo == nil)) : (has("normal") || (v == nil && !holoRarity))
        let holo = fromTcg ? tcgHolo != nil : (has("holo") || (v == nil && holoRarity))
        let reverse = has("reverse") || tcgReverse != nil
        let first = has("firstEdition") || tcgFirst != nil
        var out: [Printing] = []
        if normal { out.append(Printing(kind: .normal, eur: p?.headline, eur30: p?.avg30, eurLow: p?.low, usd: tcgNormal)) }
        if holo {
            // Cardmarket's standard price is the holo price when the card has no non-holo printing
            out.append(Printing(kind: .holo, eur: normal ? nil : p?.headline, eur30: normal ? nil : p?.avg30, eurLow: normal ? nil : p?.low, usd: tcgHolo))
        }
        if reverse { out.append(Printing(kind: .reverse, eur: p?.headlineHolo, eur30: p?.avg30Holo, eurLow: p?.lowHolo, usd: tcgReverse)) }
        if first { out.append(Printing(kind: .firstEdition, eur: nil, eur30: nil, eurLow: nil, usd: tcgFirst)) }
        if out.isEmpty { out.append(Printing(kind: .normal, eur: p?.headline, eur30: p?.avg30, eurLow: p?.low, usd: tcg.values.max())) }
        return out
    }
}
