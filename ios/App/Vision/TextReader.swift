import CoreGraphics
import Foundation
import Vision

/// What the card's printed text says about which version it is.
struct CardText: Equatable {
    /// card number as printed, e.g. "037" and total "167"
    var number: String?
    var total: String?
    /// most likely language and how sure (score gap to the runner-up, 0..)
    var lang: String?
    var langMargin: Double
    var langScores: [String: Double]
    /// the name bar text, for matching names
    var nameText: String
    var text: String
    /// what the script check saw (details view): "kana 120 · kanji 40 · hangul 3"
    var script: String = ""
    /// the card's name as printed: the largest line of the name bar, without "HP 160"
    var title: String = ""
}

/**
 * Reads a straightened card with Apple's on-device text recognition (Vision) to find its number
 * (037/167) and its language; a port of the Android app's VersionResolver (ML Kit there).
 * Western languages come from the words every card prints ("Weakness" / "Schwäche" /
 * "Faiblesse"..., HP / KP / PV / PS) and from the name. Without those words the printed script
 * decides: the Japanese and the Korean reader look at the card, and the one that sees more of its
 * own script wins (characters weighted by the reader's confidence): Hangul is Korean, kana
 * Japanese, Chinese characters without kana Chinese - traditional or simplified by the characters
 * only one of the two uses.
 */
final class TextReader {
    /// one line of text: its words, confidence and box in pixels (y from the top)
    struct Line { let text: String; let confidence: Double; let box: CGRect }

    private static let western = ["en-US", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-BR"]

    func recognise(_ image: CGImage, languages: [String]) -> [Line] {
        let req = VNRecognizeTextRequest()
        req.recognitionLevel = .accurate
        req.usesLanguageCorrection = false
        req.recognitionLanguages = languages
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        do { try handler.perform([req]) } catch {
            NSLog("TextReader: %@", error.localizedDescription)
            return []
        }
        let w = CGFloat(image.width), h = CGFloat(image.height)
        return (req.results ?? []).compactMap { o in
            guard let c = o.topCandidates(1).first else { return nil }
            let b = o.boundingBox
            return Line(text: c.string, confidence: Double(c.confidence),
                        box: CGRect(x: b.minX * w, y: (1 - b.maxY) * h, width: b.width * w, height: b.height * h))
        }
    }

    /** detectAsian: check the printed script when the western words are missing (false: western languages only) */
    func read(_ card: CGImage, detectAsian: Bool = true) -> CardText {
        var lines = recognise(card, languages: Self.western)
        var asian: String?
        var asianChars = 0
        var scriptInfo = ""
        // a western card prints "Weakness", "Schwäche" …; an Asian card doesn't
        let westernScore = Self.westernScore(lines.map(\.text).joined(separator: "\n"))
        if detectAsian && westernScore < Self.westernSure {
            let s = asianScript(card)
            scriptInfo = s.info
            if let lang = s.lang, !s.lines.isEmpty {
                lines = s.lines
                asian = lang
                asianChars = s.chars
            }
        }
        if lines.isEmpty { return CardText(number: nil, total: nil, lang: nil, langMargin: 0, langScores: [:], nameText: "", text: "", script: scriptInfo) }
        var result = Self.analyse(lines, height: Double(card.height), asian: asian, asianChars: asianChars)
        result.script = scriptInfo
        // the set number is tiny: read the bottom strip again, enlarged
        if let strip = readNumberStrip(card) { result.number = strip.0; result.total = strip.1 }
        return result
    }

    private struct Script { let lang: String?; let lines: [Line]; let chars: Int; let info: String }

    private func asianScript(_ img: CGImage) -> Script {
        let jt = recognise(img, languages: ["ja-JP"])
        let kt = recognise(img, languages: ["ko-KR"])
        let kana = Self.weighted(jt, Self.isKana)
        let han = Self.weighted(jt, Self.isHan)
        let hangul = Self.weighted(kt, Self.isHangul)
        let seen = String(format: "kana %.0f · kanji %.0f · hangul %.0f", kana, han, hangul)
        // each reader turns the other script into a few of its own characters; the real one sees far more
        if hangul >= Self.minScript && hangul > kana + han { return Script(lang: "ko", lines: kt, chars: Int(hangul), info: "\(seen) → Korean") }
        if kana >= 3 && kana + han >= Self.minScript && kana >= 0.15 * han {
            return Script(lang: "ja", lines: jt, chars: Int(kana + han), info: "\(seen) → Japanese")
        }
        if han >= Self.minScript && han > hangul {
            // Chinese: the Chinese reader knows both character sets; which one is printed
            let ct = recognise(img, languages: ["zh-Hant", "zh-Hans"])
            let read = (ct.isEmpty ? jt : ct).map(\.text).joined()
            let trad = read.filter { Self.traditional.contains($0) }.count
            let simp = read.filter { Self.simplified.contains($0) }.count
            let lang = simp > trad ? "zh-cn" : "zh-tw"
            return Script(lang: lang, lines: ct.isEmpty ? jt : ct, chars: Int(han),
                          info: "\(seen) · traditional \(trad) · simplified \(simp) → " + (lang == "zh-cn" ? "Chinese (simplified)" : "Chinese (traditional)"))
        }
        return Script(lang: nil, lines: [], chars: 0, info: seen)
    }

    /// characters of one script, each counted with its line's confidence
    private static func weighted(_ lines: [Line], _ script: (Character) -> Bool) -> Double {
        lines.reduce(0) { acc, l in acc + Double(l.text.filter(script).count) * (l.confidence > 0 ? l.confidence : 0.6) }
    }

    private func readNumberStrip(_ card: CGImage) -> (String, String)? {
        let y0 = Int(Double(card.height) * 0.86)
        guard let crop = card.cropping(to: CGRect(x: 0, y: y0, width: card.width, height: card.height - y0)) else { return nil }
        let bw = Int(Double(crop.width) * 2.5), bh = Int(Double(crop.height) * 2.5)
        guard let ctx = CGContext(data: nil, width: bw, height: bh, bitsPerComponent: 8, bytesPerRow: bw * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(crop, in: CGRect(x: 0, y: 0, width: bw, height: bh))
        guard let big = ctx.makeImage() else { return nil }
        for line in recognise(big, languages: ["en-US"]) {
            if let m = Self.firstMatch(Self.number, line.text.replacingOccurrences(of: " ", with: "")) {
                return (Self.cleanNumber(m[1], m[2]), m[2])
            }
        }
        return nil
    }

    static func analyse(_ lines: [Line], height: Double, asian: String?, asianChars: Int) -> CardText {
        let all = lines.map(\.text).joined(separator: "\n")
        // card number: the lowest "123/456" on the card
        var number: String?, total: String?
        var bestY = -1.0
        for l in lines {
            guard let m = firstMatch(Self.number, l.text.replacingOccurrences(of: " ", with: "")) else { continue }
            let y = Double(l.box.midY)
            if y > bestY { bestY = y; number = cleanNumber(m[1], m[2]); total = m[2] }
        }
        if number == nil, let m = firstMatch(promo, all.replacingOccurrences(of: " ", with: "")) { number = m[0] }
        // name bar: the upper 16% of the card; the name is its largest line
        let bar = lines.filter { Double($0.box.midY) < height * 0.16 }
        let nameText = bar.map(\.text).joined(separator: " ")
        var title = bar.max(by: { $0.box.height < $1.box.height })?.text ?? ""
        title = title.replacingOccurrences(of: "(?i)\\bHP\\s*\\d+", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\s\\d{2,3}\\s*$", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)

        var scores: [String: Double] = [:]
        let norm = " " + fold(all) + " "
        for (lang, words) in keywords {
            var s = 0.0
            for w in words where norm.contains(" \(fold(w)) ") || (w.count >= 6 && norm.contains(fold(w))) { s += weight[w] ?? 1 }
            scores[lang] = s
        }
        // the HP label glued to the number: "HP80", "80 KP", "PV 120"
        for m in allMatches(hp, norm) {
            let label = m[1].isEmpty ? m[2] : m[1]
            for (lang, w) in hpLangs[label] ?? [] { scores[lang, default: 0] += w }
        }
        if let asian { scores[asian] = 3.0 + Double(asianChars) / 10.0 }
        // highest first; on a tie the order of the keyword table (en, de, fr, …) as on Android
        let ranked = scores.sorted { a, b in a.value != b.value ? a.value > b.value : langOrder(a.key) < langOrder(b.key) }
        let top = ranked.first.flatMap { $0.value > 0 ? $0 : nil }
        let margin = top.map { $0.value - (ranked.count > 1 ? ranked[1].value : 0) } ?? 0
        return CardText(number: number, total: total, lang: top?.key, langMargin: margin, langScores: scores,
                        nameText: nameText, text: all, title: title)
    }

    private static func langOrder(_ l: String) -> Int { keywords.firstIndex { $0.0 == l } ?? keywords.count }

    // ------------------------------------------------------------------ the rules (as VersionResolver.kt)

    private static let number = try! NSRegularExpression(pattern: "([A-Z]{0,4}\\d{1,3}[a-z]?)/([A-Z]{0,4}\\d{1,3})")
    private static let promo = try! NSRegularExpression(pattern: "\\b(SWSH|SM|XY|BW|SVP|SV|DP|HGSS)\\d{2,3}\\b")
    private static let hp = try! NSRegularExpression(pattern: "(?:\\b(hp|kp|pv|ps) ?\\d{2,3}\\b)|(?:\\b\\d{2,3} ?(hp|kp|pv|ps)\\b)")
    private static let hpLangs: [String: [(String, Double)]] = [
        "hp": [("en", 1.5)], "kp": [("de", 1.5)], "pv": [("fr", 1.5)], "ps": [("es", 0.5), ("it", 0.5), ("pt", 0.5)],
    ]

    /// words printed on every card, per language (accents are ignored when comparing)
    static let keywords: [(String, [String])] = [
        ("en", ["weakness", "resistance", "retreat", "basic", "stage 1", "stage 2", "evolves from", "hp",
                "illus", "ability", "trainer", "supporter", "item", "pokemon tool", "stadium"]),
        ("de", ["schwäche", "resistenz", "rückzug", "basis", "phase 1", "phase 2", "entwickelt sich aus", "kp",
                "fähigkeit", "unterstützer", "pokémon-ausrüstung", "stadion", "trainer"]),
        ("fr", ["faiblesse", "résistance", "retraite", "de base", "niveau 1", "niveau 2", "évolue de", "pv",
                "talent", "dresseur", "supporter", "objet", "outil pokémon", "stade"]),
        ("es", ["debilidad", "resistencia", "retirada", "básico", "fase 1", "fase 2", "evoluciona de", "ps",
                "habilidad", "entrenador", "partidario", "objeto", "herramienta pokémon", "estadio"]),
        ("it", ["debolezza", "resistenza", "ritirata", "base", "fase 1", "fase 2", "si evolve da", "ps",
                "abilità", "allenatore", "aiuto", "strumento", "oggetto pokémon", "stadio"]),
        ("pt", ["fraqueza", "resistência", "recuo", "básico", "estágio 1", "estágio 2", "evolui de", "ps",
                "habilidade", "treinador", "apoiador", "item", "ferramenta pokémon", "estádio"]),
    ]

    /// words shared by several languages count less
    private static let weight: [String: Double] = {
        var counts: [String: Int] = [:]
        for (_, ws) in keywords { for w in ws { counts[fold(w), default: 0] += 1 } }
        var out: [String: Double] = [:]
        for (_, ws) in keywords { for w in ws { out[w] = 1.0 / Double(counts[fold(w)] ?? 1) } }
        return out
    }()

    /// lower case, accents off, anything but a-z 0-9 / and space as a space
    static func fold(_ s: String) -> String {
        let decomposed = s.lowercased().decomposedStringWithCanonicalMapping
        var out = ""
        var lastSpace = false
        for u in decomposed.unicodeScalars {
            if u.properties.generalCategory == .nonspacingMark { continue }
            let keep = (u >= "a" && u <= "z") || (u >= "0" && u <= "9") || u == "/" || u == " "
            if keep && u != " " { out.unicodeScalars.append(u); lastSpace = false }
            else if !lastSpace { out.append(" "); lastSpace = true }
        }
        return out
    }

    // the scripts as Java's Character.UnicodeScript has them (what the Android app counts)
    static func isKana(_ c: Character) -> Bool {
        guard let v = c.unicodeScalars.first?.value else { return false }
        return (0x3041...0x3096).contains(v) || (0x309D...0x309F).contains(v) || (0x30A1...0x30FA).contains(v)
            || (0x30FD...0x30FF).contains(v) || (0x31F0...0x31FF).contains(v) || (0xFF66...0xFF6F).contains(v) || (0xFF71...0xFF9D).contains(v)
    }

    static func isHan(_ c: Character) -> Bool {
        guard let v = c.unicodeScalars.first?.value else { return false }
        return (0x4E00...0x9FFF).contains(v) || (0x3400...0x4DBF).contains(v) || (0xF900...0xFAFF).contains(v)
            || (0x20000...0x323AF).contains(v) || (0x2E80...0x2FD5).contains(v) || v == 0x3005 || v == 0x3007
            || (0x3021...0x3029).contains(v) || (0x3038...0x303B).contains(v)
    }

    static func isHangul(_ c: Character) -> Bool {
        guard let v = c.unicodeScalars.first?.value else { return false }
        return (0xAC00...0xD7A3).contains(v) || (0x1100...0x11FF).contains(v) || (0x3131...0x318E).contains(v)
            || (0xA960...0xA97C).contains(v) || (0xD7B0...0xD7FB).contains(v) || (0x3200...0x321E).contains(v)
            || (0x3260...0x327E).contains(v) || (0xFFA0...0xFFDC).contains(v) || v == 0x302E || v == 0x302F
    }

    /// below this many (confidence-weighted) characters, a script isn't really on the card
    private static let minScript = 8.0
    /// the western words that make the script check unnecessary (HP and "Illus." are printed on Asian cards too)
    private static let westernSure = 2.0
    private static let generic: Set<String> = ["hp", "kp", "pv", "ps", "illus"]

    /// How much of the words every western card prints the reader found.
    static func westernScore(_ text: String) -> Double {
        let norm = " " + fold(text) + " "
        var s = 0.0
        for (_, words) in keywords {
            for w in words where !generic.contains(fold(w)) {
                if norm.contains(" \(fold(w)) ") || (w.count >= 6 && norm.contains(fold(w))) { s += weight[w] ?? 1 }
            }
        }
        return s
    }

    /// Characters only traditional (Taiwan, Hong Kong) or only simplified (mainland) Chinese uses.
    static let traditional: Set<Character> = Set("寶夢進對擊傷點張從時個會這為與場戰鬥選擇數後備棄換隻復狀態氣類屬擁計錢幣給將階變隨機傳說讓種號圖鑑體輕價現開還準卻頭麼們來過當連續於應該發樣邊雙歡戲壓術絕滿獎勵輪")
    static let simplified: Set<Character> = Set("宝梦进对击伤点张从时个会这为与场战选择数备弃换复状态气类属拥计钱币给将阶变随机传说让种号图鉴体轻价现开还却头们来过当连续应该发样边双欢戏压术绝满奖励轮")
        .subtracting(traditional)

    /// '037' -> '37', 'TG05' -> 'TG5'
    static func normNumber(_ s: String?) -> String {
        guard let s = s?.trimmingCharacters(in: .whitespaces) else { return "" }
        guard let m = firstMatch(try! NSRegularExpression(pattern: "^([A-Za-z]*)0*(\\d+)([a-zA-Z]?)$"), s) else { return s.uppercased() }
        return m[1].uppercased() + m[2] + m[3].lowercased()
    }

    /**
     * The set code is printed right before the number ("TWM 034/167") and runs into it once spaces
     * are dropped. With a plain-digit total, the number is plain digits too (letters stay for subsets
     * like TG05/TG30).
     */
    static func cleanNumber(_ number: String, _ total: String) -> String {
        if total.allSatisfy(\.isNumber) && number.contains(where: \.isLetter) {
            return number.range(of: "\\d+[a-z]?$", options: .regularExpression).map { String(number[$0]) } ?? number
        }
        return number
    }

    /// digit pairs text recognition mixes up on small print
    private static let confusable: Set<String> = ["68", "69", "89", "08", "38", "56", "17", "09", "06", "35", "13", "47", "58"]

    /// true if two normalised numbers differ in at most one easily confused digit
    static func numberClose(_ a: String, _ b: String) -> Bool {
        if a == b { return true }
        if a.isEmpty || a.count != b.count { return false }
        var diff = 0
        for (x, y) in zip(a, b) where x != y {
            let pair = String([min(x, y), max(x, y)])
            if !confusable.contains(pair) { return false }
            diff += 1
        }
        return diff <= 1
    }

    /**
     * Of the prints with the read number, the one whose name is on the card (printedName, the name
     * bar in its own script); with several, the longest name found wins; a name the Pokémon has on
     * other prints (known) counts too. Without a readable name: a single print of that number and
     * set size (totalKnown).
     */
    static func pickPrint(_ cands: [CardInfo], printedName: String?, known: Set<String>, totalKnown: Bool) -> CardInfo? {
        if cands.isEmpty { return nil }
        func norm(_ s: String?) -> String { (s ?? "").precomposedStringWithCompatibilityMapping.lowercased().filter { $0.isLetter || $0.isNumber } }
        let printed = norm(printedName)
        let knownN = Set(known.map(norm))
        func score(_ c: CardInfo) -> Int {
            let n = norm(c.name)
            if n.isEmpty { return 0 }
            return (printed.contains(n) ? 10 + n.count : 0) + (knownN.contains(n) ? 5 : 0)
        }
        let ranked = cands.map { ($0, score($0)) }.enumerated().sorted { a, b in a.element.1 != b.element.1 ? a.element.1 > b.element.1 : a.offset < b.offset }.map(\.element)
        let (best, top) = ranked[0]
        if top > 0 && (ranked.count < 2 || ranked[1].1 != top) { return best }
        if top == 0 && cands.count == 1 && totalKnown && printed.isEmpty { return best }
        return nil
    }

    /// similarity 0..1 of two names (OCR noise tolerant)
    static func nameSimilarity(_ a: String, _ b: String) -> Double {
        let x = Array(fold(a).replacingOccurrences(of: " ", with: "")), y = Array(fold(b).replacingOccurrences(of: " ", with: ""))
        if x.isEmpty || y.isEmpty { return 0 }
        if String(x).contains(String(y)) || String(y).contains(String(x)) { return 1 }
        var d = Array(repeating: Array(repeating: 0, count: y.count + 1), count: x.count + 1)
        for i in 0...x.count { d[i][0] = i }
        for j in 0...y.count { d[0][j] = j }
        for i in 1...x.count { for j in 1...y.count {
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1))
        } }
        return 1 - Double(d[x.count][y.count]) / Double(max(x.count, y.count))
    }

    // regex helpers: the groups of a match as strings ("" for a group that didn't take part)
    static func firstMatch(_ re: NSRegularExpression, _ s: String) -> [String]? {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : ns.substring(with: m.range(at: $0)) }
    }

    static func allMatches(_ re: NSRegularExpression, _ s: String) -> [[String]] {
        let ns = s as NSString
        return re.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { m in
            (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : ns.substring(with: m.range(at: $0)) }
        }
    }
}
