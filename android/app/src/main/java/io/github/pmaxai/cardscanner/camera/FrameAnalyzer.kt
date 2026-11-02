package io.github.pmaxai.cardscanner.camera

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import io.github.pmaxai.cardscanner.vision.Recognizer
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Hands every camera frame to [onFrame] as an upright RGB Mat (released afterwards). */
class FrameAnalyzer(private val onFrame: (Mat) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            val rgb = image.toUprightRgb()
            try {
                onFrame(rgb)
            } finally {
                rgb.release()
            }
        } finally {
            image.close()
        }
    }
}

/** RGBA_8888 ImageProxy -> RGB Mat, rotated so it matches what the user sees. */
fun ImageProxy.toUprightRgb(): Mat {
    val plane = planes[0]
    val buf = plane.buffer
    buf.rewind()
    val w = width
    val h = height
    val rowBytes = w * 4
    val rgba = Mat(h, w, CvType.CV_8UC4)
    val row = ByteArray(rowBytes)
    if (plane.rowStride == rowBytes) {
        val all = ByteArray(rowBytes * h)
        buf.get(all, 0, minOf(all.size, buf.remaining()))
        rgba.put(0, 0, all)
    } else {
        for (y in 0 until h) {
            buf.position(y * plane.rowStride)
            buf.get(row, 0, rowBytes)
            rgba.put(y, 0, row)
        }
    }
    val rgb = Mat()
    Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
    rgba.release()
    val code = when (imageInfo.rotationDegrees) {
        90 -> Core.ROTATE_90_CLOCKWISE
        180 -> Core.ROTATE_180
        270 -> Core.ROTATE_90_COUNTERCLOCKWISE
        else -> -1
    }
    if (code < 0) return rgb
    val out = Mat()
    Core.rotate(rgb, out, code)
    rgb.release()
    return out
}

/** A photo from the gallery as an upright RGB Mat (EXIF orientation applied). */
fun loadPhoto(resolver: ContentResolver, uri: Uri, maxSide: Int = 3000): Mat? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val bmp = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
    }
    val upright = if (m.isIdentity) bmp else
        android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    return Recognizer.bitmapToRgb(upright)
}
