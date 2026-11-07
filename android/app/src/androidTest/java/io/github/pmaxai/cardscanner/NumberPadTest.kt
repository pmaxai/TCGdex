package io.github.pmaxai.cardscanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.NumberPadContent
import io.github.pmaxai.cardscanner.ui.Panel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.ui.NumberPad
import io.github.pmaxai.cardscanner.ui.PadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The price pad: Clear starts over and stays open; OK takes the price; OK on an empty field removes it.
 * On a landscape phone the keys sit beside the price, so OK stays on the screen.
 */
@RunWith(AndroidJUnit4::class)
class NumberPadTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun clearStaysOpenForTheNewPrice() {
        val results = mutableListOf<Double?>()
        var open by mutableStateOf(true)
        compose.setContent {
            if (open) NumberPad(PadRequest("Asking price", 12.5, null, verdicts = false) { results += it }) { open = false }
        }
        compose.onNodeWithText("Clear").performClick()
        compose.waitForIdle()
        assertTrue("the pad is still open after Clear", open)
        assertTrue("Clear doesn't set a price by itself", results.isEmpty())
        compose.onNodeWithText("3").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertFalse(open)
        assertEquals(listOf<Double?>(3.0), results)

        // Clear, then OK on the empty field: the price is removed
        open = true
        compose.waitForIdle()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertFalse(open)
        assertNull(results.last())
    }

    /** A small phone held sideways (740 × 330 dp left for the pad), drawn scaled down onto the test screen. */
    @Test
    fun landscapeKeepsOkOnTheScreen() {
        val results = mutableListOf<Double?>()
        var open by mutableStateOf(true)
        compose.setContent {
            val real = LocalDensity.current
            val scale = minOf(LocalConfiguration.current.screenWidthDp / 740f, 1f)
            CompositionLocalProvider(LocalDensity provides Density(real.density * scale, real.fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                    Box(Modifier.requiredSize(740.dp, 330.dp).background(Ink).testTag("phone"), contentAlignment = Alignment.Center) {
                        if (open) Surface(shape = RoundedCornerShape(18.dp), color = Panel) {
                            NumberPadContent(PadRequest("Selling price", 0.08, 0.08, verdicts = false) { results += it },
                                wide = true, compact = true) { open = false }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val phone = compose.onNodeWithTag("phone").getBoundsInRoot()
        for (key in listOf("OK", "Clear", "⌫", "= market")) {
            val b = compose.onNodeWithText(key).getBoundsInRoot()
            assertTrue("$key is on the screen: $b in $phone", b.top >= phone.top && b.bottom <= phone.bottom && b.right <= phone.right)
        }
        Screenshots.save(compose, "numberpad-landscape.png")
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("1").performClick()
        compose.onNodeWithText("5").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertFalse(open)
        assertEquals(listOf<Double?>(15.0), results)
    }
}
