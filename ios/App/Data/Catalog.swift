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
    /// the price check's finding for this card (nil: nothing found, or an older pack)
    var priceCheck: PriceCheck? = nil
    /// PSA prices per printing (eBay sales are split by printing); key nil = printing not stated
    var graded: [Printing.Kind?: Graded] = [:]
    /// PSA population (gem rate) per printing, if the data pack has it
    var population: [Printing.Kind?: Population] = [:]

    var year: String? { releaseDate.map { String($0.prefix(4)) }.flatMap { $0.count == 4 && $0.allSatisfy(\.isNumber) ? $0 : nil } }
    var numberText: String { if let t = setTotal, t > 0 { return "\(number)/\(t)" }; return number }
    var thumbnailUrl: String? { imageUrl.map { "\($0)/low.webp" } ?? imageAlt }
    var highUrl: String? { imageUrl.map { "\($0)/high.webp" } ?? imageAltLarge ?? imageAlt }
    var bestValue: Double? { printings.compactMap(\.value).max() }
    func printing(_ kind: Printing.Kind?) -> Printing? { printings.first { $0.kind == kind } ?? printings.first }

    /// PSA prices of this printing only; a row without a stated printing counts for the card's main printing
    func gradedFor(_ kind: Printing.Kind?) -> Graded? {
        graded[kind] ?? graded[nil].flatMap { kind == nil || kind == printings.first?.kind ? $0 : nil }
    }

    func populationFor(_ kind: Printing.Kind?) -> Population? {
        population[kind] ?? population[nil].flatMap { kind == nil || kind == printings.first?.kind ? $0 : nil }
    }
}

/// What the data pipeline's price check found (pricecheck.py): TCGdex links some cards to the wrong Cardmarket product.
struct PriceCheck: Equatable {
    enum Status { case dropped, uncertain, disagree }
    let status: Status
    /// the Cardmarket trend in question, EUR
    let cmTrend: Double?
    /// the card's TCGplayer prices (lowest and highest printing, not reverse holo), EUR
    let tpLo: Double?, tpHi: Double?
    /// the card(s) the Cardmarket price is shared with, e.g. "Plinfa 239/236"
    let others: [String]

    /// a sentence for under the price
    var message: String {
        switch status {
        case .dropped:
            return "No reliable Cardmarket price for this card" + (others.first.map { " (the database gave it the price of \($0))" } ?? "")
        case .uncertain:
            let names = others.prefix(2).joined(separator: ", ")
            return "This Cardmarket price is shared with " + (names.isEmpty ? "another card" : names) + " and may be theirs – check Cardmarket"
        case .disagree:
            return "Cardmarket and TCGplayer disagree" + (tpHi.map { " (TCGplayer ≈ " + String(format: "%.2f €", $0) + ")" } ?? "") + " – check before you pay"
        }
    }
}

/// PSA prices: eBay sold averages, USD.
struct Graded: Equatable {
    let psa10: Double?, psa10n: Int?, psa9: Double?, psa9n: Int?, psa8: Double?, psa8n: Int?
    let updated: String?
}

/// PSA population of one printing: how many copies were graded, how many got a 10 (gem rate).
struct Population: Equatable {
    let psaTotal: Int
    let psa10: Int?, psa9: Int?, psa8: Int?
    let gemRate: Double?
    let updated: String?
}

/**
 * A card's price history: one shared calendar (weekly for a year, then every day of the last 90
 * days), a series per price (NaN = no price that day), and the ECB's USD per EUR each day.
 */
struct PriceHistory {
    let dates: [String]
    let usdPerEur: [Double?]
    let series: [String: [Float]]
    var lastRate: Double? { usdPerEur.last { $0 != nil } ?? nil }

    /// Cardmarket trend (EUR) of a printing, picked like the price panel picks its price
    func eur(_ kind: Printing.Kind?, hasNormal: Bool) -> [Float]? {
        switch kind {
        case .reverse: return series["cm_trend_h"]
        case .holo: return hasNormal ? nil : series["cm_trend"]
        case .firstEdition: return nil
        default: return series["cm_trend"]
        }
    }

    /// TCGplayer market price (USD) of a printing
    func usd(_ kind: Printing.Kind?) -> [Float]? {
        switch kind {
        case .holo: return series["tp_holo"]
        case .reverse: return series["tp_reverse"]
        case .firstEdition: return series["tp_first"]
        default: return series["tp_normal"]
        }
    }

    /// TCGplayer converted to EUR at each day's ECB rate (the latest rate where a day has none)
    func usdInEur(_ kind: Printing.Kind?) -> [Float]? {
        guard let a = usd(kind) else { return nil }
        let fallback = lastRate
        return a.indices.map { i in
            let r = (i < usdPerEur.count ? usdPerEur[i] : nil) ?? fallback
            if let r, r > 0, !a[i].isNaN { return Float(Double(a[i]) / r) }
            return .nan
        }
    }

    /**
     * zlib-compressed little-endian values (the pipeline's history.write_app_history): float16, or
     * float32 for a series of size values with a price above float16's range (65,504).
     */
    static func decode(_ blob: Data, size: Int) -> [Float] {
        guard let raw = Inflate.zlib(blob, capacity: max(size * 4, blob.count * 8)) else { return [] }
        if size > 0 && raw.count == size * 4 {
            return raw.withUnsafeBytes { b in (0..<size).map { Float(bitPattern: UInt32(littleEndian: b.loadUnaligned(fromByteOffset: $0 * 4, as: UInt32.self))) } }
        }
        let n = raw.count / 2
        return raw.withUnsafeBytes { b in (0..<n).map { Float(Float16(bitPattern: UInt16(littleEndian: b.loadUnaligned(fromByteOffset: $0 * 2, as: UInt16.self)))) } }
    }
}

/// The card catalog of the data pack (port of Catalog.kt).
final class Catalog {
    private let db: SQLiteDB
    private var cache: [String: CardInfo?] = [:]
    private var groupCache: [String: [String]] = [:]
    private let lock = NSLock()
    private let hasInfo: Bool, hasTcg: Bool, hasReleaseDate: Bool, hasAltImages: Bool, hasGroups: Bool
    private let hasGraded: Bool, gradedByPrinting: Bool, hasPopulation: Bool, hasPriceCheck: Bool, hasHistory: Bool, hasDex: Bool
    let hasCategory: Bool
    /// USD per EUR of the data pack's day (ECB), for showing TCGplayer prices in euros
    private let usdPerEur: Double?
    /// the history calendar and the euro rate on each of its days (nil: the pack has no history)
    private let historyCalendar: (dates: [String], rates: [Double?])?

    init(db: SQLiteDB) {
        self.db = db
        let info = db.tableExists("card_info")
        hasInfo = info
        hasTcg = db.tableExists("tcgplayer")
        hasCategory = info && db.columns("card_info").contains("category")
        hasReleaseDate = db.columns("sets").contains("release_date")
        hasAltImages = db.columns("cards").contains("image_alt")
        hasGroups = db.columns("entries").contains("grp")
        let graded = db.tableExists("graded")
        hasGraded = graded
        gradedByPrinting = graded && db.columns("graded").contains("printing")
        hasPopulation = db.tableExists("population")
        hasPriceCheck = db.tableExists("price_check")
        hasHistory = db.tableExists("history")
        hasDex = info && db.columns("card_info").contains("dex")
        func metaValue(_ key: String) -> String? { db.query("SELECT value FROM meta WHERE key = ?", [key]).first?.first?.string }
        usdPerEur = metaValue("usd_per_eur").flatMap { Double($0) }.flatMap { $0 > 0 ? $0 : nil }
        if db.tableExists("history"), let d = metaValue("history_dates") {
            let dates = d.split(separator: ",").map(String.init)
            let rates = (metaValue("history_rates") ?? "").split(separator: ",", omittingEmptySubsequences: false).map { Double($0) }
            historyCalendar = (dates, dates.indices.map { $0 < rates.count ? rates[$0] : nil })
        } else {
            historyCalendar = nil
        }
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
                        releaseDate: c[20].string.flatMap { $0.isEmpty ? nil : $0 }, imageAlt: c[21].string, imageAltLarge: c[22].string,
                        priceCheck: priceCheck(cardId, c[1].string), graded: graded(cardId), population: population(cardId))
    }

    /// The price check's finding for a card, with the other cards named in lang where possible.
    private func priceCheck(_ id: String, _ lang: String?) -> PriceCheck? {
        guard hasPriceCheck, let r = db.query("SELECT status, cm_trend, tp_lo, tp_hi, others FROM price_check WHERE id = ?", [id]).first else { return nil }
        let status: PriceCheck.Status
        switch r[0].string {
        case "dropped": status = .dropped
        case "uncertain": status = .uncertain
        case "disagree": status = .disagree
        default: return nil
        }
        let others = (r[4].string ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.map { other -> String in
            guard let o = db.query("""
                SELECT c.name, c.local_id, s.official FROM cards c LEFT JOIN sets s ON s.id = c.set_id AND s.lang = c.lang
                WHERE c.id = ? ORDER BY CASE c.lang WHEN ? THEN 0 WHEN 'en' THEN 1 ELSE 2 END LIMIT 1
                """, [other, lang ?? "en"]).first else { return other }
            let total = o[2].int
            return "\(o[0].string ?? "") \(o[1].string ?? "")" + ((total ?? 0) > 0 ? "/\(total!)" : "")
        }
        return PriceCheck(status: status, cmTrend: r[1].double, tpLo: r[2].double, tpHi: r[3].double, others: others)
    }

    private static func kindOf(_ code: String?) -> Printing.Kind? {
        switch code {
        case "normal": return .normal
        case "holo": return .holo
        case "reverse": return .reverse
        case "1st": return .firstEdition
        default: return nil
        }
    }

    private func graded(_ id: String) -> [Printing.Kind?: Graded] {
        guard hasGraded else { return [:] }
        var out: [Printing.Kind?: Graded] = [:]
        for c in db.query("SELECT \(gradedByPrinting ? "printing" : "''"), psa10, psa10_n, psa9, psa9_n, psa8, psa8_n, updated FROM graded WHERE id = ?", [id]) {
            out[Self.kindOf(c[0].string)] = Graded(psa10: c[1].double, psa10n: c[2].int, psa9: c[3].double, psa9n: c[4].int,
                                                   psa8: c[5].double, psa8n: c[6].int, updated: c[7].string)
        }
        return out
    }

    private func population(_ id: String) -> [Printing.Kind?: Population] {
        guard hasPopulation else { return [:] }
        var out: [Printing.Kind?: Population] = [:]
        for c in db.query("SELECT printing, psa_total, psa10, psa9, psa8, gem_rate, updated FROM population WHERE id = ?", [id]) {
            guard let total = c[1].int else { continue }
            out[Self.kindOf(c[0].string)] = Population(psaTotal: total, psa10: c[2].int, psa9: c[3].int, psa8: c[4].int,
                                                       gemRate: c[5].double, updated: c[6].string)
        }
        return out
    }

    /// The price history of a card (nil: none in this data pack).
    func history(_ id: String) -> PriceHistory? {
        guard let cal = historyCalendar else { return nil }
        var series: [String: [Float]] = [:]
        for r in db.query("SELECT field, data FROM history WHERE id = ?", [id]) {
            guard let f = r[0].string, let blob = r[1].data else { continue }
            let a = PriceHistory.decode(blob, size: cal.dates.count)
            if a.count == cal.dates.count { series[f] = a }
        }
        return series.isEmpty ? nil : PriceHistory(dates: cal.dates, usdPerEur: cal.rates, series: series)
    }

    /**
     * Cards in langs with the printed number num (normalised: "5", "TG5") in a set of total cards
     * (nil: any set): the Asian prints that have no picture, so the camera can't find them.
     */
    func byNumber(_ langs: [String], _ num: String, _ total: String?) -> [CardInfo] {
        if num.isEmpty || langs.isEmpty { return [] }
        guard let last = num.matches(of: #/\d+/#).last else { return [] }
        var digits = String(String(num[last.range]).drop { $0 == "0" })
        if digits.isEmpty { digits = "0" }
        let q = "SELECT c.id, c.lang, c.local_id FROM cards c JOIN sets s ON s.id = c.set_id AND s.lang = c.lang " +
            "WHERE c.lang IN (\(langs.map { _ in "?" }.joined(separator: ", "))) AND c.local_id LIKE ?" +
            (total != nil ? " AND (s.official = ? OR s.total = ?)" : "")
        let args = langs + ["%" + digits] + (total.map { [$0, $0] } ?? [])
        return db.query(q, args).compactMap { c in
            guard let id = c[0].string, let lang = c[1].string, TextReader.normNumber(c[2].string) == num else { return nil }
            return cardExact(id, lang)
        }
    }

    /// The card's National Pokédex numbers (empty: not a Pokémon, or a data pack without them).
    func dexOf(_ id: String) -> Set<Int> {
        guard hasDex, let d = db.query("SELECT dex FROM card_info WHERE id = ?", [id]).first?.first?.string else { return [] }
        return Set(d.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) })
    }

    /// The Pokédex numbers of an artwork's cards (the recognised picture's Pokémon).
    func dexOfGroup(_ group: String) -> Set<Int> { groupMembers(group).reduce(into: Set<Int>()) { $0.formUnion(dexOf($1)) } }

    /// The names the artwork's Pokémon has on Asian prints with a picture (e.g. "メガニウム" for Meganium).
    func asianNames(_ englishName: String, _ langs: [String]) -> Set<String> {
        guard hasGroups, !langs.isEmpty else { return [] }
        let q = "SELECT DISTINCT a.name FROM cards w JOIN entries f ON f.card_id = w.id AND f.lang = 'en' " +
            "JOIN entries e ON e.grp = f.grp JOIN cards a ON a.id = e.card_id AND a.lang = e.lang " +
            "WHERE w.lang = 'en' AND w.name = ? AND a.lang IN (\(langs.map { _ in "?" }.joined(separator: ", "))) LIMIT 20"
        return Set(db.query(q, [englishName] + langs).compactMap { $0[0].string })
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
