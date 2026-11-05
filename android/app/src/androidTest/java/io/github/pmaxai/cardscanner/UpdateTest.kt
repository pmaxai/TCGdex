package io.github.pmaxai.cardscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.ui.UpdateState
import io.github.pmaxai.cardscanner.ui.updateLine
import io.github.pmaxai.cardscanner.update.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone

/** The update check reads version.json from the release 'app-latest' (written by the Android build). */
@RunWith(AndroidJUnit4::class)
class UpdateTest {
    @Test
    fun versionFileIsReadAndShown() {
        val tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Vienna"))
        try {
            val r = AppUpdate.parse("""{"versionCode": 57, "versionName": "1.1.57", "built": "2026-11-06T00:52:10Z",
                "pack": "2026-11-06T00:31:02Z", "prices": "2026-11-05T22:54:32.808Z", "apk": "CardScanner.apk"}""")!!
            assertEquals(57, r.versionCode)
            assertEquals(AppUpdate.BASE + "CardScanner.apk", r.apkUrl)
            assertEquals("5 Nov", AppUpdate.day(r.prices))
            assertEquals("New version 1.1.57 · prices of 5 Nov (yours: 3 Nov)", updateLine(UpdateState(r), "2026-11-03T22:54:32Z"))
            assertEquals("New version 1.1.57 · prices of 5 Nov", updateLine(UpdateState(r), "2026-11-05T21:00:00Z"))
        } finally {
            TimeZone.setDefault(tz)
        }
        assertNull(AppUpdate.parse("not json"))
        assertNull(AppUpdate.parse("""{"versionName": "1.1.3"}"""))        // no versionCode: ignored
        assertNull(AppUpdate.day("yesterday"))
        // a file without the optional fields still works
        val bare = AppUpdate.parse("""{"versionCode": 3}""")!!
        assertTrue(bare.apkUrl.endsWith("/app-latest/CardScanner.apk"))
    }
}
