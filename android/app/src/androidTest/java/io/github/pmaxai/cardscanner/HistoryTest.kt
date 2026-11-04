package io.github.pmaxai.cardscanner

import android.database.sqlite.SQLiteDatabase
import android.util.Half
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.Printing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

@RunWith(AndroidJUnit4::class)
class HistoryTest {
    /** what the pipeline writes: little-endian float16, zlib-compressed */
    private fun encode(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putShort(Half.toHalf(it)) }
        val d = Deflater(6)
        d.setInput(bb.array())
        d.finish()
        val out = ByteArray(v.size * 4 + 64)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    @Test
    fun historyIsReadAndMappedPerPrintingAndCurrency() {
        val f = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "history-test.sqlite").apply { delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE history(id TEXT, field TEXT, data BLOB, PRIMARY KEY(id, field))")
        db.execSQL("INSERT INTO meta VALUES ('history_dates', '2026-11-01,2026-11-02,2026-11-03,2026-11-04')")
        db.execSQL("INSERT INTO meta VALUES ('history_rates', '1.1000,,1.2000,1.2000')")
        val nan = Float.NaN
        db.execSQL("INSERT INTO history VALUES ('sv06-034', 'cm_trend', ?)", arrayOf(encode(floatArrayOf(nan, 0.08f, 0.09f, 0.1f))))
        db.execSQL("INSERT INTO history VALUES ('sv06-034', 'cm_trend_h', ?)", arrayOf(encode(floatArrayOf(0.2f, 0.21f, nan, 0.25f))))
        db.execSQL("INSERT INTO history VALUES ('sv06-034', 'tp_normal', ?)", arrayOf(encode(floatArrayOf(1.1f, 1.1f, 1.2f, 1.2f))))
        val h = Catalog(db).history("sv06-034")!!
        assertEquals(4, h.dates.size)
        assertEquals("2026-11-04", h.dates.last().toString())
        assertTrue(h.series.getValue("cm_trend")[0].isNaN())
        assertEquals(0.09f, h.series.getValue("cm_trend")[2], 1e-3f)
        // printings: reverse holo = Cardmarket's holo price; a holo next to a normal printing has none
        assertEquals(0.25f, h.eur(Printing.Kind.REVERSE, hasNormal = true)!![3], 1e-3f)
        assertEquals(0.1f, h.eur(Printing.Kind.NORMAL, hasNormal = true)!![3], 1e-3f)
        assertNull(h.eur(Printing.Kind.HOLO, hasNormal = true))
        assertEquals(0.1f, h.eur(Printing.Kind.HOLO, hasNormal = false)!![3], 1e-3f)
        // USD in EUR at each day's rate; a day without a rate takes the latest
        val usd = h.usdInEur(Printing.Kind.NORMAL)!!
        assertEquals(1.0f, usd[0], 1e-3f)          // 1.10 $ / 1.10
        assertEquals(1.1f / 1.2f, usd[1], 1e-3f)   // no rate that day: 1.20
        assertEquals(1.0f, usd[3], 1e-3f)
        assertNull("no history for this card", Catalog(db).history("sv06-999"))
        db.close()
    }
}
