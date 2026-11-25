import AVFoundation
import Accelerate
import SwiftUI
import UIKit

/**
 * The back camera: a preview layer and upright RGB frames for the scanner (4:3, about 1280 x 960,
 * like the Android app). Frames arrive on one background queue; late ones are dropped.
 */
final class Camera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "camera.frames", qos: .userInitiated)
    private let output = AVCaptureVideoDataOutput()
    private var device: AVCaptureDevice?
    private var configured = false
    /// called on the camera queue with each frame, upright (portrait)
    var onFrame: (@Sendable (RGBImage) -> Void)?
    /// false: the scanner is busy, the frame isn't converted for it (camera queue)
    var wantsFrame: (@Sendable () -> Bool)?
    private let sinkLock = NSLock()
    private var sink: (@Sendable (CMSampleBuffer) -> Void)?
    /// a recording's camera file takes every frame as it comes (camera queue), before the scanner
    var recordSink: (@Sendable (CMSampleBuffer) -> Void)? {
        get { sinkLock.lock(); defer { sinkLock.unlock() }; return sink }
        set { sinkLock.lock(); sink = newValue; sinkLock.unlock() }
    }

    var maxZoom: CGFloat { min(device?.maxAvailableVideoZoomFactor ?? 1, 10) }
    /// the zoom factor a person sees as 1× (virtual cameras start on the ultra wide)
    var zoomBase: CGFloat {
        guard let d = device, let first = d.virtualDeviceSwitchOverVideoZoomFactors.first else { return 1 }
        return CGFloat(truncating: first)
    }

    static func authorized() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }

    private var users = 0
    /// counts acquires and releases, so a delayed stop sees whether a screen came back meanwhile
    private var generation = 0

    /// A screen with the camera appears (the scanner, Trading): the camera runs while at least one is shown.
    func acquire() {
        queue.async { [self] in
            users += 1
            generation += 1
            if !configured { configure() }
            if !session.isRunning { session.startRunning() }
        }
    }

    /// That screen went away. The camera stops only when no camera screen is back within a second:
    /// switching from the scanner to Trading releases one and acquires the other, and stopping and
    /// starting the camera in between takes a second or two on a phone.
    func release() {
        queue.async { [self] in
            users = max(0, users - 1)
            guard users == 0 else { return }
            generation += 1
            let g = generation
            queue.asyncAfter(deadline: .now() + 1) { [self] in
                if users == 0 && generation == g && session.isRunning { session.stopRunning() }
            }
        }
    }

    /// The one camera picture of the app. A session shows its live image in one preview layer only
    /// (several need a multi-camera session), so this view moves into whichever camera screen is shown.
    @MainActor private var sharedPreview: CameraPreviewView?

    @MainActor func previewView() -> CameraPreviewView {
        if let v = sharedPreview { return v }
        let v = CameraPreviewView()
        v.backgroundColor = .black
        v.preview.videoGravity = .resizeAspectFill
        v.preview.session = session
        if let c = v.preview.connection, c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
        sharedPreview = v
        return v
    }

    /// the app went to the background / came back (the screens stay)
    func pause() { queue.async { [self] in if session.isRunning { session.stopRunning() } } }
    func resume() { queue.async { [self] in if users > 0 && configured && !session.isRunning { session.startRunning() } } }

    private func configure() {
        configured = true
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        // a virtual camera switches to the ultra wide for close-ups (macro) by itself
        let types: [AVCaptureDevice.DeviceType] = [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
        let found = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .back).devices
        guard let d = types.lazy.compactMap({ t in found.first { $0.deviceType == t } }).first,
              let input = try? AVCaptureDeviceInput(device: d), session.canAddInput(input) else { return }
        device = d
        session.sessionPreset = .inputPriority
        session.addInput(input)
        try? d.lockForConfiguration()
        if let f = Self.format(of: d) { d.activeFormat = f }
        if d.isFocusModeSupported(.continuousAutoFocus) { d.focusMode = .continuousAutoFocus }
        if d.isExposureModeSupported(.continuousAutoExposure) { d.exposureMode = .continuousAutoExposure }
        if d.isAutoFocusRangeRestrictionSupported { d.autoFocusRangeRestriction = .near }
        d.videoZoomFactor = zoomBase
        d.unlockForConfiguration()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        if let c = output.connection(with: .video), c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
        if wantsStills { attachPhotoOutput() }
    }

    /// the smallest 4:3 video format of at least 1280 x 960 (else the device's current one)
    private static func format(of d: AVCaptureDevice) -> AVCaptureDevice.Format? {
        let fourThree = d.formats.filter { f in
            let s = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            return s.width * 3 == s.height * 4 && s.width >= 1280
                && CMFormatDescriptionGetMediaSubType(f.formatDescription) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                && f.videoSupportedFrameRateRanges.contains { $0.maxFrameRate >= 30 }
        }
        return fourThree.min { CMVideoFormatDescriptionGetDimensions($0.formatDescription).width < CMVideoFormatDescriptionGetDimensions($1.formatDescription).width }
    }

    // ------------------------------------------------------------------ stills (Webshop's product pictures)

    private var photoOutput: AVCapturePhotoOutput?
    private var wantsStills = false
    private let stillLock = NSLock()
    private var stillDelegates: [StillDelegate] = []

    /// A full-resolution still beside the frames, for product pictures: added once, when Webshop Mode is first used.
    func enableStills() {
        queue.async { [self] in
            wantsStills = true
            guard configured, photoOutput == nil else { return }
            session.beginConfiguration()
            attachPhotoOutput()
            session.commitConfiguration()
        }
    }

    /// (camera queue, inside a configuration)
    private func attachPhotoOutput() {
        guard photoOutput == nil, let d = device else { return }
        let out = AVCapturePhotoOutput()
        guard session.canAddOutput(out) else { return }
        session.addOutput(out)
        // the largest the format offers, up to about 12 MP (a product picture needs no more)
        let dims = d.activeFormat.supportedMaxPhotoDimensions.filter { Int($0.width) * Int($0.height) <= 12_600_000 }
        if let best = dims.max(by: { Int($0.width) * Int($0.height) < Int($1.width) * Int($1.height) }) { out.maxPhotoDimensions = best }
        if let c = out.connection(with: .video), c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
        photoOutput = out
    }

    /// A still as the photo file's bytes (JPEG or HEIC, with its orientation); nil without a still camera (or on an error).
    func takeStill() async -> Data? {
        await withCheckedContinuation { (cont: CheckedContinuation<Data?, Never>) in
            queue.async { [self] in
                guard let out = photoOutput, session.isRunning else { cont.resume(returning: nil); return }
                let settings = AVCapturePhotoSettings()
                settings.maxPhotoDimensions = out.maxPhotoDimensions
                var del: StillDelegate?
                del = StillDelegate { [weak self] data in
                    cont.resume(returning: data)
                    guard let self, let d = del else { return }
                    stillLock.lock(); stillDelegates.removeAll { $0 === d }; stillLock.unlock()
                }
                stillLock.lock(); stillDelegates.append(del!); stillLock.unlock()
                out.capturePhoto(with: settings, delegate: del!)
            }
        }
    }

    /// the light (YouTube Mode's ⚙ → Light on)
    var hasTorch: Bool { (device ?? AVCaptureDevice.default(for: .video))?.hasTorch ?? false }

    func setTorch(_ on: Bool) {
        queue.async { [self] in
            guard let d = device, d.hasTorch, d.isTorchModeSupported(on ? .on : .off) else { return }
            try? d.lockForConfiguration()
            d.torchMode = on ? .on : .off
            d.unlockForConfiguration()
        }
    }

    /// zoom as a person sees it (1 = no zoom)
    func setZoom(_ z: CGFloat) {
        queue.async { [self] in
            guard let d = device else { return }
            try? d.lockForConfiguration()
            d.videoZoomFactor = max(d.minAvailableVideoZoomFactor, min(z * zoomBase, maxZoom))
            d.unlockForConfiguration()
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        recordSink?(sampleBuffer)
        if let wantsFrame, !wantsFrame() { return }
        guard let onFrame, let pb = CMSampleBufferGetImageBuffer(sampleBuffer), let img = RGBImage(pixelBuffer: pb) else { return }
        onFrame(img)
    }
}

extension RGBImage {
    /// A BGRA camera frame as RGB bytes.
    init?(pixelBuffer pb: CVPixelBuffer) {
        guard CVPixelBufferGetPixelFormatType(pb) == kCVPixelFormatType_32BGRA else { return nil }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return nil }
        var rgb = Data(count: w * h * 3)
        rgb.withUnsafeMutableBytes { d in
            var src = vImage_Buffer(data: base, height: vImagePixelCount(h), width: vImagePixelCount(w), rowBytes: CVPixelBufferGetBytesPerRow(pb))
            var dst = vImage_Buffer(data: d.baseAddress!, height: vImagePixelCount(h), width: vImagePixelCount(w), rowBytes: w * 3)
            _ = vImageConvert_BGRA8888toRGB888(&src, &dst, vImage_Flags(kvImageNoFlags))
        }
        self.init(data: rgb, width: w, height: h)
    }

    /// The centred part with this width / height (what a preview that fills its box shows); nil: it has that shape already.
    func visiblePart(aspect a: Double) -> RGBImage? {
        guard a > 0 else { return nil }
        let fa = Double(width) / Double(height)
        if abs(fa - a) / fa < 0.01 { return nil }
        let (x, y, w, h): (Int, Int, Int, Int)
        if a > fa {
            let ch = max(1, min(height, Int((Double(width) / a).rounded())))
            (x, y, w, h) = (0, (height - ch) / 2, width, ch)
        } else {
            let cw = max(1, min(width, Int((Double(height) * a).rounded())))
            (x, y, w, h) = ((width - cw) / 2, 0, cw, height)
        }
        var out = Data(count: w * h * 3)
        data.withUnsafeBytes { s in
            out.withUnsafeMutableBytes { d in
                for row in 0..<h {
                    memcpy(d.baseAddress! + row * w * 3, s.baseAddress! + ((y + row) * width + x) * 3, w * 3)
                }
            }
        }
        return RGBImage(data: out, width: w, height: h)
    }
}

/// Receives one still and hands over the photo file's bytes.
private final class StillDelegate: NSObject, AVCapturePhotoCaptureDelegate {
    private let done: (Data?) -> Void
    private var called = false
    init(_ done: @escaping (Data?) -> Void) { self.done = done }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        finish(error == nil ? photo.fileDataRepresentation() : nil)
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        finish(nil)        // only counts when no photo came before (an error)
    }

    private func finish(_ data: Data?) {
        guard !called else { return }
        called = true
        done(data)
    }
}

/// The view of the camera's one preview layer (see Camera.previewView).
final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

/// The camera picture, filling its box (the scanner cuts frames to the same part). Each camera
/// screen has one of these; the one that comes on screen takes the camera's preview view into it.
struct CameraPreview: UIViewRepresentable {
    let camera: Camera

    final class Holder: UIView {
        var camera: Camera?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { claim() }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            subviews.first?.frame = bounds
        }

        /// moves the camera's preview view in here (out of the screen that had it)
        func claim() {
            guard let camera else { return }
            let v = camera.previewView()
            if v.superview !== self {
                v.removeFromSuperview()
                v.frame = bounds
                v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                addSubview(v)
            }
            if let c = v.preview.connection, c.isVideoRotationAngleSupported(90), c.videoRotationAngle != 90 { c.videoRotationAngle = 90 }
        }
    }

    func makeUIView(context: Context) -> Holder {
        let h = Holder()
        h.backgroundColor = .black
        h.clipsToBounds = true
        h.camera = camera
        return h
    }

    func updateUIView(_ h: Holder, context: Context) {
        h.camera = camera
        if h.window != nil { h.claim() }
    }
}
