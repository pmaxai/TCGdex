package io.github.pmaxai.cardscanner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** The price pad: Clear starts over and stays open; OK takes the price; OK on an empty field removes it. */
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
}
