import SwiftUI

/// The colours of the Android app (dark, gold accent).
enum Theme {
    static let gold = Color(red: 0xF2 / 255, green: 0xC1 / 255, blue: 0x4E / 255)
    static let ink = Color(red: 0x10 / 255, green: 0x11 / 255, blue: 0x14 / 255)
    static let panel = Color(red: 0x1A / 255, green: 0x1C / 255, blue: 0x21 / 255)
    static let muted = Color(red: 0x9A / 255, green: 0xA0 / 255, blue: 0xAA / 255)
    static let good = Color(red: 0x9B / 255, green: 0xE3 / 255, blue: 0xB0 / 255)
    static let sure = Color(red: 0x4C / 255, green: 0xD0 / 255, blue: 0x7D / 255)
    static let likely = gold
    static let warn = Color(red: 0xFF / 255, green: 0x8A / 255, blue: 0x3D / 255)
}

let languageNames: [String: String] = ["en": "English", "de": "Deutsch", "fr": "Français", "es": "Español",
    "it": "Italiano", "pt": "Português", "ja": "日本語", "zh-tw": "繁體中文", "zh-cn": "简体中文", "ko": "한국어"]

extension CardInfo {
    /// "Twilight Masquerade · 034/167 · DE · 2024"
    func setLine(withLang: Bool = true) -> String {
        [setName.isEmpty ? nil : setName, numberText.isEmpty ? nil : numberText, withLang ? lang.uppercased() : nil, year]
            .compactMap { $0 }.joined(separator: " · ")
    }
}

extension Printing {
    /// the default price source's price (or the next one with a price)
    var priceText: String { shown(PriceSource.current)?.text ?? "–" }
}

enum Money {
    /// "12.34 €" in the phone's number format, as the Android app ("–" without a price)
    static func eur(_ v: Double?) -> String {
        guard let v else { return "–" }
        return String(format: "%.2f €", locale: Locale.current, v)
    }

    /// "$4.20", "$120" (whole dollars from 100 on)
    static func usd(_ v: Double?) -> String {
        guard let v else { return "–" }
        return v >= 100 ? String(format: "$%.0f", v) : String(format: "$%.2f", v)
    }
}
