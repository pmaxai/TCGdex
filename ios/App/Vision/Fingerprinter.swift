import Accelerate
import Foundation

/**
 * Runs the exported fingerprint model (model.onnx): input (N, size, size, 3) float RGB 0..255,
 * output (N, dim) unit vectors. Normalisation and PCA are inside the model. CardCore calls it
 * through the CCFingerprinting protocol; ONNX Runtime runs behind CCOnnxModel (Bridge/OnnxModel.mm).
 */
final class Fingerprinter: NSObject, CCFingerprinting {
    private let model: CCOnnxModel
    private let lock = NSLock()

    init(modelPath: String) throws {
        model = try CCOnnxModel(path: modelPath, threads: Int32(min(4, max(1, ProcessInfo.processInfo.activeProcessorCount))))
        super.init()
    }

    func embedImages(_ rgb: Data, count: Int, size: Int) -> Data? {
        let n = count * size * size * 3
        guard count > 0, rgb.count >= n, let floats = NSMutableData(length: n * MemoryLayout<Float>.size) else { return nil }
        rgb.withUnsafeBytes { src in
            vDSP_vfltu8(src.bindMemory(to: UInt8.self).baseAddress!, 1,
                        floats.mutableBytes.assumingMemoryBound(to: Float.self), 1, vDSP_Length(n))
        }
        lock.lock(); defer { lock.unlock() }
        do {
            return try model.run(floats: floats as Data, shape: [count, size, size, 3].map { NSNumber(value: $0) }, input: "image")
        } catch {
            NSLog("Fingerprinter: %@", error.localizedDescription)
            return nil
        }
    }
}
