package io.github.pmaxai.cardscanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.pmaxai.cardscanner.data.PriceHistory
import io.github.pmaxai.cardscanner.data.Printing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val UsdLine = Color(0xFF6FA8FF)

/** The part of the calendar that is inside the last [days] days. */
private fun PriceHistory.firstIndex(days: Int): Int {
    val from = dates.last().minusDays(days.toLong())
    return dates.indexOfFirst { !it.isBefore(from) }.coerceAtLeast(0)
}

private fun FloatArray.finite(from: Int): List<Int> = (from until size).filter { !this[it].isNaN() }

/** first and last price in the window, and the change between them */
private data class Change(val first: Float, val last: Float) {
    val pct: Double get() = if (first > 0) (last - first) / first.toDouble() else 0.0
}

private fun change(a: FloatArray?, from: Int): Change? {
    val idx = a?.finite(from) ?: return null
    if (idx.size < 2) return null
    return Change(a[idx.first()], a[idx.last()])
}

private fun pctText(c: Change): String {
    val p = (c.pct * 100).roundToInt()
    return when {
        p > 0 -> "▲ $p %"
        p < 0 -> "▼ ${-p} %"
        else -> "± 0 %"
    }
}

/**
 * Small 90-day price line under the price: Cardmarket trend, or TCGplayer in euros where
 * Cardmarket has no history. Tap for the full chart.
 */
@Composable
fun PriceSpark(h: PriceHistory, kind: Printing.Kind?, hasNormal: Boolean, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val from = h.firstIndex(90)
    val eur = h.eur(kind, hasNormal)?.takeIf { it.finite(from).size >= 2 }
    val usd = h.usdInEur(kind)?.takeIf { it.finite(from).size >= 2 }
    val line = eur ?: usd ?: return
    val c = change(line, from) ?: return
    Column(modifier.fillMaxWidth().padding(top = 6.dp).clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("90 days ", color = Muted, fontSize = 11.sp)
            Text(pctText(c), color = if (c.pct > 0.005) Sure else if (c.pct < -0.005) Warn else Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
            Text(if (eur != null) "  Cardmarket" else "  TCGplayer, in €", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("chart ›", color = Gold, fontSize = 12.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(34.dp).padding(top = 2.dp)) {
            val lo = line.finite(from).minOf { line[it] }
            val hi = line.finite(from).maxOf { line[it] }
            drawSeries(h, line, from, lo, hi, if (eur != null) Gold else UsdLine, dashed = eur == null, width = 2.dp.toPx())
        }
    }
}

/** One series as a line over the window: x by date, y between lo and hi; gaps over 14 days break it. */
private fun DrawScope.drawSeries(h: PriceHistory, a: FloatArray, from: Int, lo: Float, hi: Float, color: Color, dashed: Boolean,
                                 width: Float, left: Float = 0f, right: Float = size.width, top: Float = 0f, bottom: Float = size.height) {
    val d0 = h.dates[from].toEpochDay()
    val d1 = h.dates.last().toEpochDay()
    val span = (d1 - d0).coerceAtLeast(1).toFloat()
    val range = (hi - lo).takeIf { it > 0 } ?: 1f
    fun x(i: Int) = left + (h.dates[i].toEpochDay() - d0) / span * (right - left)
    fun y(v: Float) = bottom - (v - lo) / range * (bottom - top)
    val path = Path()
    var prev: Int? = null
    for (i in from until a.size) {
        val v = a[i]
        if (v.isNaN()) continue
        val p = prev
        if (p == null || h.dates[i].toEpochDay() - h.dates[p].toEpochDay() > 14) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
        prev = i
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
}

/**
 * Full chart: 1 month to 1 year, Cardmarket trend (EUR) and TCGplayer market price (USD,
 * converted at each day's ECB rate), per printing, with the high, the low and the change.
 */
@Composable
fun PriceChartDialog(title: String, h: PriceHistory, printings: List<Printing.Kind>, initial: Printing.Kind?, hasNormal: Boolean,
                     onClose: () -> Unit) {
    var kind by remember { mutableStateOf(initial) }
    var days by remember { mutableIntStateOf(90) }
    val measurer = rememberTextMeasurer()
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault()) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth(0.96f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FitText(title, 18.sp, Color.White, Modifier.weight(1f), weight = FontWeight.Bold)
                    Box(Modifier.size(36.dp).background(Color(0xFF2A2D34), CircleShape).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
                if (printings.size > 1) {
                    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        printings.forEach { k -> FilterChip(selected = k == kind, onClick = { kind = k }, label = { Text(k.label, fontSize = 12.sp) }) }
                    }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30 to "1M", 90 to "3M", 180 to "6M", 365 to "1Y").forEach { (d, label) ->
                        FilterChip(selected = d == days, onClick = { days = d }, label = { Text(label) })
                    }
                }
                val from = h.firstIndex(days)
                val cm = h.eur(kind, hasNormal)?.takeIf { it.finite(from).isNotEmpty() }
                val tp = h.usdInEur(kind)?.takeIf { it.finite(from).isNotEmpty() }
                val main = cm ?: tp
                if (main == null) {
                    Text("No price history for this printing yet.", color = Muted, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    ChartBody(h, from, cm, tp, main, fmt, measurer)
                }
            }
        }
    }
}

@Composable
private fun ChartBody(h: PriceHistory, from: Int, cm: FloatArray?, tp: FloatArray?, main: FloatArray, fmt: DateTimeFormatter,
                      measurer: androidx.compose.ui.text.TextMeasurer) {
    val idx = main.finite(from)
    val hiI = idx.maxBy { main[it] }
    val loI = idx.minBy { main[it] }
    val c = change(main, from)
    Text(buildString {
        append(if (cm != null) "Cardmarket trend " else "TCGplayer (in €) ")
        append(eur(main[idx.last()].toDouble()))
        c?.let { append("  ·  ${pctText(it)} since ${fmt.format(h.dates[idx.first()])}") }
    }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Text("high ${eur(main[hiI].toDouble())} (${fmt.format(h.dates[hiI])}) · low ${eur(main[loI].toDouble())} (${fmt.format(h.dates[loI])})",
        color = Muted, fontSize = 12.sp)

    val all = listOfNotNull(cm, tp).flatMap { a -> a.finite(from).map { a[it] } }
    val lo0 = all.min()
    val hi0 = all.max()
    val pad = ((hi0 - lo0) * 0.1f).takeIf { it > 0 } ?: (hi0 * 0.1f + 0.05f)
    val lo = (lo0 - pad).coerceAtLeast(0f)
    val hi = hi0 + pad
    val gridColor = Color(0xFF2A2D34)
    Canvas(Modifier.fillMaxWidth().height(240.dp).padding(top = 10.dp)) {
        val axis = 46.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val plotR = size.width - axis
        val plotB = size.height - bottomPad
        val label = TextStyle(color = Muted, fontSize = 10.sp)
        // three gridlines with their prices on the right
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2f
            val y = plotB - (v - lo) / (hi - lo) * plotB
            drawLine(gridColor, Offset(0f, y), Offset(plotR, y), 1.dp.toPx())
            drawText(measurer, eur(v.toDouble()), Offset(plotR + 4.dp.toPx(), (y - 7.dp.toPx()).coerceIn(0f, plotB - 12.dp.toPx())), label)
        }
        // first, middle and last date under the plot
        val d0 = h.dates[from].toEpochDay()
        val span = (h.dates.last().toEpochDay() - d0).coerceAtLeast(1).toFloat()
        val mid = (from + h.dates.size - 1) / 2
        listOf(from, mid, h.dates.size - 1).forEachIndexed { k, i ->
            val x = (h.dates[i].toEpochDay() - d0) / span * plotR
            val text = fmt.format(h.dates[i])
            val w = measurer.measure(text, label).size.width
            drawText(measurer, text, Offset(when (k) { 0 -> 0f; 1 -> x - w / 2f; else -> plotR - w }, plotB + 4.dp.toPx()), label)
        }
        tp?.let { drawSeries(h, it, from, lo, hi, UsdLine, dashed = true, width = 2.dp.toPx(), right = plotR, bottom = plotB) }
        cm?.let { drawSeries(h, it, from, lo, hi, Gold, dashed = false, width = 2.5.dp.toPx(), right = plotR, bottom = plotB) }
        // the high and the low of the main line
        for (i in listOf(hiI, loI)) {
            val x = (h.dates[i].toEpochDay() - d0) / span * plotR
            val y = plotB - (main[i] - lo) / (hi - lo) * plotB
            drawCircle(if (cm != null) Gold else UsdLine, 4.dp.toPx(), Offset(x, y))
        }
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (cm != null) {
            Box(Modifier.width(16.dp).height(3.dp).background(Gold))
            Text(" Cardmarket trend (EUR)   ", color = Muted, fontSize = 11.sp)
        }
        if (tp != null) {
            Box(Modifier.width(16.dp).height(3.dp).background(UsdLine))
            Text(" TCGplayer (USD, in €)", color = Muted, fontSize = 11.sp)
        }
    }
    val start = listOfNotNull(cm, tp).mapNotNull { a -> a.finite(from).firstOrNull() }.minOrNull()
    if (start != null && h.dates[start].isAfter(h.dates[from].plusDays(10))) {
        Text("Prices are known from ${fmt.format(h.dates[start])}; the history grows every day.", color = Muted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
    if (tp != null) Text("TCGplayer converted at each day's ECB rate" + (h.lastRate?.let { " (now 1 € = ${String.format(Locale.getDefault(), "%.4f", it)} \$)" } ?: ""),
        color = Muted, fontSize = 11.sp)
    if (abs(c?.pct ?: 0.0) >= 0.5) Text("Big swings often mean few sales: check Cardmarket.", color = Warn, fontSize = 11.sp)
}

/** The 90-day line for a shown card, opening its full chart. */
@Composable
fun PriceHistoryLine(s: Shown, modifier: Modifier = Modifier) {
    val h = s.history ?: return
    var open by remember { mutableStateOf(false) }
    val kinds = s.priceCard.printings.map { it.kind }
    val hasNormal = Printing.Kind.NORMAL in kinds
    PriceSpark(h, s.selected?.kind, hasNormal, modifier) { open = true }
    if (open) PriceChartDialog("${s.version.name} · ${s.version.setLine(withLang = false)}", h, kinds, s.selected?.kind, hasNormal) { open = false }
}

@Suppress("unused")
private fun LocalDate.short(): String = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(this)
