import Foundation

/// A card of the recording's pack, for a `cards` input: the event log's entry number and what the list shows.
struct PackCard: Equatable, Identifiable {
    let entry: Int
    let name: String
    let set: String
    let number: String
    let value: Double?
    let currency: String?
    let hit: Bool
    var id: Int { entry }
}

/**
 * `submission.json` (R5): which templates to make from a recording and the inputs filled in.
 * Written into the recording's folder on Submit; uploaded before `_complete.json`. As on Android.
 */
enum Submission {
    static let schema = "tcgdex.submission.v1"
    static let file = "submission.json"

    /// templates: the ticked ids in the picker's order; inputs: only the filled-in ones (SubmissionForm.inputs).
    static func build(templates: [String], inputs: JSON, appVersion: String?, now: Date = Date()) -> JSON {
        precondition(!templates.isEmpty, "no template")
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        var o: JSON = ["schema": schema, "submitted_at": f.string(from: now), "templates": templates.map { ["id": $0] }]
        if let v = appVersion, !v.isEmpty { o["app_version"] = v }
        if !inputs.isEmpty { o["inputs"] = inputs }
        return o
    }

    static func write(_ dir: URL, _ json: JSON) throws {
        try RecJSON.data(json).write(to: dir.appendingPathComponent(file), options: .atomic)
    }

    /// The pack at Stop, from events.json's `cards`.
    static func packOf(_ events: URL) -> [PackCard] {
        guard let o = RecJSON.read(events) else { return [] }
        return ((o["cards"] as? [JSON]) ?? []).compactMap { c in
            guard let entry = c["entry"] as? Int else { return nil }
            let card = (c["card"] as? JSON) ?? [:], p = (c["price"] as? JSON) ?? [:]
            return PackCard(entry: entry, name: card["name"] as? String ?? "", set: card["set"] as? String ?? "",
                            number: card["number_text"] as? String ?? "", value: (p["value"] as? NSNumber)?.doubleValue,
                            currency: p["currency"] as? String, hit: (c["hit"] as? Bool) ?? false)
        }
    }

    /// Whether the recording has the microphone's sound (events.json, else header.json).
    static func hasMicSound(events: URL, header: URL) -> Bool {
        for u in [events, header] {
            if let v = RecJSON.read(u)?["video"] as? JSON, let a = v["audio"] as? String { return a == "mic" }
        }
        return false
    }
}

/// One input of the templates list: a field on the submit screen (R4).
struct TemplateInput: Equatable {
    let id: String
    /// text, number, toggle, choice or cards; any other type is not shown (newer templates may add types)
    let type: String
    let label: String
    var help: String? = nil
    var min: Double? = nil
    var max: Double? = nil
    /// Bool, Double or String, as in the file
    var defaultValue: FieldValue? = nil
    var options: [String] = []
    var known: Bool { TemplatesIndex.knownTypes.contains(type) }
}

/// What a field holds: text and number fields their text, a toggle a Bool, a choice its option, a cards field the chosen entries.
enum FieldValue: Equatable {
    case text(String), bool(Bool), number(Double), cards([Int])

    var string: String? {
        switch self {
        case .text(let t): return t
        case .number(let n): return n == n.rounded() && abs(n) < 1e15 ? String(Int64(n)) : String(n)
        case .bool(let b): return String(b)
        case .cards: return nil
        }
    }
}

/// One video template: what the pipeline makes from a recording. id is opaque.
struct VideoTemplate: Equatable, Identifiable {
    let id: String
    let name: String
    let description: String
    let isDefault: Bool
    let inputs: [String]
}

/// `templates/index.json`, written by the pipeline (schema pokepov.templates.v1); fields the app doesn't know are ignored.
struct TemplatesIndex: Equatable {
    let templates: [VideoTemplate]
    let inputs: [String: TemplateInput]

    static let schema = "pokepov.templates.v1"
    static let knownTypes: Set<String> = ["text", "number", "toggle", "choice", "cards"]

    /// Without any list (fresh install, offline): the one built-in template, no inputs (R2).
    static let fallback = TemplatesIndex(templates: [VideoTemplate(id: "longform-smooth", name: "Long Form · Smooth", description: "",
                                                                   isDefault: true, inputs: [])], inputs: [:])

    /// The fields for the ticked templates: each input once, in the templates' order and their own; unknown types left out.
    func fields(for ticked: Set<String>) -> [TemplateInput] {
        var seen = Set<String>()
        var out: [TemplateInput] = []
        for t in templates where ticked.contains(t.id) {
            for i in t.inputs where !seen.contains(i) {
                seen.insert(i)
                if let f = inputs[i], f.known { out.append(f) }
            }
        }
        return out
    }

    struct BadList: Error, LocalizedError { let why: String; var errorDescription: String? { why } }

    /// Reads the file; another schema or no template at all is an error.
    static func parse(_ data: Data) throws -> TemplatesIndex {
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? JSON else { throw BadList(why: "not a template list") }
        func str(_ o: JSON, _ k: String) -> String? { (o[k] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } }
        func num(_ o: JSON, _ k: String) -> Double? { (o[k] as? NSNumber).flatMap { CFGetTypeID($0) == CFBooleanGetTypeID() ? nil : $0.doubleValue } }
        func strings(_ a: Any?) -> [String] {
            ((a as? [Any]) ?? []).compactMap { v in
                if v is NSNull { return nil }
                if let s = v as? String { return s }
                if let n = v as? NSNumber { return n.stringValue }
                return nil
            }
        }
        func value(_ v: Any?) -> FieldValue? {
            switch v {
            case let n as NSNumber where CFGetTypeID(n) == CFBooleanGetTypeID(): return .bool(n.boolValue)
            case let n as NSNumber: return .number(n.doubleValue)
            case let s as String: return .text(s)
            default: return nil
            }
        }
        let schema = str(o, "schema")
        guard schema == Self.schema else { throw BadList(why: "unknown schema '\(schema ?? "")'") }
        var inputs: [String: TemplateInput] = [:]
        for (id, d) in (o["inputs"] as? JSON) ?? [:] {
            guard let d = d as? JSON else { continue }
            inputs[id] = TemplateInput(id: id, type: str(d, "type") ?? "", label: str(d, "label") ?? id, help: str(d, "help"),
                                       min: num(d, "min"), max: num(d, "max"), defaultValue: value(d["default"]), options: strings(d["options"]))
        }
        var seen = Set<String>()
        let templates: [VideoTemplate] = ((o["templates"] as? [Any]) ?? []).compactMap { any in
            guard let t = any as? JSON, let id = str(t, "id"), !seen.contains(id) else { return nil }
            seen.insert(id)
            return VideoTemplate(id: id, name: str(t, "name") ?? id, description: str(t, "description") ?? "",
                                 isDefault: (t["default"] as? Bool) ?? false, inputs: strings(t["inputs"]))
        }
        guard !templates.isEmpty else { throw BadList(why: "no templates in the list") }
        return TemplatesIndex(templates: templates, inputs: inputs)
    }
}

/**
 * The inputs form (R4) without its screen: what a field holds, whether it's acceptable, and the
 * `inputs` object for submission.json.
 */
enum SubmissionForm {
    /// what a field starts with
    static func initial(_ f: TemplateInput) -> FieldValue? {
        switch f.type {
        case "toggle": if case .bool(let b) = f.defaultValue { return .bool(b) }; return .bool(false)
        case "choice": return f.defaultValue?.string.flatMap { f.options.contains($0) ? .text($0) : nil }
        case "cards": return .cards([])
        default: return .text("")
        }
    }

    /// "Between 2 and 6", "Not a number" … for a field that can't be submitted like this; nil if fine.
    static func problem(_ f: TemplateInput, _ raw: FieldValue?) -> String? {
        guard f.type == "number" else { return nil }
        let t = (raw?.string ?? "").trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return nil }
        guard let v = number(t) else { return "Not a number" }
        if let lo = f.min, v < lo { return range(f) }
        if let hi = f.max, v > hi { return range(f) }
        return nil
    }

    /// "Between 2 and 6", "At least 0", "At most 90" (nil: no limits)
    static func range(_ f: TemplateInput) -> String? {
        switch (f.min, f.max) {
        case let (lo?, hi?): return "Between \(fmt(lo)) and \(fmt(hi))"
        case let (lo?, nil): return "At least \(fmt(lo))"
        case let (nil, hi?): return "At most \(fmt(hi))"
        default: return nil
        }
    }

    /**
     * The inputs filled in, typed for the pipeline: text trimmed (at most `max` characters), numbers
     * as JSON numbers, toggles and choices when changed from their default, cards as arrays of entry
     * numbers. Empty fields are left out.
     */
    static func inputs(_ fields: [TemplateInput], _ raw: [String: FieldValue]) -> JSON {
        var o: JSON = [:]
        for f in fields {
            let v = raw[f.id]
            switch f.type {
            case "text":
                if case .text(var t)? = v {
                    t = t.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let m = f.max { t = String(t.prefix(Int(m))).trimmingCharacters(in: .whitespacesAndNewlines) }
                    if !t.isEmpty { o[f.id] = t }
                }
            case "number":
                if case .text(let t)? = v, !t.trimmingCharacters(in: .whitespaces).isEmpty, problem(f, v) == nil, let n = number(t) {
                    o[f.id] = n == n.rounded() && abs(n) < 1e15 ? Int64(n) as Any : n as Any
                }
            case "toggle":
                let def: Bool = { if case .bool(let b) = f.defaultValue { return b }; return false }()
                if case .bool(let b)? = v, b != def { o[f.id] = b }
            case "choice":
                if case .text(let t)? = v, !t.isEmpty, t != f.defaultValue?.string { o[f.id] = t }
            case "cards":
                if case .cards(let list)? = v {
                    var seen = Set<Int>()
                    let unique = list.filter { seen.insert($0).inserted }
                    if !unique.isEmpty { o[f.id] = unique }
                }
            default: break
            }
        }
        return o
    }

    /// A card ticked in one cards field leaves the others (it can't be in both "Always" and "Never").
    static func tickCard(_ fields: [TemplateInput], _ raw: [String: FieldValue], fieldId: String, entry: Int, on: Bool) -> [String: FieldValue] {
        var out = raw
        for f in fields where f.type == "cards" {
            var now: [Int] = { if case .cards(let l)? = raw[f.id] { return l }; return [] }()
            if f.id == fieldId || on { now.removeAll { $0 == entry } }
            if f.id == fieldId && on { now.append(entry) }
            out[f.id] = .cards(now)
        }
        return out
    }

    /// "40", "12.5", "12,50" → a number; anything else nil
    static func number(_ t: String) -> Double? {
        guard let d = Double(t.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")), d.isFinite else { return nil }
        return d
    }

    private static func fmt(_ d: Double) -> String { d == d.rounded() ? String(Int64(d)) : String(d) }
}

/// Where the list on the screen came from.
enum TemplatesSource { case bucket, saved, builtIn }

struct LoadedTemplates: Equatable {
    let index: TemplatesIndex
    let source: TemplatesSource
    /// why the bucket's list couldn't be used (nil: it was)
    var error: String? = nil
    /// when the saved copy was saved
    var savedAt: Date? = nil
}

/**
 * The templates list (R2): `<prefix>templates/index.json` from the bucket, saved on the phone;
 * offline or on any error the last saved copy; without one the built-in default only.
 */
enum Templates {
    static func key(_ cfg: S3Config) -> String { cfg.prefix + "templates/index.json" }

    /// fetch: reads the bucket's file (nil: no bucket set up).
    static func load(fetch: (() async throws -> Data)?, cache: URL) async -> LoadedTemplates {
        var why = "no bucket set up"
        if let fetch {
            do {
                let data = try await fetch()
                let index = try TemplatesIndex.parse(data)
                try? FileManager.default.createDirectory(at: cache.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? data.write(to: cache, options: .atomic)
                return LoadedTemplates(index: index, source: .bucket)
            } catch {
                why = reason(error)
            }
        }
        if let d = try? Data(contentsOf: cache), let saved = try? TemplatesIndex.parse(d) {
            let at = (try? FileManager.default.attributesOfItem(atPath: cache.path))?[.modificationDate] as? Date
            return LoadedTemplates(index: saved, source: .saved, error: why, savedAt: at)
        }
        return LoadedTemplates(index: .fallback, source: .builtIn, error: why)
    }

    private static func reason(_ e: Error) -> String {
        if let s = e as? S3Error {
            switch s.code {
            case "NoSuchKey": return "no template list in the bucket"
            case "AccessDenied": return "the key may not read templates/index.json"
            default: return s.friendly
            }
        }
        if e is URLError { return "offline" }
        if let b = e as? TemplatesIndex.BadList { return b.why }
        return e.localizedDescription
    }
}
