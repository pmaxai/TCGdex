package io.github.pmaxai.cardscanner

import android.app.Application
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.recording.Encoders
import io.github.pmaxai.cardscanner.recording.GallerySaver
import io.github.pmaxai.cardscanner.recording.RecStatus
import io.github.pmaxai.cardscanner.recording.RecordingStore
import io.github.pmaxai.cardscanner.ui.ScannerViewModel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * Upload to S3 off: Save puts the recording's videos into the Gallery (Movies/Card Scanner).
 * The screen video arrives as a plain MP4 players can seek in, with every sample; the camera
 * video as it is; the app's copies go, the list says "saved in the Gallery".
 */
@RunWith(AndroidJUnit4::class)
class GallerySaverTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var dir: File
    private val made = ArrayList<Uri>()

    @Before fun setUp() {
        assumeTrue("the Gallery's folders need Android 10", android.os.Build.VERSION.SDK_INT >= 29)
        dir = File(ctx.cacheDir, "gallery-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun tearDown() {
        made.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    /** A 4 s screen recording as the app writes it (fragmented MP4, H.264 + AAC). Returns its length in ms. */
    private fun record(file: File): Long {
        val enc = Encoders(file, 640, 368, 2_000_000, RecorderTest.Tone()).prepare()
        enc.start()
        val paint = Paint().apply { color = Color.YELLOW }
        val begin = System.nanoTime()
        var frame = 0
        while (System.nanoTime() - begin < 4_000_000_000L) {
            val c = enc.inputSurface.lockHardwareCanvas()
            c.drawColor(Color.rgb(20, 20, 30))
            c.drawRect(frame * 5f % 600, 100f, frame * 5f % 600 + 40, 140f, paint)
            enc.inputSurface.unlockCanvasAndPost(c)
            frame++
            Thread.sleep(33)
        }
        return enc.stop()
    }

    /** the top-level boxes of an MP4 ("ftyp", "moov", "mdat" … or "moof" for a fragmented one) */
    private fun boxes(input: InputStream): List<String> = DataInputStream(input.buffered()).use { s ->
        val out = ArrayList<String>()
        while (true) {
            val size32 = try { s.readInt().toLong() and 0xffffffffL } catch (e: java.io.EOFException) { break }
            val type = String(ByteArray(4).also { s.readFully(it) }, Charsets.US_ASCII)
            out += type
            val body = when (size32) { 1L -> s.readLong() - 16; 0L -> break; else -> size32 - 8 }
            var left = body
            while (left > 0) { val n = s.skip(left); if (n <= 0) { if (s.read() < 0) break; left-- } else left -= n }
        }
        out
    }

    /** samples per track ("video/avc" → count) and where a seek to 2 s lands (µs) */
    private fun samples(ex: MediaExtractor): Pair<Map<String, Int>, Long> {
        val counts = HashMap<String, Int>()
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!! }
        (0 until ex.trackCount).forEach { ex.selectTrack(it) }
        while (ex.sampleTrackIndex >= 0) { counts.merge(mimes[ex.sampleTrackIndex], 1, Int::plus); ex.advance() }
        val v = mimes.indexOfFirst { it.startsWith("video/") }
        (0 until ex.trackCount).forEach { ex.unselectTrack(it) }
        ex.selectTrack(v)
        ex.seekTo(2_000_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val at = ex.sampleTime
        ex.release()
        return counts to at
    }

    @Test
    fun theScreenVideoLandsInTheGalleryAsAPlainSeekableMp4() {
        val src = File(dir, "video.mp4")
        val ms = record(src)
        assertTrue("the app writes it fragmented", "moof" in boxes(src.inputStream()))
        val name = "test-${System.nanoTime()}.mp4"
        val uri = GallerySaver.save(ctx, src, name, 1_763_064_900_000L, remux = true).also { made += it }

        ctx.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.MIME_TYPE), null, null, null)!!.use { c ->
            assertTrue("in the Gallery", c.moveToFirst())
            assertEquals(name, c.getString(0))
            assertTrue("in Movies/Card Scanner: ${c.getString(1)}", c.getString(1).trimEnd('/') == "Movies/Card Scanner")
            assertEquals("visible (not pending)", 0, c.getInt(2))
            assertEquals("video/mp4", c.getString(3))
        }
        val top = ctx.contentResolver.openInputStream(uri)!!.use { boxes(it) }
        assertTrue("a plain MP4: $top", "moov" in top && "mdat" in top && "moof" !in top)

        val (before, seekBefore) = samples(MediaExtractor().apply { setDataSource(src.path) })
        val (after, seekAfter) = ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            samples(MediaExtractor().apply { setDataSource(pfd.fileDescriptor) })
        }
        Log.i("GallerySaver", "samples $before → $after; a seek to 2 s lands at ${seekBefore / 1000} ms in the original, ${seekAfter / 1000} ms saved")
        assertEquals("every sample, picture and sound", before, after)
        assertTrue("seekable: a seek to 2 s lands on the keyframe before it (${seekAfter / 1000} ms)", seekAfter in 900_000..2_000_000)
        val dur = MediaMetadataRetriever().run {
            ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { setDataSource(it.fileDescriptor) }
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong().also { release() }
        }
        assertTrue("its length: $dur ms (recorded $ms ms)", kotlin.math.abs(dur - ms) < 300)
    }

    /** The camera video (already a plain MP4) and anything that can't be rewritten arrive byte for byte. */
    @Test
    fun otherwiseItIsCopiedAsItIs() {
        val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val src = File(dir, "camera.mp4").apply { writeBytes(bytes) }
        val copied = GallerySaver.save(ctx, src, "test-${System.nanoTime()}-camera.mp4", System.currentTimeMillis(), remux = false).also { made += it }
        assertArrayEquals(bytes, ctx.contentResolver.openInputStream(copied)!!.use { it.readBytes() })
        // not a video at all: rewriting fails, so it is copied, nothing lost
        val notMp4 = GallerySaver.save(ctx, src, "test-${System.nanoTime()}.mp4", System.currentTimeMillis(), remux = true).also { made += it }
        assertArrayEquals(bytes, ctx.contentResolver.openInputStream(notMp4)!!.use { it.readBytes() })
    }

    /** Save on a recording (Upload to S3 off): its videos into the Gallery, the app's copies gone, "saved in the Gallery". */
    @Test
    fun saveMovesTheRecordingIntoTheGallery() {
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = ScannerViewModel(ctx) }
        val store = RecordingStore(ctx)
        val rec = store.create()
        try {
            record(rec.video)
            rec.camera.writeBytes(ByteArray(50_000) { it.toByte() })
            rec.events.writeText("""{"cards":[]}""")
            rec.updateMeta { it.put("status", RecStatus.LOCAL.name).put("decide", true).put("duration_ms", 4000).put("created", System.currentTimeMillis()) }
            val size = rec.video.length() + rec.camera.length()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.rec.saveToPhone(rec.id) }
            val end = System.currentTimeMillis() + 30_000
            while (rec.status != RecStatus.SAVED) { check(System.currentTimeMillis() < end) { "not saved: ${rec.meta()}" }; Thread.sleep(200) }
            val uris = rec.meta().getJSONArray("gallery").let { a -> (0 until a.length()).map { Uri.parse(a.getString(it)) } }
            made += uris
            assertEquals("the screen and the camera video", 2, uris.size)
            uris.forEach { u -> ctx.contentResolver.query(u, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue("$u is in the Gallery", it.moveToFirst()); Log.i("GallerySaver", "saved: ${it.getString(0)}") } }
            assertFalse("the app's screen video is gone", rec.video.exists())
            assertFalse("the app's camera video is gone", rec.camera.exists())
            assertEquals("the size shown is the videos'", size, rec.shownBytes)
            assertEquals("saved in the Gallery (Movies/Card Scanner)", rec.statusText(uploadS3 = false))
            assertFalse("the question doesn't come back", rec.meta().optBoolean("decide"))
            assertTrue("Share sends the Gallery's video", vm.rec.shareIntent(rec.id) != null)
        } finally {
            rec.dir.deleteRecursively()
        }
    }
}
