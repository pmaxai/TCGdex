package io.github.pmaxai.cardscanner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.pmaxai.cardscanner.recording.GallerySaver
import io.github.pmaxai.cardscanner.recording.LoadedTemplates
import io.github.pmaxai.cardscanner.recording.PackCard
import io.github.pmaxai.cardscanner.recording.SubmissionForm
import io.github.pmaxai.cardscanner.recording.TemplateInput
import io.github.pmaxai.cardscanner.recording.TemplatesSource
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * After Stop (or a recording recovered after the app stopped): the submit screen. Leaving it
 * keeps the recording on the phone, unsubmitted; it waits in ⚙ → Recordings….
 */
@Composable
fun RecordingDoneDialog(state: UiState, vm: ScannerViewModel) {
    val r = state.recording
    val rec = r.toDecide ?: return
    SubmitDialog(state, vm, rec.id, "Recording finished", r.toDecideText,
        onSubmitted = {}, onLeave = { vm.rec.later(rec.id) }, onDeleted = {})
}

/**
 * Submit or delete a recording (R1): the video templates to make (R3) and their inputs (R4),
 * drawn from the templates list (R2). Submit writes submission.json and queues the upload;
 * Delete asks once more; Later, Back: the recording stays on the phone, unsubmitted.
 * With uploading to S3 off (⚙ → Record): Save puts the videos into the Gallery instead.
 * [preset], [presetPack], [presetMic]: given by tests instead of loading.
 */
@Composable
fun SubmitDialog(
    state: UiState, vm: ScannerViewModel, id: String, title: String, line: String?,
    onSubmitted: () -> Unit, onLeave: () -> Unit, onDeleted: () -> Unit,
    preset: LoadedTemplates? = null, presetPack: List<PackCard>? = null, presetMic: Boolean? = null,
) {
    var loaded by remember(id) { mutableStateOf(preset) }
    var pack by remember(id) { mutableStateOf(presetPack) }
    var mic by remember(id) { mutableStateOf(presetMic ?: false) }
    // null until the user ticks: the list's default template (R3)
    var ticked by remember(id) { mutableStateOf<Set<String>?>(null) }
    var values by remember(id) { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var open by remember(id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    var sent by remember(id) { mutableStateOf(false) }
    // uploading to S3 off: saved on the phone, in the Gallery - no templates (they are for the pipeline)
    val local = !state.recording.settings.uploadS3
    val ctx = LocalContext.current
    fun saveNow() { sent = true; vm.rec.saveToPhone(id); onSubmitted() }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) saveNow() else Toast.makeText(ctx, "Saving into the Gallery needs the storage permission", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(id, local) {
        if (local) return@LaunchedEffect
        if (loaded == null) loaded = vm.rec.loadTemplates()
        if (pack == null) pack = vm.rec.packCards(id)
        if (presetMic == null) mic = vm.rec.hasMicSound(id)
    }
    val l = loaded
    val chosen = ticked ?: l?.index?.templates?.filter { it.default }?.map { it.id }?.toSet() ?: emptySet()
    val fields = l?.index?.fieldsFor(chosen) ?: emptyList()
    fun value(f: TemplateInput): Any? = if (values.containsKey(f.id)) values[f.id] else SubmissionForm.initial(f)
    fun current(): Map<String, Any?> = fields.associate { it.id to value(it) }
    val problems = fields.mapNotNull { f -> SubmissionForm.problem(f, value(f)) }
    val canSubmit = l != null && chosen.isNotEmpty() && problems.isEmpty() && !sent

    Dialog(onDismissRequest = onLeave, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth(0.95f)) {
            Column(Modifier.padding(16.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    line?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }

                    if (local) {
                        Text("Save puts the video into the Gallery of this phone, in ${GallerySaver.place}" +
                            " (and the camera video, if recorded). Uploading to S3 is off (⚙ → Record).",
                            color = Color.White, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 14.dp))
                    } else {
                        Text("Videos to make", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                        if (l == null) {
                            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Loading the templates…", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                            }
                        } else {
                            l.index.templates.forEach { t ->
                                val on = t.id in chosen
                                Row(Modifier.fillMaxWidth().clickable { ticked = if (on) chosen - t.id else chosen + t.id }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top) {
                                    Checkbox(checked = on, onCheckedChange = { ticked = if (it) chosen + t.id else chosen - t.id },
                                        colors = CheckboxDefaults.colors(checkedColor = Gold, checkmarkColor = Ink))
                                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                                        Text(t.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        if (t.description.isNotBlank()) Text(t.description, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                                    }
                                }
                            }
                            sourceNote(l)?.let { (text, warn) -> Text(text, color = if (warn) Warn else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                            if (chosen.isEmpty()) Text("Tick at least one.", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }

                        if (fields.isNotEmpty()) {
                            Text("Details (all optional)", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                            fields.forEach { f ->
                                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    when (f.type) {
                                        "text" -> TextInput(f, value(f) as? String ?: "") { values = values + (f.id to it) }
                                        "number" -> NumberInput(f, value(f) as? String ?: "") { values = values + (f.id to it) }
                                        "toggle" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(f.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                            Switch(checked = value(f) as? Boolean ?: false, onCheckedChange = { values = values + (f.id to it) })
                                        }
                                        "choice" -> {
                                            Text(f.label, color = Color.White, fontSize = 15.sp)
                                            // "own" voice: only with the microphone's sound in the recording
                                            val options = f.options.filter { !(f.id == "voice" && it == "own" && !mic) }
                                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                options.forEach { o ->
                                                    FilterChip(selected = value(f) == o, onClick = { values = values + (f.id to o) }, label = { Text(o) })
                                                }
                                            }
                                        }
                                        "cards" -> CardsInput(f, fields, pack, open == f.id, onOpen = { open = if (open == f.id) null else f.id },
                                            chosen = { g -> (value(g) as? List<*>)?.filterIsInstance<Int>() ?: emptyList() }) { entry, on ->
                                            values = values + SubmissionForm.tickCard(fields, current(), f.id, entry, on)
                                        }
                                    }
                                }
                            }
                        }
                        if (state.recording.settings.wifiOnly) Text("It goes up on Wi-Fi.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ confirmDelete = true }, enabled = !sent) { Text("Delete", color = RecRed) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onLeave, enabled = !sent) { Text("Later") }
                    if (local) Button({
                        if (GallerySaver.needsPermission && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                            PackageManager.PERMISSION_GRANTED) askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else saveNow()
                    }, enabled = !sent) { Text("Save") }
                    else Button({
                        val ll = l ?: return@Button
                        sent = true
                        val order = ll.index.templates.filter { it.id in chosen }.map { it.id }
                        vm.rec.submit(id, order, SubmissionForm.inputs(fields, current()))
                        onSubmitted()
                    }, enabled = canSubmit) { Text("Submit") }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this recording?") },
            text = { Text(if (local) "Its video and files are deleted on this phone. Nothing goes into the Gallery."
                else "Its video and files are deleted on this phone. Nothing is uploaded.") },
            confirmButton = { Button({ confirmDelete = false; vm.rec.discard(id); onDeleted() }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } })
    }
}

/** Where the list came from, when it isn't the bucket's: (text, a warning). */
private fun sourceNote(l: LoadedTemplates): Pair<String, Boolean>? {
    val why = l.error?.let { if (it == "offline") "offline" else "the template list couldn't be loaded: $it" } ?: "the template list couldn't be loaded"
    return when (l.source) {
        TemplatesSource.BUCKET -> null
        TemplatesSource.SAVED -> ("${why.replaceFirstChar { it.uppercase() }} – the list saved " +
            (l.savedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "earlier") + ".") to false
        TemplatesSource.BUILT_IN -> ("${why.replaceFirstChar { it.uppercase() }} – only the default video for now.") to true
    }
}

@Composable
private fun TextInput(f: TemplateInput, text: String, onChange: (String) -> Unit) {
    val max = f.max?.toInt()
    OutlinedTextField(value = text, onValueChange = { onChange(if (max != null) it.take(max) else it) },
        label = { Text(f.label) }, singleLine = (max ?: Int.MAX_VALUE) <= 120,
        supportingText = { Text(listOfNotNull(f.help, max?.let { "${text.length}/$it" }).joinToString(" · ")) },
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NumberInput(f: TemplateInput, text: String, onChange: (String) -> Unit) {
    val problem = SubmissionForm.problem(f, text)
    val hint = problem ?: SubmissionForm.range(f)
    OutlinedTextField(value = text, onValueChange = onChange, label = { Text(f.label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        placeholder = if (f.default != null) { { Text(f.default.toString().removeSuffix(".0")) } } else null,
        isError = problem != null,
        supportingText = if (hint != null) { { Text(hint) } } else null,
        modifier = Modifier.fillMaxWidth())
}

/** A `cards` input: the recording's pack, each card ticked or not; a card ticked here leaves the other cards inputs. */
@Composable
private fun CardsInput(
    f: TemplateInput, fields: List<TemplateInput>, pack: List<PackCard>?, open: Boolean, onOpen: () -> Unit,
    chosen: (TemplateInput) -> List<Int>, onTick: (Int, Boolean) -> Unit,
) {
    val mine = chosen(f)
    Row(Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E3138), RoundedCornerShape(10.dp)).clickable(onClick = onOpen)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(f.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(if (mine.isEmpty()) "none" else "${mine.size} selected", color = if (mine.isEmpty()) Muted else Gold, fontSize = 13.sp)
        Text(if (open) "  ▾" else "  ▸", color = Muted, fontSize = 13.sp)
    }
    if (!open) return
    val cards = pack ?: emptyList()
    if (cards.isEmpty()) Text("No cards in this recording's pack.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
    val others = fields.filter { it.type == "cards" && it.id != f.id }
    cards.forEach { c ->
        val on = c.entry in mine
        val elsewhere = others.firstOrNull { c.entry in chosen(it) }
        Row(Modifier.fillMaxWidth().clickable { onTick(c.entry, !on) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = on, onCheckedChange = { onTick(c.entry, it) }, colors = CheckboxDefaults.colors(checkedColor = Gold, checkmarkColor = Ink))
            Column(Modifier.weight(1f)) {
                Text((if (c.hit) "★ " else "") + c.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                Text(listOf("${c.set} ${c.number}".trim(), price(c), elsewhere?.label.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Muted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

private fun price(c: PackCard): String = when {
    c.value == null -> ""
    c.currency == "USD" -> String.format(Locale.getDefault(), "$%.2f", c.value)
    else -> String.format(Locale.getDefault(), "%.2f €", c.value)
}
