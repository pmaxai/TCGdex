package io.github.pmaxai.cardscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pmaxai.cardscanner.data.CardInfo
import io.github.pmaxai.cardscanner.data.Graded
import io.github.pmaxai.cardscanner.data.Population
import io.github.pmaxai.cardscanner.data.PriceShown
import io.github.pmaxai.cardscanner.data.PriceSource
import io.github.pmaxai.cardscanner.data.Printing

/** "12.30 €", "≈ 11.40 €" for TCGplayer in euros, "$12.80" without a rate */
val PriceShown.text: String get() {
    val e = eur ?: return usd(native)
    val t = formatEur(e)
    return if (source.isUsd) "≈ $t" else t
}

/** [eur] for code whose receiver has an `eur` of its own */
private fun formatEur(v: Double): String = eur(v)

/** a printing's price in the default source (or the next one it has), "–" without any */
val Printing.priceText: String get() = shown(PriceSource.current)?.text ?: "–"

/** the most valuable printing's price in the default source, for the lists */
val CardInfo.bestPriceText: String get() = printings.filter { it.value != null }.maxByOrNull { it.value!! }?.priceText ?: "–"

/** The price of a printing in the default source (or the next one with a price), large. */
@Composable
fun PriceHeadline(p: Printing, source: PriceSource) {
    val shown = p.shown(source)
    if (shown == null) {
        Text("No price for this card.", color = Muted)
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(shown.text, color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text("${shown.source.label} · ${p.kind.label}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    if (shown.source != source) Text("No ${source.label} for this card – ${shown.source.label} shown", color = Muted, fontSize = 11.sp)
}

/** Every source's price of one printing, by provider; the default source marked ★. */
@Composable
fun PriceSourcesTable(p: Printing, source: PriceSource, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        var any = false
        PriceSource.PROVIDERS.forEach { (name, sources) ->
            if (sources.none { p.raw(it) != null }) return@forEach
            Text(if (name == "Cardmarket") "Cardmarket · €" else "TCGplayer · $", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = if (any) 8.dp else 0.dp, bottom = 2.dp))
            any = true
            sources.forEach { src ->
                val mine = src == source
                val color = if (mine) Gold else Color.White
                val weight = if (mine) FontWeight.Bold else FontWeight.Normal
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (mine) "★ " else "") + src.title, color = color, fontSize = 14.sp, fontWeight = weight, modifier = Modifier.weight(1f))
                    val v = p.raw(src)
                    if (v == null) Text("–", color = Muted, fontSize = 14.sp)
                    else {
                        Text(if (src.isUsd) usd(v) else eur(v), color = color, fontSize = 14.sp, fontWeight = weight)
                        if (src.isUsd) p.eurValue(src)?.let { e ->
                            Text("≈ ${eur(e)}", color = Muted, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 76.dp))
                        }
                    }
                }
            }
        }
        if (!any) Text("No prices for the ${p.kind.label.lowercase()}.", color = Muted, fontSize = 14.sp)
    }
}

/** A printing's prices: the headline, the table of sources, and what to know about them. */
@Composable
fun PriceBlock(p: Printing?, card: CardInfo, source: PriceSource, pricedFromEnglish: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        if (p == null) Text("No price for this card.", color = Muted)
        else {
            PriceHeadline(p, source)
            PriceSourcesTable(p, source)
        }
        card.priceCheck?.let { Text("⚠ ${it.message}", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (pricedFromEnglish) Text("Prices of the English version (none for this language)", color = Muted, fontSize = 11.sp)
        card.prices?.updated?.let { Text("Prices from ${it.take(10)}", color = Muted, fontSize = 11.sp) }
    }
}

/**
 * PSA: the sold prices of graded copies (an add-on) and the gem rate. Without the add-on a locked
 * line says what it adds; [onLocked] shows its message.
 */
@Composable
fun PsaSection(hasAddon: Boolean, graded: Graded?, population: Population?, printing: Printing.Kind?, onlyOtherPrintings: Boolean,
               onLocked: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (hasAddon) {
            graded?.let { PsaLine(it, printing = printing) }
            if (graded == null && onlyOtherPrintings && printing != null)
                Text("No PSA sales for the ${printing.label.lowercase()} yet", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else if (graded != null || onlyOtherPrintings) {
            Text("🔒  PSA 10 · 9 · 8 sold prices – add-on", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp).clickable(onClick = onLocked).padding(vertical = 4.dp))
        }
        population?.let { GemLine(it) }
    }
}

/** the PSA part of a shown card (the scanner's result, the search's card page) */
@Composable
fun ShownPsa(s: Shown, state: UiState, vm: ScannerViewModel) {
    if (!state.settings.showPsa) return
    PsaSection(state.account.has("psa_prices"), s.graded, s.population,
        s.selected?.kind?.takeIf { s.priceCard.printings.size > 1 }, s.gradedOnlyOtherPrintings) { vm.showLocked(LockedFeature.PSA) }
}

/** A round button in a corner of the camera picture (a small lock when the plan lacks it, a count when it holds some). */
@Composable
fun CornerButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, locked: Boolean = false, on: Boolean = false,
                 badge: Int = 0, onClick: () -> Unit) {
    Box(modifier) {
        Box(Modifier.padding(6.dp).size(46.dp).background(if (on) Gold else Color(0x8C000000), CircleShape)
            .clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (on) Ink else if (locked) Muted else Color.White, modifier = Modifier.size(22.dp))
            if (locked) Text("🔒", fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 3.dp))
        }
        if (badge > 0) Text("$badge", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopEnd).widthIn(min = 20.dp).background(Gold, RoundedCornerShape(10.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp))
    }
}
