package io.github.pmaxai.cardscanner

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.recording.Encoders
import io.github.pmaxai.cardscanner.recording.FragmentedMp4Writer
import io.github.pmaxai.cardscanner.recording.PcmSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The recording's video: H.264 + AAC into a fragmented MP4 that Android's own extractor reads,
 * with times on the frames' clock, a keyframe every second, and a file that is still usable
 * when the app dies mid-recording.
 */
@RunWith(AndroidJUnit4::class)
class RecorderTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "rec-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** A tone, delivered in real time like a microphone. */
    private class Tone(override val sampleRate: Int = 48_000) : PcmSource {
        private var start = 0L
        private var frames = 0L
        @Volatile private var stopped = false
        override fun start() { start = System.nanoTime(); frames = 0 }
        override fun read(buf: ByteArray): Pair<Int, Long>? {
            if (stopped) return null
            val n = buf.size / 2
            val t = start + frames * 1_000_000_000L / sampleRate
            val wait = t + n * 1_000_000_000L / sampleRate - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            for (i in 0 until n) {
                val v = (sin(2 * PI * 440 * (frames + i) / sampleRate) * 8000).toInt()
                buf[2 * i] = (v and 0xff).toByte(); buf[2 * i + 1] = (v shr 8 and 0xff).toByte()
            }
            frames += n
            return buf.size to t
        }
        override fun stop() { stopped = true }
    }

    private class Track(val format: MediaFormat, val times: List<Long>, val sync: List<Boolean>)

    private fun read(f: File): Map<String, Track> {
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        val out = HashMap<String, Track>()
        for (t in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(t)
            ex.selectTrack(t)
            val times = ArrayList<Long>(); val sync = ArrayList<Boolean>()
            while (true) {
                if (ex.sampleTrackIndex == t) { times += ex.sampleTime; sync += (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0 }
                if (!ex.advance()) break
            }
            ex.unselectTrack(t)
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            out[fmt.getString(MediaFormat.KEY_MIME)!!] = Track(fmt, times, sync)
        }
        ex.release()
        return out
    }

    @Test
    fun aRecordingIsAPlayableFragmentedMp4() {
        val file = File(dir, "video.mp4")
        var zero = 0L
        val enc = Encoders(file, 640, 368, 2_000_000, Tone()) { zero = it }.prepare()
        enc.start()
        val paint = Paint().apply { color = Color.YELLOW }
        val posts = ArrayList<Long>()
        var copied: File? = null
        val begin = System.nanoTime()
        var frame = 0
        while (System.nanoTime() - begin < 3_200_000_000L) {
            val c = enc.inputSurface.lockHardwareCanvas()
            c.drawColor(Color.rgb(20, 20, 30))
            c.drawRect(frame * 5f % 600, 100f, frame * 5f % 600 + 40, 140f, paint)
            enc.inputSurface.unlockCanvasAndPost(c)
            posts += System.nanoTime()
            frame++
            // halfway: what's on disk now must already play (a crash here loses only the last second)
            if (copied == null && System.nanoTime() - begin > 2_300_000_000L) {
                copied = File(dir, "crash.mp4").also { file.copyTo(it) }
            }
            Thread.sleep(33)
        }
        val durationMs = enc.stop()
        assertTrue("duration $durationMs", durationMs in 2_500..4_000)
        // the video's 0 is the time (System.nanoTime) of a frame that was posted – the first one the
        // encoder took (a busy emulator may drop the first few, so it needn't be the first posted)
        val nearest = posts.minOf { abs(zero - it) }
        assertTrue("the video's 0 is a posted frame's own time (System.nanoTime): ${nearest / 1_000_000} ms off, " +
            "${(zero - posts.first()) / 1_000_000} ms after the first post", nearest < 50_000_000L && zero - posts.first() < 1_000_000_000L)

        val tracks = read(file)
        val v = tracks.getValue("video/avc")
        val a = tracks.getValue("audio/mp4a-latm")
        assertEquals(640, v.format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(368, v.format.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(0L, v.times.first())
        assertTrue("frames ${v.times.size}", v.times.size in 60..130)
        assertTrue("times only go forward", v.times.zipWithNext().all { (x, y) -> y > x })
        val keys = v.times.filterIndexed { i, _ -> v.sync[i] }
        assertTrue("a keyframe every second: $keys", keys.size >= 3 && keys.zipWithNext().all { (x, y) -> y - x in 700_000..1_400_000 })
        assertTrue("sound is there: ${a.times.size} AAC frames", a.times.size > 100)
        assertTrue("sound starts with the picture", a.times.first() < 300_000)
        assertEquals(48_000, a.format.getInteger(MediaFormat.KEY_SAMPLE_RATE))

        // the copy taken mid-recording: cut to its last whole fragment, it plays
        val crash = copied!!
        val kept = FragmentedMp4Writer.repair(crash)
        assertTrue(kept > 0)
        val ct = read(crash).getValue("video/avc")
        assertTrue("at most about a second lost: ${ct.times.size} frames", ct.times.size >= 30)
    }

    @Test
    fun annexBBecomesLengthPrefixed() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 1, 0x41, 9)
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 9), FragmentedMp4Writer.annexBToLengthPrefixed(annexB))
    }

    @Test
    fun sizesFollowTheScreenAndTheEncoder() {
        val (w, h) = Encoders.chooseSize(2400, 1080, 1920)        // a 20:9 phone held sideways
        assertTrue("$w x $h", w <= 1920 && h <= 1080 && w % 16 == 0 && h % 16 == 0)
        assertTrue("aspect kept", abs(w.toDouble() / h - 2400.0 / 1080) < 0.05)
        val (pw, ph) = Encoders.chooseSize(1080, 2400, 1280)       // upright, 720p setting
        assertTrue("$pw x $ph", ph <= 1280 && pw < ph)
    }
}
