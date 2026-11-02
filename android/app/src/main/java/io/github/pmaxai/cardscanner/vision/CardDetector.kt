package io.github.pmaxai.cardscanner.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** A found card: corners tl, tr, br, bl (portrait order) in image pixels. */
class Detection(val quad: Array<Point>, val score: Double)

/** Where the app's guide frame expects the card: centre and card height as fractions of the image. */
data class Prior(val cx: Double, val cy: Double, val h: Double)

/**
 * Finds cards in a photo. Line-by-line port of pipeline/tcgscan/detect.py
 * (multi-scale contours + hull repair + edge check + border expansion + line
 * refinement); the pipeline's accuracy test measures exactly this logic.
 */
class CardDetector(
    private val workSizes: IntArray = intArrayOf(300, 450, 600),   // short sides
    private val supportMin: Double = 0.15,
    private val supportPow: Int = 2,
    private val refine: Boolean = true,
    private val expand: Boolean = true,
    private val useHull: Boolean = true,
) {
    private class Cand(val score: Double, val quad: Array<Point>, val support: Double, val si: Int)
    private class Kept(var quad: Array<Point>, val score: Double, val si: Int)

    private class Ctx(val scale: Double, val small: Mat, val gray: Mat, val union: Mat) {
        val unionBytes: ByteArray by lazy { bytes(union) }
        fun release() {
            if (scale < 1.0) small.release()
            gray.release(); union.release()
        }
    }

    fun detect(rgb: Mat, maxCards: Int = 1, minAreaFrac: Double = 0.03, prior: Prior? = null): List<Detection> {
        val cands = ArrayList<Cand>()
        val ctx = ArrayList<Ctx>()
        try {
            for ((si, ws) in workSizes.withIndex()) {
                val x = scaleCandidates(rgb, ws, minAreaFrac, prior, si, cands)
                ctx.add(x)
            }
            val sorted = cands.sortedByDescending { it.score }        // stable, like Python's sort
            val kept = ArrayList<Kept>()
            for (c in sorted) {
                val cen = centroid(c.quad)
                val clash = kept.any { k -> inside(cen, k.quad) || inside(centroid(k.quad), c.quad) }
                if (!clash) kept.add(Kept(c.quad, c.score, c.si))
            }
            val result = ArrayList<Detection>()
            for (k in kept.take(maxCards)) {
                var q = k.quad
                val si = k.si
                if (refine) {
                    val x = ctx[si]
                    val raw = rawEdgePoints(x.gray)
                    val sc = x.scale
                    var qs = q.map { Point(it.x * sc, it.y * sc) }.toTypedArray()
                    qs = refineQuad(qs, raw)
                    if (expand) qs = refineQuad(expandToBorder(qs, x.unionBytes, x.union.cols(), x.union.rows(), x.small), raw)
                    q = qs.map { Point(it.x / sc, it.y / sc) }.toTypedArray()
                }
                result.add(Detection(orderQuad(q), k.score))
            }
            return result
        } finally {
            ctx.forEach { it.release() }
        }
    }

    // ------------------------------------------------------------ one scale
    private fun scaleCandidates(img: Mat, workSize: Int, minAreaFrac: Double, prior: Prior?, si: Int,
                                out: MutableList<Cand>): Ctx {
        val scale = min(1.0, workSize.toDouble() / min(img.rows(), img.cols()))
        val small = if (scale < 1.0) Mat().also { Imgproc.resize(img, it, Size(), scale, scale, Imgproc.INTER_AREA) } else img
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val imgArea = small.rows().toDouble() * small.cols()
        val maps = edgeMaps(small, gray)
        val union = Mat.zeros(gray.size(), CvType.CV_8U)
        for (m in maps) Core.bitwise_or(union, m, union)
        val k = max(3, (pyRound(0.004 * max(small.rows(), small.cols())).toInt()) or 1)
        val kernel = Mat.ones(k, k, CvType.CV_8U)
        Imgproc.dilate(union, union, kernel)
        kernel.release()
        val x = Ctx(scale, small, gray, union)
        val ub = x.unionBytes
        val uw = union.cols()
        val uh = union.rows()

        fun consider(quad: Array<Point>) {
            var s = quadScore(quad, imgArea)
            if (s <= 0) return
            val (supMean, supMin) = edgeSupport(quad, ub, uw, uh)
            if (supMin < supportMin) return
            if (prior != null) s *= priorWeight(quad, prior, uh, uw)
            var pw = 1.0
            repeat(supportPow) { pw *= supMean }
            out.add(Cand(s * pw, quad.map { Point(it.x / scale, it.y / scale) }.toTypedArray(), supMean, si))
        }

        for (edges in maps) {
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area < minAreaFrac * imgArea) { c.release(); continue }
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val quads = ArrayList<Array<Point>>()
                for (eps in doubleArrayOf(0.015, 0.025, 0.04)) {
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                    val pts = approx.toArray()
                    approx.release()
                    if (pts.size == 4) {
                        val mp = MatOfPoint(*pts.map { Point(it.x, it.y) }.toTypedArray())
                        val convex = Imgproc.isContourConvex(mp)
                        mp.release()
                        if (convex) { quads.add(pts); break }
                    }
                }
                if (useHull) {
                    val idx = MatOfInt()
                    Imgproc.convexHull(c, idx)
                    val cp = c.toArray()
                    val hullPts = idx.toArray().map { cp[it] }
                    idx.release()
                    val hullMat = MatOfPoint(*hullPts.toTypedArray())
                    val hullArea = Imgproc.contourArea(hullMat)
                    hullMat.release()
                    if (hullArea > 0 && area / hullArea > 0.8) {
                        val h2f = MatOfPoint2f(*hullPts.toTypedArray())
                        val ap = MatOfPoint2f()
                        Imgproc.approxPolyDP(h2f, ap, 0.005 * peri, true)
                        val hq = hullToQuad(ap.toArray())
                        h2f.release(); ap.release()
                        if (hq != null && area / max(polyArea(hq), 1.0) > 0.8) quads.add(hq)
                    }
                }
                c2f.release(); c.release()
                for (q in quads) consider(q)
            }
        }
        maps.forEach { it.release() }
        return x
    }

    private fun edgeMaps(small: Mat, gray: Mat): List<Mat> {
        val k3 = Mat.ones(3, 3, CvType.CV_8U)
        val out = ArrayList<Mat>()
        out.add(autoCanny(gray).also { Imgproc.dilate(it, it, k3) })
        out.add(autoCanny(gray, 0.33, 0.8).also { Imgproc.dilate(it, it, k3) })
        // colour edges: a yellow card border on a beige table is clear in colour, not in grey
        val lab = Mat()
        Imgproc.cvtColor(small, lab, Imgproc.COLOR_RGB2Lab)
        Imgproc.GaussianBlur(lab, lab, Size(5.0, 5.0), 0.0)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        lab.release()
        val e = autoCanny(ch[0])
        for (i in 1..2) {
            val c = Mat()
            Imgproc.Canny(ch[i], c, 20.0, 50.0)
            Core.bitwise_or(e, c, e)
            c.release()
        }
        ch.forEach { it.release() }
        Imgproc.morphologyEx(e, e, Imgproc.MORPH_CLOSE, k3, Point(-1.0, -1.0), 2)
        out.add(e)
        // adaptive threshold catches low-contrast borders
        val th = Mat()
        Imgproc.adaptiveThreshold(gray, th, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 31, 5.0)
        Core.bitwise_not(th, th)
        val k5 = Mat.ones(5, 5, CvType.CV_8U)
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, k5)
        k5.release(); k3.release()
        out.add(th)
        return out
    }

    private class EdgePoints(val xs: FloatArray, val ys: FloatArray)

    private fun rawEdgePoints(gray: Mat): EdgePoints {
        val a = autoCanny(gray)
        val b = autoCanny(gray, 0.33, 0.8)
        Core.bitwise_or(a, b, a)
        val w = a.cols()
        val bytes = bytes(a)
        a.release(); b.release()
        var n = 0
        for (v in bytes) if (v.toInt() != 0) n++
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var j = 0
        for (i in bytes.indices) if (bytes[i].toInt() != 0) { xs[j] = (i % w).toFloat(); ys[j] = (i / w).toFloat(); j++ }
        return EdgePoints(xs, ys)
    }

    // ------------------------------------------------------------ refinement
    private fun refineQuad(quad: Array<Point>, e: EdgePoints, bandFrac: Double = 0.015): Array<Point> {
        val q = orderQuad(quad)
        val cardH = dist(q[3], q[0])
        val band = max(3.0, bandFrac * cardH)
        if (e.xs.size < 50) return q
        val lines = ArrayList<Pair<Point, Point>>()
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            val ux = dx / len
            val uy = dy / len
            val nx = -uy
            val ny = ux
            val sel = ArrayList<Point>()
            for (k in e.xs.indices) {
                val rx = e.xs[k] - a.x
                val ry = e.ys[k] - a.y
                val t = rx * ux + ry * uy
                val d = rx * nx + ry * ny
                if (abs(d) < band && t > 0.1 * len && t < 0.9 * len) sel.add(Point(e.xs[k].toDouble(), e.ys[k].toDouble()))
            }
            if (sel.size < 0.3 * 0.8 * len) return q
            val pts = MatOfPoint2f(*sel.toTypedArray())
            val line = Mat()
            Imgproc.fitLine(pts, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val v = FloatArray(4)
            line.get(0, 0, v)
            pts.release(); line.release()
            lines.add(Point(v[2].toDouble(), v[3].toDouble()) to Point((v[2] + v[0]).toDouble(), (v[3] + v[1]).toDouble()))
        }
        val out = intersectAll(lines) ?: return q
        for (i in 0 until 4) if (max(abs(out[i].x - q[i].x), abs(out[i].y - q[i].y)) > 0.05 * cardH) return q
        return out
    }

    /** If the quad is the card's inner panel, grow it to the outer edge of the border. */
    private fun expandToBorder(quad: Array<Point>, edges: ByteArray, w: Int, h: Int, img: Mat,
                               lo: Double = 0.015, hi: Double = 0.065): Array<Point> {
        val q = orderQuad(quad)
        val cardH = dist(q[3], q[0])
        val c = centroid(q)
        val start = lo * cardH
        val stop = hi * cardH
        val step = max(1.0, 0.004 * cardH)
        val nOffs = ceil((stop - start) / step).toInt()
        if (nOffs < 3) return q
        val ts = DoubleArray(40) { 0.15 + (0.85 - 0.15) * it / 39.0 }
        val lines = ArrayList<Pair<Point, Point>>()
        val found = ArrayList<Double>()
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val len = max(1e-6, dist(a, b))
            val ux = (b.x - a.x) / len
            val uy = (b.y - a.y) / len
            var nx = -uy
            var ny = ux
            if (((a.x + b.x) / 2 - c.x) * nx + ((a.y + b.y) / 2 - c.y) * ny < 0) { nx = -nx; ny = -ny }
            var bestOff = -1.0
            var bestSup = 0.0
            for (o in 0 until nOffs) {
                val off = start + o * step
                val pax = a.x + nx * off
                val pay = a.y + ny * off
                val pbx = b.x + nx * off
                val pby = b.y + ny * off
                var okN = 0
                var hits = 0
                for (t in ts) {
                    val px = Math.rint(pax + (pbx - pax) * t).toInt()
                    val py = Math.rint(pay + (pby - pay) * t).toInt()
                    if (px in 0 until w && py in 0 until h) {
                        okN++
                        if (edges[py * w + px].toInt() != 0) hits++
                    }
                }
                if (okN < 0.9 * ts.size) break
                val sup = hits.toDouble() / okN
                if (sup > bestSup + 0.05) { bestOff = off; bestSup = sup }
                else if (bestSup >= 0.6 && sup < bestSup - 0.2) break
            }
            if (bestOff < 0 || bestSup < 0.6) return q
            found.add(bestOff)
            lines.add(Point(a.x + nx * bestOff, a.y + ny * bestOff) to Point(b.x + nx * bestOff, b.y + ny * bestOff))
        }
        if (found.max() / found.min() > 1.7) return q
        val out = intersectAll(lines) ?: return q
        // the band between old and new outline must be one colour (a card border)
        val mask = Mat.zeros(h, w, CvType.CV_8U)
        val po = MatOfPoint(*out.map { Point(it.x.toInt().toDouble(), it.y.toInt().toDouble()) }.toTypedArray())
        val pq = MatOfPoint(*q.map { Point(it.x.toInt().toDouble(), it.y.toInt().toDouble()) }.toTypedArray())
        Imgproc.fillConvexPoly(mask, po, Scalar(1.0))
        Imgproc.fillConvexPoly(mask, pq, Scalar(0.0))
        po.release(); pq.release()
        val mb = bytes(mask)
        mask.release()
        val ib = bytes(img)
        val ringIdx = ArrayList<Int>()
        for (i in mb.indices) if (mb[i].toInt() != 0) ringIdx.add(i)
        if (ringIdx.size < 50) return q
        val med = DoubleArray(3)
        for (ch in 0 until 3) {
            val hist = IntArray(256)
            for (i in ringIdx) hist[ib[i * 3 + ch].toInt() and 0xFF]++
            med[ch] = medianOfHist(hist, ringIdx.size)
        }
        var close = 0
        for (i in ringIdx) {
            var s = 0.0
            for (ch in 0 until 3) s += abs((ib[i * 3 + ch].toInt() and 0xFF) - med[ch])
            if (s < 60) close++
        }
        if (close.toDouble() / ringIdx.size < 0.6) return q
        return out
    }

    companion object {
        const val CARD_ASPECT = 63.0 / 88.0

        fun orderQuad(p: Array<Point>): Array<Point> {
            val cx = p.sumOf { it.x } / 4
            val cy = p.sumOf { it.y } / 4
            val s = p.sortedBy { atan2(it.y - cy, it.x - cx) }
            var start = 0
            for (i in 1 until 4) if (s[i].x + s[i].y < s[start].x + s[start].y) start = i
            var r = Array(4) { s[(it + start) % 4] }
            val w = (dist(r[1], r[0]) + dist(r[2], r[3])) / 2
            val h = (dist(r[3], r[0]) + dist(r[2], r[1])) / 2
            if (w > h) r = Array(4) { r[(it + 1) % 4] }
            return r
        }

        fun quadScore(quad: Array<Point>, imgArea: Double): Double {
            val q = orderQuad(quad)
            val sides = DoubleArray(4) { dist(q[it], q[(it + 1) % 4]) }
            if (sides.min() < 10) return 0.0
            val w = (sides[0] + sides[2]) / 2
            val h = (sides[1] + sides[3]) / 2
            val aspect = w / h
            if (!(aspect > 0.5 && aspect < 0.95)) return 0.0
            val frac = polyArea(q) / imgArea
            if (frac > 0.98) return 0.0
            val cos = DoubleArray(4) { i ->
                val p = q[i]
                val a = q[(i + 3) % 4]
                val b = q[(i + 1) % 4]
                val v1x = a.x - p.x; val v1y = a.y - p.y
                val v2x = b.x - p.x; val v2y = b.y - p.y
                abs(v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-6)
            }
            if (cos.max() > 0.5) return 0.0
            val aspectScore = exp(-((aspect - CARD_ASPECT) / 0.07).let { it * it })
            val angleScore = 1.0 - cos.average()
            return aspectScore * angleScore * sqrt(frac)
        }

        fun edgeSupport(quad: Array<Point>, edges: ByteArray, w: Int, h: Int, n: Int = 40): Pair<Double, Double> {
            val q = orderQuad(quad)
            val fr = DoubleArray(4)
            for (i in 0 until 4) {
                val a = q[i]
                val b = q[(i + 1) % 4]
                var hits = 0
                for (k in 0 until n) {
                    val t = 0.12 + (0.88 - 0.12) * k / (n - 1)
                    val px = Math.rint(a.x + (b.x - a.x) * t).toInt()
                    val py = Math.rint(a.y + (b.y - a.y) * t).toInt()
                    if (px in 0 until w && py in 0 until h && edges[py * w + px].toInt() != 0) hits++
                }
                fr[i] = hits.toDouble() / n
            }
            return fr.average() to fr.min()
        }

        fun priorWeight(quad: Array<Point>, prior: Prior, hh: Int, ww: Int): Double {
            val q = orderQuad(quad)
            val c = centroid(q)
            val size = (dist(q[3], q[0]) + dist(q[2], q[1])) / 2 / hh
            val d = hypot(c.x / ww - prior.cx, c.y / hh - prior.cy)
            val g1 = exp(-(d / 0.18).let { it * it })
            val g2 = exp(-(ln(max(size, 1e-3) / prior.h) / 0.35).let { it * it })
            return g1 * g2 + 0.05
        }

        fun hullToQuad(hull: Array<Point>): Array<Point>? {
            val pts = hull.map { Point(it.x, it.y) }.toMutableList()
            if (pts.size < 4) return null
            while (pts.size > 4) {
                val n = pts.size
                var best = Double.POSITIVE_INFINITY
                var bestI = -1
                var bestP: Point? = null
                for (i in 0 until n) {
                    val a = pts[(i - 1 + n) % n]
                    val b = pts[i]
                    val c = pts[(i + 1) % n]
                    val d = pts[(i + 2) % n]
                    val p = lineIntersection(a, b, c, d) ?: continue
                    if ((p.x - b.x) * (b.x - a.x) + (p.y - b.y) * (b.y - a.y) < 0 ||
                        (p.x - c.x) * (c.x - d.x) + (p.y - c.y) * (c.y - d.y) < 0) continue
                    val added = abs((c.x - b.x) * (p.y - b.y) - (p.x - b.x) * (c.y - b.y)) / 2
                    if (added < best) { best = added; bestI = i; bestP = p }
                }
                if (bestP == null) return null
                pts[bestI] = bestP
                pts.removeAt((bestI + 1) % n)
            }
            return pts.toTypedArray()
        }

        /** Corners from four side lines: corner i = side i-1 x side i. */
        fun intersectAll(lines: List<Pair<Point, Point>>): Array<Point>? {
            val out = ArrayList<Point>(4)
            for (i in 0 until 4) {
                val l1 = lines[(i + 3) % 4]
                val l2 = lines[i]
                out.add(lineIntersection(l1.first, l1.second, l2.first, l2.second) ?: return null)
            }
            return out.toTypedArray()
        }

        fun lineIntersection(p1: Point, p2: Point, p3: Point, p4: Point): Point? {
            val d = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
            if (abs(d) < 1e-9) return null
            val a = p1.x * p2.y - p1.y * p2.x
            val b = p3.x * p4.y - p3.y * p4.x
            return Point((a * (p3.x - p4.x) - (p1.x - p2.x) * b) / d, (a * (p3.y - p4.y) - (p1.y - p2.y) * b) / d)
        }

        fun polyArea(q: Array<Point>): Double {
            var s = 0.0
            for (i in q.indices) {
                val a = q[i]
                val b = q[(i + 1) % q.size]
                s += a.x * b.y - b.x * a.y
            }
            return abs(s) / 2
        }

        fun centroid(q: Array<Point>): Point = Point(q.sumOf { it.x } / q.size, q.sumOf { it.y } / q.size)

        fun dist(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

        fun inside(pt: Point, quad: Array<Point>): Boolean {
            val m = MatOfPoint2f(*quad)
            val r = Imgproc.pointPolygonTest(m, pt, false)
            m.release()
            return r >= 0
        }

        /** Python's round(): halves go to the even neighbour. */
        fun pyRound(x: Double): Long = Math.rint(x).roundToLong()

        fun bytes(m: Mat): ByteArray {
            val c = if (m.isContinuous) m else m.clone()
            val b = ByteArray((c.total() * c.channels()).toInt())
            c.get(0, 0, b)
            if (c !== m) c.release()
            return b
        }

        fun medianOfHist(hist: IntArray, n: Int): Double {
            fun kth(k: Int): Int {
                var acc = 0
                for (v in 0..255) { acc += hist[v]; if (acc > k) return v }
                return 255
            }
            return if (n % 2 == 1) kth(n / 2).toDouble() else (kth(n / 2 - 1) + kth(n / 2)) / 2.0
        }

        fun autoCanny(ch: Mat, loF: Double = 0.66, hiF: Double = 1.33): Mat {
            val b = bytes(ch)
            val hist = IntArray(256)
            for (v in b) hist[v.toInt() and 0xFF]++
            val med = medianOfHist(hist, b.size)
            val lo = max(5.0, loF * med).toInt()
            val hi = min(255.0, max(hiF * med, (lo + 25).toDouble())).toInt()
            val out = Mat()
            Imgproc.Canny(ch, out, lo.toDouble(), hi.toDouble())
            return out
        }
    }
}
