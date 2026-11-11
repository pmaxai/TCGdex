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
    /** the TCGplayer price in EUR at the data pack's exchange rate (null: no rate in the pack) */
    val usdEur: Double? = null,
) {
    enum class Kind(val label: String) {
        NORMAL("Normal"), HOLO("Holo"), REVERSE("Reverse holo"), FIRST_EDITION("1st Edition")
    }
    /** the price to judge this printing by: Cardmarket, else TCGplayer (in EUR where the rate is known) */
    val value: Double? get() = eur ?: usdEur ?: usd
}

/**
 * What the data pipeline's price check found (pricecheck.py). TCGdex links some cards to the
 * wrong Cardmarket product, so they carry another card's prices.
 */
data class PriceCheck(
    val status: Status,
    /** the Cardmarket trend in question, EUR */
    val cmTrend: Double?,
    /** the card's TCGplayer prices (lowest and highest printing, not reverse holo), EUR */
    val tpLo: Double?, val tpHi: Double?,
    /** the card(s) the Cardmarket price is shared with, e.g. "Plinfa 239/236" */
    val others: List<String>,
) {
    enum class Status {
        /** the Cardmarket price was another card's and was removed: TCGplayer is shown */
        DROPPED,
        /** the Cardmarket price is shared with other cards and may be theirs */
        UNCERTAIN,
        /** Cardmarket and TCGplayer are more than 4x apart */
        DISAGREE,
    }

    /** a sentence for under the price */
    val message: String get() = when (status) {
        Status.DROPPED -> "No reliable Cardmarket price for this card" +
            (others.firstOrNull()?.let { " (the database gave it the price of $it)" } ?: "")
        Status.UNCERTAIN -> "This Cardmarket price is shared with " + others.take(2).joinToString(", ").ifEmpty { "another card" } +
            " and may be theirs – check Cardmarket"
        Status.DISAGREE -> "Cardmarket and TCGplayer disagree" +
            (tpHi?.let { " (TCGplayer ≈ " + String.format(java.util.Locale.getDefault(), "%.2f €", it) + ")" } ?: "") +
            " – check before you pay"
    }
}

/**
 * A card's price history: one shared calendar (weekly for a year, then every day of the last
 * 90 days), a series per price (NaN = no price that day), and the ECB's USD per EUR each day.
 */
class PriceHistory(val dates: List<java.time.LocalDate>, val usdPerEur: List<Double?>, val series: Map<String, FloatArray>) {
    val lastRate: Double? get() = usdPerEur.lastOrNull { it != null }

    /** Cardmarket trend (EUR) of a printing, picked like the price panel picks its price */
    fun eur(kind: Printing.Kind?, hasNormal: Boolean): FloatArray? = when (kind) {
        Printing.Kind.REVERSE -> series["cm_trend_h"]
        Printing.Kind.HOLO -> if (hasNormal) null else series["cm_trend"]
        Printing.Kind.FIRST_EDITION -> null
        else -> series["cm_trend"]
    }

    /** TCGplayer market price (USD) of a printing */
    fun usd(kind: Printing.Kind?): FloatArray? = when (kind) {
        Printing.Kind.HOLO -> series["tp_holo"]
        Printing.Kind.REVERSE -> series["tp_reverse"]
        Printing.Kind.FIRST_EDITION -> series["tp_first"]
        else -> series["tp_normal"]
    }

    /** TCGplayer converted to EUR at each day's ECB rate (the latest rate where a day has none) */
    fun usdInEur(kind: Printing.Kind?): FloatArray? {
        val a = usd(kind) ?: return null
        val fallback = lastRate
        return FloatArray(a.size) { i ->
            val r = usdPerEur.getOrNull(i) ?: fallback
            if (r != null && r > 0 && !a[i].isNaN()) (a[i] / r).toFloat() else Float.NaN
        }
    }

    companion object {
        /**
         * zlib-compressed little-endian values (the pipeline's history.write_app_history): float16,
         * or float32 for a series of [size] values with a price above float16's range (65,504).
         */
        fun decode(blob: ByteArray, size: Int = -1): FloatArray {
            val inf = java.util.zip.Inflater()
            inf.setInput(blob)
            val out = java.io.ByteArrayOutputStream(blob.size * 4)
            val buf = ByteArray(4096)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            val bb = java.nio.ByteBuffer.wrap(out.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (size > 0 && out.size() == size * 4) return FloatArray(size) { bb.getFloat() }
            return FloatArray(out.size() / 2) { android.util.Half.toFloat(bb.getShort()) }
        }
    }
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
    /** the price check's finding for this card (null: nothing found, or an older pack) */
    val priceCheck: PriceCheck? = null,
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
    private val hasHistory = tableExists("history")
    private val hasPriceCheck = tableExists("price_check")
    /** USD per EUR of the data pack's day (ECB), for showing TCGplayer prices in euros */
    private val usdPerEur: Double? by lazy { meta("usd_per_eur")?.toDoubleOrNull()?.takeIf { it > 0 } }

    private fun meta(key: String): String? = runCatching {
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** the history calendar and the euro rate on each of its days (null: the pack has no history) */
    private val historyCalendar: Pair<List<java.time.LocalDate>, List<Double?>>? by lazy {
        if (!hasHistory) return@lazy null
        val dates = meta("history_dates")?.split(",")?.mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: return@lazy null
        val rates = (meta("history_rates") ?: "").split(",").map { it.toDoubleOrNull() }
        dates to List(dates.size) { rates.getOrNull(it) }
    }

    /** The price history of a card (null: none in this data pack). */
    fun history(id: String): PriceHistory? {
        val (dates, rates) = historyCalendar ?: return null
        val series = HashMap<String, FloatArray>()
        runCatching {
            db.rawQuery("SELECT field, data FROM history WHERE id = ?", arrayOf(id)).use { c ->
                while (c.moveToNext()) {
                    val a = PriceHistory.decode(c.getBlob(1), dates.size)
                    if (a.size == dates.size) series[c.getString(0)] = a
                }
            }
        }
        return if (series.isEmpty()) null else PriceHistory(dates, rates, series)
    }
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

    /**
     * Cards in [langs] with the printed number [num] (normalised: "5", "TG5") in a set of [total]
     * cards (null: any set): the Asian prints that have no picture, so the camera can't find them.
     */
    fun byNumber(langs: List<String>, num: String, total: String?): List<CardInfo> {
        if (num.isEmpty() || langs.isEmpty()) return emptyList()
        val digits = Regex("""\d+""").findAll(num).lastOrNull()?.value?.trimStart('0')?.ifEmpty { "0" } ?: return emptyList()
        val q = "SELECT c.id, c.lang, c.local_id FROM cards c JOIN sets s ON s.id = c.set_id AND s.lang = c.lang " +
            "WHERE c.lang IN (${langs.joinToString { "?" }}) AND c.local_id LIKE ?" + (if (total != null) " AND (s.official = ? OR s.total = ?)" else "")
        val args = langs + listOf("%$digits") + listOfNotNull(total, total)
        val out = ArrayList<CardInfo>()
        db.rawQuery(q, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                if (io.github.pmaxai.cardscanner.vision.VersionResolver.normNumber(c.getString(2)) != num) continue
                cardExact(c.getString(0), c.getString(1))?.let { out += it }
            }
        }
        return out
    }

    /** The names the artwork's Pokémon has on Asian prints with a picture (e.g. "メガニウム" for Meganium). */
    fun asianNames(englishName: String, langs: List<String>): Set<String> {
        if (!columns("entries").contains("grp") || langs.isEmpty()) return emptySet()
        val q = "SELECT DISTINCT a.name FROM cards w JOIN entries f ON f.card_id = w.id AND f.lang = 'en' " +
            "JOIN entries e ON e.grp = f.grp JOIN cards a ON a.id = e.card_id AND a.lang = e.lang " +
            "WHERE w.lang = 'en' AND w.name = ? AND a.lang IN (${langs.joinToString { "?" }}) LIMIT 20"
        val out = HashSet<String>()
        db.rawQuery(q, (listOf(englishName) + langs).toTypedArray()).use { c -> while (c.moveToNext()) out += c.getString(0) }
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
                printings = printings(c.getString(18), rarity, prices, tcgplayer(cardId)).map { pr ->
                    val r = usdPerEur
                    if (pr.usd != null && r != null) pr.copy(usdEur = Math.round(pr.usd / r * 100) / 100.0) else pr
                },
                priceCheck = priceCheck(cardId, c.getString(1)),
                graded = graded(cardId),
                population = population(cardId),
                category = c.getString(19),
                releaseDate = c.getString(20)?.takeIf { it.isNotBlank() },
                imageAlt = c.getString(21), imageAltLarge = c.getString(22),
            )
        }
    }

    /** The price check's finding for a card, with the other cards named in [lang] where possible. */
    private fun priceCheck(id: String, lang: String?): PriceCheck? {
        if (!hasPriceCheck) return null
        val row = runCatching {
            db.rawQuery("SELECT status, cm_trend, tp_lo, tp_hi, others FROM price_check WHERE id = ?", arrayOf(id)).use { c ->
                if (!c.moveToFirst()) null else listOf(c.getString(0), c.doubleOrNull(1), c.doubleOrNull(2), c.doubleOrNull(3), c.getString(4))
            }
        }.getOrNull() ?: return null
        val status = when (row[0] as String?) {
            "dropped" -> PriceCheck.Status.DROPPED
            "uncertain" -> PriceCheck.Status.UNCERTAIN
            "disagree" -> PriceCheck.Status.DISAGREE
            else -> return null
        }
        val others = (row[4] as String?).orEmpty().split(",").filter { it.isNotBlank() }.map { other ->
            db.rawQuery("""SELECT c.name, c.local_id, s.official FROM cards c LEFT JOIN sets s ON s.id = c.set_id AND s.lang = c.lang
                           WHERE c.id = ? ORDER BY CASE c.lang WHEN ? THEN 0 WHEN 'en' THEN 1 ELSE 2 END LIMIT 1""",
                arrayOf(other, lang ?: "en")).use { c ->
                if (!c.moveToFirst()) other else {
                    val total = c.intOrNull(2)
                    "${c.getString(0)} ${c.getString(1)}" + (if (total != null && total > 0) "/$total" else "")
                }
            }
        }
        return PriceCheck(status, row[1] as Double?, row[2] as Double?, row[3] as Double?, others)
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
