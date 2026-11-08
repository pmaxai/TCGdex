package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.license.LicenceStatus
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.LicenceGate
import io.github.pmaxai.cardscanner.ui.LicencePage
import io.github.pmaxai.cardscanner.ui.LicenceState
import io.github.pmaxai.cardscanner.ui.Panel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The licence screens, saved as pictures (the CI prints them): first start, moved away, typing, Settings › Licence. */
@RunWith(AndroidJUnit4::class)
class LicenceScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val key = "CS-7K2QX-9MZ4D-01ABC-1XYZW"
    private val camera: @androidx.compose.runtime.Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color(0xFF30343C)), contentAlignment = Alignment.Center) { Text("camera", color = Color.Gray) }
    }

    @Test
    fun gate() {
        var state by mutableStateOf(LicenceState(required = true))
        val keys = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                key(state.status) { LicenceGate(state, onKey = { keys += it }, camera = camera) }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("LicenceScreenshot", "skipped: the test screen didn't come up"); return }
        compose.onNodeWithText("Activate Card Scanner").assertExists()
        Screenshots.save(compose, "licence-1-first-start.png")

        compose.onNodeWithText("Type the key instead").performClick()
        compose.onNodeWithText("Licence key").performClick()
        compose.onNodeWithText("Licence key").performTextInput("cs-7k2qx-9mz4d-01abc-1xyzw")
        compose.onNodeWithText("Activate").performClick()
        compose.waitForIdle()
        assertEquals(listOf("CS-7K2QX-9MZ4D-01ABC-1XYZW"), keys)
        state = state.copy(error = "This licence key doesn't exist. Check it, or scan the QR code from your licence e-mail.")
        compose.waitForIdle()
        Screenshots.save(compose, "licence-2-typed.png")

        state = LicenceState(required = true, status = LicenceStatus.SUSPENDED, suspendedBy = "Samsung Galaxy S24",
            suspendedSince = 1_762_630_800_000L)
        compose.waitForIdle()
        compose.onNodeWithText("This licence is now used on another device").assertExists()
        Screenshots.save(compose, "licence-3-moved.png")
    }

    @Test
    fun settingsPage() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    LicencePage(LicenceState(required = true, status = LicenceStatus.ACTIVE, key = key,
                        since = 1_762_630_800_000L, lastCheck = 1_762_634_400_000L))
                }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("LicenceScreenshot", "skipped: the test screen didn't come up"); return }
        compose.onNodeWithText("CS-7K2QX-•••••-•••••-1XYZW").assertExists()
        compose.onNodeWithText("Show the licence QR code").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(key).assertExists()
        Screenshots.save(compose, "licence-4-switch-device.png")
    }
}
