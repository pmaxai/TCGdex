package io.github.pmaxai.cardscanner.vision

import android.graphics.Bitmap
import io.github.pmaxai.cardscanner.data.Pack
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
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

    /** The same frame, as if no card was in it (it covered too little of the guide frame). */
    fun withoutCard() = Recognition(quad, false, emptyList(), 0f, preview, timings)
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

    /** matches with at least this margin are sure: no second guess at the outline needed */
    private val sureMargin = pack.metaFloat("margin_99", 0.085f)

    /**
     * @param rgb photo as RGB CV_8UC3
     * @param prior where the guide frame expects the card (live camera); null for photos
     *
     * The detector's outline is read first. If that gives no sure match, its second guess
     * (another outline, e.g. from straight lines when fingers or a shiny border break the
     * card's contour) is read too, and the better match wins (export.app_recognise).
     */
    fun recognise(rgb: Mat, prior: Prior? = null, withPreview: Boolean = false): Recognition {
        val t = LinkedHashMap<String, Long>()
        var t0 = System.nanoTime()
        fun lap(name: String) { val now = System.nanoTime(); t[name] = (t[name] ?: 0L) + (now - t0) / 1_000_000; t0 = now }

        val dets = detector.hypotheses(rgb, k = HYPOTHESES, prior = prior)
        lap("detect")
        val quads = dets.map { it.quad }.ifEmpty {
            listOfNotNull(prior?.let { Readings.guideQuad(rgb.cols(), rgb.rows(), it) })
        }
        if (quads.isEmpty()) return Recognition(null, false, emptyList(), 0f, null, t)

        var best: Read? = null
        for (q in quads) {
            val r = read(rgb, q, withPreview, ::lap)
            if (best == null || (r.hits.firstOrNull()?.score ?: -1f) > (best.hits.firstOrNull()?.score ?: -1f)) {
                best?.preview?.recycle()
                best = r
            } else r.preview?.recycle()
            if (r.margin >= sureMargin) break
        }
        val b = best!!
        return Recognition(b.quad, dets.isNotEmpty(), b.hits, b.margin, b.preview, t)
    }

    private class Read(val quad: Array<Point>, val hits: List<Hit>, val margin: Float, val preview: Bitmap?)

    private fun read(rgb: Mat, quad: Array<Point>, withPreview: Boolean, lap: (String) -> Unit): Read {
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
        return Read(quad, hits, margin, preview)
    }

    /**
     * Read the card's printed text (number, language). When only the artwork box was
     * found, its orientation is a guess, so the other way round is tried too if the
     * first attempt finds no number.
     */
    fun readText(rgb: Mat, r: Recognition, hit: Hit, resolver: VersionResolver, tryJapanese: Boolean): CardText? {
        fun attempt(h: Hit): CardText? {
            val quad = cardQuad(r, h) ?: return null
            val card = Readings.cardForReading(rgb, quad, upsideDown = false)
            val bmp = Bitmap.createBitmap(card.cols(), card.rows(), Bitmap.Config.ARGB_8888)
            val rgba = Mat()
            Imgproc.cvtColor(card, rgba, Imgproc.COLOR_RGB2RGBA)
            Utils.matToBitmap(rgba, bmp)
            rgba.release(); card.release()
            return resolver.read(bmp, tryJapanese)
        }
        val first = attempt(hit)
        if (first?.number != null) return first
        val other = when (hit.reading) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 2 }
        val second = attempt(hit.copy(reading = other)) ?: return first
        fun quality(t: CardText?) = (if (t?.number != null) 3.0 else 0.0) + (t?.langMargin ?: 0.0)
        return if (quality(second) > quality(first)) second else first
    }

    companion object {
        /** outlines tried per frame at most (the detector's answer + second guesses) */
        const val HYPOTHESES = 2
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
