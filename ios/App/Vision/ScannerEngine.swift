import CoreGraphics
import Foundation
import UIKit

/// One card the matcher found: its artwork group (a card id), how well it matched, and how it was read.
struct Match: Equatable {
    let group: String
    let score: Float
    /// the reference entry (index into the pack)
    let entry: Int
    /// 0 card, 1 card upside down, 2 artwork, 3 artwork upside down
    let reading: Int
    let raw: CCHit

    var readingName: String { ScannerEngine.readings[max(0, min(3, reading))] }
    static func == (a: Match, b: Match) -> Bool { a.group == b.group && a.score == b.score && a.entry == b.entry && a.reading == b.reading }
}

/// What the scanner saw in one photo or frame.
struct Scan {
    /// card outline used (tl, tr, br, bl in image pixels): detected, or the guide frame; nil when nothing was read
    let quad: [CGPoint]?
    let detected: Bool
    let hits: [Match]
    /// taken by hand: the most likely cards over every outline read, best first (else = hits)
    let candidates: [Match]
    /// score gap between the best and second-best card: the confidence measure
    let margin: Float
    let timingsMs: [String: Double]
    let raw: CCRecognition

    var best: Match? { hits.first }
}

/// Card recognition (detector, fingerprint model, matcher) over the data pack; CardCore does the work.
final class ScannerEngine {
    static let readings = ["card", "card upside down", "artwork", "artwork upside down"]

    let pack: Pack
    let geometry: CCGeometry
    /// matches with at least this margin are sure
    let sureMargin: Float
    /// and with this one likely (the scanner waits for a second frame)
    let likelyMargin: Float
    private let fingerprinter: Fingerprinter
    private let recognizer: CCRecognizer
    private let lock = NSLock()

    init(pack: Pack) throws {
        self.pack = pack
        let g = CCGeometry()
        g.rectW = Int32(pack.metaInt("rect_w")); g.rectH = Int32(pack.metaInt("rect_h"))
        g.cardW = Int32(pack.metaInt("card_w")); g.cardH = Int32(pack.metaInt("card_h"))
        g.artX = Int32(pack.metaInt("art_x")); g.artY = Int32(pack.metaInt("art_y"))
        g.artW = Int32(pack.metaInt("art_w")); g.artH = Int32(pack.metaInt("art_h"))
        g.input = Int32(pack.metaInt("input"))
        let box = (pack.meta["art_box"] ?? "0.07,0.09,0.93,0.5").split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        let b = box.count == 4 ? box : [0.07, 0.09, 0.93, 0.5]
        (g.artX0, g.artY0, g.artX1, g.artY1) = (b[0], b[1], b[2], b[3])
        let sure = pack.metaFloat("margin_99", 0.085)
        let fp = try Fingerprinter(modelPath: pack.modelPath)
        geometry = g
        sureMargin = sure
        likelyMargin = pack.metaFloat("margin_90", 0.038)
        fingerprinter = fp
        recognizer = CCRecognizer(geometry: g, index: pack.index, n: Int32(pack.n), dim: Int32(pack.dim), groups: pack.groupData,
                                  sureMargin: sure, fingerprinter: fp)
    }

    /**
     * prior: where the guide frame expects the card (live camera); nil for photos.
     * manual: taken by hand (double tap): every outline and the guide frame are read, and a longer list comes back.
     */
    func recognize(_ img: RGBImage, prior: CCPrior? = nil, manual: Bool = false) -> Scan {
        lock.lock(); defer { lock.unlock() }
        let r = recognizer.recognizeRGB(img.data, width: Int32(img.width), height: Int32(img.height),
                                        hasPrior: prior != nil, prior: prior ?? CCPrior(cx: 0.5, cy: 0.5, h: 0.7), manual: manual)
        func m(_ h: CCHit) -> Match {
            Match(group: pack.groupNames[Int(h.group)], score: h.score, entry: Int(h.entry), reading: Int(h.reading), raw: h)
        }
        return Scan(quad: r.hasQuad ? r.quad.map(\.cgPointValue) : nil, detected: r.detected, hits: r.hits.map(m),
                    candidates: r.candidates.map(m), margin: r.margin,
                    timingsMs: r.timingsMs.mapValues(\.doubleValue), raw: r)
    }

    /// The card of a match straightened and upright (for reading its text, or showing it when it has no image).
    func cardImage(_ img: RGBImage, _ scan: Scan, _ hit: Match, margin: Double = 0.03, width: Int = 360, height: Int = 503) -> RGBImage? {
        lock.lock(); defer { lock.unlock() }
        guard let d = recognizer.cardImageRGB(img.data, width: Int32(img.width), height: Int32(img.height), recognition: scan.raw,
                                              hit: hit.raw, margin: margin, outWidth: Int32(width), outHeight: Int32(height)) else { return nil }
        return RGBImage(data: d, width: width, height: height)
    }

    /// The card's outer corners of a match (tl, tr, br, bl in the image's pixels); nil without an outline.
    func cardQuad(_ scan: Scan, _ hit: Match, margin: Double = 0) -> [CGPoint]? {
        lock.lock(); defer { lock.unlock() }
        return recognizer.cardQuad(of: scan.raw, hit: hit.raw, margin: margin)?.map(\.cgPointValue)
    }

    /// The part of an image inside a quad (tl, tr, br, bl), straightened to width x height.
    static func rectify(_ img: RGBImage, quad: [CGPoint], width: Int, height: Int) -> RGBImage {
        let d = CCRecognizer.rectifyRGB(img.data, width: Int32(img.width), height: Int32(img.height), quad: quad.map { NSValue(cgPoint: $0) },
                                        outWidth: Int32(width), outHeight: Int32(height))
        return RGBImage(data: d, width: width, height: height)
    }

    /// Entries the search must skip (nil: none), e.g. the cheap energy cards.
    func setExcluded(_ mask: [Bool]?) {
        lock.lock(); defer { lock.unlock() }
        recognizer.setExcluded(mask.map { m in Data(m.map { $0 ? UInt8(1) : 0 }) })
    }

    static func guidePrior(width: Int, height: Int, size: Double = 0.7) -> CCPrior {
        CCRecognizer.guidePriorWidth(Int32(width), height: Int32(height), size: size)
    }

    static func guideQuad(width: Int, height: Int, prior: CCPrior) -> [CGPoint] {
        CCRecognizer.guideQuadWidth(Int32(width), height: Int32(height), prior: prior).map(\.cgPointValue)
    }
}

extension ScannerEngine {
    /**
     * Read the card's printed text (number, language). When only the artwork box was found, its
     * orientation is a guess, so the other way round is tried too if the first attempt finds no number.
     */
    func readText(_ img: RGBImage, _ r: Scan, _ hit: Match, reader: TextReader) -> CardText? {
        func attempt(_ h: Match) -> CardText? {
            guard let card = cardImage(img, r, h, margin: 0.06, width: 720, height: 1006), let cg = card.uiImage?.cgImage else { return nil }
            return reader.read(cg)
        }
        let first = attempt(hit)
        if first?.number != nil { return first }
        let other = [1, 0, 3, 2][max(0, min(3, hit.reading))]
        let raw = CCHit.make(group: hit.raw.group, score: hit.raw.score, entry: hit.raw.entry, reading: Int32(other))
        guard let second = attempt(Match(group: hit.group, score: hit.score, entry: hit.entry, reading: other, raw: raw)) else { return first }
        func quality(_ t: CardText?) -> Double { (t?.number != nil ? 3 : 0) + (t?.langMargin ?? 0) }
        return quality(second) > quality(first) ? second : first
    }
}
