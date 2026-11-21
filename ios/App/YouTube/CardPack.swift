import Foundation

/// The one hit rule: the HIT badge, the pack bar, the pack CSV and the recording's log all ask this.
func isHit(_ value: Double?, _ threshold: Double) -> Bool { threshold > 0 && (value ?? 0) >= threshold }

/// One card of the pack (YouTube Mode): the card as shown, without the camera's picture of it.
struct SessionItem: Identifiable {
    let serial: Int
    let time: Date
    let shown: Shown
    var id: Int { serial }
    var group: String { shown.group }
    var name: String { shown.version.name }
    var value: Double? { shown.value }
}

/// What changed the pack or the shown card: the recording's log takes the event's type and reason from it.
enum PackReason {
    case shown, textRead, printing, version, otherCard, searchReplace, addAgain, swipe, removeLast, next, reset

    /// the reason in the log's card_updated / card_removed events
    var wire: String? {
        switch self {
        case .textRead: return "text_read"
        case .printing: return "printing"
        case .version: return "version"
        case .otherCard: return "other_card"
        case .searchReplace: return "search_replace"
        case .swipe: return "swipe"
        case .removeLast: return "remove_last"
        case .shown, .addAgain, .next, .reset: return nil
        }
    }
}

extension Shown {
    /// as the pack keeps it: without the camera's picture (a pack can hold hundreds of cards)
    var forPack: Shown { var s = self; s.scan = nil; return s }
}

/**
 * The pack (YouTube Mode) as a CSV file: one row per card with everything the app knows about
 * it, then the totals – the same file as the Android app's. Written the way spreadsheet apps in
 * the phone's region expect it: where the decimal mark is a comma (German, French, …), fields are
 * separated by ';' and numbers use the comma, like Excel does there. UTF-8 with a BOM, so umlauts
 * and Japanese names show correctly.
 */
enum PackCsv {
    static let header = [
        "No", "Scanned", "Name", "English name", "Set", "Number", "Language", "Card ID", "Printing",
        "Value", "Currency", "Cardmarket trend EUR", "Cardmarket 30-day avg EUR", "Cardmarket lowest EUR",
        "TCGplayer USD", "PSA 10 USD", "PSA 10 sales", "PSA 9 USD", "PSA 9 sales", "PSA 8 USD", "PSA 8 sales",
        "PSA gem rate %", "PSA graded",
        "Hit", "All printings", "Rarity", "Series", "Released", "Prices from", "Cardmarket search",
    ]

    static func build(_ items: [SessionItem], threshold: Double, title: String, locale: Locale = .current,
                      timeZone: TimeZone = .current) -> String {
        let decimalComma = (locale.decimalSeparator ?? ".") == ","
        let sep: Character = decimalComma ? ";" : ","
        func num(_ v: Double?) -> String {
            guard let v else { return "" }
            let t = String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), v)
            return decimalComma ? t.replacingOccurrences(of: ".", with: ",") : t
        }
        func int(_ v: Int?) -> String { v.map(String.init) ?? "" }
        func cell(_ t: String) -> String {
            t.contains(where: { $0 == sep || $0 == "\"" || $0 == "\n" || $0 == "\r" })
                ? "\"" + t.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : t
        }
        let time = DateFormatter()
        time.locale = Locale(identifier: "en_US_POSIX")
        time.timeZone = timeZone
        time.dateFormat = "yyyy-MM-dd HH:mm:ss"
        var out = "\u{FEFF}"
        func row(_ values: [String]) { out += values.map(cell).joined(separator: String(sep)) + "\r\n" }

        row(header)
        var eur = 0.0, usd = 0.0
        for (i, item) in items.enumerated() {
            let s = item.shown, v = s.version, p = s.selected, g = s.graded
            let currency = p?.eur != nil ? "EUR" : p?.usd != nil ? "USD" : ""
            if currency == "EUR" { eur += p?.eur ?? 0 } else if currency == "USD" { usd += p?.usd ?? 0 }
            let gem = s.population.flatMap { pop in (pop.gemRate ?? pop.psa10.map { Double($0) / Double(max(1, pop.psaTotal)) }).map { $0 * 100 } }
            let q = formEncoded(s.priceCard.name)
            row([
                String(i + 1), time.string(from: item.time), v.name, s.english?.name ?? (v.lang == "en" ? v.name : ""),
                v.setName, v.numberText, v.lang, v.id, p?.kind.rawValue ?? "",
                num(p?.eur ?? p?.usd), currency, num(p?.eur), num(p?.eur30), num(p?.eurLow), num(p?.usd),
                num(g?.psa10), int(g?.psa10n), num(g?.psa9), int(g?.psa9n), num(g?.psa8), int(g?.psa8n),
                num(gem), int(s.population?.psaTotal),
                isHit(s.value, threshold) ? "yes" : "",
                s.priceCard.printings.map { "\($0.kind.rawValue) \(printingValue($0, num))" }.joined(separator: " | "),
                s.priceCard.rarity ?? "", s.priceCard.series, v.releaseDate ?? s.priceCard.releaseDate ?? "",
                s.priceCard.prices?.updated.map { String($0.prefix(10)) } ?? "",
                "https://www.cardmarket.com/en/Pokemon/Products/Search?searchString=" + q,
            ])
        }
        out += "\r\n"
        row(["Cards", String(items.count)])
        row(["Pack value EUR", num(eur)])
        if usd > 0 { row(["Pack value USD (cards without a Cardmarket price)", num(usd)]) }
        if !title.trimmingCharacters(in: .whitespaces).isEmpty { row(["Title", title]) }
        return out
    }

    /// as a form field (Java's URLEncoder, as on Android): spaces as "+", all but A–Z a–z 0–9 . - * _ as %XX
    static func formEncoded(_ t: String) -> String {
        let keep = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-*_ ")
        return (t.addingPercentEncoding(withAllowedCharacters: keep) ?? "").replacingOccurrences(of: " ", with: "+")
    }

    private static func printingValue(_ p: Printing, _ num: (Double?) -> String) -> String {
        if p.eur != nil { return num(p.eur) + " EUR" }
        if p.usd != nil { return num(p.usd) + " USD" }
        return "-"
    }

    /// "pack_2026-11-03_0314.csv", with the title in front if there is one
    static func fileName(title: String, now: Date = Date(), timeZone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = timeZone
        f.dateFormat = "yyyy-MM-dd_HHmm"
        let t = String(title.replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "_")).prefix(40))
        return (t.isEmpty ? "" : t + "_") + "pack_\(f.string(from: now)).csv"
    }
}
