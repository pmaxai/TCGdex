import AVFoundation
import ReplayKit
import UIKit

/// What a finished capture left: the folder, why it stopped, the video's length and size, the screen's size in points.
struct FinishedCapture {
    let dir: RecordingDir
    let reason: String
    let durationMs: Int64
    let width: Int
    let height: Int
    let screen: CGSize
    let startedAt: Date
    let hasAudio: Bool
    var error: String? = nil
    /// the video stopped working during the recording: the file holds what was written until then
    var failedMidway = false
    /// how much video went into the writer (for a file that failed: the most it can hold)
    var appendedMs: Int64 = 0
}

/**
 * The screen as a video (the Android app's RecordingService with MediaProjection): ReplayKit hands
 * the app's own screen, frame by frame, to an H.264 writer at the chosen size and bitrate, with the
 * microphone if asked for. Movie fragments every 10 seconds keep the file playable up to the last
 * fragment if the app is stopped. Stops by itself when storage runs low or the app leaves the screen.
 */
@MainActor
final class ScreenRecorder {
    enum Phase: Equatable { case idle, starting, recording(since: Date), stopping }

    /// below this much free storage a recording stops (as on Android)
    nonisolated static let lowStorage: Int64 = 300 * 1024 * 1024

    private let writer = ScreenWriter()
    private let store: RecordingStore
    private var dir: RecordingDir?
    private var watch: Timer?
    private var background: NSObjectProtocol?
    private var stopRequested = false
    private var startedAt = Date()
    /// why the video stopped working during the recording (with the system's error codes)
    private var failure: String?

    /// the first frame is in the video: its host time in nanoseconds (the event log's zero)
    var onFirstFrame: ((Int64) -> Void)?
    var onPhase: ((Phase) -> Void)?
    /// the capture ended (or never started: durationMs 0)
    var onFinished: ((FinishedCapture) -> Void)?

    init(store: RecordingStore) { self.store = store }

    /// The video's size: the screen's shape, its long side as asked, even numbers.
    nonisolated static func outputSize(screen: CGSize, longSide: Int) -> (Int, Int) {
        let long = max(screen.width, screen.height), short = min(screen.width, screen.height)
        guard long > 0 else { return (longSide * 9 / 16, longSide) }
        let s = Int((Double(longSide) * Double(short / long) / 2).rounded()) * 2
        return screen.width < screen.height ? (s, longSide) : (longSide, s)
    }

    func start(dir: RecordingDir, longSide: Int, bitrate: Int, mic: Bool) {
        guard self.dir == nil else { return }
        self.dir = dir
        stopRequested = false
        failure = nil
        let screen = UIScreen.main.bounds.size, scale = UIScreen.main.scale
        let size = Self.outputSize(screen: CGSize(width: screen.width * scale, height: screen.height * scale), longSide: longSide)
        if let e = writer.open(dir.video, size: size, bitrate: bitrate, mic: mic) { finish(reason: "error", error: e); return }
        onPhase?(.starting)
        let w = writer
        w.onFirstFrame = { [weak self] nanos in
            Task { @MainActor in
                self?.onFirstFrame?(nanos)
                self?.onPhase?(.recording(since: Date()))
            }
        }
        // the video stopped working: the recording stops right away (not at the next Stop)
        w.onFailure = { [weak self] text in Task { @MainActor in self?.failed(text) } }
        let rec = RPScreenRecorder.shared()
        rec.isMicrophoneEnabled = mic
        rec.startCapture(handler: { sample, type, error in
            if let error { w.captureFailed(error); return }
            w.enqueue(sample, type)
        }, completionHandler: { [weak self] error in
            let why = error.map(Self.why)
            Task { @MainActor in
                guard let self else { return }
                if let why { self.finish(reason: "error", error: why) } else { self.startedAt = Date(); self.watchStorage() }
            }
        })
        background = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            // home, lock, a call: the recording ends, so nothing else ends up in the video
            MainActor.assumeIsolated { self?.stop(reason: "background") }
        }
    }

    nonisolated private static func why(_ e: Error) -> String {
        let ns = e as NSError
        if ns.domain == RPRecordingErrorDomain && ns.code == RPRecordingErrorCode.userDeclined.rawValue { return "screen recording wasn't allowed" }
        return ScreenWriter.describe(e)
    }

    /// The video stopped working during the recording.
    private func failed(_ text: String) {
        guard dir != nil else { return }
        if failure == nil { failure = text }
        stop(reason: "error")
    }

    private func watchStorage() {
        watch?.invalidate()
        let store = self.store
        watch = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            if store.freeBytes() < Self.lowStorage { MainActor.assumeIsolated { self?.stop(reason: "low_storage") } }
        }
    }

    /// Stop: the last frames go in, the file is closed, onFinished follows.
    func stop(reason: String) {
        guard dir != nil, !stopRequested else { return }
        stopRequested = true
        onPhase?(.stopping)
        RPScreenRecorder.shared().stopCapture { [weak self] _ in
            Task { @MainActor in self?.finish(reason: reason, error: nil) }
        }
    }

    private func finish(reason: String, error: String?) {
        watch?.invalidate(); watch = nil
        if let b = background { NotificationCenter.default.removeObserver(b); background = nil }
        guard let d = dir else { return }
        dir = nil
        let screen = UIScreen.main.bounds.size
        let started = startedAt
        let failedBefore = failure
        failure = nil
        Task { @MainActor in
            let r = await writer.close()
            let e = error ?? failedBefore ?? r.error
            let f = FinishedCapture(dir: d, reason: e != nil && (reason == "user" || r.failedMidway) ? "error" : reason, durationMs: r.durationMs,
                                    width: r.size.0, height: r.size.1, screen: screen, startedAt: started, hasAudio: r.audio, error: e,
                                    failedMidway: r.failedMidway, appendedMs: r.appendedMs)
            onPhase?(.idle)
            onFinished?(f)
        }
    }
}

/// The screen video's writer, on its own queue (ReplayKit's frames arrive on another thread).
final class ScreenWriter: @unchecked Sendable {
    /**
     * How often the file is made playable up to here (what a crash or a failure can lose at most).
     * Not shorter: with 2 seconds the writer failed during busy pack openings (-11800 / -16341), a
     * failure others saw go away with longer fragments.
     */
    static let fragmentSeconds = 10.0

    private let queue = DispatchQueue(label: "recording.screen", qos: .userInitiated)
    private var writer: AVAssetWriter?
    private var videoIn: AVAssetWriterInput?
    private var audioIn: AVAssetWriterInput?
    private var firstPTS: CMTime?
    private var lastPTS = CMTime.invalid
    private var size = (0, 0)
    private var closed = false
    private var failure: String?
    /// set before the capture starts; called once, on the writer's queue
    var onFirstFrame: ((Int64) -> Void)?
    /// the video stopped working (the error with its codes); called once, on the writer's queue
    var onFailure: ((String) -> Void)?

    /**
     * An error as the person can pass it on: its text, the reason if the system gives one, and every
     * code down the chain ("The operation could not be completed – … (AVFoundationErrorDomain -11800 ›
     * NSOSStatusErrorDomain -16364)").
     */
    static func describe(_ e: Error?) -> String {
        guard let e else { return "the video stopped working" }
        let top = e as NSError
        var codes: [String] = []
        var cur: NSError? = top
        while let n = cur, codes.count < 6 {
            codes.append("\(n.domain) \(n.code)")
            cur = n.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        var text = top.localizedDescription
        if let r = top.localizedFailureReason, !r.isEmpty, r != text { text += " – " + r }
        return text + " (" + codes.joined(separator: " › ") + ")"
    }

    /// once: the first reason counts
    private func fail(_ text: String) {
        guard failure == nil else { return }
        failure = text
        onFailure?(text)
    }

    /// ReplayKit reports that the capture failed (any thread).
    func captureFailed(_ e: Error) {
        let text = "screen capture: " + Self.describe(e)
        queue.async { self.fail(text) }
    }

    private struct Failure: Error, LocalizedError { let text: String; var errorDescription: String? { text } }

    /// A new file; the error's text if it can't be written.
    func open(_ url: URL, size: (Int, Int), bitrate: Int, mic: Bool) -> String? {
        queue.sync {
            firstPTS = nil; lastPTS = .invalid; closed = false; failure = nil
            self.size = size
            do {
                try? FileManager.default.removeItem(at: url)
                let w = try AVAssetWriter(outputURL: url, fileType: .mp4)
                w.movieFragmentInterval = CMTime(seconds: Self.fragmentSeconds, preferredTimescale: 600)
                w.shouldOptimizeForNetworkUse = false
                let v = AVAssetWriterInput(mediaType: .video, outputSettings: [
                    AVVideoCodecKey: AVVideoCodecType.h264,
                    AVVideoWidthKey: size.0, AVVideoHeightKey: size.1,
                    AVVideoScalingModeKey: AVVideoScalingModeResizeAspect,
                    AVVideoCompressionPropertiesKey: [
                        AVVideoAverageBitRateKey: bitrate,
                        AVVideoExpectedSourceFrameRateKey: 30,
                        AVVideoMaxKeyFrameIntervalKey: 60,
                        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                        // frames in the order they come (no B-frames): fragments and reordered frames trouble the writer
                        AVVideoAllowFrameReorderingKey: false,
                    ] as [String: Any],
                ])
                v.expectsMediaDataInRealTime = true
                guard w.canAdd(v) else { throw Failure(text: "the video can't be written") }
                w.add(v)
                audioIn = nil
                if mic {
                    let a = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                        AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 96_000,
                    ])
                    a.expectsMediaDataInRealTime = true
                    if w.canAdd(a) { w.add(a); audioIn = a }
                }
                guard w.startWriting() else { throw Failure(text: w.error?.localizedDescription ?? "the video can't be written") }
                writer = w
                videoIn = v
                return nil
            } catch {
                writer = nil; videoIn = nil; audioIn = nil
                return error.localizedDescription
            }
        }
    }

    /// a frame or a piece of sound from ReplayKit (any thread)
    func enqueue(_ sample: CMSampleBuffer, _ type: RPSampleBufferType) {
        guard CMSampleBufferDataIsReady(sample) else { return }
        queue.async { self.append(sample, type) }
    }

    private func append(_ sample: CMSampleBuffer, _ type: RPSampleBufferType) {
        guard let w = writer, !closed else { return }
        // the writer can fail between two frames, too
        if w.status == .failed { fail(Self.describe(w.error)); return }
        guard w.status == .writing else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        switch type {
        case .video:
            guard let v = videoIn, v.isReadyForMoreMediaData else { return }
            if firstPTS == nil {
                w.startSession(atSourceTime: pts)
                firstPTS = pts
                // the frame's host time; a buffer on another clock counts from now
                let host = CACurrentMediaTime()
                let t = abs(CMTimeGetSeconds(pts) - host) < 5 ? CMTimeGetSeconds(pts) : host
                onFirstFrame?(Int64(t * 1_000_000_000))
            } else if lastPTS.isValid, CMTimeGetSeconds(CMTimeSubtract(pts, lastPTS)) < 1.0 / 30 - 0.004 {
                return       // 30 frames a second are enough
            }
            if v.append(sample) { lastPTS = pts } else { fail(Self.describe(w.error)) }
        case .audioMic:
            guard let a = audioIn, firstPTS != nil, a.isReadyForMoreMediaData else { return }
            if !a.append(sample) { fail("sound: " + Self.describe(w.error)) }
        default:
            break
        }
    }

    struct Closed {
        let durationMs: Int64
        let size: (Int, Int)
        let audio: Bool
        let error: String?
        /// it failed during the recording: the file stays as it is, playable up to its last whole fragment
        var failedMidway = false
        /// the video that went in, first to last frame
        var appendedMs: Int64 = 0
    }

    /// The file is finished; thrown away when no frame came; left as it is when it failed during the recording.
    func close() async -> Closed {
        let (w, first, last, size, audio, failure): (AVAssetWriter?, CMTime?, CMTime, (Int, Int), Bool, String?) = queue.sync {
            closed = true
            defer { writer = nil; videoIn = nil; audioIn = nil; onFirstFrame = nil; onFailure = nil }
            return (writer, firstPTS, lastPTS, self.size, audioIn != nil, self.failure)
        }
        guard let w else { return Closed(durationMs: 0, size: size, audio: audio, error: failure) }
        guard let first else {
            if w.status == .writing { w.cancelWriting() }
            return Closed(durationMs: 0, size: size, audio: audio, error: failure ?? w.error.map { Self.describe($0) })
        }
        let end = last.isValid ? last : first
        let ms = max(0, Int64(CMTimeGetSeconds(CMTimeSubtract(end, first)) * 1000))
        guard w.status == .writing else {
            // a failed writer can't be finished, and cancelling would delete the file: it stays as it is
            return Closed(durationMs: 0, size: size, audio: audio, error: failure ?? Self.describe(w.error), failedMidway: true, appendedMs: ms)
        }
        for i in w.inputs { i.markAsFinished() }
        w.endSession(atSourceTime: end)
        await w.finishWriting()
        return Closed(durationMs: ms, size: size, audio: audio,
                      error: w.status == .completed ? failure : (w.error.map { Self.describe($0) } ?? "the video couldn't be finished"),
                      appendedMs: ms)
    }
}

/**
 * The camera's own clean video (camera.mp4) while recording, without anything drawn over it – the
 * Android app's CameraRecording. The camera's frames as the scanner gets them (upright), H.264.
 */
final class CameraFileWriter: @unchecked Sendable {
    private let lock = NSLock()
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var url: URL?
    private var bitrate = 8_000_000
    private var started = false
    private var lastPTS = CMTime.invalid
    private(set) var firstFrameNanos: Int64?
    private(set) var error: String?
    private(set) var size: (Int, Int)?

    func prepare(_ url: URL, bitrate: Int) {
        lock.lock(); defer { lock.unlock() }
        self.url = url
        self.bitrate = bitrate
        writer = nil; input = nil; started = false; firstFrameNanos = nil; error = nil; size = nil; lastPTS = .invalid
        try? FileManager.default.removeItem(at: url)
    }

    var active: Bool { lock.lock(); defer { lock.unlock() }; return url != nil }

    /// a camera frame (camera queue): the first one sets the file's size
    func append(_ sample: CMSampleBuffer) {
        lock.lock(); defer { lock.unlock() }
        guard let url, error == nil else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        if writer == nil {
            guard let pb = CMSampleBufferGetImageBuffer(sample) else { return }
            let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
            do {
                let aw = try AVAssetWriter(outputURL: url, fileType: .mp4)
                let i = AVAssetWriterInput(mediaType: .video, outputSettings: [
                    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: w, AVVideoHeightKey: h,
                    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: bitrate, AVVideoExpectedSourceFrameRateKey: 30,
                                                      AVVideoMaxKeyFrameIntervalKey: 60] as [String: Any],
                ])
                i.expectsMediaDataInRealTime = true
                guard aw.canAdd(i) else { error = "encoder"; return }
                aw.add(i)
                guard aw.startWriting() else { error = aw.error?.localizedDescription ?? "encoder"; return }
                aw.startSession(atSourceTime: pts)
                writer = aw; input = i; size = (w, h)
                let host = CACurrentMediaTime()
                let t = abs(CMTimeGetSeconds(pts) - host) < 5 ? CMTimeGetSeconds(pts) : host
                firstFrameNanos = Int64(t * 1_000_000_000)
            } catch { self.error = error.localizedDescription; return }
        }
        guard let input, input.isReadyForMoreMediaData, writer?.status == .writing else { return }
        if input.append(sample) { lastPTS = pts }
    }

    /// The file is closed (after Stop); nil error: it's complete.
    func stop() async {
        let (w, i, last): (AVAssetWriter?, AVAssetWriterInput?, CMTime) = lock.withLock {
            defer { writer = nil; input = nil; url = nil }
            return (writer, input, lastPTS)
        }
        guard let w else { return }
        i?.markAsFinished()
        if last.isValid { w.endSession(atSourceTime: last) }
        await w.finishWriting()
        if w.status != .completed { lock.withLock { error = w.error?.localizedDescription ?? "encoder" } }
    }

    /// The log's camera object; screenZeroNanos: the screen file's first frame.
    func headerJson(enabled: Bool, file: URL, screenZeroNanos: Int64?) -> JSON {
        lock.lock(); defer { lock.unlock() }
        let ok = enabled && error == nil && RecordingDir.size(file) > 0
        if !ok {
            if enabled && error != nil { try? FileManager.default.removeItem(at: file) }
            return ["file": NSNull(), "width": NSNull(), "height": NSNull(), "fps": NSNull(), "offset_ms": NSNull(),
                    "error": !enabled ? "off" : (error ?? "encoder")]
        }
        let off: Any = firstFrameNanos.flatMap { a in screenZeroNanos.map { (a - $0) / 1_000_000 as Any } } ?? NSNull()
        return ["file": "camera.mp4", "width": RecJSON.i(size?.0), "height": RecJSON.i(size?.1), "fps": 30, "offset_ms": off, "error": NSNull()]
    }
}
