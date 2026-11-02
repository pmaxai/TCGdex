package io.github.pmaxai.cardscanner.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Image geometry shared with the pipeline (values come from the data pack's meta table):
 * a straightened card is warped to rectW x rectH, stored at cardW x cardH, and the
 * fingerprint model sees the artwork rectangle (artX, artY, artW, artH) resized to input x input.
 */
class Geometry(
    val rectW: Int, val rectH: Int,
    val cardW: Int, val cardH: Int,
    val artX: Int, val artY: Int, val artW: Int, val artH: Int,
    /** artwork box as fractions of the card (x0, y0, x1, y1) */
    val artBox: DoubleArray,
    val input: Int,
)

object Readings {
    /** Warp the quad (tl, tr, br, bl) to an upright card image; like detect.rectify. */
    fun rectify(rgb: Mat, quad: Array<Point>, w: Int, h: Int): Mat {
        val src = MatOfPoint2f(*quad)
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(rgb, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        src.release(); dst.release(); m.release()
        return out
    }

    /** Straightened card at the stored size (embedders.to_card). */
    fun toCard(rgb: Mat, quad: Array<Point>, g: Geometry): Mat {
        val r = rectify(rgb, quad, g.rectW, g.rectH)
        val card = Mat()
        Imgproc.resize(r, card, Size(g.cardW.toDouble(), g.cardH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        r.release()
        return card
    }

    fun rot180(m: Mat): Mat = Mat().also { Core.rotate(m, it, Core.ROTATE_180) }

    /**
     * Second reading: the quad is the card's ARTWORK box (detect.as_artwork). Returns two
     * card-sized images (both 180-degree orientations) with the quad's content placed where
     * the artwork sits on a card; the rest is neutral grey.
     */
    fun asArtwork(rgb: Mat, quad: Array<Point>, g: Geometry): Pair<Mat, Mat> {
        val q = CardDetector.orderQuad(quad)
        val land = arrayOf(q[3], q[0], q[1], q[2])           // np.roll(q, 1): long side horizontal
        val cw = g.cardW.toDouble()
        val ch = g.cardH.toDouble()
        val (x0, y0, x1, y1) = g.artBox.toList()
        val dst = MatOfPoint2f(
            Point(x0 * cw, y0 * ch), Point(x1 * cw - 1, y0 * ch), Point(x1 * cw - 1, y1 * ch - 1), Point(x0 * cw, y1 * ch - 1)
        )
        val rx = (x0 * cw).toInt()
        val ry = (y0 * ch).toInt()
        val roi = Rect(rx, ry, (x1 * cw).toInt() - rx, (y1 * ch).toInt() - ry)
        val out = ArrayList<Mat>()
        for (src in listOf(land, arrayOf(land[2], land[3], land[0], land[1]))) {
            val s = MatOfPoint2f(*src)
            val m = Imgproc.getPerspectiveTransform(s, dst)
            val warped = Mat()
            Imgproc.warpPerspective(rgb, warped, m, Size(cw, ch), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT,
                Scalar(128.0, 128.0, 128.0))
            val canvas = Mat(g.cardH, g.cardW, CvType.CV_8UC3, Scalar(128.0, 128.0, 128.0))
            warped.submat(roi).copyTo(canvas.submat(roi))
            out.add(canvas)
            s.release(); m.release(); warped.release()
        }
        dst.release()
        return out[0] to out[1]
    }

    /**
     * The whole card's corners when [quad] is its artwork box (the inverse of [asArtwork]).
     * Returned in the card's own order (tl, tr, br, bl of the upright card), so
     * [rectify] gives an upright card. Used to read the card's text.
     */
    fun cardQuadFromArtwork(quad: Array<Point>, upsideDown: Boolean, g: Geometry): Array<Point> {
        val q = CardDetector.orderQuad(quad)
        val land = arrayOf(q[3], q[0], q[1], q[2])
        val src = if (upsideDown) arrayOf(land[2], land[3], land[0], land[1]) else land
        val cw = g.cardW.toDouble()
        val ch = g.cardH.toDouble()
        val (x0, y0, x1, y1) = g.artBox.toList()
        val dst = MatOfPoint2f(
            Point(x0 * cw, y0 * ch), Point(x1 * cw - 1, y0 * ch), Point(x1 * cw - 1, y1 * ch - 1), Point(x0 * cw, y1 * ch - 1)
        )
        val s = MatOfPoint2f(*src)
        val h = Imgproc.getPerspectiveTransform(dst, s)            // card coordinates -> photo
        // a little margin: the artwork box position varies between card eras
        val m = 0.03
        val corners = MatOfPoint2f(Point(-m * cw, -m * ch), Point((1 + m) * cw, -m * ch),
            Point((1 + m) * cw, (1 + m) * ch), Point(-m * cw, (1 + m) * ch))
        val out = MatOfPoint2f()
        Core.perspectiveTransform(corners, out, h)
        val r = out.toArray()
        listOf(dst, s, h, corners, out).forEach { it.release() }
        return r
    }

    /** Upright card at a size good for reading its small print (set number). */
    fun cardForReading(rgb: Mat, quad: Array<Point>, upsideDown: Boolean): Mat {
        val r = rectify(rgb, quad, 720, 1006)
        if (!upsideDown) return r
        val u = rot180(r)
        r.release()
        return u
    }

    /** The model's input: the artwork rectangle of a card image, resized (embedders.crop_region). */
    fun artInput(card: Mat, g: Geometry): Mat {
        val sub = card.submat(Rect(g.artX, g.artY, g.artW, g.artH))
        val out = Mat()
        Imgproc.resize(sub, out, Size(g.input.toDouble(), g.input.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    /** The guide frame as a quad, used when no card is found (evaluate.guide_quad). */
    fun guideQuad(w: Int, h: Int, prior: Prior): Array<Point> {
        var ch = prior.h * h
        var cw = ch * 63 / 88
        if (cw > 0.9 * w) { cw = 0.9 * w; ch = cw * 88 / 63 }
        val x0 = prior.cx * w - cw / 2
        val y0 = prior.cy * h - ch / 2
        return arrayOf(Point(x0, y0), Point(x0 + cw, y0), Point(x0 + cw, y0 + ch), Point(x0, y0 + ch))
    }

    /** augment.guide_prior: card centred, 70% of the height but at most 80% of the width. */
    fun guidePrior(w: Int, h: Int): Prior =
        Prior(0.5, 0.5, minOf(0.7, 0.8 * w / (CardDetector.CARD_ASPECT * h)))
}
