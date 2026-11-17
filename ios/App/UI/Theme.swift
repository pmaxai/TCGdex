import SwiftUI

/// The colours of the Android app (dark, gold accent).
enum Theme {
    static let gold = Color(red: 0xF2 / 255, green: 0xC1 / 255, blue: 0x4E / 255)
    static let ink = Color(red: 0x10 / 255, green: 0x11 / 255, blue: 0x14 / 255)
    static let panel = Color(red: 0x1A / 255, green: 0x1C / 255, blue: 0x21 / 255)
    static let muted = Color(red: 0x9A / 255, green: 0xA0 / 255, blue: 0xAA / 255)
    static let good = Color(red: 0x9B / 255, green: 0xE3 / 255, blue: 0xB0 / 255)
}

enum Money {
    private static let eur: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = "EUR"
        f.locale = Locale(identifier: "en_IE")
        return f
    }()
    private static let usd: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = "USD"
        f.locale = Locale(identifier: "en_US")
        return f
    }()

    /// "€12.34" ("–" without a price)
    static func eur(_ v: Double?) -> String { v.flatMap { eur.string(from: NSNumber(value: $0)) } ?? "–" }
    static func usd(_ v: Double?) -> String { v.flatMap { usd.string(from: NSNumber(value: $0)) } ?? "–" }
}
