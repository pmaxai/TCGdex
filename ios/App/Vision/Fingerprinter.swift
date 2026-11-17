import Accelerate
import Foundation
import OnnxRuntimeBindings

/**
 * Runs the exported fingerprint model (model.onnx): input (N, size, size, 3) float RGB 0..255,
 * output (N, dim) unit vectors. Normalisation and PCA are inside the model. CardCore calls it
 * through the CCFingerprinting protocol.
 */
final class Fingerprinter: NSObject, CCFingerprinting {
    private let env: ORTEnv
    private let session: ORTSession
    private let output: String
    private let lock = NSLock()

    init(modelPath: String) throws {
        let e = try ORTEnv(loggingLevel: .warning)
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(Int32(min(4, max(1, ProcessInfo.processInfo.activeProcessorCount))))
        try options.setGraphOptimizationLevel(.all)
        let s = try ORTSession(env: e, modelPath: modelPath, sessionOptions: options)
        env = e
        session = s
        output = try s.outputNames().first ?? "embedding"
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
            let input = try ORTValue(tensorData: floats, elementType: .float,
                                     shape: [count, size, size, 3].map { NSNumber(value: $0) })
            let out = try session.run(withInputs: ["image": input], outputNames: [output], runOptions: nil)
            guard let v = out[output] else { return nil }
            return try v.tensorData() as Data
        } catch {
            NSLog("Fingerprinter: %@", error.localizedDescription)
            return nil
        }
    }
}
