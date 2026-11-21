package io.github.pmaxai.cardscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.shop.ShopTerms
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The collection's public link (shop doc: sharing from the app): anyone with it sees the whole collection with prices. */
data class PublicLink(val url: String, val views: Int, val createdAt: Long?) {
    companion object {
        fun of(o: JSONObject): PublicLink? = AccountStatus.str(o, "url")?.let { PublicLink(it, o.optInt("views", 0), AccountStatus.time(o, "created_at")) }

        const val NOT_YET = "Sharing from the app isn't on overpricedcardboard.com yet – it comes with the next update of the website."
    }
}

/** The details of a copy as the edit sheet changes them (a copy in the account's collection, or on the phone). */
data class CopyDetails(
    val quantity: Int, val condition: String, val finish: String, val language: String,
    val graded: Boolean, val company: String, val grade: String,
    val purchaseCents: Long?, val note: String, val binderId: Long?,
) {
    /** the grading as the shop takes it (null: not graded) */
    val grading: JSONObject? get() = if (!graded) null else
        JSONObject().put("company", company.trim().take(20)).put("grade", grade.trim().take(10))

    /** "PSA|10" (null: not graded), to compare */
    val gradingKey: String? get() = grading?.let { "${it.optString("company")}|${it.optString("grade")}" }

    val trimmedNote: String? get() = note.trim().takeIf { it.isNotEmpty() }?.take(500)

    /** why it can't be saved (null: it can) */
    val problem: String? get() =
        if (graded && (company.isBlank() || grade.isBlank())) "A graded card needs the company and the grade (e.g. PSA 10)." else null

    /** The fields that differ from [old], as PATCH /collection/items/{id} takes them (`null` clears). */
    fun changes(old: CopyDetails): JSONObject {
        val j = JSONObject()
        if (quantity != old.quantity) j.put("quantity", quantity.coerceIn(1, 999))
        if (condition != old.condition) j.put("condition", condition)
        if (finish != old.finish) j.put("finish", finish)
        if (language != old.language) j.put("language", language)
        if (gradingKey != old.gradingKey) j.put("grading", grading ?: JSONObject.NULL)
        if (purchaseCents != old.purchaseCents) j.put("purchase_price_cents", purchaseCents ?: JSONObject.NULL)
        if (trimmedNote != old.trimmedNote) j.put("note", trimmedNote ?: JSONObject.NULL)
        if (binderId != old.binderId) j.put("binder_id", binderId ?: JSONObject.NULL)
        return j
    }

    companion object {
        fun of(i: CollectionItem) = CopyDetails(i.quantity, i.condition, i.finish, i.language, i.gradingCompany != null || i.gradingGrade != null,
            i.gradingCompany ?: "", i.gradingGrade ?: "", i.purchaseCents, i.note ?: "", i.binderId)

        fun of(i: LocalItem) = CopyDetails(i.quantity, i.condition, i.finish, i.language, i.gradingCompany != null || i.gradingGrade != null,
            i.gradingCompany ?: "", i.gradingGrade ?: "", i.purchaseCents, i.note ?: "", null)
    }
}

/** "2 × DE · Holo · NM · PSA 10" */
fun copyLine(quantity: Int, language: String, finish: String, condition: String, graded: String?): String =
    "$quantity × " + listOf(language, ShopTerms.finishLabel(finish), condition, graded ?: "").filter { it.isNotBlank() }.joinToString(" · ")

/**
 * The collection page's Edit: every detail the shop allows (quantity, condition, printing,
 * language, grading, purchase price, binder, note), and Delete (asked first). For a copy in the
 * account's collection or on the phone. [onSave] / [onDelete] answer null when done, else why not.
 */
@Composable
fun EditCopySheet(title: String, binders: List<Pair<Long, String>>, details: CopyDetails, onClose: () -> Unit,
                  onSave: suspend (CopyDetails) -> String?, onDelete: suspend () -> String?) {
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(details) }
    var price by remember { mutableStateOf(details.purchaseCents?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // the purchase price: blank → none; not a price → can't save
    val t = price.trim().replace(',', '.')
    val priceOk = t.isEmpty() || t.toDoubleOrNull()?.let { it >= 0 && it < 10_000_000 } == true
    val cents = t.toDoubleOrNull()?.takeIf { it >= 0 }?.let { Math.round(it * 100) }
    val languages = if (ShopTerms.LANGUAGES.any { it.first == d.language }) ShopTerms.LANGUAGES else listOf(d.language to d.language) + ShopTerms.LANGUAGES
    val finishes = if (d.finish in ShopTerms.FINISHES) ShopTerms.FINISHES else listOf(d.finish) + ShopTerms.FINISHES

    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(title, maxLines = 2) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quantity", color = Muted, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                OutlinedButton({ d = d.copy(quantity = (d.quantity - 1).coerceAtLeast(1)) }) { Text("−") }
                Text("${d.quantity}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                OutlinedButton({ d = d.copy(quantity = (d.quantity + 1).coerceAtMost(999)) }) { Text("+") }
            }
            Label("Condition")
            Chips(ShopTerms.CONDITIONS.map { it.code to it.code }, d.condition) { d = d.copy(condition = it) }
            Label("Printing")
            Chips(finishes.map { it to ShopTerms.finishLabel(it) }, d.finish) { d = d.copy(finish = it) }
            Label("Language")
            Chips(languages.map { it.first to it.second }, d.language) { d = d.copy(language = it) }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Graded", color = Color.White, modifier = Modifier.weight(1f))
                Switch(d.graded, { d = d.copy(graded = it) })
            }
            if (d.graded) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(d.company, { d = d.copy(company = it.take(20)) }, label = { Text("Company") }, placeholder = { Text("PSA") },
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(d.grade, { d = d.copy(grade = it.take(10)) }, label = { Text("Grade") }, placeholder = { Text("10") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(price, { price = it }, label = { Text("Purchase price per copy (€)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !priceOk,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (binders.isNotEmpty()) {
                Label("Binder")
                Chips(listOf(-1L to "Main collection") + binders, d.binderId ?: -1L) { d = d.copy(binderId = it.takeIf { id -> id >= 0 }) }
            }
            OutlinedTextField(d.note, { d = d.copy(note = it.take(500)) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            (error ?: d.problem)?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            TextButton({ confirmDelete = true }, enabled = !busy, modifier = Modifier.padding(top = 6.dp)) {
                Text("Delete from the collection", color = Warn)
            }
        }
    }, confirmButton = {
        Button({
            busy = true
            scope.launch {
                error = onSave(d.copy(purchaseCents = if (t.isEmpty()) null else cents))
                busy = false
                if (error == null) onClose()
            }
        }, enabled = !busy && priceOk && d.problem == null) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink) else Text("Save")
        }
    }, dismissButton = { TextButton(onClose, enabled = !busy) { Text("Cancel") } })

    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${details.quantity} × $title?") },
        text = { Text("It leaves your collection.") },
        confirmButton = {
            TextButton({
                confirmDelete = false
                busy = true
                scope.launch {
                    error = onDelete()
                    busy = false
                    if (error == null) onClose()
                }
            }) { Text("Delete", color = Warn) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun Label(text: String) = Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label) }) }
    }
}

/**
 * The collection's Share: a public link of the whole collection with prices to send to friends, and
 * Make private (every public link stops; personal links sent by email from the website keep running).
 * [load] / [makePublic] / [makePrivate] answer null when done, else why not; the link comes in [a].
 */
@Composable
fun ShareSheet(a: AccountUi, onClose: () -> Unit, load: suspend () -> String?, makePublic: suspend () -> String?,
               makePrivate: suspend () -> String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var confirmPrivate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { error = load(); loading = false }
    fun act(call: suspend () -> String?) {
        busy = true; copied = false
        scope.launch { error = call(); busy = false }
    }
    val link = a.publicLink

    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Share your collection") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
                link != null -> {
                    Text("🌐  Public", color = Sure, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Anyone with the link sees your whole collection and what the cards are worth.", color = Muted, fontSize = 13.sp)
                    SelectionContainer { Text(link.url, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                    Text((if (link.views == 1) "1 view" else "${link.views} views") +
                        (link.createdAt?.let { " · since " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: ""),
                        color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Button({ send(ctx, link.url) }) { Text("Send the link") }
                        OutlinedButton({ copyToClipboard(ctx, link.url); copied = true }) { Text(if (copied) "Copied" else "Copy") }
                    }
                    TextButton({ confirmPrivate = true }, enabled = !busy) { Text("Make private", color = Warn) }
                }
                else -> {
                    Text("🔒  Private", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Only you see your collection.", color = Muted, fontSize = 13.sp)
                    if (error != PublicLink.NOT_YET) {
                        Button({ act(makePublic) }, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text("Create a public link") }
                        Text("Anyone with the link sees your whole collection and what the cards are worth. You can make it private again at any time.",
                            color = Muted, fontSize = 12.sp)
                    }
                    if (a.otherPublicLinks > 0) TextButton({ confirmPrivate = true }, enabled = !busy) { Text("Make private", color = Warn) }
                }
            }
            if (!loading && a.otherPublicLinks > 0) Text(if (a.otherPublicLinks == 1) "1 more public link made on the website is on too; Make private stops it as well."
                else "${a.otherPublicLinks} more public links made on the website are on too; Make private stops them as well.", color = Muted, fontSize = 12.sp)
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            error?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp) }
        }
    }, confirmButton = { TextButton(onClose, enabled = !busy) { Text("Done") } })

    if (confirmPrivate) AlertDialog(onDismissRequest = { confirmPrivate = false }, title = { Text("Make your collection private?") },
        text = { Text("Every public link stops working at once. Personal links you sent by email from the website keep running until they end.") },
        confirmButton = { TextButton({ confirmPrivate = false; act(makePrivate) }) { Text("Make private", color = Warn) } },
        dismissButton = { TextButton({ confirmPrivate = false }) { Text("Cancel") } })
}

/** the phone's share sheet with the link */
private fun send(ctx: Context, url: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "My Pokémon card collection")
        .putExtra(Intent.EXTRA_TEXT, "My collection on Overpriced Cardboard: $url")
    ctx.startActivity(Intent.createChooser(i, "Send the link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copyToClipboard(ctx: Context, url: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Collection link", url))
}
