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

/** PSA population of one printing: how many copies were graded, how many got a 10 (gem rate). */
data class Population(val psaTotal: Int, val psa10: Int?, val psa9: Int?, val psa8: Int?, val gemRate: Double?,
                      val updated: String?)

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
    /** PSA prices per printing (eBay sales are split by printing); key null = printing not stated */
    val graded: Map<Printing.Kind?, Graded>,
    /** PSA population (gem rate) per printing, if the data pack has it */
    val population: Map<Printing.Kind?, Population> = emptyMap(),
    /** 'Pokemon', 'Trainer' or 'Energy' (null in older packs) */
    val category: String? = null,
    /** release date of the card's set in its language, "1999-01-09" */
    val releaseDate: String? = null,
    /** picture from the second source (pokemontcg.io) when TCGdex has none: small and large */
    val imageAlt: String? = null,
    val imageAltLarge: String? = null,
) {
    /** release year, "1999" */
    val year: String? get() = releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    val numberText: String get() = if (setTotal != null && setTotal > 0) "$number/$setTotal" else number
    val thumbnailUrl: String? get() = imageUrl?.let { "$it/low.webp" } ?: imageAlt
    /** the large image (fullscreen view) */
    val highUrl: String? get() = imageUrl?.let { "$it/high.webp" } ?: imageAltLarge ?: imageAlt
    val bestValue: Double? get() = printings.mapNotNull { it.value }.maxOrNull()
    fun printing(kind: Printing.Kind?): Printing? = printings.firstOrNull { it.kind == kind } ?: printings.firstOrNull()

    /** PSA prices of this printing only; a row without a stated printing counts for the card's main printing */
    fun gradedFor(kind: Printing.Kind?): Graded? =
        graded[kind] ?: graded[null]?.takeIf { kind == null || kind == printings.firstOrNull()?.kind }

    fun populationFor(kind: Printing.Kind?): Population? =
        population[kind] ?: population[null]?.takeIf { kind == null || kind == printings.firstOrNull()?.kind }

    /** printings with PSA sales, for "no PSA sales for this printing" hints */
    val gradedPrintings: Set<Printing.Kind?> get() = graded.keys
}

class Catalog(private val db: SQLiteDatabase) {
    private val cache = HashMap<String, CardInfo?>()
    private val groupCache = HashMap<String, List<String>>()
    private val hasInfo = tableExists("card_info")
    private val hasTcg = tableExists("tcgplayer")
    private val hasGraded = tableExists("graded")
    private val hasPopulation = tableExists("population")
    /** the data pack knows each card's category (for skipping energy cards) */
    val hasCategory = hasInfo && columns("card_info").contains("category")
    private val hasReleaseDate = columns("sets").contains("release_date")
    private val hasAltImages = columns("cards").contains("image_alt")
    private val hasGroups = columns("entries").contains("grp")

    /** The artwork group of a card (its own id when it has no picture in the index). */
    fun groupOf(cardId: String): String =
        db.rawQuery("SELECT ${if (hasGroups) "grp" else "card_id"} FROM entries WHERE card_id = ? LIMIT 1", arrayOf(cardId))
            .use { c -> if (c.moveToFirst()) c.getString(0) else cardId }

    /**
     * Cards whose name (any language), number or set matches every word of [query], best
     * first: "whimsicott 019", "elfun", "charizard base", "GG01", "sv06 34". One row per
     * card, in the language whose name matched (English when several did). Also finds
     * cards without a picture, which the camera can't recognise.
     */
    fun search(query: String, limit: Int = 50): List<CardInfo> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
        if (words.isEmpty()) return emptyList()
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        fun like(w: String) = w.replace("\\", "").replace("%", "").replace("_", "")
        for (w in words) {
            val num = w.substringBefore('/')
            val conds = mutableListOf("c.name LIKE ?", "s.name LIKE ?", "c.set_id LIKE ?")
            args += "%${like(w)}%"; args += "%${like(w)}%"; args += "${like(w)}%"
            if (num.any { it.isDigit() }) {
                conds += "ltrim(upper(c.local_id), '0') = ltrim(upper(?), '0')"
                args += num
            }
            where += conds.joinToString(" OR ", "(", ")")
        }
        class Row(val id: String, val lang: String, val name: String, val number: String, val setId: String,
                  val setName: String, val date: String)
        val rows = ArrayList<Row>()
        db.rawQuery("""
            SELECT c.id, c.lang, c.name, c.local_id, c.set_id, s.name, ${if (hasReleaseDate) "s.release_date" else "''"}
            FROM cards c LEFT JOIN sets s ON s.id = c.set_id AND s.lang = c.lang
            WHERE ${where.joinToString(" AND ")}
            ORDER BY ${if (hasReleaseDate) "s.release_date" else "c.id"} DESC LIMIT 800
        """.trimIndent(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) rows += Row(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "")
        }
        fun digits(s: String) = s.trimStart('0').uppercase()
        fun score(r: Row): Int = words.sumOf { w ->
            val name = r.name.lowercase()
            val num = w.substringBefore('/')
            maxOf(
                when {
                    name == w -> 6
                    name.startsWith(w) -> 5
                    Regex("(^|[^\\p{L}])${Regex.escape(w)}").containsMatchIn(name) -> 4
                    name.contains(w) -> 2
                    else -> 0
                },
                if (num.any { it.isDigit() } && digits(r.number) == digits(num)) 4 else 0,
                when { r.setId.lowercase() == w -> 4; r.setId.lowercase().startsWith(w) -> 3; else -> 0 },
                if (r.setName.lowercase().contains(w)) 1 else 0,
            )
        }
        val langRank = mapOf("en" to 0, "de" to 1)
        return rows.groupBy { it.id }.values
            .map { same -> same.maxWith(compareBy<Row> { score(it) }.thenBy { -(langRank[it.lang] ?: 2) }) }
            .sortedWith(compareByDescending<Row> { score(it) }.thenByDescending { it.date })
            .take(limit)
            .mapNotNull { cardExact(it.id, it.lang) }
    }

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
                   ${if (hasInfo) "i.variants" else "NULL"}, ${if (hasCategory) "i.category" else "NULL"},
                   ${if (hasReleaseDate) "s.release_date" else "NULL"},
                   ${if (hasAltImages) "c.image_alt, c.image_alt_large" else "NULL, NULL"}
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
                population = population(cardId),
                category = c.getString(19),
                releaseDate = c.getString(20)?.takeIf { it.isNotBlank() },
                imageAlt = c.getString(21), imageAltLarge = c.getString(22),
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

    private val gradedByPrinting = hasGraded && columns("graded").contains("printing")

    private fun kindOf(code: String?): Printing.Kind? = when (code) {
        "normal" -> Printing.Kind.NORMAL
        "holo" -> Printing.Kind.HOLO
        "reverse" -> Printing.Kind.REVERSE
        "1st" -> Printing.Kind.FIRST_EDITION
        else -> null
    }

    private fun population(id: String): Map<Printing.Kind?, Population> {
        if (!hasPopulation) return emptyMap()
        val out = LinkedHashMap<Printing.Kind?, Population>()
        db.rawQuery("SELECT printing, psa_total, psa10, psa9, psa8, gem_rate, updated FROM population WHERE id = ?",
            arrayOf(id)).use { c ->
            while (c.moveToNext()) {
                val total = c.intOrNull(1) ?: continue
                out[kindOf(c.getString(0))] = Population(total, c.intOrNull(2), c.intOrNull(3), c.intOrNull(4),
                    c.doubleOrNull(5), c.getString(6))
            }
        }
        return out
    }

    /**
     * Card ids that are energy cards. commonOnly: just the cheap ones from booster packs
     * (rarity Common / None), so gold and secret rare energies are still recognised.
     */
    fun energyIds(commonOnly: Boolean): Set<String> {
        if (!hasCategory) return emptySet()
        val out = HashSet<String>()
        db.rawQuery("SELECT id, rarity FROM card_info WHERE category = 'Energy'", null).use { c ->
            while (c.moveToNext()) {
                val r = c.getString(1)?.trim()?.lowercase()
                if (!commonOnly || r == null || r.isEmpty() || r == "common" || r == "none" || r == "c") out.add(c.getString(0))
            }
        }
        return out
    }

    private fun graded(id: String): Map<Printing.Kind?, Graded> {
        if (!hasGraded) return emptyMap()
        val sql = "SELECT ${if (gradedByPrinting) "printing" else "''"}, psa10, psa10_n, psa9, psa9_n, psa8, psa8_n, updated " +
            "FROM graded WHERE id = ?"
        val out = LinkedHashMap<Printing.Kind?, Graded>()
        db.rawQuery(sql, arrayOf(id)).use { c ->
            while (c.moveToNext()) {
                val kind = when (c.getString(0)) {
                    "normal" -> Printing.Kind.NORMAL
                    "holo" -> Printing.Kind.HOLO
                    "reverse" -> Printing.Kind.REVERSE
                    "1st" -> Printing.Kind.FIRST_EDITION
                    else -> null
                }
                out[kind] = Graded(c.doubleOrNull(1), c.intOrNull(2), c.doubleOrNull(3), c.intOrNull(4),
                    c.doubleOrNull(5), c.intOrNull(6), c.getString(7))
            }
        }
        return out
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
