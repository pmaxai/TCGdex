package io.github.pmaxai.cardscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Card search: name (any language), number and set, e.g. "whimsicott 019", "elfun",
 * "charizard base", "GG01". Finds every card in the database, also those the camera can't
 * recognise (no picture). LOOK_UP shows the card without it being in front of the camera;
 * REPLACE puts it in place of the shown card (and its pack entry).
 */
@Composable
fun SearchDialog(mode: SearchMode, vm: ScannerViewModel) {
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
                        CardRow(c) { keyboard?.hide(); vm.pickFromSearch(c) }
                    }
                }
                if (results.size >= 50) Hint("Showing the first 50 – add a word to narrow it down.")
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
