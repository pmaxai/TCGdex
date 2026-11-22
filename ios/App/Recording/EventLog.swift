import Foundation
import QuartzCore

/// Recording (YouTube Mode): the screen as a video, a log of every card shown, both uploaded to S3 or saved in Photos.
enum Recording {
    /// the event log's format (the same as the Android app's); a breaking change moves it to v2
    static let schema = "tcgdex.recording.v1"
    /// the reveal's animation lengths, for the pipeline
    static let countUpMs = 900
    static let hitBurstMs = 1600
}

/// JSON helpers for the recording's files: null as NSNull, sorted keys, numbers rounded to cents.
enum RecJSON {
    static func n(_ v: Double?) -> Any { v.map { round2($0) as Any } ?? NSNull() }
    static func s(_ v: String?) -> Any { v.map { $0 as Any } ?? NSNull() }
    static func i(_ v: Int?) -> Any { v.map { $0 as Any } ?? NSNull() }
    static func round2(_ v: Double) -> Double { (v * 100).rounded() / 100 }

    static func data(_ o: Any, pretty: Bool = true) -> Data {
        var opts: JSONSerialization.WritingOptions = [.sortedKeys, .withoutEscapingSlashes]
        if pretty { opts.insert(.prettyPrinted) }
        return (try? JSONSerialization.data(withJSONObject: o, options: opts)) ?? Data("{}".utf8)
    }

    static func text(_ o: Any, pretty: Bool = true) -> String { String(decoding: data(o, pretty: pretty), as: UTF8.self) }

    static func read(_ url: URL) -> JSON? {
        (try? Data(contentsOf: url)).flatMap { try? JSONSerialization.jsonObject(with: $0) as? JSON }
    }

    static func iso(_ d: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: d)
    }

    static func printingName(_ k: Printing.Kind?) -> String? {
        switch k {
        case .normal: return "normal"
        case .holo: return "holo"
        case .reverse: return "reverse_holo"
        case .firstEdition: return "first_edition"
        case nil: return nil
        }
    }

    static func cardJson(_ s: Shown) -> JSON {
        let v = s.version
        let en = s.english ?? (v.lang == "en" ? v : nil)
        return ["id": v.id, "lang": v.lang, "name": v.name, "name_en": self.s(en?.name), "set": v.setName, "set_en": self.s(en?.setName),
                "number": v.number, "number_text": v.numberText,
                "release_year": i((v.year ?? s.priceCard.year).flatMap { Int($0) }),
                "rarity": self.s(s.priceCard.rarity ?? v.rarity), "printing": self.s(printingName(s.printing)),
                "printing_by_user": s.printingByUser, "version_by_user": s.versionByUser]
    }

    static func priceJson(_ s: Shown) -> JSON {
        let p = s.selected
        let basis: String? = p?.eur != nil ? "cardmarket" : p?.usdEur != nil ? "tcgplayer_eur" : p?.usd != nil ? "tcgplayer_usd" : nil
        let psa: Any = s.graded.map { g -> JSON in
            ["10": ["usd": n(g.psa10), "sales": i(g.psa10n)], "9": ["usd": n(g.psa9), "sales": i(g.psa9n)], "8": ["usd": n(g.psa8), "sales": i(g.psa8n)]]
        } ?? NSNull()
        let gem = s.population.flatMap { pop in (pop.gemRate ?? pop.psa10.map { Double($0) / Double(max(1, pop.psaTotal)) }).map { $0 * 100 } }
        var cm: Any = NSNull()
        if p?.eur != nil || p?.eur30 != nil || p?.eurLow != nil { cm = ["trend": n(p?.eur), "avg30": n(p?.eur30), "low": n(p?.eurLow)] as JSON }
        let currency: String? = basis == "tcgplayer_usd" ? "USD" : basis == nil ? nil : "EUR"
        return ["value": n(s.value), "currency": self.s(currency),
                "basis": self.s(basis), "cardmarket": cm, "tcgplayer_usd": n(p?.usd), "from_english": s.pricedFromEnglish,
                "prices_updated": self.s(s.priceCard.prices?.updated.map { String($0.prefix(10)) }),
                "psa": psa, "gem_rate_pct": n(gem.map { ($0 * 10).rounded() / 10 }),
                "warning": self.s(s.priceCard.priceCheck?.message),
                "all_printings": s.priceCard.printings.map { ["printing": self.s(printingName($0.kind)), "value": n($0.value)] as JSON }]
    }

    static func quadJson(_ q: [CGPoint]?) -> Any {
        guard let q else { return NSNull() }
        return q.map { [($0.x * 1000).rounded() / 1000, ($0.y * 1000).rounded() / 1000] }
    }
}

/**
 * The recording's event log (the Android app's EventLog): every change of the pack or of the shown
 * card, with its time in the video. The scanner reports each change (observe, with what caused
 * it); the log works out what happened by comparing the pack and the shown card with the last
 * report, so no change can slip through. Lines go to `events.jsonl` on a background queue (synced
 * every event); finish turns them into `events.json` (schema tcgdex.recording.v1) with the final
 * pack and totals.
 *
 * A card's reveal (`card_shown`, `card_added_again`) takes its time from the screen: when the
 * reveal's first frame is on screen (revealDrawn), not when the state changed. The event waits for
 * that (at most a second; any other event first writes it with the state's time), so the log
 * stays in order. Times are host time (CACurrentMediaTime), the clock of the video's frames.
 */
final class EventLog: @unchecked Sendable {
    let recordingId: String
    private let jsonl: URL
    private let threshold: Double
    private let nanos: () -> Int64
    private let wall: () -> Date
    private let revealTiming: Bool
    private let writer = DispatchQueue(label: "recording.log", qos: .utility)

    private final class Entry { let entry: Int; let beforeRecording: Bool; var firstT: Int64?
        init(_ e: Int, _ b: Bool, _ t: Int64?) { entry = e; beforeRecording = b; firstT = t } }
    /// a reveal waiting for its first frame on screen
    private final class Pending { let type: String; let stateT: Int64; let serial: Int; let entry: Entry?; let fields: JSON
        init(_ type: String, _ t: Int64, _ serial: Int, _ e: Entry?, _ f: JSON) { self.type = type; stateT = t; self.serial = serial; entry = e; fields = f } }

    private let lock = NSRecursiveLock()
    private var pending: Pending?
    private var drawnEarly: [(Int, Int64)] = []
    private var seq = 0
    private var lastT: Int64 = 0
    private var nextEntry = 1
    /// the video's time zero: its first frame (same clock as nanos)
    private(set) var zeroNanos: Int64?
    private var entries: [Int: Entry] = [:]
    private var lastSession: [(Int, String)] = []
    private var lastShownSerial: Int?
    private var lastShownKey: String?
    private var wallZero: Date?

    static func hostNanos() -> Int64 { Int64(CACurrentMediaTime() * 1_000_000_000) }

    init(recordingId: String, jsonl: URL, threshold: Double, nanos: @escaping () -> Int64 = EventLog.hostNanos,
         wall: @escaping () -> Date = Date.init, revealTiming: Bool = false) {
        self.recordingId = recordingId
        self.jsonl = jsonl
        self.threshold = threshold
        self.nanos = nanos
        self.wall = wall
        self.revealTiming = revealTiming
    }

    /// what makes a card's entry different for the log: version, printing, price
    static func key(_ s: Shown) -> String { "\(s.version.id)/\(s.version.lang)/\(s.printing?.rawValue ?? "nil")/\(s.value.map { String($0) } ?? "nil")/\(s.group)" }

    private func hit(_ s: Shown) -> Bool { isHit(s.value, threshold) }

    /// The first frame is in the video: t = 0. The cards already in the pack are entries 1…k.
    func start(firstFrameNanos: Int64, packAtStart: [SessionItem], shown: Shown?) {
        lock.lock(); defer { lock.unlock() }
        zeroNanos = firstFrameNanos
        var pack: [JSON] = []
        for item in packAtStart {
            let e = Entry(nextEntry, true, nil)
            nextEntry += 1
            entries[item.serial] = e
            pack.append(["entry": e.entry, "before_recording": true, "hit": hit(item.shown),
                         "card": RecJSON.cardJson(item.shown), "price": RecJSON.priceJson(item.shown)])
        }
        lastSession = packAtStart.map { ($0.serial, Self.key($0.shown)) }
        lastShownSerial = shown?.serial
        lastShownKey = shown.map(Self.key)
        emit("recording_started", 0) { $0["pack_at_start"] = pack }
    }

    /// A change of the pack or the shown card, right after it happened. quad: the card's corners in camera.mp4 (0–1).
    func observe(_ reason: PackReason, source: String?, shown: Shown?, session: [SessionItem], quad: [CGPoint]?) {
        lock.lock(); defer { lock.unlock() }
        guard zeroNanos != nil else { return }
        let t = now()
        let inPack = Set(session.map(\.serial))

        if reason == .reset {
            if !lastSession.isEmpty { emit("pack_reset", t) { _ in } }
            entries.removeAll()
            lastSession = []
            remember(shown, session)
            return
        }

        let old = lastSession
        let new = session.map { ($0.serial, Self.key($0.shown)) }
        if new.count == old.count {
            for (i, (serial, k)) in new.enumerated() {
                let (oldSerial, oldKey) = old[i]
                if serial != oldSerial || k != oldKey {
                    let e = entries.removeValue(forKey: oldSerial) ?? newEntry(t)
                    entries[serial] = e
                    updated(t, e.entry, reason, session[i].shown, quad)
                }
            }
        } else if new.count < old.count {
            for (s, _) in old where !inPack.contains(s) {
                guard let e = entries.removeValue(forKey: s) else { continue }
                emit("card_removed", t) { $0["entry"] = e.entry; $0["reason"] = reason == .removeLast ? "remove_last" : "swipe" }
            }
        } else {
            for item in session where entries[item.serial] == nil {
                let e = newEntry(t)
                entries[item.serial] = e
                if reason == .addAgain {
                    let copyOf = lastShownSerial.flatMap { entries[$0]?.entry }
                    reveal("card_added_again", t, item.serial, e, ["entry": e.entry, "copy_of": RecJSON.i(copyOf), "hit": hit(item.shown),
                                                                   "card": RecJSON.cardJson(item.shown), "price": RecJSON.priceJson(item.shown)])
                } else {
                    shownEvent(t, e, true, source, item.serial, item.shown, quad)
                }
            }
        }

        // the shown card outside the pack: on screen, so the pipeline must know it too
        if let shown, !inPack.contains(shown.serial) {
            if shown.serial != lastShownSerial { shownEvent(t, nil, false, source, shown.serial, shown, quad) }
            else if Self.key(shown) != lastShownKey { updated(t, nil, reason, shown, quad) }
        } else if shown == nil, let last = lastShownSerial {
            // the panel is empty (Next card); a swiped pack card was logged as removed above
            let wasInPack = old.contains { $0.0 == last }
            if !(reason == .swipe && wasInPack) { emit("card_cleared", t) { _ in } }
        }
        remember(shown, session)
    }

    /// The last frame: the recording ends (error: what stopped it, with the system's codes).
    func stop(reason: String, error: String? = nil) {
        lock.lock(); defer { lock.unlock() }
        if zeroNanos != nil {
            emit("recording_stopped", now()) { o in
                o["reason"] = reason
                if let error { o["error"] = error }
            }
        }
    }

    /// The reveal of the card with this serial is on screen now (drawNanos, host time): its waiting event takes this time.
    func revealDrawn(_ serial: Int, drawNanos: Int64) {
        lock.lock(); defer { lock.unlock() }
        guard revealTiming, zeroNanos != nil else { return }
        if let p = pending, p.serial == serial { flushPending(at: drawT(drawNanos)) }
        else {
            drawnEarly.append((serial, drawNanos))
            if drawnEarly.count > 8 { drawnEarly.removeFirst(drawnEarly.count - 8) }
        }
    }

    private func newEntry(_ t: Int64) -> Entry {
        let e = Entry(nextEntry, false, t)
        nextEntry += 1
        return e
    }

    private func drawT(_ n: Int64) -> Int64 { (n - (zeroNanos ?? n)) / 1_000_000 }

    /// A reveal: written now, or (with reveal timing) when its first frame is on screen.
    private func reveal(_ type: String, _ t: Int64, _ serial: Int, _ e: Entry?, _ fields: JSON) {
        if !revealTiming { emit(type, t) { o in for (k, v) in fields { o[k] = v } }; return }
        flushPending()
        let p = Pending(type, t, serial, e, fields)
        pending = p
        if let i = drawnEarly.firstIndex(where: { $0.0 == serial }) {
            let early = drawnEarly.remove(at: i).1
            flushPending(at: drawT(early))
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self else { return }
            self.lock.lock(); defer { self.lock.unlock() }
            if self.pending === p { self.flushPending() }
        }
    }

    /// Writes the waiting reveal: at its drawn time, else at the time its state changed. Never before the last event.
    private func flushPending(at: Int64? = nil) {
        guard let p = pending else { return }
        pending = nil
        let t = max(at ?? p.stateT, lastT)
        if let e = p.entry, e.firstT == p.stateT { e.firstT = t }
        write(p.type, t) { o in for (k, v) in p.fields { o[k] = v } }
    }

    private func remember(_ shown: Shown?, _ session: [SessionItem]) {
        lastSession = session.map { ($0.serial, Self.key($0.shown)) }
        lastShownSerial = shown?.serial
        lastShownKey = shown.map(Self.key)
    }

    private func shownEvent(_ t: Int64, _ e: Entry?, _ inPack: Bool, _ source: String?, _ serial: Int, _ s: Shown, _ quad: [CGPoint]?) {
        reveal("card_shown", t, serial, e, ["entry": RecJSON.i(e?.entry), "in_pack": inPack, "source": source ?? "camera",
                                            "confidence": "\(s.confidence)", "hit": hit(s), "card_quad": RecJSON.quadJson(quad),
                                            "card": RecJSON.cardJson(s), "price": RecJSON.priceJson(s)])
    }

    private func updated(_ t: Int64, _ entry: Int?, _ reason: PackReason, _ s: Shown, _ quad: [CGPoint]?) {
        emit("card_updated", t) { o in
            o["entry"] = RecJSON.i(entry); o["reason"] = reason.wire ?? "text_read"; o["hit"] = self.hit(s)
            o["card_quad"] = RecJSON.quadJson(quad); o["card"] = RecJSON.cardJson(s); o["price"] = RecJSON.priceJson(s)
        }
    }

    /// video time now; never before the last written event
    private func now() -> Int64 {
        guard let z = zeroNanos else { return 0 }
        return max((nanos() - z) / 1_000_000, lastT)
    }

    /// An event at t; a waiting reveal goes first, so the log stays in order.
    private func emit(_ type: String, _ t: Int64, _ fill: (inout JSON) -> Void) {
        flushPending()
        write(type, max(t, lastT), fill)
    }

    private func write(_ type: String, _ t: Int64, _ fill: (inout JSON) -> Void) {
        lastT = t
        seq += 1
        var o: JSON = ["seq": seq, "t_ms": t, "at": RecJSON.iso(wallAt(t)), "type": type]
        fill(&o)
        let line = RecJSON.data(o, pretty: false) + Data("\n".utf8)
        let url = jsonl
        writer.async {
            if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
            guard let h = try? FileHandle(forWritingTo: url) else { return }
            defer { try? h.close() }
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: line)
            try? h.synchronize()
        }
    }

    /// wall-clock time of video time t
    private func wallAt(_ t: Int64) -> Date {
        let z = wallZero ?? {
            let since = zeroNanos.map { Double(nanos() - $0) / 1_000_000_000 } ?? 0
            let w = wall().addingTimeInterval(-since)
            wallZero = w
            return w
        }()
        return z.addingTimeInterval(Double(t) / 1000)
    }

    /// The entry numbers of the pack's cards (for events.json and the submit screen).
    func entry(of serial: Int) -> Int? { lock.lock(); defer { lock.unlock() }; return entries[serial]?.entry }

    /**
     * events.json: the header, every event written so far, the final pack and the totals.
     * finalPack is the pack at Stop (the same cards as Save pack's CSV).
     */
    func finish(header: JSON, finalPack: [SessionItem], status: String) -> String {
        lock.lock(); defer { lock.unlock() }
        flushPending()
        // wait for the writer, then read the lines back
        writer.sync {}
        let events: [JSON] = ((try? String(contentsOf: jsonl, encoding: .utf8)) ?? "").split(separator: "\n").compactMap {
            (try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? JSON
        }
        var cards: [JSON] = []
        var eur = 0.0, usd = 0.0, hits = 0
        for item in finalPack {
            let e = entries[item.serial]
            let s = item.shown, p = s.selected
            if let v = p?.eur { eur += v } else if let v = p?.usd { usd += v }
            if hit(s) { hits += 1 }
            cards.append(["entry": RecJSON.i(e?.entry), "first_t_ms": e?.firstT.map { $0 as Any } ?? NSNull(),
                          "before_recording": e?.beforeRecording ?? false, "hit": hit(s),
                          "card": RecJSON.cardJson(s), "price": RecJSON.priceJson(s)])
        }
        var out: JSON = ["schema": Recording.schema, "recording_id": recordingId, "status": status]
        for (k, v) in header { out[k] = v }
        out["events"] = events
        out["cards"] = cards
        out["summary"] = ["cards": finalPack.count, "hits": hits, "pack_value_eur": RecJSON.round2(eur), "pack_value_usd": RecJSON.round2(usd)] as JSON
        return RecJSON.text(out)
    }
}
