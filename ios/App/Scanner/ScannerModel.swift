import SwiftUI
import UIKit

/// The scanner's settings, kept on the phone.
struct ScannerSettings: Equatable {
    /// alert (vibrate) at this value or more, EUR (0 = off)
    var threshold: Double = 5
    var showPsa = true
    var showDebug = false
    /// how long the frame must stay green before the card is taken (0 = at once)
    var triggerDelayMs = 300
    /// energy cards: 0 = recognise, 1 = skip common energy, 2 = skip all energy
    var energyFilter = 0
    /// height of the capture frame, percent of the camera picture
    var guideSize = 70
    var zoom: Double = 1
    /// YouTube Mode instead of the scanner (the Scanner tab's mode; Creator plan)
    var youtube = false
    /// YouTube Mode's title line at the top of the picture (optional)
    var title = ""
    var guideFraction: Double { Double(guideSize) / 100 }

    static func load(_ d: UserDefaults = .standard) -> ScannerSettings {
        var s = ScannerSettings()
        if d.object(forKey: "threshold") != nil { s.threshold = d.double(forKey: "threshold") }
        if d.object(forKey: "show_psa") != nil { s.showPsa = d.bool(forKey: "show_psa") }
        s.showDebug = d.bool(forKey: "show_debug")
        if d.object(forKey: "trigger_delay") != nil { s.triggerDelayMs = d.integer(forKey: "trigger_delay") }
        s.energyFilter = d.integer(forKey: "energy_filter")
        if d.object(forKey: "guide_size") != nil { s.guideSize = d.integer(forKey: "guide_size") }
        if d.object(forKey: "zoom") != nil { s.zoom = max(1, d.double(forKey: "zoom")) }
        s.youtube = d.bool(forKey: "youtube")
        s.title = d.string(forKey: "title") ?? ""
        return s
    }

    func save(_ d: UserDefaults = .standard) {
        d.set(threshold, forKey: "threshold")
        d.set(showPsa, forKey: "show_psa")
        d.set(showDebug, forKey: "show_debug")
        d.set(triggerDelayMs, forKey: "trigger_delay")
        d.set(energyFilter, forKey: "energy_filter")
        d.set(guideSize, forKey: "guide_size")
        d.set(zoom, forKey: "zoom")
        d.set(youtube, forKey: "youtube")
        d.set(title, forKey: "title")
    }
}

/// What the camera queue needs to know of the scanner, without waiting for the main thread.
private final class FrameGate: @unchecked Sendable {
    private let lock = NSLock()
    private var _busy = false, _paused = false, _manual = false, _kept: String?, _aspect = 0.0, _guide = 0.7, _picking = false
    func tryBegin() -> Bool { lock.lock(); defer { lock.unlock() }; if _busy || _paused || _picking { return false }; _busy = true; return true }
    func end() { lock.lock(); _busy = false; lock.unlock() }
    func takeManual() -> Bool { lock.lock(); defer { lock.unlock() }; let m = _manual; _manual = false; return m }
    func set(paused: Bool? = nil, manual: Bool? = nil, kept: String?? = nil, aspect: Double? = nil, guide: Double? = nil, picking: Bool? = nil) {
        lock.lock(); defer { lock.unlock() }
        if let paused { _paused = paused }
        if let manual { _manual = manual }
        if let kept { _kept = kept }
        if let aspect { _aspect = aspect }
        if let guide { _guide = guide }
        if let picking { _picking = picking }
    }
    var kept: String? { lock.lock(); defer { lock.unlock() }; return _kept }
    /// a new frame would be looked at now
    var idle: Bool { lock.lock(); defer { lock.unlock() }; return !_busy && !_paused && !_picking }
    var aspect: Double { lock.lock(); defer { lock.unlock() }; return _aspect }
    var guide: Double { lock.lock(); defer { lock.unlock() }; return _guide }
}

/**
 * The scanner (the Android app's ScannerViewModel, scanner mode): camera frames are recognised on
 * the camera queue; which card is shown, and everything the person taps, is decided on the main
 * thread. The printed text (language, number) is read a moment later and refines the version.
 */
@MainActor
final class ScannerModel: ObservableObject {
    @Published private(set) var shown: Shown?
    /// after a double tap: the most likely cards to pick from
    @Published var picks: [Candidate]?
    @Published private(set) var liveQuad: [CGPoint]?
    @Published private(set) var liveFrame = CGSize(width: 1, height: 1)
    @Published private(set) var liveConfidence = Confidence.unsure
    @Published private(set) var liveProgress: Double?
    /// a photo or a looked-up card is shown: the camera waits
    @Published private(set) var paused = false
    @Published var message: String?
    /// grows with each double tap (the white flash)
    @Published private(set) var flash = 0
    @Published private(set) var debugLines: [String] = []
    @Published var settings: ScannerSettings { didSet { settingsChanged(oldValue) } }
    /// false in the free version: no price alerts (they would tell the price)
    var alertsAllowed = true
    /// YouTube Mode's pack: every card taken by the camera, in order (photos and looked-up cards only with +1)
    @Published private(set) var session: [SessionItem] = []
    var sessionValue: Double { session.reduce(0) { $0 + ($1.value ?? 0) } }
    /// the recording's log: told right after each change of the pack or of the shown card, and what caused it
    var packObserver: ((PackReason, _ source: String?) -> Void)?

    let camera = Camera()
    let engine: ScannerEngine
    let resolver: CardResolver
    var catalog: Catalog { resolver.catalog }
    private let tracker: ScanTracker
    private let reader = TextReader()
    private let gate = FrameGate()
    private let textQueue = DispatchQueue(label: "scanner.text", qos: .userInitiated)
    /// recognition runs here, so the camera's queue stays free (a recording's camera file gets every frame)
    private let recognizeQueue = DispatchQueue(label: "scanner.recognize", qos: .userInitiated)
    /// the size of the camera's whole frame (the analysed one is its centred part)
    private var fullFrame = CGSize.zero
    private var serial = 0
    private var kept: String? { didSet { gate.set(kept: .some(kept)) } }
    private var rejected: Set<String> = []
    private var lastPicks: [Candidate] = []
    private var lastText: CardText?
    private var lastAlerted: String?

    init(engine: ScannerEngine, catalog: Catalog) {
        self.engine = engine
        resolver = CardResolver(catalog: catalog, pack: engine.pack)
        tracker = ScanTracker(sureMargin: engine.sureMargin, likelyMargin: engine.likelyMargin)
        settings = ScannerSettings.load()
        tracker.delayMs = Int64(settings.triggerDelayMs)
        gate.set(guide: settings.guideFraction)
        applyEnergyFilter(settings.energyFilter)
        camera.onFrame = { [weak self] img in self?.onFrame(img) }
        camera.wantsFrame = { [gate] in gate.idle }
    }

    // ------------------------------------------------------------------ frames (camera queue)

    /// The camera box's width / height: frames are cut to what it shows.
    func setViewAspect(_ a: Double) { gate.set(aspect: a) }

    nonisolated private func onFrame(_ frame: RGBImage) {
        guard gate.tryBegin() else { return }
        recognizeQueue.async { [weak self] in self?.recognize(frame) }
    }

    nonisolated private func recognize(_ full: RGBImage) {
        let fullSize = CGSize(width: full.width, height: full.height)
        let img = full.visiblePart(aspect: gate.aspect) ?? full
        let prior = ScannerEngine.guidePrior(width: img.width, height: img.height, size: gate.guide)
        let manual = gate.takeManual()
        let keep = gate.kept
        let t0 = Date()
        let r = engine.recognize(img, prior: prior, manual: manual || keep != nil)
        let ms = Date().timeIntervalSince(t0) * 1000
        DispatchQueue.main.async { [self] in
            MainActor.assumeIsolated {
                fullFrame = fullSize
                if manual { takeByHand(r, img) } else { analysed(r, img, ms: ms) }
            }
            gate.end()
        }
    }

    private func confidence(_ r: Scan) -> Confidence {
        guard r.best != nil else { return .unsure }
        if r.margin >= engine.sureMargin { return .sure }
        if r.margin >= engine.likelyMargin { return .likely }
        return .unsure
    }

    private func analysed(_ r: Scan, _ img: RGBImage, ms: Double) {
        let conf = confidence(r)
        if picks == nil {
            var vote = ScanTracker.voteWhileKept(best: r.best, candidates: r.candidates, kept: kept, sureMargin: engine.sureMargin)
            if kept != nil, let v = vote, rejected.contains(v.group) { vote = nil }
            let changed = tracker.update(vote?.group, margin: vote == nil ? 0 : r.margin)
            if changed, let g = tracker.shown {
                kept = nil
                rejected = []
                lastPicks = []
                showNew(g, r, img, fromPhoto: false, confidence: conf)
            }
        }
        publishLive(r, img, conf, ms: ms)
    }

    /// Double tap on the camera: the next frame is taken by hand.
    func manualTrigger() {
        if paused { return }
        gate.set(manual: true)
        flash += 1
    }

    /**
     * Take the card in the frame now: every outline plus the guide frame itself were read, the best
     * match is shown and kept, however unsure it is.
     */
    private func takeByHand(_ r: Scan, _ img: RGBImage) {
        guard let best = r.best else { message = "No card found in the frame"; return }
        tracker.lock(best.group)
        kept = best.group
        rejected = []
        showNew(best.group, r, img, fromPhoto: false, confidence: .likely, source: "double_tap")
        let list = r.candidates.compactMap { resolver.candidate(of: $0) }
        lastPicks = list
        if list.count > 1 { picks = list; gate.set(picking: true) }
        publishLive(r, img, confidence(r), ms: 0)
    }

    /// A photo from the library: recognised once, the camera waits.
    func onPhoto(_ img: RGBImage) {
        paused = true
        gate.set(paused: true)
        Task.detached(priority: .userInitiated) { [engine] in
            let r = engine.recognize(img)
            await MainActor.run {
                if let best = r.best { self.showNew(best.group, r, img, fromPhoto: true, confidence: self.confidence(r)) }
                else { self.message = "No card recognised on this photo." }
                self.publishLive(r, img, self.confidence(r), ms: 0)
            }
        }
    }

    private func showNew(_ group: String, _ r: Scan, _ img: RGBImage, fromPhoto: Bool, confidence conf: Confidence, source: String = "camera") {
        let hit = r.hits.first { $0.group == group }
        // shown at once from the picture; the printed text (language, set number) refines it a moment later
        guard let version = resolver.resolveVersion(group: group, entry: hit?.entry, text: nil) else { return }
        let p = engine.pack
        let alternatives = r.hits.filter { $0.group != group }.prefix(3).compactMap { h in
            catalog.card(p.entryCard[h.entry], p.entryLang[h.entry]).map { Candidate(group: h.group, info: $0, score: h.score, reading: h.readingName) }
        }
        lastText = nil
        let scan = hit.flatMap { engine.cardImage(img, r, $0)?.uiImage }
        serial += 1
        let first = resolver.build(group: group, version: version, text: nil, confidence: conf, alternatives: alternatives,
                                   locked: false, fromPhoto: fromPhoto, scan: scan, serial: serial)
        shown = first
        picks = nil
        gate.set(picking: false)
        if settings.youtube && !fromPhoto { addToSession(first) }
        logPack(.shown, fromPhoto ? "photo" : source)
        guard let hit else { maybeAlert(first); return }
        // the card straightened, upright, for reading its text
        let textReader = reader, eng = engine
        textQueue.async { [weak self] in
            let text = eng.readText(img, r, hit, reader: textReader)
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.refine(first.serial, group, hit, text) } }
        }
    }

    /// The printed text has been read: switch to the exact version (language, set) if it differs.
    private func refine(_ serialShown: Int, _ group: String, _ hit: Match, _ text: CardText?) {
        guard let cur = shown, cur.serial == serialShown else { return }
        lastText = text
        if cur.versionByUser { return }
        let version = resolver.resolveVersion(group: group, entry: hit.entry, text: text)
        var updated = cur
        if let version, version.id != cur.version.id || version.lang != cur.version.lang {
            var b = resolver.build(group: group, version: version, text: text, confidence: cur.confidence, alternatives: cur.alternatives,
                                   locked: cur.locked, fromPhoto: cur.fromPhoto, scan: cur.scan, serial: cur.serial)
            let keep = cur.printingByUser && b.priceCard.printings.contains { $0.kind == cur.printing } ? cur.printing : nil
            b.printing = keep ?? b.printing
            b.printingByUser = keep != nil
            updated = b
        } else {
            updated.text = text
        }
        shown = updated
        replaceInSession(updated)
        logPack(.textRead)
        maybeAlert(updated)
    }

    private func publishLive(_ r: Scan, _ img: RGBImage, _ conf: Confidence, ms: Double) {
        liveQuad = r.quad
        liveFrame = CGSize(width: img.width, height: img.height)
        liveConfidence = conf
        let delay = settings.triggerDelayMs
        liveProgress = delay > 0 ? tracker.streakMillis().map { min(1, max(0, Double($0) / Double(delay))) } : nil
        guard settings.showDebug || shown?.fromPhoto == true else { return }
        var lines = [String(format: "card found: %@   margin %.3f (%@)", r.detected ? "yes" : "no", r.margin, "\(conf)")]
        lines.append(r.timingsMs.sorted { $0.key < $1.key }.map { "\($0.key) \(Int($0.value)) ms" }.joined(separator: "  ") + (ms > 0 ? String(format: "  (all %.0f ms)", ms) : ""))
        for (i, h) in r.hits.prefix(5).enumerated() {
            let name = catalog.card(engine.pack.entryCard[h.entry], "en")?.name ?? h.group
            lines.append(String(format: "%ld. %.3f  %@  %@  [%@]", i + 1, h.score, h.group, name, h.readingName))
        }
        if let t = shown?.text {
            lines.append("printed number: \(t.number ?? "?")/\(t.total ?? "?")   language: \(t.lang ?? "?") (+\(String(format: "%.1f", t.langMargin)))")
            lines.append("language scores: " + t.langScores.sorted { $0.value > $1.value }.prefix(4).map { "\($0.key) \(String(format: "%.1f", $0.value))" }.joined(separator: " "))
            lines.append("name bar: \(t.nameText.prefix(40))")
            if !t.script.isEmpty { lines.append("script: \(t.script)") }
        }
        debugLines = lines
    }

    private func maybeAlert(_ sh: Shown) {
        // YouTube Mode shows a HIT instead (a vibration would shake the tripod)
        guard alertsAllowed, !settings.youtube, sh.confidence != .unsure, settings.threshold > 0, let best = sh.priceCard.bestValue else { return }
        // any printing above the threshold: a valuable reverse holo must not slip through
        if best < settings.threshold || lastAlerted == sh.group { return }
        lastAlerted = sh.group
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    // ------------------------------------------------------------------ what the person taps

    /// Picked from the list after a double tap (the shown card: just close it).
    func pick(_ c: Candidate) {
        closePicks()
        if c.group != shown?.group { choose(c) }
    }

    func closePicks() {
        picks = nil
        gate.set(picking: false)
    }

    /// The person says it is this other card: show it and keep it.
    func choose(_ c: Candidate) {
        guard let s = shown else { return }
        tracker.lock(c.group)
        kept = c.group
        rejected = rejectedBy(s, c.group)
        let version = resolver.resolveVersion(group: c.group, entry: nil, text: lastText) ?? c.info
        let alternatives = ([Candidate(group: s.group, info: s.version, score: 0, reading: "")] + s.alternatives).filter { $0.group != c.group }
        serial += 1
        var b = resolver.build(group: c.group, version: version, text: lastText, confidence: .sure, alternatives: alternatives,
                               locked: true, fromPhoto: s.fromPhoto, scan: s.scan, serial: serial)
        b.versionByUser = true
        shown = b
        replaceInSession(b, oldSerial: s.serial)
        logPack(.otherCard)
        maybeAlert(b)
    }

    /// Another version of the same artwork (set, language): same card, its own prices.
    func chooseVersion(_ info: CardInfo) {
        guard let s = shown else { return }
        var b = resolver.build(group: s.group, version: info, text: s.text, confidence: s.confidence, alternatives: s.alternatives,
                               locked: true, fromPhoto: s.fromPhoto, scan: s.scan, serial: s.serial)
        b.printing = s.printing
        b.printingByUser = s.printingByUser
        b.versionByUser = true
        b.fromSearch = s.fromSearch
        if b.priceCard.printing(s.printing)?.kind != s.printing { b.printing = defaultPrinting(b.priceCard) }
        tracker.lock(s.group)
        shown = b
        replaceInSession(b)
        logPack(.version)
    }

    /// Step through the versions of the shown card (+1 next, -1 previous).
    func stepVersion(_ step: Int) {
        guard let s = shown, s.versions.count > 1 else { return }
        let n = s.versions.count, i = max(0, s.versionIndex)
        chooseVersion(s.versions[((i + step) % n + n) % n])
    }

    func choosePrinting(_ kind: Printing.Kind) {
        guard var s = shown else { return }
        s.printing = kind
        s.printingByUser = true
        s.locked = true
        tracker.lock(s.group)
        shown = s
        replaceInSession(s)
        logPack(.printing)
    }

    /// A card from the last scans (Trading's tray): shown again and kept.
    func show(_ s: Shown) {
        tracker.lock(s.group)
        kept = s.group
        rejected = []
        shown = s
    }

    /// Forget the current card and look for the next one.
    func next() {
        tracker.reset()
        kept = nil
        rejected = []
        shown = nil
        closePicks()
        setPaused(false)
        logPack(.next)
    }

    /// Tap on the waiting camera: scan again.
    func resume() {
        tracker.reset()
        kept = nil
        if shown?.fromPhoto == true { shown = nil }
        setPaused(false)
    }

    private func setPaused(_ p: Bool) {
        paused = p
        gate.set(paused: p)
    }

    /// Looked up without the card in front of the camera: shown, the camera pauses.
    func lookUp(_ c: Candidate) {
        tracker.reset()
        kept = nil
        rejected = []
        lastText = nil
        serial += 1
        var b = resolver.build(group: c.group, version: c.info, text: nil, confidence: .sure, alternatives: [], locked: true,
                               fromPhoto: true, scan: nil, serial: serial)
        b.versionByUser = true
        b.fromSearch = true
        shown = b
        closePicks()
        setPaused(true)
        logPack(.shown, "search")
    }

    /// The shown card is this one (found in the search): it takes its place.
    func replaceWith(_ c: Candidate) {
        guard let s = shown else { return lookUp(c) }
        tracker.lock(c.group)
        kept = c.group
        rejected = rejectedBy(s, c.group)
        let alternatives = ([Candidate(group: s.group, info: s.version, score: 0, reading: "")] + s.alternatives).filter { $0.group != c.group }
        serial += 1
        var b = resolver.build(group: c.group, version: c.info, text: lastText, confidence: .sure, alternatives: alternatives,
                               locked: true, fromPhoto: s.fromPhoto, scan: s.scan, serial: serial)
        b.versionByUser = true
        b.fromSearch = s.fromSearch
        shown = b
        closePicks()
        replaceInSession(b, oldSerial: s.serial)
        logPack(.searchReplace)
        maybeAlert(b)
    }

    /// What the person said the card isn't: the shown card, its alternatives, the list after a double tap.
    private func rejectedBy(_ s: Shown, _ chosen: String) -> Set<String> {
        Set(([s.group] + s.alternatives.map(\.group) + lastPicks.map(\.group)).filter { $0 != chosen })
    }

    // ------------------------------------------------------------------ the pack (YouTube Mode)

    /// A new card from the camera into the pack: not when unsure, not the same card twice in a row (+1 does that).
    private func addToSession(_ sh: Shown) {
        if sh.confidence == .unsure || session.last?.group == sh.group { return }
        session.append(SessionItem(serial: sh.serial, time: Date(), shown: sh.forPack))
    }

    /// The shown card changed (its text was read, another printing, version or card): so did its place in the pack.
    private func replaceInSession(_ sh: Shown, oldSerial: Int? = nil) {
        let old = oldSerial ?? sh.serial
        guard let i = session.firstIndex(where: { $0.serial == old }) else { return }
        session[i] = SessionItem(serial: sh.serial, time: session[i].time, shown: sh.forPack)
    }

    /// New pack: the value starts at zero.
    func resetSession() {
        session = []
        logPack(.reset)
    }

    /**
     * +1: the shown card goes into the pack once more (two of the same card in a row are otherwise
     * counted once). The copy is a card of its own: its printing can differ, and swiping it away
     * leaves the first one in the pack. Also adds a card the scanner wasn't sure enough about, a
     * photo or a looked-up card.
     */
    func addAgain() {
        guard var s = shown else { return }
        serial += 1
        s.serial = serial
        shown = s
        session.append(SessionItem(serial: s.serial, time: Date(), shown: s.forPack))
        logPack(.addAgain)
    }

    func undoLast() {
        guard !session.isEmpty else { return }
        session.removeLast()
        logPack(.removeLast)
    }

    /// Swiped away: the card leaves the pack. It is not taken again until another card was in front of the camera.
    func dismissShown() {
        guard let s = shown else { return }
        kept = nil
        shown = nil
        closePicks()
        session.removeAll { $0.serial == s.serial }
        logPack(.swipe)
    }

    /// The card's corners in the camera's whole frame (camera.mp4), 0–1, clockwise from the top left; nil without a live outline.
    func cameraQuad() -> [CGPoint]? {
        guard let q = liveQuad, q.count == 4, !paused, shown?.fromPhoto != true, fullFrame.width > 0, fullFrame.height > 0 else { return nil }
        let ox = (fullFrame.width - liveFrame.width) / 2, oy = (fullFrame.height - liveFrame.height) / 2
        return Self.clockwiseFromTopLeft(q.map { CGPoint(x: (ox + $0.x) / fullFrame.width, y: (oy + $0.y) / fullFrame.height) })
    }

    /// Four corners in picture order: clockwise (on screen, y down) from the one nearest the top left; each 0–1.
    nonisolated static func clockwiseFromTopLeft(_ p: [CGPoint]) -> [CGPoint] {
        guard !p.isEmpty else { return p }
        let cx = p.map(\.x).reduce(0, +) / CGFloat(p.count), cy = p.map(\.y).reduce(0, +) / CGFloat(p.count)
        let sorted = p.sorted { atan2($0.y - cy, $0.x - cx) < atan2($1.y - cy, $1.x - cx) }
        let start = sorted.indices.min { sorted[$0].x + sorted[$0].y < sorted[$1].x + sorted[$1].y } ?? 0
        return sorted.indices.map { i in
            let q = sorted[(start + i) % sorted.count]
            return CGPoint(x: min(1, max(0, q.x)), y: min(1, max(0, q.y)))
        }
    }

    /// what the pack and the shown card are now: the recording's log compares it with the last time
    private func logPack(_ reason: PackReason, _ source: String? = nil) { packObserver?(reason, source) }

    func zoom(by factor: Double) { setZoom(settings.zoom * factor) }

    func setZoom(_ z: Double) {
        let m = Double(camera.maxZoom / max(1, camera.zoomBase))
        settings.zoom = min(max(1, z), max(1, m))
    }

    private func settingsChanged(_ old: ScannerSettings) {
        settings.save()
        tracker.delayMs = Int64(settings.triggerDelayMs)
        gate.set(guide: settings.guideFraction)
        if settings.zoom != old.zoom { camera.setZoom(settings.zoom) }
        if settings.energyFilter != old.energyFilter { applyEnergyFilter(settings.energyFilter) }
    }

    /// Energy cards the scanner skips (the packs' fillers, or all of them).
    private func applyEnergyFilter(_ mode: Int) {
        let p = engine.pack
        guard mode > 0 else { engine.setExcluded(nil); return }
        let ids = catalog.energyIds(commonOnly: mode == 1)
        engine.setExcluded(ids.isEmpty ? nil : p.entryCard.map { ids.contains($0) })
    }
}
