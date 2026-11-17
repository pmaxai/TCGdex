import Compression
import Foundation

enum Inflate {
    /// A zlib stream (2-byte header, deflate, Adler-32) unpacked; capacity: the expected size (it grows if needed).
    static func zlib(_ data: Data, capacity: Int) -> Data? {
        guard data.count > 2 else { return nil }
        // Apple's ZLIB decoder reads the raw deflate stream: skip the 2-byte header
        let body = Data(data.dropFirst(2))
        var cap = max(64, capacity)
        while cap <= 256 << 20 {
            var out = Data(count: cap)
            let n = out.withUnsafeMutableBytes { dst in
                body.withUnsafeBytes { src in
                    compression_decode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, cap,
                                              src.bindMemory(to: UInt8.self).baseAddress!, body.count, nil, COMPRESSION_ZLIB)
                }
            }
            if n == 0 { return nil }
            if n < cap { out.count = n; return out }
            cap *= 2
        }
        return nil
    }
}
