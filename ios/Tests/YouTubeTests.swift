import XCTest
@testable import OverpricedCardboard

/// YouTube Mode: the pack (what goes in, +1, swipe, remove last, new pack, versions), the HIT rule and the pack's CSV.
@MainActor
final class YouTubeTests: XCTestCase {
    private var saved: [String: Any?] = [:]
    private let keys = ["youtube", "title", "threshold"]

    override func setUp() async throws {
        for k in keys { saved[k] = UserDefaults.standard.object(forKey: k) }
    }

    override func tearDown() async throws {
        for k in keys { if let v = saved[k] ?? nil { UserDefaults.standard.set(v, forKey: k) } else { UserDefaults.standard.removeObject(forKey: k) } }
    }

    private func scanner() throws -> (ScannerModel, Catalog) {
        guard let pack = ParityTests.pack ?? Pack.bundledDir.flatMap({ try? Pack(dir: $0) }) else { throw XCTSkip("no data pack") }
        let cat = Catalog(db: pack.db)
        return (ScannerModel(engine: try ScannerEngine(pack: pack), catalog: cat), cat)
    }

    private func candidate(_ cat: Catalog, _ id: String, _ lang: String = "en") throws -> Candidate {
        let c = try XCTUnwrap(cat.cardExact(id, lang) ?? cat.cardExact(id, "en"), "\(id) in the data pack")
        return Candidate(group: cat.groupOf(c.id), info: c, score: 0, reading: "")
    }

    func testThePack() throws {
        let (sc, cat) = try scanner()
        sc.settings.youtube = true
        sc.settings.threshold = 5
        var reasons: [PackReason] = []
        sc.packObserver = { r, _ in reasons.append(r) }
        // looked up: shown, not in the pack; +1 puts it in
        sc.lookUp(try candidate(cat, "base1-4"))
        XCTAssertTrue(sc.session.isEmpty)
        sc.addAgain()
        XCTAssertEqual(sc.session.count, 1)
        let first = try XCTUnwrap(sc.shown?.serial)
        XCTAssertEqual(sc.session.last?.serial, first)
        // +1 again: a copy of its own (its own serial), the same card twice in the pack
        sc.addAgain()
        XCTAssertEqual(sc.session.count, 2)
        XCTAssertEqual(Set(sc.session.map(\.group)).count, 1)
        XCTAssertNotEqual(sc.session[0].serial, sc.session[1].serial)
        XCTAssertGreaterThan(sc.sessionValue, 0)
        // another printing of the shown card: its place in the pack changes with it
        if let other = sc.shown?.priceCard.printings.first(where: { $0.kind != sc.shown?.selected?.kind }) {
            sc.choosePrinting(other.kind)
            XCTAssertEqual(sc.session.last?.shown.printing, other.kind)
            XCTAssertEqual(sc.session.first?.shown.printing == other.kind, false, "the first copy keeps its printing")
        }
        // swiped away: the shown copy leaves the pack, the first stays
        sc.dismissShown()
        XCTAssertNil(sc.shown)
        XCTAssertEqual(sc.session.map(\.serial), [first])
        // remove last, new pack
        sc.lookUp(try candidate(cat, "sv08-059", "de"))
        sc.addAgain()
        XCTAssertEqual(sc.session.count, 2)
        sc.undoLast()
        XCTAssertEqual(sc.session.count, 1)
        sc.resetSession()
        XCTAssertTrue(sc.session.isEmpty)
        XCTAssertEqual(sc.sessionValue, 0)
        XCTAssertTrue(reasons.contains(.addAgain) && reasons.contains(.swipe) && reasons.contains(.removeLast) && reasons.contains(.reset))
        XCTAssertEqual(reasons.first, .shown, "a looked-up card is shown (and logged) first")
    }

    func testVersionsStepRoundAndReplaceThePackCard() throws {
        let (sc, cat) = try scanner()
        sc.settings.youtube = true
        sc.lookUp(try candidate(cat, "sv08-059", "de"))
        sc.addAgain()
        let s = try XCTUnwrap(sc.shown)
        guard s.versions.count > 1 else { throw XCTSkip("one version only") }
        let start = s.versionIndex
        sc.stepVersion(1)
        XCTAssertEqual(sc.shown?.versionIndex, (start + 1) % s.versions.count)
        XCTAssertEqual(sc.session.last?.shown.version.id, sc.shown?.version.id)
        XCTAssertEqual(sc.session.last?.shown.version.lang, sc.shown?.version.lang)
        sc.stepVersion(-1)
        XCTAssertEqual(sc.shown?.versionIndex, start)
        XCTAssertEqual(sc.session.count, 1, "the same card, not a new one")
    }

    func testHitRule() {
        XCTAssertTrue(isHit(5, 5))
        XCTAssertFalse(isHit(4.99, 5))
        XCTAssertFalse(isHit(100, 0), "no alert price: no hits")
        XCTAssertFalse(isHit(nil, 5))
    }

    func testPackCsv() throws {
        let (sc, cat) = try scanner()
        sc.settings.youtube = true
        sc.lookUp(try candidate(cat, "base1-4"))
        sc.addAgain()
        sc.lookUp(try candidate(cat, "sv08-059", "de"))
        sc.addAgain()
        let items = sc.session
        // the US: commas, a dot for decimals
        let us = PackCsv.build(items, threshold: 5, title: "Surging Sparks", locale: Locale(identifier: "en_US"), timeZone: TimeZone(identifier: "UTC")!)
        XCTAssertTrue(us.hasPrefix("\u{FEFF}No,Scanned,Name,English name,Set,Number,Language,Card ID,Printing,Value,Currency,"))
        let lines = us.components(separatedBy: "\r\n")
        XCTAssertEqual(lines[0].split(separator: ",").count, PackCsv.header.count)
        XCTAssertTrue(lines[1].hasPrefix("1,"))
        XCTAssertTrue(lines[1].contains(",base1-4,"), lines[1])
        XCTAssertTrue(lines[2].contains(",de,"), lines[2])
        XCTAssertTrue(us.contains("\r\nCards,2\r\n"))
        XCTAssertTrue(us.contains("\r\nPack value EUR,"))
        XCTAssertTrue(us.hasSuffix("Title,Surging Sparks\r\n"))
        XCTAssertTrue(lines[1].contains("https://www.cardmarket.com/en/Pokemon/Products/Search?searchString="), lines[1])
        // Germany: semicolons, a comma for decimals
        let de = PackCsv.build(items, threshold: 5, title: "", locale: Locale(identifier: "de_DE"))
        XCTAssertTrue(de.hasPrefix("\u{FEFF}No;Scanned;Name;"))
        let value = de.components(separatedBy: "\r\n").first { $0.hasPrefix("Pack value EUR;") }
        XCTAssertTrue(value?.contains(",") == true, value ?? "")
        XCTAssertFalse(de.contains("Title;"))
        // the file's name: the title in front, then the date
        let name = PackCsv.fileName(title: "Surging Sparks box #1", now: Date(timeIntervalSince1970: 1_800_000_000), timeZone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(name, "Surging_Sparks_box_1_pack_2027-01-15_0800.csv")
        XCTAssertEqual(PackCsv.fileName(title: "", now: Date(timeIntervalSince1970: 1_800_000_000), timeZone: TimeZone(identifier: "UTC")!),
                       "pack_2027-01-15_0800.csv")
        XCTAssertEqual(PackCsv.formEncoded("Glurak-ex Pokémon"), "Glurak-ex+Pok%C3%A9mon")
    }

    func testCornersInPictureOrder() {
        let q = ScannerModel.clockwiseFromTopLeft([CGPoint(x: 0.9, y: 0.8), CGPoint(x: 0.1, y: 0.2), CGPoint(x: 0.1, y: 0.8), CGPoint(x: 0.9, y: 0.2)])
        XCTAssertEqual(q, [CGPoint(x: 0.1, y: 0.2), CGPoint(x: 0.9, y: 0.2), CGPoint(x: 0.9, y: 0.8), CGPoint(x: 0.1, y: 0.8)])
    }
}
