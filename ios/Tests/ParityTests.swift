import CoreGraphics
import UIKit
import XCTest
@testable import OverpricedCardboard

/**
 * Checks that the iPhone app sees what the Python pipeline saw (parity.zip from the data pack),
 * like the Android app's ParityTest: the detector on synthetic photos, and full recognition on
 * the real test photos. Lines starting with "ParityTest:" are what the CI log shows.
 */
final class ParityTests: XCTestCase {
    static var pack: Pack!
    static var engine: ScannerEngine!

    override class func setUp() {
        super.setUp()
        let t0 = Date()
        guard let dir = Pack.bundledDir else { return }
        pack = try? Pack(dir: dir)
        if let pack { engine = try? ScannerEngine(pack: pack) }
        log("pack \(pack?.version ?? "-"): \(pack?.n ?? 0) x \(pack?.dim ?? 0) loaded in \(Int(Date().timeIntervalSince(t0) * 1000)) ms")
    }

    static func log(_ s: String) { print("ParityTest: " + s) }
    private func log(_ s: String) { Self.log(s) }

    private var fixtures: URL {
        get throws { try XCTUnwrap(Bundle(for: ParityTests.self).url(forResource: "Fixtures", withExtension: nil), "test photos missing") }
    }

    private func json() throws -> [String: Any] {
        let data = try Data(contentsOf: fixtures.appendingPathComponent("parity/parity.json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func photo(_ path: String) throws -> RGBImage {
        try XCTUnwrap(RGBImage(contentsOf: fixtures.appendingPathComponent(path)), "can't read \(path)")
    }

    private func quad(_ a: Any?) -> [CGPoint] {
        ((a as? [[Double]]) ?? []).map { CGPoint(x: $0[0], y: $0[1]) }
    }

    func testPackLoaded() throws {
        let pack = try XCTUnwrap(Self.pack, "the data pack didn't load")
        XCTAssertGreaterThan(pack.n, 1000)
        XCTAssertEqual(pack.index.count, pack.n * pack.dim * 4)
        XCTAssertNotNil(Self.engine, "the fingerprint model didn't load")
    }

    func testDetectorMatchesPipelineOnSyntheticPhotos() throws {
        let items = try XCTUnwrap(json()["synthetic"] as? [[String: Any]])
        var agree = 0
        var lines: [String] = []
        for it in items {
            let file = it["file"] as? String ?? ""
            let img = try photo("parity/photos/\(file)")
            let t0 = Date()
            let d = CCDetector.detectRGB(img.data, width: Int32(img.width), height: Int32(img.height), maxCards: 1)
            let ms = Int(Date().timeIntervalSince(t0) * 1000)
            let pyDetected = it["detected"] as? Bool ?? false
            var overlap = "-"
            var ok = d.isEmpty
            if pyDetected {
                let iou = d.isEmpty ? 0 : CCDetector.iou(d[0], quad(it["quad"]).map { NSValue(cgPoint: $0) })
                if !d.isEmpty { overlap = String(format: "%.3f", iou) }
                ok = !d.isEmpty && iou >= 0.9
            }
            if ok { agree += 1 }
            lines.append("\(file): python=\(pyDetected) app=\(!d.isEmpty) overlap=\(overlap) \(ms)ms \(ok ? "OK" : "DIFF")")
        }
        lines.forEach(log)
        log("detector agrees on \(agree)/\(items.count) synthetic photos")
        XCTAssertGreaterThanOrEqual(agree, items.count - 1, "detector differs from the pipeline:\n" + lines.joined(separator: "\n"))
    }

    func testRealPhotosRecognisedLikePipeline() throws {
        let engine = try XCTUnwrap(Self.engine)
        let catalog = Catalog(db: Self.pack.db)
        let items = try XCTUnwrap(json()["real"] as? [[String: Any]])
        var same = 0, n = 0
        var lines: [String] = []
        for it in items where it["detected"] as? Bool == true {
            let file = it["file"] as? String ?? ""
            let img = try photo("real/\(file)")
            n += 1
            let r = engine.recognize(img)
            let py = (it["top"] as? [[String: Any]])?.first
            let pyId = py?["id"] as? String ?? "?"
            let app = r.best
            let match = app?.group == pyId
            if match { same += 1 }
            let name = app.flatMap { catalog.card($0.group, "en")?.name } ?? "-"
            let times = r.timingsMs.sorted { $0.key < $1.key }.map { "\($0.key)=\(Int($0.value))ms" }.joined(separator: " ")
            lines.append(String(format: "%@: python=%@ %.3f app=%@ %@ %.3f [%@] %@ %@", file, pyId, (py?["score"] as? Double) ?? 0,
                                app?.group ?? "-", name, app?.score ?? 0, app?.readingName ?? "-", times, match ? "OK" : "DIFF"))
        }
        lines.forEach(log)
        log("real photos: app agrees with the pipeline on \(same)/\(n)")
        XCTAssertGreaterThan(n, 0)
        XCTAssertEqual(same, n, "recognition differs from the pipeline:\n" + lines.joined(separator: "\n"))
    }

    func testTakingACardByHandFindsAtLeastTheAutomaticMatch() throws {
        let engine = try XCTUnwrap(Self.engine)
        let items = try XCTUnwrap(json()["real"] as? [[String: Any]])
        var n = 0
        for it in items {
            let file = it["file"] as? String ?? ""
            let img = try photo("real/\(file)")
            let prior = ScannerEngine.guidePrior(width: img.width, height: img.height)
            let auto = engine.recognize(img, prior: prior)
            let manual = engine.recognize(img, prior: prior, manual: true)
            let a = auto.best?.score ?? 0, m = manual.best?.score ?? -1
            log(String(format: "by hand %@: %@ %.3f (automatic %@ %.3f)", file, manual.best?.group ?? "-", m, auto.best?.group ?? "-", a))
            XCTAssertGreaterThanOrEqual(m, a - 1e-4, "\(file): taking it by hand found a worse match")
            let c = manual.candidates
            XCTAssertTrue((6...10).contains(c.count), "\(file): list size \(c.count)")
            XCTAssertEqual(Set(c.map(\.group)).count, c.count, "\(file): distinct cards")
            XCTAssertEqual(manual.best?.group, c.first?.group, "\(file): shown card first")
            XCTAssertTrue(zip(c, c.dropFirst()).allSatisfy { $0.score >= $1.score }, "\(file): best first")
            n += 1
        }
        XCTAssertGreaterThan(n, 0)
    }

    func testCardImageIsUpright() throws {
        let engine = try XCTUnwrap(Self.engine)
        let items = try XCTUnwrap(json()["real"] as? [[String: Any]])
        let file = try XCTUnwrap(items.first { $0["detected"] as? Bool == true }?["file"] as? String)
        let img = try photo("real/\(file)")
        let r = engine.recognize(img)
        let best = try XCTUnwrap(r.best)
        let card = try XCTUnwrap(engine.cardImage(img, r, best))
        XCTAssertEqual(card.width, 360)
        XCTAssertEqual(card.height, 503)
        XCTAssertNotNil(card.uiImage)
    }

    /// The card search finds any card, also those without a picture (which the camera can't recognise).
    func testSearchFindsCardsByNameNumberAndSet() throws {
        let cat = Catalog(db: try XCTUnwrap(Self.pack).db)
        func ids(_ q: String) -> [String] { cat.search(q).map { "\($0.id)/\($0.lang)" } }
        let whim = ids("whimsicott 019")
        log("search 'whimsicott 019': \(whim.prefix(5))")
        XCTAssertEqual(whim.first, "mep-019/en", "whimsicott 019")
        XCTAssertEqual(ids("flampion 034").first, "sv06-034/de", "German name finds the German card")
        XCTAssertEqual(ids("darumaka 034/167").first, "sv06-034/en", "number with total")
        XCTAssertTrue(ids("sv06 34").first?.hasPrefix("sv06-034/") == true, "set code + number")
        XCTAssertGreaterThanOrEqual(cat.search("whimsicott").count, 10, "several Whimsicotts")
        XCTAssertTrue(cat.search("x zzzzqq").isEmpty, "one letter is not enough to match everything")
        XCTAssertEqual(cat.groupOf("mep-019"), "mep-019", "the artwork group of a card without a picture is the card itself")
    }

    func testCardHasPrintingsAndPrices() throws {
        let cat = Catalog(db: try XCTUnwrap(Self.pack).db)
        let card = try XCTUnwrap(cat.cardExact("sv06-034", "en"))
        log("sv06-034: \(card.name) \(card.setName) \(card.numberText) \(card.printings.map { "\($0.kind.rawValue) \(Money.eur($0.eur)) / \(Money.usd($0.usd))" })")
        XCTAssertEqual(card.name, "Darumaka")
        XCTAssertFalse(card.printings.isEmpty)
        XCTAssertTrue(card.printings.contains { $0.value != nil }, "no price at all")
    }

    func testExcludedCardsAreNeverMatched() throws {
        let engine = try XCTUnwrap(Self.engine)
        let pack = try XCTUnwrap(Self.pack)
        let items = try XCTUnwrap(json()["real"] as? [[String: Any]])
        let file = try XCTUnwrap(items.first { $0["detected"] as? Bool == true }?["file"] as? String)
        let img = try photo("real/\(file)")
        let first = try XCTUnwrap(engine.recognize(img).best)
        let g = pack.entryGroup[first.entry]
        engine.setExcluded(pack.entryGroup.map { $0 == g })
        defer { engine.setExcluded(nil) }
        let again = engine.recognize(img)
        log("excluded \(first.group): now \(again.best?.group ?? "-")")
        XCTAssertFalse(again.hits.contains { $0.group == first.group }, "excluded card was still matched")
    }

    /// What the printed text says (Vision here, ML Kit on Android): the language decides what is shown.
    func testRealPhotosLanguageAndNumber() throws {
        let engine = try XCTUnwrap(Self.engine)
        let cat = Catalog(db: Self.pack.db)
        let expected = ["Magneton 059-191.jpg": ("de", "59"), "Plusle 69-101.jpg": ("en", "69"),
                        // Japanese Meganium of M1S (2025): no picture in the data; script, number and printed name find it
                        "me01-010__Meganium_japanese_shiny_border_screenshot.jpg": ("ja", "5")]
        let expectedCard = ["me01-010__Meganium_japanese_shiny_border_screenshot.jpg": "M1S-005"]
        let items = try XCTUnwrap(json()["real"] as? [[String: Any]])
        let reader = TextReader()
        var lines: [String] = []
        var wrong = 0
        for it in items {
            let file = it["file"] as? String ?? ""
            let img = try photo("real/\(file)")
            let r = engine.recognize(img)
            guard let hit = r.best else { continue }
            let t0 = Date()
            guard let t = engine.readText(img, r, hit, reader: reader) else { continue }
            let ms = Int(Date().timeIntervalSince(t0) * 1000)
            let num = TextReader.normNumber(t.number)
            let exp = expected[file]
            let ok = exp == nil || t.lang == exp!.0
            if !ok { wrong += 1 }
            var verdict = ""
            if let exp { verdict = (ok ? "language OK" : "language EXPECTED \(exp.0)") + (num == exp.1 ? ", number OK" : ", number expected \(exp.1)") }
            lines.append("\(file) [\(hit.readingName)]: number=\(t.number ?? "-")/\(t.total ?? "-") lang=\(t.lang ?? "-") (+\(String(format: "%.1f", t.langMargin))) \(ms)ms \(verdict)")
            lines.append("   scores " + t.langScores.sorted { $0.value > $1.value }.map { "\($0.key)=\(String(format: "%.1f", $0.value))" }.joined(separator: ", "))
            lines.append("   name bar: \(t.nameText.prefix(60)) | text: \(t.text.replacingOccurrences(of: "\n", with: " / ").prefix(160))")
            lines.append("   script: \(t.script)")
            // an Asian print: the exact card by its number and printed name (it has no picture)
            if let lang = t.lang, ["ja", "ko", "zh-tw", "zh-cn"].contains(lang) {
                let langs = lang.hasPrefix("zh") ? ["zh-tw", "zh-cn"] : [lang]
                let cands = cat.byNumber(langs, num, t.total.map { TextReader.normNumber($0) })
                let known = cat.englishOf(hit.group).map { cat.asianNames($0.name, langs) } ?? []
                let picked = TextReader.pickPrint(cands, printedName: t.nameText, known: known, totalKnown: t.total != nil)
                lines.append("   print: \(cands.map { "\($0.id) \($0.name)" }) -> \(picked?.id ?? "-")")
                if let want = expectedCard[file], picked?.id != want { wrong += 1; lines.append("   PRINT EXPECTED \(want)") }
            } else if let want = expectedCard[file] {
                wrong += 1; lines.append("   PRINT EXPECTED \(want) (language not Asian)")
            }
        }
        lines.forEach(log)
        XCTAssertEqual(wrong, 0, "language/number differ:\n" + lines.joined(separator: "\n"))
    }

    /// A Korean print isn't in the card data; its set is the Japanese one (same code and number).
    func testAKoreanPrintIsItsJapaneseOriginal() throws {
        let cat = Catalog(db: try XCTUnwrap(Self.pack).db)
        let group = cat.groupOf("me01-010")
        let dex = cat.dexOfGroup(group)
        let cands = cat.byNumber(["ja"], "5", "63")
        log("korean 005/063 of \(group) (dex \(dex.sorted())): \(cands.map { "\($0.id) \($0.name) \(cat.dexOf($0.id).sorted())" })")
        XCTAssertTrue(cands.contains { $0.id == "M1S-005" }, "candidates 005/063 in Japanese: \(cands.map(\.id))")
        if dex.isEmpty { log("this data pack has no Pokédex numbers yet"); return }
        XCTAssertEqual(cands.filter { !cat.dexOf($0.id).isDisjoint(with: dex) }.map(\.id), ["M1S-005"])
    }

    /// The version the scanner shows for a photo: the artwork's card in the printed language and set.
    func testShownVersionOfTheRealPhotos() throws {
        let engine = try XCTUnwrap(Self.engine)
        let cat = Catalog(db: Self.pack.db)
        let resolver = CardResolver(catalog: cat, pack: Self.pack)
        let reader = TextReader()
        let expected = ["Magneton 059-191.jpg": "de", "Plusle 69-101.jpg": "en"]
        let items = try XCTUnwrap(json()["real"] as? [[String: Any]])
        for it in items {
            let file = it["file"] as? String ?? ""
            let img = try photo("real/\(file)")
            let r = engine.recognize(img)
            guard let hit = r.best else { continue }
            let text = engine.readText(img, r, hit, reader: reader)
            let v = try XCTUnwrap(resolver.resolveVersion(group: hit.group, entry: hit.entry, text: text), file)
            let shown = resolver.build(group: hit.group, version: v, text: text, confidence: .sure, alternatives: [], locked: false,
                                       fromPhoto: true, scan: nil, serial: 1)
            log("shown for \(file): \(v.id)/\(v.lang) \(v.name) \(v.setLine()) · \(shown.selected.map { "\($0.kind.rawValue) \($0.priceText)" } ?? "no price") · \(shown.versions.count) versions")
            if let want = expected[file] { XCTAssertEqual(v.lang, want, file) }
        }
    }

    /// The data pack carries price history once the pipeline has built it: a well-known card has a line.
    func testPackHasPriceHistory() throws {
        let pack = try XCTUnwrap(Self.pack)
        let cat = Catalog(db: pack.db)
        guard let dates = pack.db.query("SELECT value FROM meta WHERE key = 'history_dates'").first?.first?.string else {
            log("this data pack has no price history yet"); return
        }
        let with = ["sv06-034", "sv03.5-006", "base1-4", "swsh7-215", "sv08-059"].compactMap { id in
            cat.history(id).map { h in "\(id): " + h.series.map { "\($0.key) \($0.value.filter { !$0.isNaN }.count)" }.sorted().joined(separator: ", ") }
        }
        log("price history: \(dates.split(separator: ",").count) days in the calendar, points: \(with)")
        XCTAssertFalse(with.isEmpty, "no card has a price history")
    }

    func testPackPriceCheck() throws {
        let pack = try XCTUnwrap(Self.pack)
        guard pack.db.tableExists("price_check") else { log("this data pack has no price check yet"); return }
        let cat = Catalog(db: pack.db)
        // the two cards reported: commons that carried their secret rare's Cardmarket price
        for id in ["sm12-54", "sm12-68"] {
            let card = try XCTUnwrap(cat.cardExact(id, "en"))
            log("\(id): \(card.name): \(card.priceCheck.map { "\($0.status)" } ?? "-"), \(card.printings.map { "\($0.kind.rawValue) \($0.eur ?? -1) / \($0.usdEur ?? -1)" })")
            XCTAssertTrue(card.printings.allSatisfy { ($0.eur ?? 0) < 5 }, "\(id) still has a Cardmarket price over 5 EUR")
        }
    }

    /// The scanner's steadiness rules (ScanTracker): a sure card is taken after the delay, a kept one stays.
    func testScanTracker() {
        let t = ScanTracker(sureMargin: 0.085, likelyMargin: 0.038)
        t.delayMs = 300
        XCTAssertFalse(t.update("a", margin: 0.1, now: 0))
        XCTAssertFalse(t.update("a", margin: 0.1, now: 150))
        XCTAssertTrue(t.update("a", margin: 0.1, now: 320))
        XCTAssertEqual(t.shown, "a")
        // only yellow: three agreeing frames and the delay
        t.reset()
        XCTAssertFalse(t.update("b", margin: 0.05, now: 1000))
        XCTAssertFalse(t.update("b", margin: 0.05, now: 1100))
        XCTAssertTrue(t.update("b", margin: 0.05, now: 1400))
        // kept by hand: a sure other card needs 700 ms and two sure frames of the last five
        t.lock("c")
        XCTAssertFalse(t.update("d", margin: 0.1, now: 2000))
        XCTAssertFalse(t.update("d", margin: 0.1, now: 2500))
        XCTAssertTrue(t.update("d", margin: 0.1, now: 2750))
        XCTAssertEqual(t.shown, "d")
    }
}
