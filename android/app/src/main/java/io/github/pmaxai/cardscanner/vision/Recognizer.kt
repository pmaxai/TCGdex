package io.github.pmaxai.cardscanner.vision

import android.graphics.Bitmap
import io.github.pmaxai.cardscanner.data.Pack
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/** What the scanner saw in one photo/frame. */
class Recognition(
    /** card outline used (detected, or the guide frame if nothing was found) */
    val quad: Array<Point>?,
    val detected: Boolean,
    val hits: List<Hit>,
    /** score gap between the best and second-best card: the confidence measure */
    val margin: Float,
    /** straightened card as the matcher saw it (for the debug view) */
    val preview: Bitmap?,
    val timings: Map<String, Long>,
) {
    val best: Hit? get() = hits.firstOrNull()
}

class Recognizer(pack: Pack) : AutoCloseable {
    val geometry = Geometry(
        rectW = pack.metaInt("rect_w"), rectH = pack.metaInt("rect_h"),
        cardW = pack.metaInt("card_w"), cardH = pack.metaInt("card_h"),
        artX = pack.metaInt("art_x"), artY = pack.metaInt("art_y"),
        artW = pack.metaInt("art_w"), artH = pack.metaInt("art_h"),
        artBox = (pack.meta["art_box"] ?: "0.07,0.09,0.93,0.5").split(",").map { it.trim().toDouble() }.toDoubleArray(),
        input = pack.metaInt("input"),
    )
    private val detector = CardDetector()
    private val fingerprinter = Fingerprinter(pack.modelPath, geometry.input)
    private val matcher = Matcher(pack.index, pack.n, pack.dim, pack.entryGroup)

    /**
     * @param rgb photo as RGB CV_8UC3
     * @param prior where the guide frame expects the card (live camera); null for photos
     */
    fun recognise(rgb: Mat, prior: Prior? = null, withPreview: Boolean = false): Recognition {
        val t = LinkedHashMap<String, Long>()
        var t0 = System.nanoTime()
        fun lap(name: String) { val now = System.nanoTime(); t[name] = (now - t0) / 1_000_000; t0 = now }

        val dets = detector.detect(rgb, maxCards = 1, prior = prior)
        lap("detect")
        val quad = dets.firstOrNull()?.quad ?: prior?.let { Readings.guideQuad(rgb.cols(), rgb.rows(), it) }
            ?: return Recognition(null, false, emptyList(), 0f, null, t)

        val g = geometry
        val card = Readings.toCard(rgb, quad, g)
        val card180 = Readings.rot180(card)
        val (artA, artB) = Readings.asArtwork(rgb, quad, g)
        val inputs = listOf(card, card180, artA, artB).map { Readings.artInput(it, g) }
        lap("readings")
        val fps = fingerprinter.embed(inputs)
        lap("model")
        val hits = matcher.search(fps)
        lap("search")

        val preview = if (withPreview) {
            Bitmap.createBitmap(card.cols(), card.rows(), Bitmap.Config.ARGB_8888).also {
                val rgba = Mat()
                Imgproc.cvtColor(card, rgba, Imgproc.COLOR_RGB2RGBA)
                Utils.matToBitmap(rgba, it)
                rgba.release()
            }
        } else null
        listOf(card, card180, artA, artB).forEach { it.release() }
        inputs.forEach { it.release() }

        val margin = if (hits.size > 1) hits[0].score - hits[1].score else hits.firstOrNull()?.score ?: 0f
        return Recognition(quad, dets.isNotEmpty(), hits, margin, preview, t)
    }

    override fun close() {
        fingerprinter.close()
    }

    /** The card's corners in its own order (rectify -> upright card), whichever reading won. */
    fun cardQuad(r: Recognition, hit: Hit): Array<Point>? {
        val q = r.quad ?: return null
        return when (hit.reading) {
            0 -> q
            1 -> arrayOf(q[2], q[3], q[0], q[1])
            2 -> Readings.cardQuadFromArtwork(q, false, geometry)
            else -> Readings.cardQuadFromArtwork(q, true, geometry)
        }
    }

    companion object {
        val READINGS = listOf("card", "card upside down", "artwork", "artwork upside down")

        /** Android bitmap -> RGB Mat. */
        fun bitmapToRgb(bmp: Bitmap): Mat {
            val rgba = Mat()
            val b = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            Utils.bitmapToMat(b, rgba)
            val rgb = Mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            rgba.release()
            return rgb
        }
    }
}
