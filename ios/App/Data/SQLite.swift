import Foundation
import SQLite3

/// A read-only SQLite database (the data pack's catalog) with just what the app needs.
final class SQLiteDB {
    private var db: OpaquePointer?
    private let lock = NSLock()

    /// immutable: a file nothing writes (the app bundle is read-only, so no journal is looked for)
    init(path: String, immutable: Bool = true) throws {
        let name = immutable ? "file:" + (path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? path) + "?immutable=1" : path
        let flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX | (immutable ? SQLITE_OPEN_URI : 0)
        if sqlite3_open_v2(name, &db, flags, nil) != SQLITE_OK {
            throw NSError(domain: "SQLite", code: 1, userInfo: [NSLocalizedDescriptionKey: "can't open \(path)"])
        }
    }

    deinit { sqlite3_close(db) }

    /// One value of a row: text, number or blob (nil = NULL).
    enum Cell {
        case null, integer(Int64), real(Double), text(String), blob(Data)
        var string: String? {
            switch self {
            case .text(let s): return s
            case .integer(let i): return String(i)
            case .real(let d): return String(d)
            default: return nil
            }
        }
        var double: Double? {
            switch self {
            case .real(let d): return d
            case .integer(let i): return Double(i)
            case .text(let s): return Double(s)
            default: return nil
            }
        }
        var int: Int? {
            switch self {
            case .integer(let i): return Int(i)
            case .real(let d): return Int(d)
            case .text(let s): return Int(s)
            default: return nil
            }
        }
        var data: Data? { if case .blob(let d) = self { return d }; return nil }
        var isNull: Bool { if case .null = self { return true }; return false }
    }

    /// The rows of a query; arguments are bound as text.
    func query(_ sql: String, _ args: [String] = []) -> [[Cell]] {
        lock.lock(); defer { lock.unlock() }
        var st: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(st) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (i, a) in args.enumerated() { sqlite3_bind_text(st, Int32(i + 1), a, -1, transient) }
        var rows: [[Cell]] = []
        while sqlite3_step(st) == SQLITE_ROW {
            let n = sqlite3_column_count(st)
            var row: [Cell] = []
            row.reserveCapacity(Int(n))
            for c in 0..<n {
                switch sqlite3_column_type(st, c) {
                case SQLITE_INTEGER: row.append(.integer(sqlite3_column_int64(st, c)))
                case SQLITE_FLOAT: row.append(.real(sqlite3_column_double(st, c)))
                case SQLITE_TEXT: row.append(.text(String(cString: sqlite3_column_text(st, c))))
                case SQLITE_BLOB:
                    let len = Int(sqlite3_column_bytes(st, c))
                    if let p = sqlite3_column_blob(st, c), len > 0 { row.append(.blob(Data(bytes: p, count: len))) } else { row.append(.blob(Data())) }
                default: row.append(.null)
                }
            }
            rows.append(row)
        }
        return rows
    }

    func columns(_ table: String) -> Set<String> {
        Set(query("PRAGMA table_info(\(table))").compactMap { $0.count > 1 ? $0[1].string : nil })
    }

    func tableExists(_ name: String) -> Bool {
        !query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", [name]).isEmpty
    }
}
