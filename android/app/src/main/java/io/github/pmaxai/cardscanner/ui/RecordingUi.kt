package io.github.pmaxai.cardscanner.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import io.github.pmaxai.cardscanner.recording.RecStatus
import io.github.pmaxai.cardscanner.recording.RecordingController
import kotlinx.coroutines.delay
import java.util.Locale

val RecRed = Color(0xFFE5484D)

/**
 * ● REC next to +1 and ⚙. Idle: opens the start sheet (or the Recording settings while the
 * upload settings are incomplete). Recording: a red dot and the time; a tap stops.
 */
@Composable
fun RecButton(state: UiState, vm: ScannerViewModel, onStart: () -> Unit) {
    val r = state.recording
    val phase = r.phase
    if (phase is RecordingController.Phase.Recording || phase is RecordingController.Phase.Starting || phase is RecordingController.Phase.Stopping) {
        if (!r.settings.showTimer) return
        var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(phase) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(250) } }
        val secs = if (phase is RecordingController.Phase.Recording) ((now - phase.since) / 1000).coerceAtLeast(0) else 0
        val blink = (now / 600) % 2 == 0L
        Row(Modifier.padding(end = 8.dp).height(44.dp).background(Panel, RoundedCornerShape(22.dp)).border(2.dp, RecRed, RoundedCornerShape(22.dp))
            .clickable(enabled = phase is RecordingController.Phase.Recording) { vm.rec.stop() }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(if (blink || phase !is RecordingController.Phase.Recording) RecRed else RecRed.copy(alpha = 0.35f), CircleShape))
            Text(when (phase) {
                is RecordingController.Phase.Stopping -> "  saving…"
                is RecordingController.Phase.Starting -> "  starting…"
                else -> "  %d:%02d".format(Locale.ROOT, secs / 60, secs % 60)
            }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        Box(Modifier.padding(end = 8.dp).height(44.dp).border(2.dp, RecRed, RoundedCornerShape(22.dp))
            .clickable { if (r.settings.uploadReady) onStart() else vm.openSettings(SettingsPage.RECORDING) }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            Text("● REC", color = RecRed, fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
    }
}

/**
 * Before a recording: continue the pack or start a new one, microphone, the space it needs;
 * then Android's screen-capture consent (on Android 14+: "A single app" → Card Scanner).
 */
@Composable
fun StartSheet(state: UiState, vm: ScannerViewModel, landscape: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val r = state.recording
    var newPack by rememberSaveable { mutableStateOf(false) }
    var mic by rememberSaveable { mutableStateOf(r.settings.mic) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            if (newPack) vm.resetSession()
            if (mic != r.settings.mic) vm.rec.saveSettings(r.settings.copy(mic = mic), null)
            vm.rec.start(res.resultCode, data, landscape)
            onClose()
        } else {
            onClose()
        }
    }
    fun askConsent() {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        consent.launch(mpm.createScreenCaptureIntent())
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askConsent() }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> mic = ok }

    // an hour at the chosen settings; the camera file about as much again
    val perMinute = r.settings.bitrateMbps * 1_000_000L / 8 * 60 * (if (r.settings.recordCamera) 2 else 1)
    val free = remember { vm.rec.store.freeBytes() }
    val minutes = (free - io.github.pmaxai.cardscanner.recording.RecordingService.LOW_STORAGE) / perMinute

    AlertDialog(onDismissRequest = onClose,
        title = { Text("Start recording") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.session.isNotEmpty()) {
                    Text("The pack has ${state.session.size} cards", color = Color.White, fontSize = 14.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !newPack, onClick = { newPack = false }, label = { Text("Continue pack (${state.session.size})") })
                        FilterChip(selected = newPack, onClick = { newPack = true }, label = { Text("New pack") })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Microphone", color = Color.White, fontSize = 14.sp)
                        Text(if (mic) "your voice is in the video" else "no sound (the pipeline can add a voiceover)", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = mic, onCheckedChange = { on ->
                        if (on && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            askMic.launch(Manifest.permission.RECORD_AUDIO)
                        else mic = on
                    })
                }
                Text(if (minutes < 60) "⚠ Space for about $minutes minutes at these settings (an hour needs ${perMinute * 60 / 1_000_000_000} GB)"
                    else "Space for more than an hour", color = if (minutes < 60) Warn else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                Text((if (landscape) "Landscape" else "Portrait") + ", locked until Stop. Android now asks to share the screen" +
                    (if (Build.VERSION.SDK_INT >= 34) ": choose \"A single app\" → Card Scanner." else "."),
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            Button({
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else askConsent()
            }, enabled = minutes > 0) { Text("● Start") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

/** ⚙ → Recordings…: every recording with its upload status; Retry, Upload now, Share, Delete. */
@Composable
fun RecordingsDialog(state: UiState, vm: ScannerViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val r = state.recording
    LaunchedEffect(Unit) { while (true) { vm.rec.refresh(); delay(2_000) } }
    var confirm by remember { mutableStateOf<String?>(null) }
    var askUploadNow by remember { mutableStateOf<RecInfo?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = Modifier.fillMaxWidth(0.95f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recordings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClose) { Text("Close") }
                }
                r.lastSummary?.let { Text(it, color = Gold, fontSize = 13.sp) }
                if (r.batteryHint) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Color(0xFF2A2214), RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text("The phone's battery saver may stop uploads in the background. Allow Card Scanner to run unrestricted:",
                            color = Color.White, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) {
                                Text("Battery settings")
                            }
                            if (Build.MANUFACTURER.equals("xiaomi", true) || Build.MANUFACTURER.equals("redmi", true) || Build.MANUFACTURER.equals("poco", true)) {
                                TextButton({ runCatching { ctx.startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter",
                                    "com.miui.permcenter.autostart.AutoStartManagementActivity"))) } }) { Text("Autostart") }
                            }
                        }
                    }
                }
                if (r.recordings.isEmpty()) Text("No recordings yet. ● REC in YouTube mode starts one.", color = Muted, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp))
                r.recordings.forEach { rec ->
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, Color(0xFF2E3138), RoundedCornerShape(10.dp)).padding(10.dp)) {
                        val d = rec.durationMs / 1000
                        Text(rec.date, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("%d:%02d · %d cards · %d hits · %s".format(Locale.ROOT, d / 60, d % 60, rec.cards, rec.hits, size(rec.bytes)),
                            color = Muted, fontSize = 12.sp)
                        Text(rec.statusText, color = when (rec.status) {
                            RecStatus.UPLOADED -> Sure; RecStatus.FAILED -> Warn; RecStatus.UPLOADING -> Gold; else -> Color.White
                        }, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (rec.status == RecStatus.FAILED) TextButton({ vm.rec.retry(rec.id) }) { Text("Retry") }
                            if (rec.status == RecStatus.QUEUED || rec.status == RecStatus.LOCAL || rec.status == RecStatus.FAILED)
                                TextButton({ askUploadNow = rec }) { Text("Upload now") }
                            if (rec.status != RecStatus.RECORDING && rec.bytes > 0) TextButton({
                                vm.rec.shareIntent(rec.id)?.let { runCatching { ctx.startActivity(it) } }
                            }) { Text("Share") }
                            if (rec.status != RecStatus.RECORDING) TextButton({ confirm = rec.id }) { Text("Delete", color = Warn) }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    confirm?.let { id ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete this recording?") },
            text = { Text("It is deleted on this phone; an upload in progress stops (files already in the bucket stay).") },
            confirmButton = { Button({ vm.rec.delete(id); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Keep") } })
    }
    askUploadNow?.let { rec ->
        AlertDialog(onDismissRequest = { askUploadNow = null }, title = { Text("Upload now?") },
            text = { Text("${size(rec.bytes)} – also over mobile data, this one time.") },
            confirmButton = { Button({ vm.rec.uploadNow(rec.id); askUploadNow = null }) { Text("Upload") } },
            dismissButton = { TextButton({ askUploadNow = null }) { Text("Cancel") } })
    }
}

fun size(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(Locale.ROOT, bytes / 1e9) else "%d MB".format(Locale.ROOT, bytes / 1_000_000)

