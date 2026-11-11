package io.github.pmaxai.cardscanner.shop

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import io.github.pmaxai.cardscanner.vision.CardDetector
import io.github.pmaxai.cardscanner.vision.Prior
import io.github.pmaxai.cardscanner.vision.Readings
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Product pictures taken with the scanner (Webshop mode), for cards that have no picture in
 * their language: a full-resolution still of the camera, cut to the card's outer edge and
 * straightened, at the card's shape (63:88).
 */
object ProductPhoto {
    /** the still camera bound next to preview and analysis (null: none; then a frame of the scanner is taken) */
    @Volatile var capture: ImageCapture? = null

    /** A still use case for the camera: full resolution, 4:3 like the scanner's frames. */
    fun newCapture(): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setJpegQuality(95)
        .setResolutionSelector(ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build())
        .build()

    private val executor = Executors.newSingleThreadExecutor()

    /** A still, upright, at most [maxSide] pixels on its long side; null without a still camera. */
    suspend fun take(maxSide: Int = 3200): Bitmap? {
        val c = capture ?: return null
        val image = suspendCancellableCoroutine<ImageProxy> { cont ->
            c.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image) }
                override fun onError(e: ImageCaptureException) { cont.resumeWithException(e) }
            })
        }
        return image.use { img ->
            val raw = img.toBitmap()
            val rot = img.imageInfo.rotationDegrees
            val k = minOf(1f, maxSide.toFloat() / max(raw.width, raw.height))
            val m = Matrix().apply { if (k < 1f) postScale(k, k); if (rot != 0) postRotate(rot.toFloat()) }
            if (k == 1f && rot == 0) raw
            else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
        }
    }

    /**
     * The product picture from [rgb] (what the camera box shows): the card inside [quad] (its
     * outer corners tl, tr, br, bl of the upright card) straightened; without an outline, the
     * guide frame ([prior]) cut out. Its height is the card's height in the photo, 600–1600 px.
     */
    fun cardImage(rgb: Mat, quad: Array<Point>?, prior: Prior): Mat {
        if (quad != null && quad.size == 4) {
            val tall = (hypot(quad[3].x - quad[0].x, quad[3].y - quad[0].y) + hypot(quad[2].x - quad[1].x, quad[2].y - quad[1].y)) / 2
            val h = tall.roundToInt().coerceIn(600, 1600)
            return Readings.rectify(rgb, quad, (h * CardDetector.CARD_ASPECT).roundToInt(), h)
        }
        val ch = (prior.h * rgb.rows()).coerceAtMost(rgb.rows().toDouble())
        val cw = (ch * CardDetector.CARD_ASPECT).coerceAtMost(rgb.cols().toDouble())
        val x = (prior.cx * rgb.cols() - cw / 2).coerceIn(0.0, rgb.cols() - cw)
        val y = (prior.cy * rgb.rows() - ch / 2).coerceIn(0.0, rgb.rows() - ch)
        val sub = rgb.submat(Rect(x.roundToInt(), y.roundToInt(), cw.roundToInt().coerceAtLeast(1), ch.roundToInt().coerceAtLeast(1)))
        val h = ch.roundToInt().coerceIn(600, 1600)
        val out = Mat()
        Imgproc.resize(sub, out, Size((h * CardDetector.CARD_ASPECT).roundToInt().toDouble(), h.toDouble()), 0.0, 0.0,
            if (h < ch) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
        sub.release()
        return out
    }

    /**
     * A picture of another print (the Japanese original, the western card) for a product printed in
     * another language: a band across the lower half says so, e.g. "Printed in Korean" - large
     * white letters on dark red, the full width of the card, so nobody takes it for the print sold.
     */
    fun labeled(src: Bitmap, label: String): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        val w = out.width.toFloat(); val h = out.height.toFloat()
        val bandH = h * 0.12f
        val top = h * 0.58f
        c.drawRect(0f, top, w, top + bandH, android.graphics.Paint().apply { color = 0xEEB71C1C.toInt() })
        val line = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; strokeWidth = h * 0.006f }
        c.drawLine(0f, top, w, top, line)
        c.drawLine(0f, top + bandH, w, top + bandH, line)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = bandH * 0.56f
            while (measureText(label) > w * 0.9f && textSize > 8f) textSize *= 0.92f
        }
        val fm = text.fontMetrics
        c.drawText(label, w / 2, top + bandH / 2 - (fm.ascent + fm.descent) / 2, text)
        return out
    }

    /** A picture from the web (TCGdex, pokemontcg.io); null if it can't be loaded. */
    fun download(url: String): Bitmap? = runCatching {
        val http = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val bytes = r.body?.bytes() ?: return@use null
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    /** A picture → JPEG bytes (quality 92). */
    fun jpegOf(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it); it.toByteArray() }

    /** RGB Mat → JPEG bytes (quality 92) and a small picture for the screen. */
    fun encode(card: Mat, thumbHeight: Int = 220): Pair<ByteArray, Bitmap> {
        val rgba = Mat()
        Imgproc.cvtColor(card, rgba, Imgproc.COLOR_RGB2RGBA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        val jpeg = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it); it.toByteArray() }
        val thumb = Bitmap.createScaledBitmap(bmp, (thumbHeight * bmp.width.toFloat() / bmp.height).roundToInt().coerceAtLeast(1), thumbHeight, true)
        if (thumb !== bmp) bmp.recycle()
        return jpeg to thumb
    }
}
