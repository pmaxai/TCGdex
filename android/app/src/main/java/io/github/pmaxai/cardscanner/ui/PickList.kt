package io.github.pmaxai.cardscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

/**
 * After a double tap: the most likely cards, best first, in a small scrolling window.
 * Tap the right one to show it (and put it in the pack instead); ✕ or outside keeps the
 * card shown.
 */
@Composable
fun PickList(picks: List<Candidate>, shown: Shown?, onPick: (Candidate) -> Unit, onSearch: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Which card is it?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Most likely first · tap the right one", color = Muted, fontSize = 12.sp)
                    }
                    Box(Modifier.size(40.dp).background(Color(0xFF2A2D34), CircleShape).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center) {
                        Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val top = picks.first().score
                val low = picks.last().score
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    itemsIndexed(picks, key = { _, c -> c.group }) { i, c ->
                        val rel = if (top > low) 0.12f + 0.88f * (c.score - low) / (top - low) else 1f
                        val current = shown != null && c.group == shown.group
                        // the shown card as it is now (the printed text may have set its exact version)
                        val row = if (current) c.copy(info = shown!!.version, image = shown.imageCard?.thumbnailUrl ?: c.image) else c
                        CardRow(row, rank = i + 1, current = current, rel = rel) { onPick(c) }
                    }
                }
                // not in the list (e.g. a card the scanner has no picture of): look it up by name or number
                Text("🔍  Not there? Search by name or number", color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onSearch).padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

/** One card in a list: picture, name, set · number · language · year, price; [rank] and [rel] (bar) optional. */
@Composable
fun CardRow(c: Candidate, rank: Int? = null, current: Boolean = false, rel: Float? = null, onClick: () -> Unit) {
    val blank = ColorPainter(Color(0xFF2A2D34))
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .background(if (current) Gold.copy(alpha = 0.14f) else Color.Transparent)
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rank != null) Text("$rank", color = Muted, fontSize = 13.sp, modifier = Modifier.width(24.dp))
        Box(Modifier.width(44.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            AsyncImage(model = c.image, contentDescription = c.info.name, placeholder = blank, error = blank, fallback = blank,
                modifier = Modifier.matchParentSize())
            // no picture anywhere: the camera can't recognise this card, only the search finds it
            if (c.image == null) Text("no\npicture", color = Muted, fontSize = 8.sp, lineHeight = 9.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.info.name + if (current) "  ✓ shown" else "", color = if (current) Gold else Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.info.setLine(), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // how close it came, compared with the others in the list
            if (rel != null) Box(Modifier.padding(top = 4.dp).fillMaxWidth(rel.coerceIn(0.05f, 1f)).height(3.dp)
                .background(if (rank == 1) Sure else Muted.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.width(8.dp))
        Text(eur(c.info.bestValue), color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
