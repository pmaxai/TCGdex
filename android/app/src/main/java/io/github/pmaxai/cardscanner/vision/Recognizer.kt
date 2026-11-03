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
    /** taken by hand: the most likely cards over every outline read, best first (else = hits) */
    val candidates: List<Hit> = hits,
) {
    val best: Hit? get() = hits.firstOrNull()

    /** The same frame, as if no card was in it (it covered too little of the guide frame). */
    fun withoutCard() = Recognition(quad, false, emptyList(), 0f, preview, timings, emptyList())
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
    fun recognise(rgb: Mat, prior: Prior? = null, withPreview: Boolean = false, manual: Boolean = false): Recognition {
        val t = LinkedHashMap<String, Long>()
        var t0 = System.nanoTime()
        fun lap(name: String) { val now = System.nanoTime(); t[name] = (t[name] ?: 0L) + (now - t0) / 1_000_000; t0 = now }

        val dets = detector.hypotheses(rgb, k = HYPOTHESES, prior = prior)
        lap("detect")
        // taken by hand: the guide frame itself is one more guess (the user put the card there)
        val guide = prior?.let { Readings.guideQuad(rgb.cols(), rgb.rows(), it) }
        val quads = if (manual) dets.map { it.quad } + listOfNotNull(guide)
                    else dets.map { it.quad }.ifEmpty { listOfNotNull(guide) }
        if (quads.isEmpty()) return Recognition(null, false, emptyList(), 0f, null, t)

        var best: Read? = null
        var bestIsOutline = false
        // by hand: a longer list, merged over every outline (each card with its best score)
        val k = if (manual) MANUAL_CANDIDATES else 5
        val all = HashMap<String, Hit>()
        for ((i, q) in quads.withIndex()) {
            val r = read(rgb, q, withPreview, ::lap, k, pool = if (manual) 160 else 40)
            if (manual) r.hits.forEach { h -> if ((all[h.group]?.score ?: Float.NEGATIVE_INFINITY) < h.score) all[h.group] = h }
            if (best == null || (r.hits.firstOrNull()?.score ?: -1f) > (best.hits.firstOrNull()?.score ?: -1f)) {
                best?.preview?.recycle()
                best = r
                bestIsOutline = i < dets.size
            } else r.preview?.recycle()
            if (!manual && r.margin >= sureMargin) break
        }
        val b = best!!
        val candidates = if (manual) all.values.sortedByDescending { it.score }.take(MANUAL_CANDIDATES) else b.hits
        return Recognition(b.quad, bestIsOutline, b.hits, b.margin, b.preview, t, candidates)
    }

    private class Read(val quad: Array<Point>, val hits: List<Hit>, val margin: Float, val preview: Bitmap?)

    private fun read(rgb: Mat, quad: Array<Point>, withPreview: Boolean, lap: (String) -> Unit, k: Int = 5, pool: Int = 40): Read {
        val g = geometry
        val card = Readings.toCard(rgb, quad, g)
        val card180 = Readings.rot180(card)
        val (artA, artB) = Readings.asArtwork(rgb, quad, g)
        val inputs = listOf(card, card180, artA, artB).map { Readings.artInput(it, g) }
        lap("readings")
        val fps = fingerprinter.embed(inputs)
        lap("model")
        val hits = matcher.search(fps, k = k, pool = pool)
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

    /** Entries the search must skip (null: none). */
    fun setExcluded(mask: BooleanArray?) { matcher.excluded = mask }

    /**
     * How much of the guide frame (0..1) the card covers, using the card outline of the
     * best reading. 0 when no card outline was found (the guide frame itself was read).
     */
    fun guideFill(r: Recognition, hit: Hit, w: Int, h: Int, prior: Prior): Double {
        if (!r.detected) return 0.0
        val card = cardQuad(r, hit, margin = 0.0) ?: return 0.0
        val guide = Readings.guideQuad(w, h, prior)
        val a = MatOfPoint2f(*CardDetector.orderQuad(card))
        val b = MatOfPoint2f(*guide)
        val inter = MatOfPoint2f()
        val area = runCatching { Imgproc.intersectConvexConvex(a, b, inter, true).toDouble() }.getOrDefault(0.0)
        listOf(a, b, inter).forEach { it.release() }
        val guideArea = (guide[1].x - guide[0].x) * (guide[3].y - guide[0].y)
        return if (guideArea > 0) (area / guideArea).coerceIn(0.0, 1.0) else 0.0
    }

    override fun close() {
        fingerprinter.close()
    }

    /** The upright card's outline for this reading. margin: extra room around a card rebuilt from its artwork box. */
    fun cardQuad(r: Recognition, hit: Hit, margin: Double = 0.06): Array<Point>? {
        val q = r.quad ?: return null
        return when (hit.reading) {
            0 -> q
            1 -> arrayOf(q[2], q[3], q[0], q[1])
            2 -> Readings.cardQuadFromArtwork(q, false, geometry, margin)
            else -> Readings.cardQuadFromArtwork(q, true, geometry, margin)
        }
    }

    /**
     * The card as the camera saw it, straightened and upright: the picture shown when the
     * card has no image in the database or the phone is offline.
     */
    fun cardBitmap(rgb: Mat, r: Recognition, hit: Hit, w: Int = 360, h: Int = 503): Bitmap? {
        val quad = cardQuad(r, hit, margin = 0.03) ?: return null
        val card = Readings.rectify(rgb, quad, w, h)
        return try {
            val rgba = Mat()
            Imgproc.cvtColor(card, rgba, Imgproc.COLOR_RGB2RGBA)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it); rgba.release() }
        } finally {
            card.release()
        }
    }

    /**
     * Single-frame reverse holo score of the card in this frame, against the scan of pack
     * entry [refEntry] (the version shown). Null: no texture data in the pack, or no score.
     */
    fun foilScore(rgb: Mat, r: Recognition, hit: Hit, texture: ByteArray?, refEntry: Int): FoilCheck.Result? {
        if (texture == null) return null
        val quad = cardQuad(r, hit, margin = 0.0) ?: return null
        val ref = FoilCheck.reference(texture, refEntry)
        val grids = FoilCheck.photoGrids(rgb, quad, ref, geometry.rectW, geometry.rectH)
        return FoilCheck.scores(grids, ref).takeIf { !it.score.isNaN() }
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
        /** cards listed after a double tap */
        const val MANUAL_CANDIDATES = 10
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
