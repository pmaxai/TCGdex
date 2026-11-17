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
}
