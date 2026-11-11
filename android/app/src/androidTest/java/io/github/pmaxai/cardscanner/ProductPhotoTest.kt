package io.github.pmaxai.cardscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.shop.ProductPhoto
import io.github.pmaxai.cardscanner.vision.Prior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/** "Create Product Image": only the card, straightened, at the card's shape; the guide frame when no edge is found. */
@RunWith(AndroidJUnit4::class)
class ProductPhotoTest {
    @Before fun openCv() { assertTrue("OpenCV failed to load", OpenCVLoader.initLocal()) }

    private fun photo(): Pair<Mat, Array<Point>> {
        val rgb = Mat(1440, 1920, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))         // a dark table
        // a tilted card, tl tr br bl, a little over 1000 px tall
        val quad = arrayOf(Point(700.0, 200.0), Point(1430.0, 260.0), Point(1360.0, 1270.0), Point(630.0, 1210.0))
        Imgproc.fillPoly(rgb, listOf(MatOfPoint(*quad)), Scalar(200.0, 30.0, 30.0))
        Imgproc.circle(rgb, Point(760.0, 280.0), 20, Scalar(0.0, 200.0, 0.0), -1)      // a mark near the card's top left
        return rgb to quad
    }

    @Test
    fun theCardIsCutOutStraight() {
        val (rgb, quad) = photo()
        val out = ProductPhoto.cardImage(rgb, quad, Prior(0.5, 0.5, 0.7))
        val h = out.rows(); val w = out.cols()
        assertTrue("height from the card's own size: $h", h in 1000..1030)
        assertEquals((h * 63.0 / 88).roundToInt(), w)
        fun px(x: Int, y: Int) = out.get(y, x)
        // nothing of the table: the card's colour up to the edges
        for ((x, y) in listOf(3 to 3, w - 4 to 3, w - 4 to h - 4, 3 to h - 4, w / 2 to h / 2))
            assertTrue("card colour at $x,$y: ${px(x, y).toList()}", px(x, y)[0] > 150 && px(x, y)[2] < 80)
        // upright: the mark stays at the top left
        val mark = px((w * 0.08).roundToInt(), (h * 0.07).roundToInt())
        assertTrue("the mark at the top left: ${mark.toList()}", mark[1] > 150)
        val (jpeg, thumb) = ProductPhoto.encode(out)
        assertTrue("a JPEG", jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        assertEquals(220, thumb.height)
    }

    @Test
    fun withoutAnEdgeTheGuideFrameIsCutOut() {
        val (rgb, _) = photo()
        val out = ProductPhoto.cardImage(rgb, null, Prior(0.5, 0.5, 0.7))
        assertEquals((1440 * 0.7).roundToInt(), out.rows())
        assertEquals((out.rows() * 63.0 / 88).roundToInt(), out.cols())
        // a small card in a big photo: at least 600 px tall
        val small = Mat(480, 640, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        assertEquals(600, ProductPhoto.cardImage(small, null, Prior(0.5, 0.5, 0.7)).rows())
    }
}
