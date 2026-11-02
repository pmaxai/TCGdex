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
import io.github.pmaxai.cardscanner.vision.Recognizer
import org.json.JSONArray
import org.json.JSONObject
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
            val match = app != null && app.cardId == py.getString("id")
            if (match) same++
            val name = app?.let { h -> catalog.card(h.cardId, "en")?.name } ?: "-"
            lines += "${it.getString("file")}: python=${py.getString("id")} %.3f app=${app?.cardId} %s %.3f [%s] %s %s".format(
                py.getDouble("score"), name, app?.score ?: 0f, app?.let { h -> Recognizer.READINGS[h.reading] } ?: "-",
                r.timings.entries.joinToString(" ") { e -> "${e.key}=${e.value}ms" }, if (match) "OK" else "DIFF")
        }
        lines.forEach { Log.i(TAG, it) }
        val n = (0 until items.length()).count { items.getJSONObject(it).getBoolean("detected") }
        Log.i(TAG, "real photos: app agrees with the pipeline on $same/$n")
        assertTrue("recognition differs from the pipeline:\n" + lines.joinToString("\n"), same == n)
    }
}
