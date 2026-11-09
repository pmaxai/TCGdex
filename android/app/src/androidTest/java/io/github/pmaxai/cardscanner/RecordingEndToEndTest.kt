package io.github.pmaxai.cardscanner

import android.app.Activity
import android.media.MediaExtractor
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.pmaxai.cardscanner.recording.RecordingController
import io.github.pmaxai.cardscanner.recording.RecordingPrefs
import io.github.pmaxai.cardscanner.recording.RecordingSettings
import io.github.pmaxai.cardscanner.recording.RecordingStore
import io.github.pmaxai.cardscanner.recording.RecordingUploader
import io.github.pmaxai.cardscanner.recording.S3Client
import io.github.pmaxai.cardscanner.recording.S3Config
import io.github.pmaxai.cardscanner.recording.UploadState
import io.github.pmaxai.cardscanner.ui.ScannerViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * A whole recording on the emulator: Android's screen-capture consent (granted to the test
 * through the PROJECT_MEDIA app op), the foreground service, a scripted pack through the
 * view model, Stop, the files on the phone, and the upload into the CI's MinIO.
 */
@RunWith(AndroidJUnit4::class)
class RecordingEndToEndTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun waitUntil(ms: Long, what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            Thread.sleep(200)
        }
    }

    /** Runs a shell command and waits for it to end (the pipe is read to its end). */
    private fun shell(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd))
            .use { String(it.readBytes()) }

    /**
     * Android's "share your screen?" dialog, if it shows despite the app op: whole screen, Start.
     * Returns when [done] or after [ms].
     */
    private fun answerConsent(done: () -> Boolean, ms: Long) {
        val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val end = System.currentTimeMillis() + ms
        var round = 0
        while (!done() && System.currentTimeMillis() < end) {
            val start = dev.findObject(By.text(Pattern.compile("(?i)start( now)?")).clickable(true))
                ?: dev.findObject(By.text(Pattern.compile("(?i)start( now)?")))
            val whole = dev.findObject(By.text(Pattern.compile("(?i)(share )?entire screen")))
            val single = dev.findObject(By.text(Pattern.compile("(?i)(share )?a single app")))
            when {
                start != null -> { Log.i("RecordingE2E", "consent: Start"); start.click() }
                whole != null -> { Log.i("RecordingE2E", "consent: Entire screen"); whole.click() }
                single != null -> { Log.i("RecordingE2E", "consent: open the choice"); single.click() }
            }
            if (++round % 6 == 3) {
                val texts = runCatching { dev.findObjects(By.text(Pattern.compile(".+"))).map { it.text } }.getOrDefault(emptyList())
                Log.i("RecordingE2E", "screen: ${dev.currentPackageName}: $texts")
                if (round == 3) runCatching {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { b ->
                        androidx.test.services.storage.TestStorage().openOutputFile("recording-e2e-consent.png")
                            .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
            Thread.sleep(700)
        }
        if (!done()) shell("logcat -d -t 400").lines().filter { it.contains("rojection", true) || it.contains("consent", true) }
            .takeLast(40).forEach { Log.i("RecordingE2E", "log: $it") }
    }

    @Test
    fun aRecordingFromConsentToUpload() {
        val pkg = ctx.packageName
        Log.i("RecordingE2E", "appops: " + shell("appops set $pkg PROJECT_MEDIA allow") + shell("appops get $pkg PROJECT_MEDIA").trim())
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        RecordingPrefs(ctx).save(RecordingSettings(recordCamera = false, uploadAuto = false, mic = false, fit169 = false))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var vm: ScannerViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[ScannerViewModel::class.java] }
            waitUntil(180_000, "card data loaded") { !vm.state.value.loading }

            val result = CompletableFuture<ActivityResult>()
            scenario.onActivity { a ->
                val launcher = a.activityResultRegistry.register("consent-test", ActivityResultContracts.StartActivityForResult()) { result.complete(it) }
                launcher.launch(a.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
            answerConsent({ result.isDone }, 30_000)
            val res = runCatching { result.get(5, TimeUnit.SECONDS) }.getOrNull()
            Log.i("RecordingE2E", "consent result: ${res?.resultCode} data=${res?.data != null}")
            if (res == null) shell("input keyevent KEYCODE_BACK") // the dialog couldn't be answered: leave it, skip
            assumeTrue("screen capture not granted on this device", res != null && res.resultCode == Activity.RESULT_OK && res.data != null)
            scenario.onActivity { vm.rec.start(res!!.resultCode, res.data!!, landscape = false) }
            waitUntil(30_000, "first frame") { RecordingController.phase.value is RecordingController.Phase.Recording && RecordingController.log != null }

            // a scripted pack: two cards looked up and put into the pack, the last one removed again
            val cards = runBlocking { vm.search("pikachu") }.take(2)
            assertEquals(2, cards.size)
            scenario.onActivity { vm.lookUp(cards[0]); vm.addAgain() }
            Thread.sleep(1_500)
            scenario.onActivity { vm.lookUp(cards[1]); vm.addAgain() }
            Thread.sleep(1_500)
            scenario.onActivity { vm.undoLast() }
            Thread.sleep(1_500)
            val packAtStop = vm.state.value.session.size
            scenario.onActivity { vm.rec.stop() }
            waitUntil(30_000, "recording finished") { RecordingController.phase.value is RecordingController.Phase.Idle }

            val dir = RecordingStore(ctx).list().first()
            waitUntil(15_000, "events.json") { dir.events.exists() && dir.packCsv.exists() }
            val o = JSONObject(dir.events.readText())
            Log.i("RecordingE2E", "events: " + (0 until o.getJSONArray("events").length()).joinToString { i ->
                o.getJSONArray("events").getJSONObject(i).let { "${it.getString("type")}@${it.getLong("t_ms")}" } })
            assertEquals("tcgdex.recording.v1", o.getString("schema"))
            assertEquals("complete", o.getString("status"))
            val types = (0 until o.getJSONArray("events").length()).map { o.getJSONArray("events").getJSONObject(it).getString("type") }
            assertEquals("recording_started", types.first())
            assertEquals("recording_stopped", types.last())
            assertEquals(2, types.count { it == "card_added_again" })
            assertTrue(types.contains("card_removed"))
            assertTrue("looked-up cards are on screen, not in the pack", types.contains("card_shown"))
            assertEquals(packAtStop, o.getJSONArray("cards").length())
            val video = o.getJSONObject("video")
            assertTrue(video.getLong("duration_ms") > 3_000)
            assertEquals("none", video.getString("audio"))
            assertEquals("off", o.getJSONObject("camera").getString("error"))

            // the screen file plays
            val ex = MediaExtractor()
            ex.setDataSource(dir.video.path)
            assertTrue(ex.trackCount >= 1)
            ex.release()

            // and goes up into a real S3 (the CI's MinIO), marker last
            val endpoint = InstrumentationRegistry.getArguments().getString("s3Endpoint").orEmpty()
            if (endpoint.isNotBlank()) {
                val c = S3Client(S3Config(endpoint, "us-east-1", "recordings-test", "e2e/", pathStyle = true, accessKeyId = "minioadmin",
                    secret = "minioadmin", allowHttp = true))
                RecordingUploader(c, dir.id, UploadState(dir.uploadState), "ci").run(dir.uploadFiles())
                val marker = JSONObject(String(c.getObject("e2e/recordings/${dir.id}/_complete.json")))
                assertTrue(marker.getJSONObject("files").has("video.mp4"))
                Log.i("RecordingE2E", "uploaded ${dir.id}: ${marker.getJSONObject("files")}")
            }
            dir.dir.deleteRecursively()
        } finally {
            scenario.close()
            shell("appops reset $pkg")
        }
    }
}
