import Accelerate
import CoreGraphics
import ImageIO
import UIKit

/// A photo as tightly packed RGB bytes, upright: what CardCore reads (as OpenCV's RGB Mats on Android).
struct RGBImage {
    let data: Data
    let width: Int
    let height: Int

    init(data: Data, width: Int, height: Int) {
        precondition(data.count == width * height * 3)
        self.data = data
        self.width = width
        self.height = height
    }

    /**
     * The image turned upright by its orientation. Colours are taken as they are stored, without a
     * conversion to another colour space (like the pipeline reads its photos), so a Display P3 photo
     * gives the same numbers as on Android.
     */
    init?(cgImage cg: CGImage, orientation: CGImagePropertyOrientation = .up) {
        let w = cg.width, h = cg.height
        guard w > 0, h > 0 else { return nil }
        let own = cg.colorSpace.flatMap { $0.model == .rgb ? $0 : nil }
        var rgbx = Data(count: w * h * 4)
        let drawn: Bool = rgbx.withUnsafeMutableBytes { buf in
            let info = CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
            for space in [own, CGColorSpace(name: CGColorSpace.sRGB)].compactMap({ $0 }) {
                if let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                       space: space, bitmapInfo: info) {
                    ctx.interpolationQuality = .none
                    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
                    return true
                }
            }
            return false
        }
        guard drawn else { return nil }
        let (up, uw, uh) = RGBImage.upright(rgbx, w, h, orientation)
        var rgb = Data(count: uw * uh * 3)
        up.withUnsafeBytes { s in
            rgb.withUnsafeMutableBytes { d in
                var src = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: s.baseAddress!), height: vImagePixelCount(uh),
                                        width: vImagePixelCount(uw), rowBytes: uw * 4)
                var dst = vImage_Buffer(data: d.baseAddress!, height: vImagePixelCount(uh), width: vImagePixelCount(uw), rowBytes: uw * 3)
                _ = vImageConvert_RGBA8888toRGB888(&src, &dst, vImage_Flags(kvImageNoFlags))
            }
        }
        self.init(data: rgb, width: uw, height: uh)
    }

    init?(uiImage: UIImage) {
        guard let cg = uiImage.cgImage else { return nil }
        self.init(cgImage: cg, orientation: CGImagePropertyOrientation(uiImage.imageOrientation))
    }

    /// A photo file (JPEG, PNG, HEIC) with its EXIF orientation applied.
    init?(contentsOf url: URL) {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        self.init(source: src)
    }

    init?(imageData: Data) {
        guard let src = CGImageSourceCreateWithData(imageData as CFData, nil) else { return nil }
        self.init(source: src)
    }

    private init?(source: CGImageSource) {
        guard let cg = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else { return nil }
        let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let raw = (props?[kCGImagePropertyOrientation] as? NSNumber)?.uint32Value ?? 1
        self.init(cgImage: cg, orientation: CGImagePropertyOrientation(rawValue: raw) ?? .up)
    }

    /// Exact quarter turns and mirroring of an RGBX buffer (no resampling).
    private static func upright(_ rgbx: Data, _ w: Int, _ h: Int, _ o: CGImagePropertyOrientation) -> (Data, Int, Int) {
        let (turn, mirror): (Int, Bool) = switch o {
        case .up: (kRotate0DegreesClockwise, false)
        case .upMirrored: (kRotate0DegreesClockwise, true)
        case .down: (kRotate180DegreesClockwise, false)
        case .downMirrored: (kRotate180DegreesClockwise, true)
        case .right: (kRotate90DegreesClockwise, false)
        case .rightMirrored: (kRotate90DegreesCounterClockwise, true)
        case .left: (kRotate90DegreesCounterClockwise, false)
        case .leftMirrored: (kRotate90DegreesClockwise, true)
        @unknown default: (kRotate0DegreesClockwise, false)
        }
        var cur = rgbx, cw = w, ch = h
        if turn != kRotate0DegreesClockwise {
            let swap = turn == kRotate90DegreesClockwise || turn == kRotate90DegreesCounterClockwise
            let (ow, oh) = swap ? (ch, cw) : (cw, ch)
            cur = transform(cur, cw, ch, ow, oh) { src, dst in
                var back: [UInt8] = [0, 0, 0, 0]
                _ = vImageRotate90_ARGB8888(src, dst, UInt8(turn), &back, vImage_Flags(kvImageNoFlags))
            }
            (cw, ch) = (ow, oh)
        }
        if mirror {
            cur = transform(cur, cw, ch, cw, ch) { src, dst in _ = vImageHorizontalReflect_ARGB8888(src, dst, vImage_Flags(kvImageNoFlags)) }
        }
        return (cur, cw, ch)
    }

    private static func transform(_ input: Data, _ w: Int, _ h: Int, _ ow: Int, _ oh: Int,
                                  _ op: (UnsafePointer<vImage_Buffer>, UnsafePointer<vImage_Buffer>) -> Void) -> Data {
        var out = Data(count: ow * oh * 4)
        input.withUnsafeBytes { s in
            out.withUnsafeMutableBytes { d in
                var src = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: s.baseAddress!), height: vImagePixelCount(h),
                                        width: vImagePixelCount(w), rowBytes: w * 4)
                var dst = vImage_Buffer(data: d.baseAddress!, height: vImagePixelCount(oh), width: vImagePixelCount(ow), rowBytes: ow * 4)
                op(&src, &dst)
            }
        }
        return out
    }

    /// For showing it (sRGB).
    var uiImage: UIImage? {
        guard let provider = CGDataProvider(data: data as CFData),
              let cg = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 24, bytesPerRow: width * 3,
                               space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

extension CGImagePropertyOrientation {
    init(_ o: UIImage.Orientation) {
        switch o {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
