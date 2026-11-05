package io.github.pmaxai.cardscanner

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.PriceCheck
import io.github.pmaxai.cardscanner.data.Printing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The price check from the data pack: Plinfa 54/236 lost the secret rare's Cardmarket price. */
@RunWith(AndroidJUnit4::class)
class PriceCheckTest {
    @Test
    fun droppedPriceFallsBackToTcgplayerInEuros() {
        val f = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pricecheck-test.sqlite").apply { delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE cards(id TEXT, lang TEXT, set_id TEXT, local_id TEXT, name TEXT, image_url TEXT, PRIMARY KEY(id, lang))")
        db.execSQL("CREATE TABLE sets(id TEXT, lang TEXT, name TEXT, serie_id TEXT, serie_name TEXT, official INTEGER, total INTEGER, PRIMARY KEY(id, lang))")
        db.execSQL("CREATE TABLE prices(id TEXT PRIMARY KEY, updated TEXT, trend REAL, avg REAL, low REAL, avg1 REAL, avg7 REAL, avg30 REAL, trend_holo REAL, avg_holo REAL, low_holo REAL, avg30_holo REAL)")
        db.execSQL("CREATE TABLE card_info(id TEXT PRIMARY KEY, rarity TEXT, variants TEXT, category TEXT)")
        db.execSQL("CREATE TABLE tcgplayer(id TEXT PRIMARY KEY, updated TEXT, data TEXT)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE price_check(id TEXT PRIMARY KEY, status TEXT NOT NULL, cm_trend REAL, tp_lo REAL, tp_hi REAL, others TEXT)")
        db.execSQL("INSERT INTO sets VALUES ('sm12', 'de', 'Welten im Wandel', 'sm', 'Sonne & Mond', 236, 271)")
        db.execSQL("INSERT INTO sets VALUES ('sm12', 'en', 'Cosmic Eclipse', 'sm', 'Sun & Moon', 236, 271)")
        for ((id, num) in listOf("sm12-54" to "54", "sm12-239" to "239")) {
            db.execSQL("INSERT INTO cards VALUES (?, 'de', 'sm12', ?, 'Plinfa', NULL)", arrayOf(id, num))
            db.execSQL("INSERT INTO cards VALUES (?, 'en', 'sm12', ?, 'Piplup', NULL)", arrayOf(id, num))
        }
        db.execSQL("INSERT INTO card_info VALUES ('sm12-54', 'Common', '{\"normal\":true,\"reverse\":true}', 'Pokemon')")
        db.execSQL("INSERT INTO card_info VALUES ('sm12-239', 'Secret Rare', '{\"holo\":true}', 'Pokemon')")
        // the secret rare keeps its Cardmarket price; the common's was removed by the pipeline
        db.execSQL("INSERT INTO prices VALUES ('sm12-239', '2026-11-03', 39.74, 40.44, 10.0, 43.3, 39.42, 41.15, NULL, NULL, NULL, NULL)")
        db.execSQL("INSERT INTO tcgplayer VALUES ('sm12-54', 'x', '{\"normal\":0.33,\"reverse-holofoil\":0.98}')")
        db.execSQL("INSERT INTO tcgplayer VALUES ('sm12-239', 'x', '{\"holofoil\":62.97}')")
        db.execSQL("INSERT INTO meta VALUES ('usd_per_eur', '1.1000')")
        db.execSQL("INSERT INTO price_check VALUES ('sm12-54', 'dropped', 39.74, 0.3, 0.3, 'sm12-239')")
        db.execSQL("INSERT INTO price_check VALUES ('ex13-103', 'disagree', 111.5, 7400.0, 7400.0, NULL)")

        val cat = Catalog(db)
        val plinfa = cat.cardExact("sm12-54", "de")!!
        val normal = plinfa.printing(Printing.Kind.NORMAL)!!
        assertNull("no Cardmarket price left", normal.eur)
        assertEquals(0.33, normal.usd!!, 1e-9)
        assertEquals(0.30, normal.usdEur!!, 1e-9)
        assertEquals(0.30, normal.value!!, 1e-9)            // judged in euros now, not in dollars
        val check = plinfa.priceCheck!!
        assertEquals(PriceCheck.Status.DROPPED, check.status)
        assertEquals(listOf("Plinfa 239/236"), check.others)   // named in the card's language
        assertTrue(check.message, check.message.contains("Plinfa 239/236"))
        val secret = cat.cardExact("sm12-239", "de")!!
        assertNull(secret.priceCheck)
        assertEquals(39.74, secret.printing(Printing.Kind.HOLO)!!.eur!!, 1e-9)
        db.close()
    }
}
