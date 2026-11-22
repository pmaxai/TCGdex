import AVFoundation
import ReplayKit
import SwiftUI
import UIKit
import XCTest
@testable import OverpricedCardboard

/// Diagnostics only (branch diag/ios-recording): where the screen recording fails. Prints DIAG lines into test.log.
@MainActor
final class RecordingDiagnostics: XCTestCase {
    private func say(_ s: String) { print("StoreTest: DIAG: " + s) }

    private func describe(_ e: Error?) -> String {
        guard let e else { return "nil" }
        var out = ""
        var cur: NSError? = e as NSError
        while let n = cur {
            out += "[\(n.domain) \(n.code) \(n.localizedDescription) | \(n.localizedFailureReason ?? "-")] "
            cur = n.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return out
    }

    private func frame(_ w: Int, _ h: Int, _ fmt: OSType, _ pts: CMTime) -> CMSampleBuffer? {
        var pb: CVPixelBuffer?
        let attrs: [String: Any] = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]]
        guard CVPixelBufferCreate(nil, w, h, fmt, attrs as CFDictionary, &pb) == kCVReturnSuccess, let pb else { return nil }
        CVPixelBufferLockBaseAddress(pb, [])
        if CVPixelBufferIsPlanar(pb) {
            for p in 0..<CVPixelBufferGetPlaneCount(pb) {
                memset(CVPixelBufferGetBaseAddressOfPlane(pb, p), p == 0 ? 60 : 128, CVPixelBufferGetBytesPerRowOfPlane(pb, p) * CVPixelBufferGetHeightOfPlane(pb, p))
            }
        } else {
            memset(CVPixelBufferGetBaseAddress(pb), 90, CVPixelBufferGetBytesPerRow(pb) * h)
        }
        CVPixelBufferUnlockBaseAddress(pb, [])
        var desc: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pb, formatDescriptionOut: &desc)
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 30), presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sb: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: pb, formatDescription: desc!, sampleTiming: &timing, sampleBufferOut: &sb)
        return sb
    }

    private func url(_ name: String) -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("diag-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent(name)
    }

    /// The app's own writer with frames shaped like ReplayKit's (420f, the screen's pixels, host-time stamps).
    func testAppWriterWithReplayKitLikeFrames() async throws {
        for (label, fmt) in [("420f", kCVPixelFormatType_420YpCbCr8BiPlanarFullRange), ("BGRA", kCVPixelFormatType_32BGRA)] {
            let w = ScreenWriter()
            let u = url("video.mp4")
            let size = ScreenRecorder.outputSize(screen: CGSize(width: 1179, height: 2556), longSide: 1920)
            say("app writer \(label): open -> \(w.open(u, size: size, bitrate: 8_000_000, mic: false) ?? "ok") size \(size)")
            let start = CACurrentMediaTime()
            for i in 0..<60 {
                let pts = CMTime(seconds: start + Double(i) / 30, preferredTimescale: 1_000_000_000)
                if let sb = frame(1179, 2556, fmt, pts) { w.enqueue(sb, .video) }
                try await Task.sleep(nanoseconds: 33_000_000)
            }
            let r = await w.close()
            say("app writer \(label): duration \(r.durationMs) ms, error \(r.error ?? "nil"), bytes \(RecordingDir.size(u))")
        }
    }

    /// The writer's settings one by one: which one makes it fail.
    func testWriterVariants() async throws {
        struct V { let name: String; let fragments: Bool; let scale: Bool; let type: AVFileType; let profile: Bool }
        let variants = [
            V(name: "as in the app", fragments: true, scale: true, type: .mp4, profile: true),
            V(name: "no fragments", fragments: false, scale: true, type: .mp4, profile: true),
            V(name: "no scaling key", fragments: true, scale: false, type: .mp4, profile: true),
            V(name: "no profile", fragments: true, scale: true, type: .mp4, profile: false),
            V(name: "mov", fragments: true, scale: true, type: .mov, profile: true),
            V(name: "plain", fragments: false, scale: false, type: .mp4, profile: false),
        ]
        for v in variants {
            let u = url(v.type == .mov ? "v.mov" : "v.mp4")
            do {
                let w = try AVAssetWriter(outputURL: u, fileType: v.type)
                if v.fragments { w.movieFragmentInterval = CMTime(seconds: 2, preferredTimescale: 600) }
                var comp: [String: Any] = [AVVideoAverageBitRateKey: 8_000_000, AVVideoExpectedSourceFrameRateKey: 30, AVVideoMaxKeyFrameIntervalKey: 60]
                if v.profile { comp[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel }
                var settings: [String: Any] = [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 886, AVVideoHeightKey: 1920,
                                               AVVideoCompressionPropertiesKey: comp]
                if v.scale { settings[AVVideoScalingModeKey] = AVVideoScalingModeResizeAspect }
                let i = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
                i.expectsMediaDataInRealTime = true
                w.add(i)
                let ok = w.startWriting()
                let start = CACurrentMediaTime()
                var appended = 0, failedAt = -1
                for n in 0..<45 {
                    let pts = CMTime(seconds: start + Double(n) / 30, preferredTimescale: 1_000_000_000)
                    if n == 0 { w.startSession(atSourceTime: pts) }
                    guard let sb = frame(1179, 2556, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, pts) else { continue }
                    while !i.isReadyForMoreMediaData && w.status == .writing { try await Task.sleep(nanoseconds: 5_000_000) }
                    if i.append(sb) { appended += 1 } else if failedAt < 0 { failedAt = n }
                }
                i.markAsFinished()
                await w.finishWriting()
                say("variant \(v.name): startWriting \(ok), appended \(appended), first failure at \(failedAt), status \(w.status.rawValue), error \(describe(w.error)), bytes \(RecordingDir.size(u))")
            } catch {
                say("variant \(v.name): writer not created \(describe(error))")
            }
        }
    }

    /// ReplayKit on this machine, and the app's ScreenRecorder for 4 s.
    func testReplayKitAndTheAppRecorder() async throws {
        let r = RPScreenRecorder.shared()
        say("replaykit available \(r.isAvailable), recording \(r.isRecording)")
        let store = RecordingStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("diag-rec-\(UUID().uuidString)"))
        let rec = ScreenRecorder(store: store)
        var finished: FinishedCapture?
        var phases: [String] = []
        rec.onPhase = { phases.append("\($0)") }
        rec.onFirstFrame = { n in self.say("first frame at host \(n), now \(EventLog.hostNanos())") }
        rec.onFinished = { finished = $0 }
        let d = store.create()
        rec.start(dir: d, longSide: 1920, bitrate: 8_000_000, mic: false)
        try await Task.sleep(nanoseconds: 4_000_000_000)
        rec.stop(reason: "user")
        for _ in 0..<100 where finished == nil { try await Task.sleep(nanoseconds: 100_000_000) }
        say("app recorder phases \(phases)")
        if let f = finished {
            say("app recorder finished: reason \(f.reason), duration \(f.durationMs), size \(f.width)x\(f.height), error \(f.error ?? "nil"), bytes \(RecordingDir.size(d.video))")
        } else {
            say("app recorder: not finished after stop")
        }
    }

    /// The layer screen captures leave out: is it there when the timer's view is made, and does its content get a size?
    func testSecureCanvas() async throws {
        let field = UITextField()
        field.isSecureTextEntry = true
        say("secure field at init: subviews \(field.subviews.map { String(describing: type(of: $0)) }), sublayer delegate \(field.layer.sublayers?.first?.delegate.map { String(describing: type(of: $0)) } ?? "nil")")
        let win = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        win.addSubview(field)
        field.frame = CGRect(x: 0, y: 0, width: 200, height: 40)
        field.layoutIfNeeded()
        say("secure field laid out: subviews \(field.subviews.map { String(describing: type(of: $0)) }), sublayer delegate \(field.layer.sublayers?.first?.delegate.map { String(describing: type(of: $0)) } ?? "nil")")
        let host = UIHostingController(rootView: RecIndicator(rec: RecordingModel(store: RecordingStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("diag-\(UUID().uuidString)")))))
        say("indicator alone fits \(host.sizeThatFits(in: CGSize(width: 393, height: 77)))")
        let page = UIHostingController(rootView: Text("x").hiddenFromScreenCapture().frame(width: 393, height: 77))
        win.rootViewController = page
        win.isHidden = false
        page.view.layoutIfNeeded()
        try await Task.sleep(nanoseconds: 500_000_000)
        func dump(_ v: UIView, _ depth: Int) {
            say(String(repeating: "  ", count: depth) + "\(type(of: v)) \(v.frame) hidden \(v.isHidden) alpha \(v.alpha)")
            if depth < 8 { v.subviews.forEach { dump($0, depth + 1) } }
        }
        dump(page.view, 0)
        win.isHidden = true
    }
}
