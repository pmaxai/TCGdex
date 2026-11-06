package io.github.pmaxai.cardscanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import io.github.pmaxai.cardscanner.data.Catalog
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.ui.Gold
import io.github.pmaxai.cardscanner.ui.Ink
import io.github.pmaxai.cardscanner.ui.Panel
import io.github.pmaxai.cardscanner.ui.PriceChartPanel
import io.github.pmaxai.cardscanner.ui.PriceSpark
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Draws the price line and the full chart for a few cards of the bundled data pack and saves
 * them as pictures (the CI uploads them), to look at the chart with real history.
 */
@RunWith(AndroidJUnit4::class)
class ChartScreenshotTest {
    @get:Rule val compose = createComposeRule()

    companion object {
        private const val TAG = "ChartScreenshot"
        private var db: SQLiteDatabase? = null

        private fun catalog(): Catalog {
            val open = db
            if (open != null) return Catalog(open)
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val f = File(ctx.cacheDir, "chart-test-catalog.sqlite")
            if (!f.exists()) ctx.assets.open("pack/catalog.sqlite").use { i -> f.outputStream().use { i.copyTo(it, 1 shl 20) } }
            return Catalog(SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY).also { db = it })
        }

        @AfterClass @JvmStatic
        fun close() { db?.close() }
    }

    private fun shoot(name: String, id: String, kinds: List<Printing.Kind>, kind: Printing.Kind?, days: Int) {
        val h = catalog().history(id)
        if (h == null) { Log.i(TAG, "$id has no price history in this pack"); return }
        val hasNormal = Printing.Kind.NORMAL in kinds
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Panel)) {
                Box(Modifier.fillMaxSize().background(Ink)) {
                    Column(Modifier.width(400.dp).padding(8.dp)) {
                        Column(Modifier.background(Panel).padding(12.dp)) { PriceSpark(h, kind, hasNormal) {} }
                        Spacer(Modifier.height(12.dp))
                        PriceChartPanel(id, h, kinds, kind, hasNormal, initialDays = days) {}
                    }
                }
            }
        }
        if (!Screenshots.ready(compose)) { Log.w(TAG, "chart-$name.png skipped: the test screen didn't come up"); return }
        if (Screenshots.save(compose, "chart-$name.png")) Log.i(TAG, "chart-$name.png: $id, ${h.series.keys.sorted()}")
    }

    @Test fun charizardBaseSetYear() = shoot("base1-4-1y", "base1-4", listOf(Printing.Kind.HOLO, Printing.Kind.FIRST_EDITION), Printing.Kind.HOLO, 365)
    @Test fun umbreonSixMonths() = shoot("swsh7-215-6m", "swsh7-215", listOf(Printing.Kind.HOLO), Printing.Kind.HOLO, 180)
    @Test fun charizard151ThreeMonths() = shoot("sv03.5-199-3m", "sv03.5-199", listOf(Printing.Kind.HOLO), Printing.Kind.HOLO, 90)
    @Test fun commonReverseHolo() = shoot("sv06-034-rev-3m", "sv06-034", listOf(Printing.Kind.NORMAL, Printing.Kind.REVERSE), Printing.Kind.REVERSE, 90)
}
