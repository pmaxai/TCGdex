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
import androidx.test.uiautomator.Until
import io.github.pmaxai.cardscanner.license.Activated
import io.github.pmaxai.cardscanner.license.LicenceStore
import io.github.pmaxai.cardscanner.recording.RecordingController
import io.github.pmaxai.cardscanner.recording.RecordingPrefs
import io.github.pmaxai.cardscanner.recording.RecordingSettings
import io.github.pmaxai.cardscanner.recording.RecordingStore
import io.github.pmaxai.cardscanner.recording.RecordingUploader
import io.github.pmaxai.cardscanner.recording.S3Client
import io.github.pmaxai.cardscanner.recording.S3Config
import io.github.pmaxai.cardscanner.recording.UploadState
import io.github.pmaxai.cardscanner.ui.AppMode
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

    private fun savePicture(name: String, bmp: android.graphics.Bitmap?) {
        bmp ?: return
        runCatching { androidx.test.services.storage.TestStorage().openOutputFile(name).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test
    fun aRecordingFromConsentToUpload() {
        val pkg = ctx.packageName
        Log.i("RecordingE2E", "appops: " + shell("appops set $pkg PROJECT_MEDIA allow") + shell("appops get $pkg PROJECT_MEDIA").trim())
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        shell("pm grant $pkg android.permission.CAMERA") // else the app's camera question covers the screen
        RecordingPrefs(ctx).save(RecordingSettings(recordCamera = false, uploadAuto = false, mic = false, fit169 = true))
        // a licence on this phone (the service doesn't know its token; offline answers keep it), so YouTube mode is on screen
        LicenceStore(ctx).activated("CS-E2ETE-STE2E-TESTE-2ETES", Activated("e2e", "e2e-token", System.currentTimeMillis()))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var modeBefore: io.github.pmaxai.cardscanner.ui.Settings? = null
        lateinit var vm: ScannerViewModel
        try {
            scenario.onActivity { vm = ViewModelProvider(it)[ScannerViewModel::class.java] }
            waitUntil(180_000, "card data loaded") { !vm.state.value.loading }
            modeBefore = vm.state.value.settings
            scenario.onActivity { vm.updateSettings { it.withMode(AppMode.YOUTUBE) } }
            waitUntil(10_000, "YouTube mode") { vm.state.value.settings.youtube && !vm.state.value.licence.locked }

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
            // the red dot and the time: on the screen, in the black border (they must not be in the video, see below)
            val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val timer = dev.wait(Until.findObject(By.pkg(ctx.packageName).text(Pattern.compile("●\\s*\\d+:\\d\\d(:\\d\\d)?"))), 5_000)
            if (timer == null) {
                savePicture("recording-3-screen-while-recording.png",
                    runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() }.getOrNull())
                val texts = runCatching { dev.findObjects(By.text(Pattern.compile(".+"))).map { "${it.applicationPackage}:'${it.text}'@${it.visibleBounds}" } }
                    .getOrDefault(emptyList())
                val dm = ctx.resources.displayMetrics
                Log.i("RecordingE2E", "no timer found; density ${dm.density}, ${dm.widthPixels}x${dm.heightPixels}, " +
                    "phase ${vm.state.value.recording.phase}, fit ${vm.state.value.recording.settings.fit169}, timer ${vm.state.value.recording.settings.showTimer}; " +
                    "windows ${InstrumentationRegistry.getInstrumentation().uiAutomation.windows.map { "${it.type}/${it.title}" }}; texts $texts")
            }
            assertTrue("the recording's time is on the screen", timer != null)
            val timerBounds = timer!!.visibleBounds
            val screen = android.graphics.Point().also { p ->
                @Suppress("DEPRECATION") ctx.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealSize(p) }
            Log.i("RecordingE2E", "timer '${timer.text}' at $timerBounds on a ${screen.x}x${screen.y} screen")
            savePicture("recording-3-screen-while-recording.png",
                runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() }.getOrNull())

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
            // FIX-1: Fit to 16:9 – the upright screen was laid out in a 9:16 area, and that area is in the log
            val cr = video.getJSONObject("content_rect")
            Log.i("RecordingE2E", "video ${video.getInt("width")}x${video.getInt("height")}, content_rect $cr")
            assertEquals("content_rect is 9:16: $cr", 9.0 / 16, cr.getDouble("width") / cr.getDouble("height"), 0.02)
            assertTrue("content_rect inside the video: $cr", cr.getInt("x") >= 0 && cr.getInt("y") >= 0 &&
                cr.getInt("x") + cr.getInt("width") <= video.getInt("width") + 1 && cr.getInt("y") + cr.getInt("height") <= video.getInt("height") + 1)
            // FIX-5: the reveals' times come from their first drawn frame
            val reveals = o.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                .filter { it.getString("type") == "card_shown" || it.getString("type") == "card_added_again" }
            Log.i("RecordingE2E", "reveals at " + reveals.joinToString { "${it.getString("type")}@${it.getLong("t_ms")}" })
            reveals.forEach { e -> Log.i("RecordingE2E", "  ${e.getString("type")}@${e.getLong("t_ms")} entry=${e.opt("entry")} " +
                "in_pack=${e.opt("in_pack")} source=${e.opt("source")} card=${e.getJSONObject("card").optString("id")}") }

            // the red dot and the time are not in the video (their window is secure: recorded black, on black)
            val frame = android.media.MediaMetadataRetriever().run {
                try { setDataSource(dir.video.path); getFrameAtTime(3_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST) } finally { release() }
            }!!
            savePicture("recording-4-the-video-at-3s.png", frame)
            val sy = frame.height.toDouble() / screen.y
            val bandTop = cr.getInt("y") + cr.getInt("height")
            assertTrue("the timer sits in the border below the 16:9 area: $timerBounds", timerBounds.top * sy >= bandTop - 2)
            // the whole border below the 16:9 area: no red dot, no red frame
            var red = 0; var px = 0
            for (y in bandTop until frame.height)
                for (x in 0 until frame.width) {
                    val c = frame.getPixel(x, y); px++
                    if (android.graphics.Color.red(c) > 150 && android.graphics.Color.green(c) < 120 && android.graphics.Color.blue(c) < 120) red++
                }
            var lit = 0
            for (y in cr.getInt("y") until cr.getInt("y") + cr.getInt("height") step 4)
                for (x in cr.getInt("x") until cr.getInt("x") + cr.getInt("width") step 4)
                    if (frame.getPixel(x.coerceAtMost(frame.width - 1), y.coerceAtMost(frame.height - 1)).let { android.graphics.Color.green(it) } > 60) lit++
            Log.i("RecordingE2E", "video frame at 3 s: $red red of $px pixels in the border with the timer; $lit lit samples in the 16:9 area")
            assertTrue("the YouTube screen is in the video", lit > 50)
            assertEquals("the timer is not in the video", 0, red)

            // FIX-4: the screen file plays, at a constant 30 fps
            val ex = MediaExtractor()
            ex.setDataSource(dir.video.path)
            assertTrue(ex.trackCount >= 1)
            ex.selectTrack(0)
            val times = ArrayList<Long>()
            do { times += ex.sampleTime } while (ex.advance())
            ex.release()
            val gaps = times.zipWithNext { a, b -> b - a }
            val fps = (times.size - 1) * 1_000_000.0 / (times.last() - times.first())
            val even = gaps.count { kotlin.math.abs(it - 33_333) <= 1_000 }
            Log.i("RecordingE2E", "video: ${times.size} frames, %.2f fps, %d of %d gaps 1/30 s".format(fps, even, gaps.size))
            assertTrue("%.2f fps".format(fps), fps in 29.5..30.5)
            assertTrue("frames 1/30 s apart: $even of ${gaps.size}", even >= gaps.size * 0.98)

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
            modeBefore?.let { m -> runCatching { scenario.onActivity { vm.updateSettings { it.copy(youtube = m.youtube, trading = m.trading, webshop = m.webshop) } } } }
            scenario.close()
            ctx.getSharedPreferences("licence", 0).edit().clear().commit()
            shell("appops reset $pkg")
        }
    }
}
