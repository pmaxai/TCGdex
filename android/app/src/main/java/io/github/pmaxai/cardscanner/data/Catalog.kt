package io.github.pmaxai.cardscanner.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Cardmarket price guide values in EUR (null = no data). The "holo" columns are Cardmarket's reverse-holo prices. */
data class Prices(
    val updated: String?,
    val trend: Double?, val avg: Double?, val low: Double?, val avg30: Double?,
    val trendHolo: Double?, val avgHolo: Double?, val lowHolo: Double?, val avg30Holo: Double?,
) {
    val headline: Double? get() = trend ?: avg30 ?: avg
    val headlineHolo: Double? get() = trendHolo ?: avg30Holo ?: avgHolo
}

/** One printing of a card (normal, holo, reverse holo, 1st edition) with its prices. */
data class Printing(
    val kind: Kind,
    /** Cardmarket EUR: trend (or 30-day / average), 30-day average, lowest offer */
    val eur: Double?, val eur30: Double?, val eurLow: Double?,
    /** TCGplayer market price, USD */
    val usd: Double?,
) {
    enum class Kind(val label: String) {
        NORMAL("Normal"), HOLO("Holo"), REVERSE("Reverse holo"), FIRST_EDITION("1st Edition")
    }
    /** the price to judge this printing by */
    val value: Double? get() = eur ?: usd
}

/** PSA prices: eBay sold averages via PokemonPriceTracker, USD. */
data class Graded(val psa10: Double?, val psa10n: Int?, val psa9: Double?, val psa9n: Int?,
                  val psa8: Double?, val psa8n: Int?, val updated: String?)

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
    val printings: List<Printing>,
    val graded: Graded?,
) {
    val numberText: String get() = if (setTotal != null && setTotal > 0) "$number/$setTotal" else number
    val thumbnailUrl: String? get() = imageUrl?.let { "$it/low.webp" }
    val bestValue: Double? get() = printings.mapNotNull { it.value }.maxOrNull()
    fun printing(kind: Printing.Kind?): Printing? = printings.firstOrNull { it.kind == kind } ?: printings.firstOrNull()
}

class Catalog(private val db: SQLiteDatabase) {
    private val cache = HashMap<String, CardInfo?>()
    private val groupCache = HashMap<String, List<String>>()
    private val hasInfo = tableExists("card_info")
    private val hasTcg = tableExists("tcgplayer")
    private val hasGraded = tableExists("graded")

    /** The card in exactly this language, or null. */
    fun cardExact(id: String, lang: String): CardInfo? = synchronized(cache) {
        cache.getOrPut("$lang/$id") { query(id, lang) }
    }

    /** The card in this language if it exists, else English, else any language. */
    fun card(id: String, lang: String): CardInfo? = cardExact(id, lang) ?: cardExact(id, "en") ?: synchronized(cache) {
        cache.getOrPut("*/$id") { query(id, null) }
    }

    /** Card ids that share this artwork (languages with own ids, reprints). */
    fun groupMembers(group: String): List<String> = synchronized(groupCache) {
        groupCache.getOrPut(group) {
            val ids = ArrayList<String>()
            db.rawQuery("SELECT DISTINCT card_id FROM entries WHERE ${if (columns("entries").contains("grp")) "grp" else "card_id"} = ?",
                arrayOf(group)).use { c -> while (c.moveToNext()) ids.add(c.getString(0)) }
            if (group !in ids) ids.add(0, group)
            ids
        }
    }

    /** Every version of an artwork: each member card in each language the catalog has. */
    fun versions(group: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (id in groupMembers(group)) {
            db.rawQuery("SELECT lang FROM cards WHERE id = ?", arrayOf(id)).use { c ->
                while (c.moveToNext()) out.add(id to c.getString(0))
            }
        }
        return out
    }

    /** The English version of an artwork, for the translation line and as price fallback. */
    fun englishOf(group: String): CardInfo? =
        groupMembers(group).firstNotNullOfOrNull { cardExact(it, "en") }

    private fun query(id: String, lang: String?): CardInfo? {
        val sql = """
            SELECT c.id, c.lang, c.name, c.local_id, c.image_url, ${if (hasInfo) "i.rarity" else "c.rarity"},
                   s.name, s.official, s.serie_name,
                   p.updated, p.trend, p.avg, p.low, p.avg30, p.trend_holo, p.avg_holo, p.low_holo, p.avg30_holo,
                   ${if (hasInfo) "i.variants" else "NULL"}
            FROM cards c
            LEFT JOIN sets s ON s.id = c.set_id AND s.lang = c.lang
            LEFT JOIN prices p ON p.id = c.id
            ${if (hasInfo) "LEFT JOIN card_info i ON i.id = c.id" else ""}
            WHERE c.id = ? ${if (lang != null) "AND c.lang = ?" else ""}
            ORDER BY CASE c.lang WHEN 'en' THEN 0 ELSE 1 END
            LIMIT 1
        """.trimIndent()
        val args = if (lang != null) arrayOf(id, lang) else arrayOf(id)
        db.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst()) return null
            val hasPrice = !c.isNull(10) || !c.isNull(11) || !c.isNull(13) || !c.isNull(14)
            val prices = if (!hasPrice) null else Prices(
                c.getString(9), c.doubleOrNull(10), c.doubleOrNull(11), c.doubleOrNull(12), c.doubleOrNull(13),
                c.doubleOrNull(14), c.doubleOrNull(15), c.doubleOrNull(16), c.doubleOrNull(17),
            )
            val cardId = c.getString(0)
            val rarity = c.getString(5)
            return CardInfo(
                id = cardId, lang = c.getString(1), name = c.getString(2) ?: "?",
                number = c.getString(3) ?: "", imageUrl = c.getString(4), rarity = rarity,
                setName = c.getString(6) ?: "", setTotal = c.intOrNull(7), series = c.getString(8) ?: "",
                prices = prices,
                printings = printings(c.getString(18), rarity, prices, tcgplayer(cardId)),
                graded = graded(cardId),
            )
        }
    }

    /** TCGplayer market price (USD) per printing key, e.g. normal, holofoil, reverse-holofoil, 1st-edition. */
    private fun tcgplayer(id: String): Map<String, Double> {
        if (!hasTcg) return emptyMap()
        val json = db.rawQuery("SELECT data FROM tcgplayer WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return emptyMap()
        val out = HashMap<String, Double>()
        runCatching {
            val o = JSONObject(json)
            for (k in o.keys()) {
                val nested = o.optJSONObject(k)
                val p = if (nested != null) listOf("marketPrice", "midPrice", "lowPrice").firstNotNullOfOrNull { f ->
                    nested.optDouble(f).takeIf { !it.isNaN() && it > 0 }
                } else o.optDouble(k).takeIf { !it.isNaN() && it > 0 }
                if (p != null) out[k.lowercase()] = p
            }
        }
        return out
    }

    private fun graded(id: String): Graded? {
        if (!hasGraded) return null
        return db.rawQuery("SELECT psa10, psa10_n, psa9, psa9_n, psa8, psa8_n, updated FROM graded WHERE id = ?",
            arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null
            else Graded(c.doubleOrNull(0), c.intOrNull(1), c.doubleOrNull(2), c.intOrNull(3),
                c.doubleOrNull(4), c.intOrNull(5), c.getString(6))
        }
    }

    /**
     * Which printings exist and their prices. TCGplayer lists every printing it sells
     * separately, which is the more reliable "does a reverse holo exist" source; TCGdex
     * variants fill in where TCGplayer has nothing. Cardmarket's price guide has the
     * standard printing plus reverse holo (its "holo" columns).
     */
    private fun printings(variantsJson: String?, rarity: String?, p: Prices?, tcg: Map<String, Double>): List<Printing> {
        val v = runCatching { variantsJson?.let { JSONObject(it) } }.getOrNull()
        fun has(k: String) = v?.optBoolean(k, false) == true
        val holoRarity = rarity?.contains("holo", ignoreCase = true) == true
        val keys = tcg.keys
        val tcgNormal = tcg["normal"] ?: tcg["unlimited"]
        val tcgHolo = tcg["holofoil"] ?: tcg["unlimited-holofoil"]
        val tcgReverse = tcg["reverse-holofoil"] ?: tcg["reverseholofoil"]
        val tcgFirst = tcg["1st-edition-holofoil"] ?: tcg["1st-edition"] ?: tcg["1stedition"] ?: tcg["1st-edition-normal"]
        val fromTcg = keys.isNotEmpty()
        val normal = if (fromTcg) tcgNormal != null || (has("normal") && tcgHolo == null) else has("normal") || (v == null && !holoRarity)
        val holo = if (fromTcg) tcgHolo != null else has("holo") || (v == null && holoRarity)
        val reverse = has("reverse") || tcgReverse != null
        val first = has("firstEdition") || tcgFirst != null
        val out = ArrayList<Printing>()
        if (normal) out += Printing(Printing.Kind.NORMAL, p?.headline, p?.avg30, p?.low, tcgNormal)
        if (holo) {
            // Cardmarket's standard price is the holo price when the card has no non-holo printing
            out += Printing(Printing.Kind.HOLO, if (!normal) p?.headline else null, if (!normal) p?.avg30 else null,
                if (!normal) p?.low else null, tcgHolo)
        }
        if (reverse) out += Printing(Printing.Kind.REVERSE, p?.headlineHolo, p?.avg30Holo, p?.lowHolo, tcgReverse)
        if (first) out += Printing(Printing.Kind.FIRST_EDITION, null, null, null, tcgFirst)
        if (out.isEmpty()) out += Printing(Printing.Kind.NORMAL, p?.headline, p?.avg30, p?.low, tcg.values.maxOrNull())
        return out
    }

    private fun columns(table: String): Set<String> = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        val s = HashSet<String>()
        while (c.moveToNext()) s.add(c.getString(1))
        s
    }

    private fun tableExists(name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
}
