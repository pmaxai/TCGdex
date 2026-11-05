package io.github.pmaxai.cardscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pmaxai.cardscanner.update.AppUpdate

private val BannerBg = Color(0xFF233042)

/** The text of the update offer: "New version 1.1.57 · prices of 6 Nov (yours: 4 Nov)" */
fun updateLine(u: UpdateState, localPrices: String?): String {
    val theirs = AppUpdate.day(u.release.prices)
    val yours = AppUpdate.day(localPrices)
    return "New version ${u.release.versionName}" +
        (theirs?.let { " · prices of $it" } ?: "") +
        (if (yours != null && yours != theirs) " (yours: $yours)" else "")
}

/** A newer app is out: one tap downloads it and opens Android's installer. */
@Composable
fun UpdateBanner(u: UpdateState, localPrices: String?, onUpdate: () -> Unit, onLater: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(BannerBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
        val p = u.progress
        when {
            p != null -> {
                Text("Downloading the update… ${(p * 100).toInt()} %", color = Color.White, fontSize = 14.sp)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), color = Gold)
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(updateLine(u, localPrices), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    val note = when {
                        u.error != null -> "Update failed: ${u.error}"
                        u.needsPermission -> "Allow Card Scanner to install updates (Settings opened), then come back"
                        u.apk != null -> "Downloaded – confirm the install"
                        else -> null
                    }
                    if (note != null) Text(note, color = if (u.error != null) Warn else Muted, fontSize = 12.sp)
                }
                TextButton(onClick = onLater) { Text("Later", color = Muted) }
                Button(onClick = onUpdate) {
                    Text(when { u.error != null -> "Retry"; u.apk != null -> "Install"; else -> "Update" }, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
