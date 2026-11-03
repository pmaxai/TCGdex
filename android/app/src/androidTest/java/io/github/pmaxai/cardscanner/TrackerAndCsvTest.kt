package io.github.pmaxai.cardscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.data.CardInfo
import io.github.pmaxai.cardscanner.data.Graded
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.ui.Confidence
import io.github.pmaxai.cardscanner.ui.PackCsv
import io.github.pmaxai.cardscanner.ui.SessionItem
import io.github.pmaxai.cardscanner.ui.Settings
import io.github.pmaxai.cardscanner.ui.Shown
import io.github.pmaxai.cardscanner.vision.Readings
import io.github.pmaxai.cardscanner.vision.ScanTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TrackerAndCsvTest {
    private val sure = 0.085f
    private val likely = 0.038f

    @Test
    fun instantTakesTheFirstGreenFrame() {
        val t = ScanTracker(sure, likely).apply { delayMs = 0 }
        assertTrue(t.update("a", 0.2f, now = 1000))
        assertEquals("a", t.shown)
        assertFalse(t.update("a", 0.2f, now = 1100))          // same card again: nothing new
        assertTrue(t.update("b", 0.2f, now = 1200))           // another card: taken at once
        assertEquals("b", t.shown)
    }

    @Test
    fun waitsUntilTheFrameWasGreenLongEnough() {
        val t = ScanTracker(sure, likely).apply { delayMs = 600 }
        assertFalse(t.update("a", 0.2f, now = 0))
        assertFalse(t.update("a", 0.2f, now = 250))
        assertFalse(t.update("a", 0.01f, now = 400))          // one blurry frame is forgiven
        assertTrue(t.update("a", 0.2f, now = 650))
        assertEquals("a", t.shown)
        // two frames that aren't green break the streak: it starts again
        assertFalse(t.update("b", 0.2f, now = 1000))
        assertFalse(t.update(null, 0f, now = 1200))
        assertFalse(t.update(null, 0f, now = 1400))
        assertFalse(t.update("b", 0.2f, now = 1500))
        assertFalse(t.update("b", 0.2f, now = 1900))
        assertTrue(t.update("b", 0.2f, now = 2150))
    }

    @Test
    fun yellowOnlyCardsAreStillTakenButNotBeforeTheDelay() {
        val t = ScanTracker(sure, likely).apply { delayMs = 1000 }
        assertFalse(t.update("a", 0.05f, now = 0))
        assertFalse(t.update("a", 0.05f, now = 300))
        assertFalse(t.update("a", 0.05f, now = 600))          // three frames, but only 0.6 s
        assertTrue(t.update("a", 0.05f, now = 1000))
        assertNull(t.streakMillis(now = 1000))
    }

    @Test
    fun guideFrameFitsEveryBoxShapeAndSize() {
        for (w in listOf(400, 960, 1067, 1280, 1600)) for (h in listOf(400, 960, 1004, 1280)) for (size in listOf(50, 60, 70, 80, 90)) {
            val prior = Readings.guidePrior(w, h, size / 100.0)
            val q = Readings.guideQuad(w, h, prior)
            val gw = q[1].x - q[0].x
            val gh = q[3].y - q[0].y
            assertTrue("inside $w x $h at $size%", q[0].x >= 0 && q[0].y >= 0 && q[2].x <= w && q[2].y <= h)
            assertTrue("card shape", kotlin.math.abs(gw / gh - 63.0 / 88.0) < 0.01)
            assertTrue("not wider than 90%", gw <= 0.9 * w + 1e-6)
            // the asked height whenever the box is wide enough for it
            if ((size / 100.0 + 0.1).coerceAtMost(0.9) * w >= size / 100.0 * h * 63 / 88) assertEquals(size / 100.0 * h, gh, 1.0)
        }
        // the old default (portrait 4:3 frame, 70%) is unchanged
        assertEquals(0.7, Readings.guidePrior(960, 1280).h, 1e-9)
        assertEquals(Readings.guidePrior(960, 1280).h, Readings.guidePrior(960, 1280, 0.7).h, 1e-12)
    }

    private fun card(id: String, lang: String, name: String, printings: List<Printing>,
                     graded: Map<Printing.Kind?, Graded> = emptyMap()) =
        CardInfo(id, lang, name, "034", "Twilight Masquerade", 167, "Scarlet & Violet", "Common", null, null, printings, graded,
            releaseDate = "2024-05-24")

    @Test
    fun packCsvHasOneRowPerCardAndRegionalNumbers() {
        val printings = listOf(Printing(Printing.Kind.NORMAL, 0.05, 0.06, 0.02, 0.03),
                               Printing(Printing.Kind.REVERSE, 0.07, 0.09, 0.05, 0.22))
        val graded = mapOf<Printing.Kind?, Graded>(Printing.Kind.REVERSE to Graded(40.0, 2, null, null, null, null, "2026-11-03"))
        val de = card("sv06-034", "de", "Flampion", printings, graded)
        val en = card("sv06-034", "en", "Darumaka", printings, graded)
        val shown = Shown("sv06-034", de, en, de, Printing.Kind.REVERSE, true, null, Confidence.SURE, emptyList(), emptyList(),
            null, false, false, null, 7)
        val items = listOf(SessionItem(7, 1_762_140_000_000, shown))
        val csv = PackCsv.build(items, Settings(threshold = 0.0, title = "Pack; one"), Locale.GERMANY)
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertTrue(lines[0].startsWith("No;Scanned;Name;English name;Set;Number"))
        val row = lines[1].split(";")
        assertEquals("Flampion", row[2]); assertEquals("Darumaka", row[3]); assertEquals("034/167", row[5])
        assertEquals("Reverse holo", row[8]); assertEquals("0,07", row[9]); assertEquals("EUR", row[10])
        assertEquals("40,00", row[15]); assertEquals("2", row[16])          // PSA 10 of the reverse holo
        assertTrue(csv.contains("Pack value EUR;0,07"))
        assertTrue(lines[0].contains(";Released;") && row.contains("2024-05-24"))
        assertEquals("2024", de.year)
        assertTrue(csv.contains("\"Pack; one\""))                            // the separator inside a cell is quoted
        val us = PackCsv.build(items, Settings(), Locale.US)
        assertTrue(us.contains("Reverse holo,0.07,EUR"))
        assertTrue(PackCsv.fileName(Settings(title = "My Channel!")).matches(Regex("My_Channel_pack_\\d{4}-\\d{2}-\\d{2}_\\d{4}\\.csv")))
    }

    @Test
    fun psaPricesFollowThePrinting() {
        val printings = listOf(Printing(Printing.Kind.NORMAL, 0.05, null, null, null),
                               Printing(Printing.Kind.REVERSE, 0.07, null, null, null))
        val normalPsa = Graded(12.0, 3, null, null, null, null, null)
        val reversePsa = Graded(40.0, 2, null, null, null, null, null)
        val c = card("sv06-037", "en", "Lampent", printings, mapOf(Printing.Kind.NORMAL to normalPsa, Printing.Kind.REVERSE to reversePsa))
        fun shownWith(kind: Printing.Kind) = Shown("sv06-037", c, null, c, kind, true, null, Confidence.SURE, emptyList(),
            emptyList(), null, false, false, null, 1)
        assertEquals(12.0, shownWith(Printing.Kind.NORMAL).graded?.psa10)
        assertEquals(40.0, shownWith(Printing.Kind.REVERSE).graded?.psa10)
        // only the normal printing has sales: the reverse holo shows none (not the normal's price)
        val onlyNormal = c.copy(graded = mapOf(Printing.Kind.NORMAL to normalPsa))
        val rev = Shown("sv06-037", onlyNormal, null, onlyNormal, Printing.Kind.REVERSE, true, null, Confidence.SURE,
            emptyList(), emptyList(), null, false, false, null, 1)
        assertNull(rev.graded)
        assertTrue(rev.gradedOnlyOtherPrintings)
        // older data without the printing: counts for the card's main printing only
        val old = c.copy(graded = mapOf<Printing.Kind?, Graded>(null to normalPsa))
        assertEquals(12.0, old.gradedFor(Printing.Kind.NORMAL)?.psa10)
        assertNull(old.gradedFor(Printing.Kind.REVERSE))
    }
}
