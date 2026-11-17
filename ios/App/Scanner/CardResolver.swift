import Foundation
import UIKit

/**
 * Which exact card (set, language) of an artwork group a photo shows, and what the result panel
 * shows for it (ports of resolveVersion / build / candidateOf in the Android app's ScannerViewModel).
 */
final class CardResolver {
    static let asian: Set<String> = ["ja", "ko", "zh-tw", "zh-cn", "th"]

    let catalog: Catalog
    let pack: Pack

    init(catalog: Catalog, pack: Pack) {
        self.catalog = catalog
        self.pack = pack
    }

    /**
     * Asian prints (Japanese, Korean, Chinese) are only chosen when the printed script says so;
     * otherwise the western version is shown, even when the picture matched an Asian scan best. An
     * Asian print without a picture (most of them) is found by its printed number and the name in its
     * own script.
     */
    func resolveVersion(group: String, entry: Int?, text: CardText?) -> CardInfo? {
        let cat = catalog
        let versions = cat.versions(group)
        if versions.isEmpty { return cat.card(group, "en") }
        let entryCard = entry.map { pack.entryCard[$0] }
        let entryLang = entry.map { pack.entryLang[$0] }
        let num = TextReader.normNumber(text?.number)
        let total = text?.total.map { TextReader.normNumber($0) }
        // exact number first; then one easily confused digit (6/8, 1/7 ...) if the set size agrees
        func numberMatches(_ cid: String, _ lang: String, fuzzy: Bool) -> Bool {
            guard let c = cat.cardExact(cid, lang) else { return false }
            let n = TextReader.normNumber(c.number)
            let setTotal = c.setTotal.map { TextReader.normNumber(String($0)) }
            if !fuzzy {
                let totalOk = total == nil || c.setTotal == nil || setTotal == total
                    || (Int(c.number) ?? 0) > (c.setTotal ?? Int.max)        // secret rares: number above the total
                return n == num && totalOk
            }
            return total != nil && setTotal != nil && setTotal == total && TextReader.numberClose(n, num)
        }
        func byNumber(_ pool: [(id: String, lang: String)]) -> String? {
            if num.isEmpty { return nil }
            // the card whose scan matched the picture comes first when several sets have this number
            var ids: [String] = []
            for id in [entryCard].compactMap({ $0 }) + pool.map(\.id) where !ids.contains(id) && pool.contains(where: { $0.id == id }) { ids.append(id) }
            func m(_ cid: String, _ fuzzy: Bool) -> Bool { pool.contains { $0.id == cid && numberMatches(cid, $0.lang, fuzzy: fuzzy) } }
            let exact = ids.first { m($0, false) }
            // a bare number without "/total" is weak: it may confirm the matched card, but not move to another set
            if let exact, exact != entryCard, total == nil, num.allSatisfy(\.isNumber) { return nil }
            return exact ?? ids.first { m($0, true) }
        }
        // Asian print: only when the printed script says so
        let asianLangs: [String]
        switch text?.lang {
        case "ja": asianLangs = ["ja"]
        case "ko": asianLangs = ["ko"]
        case "zh-tw": asianLangs = ["zh-tw", "zh-cn"]
        case "zh-cn": asianLangs = ["zh-cn", "zh-tw"]
        default: asianLangs = []
        }
        if !asianLangs.isEmpty {
            let pool = versions.filter { asianLangs.contains($0.lang) }
            func pick(_ id: String) -> CardInfo? {
                let langs = pool.filter { $0.id == id }.map(\.lang)
                let lang = (entryCard == id ? entryLang.flatMap { langs.contains($0) ? $0 : nil } : nil) ?? asianLangs.first { langs.contains($0) }
                return lang.flatMap { cat.cardExact(id, $0) }
            }
            // a print with a picture and this number; else one without a picture (number and name); else the picture's
            if let id = byNumber(pool) { return pick(id) }
            if let c = asianWithoutPicture(group: group, langs: asianLangs, num: num, total: total, text: text) { return c }
            // Korean prints aren't in the card data; their sets are the Japanese ones: the Japanese original is shown
            if text?.lang == "ko", let c = asianWithoutPicture(group: group, langs: ["ja"], num: num, total: total, text: nil) { return c }
            if !pool.isEmpty {
                var ids: [String] = []
                for p in pool where !ids.contains(p.id) { ids.append(p.id) }
                return pick(entryCard.flatMap { ids.contains($0) ? $0 : nil } ?? ids[0])
            }
        }
        // western print (also when the text could not be read)
        let western = versions.filter { !Self.asian.contains($0.lang) }
        let pool = western.isEmpty ? versions : western
        var ids: [String] = []
        for p in pool where !ids.contains(p.id) { ids.append(p.id) }
        let id = byNumber(pool) ?? entryCard.flatMap { ids.contains($0) ? $0 : nil } ?? (ids.contains(group) ? group : ids[0])
        // language: printed words + name, among the languages this card exists in
        var langs: [String] = []
        for p in pool where p.id == id && !langs.contains(p.lang) { langs.append(p.lang) }
        let names = Set(langs.compactMap { cat.cardExact(id, $0)?.name })
        var scores: [(String, Double)] = []
        for l in langs {
            var sc = text?.langScores[l] ?? 0
            if let text, let name = cat.cardExact(id, l)?.name, names.count > 1, !text.nameText.trimmingCharacters(in: .whitespaces).isEmpty {
                sc += 2 * TextReader.nameSimilarity(text.nameText, name)
            }
            scores.append((l, sc))
        }
        // the highest score, the first language on a tie (Kotlin's maxByOrNull)
        var best: (String, Double)?
        for s in scores where best == nil || s.1 > best!.1 { best = s }
        let bestLang = best.flatMap { $0.1 > 0 ? $0.0 : nil }
            ?? (langs.contains("en") ? "en" : nil) ?? entryLang.flatMap { langs.contains($0) ? $0 : nil } ?? langs[0]
        return cat.cardExact(id, bestLang) ?? cat.card(id, "en")
    }

    /// An Asian print without a picture: by the printed number (and set size), the picture's Pokémon and the name in its own script.
    func asianWithoutPicture(group: String, langs: [String], num: String, total: String?, text: CardText?) -> CardInfo? {
        if num.isEmpty { return nil }
        let cat = catalog
        let cands = cat.byNumber(langs, num, total)
        if cands.isEmpty { return nil }
        // the same Pokémon as the picture: one print left is the card
        let dex = cat.dexOfGroup(group)
        let sameDex = dex.isEmpty ? [] : cands.filter { !cat.dexOf($0.id).isDisjoint(with: dex) }
        let known = cat.englishOf(group).map { cat.asianNames($0.name, langs) } ?? []
        if sameDex.count == 1 { return sameDex[0] }
        return TextReader.pickPrint(sameDex.isEmpty ? cands : sameDex, printedName: text?.nameText, known: known, totalKnown: total != nil)
    }

    func build(group: String, version: CardInfo, text: CardText?, confidence: Confidence, alternatives: [Candidate],
               locked: Bool, fromPhoto: Bool, scan: UIImage?, serial: Int) -> Shown {
        let cat = catalog
        let english = version.lang == "en" ? nil : (cat.cardExact(version.id, "en") ?? cat.englishOf(group))
        let hasOwnPrice = version.printings.contains { $0.value != nil }
        let priceCard = hasOwnPrice || english == nil ? version : english!
        let raw = cat.versions(group)
        let sorted = raw.filter { !Self.asian.contains($0.lang) } + raw.filter { Self.asian.contains($0.lang) }
        var all = sorted.compactMap { cat.cardExact($0.id, $0.lang) }
        if !all.contains(where: { $0.id == version.id && $0.lang == version.lang }) { all.insert(version, at: 0) }
        let langs = all.filter { ($0.id == version.id || Self.asian.contains($0.lang)) && ($0.lang != version.lang || $0.id != version.id) }
        return Shown(group: group, version: version, english: english, priceCard: priceCard, printing: defaultPrinting(priceCard),
                     confidence: confidence, alternatives: alternatives, languages: langs, text: text, locked: locked,
                     fromPhoto: fromPhoto, serial: serial, versions: all, scan: scan, history: cat.history(priceCard.id))
    }

    /// A hit as a card to pick: the version the scanner would show for it, with a picture.
    func candidate(of h: Match) -> Candidate? {
        let cat = catalog
        guard let info = resolveVersion(group: h.group, entry: h.entry, text: nil) ?? cat.card(pack.entryCard[h.entry], pack.entryLang[h.entry]) else { return nil }
        let image = info.thumbnailUrl ?? cat.versions(h.group).lazy.compactMap { cat.cardExact($0.id, $0.lang)?.thumbnailUrl }.first
        return Candidate(group: h.group, info: info, score: h.score, reading: h.readingName, image: image)
    }

    /// Cards matching the search words, with a picture to show.
    func search(_ query: String) -> [Candidate] {
        let cat = catalog
        return cat.search(query).map { info in
            let group = cat.groupOf(info.id)
            let image = info.thumbnailUrl ?? cat.cardExact(info.id, "en")?.thumbnailUrl
                ?? cat.versions(group).lazy.compactMap { cat.cardExact($0.id, $0.lang)?.thumbnailUrl }.first
            return Candidate(group: group, info: info, score: 0, reading: "search", image: image)
        }
    }
}
