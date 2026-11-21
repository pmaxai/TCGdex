package io.github.pmaxai.cardscanner

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.ui.CollectionItem
import io.github.pmaxai.cardscanner.ui.CollectionPart
import io.github.pmaxai.cardscanner.ui.CollectionScreen
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.UiState
import io.github.pmaxai.cardscanner.ui.WantedItem
import io.github.pmaxai.cardscanner.ui.eur
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The collection page as pictures: the collection with its total, a filter, the missing cards and Found it. */
@RunWith(AndroidJUnit4::class)
class CollectionScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun collectionPage() {
        val vm = TestVm.shared
        val items = listOf(
            CollectionItem(3, "sv03.5-199", "DE", "holo", "NM", null, 2, "Glurak-ex", "151", "199/165", null, 41250, "2026-11-20T10:00:00Z"),
            CollectionItem(2, "base1-4", "EN", "holo", "EX", "PSA 8", 1, "Charizard", "Base Set", "4/102", null, 89000, "2026-11-19T10:00:00Z"),
            CollectionItem(1, "sv08-059", "EN", "normal", "NM", null, 3, "Pikachu-ex", "Surging Sparks", "059/191", null, 1890, "2026-11-18T10:00:00Z"),
        )
        val wanted = listOf(
            WantedItem(77, null, "sv03.5-199", "DE", null, "offer"),
            WantedItem(78, null, "base1-4", null, null, "searching"),
            WantedItem(null, "ref-2", "sv08-059", "EN", "normal", "searching", pending = true),
        )
        val st = UiState(loading = false, account = TestAccount.ui().copy(items = items, wanted = wanted))
        var part by mutableStateOf(CollectionPart.COLLECTION)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                CollectionScreen(st, vm, part)
            }
        }
        if (!Screenshots.ready(compose)) { Log.w("CollectionScreenshot", "skipped: the test screen didn't come up"); return }
        compose.onNodeWithText("3 cards · 6 copies").assertExists()
        compose.onNodeWithText(eur((41250 * 2 + 89000 + 1890 * 3) / 100.0)).assertExists()
        Screenshots.save(compose, "collection-page.png")

        compose.onNode(hasSetTextAction()).performTextInput("base")
        compose.waitForIdle()
        compose.onNodeWithText("Charizard").assertExists()
        compose.onNodeWithText("Pikachu-ex").assertDoesNotExist()
        Screenshots.save(compose, "collection-filter.png")

        compose.runOnIdle { part = CollectionPart.MISSING }
        compose.onNodeWithText("Missing (3)").assertExists()
        compose.onNodeWithText("waiting to sync").assertExists()
        Screenshots.save(compose, "collection-missing.png")

        // a missing card's actions, then Found it
        compose.onNodeWithText("Offer").performClick()
        compose.onNodeWithText("Remove from the missing cards").assertExists()
        Screenshots.saveScreen(compose, "collection-missing-actions.png")
        compose.onNodeWithText("Found it: add to the collection").performClick()
        compose.onNodeWithText("Found it").assertExists()
        Screenshots.saveScreen(compose, "collection-found-it.png")
    }
}
