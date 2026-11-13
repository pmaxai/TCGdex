package io.github.pmaxai.cardscanner.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Recordings saved on the phone instead of uploaded: the videos into the Gallery, in
 * Movies/Card Scanner. The screen video (a fragmented MP4, written so a crash loses little)
 * is rewritten as a plain MP4 on the way, without re-encoding, so players can seek in it;
 * the camera video is copied as it is.
 */
object GallerySaver {
    const val FOLDER = "Card Scanner"
    private const val TAG = "GallerySaver"

    /** Where they land, as people see it. */
    val place: String get() = "${Environment.DIRECTORY_MOVIES}/$FOLDER"

    /** Saving needs the storage permission only before Android 10. */
    val needsPermission: Boolean get() = Build.VERSION.SDK_INT < 29

    /**
     * [src] into the Gallery as [name] (e.g. "20261113T201500Z-7f3a.mp4"), dated [takenMs].
     * [remux]: rewrite as a plain MP4 (falls back to a copy if that fails). Returns its address.
     */
    fun save(ctx: Context, src: File, name: String, takenMs: Long, remux: Boolean): Uri {
        if (Build.VERSION.SDK_INT < 29) return saveLegacy(ctx, src, name, remux)
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, place)
            put(MediaStore.Video.Media.DATE_TAKEN, takenMs)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("the Gallery didn't take the video")
        try {
            val muxed = remux && runCatching {
                cr.openFileDescriptor(uri, "rw")!!.use { pfd -> remux(src, pfd.fileDescriptor) }
            }.onFailure { Log.w(TAG, "remux of ${src.name} failed, copying it as it is", it) }.isSuccess
            if (!muxed) cr.openOutputStream(uri, "wt")!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 20) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
    }

    /** Before Android 10: a file in the public Movies folder, then the media scanner. */
    private fun saveLegacy(ctx: Context, src: File, name: String, remux: Boolean): Uri {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER).apply { mkdirs() }
        val dst = File(dir, name)
        try {
            val muxed = remux && runCatching {
                java.io.RandomAccessFile(dst, "rw").use { f -> f.setLength(0); remux(src, f.fd) }
            }.onFailure { Log.w(TAG, "remux of ${src.name} failed, copying it as it is", it) }.isSuccess
            if (!muxed) src.copyTo(dst, overwrite = true)
        } catch (e: Exception) {
            dst.delete(); throw e
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.path), arrayOf("video/mp4"), null)
        return Uri.fromFile(dst)
    }

    /** The samples of [src] as they are into a plain MP4 (index at the end, seekable). */
    fun remux(src: File, dst: FileDescriptor) {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(src.path)
            require(ex.trackCount > 0) { "no tracks" }
            val m = MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { mux = it }
            val tracks = IntArray(ex.trackCount)
            var bufSize = 4 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                ex.selectTrack(i)
                tracks[i] = m.addTrack(f)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufSize = maxOf(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            m.start()
            val buf = ByteBuffer.allocateDirect(bufSize)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, n, ex.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(tracks[ex.sampleTrackIndex], buf, info)
                samples++
                ex.advance()
            }
            require(samples > 0) { "no samples" }
            m.stop()
        } finally {
            runCatching { mux?.release() }
            ex.release()
        }
    }
}
