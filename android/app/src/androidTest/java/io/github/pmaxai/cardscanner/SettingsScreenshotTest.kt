package io.github.pmaxai.cardscanner

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import io.github.pmaxai.cardscanner.license.LicenceStatus
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.LicenceState
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.ScannerViewModel
import io.github.pmaxai.cardscanner.ui.Settings
import io.github.pmaxai.cardscanner.ui.SettingsPage
import io.github.pmaxai.cardscanner.ui.SettingsScreen
import io.github.pmaxai.cardscanner.ui.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every settings page, drawn and saved as a picture (the CI prints them into its log). */
@RunWith(AndroidJUnit4::class)
class SettingsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyPage() {
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ScannerViewModel(ApplicationProvider.getApplicationContext<Application>())
        }
        val state = UiState(loading = false, packInfo = "21094 card images · data 2026-11-06 · PSA prices",
            hasPsaData = true, hasCategories = true, pricesDate = "2026-11-05T22:54:32Z",
            settings = Settings(threshold = 5.0, trading = true),
            licence = LicenceState(required = true, status = LicenceStatus.ACTIVE, key = "CS-7K2QX-9MZ4D-01ABC-1XYZW",
                since = 1_762_630_800_000L, lastCheck = 1_762_634_400_000L))
        var page by mutableStateOf(SettingsPage.MODE)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                key(page) { SettingsScreen(state, vm, page) }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("SettingsScreenshot", "skipped: the test screen didn't come up"); return }
        for (p in SettingsPage.values()) {
            compose.runOnIdle { page = p }
            compose.waitForIdle()
            compose.onNodeWithText("  ·  ${p.title}").assertExists()
            Screenshots.save(compose, "settings-${p.ordinal + 1}-${p.name.lowercase()}.png")
        }
    }
}
