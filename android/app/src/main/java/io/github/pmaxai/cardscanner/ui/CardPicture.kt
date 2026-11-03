package io.github.pmaxai.cardscanner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter

/**
 * The card's picture. Always shows something: the database image of this version, of the
 * same card in another language, or of another version of the artwork; while it loads,
 * without internet or when no version has one, the card as the camera saw it.
 * Hold it for a second to see it full screen.
 */
@Composable
fun CardImage(s: Shown, modifier: Modifier) {
    var full by remember { mutableStateOf(false) }
    val scan = s.scan?.let { b -> remember(b) { BitmapPainter(b.asImageBitmap()) } }
    val img = s.imageCard
    val stand = scan ?: ColorPainter(Color(0xFF2A2D34))
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Panel).holdToOpen { full = true }) {
        if (img != null) {
            AsyncImage(model = img.thumbnailUrl, contentDescription = s.version.name, placeholder = stand, error = stand,
                fallback = stand, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else if (scan != null) {
            Image(scan, s.version.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text("no picture", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
        }
    }
    if (full) FullscreenCard(s, scan) { full = false }
}

/** Calls [onOpen] when a finger rests on it for [millis] (a swipe or a quick tap does nothing). */
fun Modifier.holdToOpen(millis: Long = 1000, onOpen: () -> Unit): Modifier = composed {
    val open by rememberUpdatedState(onOpen)
    val haptic = LocalHapticFeedback.current
    pointerInput(millis) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            // released (or taken over by a swipe) before the time is up: not a hold
            val ended = withTimeoutOrNull(millis) { waitForUpOrCancellation(); true }
            if (ended == null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                open()
                // the rest of this touch belongs to the hold
                do {
                    val e = awaitPointerEvent()
                    e.changes.forEach { it.consume() }
                } while (e.changes.any { it.pressed })
            }
        }
    }
}

/** The card full screen: the large database image, or the scanned card; ✕ or a tap closes it. */
@Composable
fun FullscreenCard(s: Shown, scan: Painter?, onClose: () -> Unit) {
    val img = s.imageCard
    val low = img?.thumbnailUrl?.let { rememberAsyncImagePainter(it) }
    // both exist: switch between the database image and the scanned card (condition check)
    var showScan by remember { mutableStateOf(img == null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF2000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
            val pic = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 96.dp)
            when {
                !showScan && img != null -> AsyncImage(model = img.highUrl, contentDescription = s.version.name,
                    placeholder = low ?: scan, error = low ?: scan, contentScale = ContentScale.Fit, modifier = pic)
                scan != null -> Image(scan, s.version.name, pic, contentScale = ContentScale.Fit)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(s.version.name, 22.sp, Color.White, weight = FontWeight.Bold, align = TextAlign.Center)
                FitText("${s.version.setName} · ${s.version.numberText} · ${LANG_NAMES[s.version.lang] ?: s.version.lang}",
                    14.sp, Muted, align = TextAlign.Center)
                if (img != null && scan != null) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to "Card image", true to "Your card").forEach { (v, label) ->
                            Text(label, color = if (showScan == v) Ink else Color.White, fontSize = 14.sp,
                                fontWeight = if (showScan == v) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.background(if (showScan == v) Gold else Color(0xFF2A2D34), RoundedCornerShape(10.dp))
                                    .clickable { showScan = v }.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                }
                if (img != null && img !== s.version && (img.id != s.version.id || img.lang != s.version.lang)) {
                    Text("Image of ${img.setName} ${img.numberText} · ${img.lang.uppercase()} (none for this version)",
                        color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp).size(48.dp)
                .background(Color(0xCC2A2D34), CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Text("✕", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * One line of text that shrinks to fit its width instead of wrapping (landscape, long names,
 * big font settings). [sizing] decides the size, e.g. the final price while it counts up.
 */
@Composable
fun FitText(text: String, maxSize: TextUnit, color: Color, modifier: Modifier = Modifier, weight: FontWeight? = null,
            minSize: TextUnit = 10.sp, sizing: String = text, align: TextAlign? = null) =
    FitText(AnnotatedString(text), maxSize, color, modifier, weight, minSize, AnnotatedString(sizing), align)

@Composable
fun FitText(text: AnnotatedString, maxSize: TextUnit, color: Color, modifier: Modifier = Modifier, weight: FontWeight? = null,
            minSize: TextUnit = 10.sp, sizing: AnnotatedString = text, align: TextAlign? = null) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val size = remember(sizing, maxW, maxSize, weight) {
            if (!bounded || maxW <= 0) maxSize else {
                val w = measurer.measure(sizing, TextStyle(fontSize = maxSize, fontWeight = weight), maxLines = 1, softWrap = false).size.width
                if (w <= maxW) maxSize else (maxSize.value * maxW / w * 0.97f).coerceAtLeast(minSize.value).sp
            }
        }
        Text(text, color = color, fontSize = size, fontWeight = weight, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, textAlign = align, lineHeight = size * 1.15f,
            modifier = if (align != null) Modifier.fillMaxWidth() else Modifier)
    }
}
