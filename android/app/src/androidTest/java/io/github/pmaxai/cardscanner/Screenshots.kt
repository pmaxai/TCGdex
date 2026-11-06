package io.github.pmaxai.cardscanner

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.services.storage.TestStorage

/**
 * Pictures from the emulator tests (the CI prints them into its log). Right after the emulator
 * boots, the test window can take a few seconds to get a surface: wait and try again, and skip
 * the picture rather than fail the build (the pictures are for looking at, not a check).
 */
object Screenshots {
    private const val TAG = "Screenshots"

    /** Waits until the test screen is up; false: it didn't come up (the caller skips its pictures). */
    fun ready(compose: ComposeContentTestRule): Boolean = runCatching {
        compose.waitUntil(15_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }.isSuccess

    /** Saves the screen as [name]; true when it worked. */
    fun save(compose: ComposeContentTestRule, name: String): Boolean {
        repeat(10) { attempt ->
            compose.waitForIdle()
            val bmp = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }
                .onFailure { Log.w(TAG, "$name: attempt ${attempt + 1}: ${it.message}") }.getOrNull()
            if (bmp != null) {
                TestStorage().openOutputFile(name).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return true
            }
            Thread.sleep(700)
        }
        Log.w(TAG, "$name skipped: the screen couldn't be captured")
        return false
    }
}
