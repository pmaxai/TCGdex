package io.github.pmaxai.cardscanner.ui

import io.github.pmaxai.cardscanner.data.Printing
import java.net.URLEncoder
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pack (YouTube session) as a CSV file: one row per card with everything the app
 * knows about it, then totals. Written the way spreadsheet apps in the phone's region
 * expect it: where the decimal mark is a comma (German, French, ...), fields are
 * separated by ';' and numbers use the comma, like Excel does there. UTF-8 with a BOM, so
 * umlauts and Japanese names show correctly.
 */
object PackCsv {
    private val HEADER = listOf(
        "No", "Scanned", "Name", "English name", "Set", "Number", "Language", "Card ID", "Printing",
        "Value", "Currency", "Cardmarket trend EUR", "Cardmarket 30-day avg EUR", "Cardmarket lowest EUR",
        "TCGplayer USD", "PSA 10 USD", "PSA 10 sales", "PSA 9 USD", "PSA 9 sales", "PSA 8 USD", "PSA 8 sales",
        "Hit", "All printings", "Rarity", "Series", "Prices from", "Cardmarket search",
    )

    fun build(items: List<SessionItem>, settings: Settings, locale: Locale): String {
        val decimalComma = DecimalFormatSymbols.getInstance(locale).decimalSeparator == ','
        val sep = if (decimalComma) ';' else ','
        fun num(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.2f", it).let { s -> if (decimalComma) s.replace('.', ',') else s } } ?: ""
        fun cell(v: Any?): String {
            val t = v?.toString() ?: ""
            return if (t.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale)
        val out = StringBuilder("﻿")
        fun row(values: List<Any?>) { values.joinTo(out, sep.toString()) { cell(it) }; out.append("\r\n") }

        row(HEADER)
        var eur = 0.0
        var usd = 0.0
        items.forEachIndexed { i, item ->
            val s = item.shown
            val v = s.version
            val p = s.selected
            val g = s.graded
            val currency = when {
                p?.eur != null -> "EUR"
                p?.usd != null -> "USD"
                else -> ""
            }
            if (currency == "EUR") eur += p?.eur ?: 0.0 else if (currency == "USD") usd += p?.usd ?: 0.0
            val hit = settings.threshold > 0 && (s.value ?: 0.0) >= settings.threshold
            row(listOf(
                i + 1, time.format(Date(item.time)), v.name, s.english?.name ?: v.name.takeIf { v.lang == "en" },
                v.setName, v.numberText, v.lang, v.id, p?.kind?.label,
                num(p?.eur ?: p?.usd), currency, num(p?.eur), num(p?.eur30), num(p?.eurLow), num(p?.usd),
                num(g?.psa10), g?.psa10n, num(g?.psa9), g?.psa9n, num(g?.psa8), g?.psa8n,
                if (hit) "yes" else "",
                s.priceCard.printings.joinToString(" | ") { pr -> "${pr.kind.label} ${printingValue(pr, ::num)}" },
                s.priceCard.rarity, s.priceCard.series, s.priceCard.prices?.updated?.take(10),
                "https://www.cardmarket.com/en/Pokemon/Products/Search?searchString=" +
                    URLEncoder.encode(s.priceCard.name, "UTF-8"),
            ))
        }
        out.append("\r\n")
        row(listOf("Cards", items.size))
        row(listOf("Pack value EUR", num(eur)))
        if (usd > 0) row(listOf("Pack value USD (cards without a Cardmarket price)", num(usd)))
        if (settings.title.isNotBlank()) row(listOf("Title", settings.title))
        return out.toString()
    }

    private fun printingValue(p: Printing, num: (Double?) -> String): String = when {
        p.eur != null -> num(p.eur) + " EUR"
        p.usd != null -> num(p.usd) + " USD"
        else -> "-"
    }

    /** "pack_2026-11-03_0314.csv", with the title in front if there is one */
    fun fileName(settings: Settings, now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(now)
        val title = settings.title.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)
        return (if (title.isNotEmpty()) "${title}_" else "") + "pack_$stamp.csv"
    }
}
