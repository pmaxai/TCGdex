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
import io.github.pmaxai.cardscanner.account.storeEdition
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
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AccountGateContent(state, vm, camera)
    }
}

/** The sign-in (or connect), the plans, "ended", "check online once": the gate screen's part, also ⚙ → Account in the free version. */
@Composable
fun AccountGateContent(state: UiState, vm: ScannerViewModel, camera: @Composable (Modifier) -> Unit = { m -> QrCamera(m) { vm.account.pair(it, scanned = true) } }) {
    val a = state.account
    val ctx = LocalContext.current
    run {
        val st = a.status
        when {
            a.justConnected && st != null -> {
                Text("Connected", color = Sure, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                AccountSummary(st)
                Button({ vm.account.connectedSeen() }, modifier = Modifier.padding(top = 18.dp)) { Text("Continue") }
            }
            a.gate == AccountGate.ENDED && storeEdition -> OffersPanel(state, vm)
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
            storeEdition -> StoreSignIn(state, vm, camera)
            else -> ConnectPanel(state, vm, camera)
        }
        a.error?.let { Text("⚠ $it", color = Warn, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
        if (a.gate == AccountGate.CONNECT && !a.justConnected && !storeEdition) {
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
        manageUrl(a)?.let { url -> Button({ openUrl(ctx, url) }) { Text("Manage subscription") } }
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
    if (a.isFree) {
        // the free version: what it includes, then the sign-in or the plans (signed in: sign out and delete here too)
        FreeNote()
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) { AccountGateContent(state, vm) }
        if (st != null) {
            SignOut(a, vm)
            DeleteAccount(a, vm)
        }
        TextButton({ openUrl(ctx, StoreCoordinator.privacyUrl) }, modifier = Modifier.padding(top = 10.dp)) { Text("Privacy policy") }
        return
    }
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
        Text("Last checked ${a.lastCheck?.let(::stamp) ?: "–"}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton({ vm.account.check(force = true, manual = true) }, enabled = !a.busy) { Text("Check again", maxLines = 1) }
    }
    a.error?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp) }

    if (storeEdition) StorePart(state, vm, st)
    else Button({ openUrl(ctx, a.portalUrl) }, modifier = Modifier.padding(top = 10.dp)) { Text("Manage subscription") }

    if (a.has("collection") || a.has("wanted")) {
        Text("Collection", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        CollectionLinks(state, vm, Modifier.padding(top = 8.dp))
    }

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

    SignOut(a, vm)
    DeleteAccount(a, vm)
    TextButton({ openUrl(ctx, StoreCoordinator.privacyUrl) }, modifier = Modifier.padding(top = 10.dp)) { Text("Privacy policy") }
}

/** Sign out (Play edition) or disconnect this phone: asked first; the app goes on as the free version. */
@Composable
private fun SignOut(a: AccountUi, vm: ScannerViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Text(if (storeEdition) "Sign out of this phone" else "Disconnect this phone", color = Color.White, fontSize = 16.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
    Text(if (storeEdition) "Frees its place for another device. The app goes on as the free version until you sign in again."
        else "Frees its place for another device. The app goes on as the free version until it is connected again.", color = Muted, fontSize = 13.sp)
    OutlinedButton({ confirm = true }, enabled = !a.busy, modifier = Modifier.padding(top = 8.dp)) {
        Text(if (storeEdition) "Sign out" else "Disconnect this phone", color = Warn)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (storeEdition) "Sign out of this phone?" else "Disconnect this phone?") },
        text = { Text(if (storeEdition) "The app on this phone goes back to the free version until you sign in again. The subscription goes on."
            else "The app on this phone goes back to the free version until it is connected again with a QR code from the portal.") },
        confirmButton = { Button({ confirm = false; vm.account.disconnect() }) { Text(if (storeEdition) "Sign out" else "Disconnect") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

/** Delete the account (both stores require it in the app): asked first. */
@Composable
private fun DeleteAccount(a: AccountUi, vm: ScannerViewModel) {
    var ask by remember { mutableStateOf(false) }
    Text("Delete account", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
    Text("Deletes the account with its collection and missing cards, and signs out every device.", color = Muted, fontSize = 13.sp)
    OutlinedButton({ ask = true }, enabled = !a.busy, modifier = Modifier.padding(top = 8.dp)) { Text("Delete account…", color = Warn) }
    if (ask) AlertDialog(onDismissRequest = { ask = false }, title = { Text("Delete your account?") },
        text = { Text("Your collection, missing cards and sign-ins are deleted at once, and every device is signed out. " +
            "The subscription stops renewing; time already paid isn't refunded. This can't be undone.") },
        confirmButton = { Button({ ask = false; vm.account.deleteAccount() }) { Text("Delete") } },
        dismissButton = { TextButton({ ask = false }) { Text("Cancel") } })
}

/** where Manage subscription leads: the store's page for a store subscription; the portal only in the direct edition */
private fun manageUrl(a: AccountUi): String? {
    val st = a.status
    return when {
        !storeEdition -> a.portalUrl
        st?.source == "google_play" -> st.manageUrl ?: "https://play.google.com/store/account/subscriptions"
        else -> null
    }
}

/** the Activity behind a Compose context (the store's sheets need it) */
fun Context.activity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}

// ---------------------------------------------------------------- the Play edition: sign-in, plans, devices

/** Sign in to subscribe (or to use a subscription): Continue with Google, or a code by email; the portal's QR code too. */
@Composable
private fun StoreSignIn(state: UiState, vm: ScannerViewModel, camera: @Composable (Modifier) -> Unit) {
    val a = state.account
    val ctx = LocalContext.current
    var withQr by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    if (withQr) {
        ConnectPanel(state, vm, camera)
        TextButton({ withQr = false }, modifier = Modifier.padding(top = 10.dp)) { Text("‹ Back to sign-in") }
        return
    }
    Text("Overpriced Cardboard", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Text("Sign in to choose a plan, or to use the one you have.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp))
    a.notice?.let { Text(it, color = Warn, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
    if (BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()) {
        Button({ ctx.activity()?.let { vm.signInWithGoogle(it) } }, enabled = !a.busy, modifier = Modifier.padding(top = 18.dp).widthIn(min = 260.dp)) {
            Text("Continue with Google")
        }
        Text("or", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    }
    val sentTo = a.emailSentTo
    if (sentTo == null) {
        OutlinedTextField(email, { email = it.trim().take(120) }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.padding(top = 10.dp).widthIn(max = 360.dp).fillMaxWidth())
        OutlinedButton({ vm.account.emailCode(email) }, enabled = !a.busy && email.contains('@'), modifier = Modifier.padding(top = 8.dp)) {
            Text(if (BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()) "Use another email" else "Send me a code")
        }
    } else {
        Text("We sent a 6-digit code to $sentTo.", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 220.dp))
        Button({ vm.account.emailVerify(code) }, enabled = !a.busy && code.length == 6, modifier = Modifier.padding(top = 8.dp)) { Text("Sign in") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ code = ""; vm.account.emailBack() }) { Text("Another address") }
            TextButton({ vm.account.emailCode(sentTo) }, enabled = !a.busy && System.currentTimeMillis() >= a.resendAt) { Text("Send again") }
        }
    }
    if (a.busy) CircularProgressIndicator(Modifier.padding(top = 10.dp).size(24.dp), strokeWidth = 2.dp)
    TextButton({ withQr = true }, modifier = Modifier.padding(top = 18.dp)) {
        Text("Have a QR code from the portal? Connect with it", textAlign = TextAlign.Center)
    }
}

/** the plan's features in words */
private fun featureWords(f: List<String>): String = f.mapNotNull {
    when (it) {
        "scanner" -> "Scanner"; "trading" -> "Trading"; "youtube" -> "YouTube mode"; "recording" -> "recording"
        "collection" -> "collection"; "wanted" -> "missing cards"; else -> null
    }
}.joinToString(" · ")

/** Signed in, no running subscription: the plans with Google Play's prices. */
@Composable
private fun OffersPanel(state: UiState, vm: ScannerViewModel) {
    val a = state.account
    val s = a.store
    val st = a.status
    val ctx = LocalContext.current
    LaunchedEffect(st?.accountId) { vm.store.refresh() }
    Text(if (st == null || st.none) "Choose your plan" else "Your subscription has ended", color = if (st?.none == false) Warn else Color.White,
        fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    st?.email?.let { Text("Signed in as $it", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
    if (st != null && !st.emailVerified) Text("Confirm your email: open the link we sent you.", color = Gold, fontSize = 13.sp,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    if (s.loading) CircularProgressIndicator(Modifier.padding(top = 16.dp).size(28.dp), strokeWidth = 2.dp)
    CreatorCode(s, vm)
    s.onSale.forEach { plan -> PlanCard(plan, s, enabled = !s.busy && !a.busy) { period -> ctx.activity()?.let { vm.store.buy(it, plan, period) } } }
    if (!s.loading && s.onSale.isEmpty() && s.error == null)
        Text("The plans aren't on sale in Google Play yet.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
    StoreLines(s)
    if (s.busy) CircularProgressIndicator(Modifier.padding(top = 10.dp).size(24.dp), strokeWidth = 2.dp)
    Text("A subscription renews automatically until you cancel it in Google Play.", color = Muted, fontSize = 12.sp,
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton({ vm.store.restore() }, enabled = !s.busy) { Text("Restore purchases") }
        TextButton({ vm.account.check(force = true, manual = true) }, enabled = !a.busy) { Text("Check again") }
    }
    TextButton({ vm.account.disconnect() }, enabled = !a.busy) { Text("Sign out", color = Muted) }
}

@Composable
private fun StoreLines(s: StoreUi) {
    s.error?.let { Text("⚠ $it", color = Warn, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
    s.note?.let { Text(it, color = Sure, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
    if (s.waiting > 0) Text("A purchase waits to be confirmed: it is sent again as soon as there is a connection.", color = Muted,
        fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
}

/** The creator code: the account's (with its discount), or a field to type one in. */
@Composable
private fun CreatorCode(s: StoreUi, vm: ScannerViewModel) {
    val aff = s.affiliate
    if (aff != null) {
        Text("Creator code ${aff.code}" + (aff.discountText?.let { " · $it" } ?: ""), color = Sure, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        return
    }
    var open by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    if (!open) {
        TextButton({ open = true }, modifier = Modifier.padding(top = 6.dp)) { Text("Have a creator code?") }
        return
    }
    Row(Modifier.padding(top = 8.dp).widthIn(max = 420.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it.trim().take(40) }, label = { Text("Creator code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f))
        Button({ vm.store.applyCode(code) }, enabled = !s.busy && code.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text("Apply") }
    }
}

/** "€5.60 / month for 3 months, then €7.00"; "€60.48 for the first year, then €75.60 / year" */
private fun offerText(o: io.github.pmaxai.cardscanner.store.StoreOffer, unit: String): String {
    val n = o.introCycles
    return when {
        o.introPrice == null || n <= 0 -> "${o.price} / $unit"
        n == 1 -> "${o.introPrice} for the first $unit, then ${o.price} / $unit"
        else -> "${o.introPrice} / $unit for $n ${unit}s, then ${o.price}"
    }
}

/** a plan: what it unlocks, and a button per period with Google Play's price (a creator code's discount when there is one) */
@Composable
private fun PlanCard(plan: io.github.pmaxai.cardscanner.store.PlanOffer, s: StoreUi, enabled: Boolean, onBuy: (String) -> Unit) {
    Column(Modifier.padding(top = 14.dp).widthIn(max = 420.dp).fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text(plan.name, color = Gold, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(featureWords(plan.features), color = Muted, fontSize = 13.sp)
        // one under the other: prices like "€129.60 / year" don't fit two to a row on a phone
        (s.discounted(plan, "monthly") ?: s.price(plan, "monthly"))?.let { o ->
            Button({ onBuy("monthly") }, enabled = enabled, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) {
                Text(offerText(o, "month"), textAlign = TextAlign.Center)
            }
        }
        (s.discounted(plan, "yearly") ?: s.price(plan, "yearly"))?.let { o ->
            OutlinedButton({ onBuy("yearly") }, enabled = enabled, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) {
                Text(offerText(o, "year"), textAlign = TextAlign.Center)
            }
        }
    }
}

/** ⚙ → Account in the Play edition: manage in Google Play, extra devices, change plan, restore. */
@Composable
private fun StorePart(state: UiState, vm: ScannerViewModel, st: AccountStatus) {
    val a = state.account
    val s = a.store
    val ctx = LocalContext.current
    LaunchedEffect(st.accountId) { vm.store.refresh() }
    if (!st.emailVerified) Text("Confirm your email: open the link we sent to ${st.email ?: "you"}.", color = Gold, fontSize = 13.sp,
        modifier = Modifier.padding(top = 6.dp))
    when (st.source) {
        "google_play" -> Button({ manageUrl(a)?.let { openUrl(ctx, it) } }, modifier = Modifier.padding(top = 10.dp)) { Text("Manage subscription") }
        "web" -> Text("This subscription is managed on the website.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    }
    if (st.source == "google_play" && st.active) {
        val dev = s.devices(st)
        Text("Extra devices", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        Text("1 device is included. Bought in the app: ${st.devicesInStore}" +
            (if (st.devicesOnWeb > 0) " · on the website: ${st.devicesOnWeb}" else ""), color = Muted, fontSize = 13.sp)
        if (dev == null) {
            Text("Extra devices and plan changes work on the phone whose Google account bought the subscription. Try Restore purchases.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            val (have, max) = dev
            val plan = s.offers.firstOrNull { it.plan == st.plan }
            val next = plan?.deviceProductIds?.firstOrNull { it !in (s.purchase?.products ?: emptyList()) }
            val period = st.basePlan ?: "monthly"
            val nextPrice = next?.let { id -> plan?.basePlans?.get(period)?.let { s.prices[id]?.get(it)?.price } }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ ctx.activity()?.let { vm.store.addDevice(it) } }, enabled = !s.busy && have < max) {
                    Text("+ Add a device" + (nextPrice?.let { " · $it" } ?: ""))
                }
                OutlinedButton({ ctx.activity()?.let { vm.store.removeDevice(it) } }, enabled = !s.busy && have > 0) { Text("Remove one") }
            }
            Text("$have of $max extra devices in the app. A removed device runs to the end of its period.", color = Muted, fontSize = 12.sp)
            val others = s.onSale.filter { it.plan != st.plan }
            if (others.isNotEmpty()) {
                Text("Plan", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                others.forEach { o ->
                    OutlinedButton({ ctx.activity()?.let { vm.store.changePlan(it, o) } }, enabled = !s.busy, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Switch to ${o.name}" + (s.price(o, "monthly")?.let { " · ${it.price} / month" } ?: ""))
                    }
                }
                Text("A bigger plan starts at once; a smaller one from the next renewal. Extra devices move with it.", color = Muted, fontSize = 12.sp)
            }
        }
    }
    StoreLines(s)
    if (s.busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(22.dp), strokeWidth = 2.dp)
    TextButton({ vm.store.restore() }, enabled = !s.busy) { Text("Restore purchases") }
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
    if (a.isFree) { FreeCollectionBar(a, vm, s, condition); return }
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

/** The free version: + Collection keeps the card on this phone (50 different cards); ☆ Missing is shown locked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FreeCollectionBar(a: AccountUi, vm: ScannerViewModel, s: Shown, condition: String) {
    var sheet by remember { mutableStateOf(false) }
    fun add(cond: String, qty: Int, cents: Long?, note: String?) {
        if (!vm.addShownLocally(cond, qty, cents, note)) vm.showLocked(LockedFeature.COLLECTION_FULL)
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("+ Collection", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Gold, RoundedCornerShape(20.dp))
                .combinedClickable(onClick = { add(condition, 1, null, null) }, onLongClick = { sheet = true })
                .padding(horizontal = 14.dp, vertical = 8.dp))
        Text("🔒 Missing", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.border(1.dp, Muted, RoundedCornerShape(20.dp))
                .clickable { vm.showLocked(LockedFeature.MISSING) }.padding(horizontal = 14.dp, vertical = 7.dp))
    }
    val lines = a.localLines(s.version.id)
    val have = lines.sumOf { it.quantity }
    if (have > 0) Text("In collection: $have (" + lines.joinToString(" · ") { "${it.quantity} × ${it.language} ${ShopTerms.finishLabel(it.finish)} ${it.condition}" } + ")",
        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    // no binders without an account
    if (sheet) CollectionSheet(a.copy(binders = emptyList()), condition, onClose = { sheet = false }) { c, q, cents, _, note -> add(c, q, cents, note) }
}

/** Long press on + Collection (and the missing cards' Found it): the details of the copy. */
@Composable
internal fun CollectionSheet(a: AccountUi, condition: String, title: String = "Add to the collection", onClose: () -> Unit,
                             onAdd: (String, Int, Long?, Long?, String?) -> Unit) {
    var cond by remember { mutableStateOf(condition) }
    var qty by remember { mutableIntStateOf(1) }
    var price by remember { mutableStateOf("") }
    var binder by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    val cents = price.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { Math.round(it * 100) }
    AlertDialog(onDismissRequest = onClose, title = { Text(title) }, text = {
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
