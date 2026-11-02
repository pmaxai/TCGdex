package io.github.pmaxai.cardscanner.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/** Where the card shimmers when it moves: holo (artwork), reverse holo (rest of the card), or neither. */
data class FoilEstimate(
    val kind: Kind,
    /** shimmer of the artwork / the card body, relative to the card's border (1 = like the border) */
    val art: Double,
    val body: Double,
    val frames: Int,
) {
    enum class Kind { NORMAL, HOLO, REVERSE, UNSURE }
}

/**
 * Experimental holo check. Foil reflects differently from every angle, so while the card
 * (or phone) moves a little, foil areas change brightness and fine texture from frame to
 * frame much more than printed ink does. Each frame's straightened card is aligned to the
 * previous one, high-pass filtered (smooth glare does not count, sparkle does) and the
 * frame-to-frame change is compared between the artwork, the card body and the border.
 */
class FoilMeter(private val g: Geometry, private val maxFrames: Int = 10) {
    private val frames = ArrayDeque<Mat>()
    private val w = 126
    private val h = 176

    fun reset() {
        frames.forEach { it.release() }
        frames.clear()
    }

    val count: Int get() = frames.size

    /** quad: the card's corners in its own order (upright card). */
    fun add(rgb: Mat, quad: Array<Point>) {
        val card = Readings.rectify(rgb, quad, w, h)
        val gray = Mat()
        Imgproc.cvtColor(card, gray, Imgproc.COLOR_RGB2GRAY)
        card.release()
        val f = Mat()
        gray.convertTo(f, CvType.CV_32F)
        gray.release()
        val mean = Core.mean(f).`val`[0].coerceAtLeast(1.0)
        Core.multiply(f, Scalar(100.0 / mean), f)       // ignore overall brightness changes
        frames.addLast(f)
        while (frames.size > maxFrames) frames.removeFirst().release()
    }

    fun estimate(): FoilEstimate? {
        if (frames.size < 5) return null
        // high-pass each frame, aligned to the previous one
        val hp = ArrayList<Mat>()
        var prev: Mat? = null
        for (f in frames) {
            val aligned = if (prev == null) f.clone() else {
                val shift = Imgproc.phaseCorrelate(prev, f)
                val m = Mat(2, 3, CvType.CV_64F)
                m.put(0, 0, 1.0, 0.0, -shift.x, 0.0, 1.0, -shift.y)
                Mat().also { Imgproc.warpAffine(f, it, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE); m.release() }
            }
            val blur = Mat()
            Imgproc.GaussianBlur(aligned, blur, Size(0.0, 0.0), 3.0)
            val d = Mat()
            Core.subtract(aligned, blur, d)
            blur.release()
            prev = aligned.also { prev?.release() }
            hp.add(d)
        }
        prev?.release()
        // per-pixel change between consecutive high-pass frames
        val acc = Mat.zeros(h, w, CvType.CV_32F)
        val diff = Mat()
        for (i in 1 until hp.size) {
            Core.absdiff(hp[i], hp[i - 1], diff)
            Core.add(acc, diff, acc)
        }
        diff.release()
        hp.forEach { it.release() }
        val (x0, y0, x1, y1) = g.artBox.toList()
        val art = Rect((x0 * w).toInt() + 3, (y0 * h).toInt() + 3, ((x1 - x0) * w).toInt() - 6, ((y1 - y0) * h).toInt() - 6)
        val border = 0.045
        val bx = (border * w).toInt()
        val by = (border * h).toInt()
        val body = Rect(bx + 2, (y1 * h).toInt() + 3, w - 2 * bx - 4, h - by - (y1 * h).toInt() - 5)
        val vArt = Core.mean(acc.submat(art)).`val`[0]
        val vBody = Core.mean(acc.submat(body)).`val`[0]
        val ring = Mat.zeros(h, w, CvType.CV_8U)
        Imgproc.rectangle(ring, Point(0.0, 0.0), Point(w - 1.0, h - 1.0), Scalar(255.0), -1)
        Imgproc.rectangle(ring, Point(bx.toDouble(), by.toDouble()), Point(w - 1.0 - bx, h - 1.0 - by), Scalar(0.0), -1)
        val vBorder = Core.mean(acc, ring).`val`[0].coerceAtLeast(1e-3)
        ring.release()
        acc.release()
        val a = vArt / vBorder
        val b = vBody / vBorder
        val kind = when {
            a < 1.5 && b < 1.5 -> FoilEstimate.Kind.NORMAL
            a >= 1.8 && a > 1.3 * b -> FoilEstimate.Kind.HOLO
            b >= 1.8 && b > 1.3 * a -> FoilEstimate.Kind.REVERSE
            else -> FoilEstimate.Kind.UNSURE
        }
        return FoilEstimate(kind, (a * 100).toInt() / 100.0, (b * 100).toInt() / 100.0, frames.size)
    }

    @Suppress("unused")
    private fun rms(m: Mat): Double = sqrt(Core.mean(m.mul(m)).`val`[0])
}
