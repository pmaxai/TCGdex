package io.github.pmaxai.cardscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.Pack
import io.github.pmaxai.cardscanner.vision.CardDetector
import io.github.pmaxai.cardscanner.vision.Readings
import io.github.pmaxai.cardscanner.vision.Recognizer
import io.github.pmaxai.cardscanner.vision.ScanTracker
import org.opencv.core.Rect
import org.opencv.core.Size
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * Checks that the app sees what the Python pipeline saw (parity.zip from the data pack):
 * the detector on synthetic photos, and full recognition on the real test photos.
 * Results are logged under the tag "ParityTest" (printed by the CI workflow).
 */
@RunWith(AndroidJUnit4::class)
class ParityTest {
    companion object {
        private const val TAG = "ParityTest"
        lateinit var pack: Pack
        lateinit var recognizer: Recognizer

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assertTrue("OpenCV failed to load", OpenCVLoader.initLocal())
            val t0 = System.currentTimeMillis()
            pack = Pack.load(ApplicationProvider.getApplicationContext<Context>())
            recognizer = Recognizer(pack)
            Log.i(TAG, "pack ${pack.version}: ${pack.n} x ${pack.dim} loaded in ${System.currentTimeMillis() - t0} ms")
        }
    }

    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private val fixtures: JSONObject by lazy {
        JSONObject(testAssets.open("parity/parity.json").bufferedReader().readText())
    }

    private fun loadAsset(path: String): Mat {
        val bytes = testAssets.open(path).use { it.readBytes() }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val o = ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val deg = when (o) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        return Recognizer.bitmapToRgb(bmp)
    }

    private fun quadOf(a: JSONArray): Array<Point> =
        Array(4) { Point(a.getJSONArray(it).getDouble(0), a.getJSONArray(it).getDouble(1)) }

    private fun iou(a: Array<Point>, b: Array<Point>): Double {
        val pa = MatOfPoint2f(*CardDetector.orderQuad(a))
        val pb = MatOfPoint2f(*CardDetector.orderQuad(b))
        val inter = Imgproc.intersectConvexConvex(pa, pb, MatOfPoint2f(), true)
        val union = CardDetector.polyArea(a) + CardDetector.polyArea(b) - inter
        return if (union > 0) inter / union else 0.0
    }

    @Test
    fun detectorMatchesPipelineOnSyntheticPhotos() {
        val items = fixtures.getJSONArray("synthetic")
        val detector = CardDetector()
        var agree = 0
        val lines = ArrayList<String>()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val img = loadAsset("parity/photos/${it.getString("file")}")
            val t0 = System.currentTimeMillis()
            val d = detector.detect(img, maxCards = 1)
            val ms = System.currentTimeMillis() - t0
            val pyDetected = it.getBoolean("detected")
            val ok = if (!pyDetected) d.isEmpty()
                     else d.isNotEmpty() && iou(d[0].quad, quadOf(it.getJSONArray("quad"))) >= 0.9
            if (ok) agree++
            val overlap = if (pyDetected && d.isNotEmpty()) "%.3f".format(iou(d[0].quad, quadOf(it.getJSONArray("quad")))) else "-"
            lines += "${it.getString("file")}: python=${pyDetected} app=${d.isNotEmpty()} overlap=$overlap ${ms}ms ${if (ok) "OK" else "DIFF"}"
            img.release()
        }
        lines.forEach { Log.i(TAG, it) }
        Log.i(TAG, "detector agrees on $agree/${items.length()} synthetic photos")
        assertTrue("detector differs from the pipeline:\n" + lines.joinToString("\n"), agree >= items.length() - 1)
    }

    @Test
    fun fastSearchEqualsExhaustiveSearch() {
        val fast = io.github.pmaxai.cardscanner.vision.Matcher(pack.index, pack.n, pack.dim, pack.entryGroup)
        val full = io.github.pmaxai.cardscanner.vision.Matcher(pack.index, pack.n, pack.dim, pack.entryGroup, coarseDim = pack.dim)
        val rnd = java.util.Random(7)
        var same = 0
        val trials = 30
        var tFast = 0L
        var tFull = 0L
        repeat(trials) {
            // a reference fingerprint plus noise, as four 'readings'
            val base = rnd.nextInt(pack.n)
            val readings = List(4) { r ->
                FloatArray(pack.dim) { d -> pack.index[base * pack.dim + d] + (if (r == 0) 0.6f else 2f) * (rnd.nextGaussian().toFloat() / 22f) }
                    .let { v -> val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(v.size) { v[it] / norm } }
            }
            var t0 = System.nanoTime()
            val a = fast.search(readings)
            tFast += System.nanoTime() - t0
            t0 = System.nanoTime()
            val b = full.search(readings)
            tFull += System.nanoTime() - t0
            if (a.map { it.group }.take(3) == b.map { it.group }.take(3)) same++
        }
        Log.i(TAG, "fast search: same top-3 as exhaustive in $same/$trials, %d ms vs %d ms per search"
            .format(tFast / trials / 1_000_000, tFull / trials / 1_000_000))
        assertTrue("fast search differs from exhaustive search in ${trials - same}/$trials cases", same >= trials - 1)
    }

    @Test
    fun realPhotosLanguageAndNumber() {
        // what the label of each test photo says; photos added later are only logged
        val expected = mapOf("Magneton 059-191.jpg" to ("de" to "59"), "Plusle 69-101.jpg" to ("en" to "69"),
            // Japanese Meganium of M1S (2025): no picture in the data; script, number and printed name find it
            "me01-010__Meganium_japanese_shiny_border_screenshot.jpg" to ("ja" to "5"))
        val expectedCard = mapOf("me01-010__Meganium_japanese_shiny_border_screenshot.jpg" to "M1S-005")
        val cat = Catalog(pack.db)
        val items = fixtures.getJSONArray("real")
        val resolver = io.github.pmaxai.cardscanner.vision.VersionResolver()
        val tw = System.currentTimeMillis()
        resolver.warmUp()
        Log.i(TAG, "text recognition warm-up: ${System.currentTimeMillis() - tw} ms")
        val lines = ArrayList<String>()
        var wrong = 0
        for (i in 0 until items.length()) {
            val file = items.getJSONObject(i).getString("file")
            val img = loadAsset("real/$file")
            val r = recognizer.recognise(img, prior = null)
            val hit = r.best ?: continue
            val t0 = System.currentTimeMillis()
            val t = recognizer.readText(img, r, hit, resolver)
            img.release()
            if (t == null) continue
            val ms = System.currentTimeMillis() - t0
            val num = io.github.pmaxai.cardscanner.vision.VersionResolver.normNumber(t.number)
            val exp = expected[file]
            // the language decides what is shown; the number only narrows down reprints (logged)
            val ok = exp == null || t.lang == exp.first
            if (!ok) wrong++
            lines += "$file [${Recognizer.READINGS[hit.reading]}]: number=${t.number}/${t.total} lang=${t.lang} (+%.1f) %dms %s"
                .format(t.langMargin, ms, if (exp == null) "" else (if (ok) "language OK" else "language EXPECTED ${exp.first}") +
                    (if (num == exp.second) ", number OK" else ", number expected ${exp.second}"))
            lines += "   scores ${t.langScores.entries.sortedByDescending { it.value }.joinToString { "${it.key}=%.1f".format(it.value) }}"
            lines += "   name bar: ${t.nameText.take(60)} | text: ${t.text.replace("\n", " / ").take(160)}"
            lines += "   script: ${t.script}"
            // an Asian print: the exact card by its number and printed name (it has no picture)
            if (t.lang in setOf("ja", "ko", "zh-tw", "zh-cn")) {
                val langs = if (t.lang!!.startsWith("zh")) listOf("zh-tw", "zh-cn") else listOf(t.lang!!)
                val cands = cat.byNumber(langs, num, t.total?.let { io.github.pmaxai.cardscanner.vision.VersionResolver.normNumber(it) })
                val known = cat.englishOf(hit.group)?.name?.let { cat.asianNames(it, langs) }.orEmpty()
                val picked = io.github.pmaxai.cardscanner.vision.VersionResolver.pickPrint(cands, t.nameText, known, t.total != null)
                lines += "   print: ${cands.map { "${it.id} ${it.name}" }} -> ${picked?.id}"
                expectedCard[file]?.let { want -> if (picked?.id != want) { wrong++; lines += "   PRINT EXPECTED $want" } }
            } else expectedCard[file]?.let { want -> wrong++; lines += "   PRINT EXPECTED $want (language not Asian)" }
        }
        resolver.close()
        lines.forEach { Log.i(TAG, it) }
        assertTrue("language/number differ:\n" + lines.joinToString("\n"), wrong == 0)
    }

    @Test
    fun takingACardByHandFindsAtLeastTheAutomaticMatch() {
        val items = fixtures.getJSONArray("real")
        var n = 0
        for (i in 0 until items.length()) {
            val file = items.getJSONObject(i).getString("file")
            val img = loadAsset("real/$file")
            val prior = io.github.pmaxai.cardscanner.vision.Readings.guidePrior(img.cols(), img.rows())
            val auto = recognizer.recognise(img, prior)
            val manual = recognizer.recognise(img, prior, manual = true)
            img.release()
            val a = auto.best?.score ?: 0f
            val m = manual.best?.score ?: -1f
            Log.i(TAG, "by hand $file: ${manual.best?.group} %.3f (automatic ${auto.best?.group} %.3f)".format(m, a))
            assertTrue("$file: taking it by hand found a worse match (%.3f < %.3f)".format(m, a), m >= a - 1e-4f)
            // the list to pick from: 10 different cards, best first, the shown one on top
            val c = manual.candidates
            assertTrue("$file: list size ${c.size}", c.size in 6..10)
            assertEquals("$file: distinct cards", c.size, c.map { it.group }.toSet().size)
            assertEquals("$file: shown card first", manual.best?.group, c.first().group)
            assertTrue("$file: best first", c.zipWithNext().all { (x, y) -> x.score >= y.score })
            n++
        }
        assertTrue(n > 0)
    }

    /**
     * After a double tap the card stays while it is in the frame: slightly shifted and
     * blurred frames of the same photo must not replace it (the bug: a misread outline gave
     * another, sure card a second later).
     */
    @Test
    fun aCardTakenByHandStaysWhileItIsInTheFrame() {
        val sure = pack.metaFloat("margin_99", 0.085f)
        val likely = pack.metaFloat("margin_90", 0.038f)
        val items = fixtures.getJSONArray("real")
        var autoOther = 0
        var frames = 0
        var n = 0
        for (i in 0 until items.length()) {
            val file = items.getJSONObject(i).getString("file")
            val img = loadAsset("real/$file")
            val kept = recognizer.recognise(img, Readings.guidePrior(img.cols(), img.rows()), manual = true).best?.group
            if (kept == null) { img.release(); continue }
            val tracker = ScanTracker(sure, likely).apply { delayMs = 0; lock(kept) }
            var t = 0L
            for (k in 0 until 8) {
                val dx = (5 * k) % 21
                val dy = (7 * k) % 21
                val sub = img.submat(Rect(dx, dy, img.cols() - 21, img.rows() - 21))
                val frame = sub.clone()
                sub.release()
                if (k % 2 == 1) Imgproc.GaussianBlur(frame, frame, Size(0.0, 0.0), 1.2)
                val prior = Readings.guidePrior(frame.cols(), frame.rows())
                val r = recognizer.recognise(frame, prior, manual = true)
                // what the automatic read alone (before the fix) said about this frame
                val auto = recognizer.recognise(frame, prior)
                frame.release()
                if (auto.best != null && auto.best!!.group != kept && auto.margin >= sure) autoOther++
                frames++
                val vote = ScanTracker.voteWhileKept(r.best, r.candidates, kept, sure)
                tracker.update(vote?.group, if (vote == null) 0f else r.margin, now = t)
                t += 150
            }
            img.release()
            Log.i(TAG, "kept by hand $file: $kept -> still shown: ${tracker.shown}")
            assertEquals("$file: the card taken by hand was replaced", kept, tracker.shown)
            n++
        }
        Log.i(TAG, "frames where the automatic read alone was sure of another card: $autoOther of $frames")
        assertTrue(n > 0)
    }

    /** The card search finds any card, also those without a picture (which the camera can't recognise). */
    @Test
    fun searchFindsCardsByNameNumberAndSet() {
        val cat = Catalog(pack.db)
        fun ids(q: String) = cat.search(q).map { "${it.id}/${it.lang}" }
        val whim = ids("whimsicott 019")
        Log.i(TAG, "search 'whimsicott 019': ${whim.take(5)}")
        assertEquals("whimsicott 019", "mep-019/en", whim.first())
        // (TCGdex's German MEP promos change; Twilight Masquerade's are stable)
        assertEquals("German name finds the German card", "sv06-034/de", ids("flampion 034").firstOrNull())
        assertTrue("number with total", ids("darumaka 034/167").first() == "sv06-034/en")
        assertTrue("set code + number", ids("sv06 34").first().startsWith("sv06-034/"))
        assertTrue("several Whimsicotts, newest first", cat.search("whimsicott").size >= 10)
        assertTrue("one letter is not enough to match everything", cat.search("x zzzzqq").isEmpty())
        assertEquals("the artwork group of a card without a picture is the card itself", "mep-019", cat.groupOf("mep-019"))
    }

    /** The data pack carries price history once the pipeline has built it: a well-known card has a line. */
    @Test
    fun packHasPriceHistory() {
        val cat = Catalog(pack.db)
        val dates = pack.db.rawQuery("SELECT value FROM meta WHERE key = 'history_dates'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (dates == null) { Log.i(TAG, "this data pack has no price history yet"); return }
        val withHistory = listOf("sv06-034", "sv03.5-006", "base1-4", "swsh7-215", "sv08-059").mapNotNull { id ->
            cat.history(id)?.let { h -> id to h.series.mapValues { (_, a) -> a.count { !it.isNaN() } } }
        }
        Log.i(TAG, "price history: ${dates.split(",").size} days in the calendar, points: $withHistory")
        assertTrue("no card has a price history", withHistory.isNotEmpty())
    }

    @Test
    fun packPriceCheck() {
        val has = pack.db.rawQuery("SELECT 1 FROM sqlite_master WHERE name = 'price_check'", null).use { it.moveToFirst() }
        if (!has) { Log.i(TAG, "this data pack has no price check yet"); return }
        val counts = pack.db.rawQuery("SELECT status, COUNT(*) FROM price_check GROUP BY status", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
        val cat = Catalog(pack.db)
        // the two cards reported: commons that carried their secret rare's Cardmarket price
        val seen = listOf("sm12-54", "sm12-68").map { id ->
            val card = cat.cardExact(id, "de") ?: cat.cardExact(id, "en")!!
            id to "${card.name}: ${card.priceCheck?.status}, ${card.printings.map { "${it.kind} ${it.eur} / ${it.usdEur}" }}"
        }
        Log.i(TAG, "price check: $counts; $seen")
        for (id in listOf("sm12-54", "sm12-68")) {
            val card = cat.cardExact(id, "en")!!
            assertTrue("$id still has a Cardmarket price over 5 EUR", card.printings.all { (it.eur ?: 0.0) < 5.0 })
        }
    }

    @Test
    fun excludedCardsAreNeverMatched() {
        val m = io.github.pmaxai.cardscanner.vision.Matcher(pack.index, pack.n, pack.dim, pack.entryGroup)
        val rnd = java.util.Random(11)
        repeat(10) {
            val base = rnd.nextInt(pack.n)
            val q = FloatArray(pack.dim) { pack.index[base * pack.dim + it] }
            val g = pack.entryGroup[base]
            assertTrue(m.search(listOf(q)).first().group == g)
            m.excluded = BooleanArray(pack.n) { pack.entryGroup[it] == g }
            assertTrue("excluded card $g was still matched", m.search(listOf(q)).none { it.group == g })
            m.excluded = null
        }
        val cat = Catalog(pack.db)
        if (cat.hasCategory) {
            val all = cat.energyIds(commonOnly = false)
            val common = cat.energyIds(commonOnly = true)
            Log.i(TAG, "energy cards: ${all.size} in all, ${common.size} common ones")
            assertTrue("energy filter: all=${all.size} common=${common.size}",
                all.isNotEmpty() && common.isNotEmpty() && common.size < all.size && all.containsAll(common))
        } else Log.i(TAG, "energy cards: this pack has no categories yet")
    }

    @Test
    fun foilCheckMatchesPipeline() {
        val texture = pack.texture
        if (texture == null) { Log.i(TAG, "foil check: this pack has no texture grids, skipped"); return }
        val items = fixtures.getJSONArray("real")
        val lines = ArrayList<String>()
        var bad = 0
        var n = 0
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val f = it.optJSONObject("foil") ?: continue
            val img = loadAsset("real/${it.getString("file")}")
            val ref = io.github.pmaxai.cardscanner.vision.FoilCheck.reference(texture, f.getInt("entry"))
            val g = io.github.pmaxai.cardscanner.vision.FoilCheck.photoGrids(img, quadOf(f.getJSONArray("card_quad")), ref,
                recognizer.geometry.rectW, recognizer.geometry.rectH)
            img.release()
            val want = f.getJSONArray("grids")
            val close = (0 until want.length()).count { k -> kotlin.math.abs(g[k] - want.getInt(k)) <= 2 }
            val score = io.github.pmaxai.cardscanner.vision.FoilCheck.scores(g, ref).score
            val py = if (f.isNull("score")) Double.NaN else f.getDouble("score")
            val sameScore = (score.isNaN() && py.isNaN()) || kotlin.math.abs(score - py) < 0.05
            val ok = close >= want.length() * 0.9 && sameScore
            n++
            if (!ok) bad++
            lines += "${it.getString("file")}: cells within 2: $close/${want.length()}, score app %.3f python %.3f %s"
                .format(score, py, if (ok) "OK" else "DIFF")
        }
        lines.forEach { Log.i(TAG, it) }
        Log.i(TAG, "foil check agrees on ${n - bad}/$n real photos")
        assertTrue("foil check differs from the pipeline:\n" + lines.joinToString("\n"), bad == 0)
    }

    @Test
    fun realPhotosRecognisedLikePipeline() {
        val items = fixtures.getJSONArray("real")
        val catalog = Catalog(pack.db)
        val lines = ArrayList<String>()
        var same = 0
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            if (!it.getBoolean("detected")) continue
            val img = loadAsset("real/${it.getString("file")}")
            val r = recognizer.recognise(img, prior = null)
            img.release()
            val py = it.getJSONArray("top").getJSONObject(0)
            val app = r.best
            val match = app != null && app.group == py.getString("id")
            if (match) same++
            val name = app?.let { h -> catalog.card(h.group, "en")?.name } ?: "-"
            lines += "${it.getString("file")}: python=${py.getString("id")} %.3f app=${app?.group} %s %.3f [%s] %s %s".format(
                py.getDouble("score"), name, app?.score ?: 0f, app?.let { h -> Recognizer.READINGS[h.reading] } ?: "-",
                r.timings.entries.joinToString(" ") { e -> "${e.key}=${e.value}ms" }, if (match) "OK" else "DIFF")
        }
        lines.forEach { Log.i(TAG, it) }
        val n = (0 until items.length()).count { items.getJSONObject(it).getBoolean("detected") }
        Log.i(TAG, "real photos: app agrees with the pipeline on $same/$n")
        assertTrue("recognition differs from the pipeline:\n" + lines.joinToString("\n"), same == n)
    }
}
