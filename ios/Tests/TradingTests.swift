import XCTest
@testable import OverpricedCardboard

/// Trading Mode's numbers, as the Android app's TradingTest.
final class TradingTests: XCTestCase {
    func testVerdictBandsAsAgreed() {
        func v(_ share: Double) -> Verdict? { TradingMath.verdict(share * 50, 50) }
        XCTAssertEqual(v(0.80), .GREAT)
        XCTAssertEqual(v(0.81), .GOOD)
        XCTAssertEqual(v(0.95), .GOOD)
        XCTAssertEqual(v(0.96), .FAIR)
        XCTAssertEqual(v(1.10), .FAIR)
        XCTAssertEqual(v(1.11), .EXPENSIVE)
        XCTAssertNil(TradingMath.verdict(10, 0))
    }

    func testOfferIsRoundedDown() {
        XCTAssertEqual(TradingMath.offer(52), 44, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.offer(0.5), 0.4, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.offer(5.88), 4.9, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.offer(60), 50, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.offer(100), 85, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.walkAway(52), 52, accuracy: 1e-9)
    }

    func testMarketValueFollowsConditionAndReference() {
        let p = Printing(kind: .normal, eur: 61.18, eur30: 50, eurLow: 20, usd: 70)
        XCTAssertEqual(TradingMath.reference(p, .TREND)!, 61.18, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.reference(p, .AVG30)!, 50, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.reference(p, .LOW)!, 20, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.market(61.18, .EX), 52, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.market(61.18, .PO), 18.35, accuracy: 1e-9)
        XCTAssertEqual(TradingMath.trend(p)!, 0.2236, accuracy: 1e-4)
        XCTAssertTrue(TradingMath.fewSales(p), "lowest under half the trend")
        var q = p; q.eurLow = 40
        XCTAssertFalse(TradingMath.fewSales(q))
        XCTAssertNil(TradingMath.reference(Printing(kind: .normal, eur: nil, eur30: nil, eurLow: nil, usd: 70), .TREND))
    }

    private func line(_ id: Int64, _ name: String, _ ref: Double, _ c: Condition, _ asking: Double?, _ negotiated: Double?,
                      _ status: LineStatus = .CONSIDERING, qty: Int = 1) -> CartLine {
        CartLine(id: id, cardId: "sv-\(id)", lang: "en", group: "sv-\(id)", name: name, setLine: "Set · \(id) · EN · 2024", image: nil,
                 printing: "NORMAL", reference: ref, condition: c, qty: qty, asking: asking, negotiated: negotiated, status: status, added: id)
    }

    private func booth4() -> Cart {
        Cart(id: 1, name: "Booth 4", created: 0, lines: [line(1, "Charizard ex", 52, .NM, 45, 40), line(2, "Umbreon V", 51.76, .EX, 42, 36),
                                                         line(3, "Pikachu", 34, .NM, 33, 29), line(4, "Dropped one", 99, .NM, 80, nil, .DROPPED)])
    }

    func testCartTotalsMatchTheConceptExample() {
        let c = booth4()
        XCTAssertEqual(c.cards, 3)
        XCTAssertEqual(c.marketTotal, 130, accuracy: 1e-9)
        XCTAssertEqual(c.askingTotal, 120, accuracy: 1e-9)
        XCTAssertEqual(c.payTotal, 105, accuracy: 1e-9)
        XCTAssertEqual(c.savedVsMarket, 25, accuracy: 1e-9)
        XCTAssertEqual(c.savedVsAsking, 15, accuracy: 1e-9)
        XCTAssertEqual(c.unpriced, 0)
        XCTAssertEqual(TradingMath.verdict(45, 52), .GOOD)
        XCTAssertEqual(c.lines[0].verdict, .GREAT)
        var more = c
        more.lines.append(line(5, "Unpriced", 10, .NM, nil, nil, qty: 2))
        XCTAssertEqual(more.marketTotal, 150, accuracy: 1e-9)
        XCTAssertEqual(more.payTotal, 105, accuracy: 1e-9)
        XCTAssertEqual(more.unpriced, 1)
    }

    func testADoubtfulPriceGetsNoVerdictAndIsRemembered() throws {
        let l = line(7, "Plinfa", 39.74, .NM, 5, nil)
        XCTAssertEqual(l.verdict, .GREAT)
        var doubt = l; doubt.priceDoubt = true
        XCTAssertNil(doubt.verdict)
        var giving = doubt; giving.side = .GIVE
        XCTAssertNil(giving.sellVerdict)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("trading.json")
        let store = TradingStore(file: file)
        let (b, cart) = TradingBook().withActive(now: 1)
        var c = cart; c.lines = [doubt]
        store.save(b.replace(c))
        XCTAssertEqual(store.load().carts.first?.lines.first?.priceDoubt, true)
    }

    func testCartsKeepTheirNumbersAndSurviveARestart() {
        var b = TradingBook().newCart(now: 1).newCart(now: 2)
        XCTAssertEqual(b.carts.map(\.name), ["Booth 1", "Booth 2"])
        XCTAssertEqual(b.active?.name, "Booth 2")
        var renamed = booth4()
        renamed = Cart(id: b.active!.id, name: "Max, table 14", created: renamed.created, lines: renamed.lines)
        b = b.replace(renamed)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("trading.json")
        let store = TradingStore(file: file)
        store.save(b)
        let back = store.load()
        XCTAssertEqual(back, b)
        XCTAssertEqual(back.newCart(now: 3).active?.name, "Booth 2")
        let (made, cart) = TradingBook().withActive(now: 5)
        XCTAssertEqual(cart.name, "Booth 1")
        XCTAssertEqual(made.activeId, cart.id)
    }

    func testDealsCsvUsesTheRegionalFormat() {
        var c = booth4(); c.paidAt = 1_762_200_000_000; c.payment = "Cash"
        let de = TradingCsv.build([c], locale: Locale(identifier: "de_DE"))
        let lines = String(de.dropFirst()).components(separatedBy: "\r\n")
        XCTAssertTrue(lines[0].hasPrefix("Booth;Paid;Payment;Status;Name"))
        XCTAssertTrue(lines[1].hasPrefix("Booth 4;"))
        XCTAssertTrue(lines[1].contains(";52,00;45,00;40,00;40,00;Great deal"), lines[1])
        XCTAssertTrue(de.contains(";130,00;120,00;105,00;25,00"))
        XCTAssertTrue(TradingCsv.build([c], locale: Locale(identifier: "en_US")).contains(",52.00,45.00,40.00,"))
        XCTAssertFalse(de.contains("Optional"))
    }

    func testSplitIsExactToTheCent() {
        XCTAssertEqual(TradingMath.split(105, [52, 44, 34]), [42, 35.54, 27.46])
        XCTAssertEqual(TradingMath.split(10, [1, 1, 1]), [3.34, 3.33, 3.33])
        XCTAssertEqual(TradingMath.split(5, [0, 0]), [2.5, 2.5])
        let parts = TradingMath.split(99.99, [13.37, 0.5, 120, 7])
        XCTAssertEqual(parts.reduce(0) { $0 + Int64(($1 * 100).rounded()) }, 9999)
    }

    func testSellVerdictUsesTheVendorsUsualRange() {
        func v(_ share: Double) -> SellVerdict? { TradingMath.sellVerdict(share * 40, 40) }
        XCTAssertEqual(v(0.74), .LOW)
        XCTAssertEqual(v(0.75), .USUAL)
        XCTAssertEqual(v(0.90), .USUAL)
        XCTAssertEqual(v(0.91), .GOOD)
        let (lo, hi) = TradingMath.sellRange(40)
        XCTAssertEqual(lo, 30); XCTAssertEqual(hi, 36)
    }

    private func give(_ id: Int64, _ ref: Double, offer: Double? = nil, qty: Int = 1) -> CartLine {
        var l = line(id, "Mine \(id)", ref, .NM, offer, nil, qty: qty)
        l.side = .GIVE
        return l
    }

    func testTradeBalanceMatchesTheConceptExample() {
        let t = Cart(id: 7, name: "Booth 9", created: 0, lines: [line(1, "His A", 60, .NM, nil, nil), line(2, "His B", 60, .NM, nil, nil),
                                                                  give(3, 28, qty: 5)], trade: true)
        XCTAssertTrue(t.isTrade)
        XCTAssertEqual(t.getValue, 120, accuracy: 1e-9)
        XCTAssertEqual(t.giveValue, 112, accuracy: 1e-9)
        XCTAssertEqual(t.balance, 8, accuracy: 1e-9)
        var agreed = t; agreed.cash = 5
        XCTAssertEqual(agreed.settle, 5, accuracy: 1e-9)
        XCTAssertEqual(agreed.netAtMarket, -25, accuracy: 1e-9)
        var offered = t
        offered.lines = t.lines.map { l in var x = l; if x.side == .GIVE { x.asking = 25 }; return x }
        XCTAssertEqual(offered.giveValue, 125, accuracy: 1e-9)
        XCTAssertEqual(offered.balance, -5, accuracy: 1e-9)
        let sale = Cart(id: 8, name: "Booth 2", created: 0, lines: [give(4, 50, offer: 40)])
        XCTAssertEqual(sale.settle, -40, accuracy: 1e-9)
        XCTAssertEqual(sale.lines[0].sellVerdict, .USUAL)
        let sum = DealSummary.of([agreed, sale, booth4()])
        XCTAssertEqual(sum.spent, 110, accuracy: 1e-9)
        XCTAssertEqual(sum.received, 40, accuracy: 1e-9)
        XCTAssertEqual(sum.marketIn, 250, accuracy: 1e-9)
        XCTAssertEqual(sum.marketOut, 190, accuracy: 1e-9)
        XCTAssertEqual(sum.bought, 5)
        XCTAssertEqual(sum.given, 6)
    }

    func testBudgetCountsPaidAndAgreed() {
        var b = TradingBook().startShow("Vienna Card Show", budget: 300, now: 1)
        let sh = b.showId
        b = b.newCart(now: 2)
        XCTAssertEqual(b.active?.showId, sh)
        var paid = booth4(); paid = Cart(id: 100, name: paid.name, created: 0, lines: paid.lines, paidAt: 5, showId: sh)
        let open = Cart(id: 101, name: "Booth 5", created: 3, lines: [line(9, "Agreed", 50, .NM, 45, 40, .AGREED), line(10, "Maybe", 50, .NM, 45, nil)], showId: sh)
        b.carts += [paid, open]
        XCTAssertEqual(b.spent, 105, accuracy: 1e-9)
        XCTAssertEqual(b.agreed, 40, accuracy: 1e-9)
        XCTAssertEqual(b.show?.budget, 300)
        let day2 = b.startShow("Day 2", budget: nil, now: 9)
        XCTAssertEqual(day2.open.first?.showId, day2.showId, "a new show takes the open carts along")
    }

    /// the Android app's files (phase 1) load, with the newer fields' defaults
    func testOldFilesStillLoadAndNewFieldsRoundTrip() throws {
        let old = """
        {"version":1,"activeId":1,"nextId":2,"carts":[{"id":1,"name":"Booth 1","created":0,"paidAt":null,"payment":null,
        "lines":[{"id":5,"cardId":"sv06-034","lang":"en","group":"sv06-034","name":"Darumaka","setLine":"TWM","image":null,
        "printing":"NORMAL","reference":0.1,"condition":"NM","qty":2,"asking":null,"negotiated":null,"status":"CONSIDERING","added":5}]}]}
        """
        let b = try JSONDecoder().decode(TradingBook.self, from: Data(old.utf8))
        let c = try XCTUnwrap(b.carts.first)
        XCTAssertFalse(c.isTrade)
        XCTAssertEqual(c.lines.first?.side, .GET)
        XCTAssertEqual(c.giveRate, TradingMath.giveRate, accuracy: 1e-9)
        XCTAssertNil(c.showId)
        XCTAssertTrue(b.shows.isEmpty)
        var full = TradingBook().startShow("Show", budget: 250, now: 1)
        full.carts = [Cart(id: 1, name: "Booth 9", created: 0, lines: [give(3, 28, qty: 5), line(1, "His", 60, .EX, nil, nil)], trade: true,
                           giveRate: 0.75, cash: -3.5, showId: full.showId)]
        XCTAssertEqual(try JSONDecoder().decode(TradingBook.self, from: JSONEncoder().encode(full)), full)
    }

    func testTradeCsvHasSidesAndBalance() {
        let t = Cart(id: 7, name: "Booth 9", created: 0, lines: [line(1, "His A", 60, .NM, nil, nil), give(3, 28, qty: 5)],
                     paidAt: 1, payment: "Cash", trade: true, cash: 5)
        let csv = TradingCsv.build([t], locale: Locale(identifier: "de_DE"))
        XCTAssertTrue(csv.contains(";I give"))
        XCTAssertTrue(csv.contains("Trade balance EUR"))
        XCTAssertTrue(csv.contains(";5,00;"))
    }
}
