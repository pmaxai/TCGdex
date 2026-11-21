import XCTest
@testable import OverpricedCardboard

/// The price sources: the default price (Settings › Prices), its fallback, TCGplayer in euros, and the data pack's sources.
final class PriceSourceTests: XCTestCase {
    private func printing() -> Printing {
        var p = Printing(kind: .normal, eur: 12, eur30: 10, eurLow: 4, usd: 15)
        p.sourced = true
        p.cmTrend = 12; p.cmAvg = 11
        p.tcgMarket = 15; p.tcgMid = 16.5; p.tcgLow = 9
        p.usdPerEur = 1.25
        return p
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: PriceSource.key)
    }

    func testEverySourceAsItsProviderGivesIt() {
        let p = printing()
        XCTAssertEqual(p.raw(.cmTrend), 12)
        XCTAssertEqual(p.raw(.cmAvg30), 10)
        XCTAssertEqual(p.raw(.cmAvg), 11)
        XCTAssertEqual(p.raw(.cmLow), 4)
        XCTAssertEqual(p.raw(.tcgMarket), 15)
        XCTAssertEqual(p.raw(.tcgMid), 16.5)
        XCTAssertEqual(p.raw(.tcgLow), 9)
        // TCGplayer in euros at the data pack's rate (USD per EUR)
        XCTAssertEqual(p.eurValue(.tcgMarket), 12)
        XCTAssertEqual(p.eurValue(.tcgMid), 13.2)
        XCTAssertEqual(p.eurValue(.cmTrend), 12)
        XCTAssertEqual(p.shown(.tcgMarket)?.text, "≈ " + Money.eur(12))
        XCTAssertEqual(p.shown(.cmAvg30)?.text, Money.eur(10))
        var noRate = p
        noRate.usdPerEur = nil
        XCTAssertNil(noRate.eurValue(.tcgMarket))
        XCTAssertEqual(noRate.shown(.tcgMarket)?.text, "$15.00", "no rate: the dollar price")
    }

    func testAMissingSourceFallsBackInOrder() {
        var p = printing()
        p.cmTrend = nil
        let s = p.shown(.cmTrend)
        XCTAssertEqual(s?.source, .cmAvg30, "no trend: the 30-day average next")
        p.cmAvg = nil; p.eur30 = nil
        XCTAssertEqual(p.shown(.cmTrend)?.source, .tcgMarket)
        XCTAssertEqual(p.shown(.cmAvg)?.source, .tcgMarket)
        // a printing without any price
        let none = Printing(kind: .holo, eur: nil, eur30: nil, eurLow: nil, usd: nil)
        XCTAssertNil(none.shown(.cmTrend))
        XCTAssertEqual(none.priceText, "–")
        XCTAssertEqual(PriceSource.fallback.count, PriceSource.allCases.count, "every source is in the fallback")
    }

    func testTheChosenDefaultIsUsedEverywhere() {
        let p = printing()
        XCTAssertEqual(PriceSource.current, .cmTrend, "Cardmarket trend until another is chosen")
        XCTAssertEqual(p.value, 12)
        UserDefaults.standard.set(PriceSource.tcgMid.rawValue, forKey: PriceSource.key)
        XCTAssertEqual(PriceSource.current, .tcgMid)
        XCTAssertEqual(p.value, 13.2, "the default's euro value")
        XCTAssertEqual(p.priceText, "≈ " + Money.eur(13.2))
        XCTAssertEqual(TradingMath.reference(p, PriceSource.current), 13.2, "deals start from the default too")
        UserDefaults.standard.set("nonsense", forKey: PriceSource.key)
        XCTAssertEqual(PriceSource.current, .cmTrend)
    }

    func testLabels() {
        XCTAssertEqual(PriceSource.cmAvg30.label, "Cardmarket 30-day average")
        XCTAssertEqual(PriceSource.tcgMarket.label, "TCGplayer market price")
        XCTAssertTrue(PriceSource.tcgLow.isUSD)
        XCTAssertFalse(PriceSource.cmLow.isUSD)
        XCTAssertEqual(PriceSourcesTable.providers.flatMap(\.sources), PriceSource.allCases, "the table lists every source once")
    }

    /// The data pack fills the single sources of a card's printings.
    func testTheDataPackGivesEverySource() throws {
        guard let pack = ParityTests.pack ?? Pack.bundledDir.flatMap({ try? Pack(dir: $0) }) else { throw XCTSkip("no data pack") }
        let cat = Catalog(db: pack.db)
        let card = try XCTUnwrap(cat.cardExact("base1-4", "en"))
        let p = try XCTUnwrap(card.printings.first)
        XCTAssertTrue(p.sourced)
        let have = PriceSource.allCases.filter { p.raw($0) != nil }
        print("PriceSourceTest: base1-4 \(p.kind.rawValue): " + have.map { "\($0.rawValue) \(p.raw($0)!)" }.joined(separator: ", ")
              + " · usdPerEur \(p.usdPerEur.map { String($0) } ?? "-")")
        XCTAssertTrue(have.contains(.cmTrend) || have.contains(.cmAvg30), "a Cardmarket price")
        if p.raw(.tcgMarket) != nil, p.usdPerEur != nil { XCTAssertNotNil(p.eurValue(.tcgMarket)) }
    }
}
