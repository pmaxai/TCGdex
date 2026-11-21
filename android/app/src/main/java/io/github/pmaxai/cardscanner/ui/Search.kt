package io.github.pmaxai.cardscanner.ui

import io.github.pmaxai.cardscanner.shop.ShopTerms
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.pmaxai.cardscanner.data.CardInfo
import kotlinx.coroutines.delay

/**
 * Card search: name (any language), number and set, e.g. "whimsicott 019", "elfun",
 * "charizard base", "GG01". Finds every card in the database, also those the camera can't
 * recognise (no picture). LOOK_UP shows the card without it being in front of the camera;
 * REPLACE puts it in place of the shown card (and its pack entry).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchDialog(mode: SearchMode, vm: ScannerViewModel) {
    val account = vm.state.collectAsState().value.account
    /** long press on +: the copy's details for this card */
    var sheetFor by remember { mutableStateOf<CardInfo?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var searched by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); searched = ""; return@LaunchedEffect }
        delay(220)                      // typing: wait for a pause
        results = vm.search(q)
        searched = q
    }
    Dialog(onDismissRequest = vm::closeSearch, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f).imePadding()) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (mode == SearchMode.REPLACE) "Find the right card" else "Search cards", color = Color.White,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(if (mode == SearchMode.REPLACE) "It replaces the card shown" else "Name, number or set, any language",
                            color = Muted, fontSize = 12.sp)
                    }
                    Box(Modifier.size(40.dp).background(Color(0xFF2A2D34), CircleShape).clickable(onClick = vm::closeSearch),
                        contentAlignment = Alignment.Center) {
                        Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("e.g. whimsicott 019, elfun, GG01") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus))
                when {
                    query.trim().length < 2 -> Hint("Type at least two letters. Several words narrow it down: name, number (019 or 019/088), set name or code.")
                    searched == query.trim() && results.isEmpty() -> Hint("No card found for “$searched”.")
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(results, key = { "${it.info.id}/${it.info.lang}" }) { c ->
                        val coll = mode == SearchMode.LOOK_UP && account.has("collection")
                        val want = mode == SearchMode.LOOK_UP && account.has("wanted")
                        val trailing: (@Composable () -> Unit)? = if (coll || want) { {
                            // + Collection: the card in its language and first printing, NM (long press: the details)
                            if (coll) Box(Modifier.padding(start = 10.dp).size(32.dp).background(Gold, CircleShape)
                                .semantics { contentDescription = "Add to the collection" }
                                .combinedClickable(onClick = { vm.addToCollection(c.info) },
                                    onLongClick = { keyboard?.hide(); vm.account.loadBinders(); sheetFor = c.info }),
                                contentAlignment = Alignment.Center) {
                                Text("+", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            // ☆ Missing: the card in its language, any printing (a second tap takes it off the list)
                            if (want) {
                                val on = account.wanted.any { it.matches(c.info.id, ShopTerms.languageOf(c.info.lang), null) }
                                Text(if (on) "★" else "☆", color = if (on) Gold else Muted, fontSize = 22.sp,
                                    modifier = Modifier.padding(start = if (coll) 4.dp else 10.dp).clickable { vm.toggleWanted(c.info) }.padding(6.dp))
                            }
                        } } else null
                        CardRow(c, trailing = trailing) { keyboard?.hide(); vm.pickFromSearch(c) }
                    }
                }
                if (results.size >= ScannerViewModel.SEARCH_LIMIT)
                    Hint("Showing the first ${ScannerViewModel.SEARCH_LIMIT} – add a word to narrow it down.")
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    sheetFor?.let { card ->
        CollectionSheet(account, "NM", onClose = { sheetFor = null }) { cond, qty, cents, binder, note ->
            vm.addToCollection(card, cond, qty, cents, binder, note)
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

/**
 * The Search tab: any card by name (any language), number or set; a card opens its page here
 * with every price, the chart, PSA, + Collection, ☆ Missing and Add to cart.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchPage(state: UiState, vm: ScannerViewModel) {
    state.searchCard?.let { s ->
        BackHandler { vm.closeSearchCard() }
        SearchCardPage(s, state, vm)
        return
    }
    BackHandler { vm.selectTab(AppTab.SCANNER) }
    val account = state.account
    var sheetFor by remember { mutableStateOf<CardInfo?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var searched by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); searched = ""; return@LaunchedEffect }
        delay(220)                      // typing: wait for a pause
        results = vm.search(q)
        searched = q
    }
    Column(Modifier.fillMaxSize().background(Ink).padding(top = 12.dp)) {
        Text("Search", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Name, number or set, any language") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        when {
            query.trim().length < 2 -> Hint("e.g. whimsicott 019, elfun, charizard base, GG01. A card opens with every price, its chart, " +
                "PSA, + Collection, ☆ Missing and Add to cart.")
            searched == query.trim() && results.isEmpty() -> Hint("No card found for “$searched”.")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { "${it.info.id}/${it.info.lang}" }) { c ->
                val coll = account.has("collection") && account.status != null
                val want = account.has("wanted") && account.status != null
                val trailing: (@Composable () -> Unit)? = if (coll || want) { {
                    if (coll) Box(Modifier.padding(start = 10.dp).size(32.dp).background(Gold, CircleShape)
                        .semantics { contentDescription = "Add to the collection" }
                        .combinedClickable(onClick = { vm.addToCollection(c.info) },
                            onLongClick = { keyboard?.hide(); vm.account.loadBinders(); sheetFor = c.info }),
                        contentAlignment = Alignment.Center) {
                        Text("+", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    if (want) {
                        val on = account.wanted.any { it.matches(c.info.id, ShopTerms.languageOf(c.info.lang), null) }
                        Text(if (on) "★" else "☆", color = if (on) Gold else Muted, fontSize = 22.sp,
                            modifier = Modifier.padding(start = if (coll) 4.dp else 10.dp).clickable { vm.toggleWanted(c.info) }.padding(6.dp))
                    }
                } } else null
                CardRow(c, trailing = trailing) { keyboard?.hide(); vm.openSearchCard(c) }
            }
        }
        if (results.size >= ScannerViewModel.SEARCH_LIMIT)
            Hint("Showing the first ${ScannerViewModel.SEARCH_LIMIT} – add a word to narrow it down.")
    }
    sheetFor?.let { card ->
        CollectionSheet(account, "NM", onClose = { sheetFor = null }) { cond, qty, cents, binder, note ->
            vm.addToCollection(card, cond, qty, cents, binder, note)
        }
    }
}

/** A card opened in the search: what the Scanner shows for a scanned card, without the camera. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchCardPage(s: Shown, state: UiState, vm: ScannerViewModel) {
    val ctx = LocalContext.current
    val c = s.version
    val priced = state.account.revealed(s.priceCard.id)
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.closeSearchCard() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the search", tint = Color.White)
            }
            Text(c.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            CardImage(s, Modifier.align(Alignment.CenterHorizontally).width(240.dp).aspectRatio(63f / 88f))
            Text(c.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text(listOfNotNull(c.setName, c.numberText, c.lang.uppercase(), c.year, c.rarity).joinToString(" · "), color = Muted, fontSize = 14.sp)
            s.english?.let { e -> Text("English: ${e.name} · ${e.setName} ${e.numberText}", color = Color.White, fontSize = 13.sp) }
            PrintedOnlyHint(s)
            if (s.priceCard.printings.size > 1) {
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.priceCard.printings.forEach { p ->
                        FilterChip(selected = p.kind == s.selected?.kind, onClick = { vm.chooseSearchPrinting(p.kind) },
                            label = { Text(if (priced) "${p.kind.label} ${p.priceText}" else p.kind.label, fontSize = 12.sp) })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (priced) {
                PriceBlock(s.selected, s.priceCard, state.settings.priceSource, s.pricedFromEnglish)
                PriceHistoryLine(s, Modifier.padding(top = 6.dp))
            } else {
                RevealPrice(state.account, s.priceCard.id, vm)
            }
            ShownPsa(s, state, vm)
            CollectionBar(state, vm, s)
            val others = s.versions.filter { it.id != c.id || it.lang != c.lang }
            if (others.isNotEmpty()) {
                Text("Other version or language", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    others.forEach { v ->
                        val label = if (v.id == c.id) "${LANG_NAMES[v.lang] ?: v.lang}: ${v.name}" else v.setLine()
                        FilterChip(selected = false, onClick = { vm.chooseSearchVersion(v) }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
            }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddToCartButton(s, state, vm)
                OutlinedButton({ openCardmarket(ctx, s.priceCard.name) }) { Text("Cardmarket", maxLines = 1) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
