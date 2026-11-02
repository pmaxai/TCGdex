package io.github.pmaxai.cardscanner.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reverse holo check from a single frame: a reverse holo has foil everywhere except the
 * artwork, which gives the blank parts of the text area fine detail the card's scan
 * doesn't have. Blank cells are compared with the printed text next to them (same light,
 * blur and glare), minus the same ratio in the scan: about 0 for a normal card.
 *
 * Port of pipeline/tcgscan/foil.py; keep both identical.
 */
object FoilCheck {
    private const val W = 126
    private const val H = 176
    private val ART_BOX = doubleArrayOf(0.10, 0.12, 0.90, 0.47)
    private val BODY_BOX = doubleArrayOf(0.08, 0.56, 0.92, 0.86)
    const val N_ART = 40
    const val N_CELLS = 72
    private const val SCALE = 400.0
    private val GROW = doubleArrayOf(1.0, 1.035, 1.07)

    /** score is NaN when the card's text area has no clear blank vs text cells (full arts) */
    data class Result(val score: Double, val blank: Double, val text: Double)

    /** 72 values 0..255: 40 artwork cells, then 32 text-area cells (row by row). */
    fun textureGrids(cardRgb: Mat): IntArray {
        val small = Mat()
        Imgproc.resize(cardRgb, small, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val g8 = Mat()
        Imgproc.cvtColor(small, g8, Imgproc.COLOR_RGB2GRAY)
        val g = Mat()
        g8.convertTo(g, CvType.CV_32F)
        val b2 = Mat()
        Imgproc.GaussianBlur(g, b2, Size(0.0, 0.0), 2.0)
        val hp = Mat()
        Core.absdiff(g, b2, hp)
        val base = Mat()
        Imgproc.GaussianBlur(g, base, Size(0.0, 0.0), 8.0)
        Core.add(base, Scalar(16.0), base)
        val t = Mat()
        Core.divide(hp, base, t)
        val out = IntArray(N_CELLS)
        cells(t, ART_BOX, 8, 5, out, 0)
        cells(t, BODY_BOX, 8, 4, out, N_ART)
        listOf(small, g8, g, b2, hp, base, t).forEach { it.release() }
        return out
    }

    private fun cells(t: Mat, box: DoubleArray, cols: Int, rows: Int, out: IntArray, offset: Int) {
        val x0 = (box[0] * W).roundToInt()
        val y0 = (box[1] * H).roundToInt()
        val x1 = (box[2] * W).roundToInt()
        val y1 = (box[3] * H).roundToInt()
        val sub = t.submat(y0, y1, x0, x1)
        val grid = Mat()
        Imgproc.resize(sub, grid, Size(cols.toDouble(), rows.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val v = FloatArray(cols * rows)
        grid.get(0, 0, v)
        for (i in v.indices) out[offset + i] = (v[i] * SCALE).roundToInt().coerceIn(0, 255)
        sub.release(); grid.release()
    }

    /** The reference cells of pack entry [entry]. */
    fun reference(texture: ByteArray, entry: Int): IntArray =
        IntArray(N_CELLS) { texture[entry * N_CELLS + it].toInt() and 0xFF }

    fun scores(photo: IntArray, ref: IntArray): Result {
        val nb = N_CELLS - N_ART
        val rb = DoubleArray(nb) { ref[N_ART + it].toDouble() }
        val pb = DoubleArray(nb) { photo[N_ART + it].toDouble() }
        val lo = percentile(rb, 40.0)
        val hi = percentile(rb, 70.0)
        val flat = (0 until nb).filter { rb[it] <= lo }
        val text = (0 until nb).filter { rb[it] >= hi }
        if (flat.size < 6 || text.size < 6) return Result(Double.NaN, 0.0, 0.0)
        val rFlat = flat.sumOf { rb[it] } / flat.size
        val rText = text.sumOf { rb[it] } / text.size
        val pFlat = median(DoubleArray(flat.size) { pb[flat[it]] })
        val pText = median(DoubleArray(text.size) { pb[text[it]] })
        if (rText < 2 * rFlat + 6) return Result(Double.NaN, pFlat, pText)
        return Result(pFlat / maxOf(pText, 6.0) - rFlat / rText, pFlat, pText)
    }

    /** numpy's default (linear) percentile */
    private fun percentile(v: DoubleArray, q: Double): Double {
        val s = v.sorted()
        val pos = q / 100.0 * (s.size - 1)
        val i = pos.toInt()
        val j = minOf(i + 1, s.size - 1)
        return s[i] + (s[j] - s[i]) * (pos - i)
    }

    private fun artCorrelation(photo: IntArray, ref: IntArray): Double {
        val ma = (0 until N_ART).sumOf { photo[it].toDouble() } / N_ART
        val mb = (0 until N_ART).sumOf { ref[it].toDouble() } / N_ART
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until N_ART) {
            val x = photo[i] - ma
            val y = ref[i] - mb
            ab += x * y; aa += x * x; bb += y * y
        }
        val d = sqrt(aa * bb)
        return if (d > 0) ab / d else 0.0
    }

    /**
     * Cells of the card in the frame. The detected outline is sometimes the inner panel,
     * so it is also tried slightly grown; the one whose artwork lines up best with the
     * reference wins.
     */
    fun photoGrids(rgb: Mat, cardQuad: Array<Point>, ref: IntArray, rectW: Int, rectH: Int): IntArray {
        val cx = cardQuad.sumOf { it.x } / 4
        val cy = cardQuad.sumOf { it.y } / 4
        var best: IntArray? = null
        var bestC = -2.0
        for (k in GROW) {
            val q = Array(4) { Point(cx + (cardQuad[it].x - cx) * k, cy + (cardQuad[it].y - cy) * k) }
            val card = Readings.rectify(rgb, q, rectW, rectH)
            val g = textureGrids(card)
            card.release()
            val c = artCorrelation(g, ref)
            if (c > bestC) { best = g; bestC = c }
        }
        return best!!
    }

    /** numpy's median: the mean of the two middle values for an even count */
    private fun median(v: DoubleArray): Double {
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
