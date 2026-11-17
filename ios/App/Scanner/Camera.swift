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
    var onFrame: ((RGBImage) -> Void)?

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

    func start() {
        queue.async { [self] in
            if !configured { configure() }
            if !session.isRunning { session.startRunning() }
        }
    }

    func stop() {
        queue.async { [self] in if session.isRunning { session.stopRunning() } }
    }

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

    func setTorch(_ on: Bool) {
        queue.async { [self] in
            guard let d = device, d.hasTorch else { return }
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

/// The camera picture, filling its box (the scanner cuts frames to the same part).
struct CameraPreview: UIViewRepresentable {
    let camera: Camera

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.backgroundColor = .black
        v.preview.session = camera.session
        v.preview.videoGravity = .resizeAspectFill
        if let c = v.preview.connection, c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
        return v
    }

    func updateUIView(_ v: PreviewView, context: Context) {
        if let c = v.preview.connection, c.isVideoRotationAngleSupported(90), c.videoRotationAngle != 90 { c.videoRotationAngle = 90 }
    }
}
