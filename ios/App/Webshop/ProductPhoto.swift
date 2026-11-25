import CoreGraphics
import UIKit

/**
 * Product pictures taken with the scanner (Webshop Mode), for cards that have no picture in their
 * language: a still of the camera, cut to the card's outer edge and straightened, at the card's
 * shape (63:88). (Port of the Android app's ProductPhoto.kt.)
 */
enum ProductPhoto {
    static let cardAspect = 63.0 / 88.0

    /// A copy at most maxSide pixels on its long side, and the factor it was scaled by (1: as it was).
    static func scaled(_ img: RGBImage, maxSide: Int) -> (RGBImage, Double) {
        let k = min(1, Double(maxSide) / Double(max(img.width, img.height)))
        guard k < 1 else { return (img, 1) }
        let w = max(1, Int((Double(img.width) * k).rounded())), h = max(1, Int((Double(img.height) * k).rounded()))
        return (resized(img, width: w, height: h) ?? img, k)
    }

    /// The image drawn at another size (smooth).
    static func resized(_ img: RGBImage, width w: Int, height h: Int) -> RGBImage? {
        guard let cg = img.uiImage?.cgImage,
              let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage().flatMap { RGBImage(cgImage: $0) }
    }

    /// A rectangle of the image (clamped to it).
    static func crop(_ img: RGBImage, x: Int, y: Int, width: Int, height: Int) -> RGBImage {
        let x = max(0, min(img.width - 1, x)), y = max(0, min(img.height - 1, y))
        let w = max(1, min(img.width - x, width)), h = max(1, min(img.height - y, height))
        var out = Data(count: w * h * 3)
        img.data.withUnsafeBytes { s in
            out.withUnsafeMutableBytes { d in
                for row in 0..<h { memcpy(d.baseAddress! + row * w * 3, s.baseAddress! + ((y + row) * img.width + x) * 3, w * 3) }
            }
        }
        return RGBImage(data: out, width: w, height: h)
    }

    /**
     * The product picture from vis (what the camera box shows): the card inside quad (its outer
     * corners tl, tr, br, bl of the upright card) straightened; without an outline, the guide frame
     * cut out. Its height is the card's height in the photo, 600–1600 px.
     */
    static func cardImage(_ vis: RGBImage, quad: [CGPoint]?, prior: CCPrior) -> RGBImage {
        if let q = quad, q.count == 4 {
            let tall = (hypot(q[3].x - q[0].x, q[3].y - q[0].y) + hypot(q[2].x - q[1].x, q[2].y - q[1].y)) / 2
            let h = min(1600, max(600, Int(tall.rounded())))
            return ScannerEngine.rectify(vis, quad: q, width: Int((Double(h) * cardAspect).rounded()), height: h)
        }
        let ch = min(prior.h * Double(vis.height), Double(vis.height))
        let cw = min(ch * cardAspect, Double(vis.width))
        let x = min(max(0, prior.cx * Double(vis.width) - cw / 2), Double(vis.width) - cw)
        let y = min(max(0, prior.cy * Double(vis.height) - ch / 2), Double(vis.height) - ch)
        let sub = crop(vis, x: Int(x.rounded()), y: Int(y.rounded()), width: max(1, Int(cw.rounded())), height: max(1, Int(ch.rounded())))
        let h = min(1600, max(600, Int(ch.rounded())))
        return resized(sub, width: Int((Double(h) * cardAspect).rounded()), height: h) ?? sub
    }

    /// The picture as JPEG (quality 92) and small for the screen.
    static func encode(_ card: RGBImage, thumbHeight: CGFloat = 220) -> (Data, UIImage)? {
        guard let img = card.uiImage, let jpeg = img.jpegData(compressionQuality: 0.92) else { return nil }
        let size = CGSize(width: max(1, thumbHeight * img.size.width / max(1, img.size.height)), height: thumbHeight)
        let fmt = UIGraphicsImageRendererFormat.default()
        fmt.scale = 1
        let thumb = UIGraphicsImageRenderer(size: size, format: fmt).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        return (jpeg, thumb)
    }

    /**
     * A picture of another print (the Japanese original, the western card) for a product printed in
     * another language: a band across the lower half says so, e.g. "Printed in Korean" – large white
     * letters on dark red, the full width of the card, so nobody takes it for the print sold.
     */
    static func labeled(_ src: UIImage, label: String) -> UIImage {
        let fmt = UIGraphicsImageRendererFormat.default()
        fmt.scale = src.scale
        fmt.opaque = true
        return UIGraphicsImageRenderer(size: src.size, format: fmt).image { ctx in
            src.draw(at: .zero)
            let w = src.size.width, h = src.size.height
            let bandH = h * 0.12, top = h * 0.58
            UIColor(red: 0xB7 / 255, green: 0x1C / 255, blue: 0x1C / 255, alpha: 0xEE / 255).setFill()
            ctx.fill(CGRect(x: 0, y: top, width: w, height: bandH))
            UIColor.white.setFill()
            let line = max(1, h * 0.006)
            ctx.fill(CGRect(x: 0, y: top - line / 2, width: w, height: line))
            ctx.fill(CGRect(x: 0, y: top + bandH - line / 2, width: w, height: line))
            var size = bandH * 0.56
            var attrs: [NSAttributedString.Key: Any] = [:]
            repeat {
                attrs = [.font: UIFont.systemFont(ofSize: size, weight: .bold), .foregroundColor: UIColor.white]
                if (label as NSString).size(withAttributes: attrs).width <= w * 0.9 || size <= 8 { break }
                size *= 0.92
            } while true
            let t = (label as NSString).size(withAttributes: attrs)
            (label as NSString).draw(at: CGPoint(x: (w - t.width) / 2, y: top + (bandH - t.height) / 2), withAttributes: attrs)
        }
    }

    /// A picture from the web (TCGdex, pokemontcg.io); nil if it can't be loaded.
    static func download(_ url: String, session: URLSession = .shared) async -> UIImage? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.timeoutInterval = 30
        guard let answer = try? await session.data(for: req), let http = answer.1 as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            return nil
        }
        return UIImage(data: answer.0)
    }
}
