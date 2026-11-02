package io.github.pmaxai.cardscanner.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Cardmarket price guide values in EUR (null = no data). */
data class Prices(
    val updated: String?,
    val trend: Double?, val avg: Double?, val low: Double?, val avg30: Double?,
    val trendHolo: Double?, val avgHolo: Double?, val lowHolo: Double?, val avg30Holo: Double?,
) {
    /** The single number to judge a card by: trend, falling back to the average. */
    val headline: Double? get() = trend ?: avg30 ?: avg
    val headlineHolo: Double? get() = trendHolo ?: avg30Holo ?: avgHolo
    val best: Double? get() = listOfNotNull(headline, headlineHolo).maxOrNull()
}

data class CardInfo(
    val id: String,
    val lang: String,
    val name: String,
    val number: String,
    val setName: String,
    val setTotal: Int?,
    val series: String,
    val rarity: String?,
    val imageUrl: String?,
    val prices: Prices?,
) {
    val numberText: String get() = if (setTotal != null && setTotal > 0) "$number/$setTotal" else number
    val thumbnailUrl: String? get() = imageUrl?.let { "$it/low.webp" }
}

class Catalog(private val db: SQLiteDatabase) {
    private val cache = HashMap<String, CardInfo?>()

    fun card(id: String, lang: String): CardInfo? = cache.getOrPut("$lang/$id") { query(id, lang) ?: query(id, null) }

    private fun query(id: String, lang: String?): CardInfo? {
        val sql = """
            SELECT c.id, c.lang, c.name, c.local_id, c.image_url, c.rarity,
                   s.name, s.official, s.serie_name,
                   p.updated, p.trend, p.avg, p.low, p.avg30, p.trend_holo, p.avg_holo, p.low_holo, p.avg30_holo
            FROM cards c
            LEFT JOIN sets s ON s.id = c.set_id AND s.lang = c.lang
            LEFT JOIN prices p ON p.id = c.id
            WHERE c.id = ? ${if (lang != null) "AND c.lang = ?" else ""}
            ORDER BY CASE c.lang WHEN 'en' THEN 0 ELSE 1 END
            LIMIT 1
        """.trimIndent()
        val args = if (lang != null) arrayOf(id, lang) else arrayOf(id)
        db.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst()) return null
            val hasPrice = !c.isNull(9) || !c.isNull(10) || !c.isNull(11)
            return CardInfo(
                id = c.getString(0), lang = c.getString(1), name = c.getString(2) ?: "?",
                number = c.getString(3) ?: "", imageUrl = c.getString(4), rarity = c.getString(5),
                setName = c.getString(6) ?: "", setTotal = c.intOrNull(7), series = c.getString(8) ?: "",
                prices = if (!hasPrice) null else Prices(
                    c.getString(9), c.doubleOrNull(10), c.doubleOrNull(11), c.doubleOrNull(12), c.doubleOrNull(13),
                    c.doubleOrNull(14), c.doubleOrNull(15), c.doubleOrNull(16), c.doubleOrNull(17),
                ),
            )
        }
    }

    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
}
