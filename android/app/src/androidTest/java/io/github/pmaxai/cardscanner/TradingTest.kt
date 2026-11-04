package io.github.pmaxai.cardscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.trading.Cart
import io.github.pmaxai.cardscanner.trading.CartLine
import io.github.pmaxai.cardscanner.trading.Condition
import io.github.pmaxai.cardscanner.trading.LineStatus
import io.github.pmaxai.cardscanner.trading.Reference
import io.github.pmaxai.cardscanner.trading.TradingBook
import io.github.pmaxai.cardscanner.trading.TradingCsv
import io.github.pmaxai.cardscanner.trading.TradingMath
import io.github.pmaxai.cardscanner.trading.TradingStore
import io.github.pmaxai.cardscanner.trading.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TradingTest {
    @Test
    fun verdictBandsAsAgreed() {
        fun v(askingShare: Double) = TradingMath.verdict(askingShare * 50.0, 50.0)
        assertEquals(Verdict.GREAT, v(0.80))
        assertEquals(Verdict.GOOD, v(0.81))
        assertEquals(Verdict.GOOD, v(0.95))
        assertEquals(Verdict.FAIR, v(0.96))
        assertEquals(Verdict.FAIR, v(1.10))
        assertEquals(Verdict.EXPENSIVE, v(1.11))
        assertNull(TradingMath.verdict(10.0, 0.0))
    }

    @Test
    fun offerIsRoundedDown() {
        assertEquals(44.0, TradingMath.offer(52.0), 1e-9)      // 44.20
        assertEquals(0.4, TradingMath.offer(0.5), 1e-9)        // 0.425: 10 cent steps under 5 EUR
        assertEquals(4.9, TradingMath.offer(5.88), 1e-9)       // 4.998
        assertEquals(50.0, TradingMath.offer(60.0), 1e-9)      // 51: 5 EUR steps from 50 EUR
        assertEquals(85.0, TradingMath.offer(100.0), 1e-9)
        assertEquals(52.0, TradingMath.walkAway(52.0), 1e-9)
    }

    @Test
    fun marketValueFollowsConditionAndReference() {
        val p = Printing(Printing.Kind.NORMAL, eur = 61.18, eur30 = 50.0, eurLow = 20.0, usd = 70.0)
        assertEquals(61.18, TradingMath.reference(p, Reference.TREND)!!, 1e-9)
        assertEquals(50.0, TradingMath.reference(p, Reference.AVG30)!!, 1e-9)
        assertEquals(20.0, TradingMath.reference(p, Reference.LOW)!!, 1e-9)
        assertEquals(52.0, TradingMath.market(61.18, Condition.EX), 1e-9)
        assertEquals(18.35, TradingMath.market(61.18, Condition.PO), 1e-9)
        assertEquals(0.2236, TradingMath.trend(p)!!, 1e-4)
        assertTrue("lowest under half the trend", TradingMath.fewSales(p))
        assertFalse(TradingMath.fewSales(p.copy(eurLow = 40.0)))
        assertNull("no Cardmarket price", TradingMath.reference(p.copy(eur = null, eur30 = null, eurLow = null), Reference.TREND))
    }

    private fun line(id: Long, name: String, ref: Double, c: Condition, asking: Double?, negotiated: Double?,
                     status: LineStatus = LineStatus.CONSIDERING, qty: Int = 1) =
        CartLine(id, "sv-$id", "en", "sv-$id", name, "Set · $id · EN · 2024", null, "NORMAL", ref, c, qty, asking, negotiated, status, id)

    private fun booth4() = Cart(1, "Booth 4", 0, listOf(
        line(1, "Charizard ex", 52.0, Condition.NM, 45.0, 40.0),
        line(2, "Umbreon V", 51.76, Condition.EX, 42.0, 36.0),
        line(3, "Pikachu", 34.0, Condition.NM, 33.0, 29.0),
        line(4, "Dropped one", 99.0, Condition.NM, 80.0, null, LineStatus.DROPPED),
    ))

    @Test
    fun cartTotalsMatchTheConceptExample() {
        val c = booth4()
        assertEquals(3, c.cards)
        assertEquals(130.0, c.marketTotal, 1e-9)
        assertEquals(120.0, c.askingTotal, 1e-9)
        assertEquals(105.0, c.payTotal, 1e-9)
        assertEquals(25.0, c.savedVsMarket, 1e-9)
        assertEquals(15.0, c.savedVsAsking, 1e-9)
        assertEquals(0, c.unpriced)
        assertEquals(Verdict.GOOD, TradingMath.verdict(45.0, 52.0))     // asking 45 of 52 = 87 %
        assertEquals(Verdict.GREAT, c.lines[0].verdict)                    // negotiated 40 of 52 = 77 %
        // a line without a price counts for the market value, not for what you pay
        val more = c.copy(lines = c.lines + line(5, "Unpriced", 10.0, Condition.NM, null, null, qty = 2))
        assertEquals(150.0, more.marketTotal, 1e-9)
        assertEquals(105.0, more.payTotal, 1e-9)
        assertEquals(1, more.unpriced)
    }

    @Test
    fun cartsKeepTheirNumbersAndSurviveARestart() {
        var b = TradingBook().newCart(1).newCart(2)
        assertEquals(listOf("Booth 1", "Booth 2"), b.carts.map { it.name })
        assertEquals("Booth 2", b.active?.name)
        b = b.replace(booth4().copy(id = b.active!!.id, name = "Max, table 14"))
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "trading-test").apply { deleteRecursively() }
        val store = TradingStore(File(dir, "trading.json"))
        store.save(b)
        val back = store.load()
        assertEquals(b, back)
        // the renamed cart doesn't use up a number: Booth 1 and "Max, table 14" -> the next one is Booth 2
        assertEquals("Booth 2", back.newCart(3).active?.name)
        // no cart yet: one is made on the first + Cart
        val (made, cart) = TradingBook().withActive(5)
        assertEquals("Booth 1", cart.name)
        assertEquals(cart.id, made.activeId)
    }

    @Test
    fun dealsCsvUsesTheRegionalFormat() {
        val c = booth4().copy(paidAt = 1_762_200_000_000, payment = "Cash")
        val de = TradingCsv.build(listOf(c), Locale.GERMANY)
        val lines = de.removePrefix("﻿").split("\r\n")
        assertTrue(lines[0].startsWith("Booth;Paid;Payment;Status;Name"))
        assertTrue(lines[1].startsWith("Booth 4;"))
        assertTrue(lines[1].contains(";52,00;45,00;40,00;40,00;Great deal"))
        assertTrue(de.contains("Booth 4;") && de.contains(";130,00;120,00;105,00;25,00"))
        assertTrue(TradingCsv.build(listOf(c), Locale.US).contains(",52.00,45.00,40.00,"))
    }
}
