import Foundation

/**
 * Keeps the shown card steady and decides when to "take the shot" (port of ScanTracker.kt).
 *
 * A card is taken once the guide frame has been green (a sure match) for the same card for
 * delayMs (0 = on the first green frame). One frame that isn't green is forgiven (a blurry frame
 * while holding still). Cards that never get green (only yellow) are still taken after several
 * agreeing frames. A card the user picked (locked) is only replaced by a clearly different, sure card.
 */
final class ScanTracker {
    private struct Vote { let group: String?; let sure: Bool; let t: Int64 }

    private let sureMargin: Float
    private let likelyMargin: Float
    /** how long the frame must stay green before the card is taken */
    var delayMs: Int64 = 300

    private var window: [Vote] = []
    private(set) var shown: String?
    private(set) var locked = false

    // the current green streak
    private var streakGroup: String?
    private var streakStart: Int64 = 0
    private var streakMiss = 0

    init(sureMargin: Float, likelyMargin: Float) {
        self.sureMargin = sureMargin
        self.likelyMargin = likelyMargin
    }

    static func nowMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    func reset() {
        window.removeAll()
        shown = nil
        locked = false
        streakGroup = nil
    }

    /** The user chose this card: show it and keep it. */
    func lock(_ group: String) {
        shown = group
        locked = true
        window.removeAll()
        streakGroup = nil
    }

    /** How long the current card has been green, for a progress hint (nil: no streak). */
    func streakMillis(now: Int64 = ScanTracker.nowMs()) -> Int64? {
        guard let g = streakGroup, g != shown else { return nil }
        return now - streakStart
    }

    /** Feed one frame; returns true if the shown card changed. */
    @discardableResult
    func update(_ group: String?, margin: Float, now: Int64 = ScanTracker.nowMs()) -> Bool {
        let sure = group != nil && margin >= sureMargin
        let vote = (group != nil && margin >= likelyMargin) ? Vote(group: group, sure: sure, t: now) : Vote(group: nil, sure: false, t: now)
        window.append(vote)
        while window.count > 6 { window.removeFirst() }

        // green streak of one card
        if sure && group == streakGroup {
            streakMiss = 0
        } else if sure {
            streakGroup = group; streakStart = now; streakMiss = 0
        } else {
            streakMiss += 1
            if streakMiss >= 2 { streakGroup = nil }
        }
        if let g = streakGroup, g != shown {
            let held = now - streakStart
            let ok = locked ? held >= max(delayMs, 700) && window.suffix(5).filter({ $0.group == g && $0.sure }).count >= 2
                            : held >= delayMs
            if ok { return take(g) }
        }

        // cards that only get yellow: several agreeing frames, and never faster than the delay
        if locked { return false }
        var counts: [String: Int] = [:]
        var order: [String] = []
        for v in window { if let g = v.group { if counts[g] == nil { order.append(g) }; counts[g, default: 0] += 1 } }
        // the most frequent, the first seen on a tie (as Kotlin's maxByOrNull over a LinkedHashMap)
        var best: String?
        for g in order where best == nil || counts[g]! > counts[best!]! { best = g }
        guard let cand = best, let n = counts[cand] else { return false }
        if cand == shown { return false }
        let lastN = window.suffix(4).filter { $0.group == cand }.count
        let since = window.first { $0.group == cand }!.t
        let ok = (shown == nil ? n >= 3 : lastN == 4) && now - since >= delayMs
        return ok ? take(cand) : false
    }

    private func take(_ group: String) -> Bool {
        shown = group
        locked = false
        streakGroup = nil
        return true
    }

    /**
     * The vote of a frame while a card taken by hand (or picked from the list) is shown. That card
     * stays as long as the frame still sees it: another card only counts when it beats the kept card
     * in the same frame by a sure margin, or the kept card isn't among the frame's candidates at all.
     */
    static func voteWhileKept(best: Match?, candidates: [Match], kept: String?, sureMargin: Float) -> Match? {
        guard let best, let kept, best.group != kept else { return best }
        guard let k = candidates.first(where: { $0.group == kept })?.score else { return best }
        return best.score - k >= sureMargin ? best : nil
    }
}
