package io.github.pmaxai.cardscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.pmaxai.cardscanner.shop.ShopTerms

/** The collection page's tabs. */
enum class CollectionPart(val title: String) { COLLECTION("Collection"), MISSING("Missing cards") }

/**
 * The collection page: the whole collection (filter, counts, total value) and the missing cards
 * as its second tab. A card tapped is shown as a looked-up card; a missing card can be removed,
 * or put into the collection when it turned up elsewhere.
 */
@Composable
fun CollectionScreen(state: UiState, vm: ScannerViewModel, start: CollectionPart) {
    val a = state.account
    val parts = buildList {
        if (a.has("collection")) add(CollectionPart.COLLECTION)
        if (a.has("wanted")) add(CollectionPart.MISSING)
    }
    var part by rememberSaveable { mutableStateOf(start) }
    LaunchedEffect(start) { part = start }
    val shown = if (part in parts) part else parts.firstOrNull() ?: CollectionPart.COLLECTION
    BackHandler { vm.closeCollection() }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.closeCollection() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(shown.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (shown == CollectionPart.COLLECTION) vm.account.loadItems() else vm.account.check(force = true) },
                enabled = !(shown == CollectionPart.COLLECTION && a.itemsLoading)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Load again", tint = Color.White)
            }
        }
        if (parts.size > 1) Row(Modifier.fillMaxWidth().background(Panel)) {
            parts.forEach { p ->
                val on = p == shown
                Column(Modifier.weight(1f).clickable { part = p }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (p == CollectionPart.MISSING) "Missing (${a.wanted.size})" else "Collection", color = if (on) Gold else Muted,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) Gold else Color.Transparent))
                }
            }
        }
        when (shown) {
            CollectionPart.COLLECTION -> CollectionList(state, vm, Modifier.weight(1f))
            CollectionPart.MISSING -> MissingList(state, vm, Modifier.weight(1f))
        }
    }
}

/** Every copy in the collection, newest first, with a filter, the counts and the total value. */
@Composable
private fun CollectionList(state: UiState, vm: ScannerViewModel, modifier: Modifier) {
    val a = state.account
    var filter by rememberSaveable { mutableStateOf("") }
    // first time, and after copies were sent to the collection meanwhile
    LaunchedEffect(a.collectionEdits) { if (a.items == null || a.itemsAt != a.collectionEdits) vm.account.loadItems() }
    val all = a.items.orEmpty()
    val words = filter.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val shown = if (words.isEmpty()) all else all.filter { item -> words.all { item.searchText.contains(it) } }
    Column(modifier) {
        OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filter the collection") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (all.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${all.map { it.cardId }.toSet().size} cards · ${all.sumOf { it.quantity }} copies", color = Muted, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            Text(eur(all.sumOf { (it.valueCents ?: 0L) * it.quantity } / 100.0), color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        when {
            a.items == null || all.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (a.items == null && a.itemsLoading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(a.itemsError ?: if (a.items == null) "Loading the collection…" else "No cards in the collection yet. Tap + Collection on a card to add it.",
                    color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            shown.isEmpty() -> Text("No card matches “$filter”.", color = Muted, modifier = Modifier.padding(24.dp))
            else -> {
                a.itemsError?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { item -> CollectionRow(item, vm) }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(item: CollectionItem, vm: ScannerViewModel) {
    val lang = ShopTerms.appLang(item.language)
    val c by produceState<Candidate?>(null, item.cardId, lang) { value = vm.candidate(item.cardId, lang) }
    Row(Modifier.fillMaxWidth().clickable(enabled = c != null) { c?.let(vm::showFromCollection) }
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(c?.image ?: item.imageUrl, item.name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(item.setName, item.number).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(item.language, ShopTerms.finishLabel(item.finish), item.condition, item.graded).filter { it.isNotBlank() }
                .joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("× ${item.quantity}", color = Color.White, fontSize = 14.sp)
            item.valueCents?.let { Text(eur(it * item.quantity / 100.0), color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * The missing cards: what the shop looks for. Tap a card to remove it, or to put it into the
 * collection when it turned up elsewhere.
 */
@Composable
private fun MissingList(state: UiState, vm: ScannerViewModel, modifier: Modifier) {
    val a = state.account
    /** the card whose actions are shown (with its card, for the name) */
    var chosen by remember { mutableStateOf<Pair<WantedItem, Candidate?>?>(null) }
    /** the card that turned up elsewhere: its copy's details */
    var found by remember { mutableStateOf<WantedItem?>(null) }
    LazyColumn(modifier) {
        if (a.wanted.isEmpty()) item {
            Text("No missing cards yet. Tap ☆ Missing on a card to add it.", color = Muted, modifier = Modifier.padding(24.dp))
        }
        items(a.wanted, key = { it.id?.let { id -> "id$id" } ?: "ref${it.clientRef}" }) { w ->
            MissingRow(w, vm) { c -> chosen = w to c }
        }
    }
    chosen?.let { (w, c) ->
        AlertDialog(onDismissRequest = { chosen = null },
            title = { Text(c?.info?.let { "${it.name} · ${it.setName} ${it.numberText}" } ?: w.cardId) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (a.has("collection")) TextButton({ chosen = null; vm.account.loadBinders(); found = w }) {
                        Text("Found it: add to the collection", color = Gold, fontWeight = FontWeight.Bold)
                    }
                    TextButton({ chosen = null; vm.removeMissing(w) }) { Text("Remove from the missing cards", color = Warn) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton({ chosen = null }) { Text("Cancel") } })
    }
    found?.let { w ->
        CollectionSheet(a, "NM", title = "Found it", onClose = { found = null }) { cond, qty, cents, binder, note ->
            vm.foundMissing(w, cond, qty, cents, binder, note)
        }
    }
}

@Composable
private fun MissingRow(w: WantedItem, vm: ScannerViewModel, onClick: (Candidate?) -> Unit) {
    val lang = ShopTerms.appLang(w.language)
    val c by produceState<Candidate?>(null, w.cardId, lang) { value = vm.candidate(w.cardId, lang) }
    Row(Modifier.fillMaxWidth().clickable { onClick(c) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(c?.image, c?.info?.name ?: w.cardId)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c?.info?.name ?: w.cardId, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(c?.info?.let { "${it.setName} ${it.numberText}" }, w.language ?: "any language", w.finish?.let(ShopTerms::finishLabel))
                .joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val (text, color) = when {
            w.pending -> "waiting to sync" to Muted
            w.state == "offer" -> "Offer" to Gold
            w.state == "available" -> "In the shop" to Sure
            else -> "searching" to Muted
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = if (color == Muted) FontWeight.Normal else FontWeight.Bold)
    }
}

@Composable
private fun Thumb(url: String?, name: String) {
    val blank = ColorPainter(Color(0xFF2A2D34))
    Box(Modifier.width(40.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(4.dp))) {
        AsyncImage(model = url, contentDescription = name, placeholder = blank, error = blank, fallback = blank, modifier = Modifier.matchParentSize())
    }
}

/** On the scanner's start text and the Account page: the way to the collection page. */
@Composable
fun CollectionLinks(state: UiState, vm: ScannerViewModel, modifier: Modifier = Modifier) {
    val a = state.account
    if (a.status == null) return
    val coll = a.has("collection"); val want = a.has("wanted")
    if (!coll && !want) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (coll) OutlinedButton({ vm.openCollection(CollectionPart.COLLECTION) }) { Text("Collection", maxLines = 1) }
        if (want) OutlinedButton({ vm.openCollection(CollectionPart.MISSING) }) {
            Text("☆ Missing cards (${a.wanted.size})", maxLines = 1)
        }
    }
}
