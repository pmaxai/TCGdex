package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.license.Activated
import io.github.pmaxai.cardscanner.license.LicenceStore
import io.github.pmaxai.cardscanner.recording.CameraRecording
import io.github.pmaxai.cardscanner.ui.AppMode
import io.github.pmaxai.cardscanner.ui.ScannerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The chosen zoom stays when the camera is bound anew - a recording's camera file joining and
 * leaving (YouTube mode) - and when the camera opens again after the app was in the background.
 * (Named to run early: the tests share one process, and the app needs memory to start.)
 */
@RunWith(AndroidJUnit4::class)
class CameraZoomTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun waitUntil(ms: Long, what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            Thread.sleep(200)
        }
    }

    private fun shell(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd))
            .use { String(it.readBytes()) }

    /**
     * The app's camera's zoom now and its largest (null: not known yet): the camera bound to the
     * activity (asking with no use cases hands out that camera and changes nothing).
     */
    private fun cameraZoom(scenario: ActivityScenario<MainActivity>): Pair<Float, Float>? {
        val provider = ProcessCameraProvider.getInstance(ctx).get()
        var z: Pair<Float, Float>? = null
        scenario.onActivity { a ->
            z = provider.bindToLifecycle(a, CameraSelector.DEFAULT_BACK_CAMERA)
                .cameraInfo.zoomState.value?.let { it.zoomRatio to it.maxZoomRatio }
        }
        return z
    }

    /** the app's camera's state (open, opening, closed …) and its error, for the log */
    private fun cameraState(scenario: ActivityScenario<MainActivity>): String {
        val provider = ProcessCameraProvider.getInstance(ctx).get()
        var t = "?"
        scenario.onActivity { a ->
            val st = provider.bindToLifecycle(a, CameraSelector.DEFAULT_BACK_CAMERA).cameraInfo.cameraState.value
            t = "${st?.type} ${st?.error?.code ?: ""}"
        }
        return t
    }

    @Test
    fun theZoomStaysWhenTheCameraIsBoundAgain() {
        val pkg = ctx.packageName
        shell("pm grant $pkg android.permission.CAMERA")
        LicenceStore(ctx).activated("CS-E2ETE-STE2E-TESTE-2ETES", Activated("e2e", "e2e-token", System.currentTimeMillis()))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: ScannerViewModel
        var modeBefore: io.github.pmaxai.cardscanner.ui.Settings? = null
        val dir = File(ctx.cacheDir, "zoom-${System.nanoTime()}").apply { mkdirs() }
        try {
            scenario.onActivity { vm = ViewModelProvider(it)[ScannerViewModel::class.java] }
            waitUntil(180_000, "card data loaded") { !vm.state.value.loading }
            // the tests share one process; when earlier ones left too little memory, the app can't start (no camera then)
            assumeTrue("the app couldn't start in this test process: ${vm.state.value.error}", vm.state.value.error == null)
            modeBefore = vm.state.value.settings
            scenario.onActivity { vm.updateSettings { it.withMode(AppMode.YOUTUBE) } }
            waitUntil(10_000, "YouTube mode") { vm.state.value.settings.youtube && !vm.state.value.licence.locked }
            waitUntil(15_000, "the camera's zoom range") { cameraZoom(scenario) != null }
            val max = cameraZoom(scenario)!!.second
            Log.i("ZoomKept", "camera zoom range 1-$max")
            assumeTrue("this camera can't zoom to 2x (max $max)", max >= 2f)

            fun zoomIsTwo(what: String) {
                Thread.sleep(2_000)        // CameraX has had time to reset it
                val ok = runCatching { waitUntil(8_000, what) { abs((cameraZoom(scenario)?.first ?: 0f) - 2f) < 0.05f } }.isSuccess
                if (!ok) {
                    val seen = "camera at ${cameraZoom(scenario)?.first} (${cameraState(scenario)}), setting ${vm.state.value.settings.zoom}, max ${vm.state.value.maxZoom}"
                    Log.i("ZoomKept", "$what: not 2x - $seen")
                    throw AssertionError("$what: the camera's zoom isn't 2x - $seen")
                }
                Thread.sleep(1_000)
                val z = cameraZoom(scenario)!!.first
                Log.i("ZoomKept", "$what: camera at ${z}x, setting ${vm.state.value.settings.zoom}x")
                assertEquals("$what: the camera's zoom", 2f, z, 0.05f)
                assertEquals("$what: the zoom setting", 2f, vm.state.value.settings.zoom, 0.001f)
            }

            scenario.onActivity { vm.setZoom(2f) }
            zoomIsTwo("before the recording")

            // a recording with the camera file: VideoCapture joins preview and analysis (the camera is bound anew)
            CameraRecording.prepare(File(dir, "camera.mp4"), hd = false, bitrate = 4_000_000)
            waitUntil(15_000, "the camera file starts") { CameraRecording.active || CameraRecording.capture.value == null }
            Log.i("ZoomKept", "camera file: active ${CameraRecording.active}, error ${CameraRecording.error}, camera at ${cameraZoom(scenario)?.first}x")
            zoomIsTwo("recording")

            // Stop: the camera file leaves (bound anew again)
            CameraRecording.stop()
            zoomIsTwo("after the recording")

            // the app in the background and back: the camera closes and opens again
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(1_500)
            scenario.moveToState(Lifecycle.State.RESUMED)
            zoomIsTwo("back in the app")
        } finally {
            runCatching { CameraRecording.stop() }
            runCatching { scenario.onActivity { vm.setZoom(1f) } }
            Thread.sleep(800)           // the zoom is saved when it rests
            modeBefore?.let { m -> runCatching { scenario.onActivity { vm.updateSettings { it.copy(youtube = m.youtube, trading = m.trading, webshop = m.webshop) } } } }
            scenario.close()
            ctx.getSharedPreferences("licence", 0).edit().clear().commit()
            dir.deleteRecursively()
        }
    }
}
