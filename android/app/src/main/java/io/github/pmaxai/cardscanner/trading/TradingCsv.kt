package io.github.pmaxai.cardscanner.trading

import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deals (carts) as CSV: one row per card with condition, market value, asking and
 * negotiated price, verdict, vendor and date, then each cart's totals. Same regional
 * format as the pack CSV: ';' and decimal commas where the phone uses a comma, UTF-8 BOM.
 */
object TradingCsv {
    private val HEADER = listOf(
        "Booth", "Paid", "Payment", "Status", "Name", "Set / number / language / year", "Card ID", "Language",
        "Printing", "Condition", "Qty", "Market value each EUR", "Asking each EUR", "Negotiated each EUR",
        "You pay EUR", "Verdict",
    )

    fun build(carts: List<Cart>, locale: Locale): String {
        val decimalComma = DecimalFormatSymbols.getInstance(locale).decimalSeparator == ','
        val sep = if (decimalComma) ';' else ','
        fun num(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.2f", it).let { s -> if (decimalComma) s.replace('.', ',') else s } } ?: ""
        fun cell(v: Any?): String {
            val t = v?.toString() ?: ""
            return if (t.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
        val out = StringBuilder("﻿")
        fun row(values: List<Any?>) { values.joinTo(out, sep.toString()) { cell(it) }; out.append("\r\n") }
        row(HEADER)
        for (c in carts) {
            val paid = c.paidAt?.let { time.format(Date(it)) } ?: ""
            for (l in c.lines) {
                row(listOf(c.name, paid, c.payment, l.status.label, l.name, l.setLine, l.cardId, l.lang, l.printingLabel,
                    l.condition.label, l.qty, num(l.market), num(l.asking), num(l.negotiated),
                    if (l.active) num(l.price?.let { it * l.qty }) else "", l.verdict?.label))
            }
        }
        out.append("\r\n")
        row(listOf("Booth", "Paid", "Cards", "Market value EUR", "Asking EUR", "You pay EUR", "Saved vs market EUR"))
        for (c in carts) {
            row(listOf(c.name, c.paidAt?.let { time.format(Date(it)) } ?: "open", c.cards, num(c.marketTotal), num(c.askingTotal),
                num(c.payTotal), num(c.savedVsMarket)))
        }
        if (carts.size > 1) {
            row(listOf("All", "", carts.sumOf { it.cards }, num(carts.sumOf { it.marketTotal }), num(carts.sumOf { it.askingTotal }),
                num(carts.sumOf { it.payTotal }), num(carts.sumOf { it.savedVsMarket })))
        }
        return out.toString()
    }

    fun fileName(now: Date = Date()): String = "deals_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(now) + ".csv"
}
