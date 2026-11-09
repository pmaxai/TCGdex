package io.github.pmaxai.cardscanner

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.recording.CameraRecording
import io.github.pmaxai.cardscanner.recording.RecordingSettings
import io.github.pmaxai.cardscanner.recording.RecordingStore
import io.github.pmaxai.cardscanner.recording.S3Provider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Recording around the edges: the camera file when the camera can't bind, crash recovery, corners, settings. */
@RunWith(AndroidJUnit4::class)
class RecordingFlowTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun aCameraThatCannotBindLeavesOnlyTheScreen() {
        val dir = File(ctx.cacheDir, "flow-${System.nanoTime()}").apply { mkdirs() }
        CameraRecording.prepare(File(dir, "camera.mp4"), hd = true, bitrate = 8_000_000)
        assertTrue("the camera is asked to bind the file's use case", CameraRecording.capture.value != null)
        CameraRecording.onBound(ctx, ok = false)                     // the phone can't take three use cases
        assertEquals(null, CameraRecording.capture.value)             // scanning goes on with preview + analysis
        val cam = CameraRecording.headerJson(enabled = true, screenZeroNanos = 0L)
        assertTrue(cam.isNull("file"))
        assertEquals("bind_failed", cam.getString("error"))
        assertEquals(listOf("file", "width", "height", "fps", "offset_ms", "error"), cam.keys().asSequence().toList())
        CameraRecording.stop()
        assertEquals("off", CameraRecording.headerJson(enabled = false, screenZeroNanos = 0L).getString("error"))
        dir.deleteRecursively()
    }

    @Test
    fun cornersMapIntoTheCameraFile() {
        // upright 3:4 analysis frame, upright 9:16 camera file: the file is the frame's middle columns
        val c = CameraRecording.toCameraFile(0.5, 0.5, 3.0 / 4, 9.0 / 16)
        assertEquals(0.5, c[0], 1e-9); assertEquals(0.5, c[1], 1e-9)
        val left = CameraRecording.toCameraFile(0.125, 0.2, 3.0 / 4, 9.0 / 16)   // k = 0.75: x 0.125 is the file's left edge
        assertEquals(0.0, left[0], 1e-9); assertEquals(0.2, left[1], 1e-9)
        // sideways: 4:3 frame, 16:9 file: full width, the middle 75 % of the height
        val top = CameraRecording.toCameraFile(0.3, 0.125, 4.0 / 3, 16.0 / 9)
        assertEquals(0.3, top[0], 1e-9); assertEquals(0.0, top[1], 1e-9)
    }

    @Test
    fun aCrashedRecordingIsRebuiltFromItsEvents() {
        val dir = File(ctx.cacheDir, "crash-${System.nanoTime()}").apply { mkdirs() }
        val card = { id: String -> JSONObject().put("id", id).put("name", id) }
        val price = { v: Double -> JSONObject().put("value", v).put("currency", "EUR") }
        val lines = listOf(
            JSONObject().put("seq", 1).put("t_ms", 0).put("type", "recording_started").put("pack_at_start", JSONArray().put(
                JSONObject().put("entry", 1).put("before_recording", true).put("hit", false).put("card", card("a")).put("price", price(0.5)))),
            JSONObject().put("seq", 2).put("t_ms", 1000).put("type", "card_shown").put("entry", 2).put("in_pack", true).put("hit", false)
                .put("card", card("b")).put("price", price(1.0)),
            JSONObject().put("seq", 3).put("t_ms", 1500).put("type", "card_updated").put("entry", 2).put("hit", true)
                .put("card", card("b2")).put("price", price(6.0)),
            JSONObject().put("seq", 4).put("t_ms", 2000).put("type", "card_shown").put("entry", JSONObject.NULL).put("in_pack", false)
                .put("hit", false).put("card", card("c")).put("price", price(0.1)),
            JSONObject().put("seq", 5).put("t_ms", 3000).put("type", "card_removed").put("entry", 1).put("reason", "swipe"),
        )
        File(dir, "events.jsonl").writeText(lines.joinToString("\n") + "\n")
        val header = JSONObject().put("video", JSONObject().put("file", "video.mp4").put("duration_ms", JSONObject.NULL))
        val (text, cards) = RecordingStore.recoverEvents(header, File(dir, "events.jsonl"), "20261109T140322Z-7f3a", 4200)
        val o = JSONObject(text)
        assertEquals("recovered", o.getString("status"))
        assertEquals(4200, o.getJSONObject("video").getLong("duration_ms"))
        val ev = o.getJSONArray("events")
        assertEquals("recording_stopped", ev.getJSONObject(ev.length() - 1).getString("type"))
        assertEquals("recovered", ev.getJSONObject(ev.length() - 1).getString("reason"))
        assertEquals(listOf("b2"), cards.map { it.getJSONObject("card").getString("id") })
        assertTrue(cards.single().getBoolean("hit"))
        assertEquals(1, o.getJSONObject("summary").getInt("hits"))
        val csv = RecordingStore.csvOf(cards)
        assertTrue(csv.contains(",b2,") || csv.contains("b2"))
        dir.deleteRecursively()
    }

    @Test
    fun providerPresets() {
        assertEquals("https://s3.eu-central-1.amazonaws.com", S3Provider.AWS.endpointFor("eu-central-1"))
        assertEquals("https://sos-at-vie-1.exo.io", S3Provider.EXOSCALE.endpointFor("at-vie-1"))
        assertTrue(S3Provider.OTHER.pathStyle); assertFalse(S3Provider.EXOSCALE.pathStyle)
        assertFalse("nothing to upload to yet", RecordingSettings().uploadReady)
        assertTrue(RecordingSettings(bucket = "b", accessKeyId = "k", hasSecret = true).uploadReady)
    }
}
