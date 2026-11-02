package io.github.pmaxai.cardscanner.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.Mat
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the exported fingerprint model (model.onnx): input (N, size, size, 3) float RGB 0..255,
 * output (N, dim) unit vectors. Normalisation and PCA are inside the model.
 */
class Fingerprinter(modelPath: String, private val size: Int) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })

    /** images: RGB CV_8UC3 Mats of size x size. */
    fun embed(images: List<Mat>): List<FloatArray> {
        val px = size * size * 3
        val buf = ByteBuffer.allocateDirect(images.size * px * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val row = ByteArray(px)
        for (m in images) {
            require(m.rows() == size && m.cols() == size && m.channels() == 3) { "bad input ${m.size()}" }
            val c = if (m.isContinuous) m else m.clone()
            c.get(0, 0, row)
            if (c !== m) c.release()
            for (b in row) buf.put((b.toInt() and 0xFF).toFloat())
        }
        buf.rewind()
        val shape = longArrayOf(images.size.toLong(), size.toLong(), size.toLong(), 3)
        return OnnxTensor.createTensor(env, buf, shape).use { t ->
            session.run(mapOf("image" to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                (out[0].value as Array<FloatArray>).toList()
            }
        }
    }

    override fun close() {
        session.close()
    }
}
