package io.github.pmaxai.cardscanner

import android.app.Application
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.recording.RecStatus
import io.github.pmaxai.cardscanner.recording.RecordingSettings
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.RecInfo
import io.github.pmaxai.cardscanner.ui.RecordingUi
import io.github.pmaxai.cardscanner.ui.RecordingsDialog
import io.github.pmaxai.cardscanner.ui.ScannerViewModel
import io.github.pmaxai.cardscanner.ui.StartSheet
import io.github.pmaxai.cardscanner.ui.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The recording dialogs, saved as pictures (the CI prints them): before a recording, and ⚙ → Recordings…. */
@RunWith(AndroidJUnit4::class)
class RecordingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dialogs() {
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ScannerViewModel(ApplicationProvider.getApplicationContext<Application>())
        }
        val settings = RecordingSettings(bucket = "my-recordings", accessKeyId = "AKIA…", hasSecret = true)
        val recs = listOf(
            RecInfo("20261109T140322Z-7f3a", "9 Nov 2026, 15:03", 1_834_000, 38, 3, 1_830_000_000, RecStatus.UPLOADING, "uploading 62 %", null),
            RecInfo("20261108T191001Z-02c1", "8 Nov 2026, 20:10", 2_710_000, 51, 5, 2_640_000_000, RecStatus.QUEUED, "waiting for Wi-Fi", null),
            RecInfo("20261107T171544Z-b9e0", "7 Nov 2026, 18:15", 954_000, 17, 1, 0, RecStatus.UPLOADED, "uploaded · files deleted on the phone", null),
            RecInfo("20261106T120000Z-1d2e", "6 Nov 2026, 13:00", 312_000, 6, 0, 290_000_000, RecStatus.FAILED,
                "failed: This key may not write to the bucket/prefix", "AccessDenied"),
        )
        var shown by mutableStateOf("start")
        val state = UiState(loading = false, recording = RecordingUi(settings = settings, recordings = recs,
            lastSummary = "Recording 30:34 · 38 cards · 3 hits · 1.8 GB · queued", batteryHint = true))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                Box(Modifier.fillMaxSize().background(Ink))
                when (shown) {
                    "start" -> StartSheet(state, vm, landscape = true) {}
                    "list" -> RecordingsDialog(state, vm) {}
                }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("RecordingScreenshot", "skipped: the test screen didn't come up"); return }
        compose.onNodeWithText("Start recording").assertExists()
        Screenshots.saveScreen(compose, "recording-1-start.png")
        compose.runOnIdle { shown = "list" }
        compose.onNodeWithText("Recordings").assertExists()
        Screenshots.saveScreen(compose, "recording-2-list.png")
    }
}
