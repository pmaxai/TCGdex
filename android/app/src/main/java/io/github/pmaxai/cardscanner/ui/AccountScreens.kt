package io.github.pmaxai.cardscanner.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.pmaxai.cardscanner.BuildConfig
import io.github.pmaxai.cardscanner.account.AccountGate
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.shop.ShopTerms
import io.github.pmaxai.cardscanner.trading.LineStatus
import io.github.pmaxai.cardscanner.trading.Side
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun stamp(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

/**
 * Before the app can be used: connect it to the account (scan the portal's QR code, or type the
 * code); "Your subscription has ended"; "Connect to the internet once…"; and right after
 * connecting, the account and plan.
 */
@Composable
fun AccountGateScreen(state: UiState, vm: ScannerViewModel, camera: @Composable (Modifier) -> Unit = { m -> QrCamera(m) { vm.account.pair(it, scanned = true) } }) {
    val a = state.account
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val st = a.status
        when {
            a.justConnected && st != null -> {
                Text("Connected", color = Sure, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                AccountSummary(st)
                Button({ vm.account.connectedSeen() }, modifier = Modifier.padding(top = 18.dp)) { Text("Continue") }
            }
            a.gate == AccountGate.ENDED -> {
                Text("Your subscription has ended", color = Warn, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                st?.let { AccountSummary(it) }
                Text("Renew it in the portal; the app unlocks again by itself – no new QR code needed.", color = Muted, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                CheckButtons(a, vm)
            }
            a.gate == AccountGate.CHECK_ONLINE -> {
                Text("Connect to the internet once to check your subscription", color = Warn, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                st?.let { Text("Last checked ${a.lastCheck?.let(::stamp) ?: "–"} · ${it.email ?: ""}", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp)) }
                CheckButtons(a, vm)
            }
            else -> ConnectPanel(state, vm, camera)
        }
        a.error?.let { Text("⚠ $it", color = Warn, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
        if (a.gate == AccountGate.CONNECT && !a.justConnected) {
            TextButton({ openUrl(ctx, BuildConfig.ACCOUNT_URL.trimEnd('/') + "/app") }, modifier = Modifier.padding(top = 14.dp)) {
                Text("No account yet? Subscribe at overpricedcardboard.com/app", textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun CheckButtons(a: AccountUi, vm: ScannerViewModel) {
    val ctx = LocalContext.current
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button({ openUrl(ctx, a.portalUrl) }) { Text("Manage subscription") }
        OutlinedButton({ vm.account.check(force = true, manual = true) }, enabled = !a.busy) {
            if (a.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Check again")
        }
    }
}

@Composable
private fun AccountSummary(st: AccountStatus) {
    Text(st.email ?: "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Text("${st.planName} · ${st.stateText}", color = if (st.state == "past_due") Warn else Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
}

@Composable
private fun ConnectPanel(state: UiState, vm: ScannerViewModel, camera: @Composable (Modifier) -> Unit) {
    val a = state.account
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    Text("Connect the app to your account", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    a.notice?.let { Text(it, color = Warn, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
    Text("In the portal on overpricedcardboard.com: Account › Devices › Connect a device. Scan the QR code shown there.",
        color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !typing, onClick = { typing = false }, label = { Text("Scan QR code") })
        FilterChip(selected = typing, onClick = { typing = true }, label = { Text("Type the code") })
    }
    Spacer(Modifier.height(12.dp))
    if (!typing) {
        Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).background(Panel),
            contentAlignment = Alignment.Center) {
            camera(Modifier.fillMaxSize())
            Box(Modifier.size(190.dp).border(3.dp, Gold, RoundedCornerShape(14.dp)))
            if (a.busy) CircularProgressIndicator(color = Gold)
        }
        Text("Point the camera at the QR code", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    } else {
        OutlinedTextField(typed, { typed = it.uppercase().take(16) }, label = { Text("Code") }, singleLine = true,
            placeholder = { Text("K7Q2-9MZ4-D01A") },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth())
        Button({ vm.account.pair(typed, scanned = false) }, enabled = !a.busy && typed.isNotBlank(), modifier = Modifier.padding(top = 10.dp)) {
            if (a.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink) else Text("Connect")
        }
        Text("12 characters, as shown under the QR code; valid for 10 minutes.", color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** ⚙ → Account: who, which plan, the devices; Manage subscription, Move to a new phone, Disconnect this phone. */
@Composable
fun AccountPage(state: UiState, vm: ScannerViewModel) {
    val a = state.account
    val ctx = LocalContext.current
    val st = a.status
    var confirm by remember { mutableStateOf(false) }
    if (st == null) {
        Text("Not connected to an account", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        return
    }
    Text(st.email ?: "", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    st.name?.let { Text(it, color = Muted, fontSize = 13.sp) }
    Text("${st.planName} · ${st.stateText}", color = if (st.state == "past_due") Warn else Gold, fontSize = 15.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    Text("Devices: ${st.devicesText}" + (st.deviceName?.let { " · this phone: $it" } ?: ""), color = Color.White, fontSize = 14.sp,
        modifier = Modifier.padding(top = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Last checked ${a.lastCheck?.let(::stamp) ?: "–"}", color = Muted, fontSize = 12.sp)
        TextButton({ vm.account.check(force = true, manual = true) }, enabled = !a.busy) { Text("Check again") }
    }
    a.error?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp) }

    Button({ openUrl(ctx, a.portalUrl) }, modifier = Modifier.padding(top = 10.dp)) { Text("Manage subscription") }

    Text("Move to a new phone", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
    Text("On the new phone, open the app and scan this code. It takes over this phone's place; this one is disconnected then.",
        color = Muted, fontSize = 13.sp)
    val t = a.transfer
    when {
        t == null -> OutlinedButton({ vm.account.startTransfer() }, modifier = Modifier.padding(top = 8.dp)) { Text("Move to a new phone") }
        t.busy -> CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp), strokeWidth = 2.dp)
        t.code != null -> Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            QrImage(t.code.url, 240.dp)
            Text(t.code.code, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Valid until " + (t.code.expiresAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "–") +
                " · waiting for the new phone…", color = Muted, fontSize = 13.sp)
            TextButton({ vm.account.stopTransfer() }) { Text("Hide") }
        }
        else -> {
            Text("⚠ ${t.error}", color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton({ vm.account.startTransfer() }) { Text("Show a new code") }
        }
    }

    if (a.pending.isNotEmpty()) {
        Text("Waiting to sync", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        Text("Sent as soon as there is a connection.", color = Muted, fontSize = 13.sp)
        a.pending.forEach { op ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text((when (op.kind) { "collection_add" -> "+ Collection"; "wanted_add" -> "☆ Missing"; else -> "☆ off the list" }) + " · " + op.label,
                    color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton({ vm.account.dropPending(op.clientRef) }) { Text("✕", color = Warn) }
            }
        }
    }

    Text("Disconnect this phone", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
    Text("Frees its place for another device. The app asks for a QR code again.", color = Muted, fontSize = 13.sp)
    OutlinedButton({ confirm = true }, enabled = !a.busy, modifier = Modifier.padding(top = 8.dp)) { Text("Disconnect this phone", color = Warn) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Disconnect this phone?") },
        text = { Text("The app on this phone stops until it is connected again with a QR code from the portal.") },
        confirmButton = { Button({ confirm = false; vm.account.disconnect() }) { Text("Disconnect") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

// ---------------------------------------------------------------- collection and missing cards

private val CONDITIONS = listOf("M", "NM", "EX", "GD", "LP", "PL", "PO")

/**
 * Under the shown card: + Collection (long press: condition, quantity, price, binder, note),
 * ☆ Missing, "In collection: 2", the shop's offer. [condition]: what + Collection uses.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionBar(state: UiState, vm: ScannerViewModel, s: Shown, condition: String = "NM") {
    val a = state.account
    val coll = a.has("collection"); val want = a.has("wanted")
    if (!coll && !want) return
    val ctx = LocalContext.current
    var sheet by remember { mutableStateOf(false) }
    val cardId = s.version.id
    val lang = ShopTerms.languageOf(s.printedOnly ?: s.version.lang)
    val finish = ShopTerms.finishOf(s.printing)
    val wanted = a.wanted.firstOrNull { it.matches(cardId, lang, finish) }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (coll) Text("+ Collection", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Gold, RoundedCornerShape(20.dp))
                .combinedClickable(onClick = { vm.addShownToCollection(condition) }, onLongClick = { vm.account.loadBinders(); sheet = true })
                .padding(horizontal = 14.dp, vertical = 8.dp))
        if (want) Text(if (wanted != null) "★ Missing" else "☆ Missing", color = if (wanted != null) Gold else Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.border(1.dp, if (wanted != null) Gold else Muted, RoundedCornerShape(20.dp))
                .clickable { vm.toggleShownWanted() }.padding(horizontal = 14.dp, vertical = 7.dp))
    }
    if (coll) {
        val lines = a.collection[cardId]
        val waiting = a.pending.filter { it.kind == "collection_add" && it.cardId == cardId }.sumOf { it.body?.optInt("quantity", 1) ?: 1 }
        val have = lines?.sumOf { it.quantity } ?: 0
        val text = buildString {
            if (lines != null || waiting > 0) append("In collection: $have")
            if (have > 0) append(" (" + lines!!.joinToString(" · ") { "${it.quantity} × ${it.language} ${ShopTerms.finishLabel(it.finish)} ${it.condition}" + (it.graded?.let { g -> " $g" } ?: "") } + ")")
            if (waiting > 0) append(" · +$waiting waiting to sync")
        }
        if (text.isNotBlank()) Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
    when {
        wanted?.pending == true -> Text("☆ waiting to sync", color = Muted, fontSize = 12.sp)
        wanted?.state == "offer" -> Text("Offer from the shop ›", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { openUrl(ctx, a.portalUrl.trimEnd('/') + "/missing") }.padding(vertical = 4.dp))
        wanted?.state == "available" -> Text("In the shop now ›", color = Sure, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { openUrl(ctx, a.portalUrl.trimEnd('/') + "/missing") }.padding(vertical = 4.dp))
    }
    if (sheet) CollectionSheet(a, condition, onClose = { sheet = false }) { c, q, cents, binder, note ->
        vm.addShownToCollection(c, q, cents, binder, note)
    }
}

/** Long press on + Collection: the details of the copy. */
@Composable
private fun CollectionSheet(a: AccountUi, condition: String, onClose: () -> Unit, onAdd: (String, Int, Long?, Long?, String?) -> Unit) {
    var cond by remember { mutableStateOf(condition) }
    var qty by remember { mutableIntStateOf(1) }
    var price by remember { mutableStateOf("") }
    var binder by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    val cents = price.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { Math.round(it * 100) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Add to the collection") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Condition", color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CONDITIONS.forEach { c -> FilterChip(selected = c == cond, onClick = { cond = c }, label = { Text(c) }) }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Quantity", color = Muted, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                OutlinedButton({ qty = (qty - 1).coerceAtLeast(1) }) { Text("−") }
                Text("$qty", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                OutlinedButton({ qty = (qty + 1).coerceAtMost(999) }) { Text("+") }
            }
            OutlinedTextField(price, { price = it }, label = { Text("Purchase price per copy (€)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = price.isNotBlank() && cents == null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (a.binders.isNotEmpty()) {
                Text("Binder", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = binder == null, onClick = { binder = null }, label = { Text("Main collection") })
                    a.binders.forEach { (id, name) -> FilterChip(selected = binder == id, onClick = { binder = id }, label = { Text(name) }) }
                }
            }
            OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }, confirmButton = {
        Button({ onAdd(cond, qty, cents, binder, note.ifBlank { null }); onClose() }, enabled = price.isBlank() || cents != null) { Text("Add") }
    }, dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

/** Trading, a cart marked Paid: "Add the N cards to your collection". */
@Composable
fun CartCollectionOffer(state: UiState, vm: ScannerViewModel) {
    val id = state.account.offerCart ?: return
    val cart = state.book.carts.firstOrNull { it.id == id } ?: return
    val n = cart.lines.filter { it.side == Side.GET && it.status == LineStatus.BOUGHT }.sumOf { it.qty }
    if (n == 0) return
    AlertDialog(onDismissRequest = vm::dismissCartOffer, title = { Text("Add the $n cards to your collection?") },
        text = { Text("Each with its condition and the price you paid per copy.") },
        confirmButton = { Button({ vm.addCartToCollection(id) }) { Text("Add") } },
        dismissButton = { TextButton(vm::dismissCartOffer) { Text("Not now") } })
}

// ---------------------------------------------------------------- QR codes

/** A QR code, black on white with a quiet zone, sharp at any size. */
@Composable
fun QrImage(text: String, size: Dp) {
    val bitmap = remember(text) { qrBitmap(text) }
    Image(bitmap.asImageBitmap(), contentDescription = "QR code", filterQuality = FilterQuality.None,
        modifier = Modifier.size(size).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp))
}

fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

/** The back camera, reading QR codes; [onText] gets what one holds (the same text at most every two seconds). */
@Composable
fun QrCamera(modifier: Modifier, onText: (String) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Box(modifier, contentAlignment = Alignment.Center) { Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } }
        return
    }
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        val reader = QrReader { text -> ContextCompat.getMainExecutor(ctx).execute { onText(text) } }
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor, reader)
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Reads QR codes from the camera's frames (the Y plane is enough). */
private class QrReader(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val last = AtomicLong(0)
    @Volatile private var lastText: String? = null
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining()).also { b -> buf.get(b) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
            val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
            reader.reset()
            if (text.isNullOrBlank()) return
            if (text == lastText && System.currentTimeMillis() - last.get() < 2000) return
            lastText = text; last.set(System.currentTimeMillis())
            onText(text)
        }
    }
}
