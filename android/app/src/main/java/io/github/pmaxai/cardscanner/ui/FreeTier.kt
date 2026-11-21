package io.github.pmaxai.cardscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pmaxai.cardscanner.account.AccountStatus
import io.github.pmaxai.cardscanner.shop.ShopTerms
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * The free version: a build that needs an account, without a running subscription (never
 * subscribed, ended, signed out, or the saved answer ran out offline). The Scanner only; 10 prices
 * revealed in any 60 minutes (a card revealed stays revealed for that hour); a collection of 50
 * different cards on this phone, moved into the account's collection as soon as a plan with a
 * collection runs.
 */
object Free {
    const val REVEALS_PER_HOUR = 10
    const val MAX_CARDS = 50
    const val HOUR = 60 * 60 * 1000L
}

/** A price revealed in the free version: which card, and when. */
data class FreeReveal(val cardId: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("card_id", cardId).put("at", at)
    companion object { fun of(o: JSONObject) = FreeReveal(o.getString("card_id"), o.getLong("at")) }
}

/** A copy (or several) in the free version's collection, kept on this phone. */
data class LocalItem(
    val id: String = UUID.randomUUID().toString(),
    val cardId: String,
    /** the shop's card_language and finish, as + Collection sends them to an account */
    val language: String,
    val finish: String,
    val condition: String,
    val quantity: Int,
    val purchaseCents: Long? = null,
    val note: String? = null,
    /** "Glurak-ex · 151 199/165" */
    val label: String,
    val added: Long = System.currentTimeMillis(),
    /** "PSA", "10" when graded */
    val gradingCompany: String? = null,
    val gradingGrade: String? = null,
) {
    /** "glurak-ex · 151 199/165 de holo nm" for the filter */
    val searchText: String = "$label $cardId $language ${ShopTerms.finishLabel(finish)} $condition".lowercase()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("card_id", cardId).put("language", language).put("finish", finish)
        .put("condition", condition).put("quantity", quantity).put("purchase_price_cents", purchaseCents ?: JSONObject.NULL)
        .put("note", note ?: JSONObject.NULL).put("label", label).put("added", added)
        .put("grading_company", gradingCompany ?: JSONObject.NULL).put("grading_grade", gradingGrade ?: JSONObject.NULL)

    companion object {
        fun of(o: JSONObject) = LocalItem(o.getString("id"), o.getString("card_id"), o.optString("language"), o.optString("finish"),
            o.optString("condition"), o.optInt("quantity", 1),
            if (o.isNull("purchase_price_cents")) null else o.optLong("purchase_price_cents"),
            if (o.isNull("note")) null else o.optString("note"), o.optString("label"), o.optLong("added"),
            AccountStatus.str(o, "grading_company"), AccountStatus.str(o, "grading_grade"))
    }
}

/** this card's price may be shown (always with a subscription) */
fun AccountUi.revealed(cardId: String, now: Long = System.currentTimeMillis()): Boolean =
    !isFree || reveals.any { it.cardId == cardId && it.at > now - Free.HOUR }

/** prices that may still be revealed in this hour */
fun AccountUi.revealsLeft(now: Long = System.currentTimeMillis()): Int =
    (Free.REVEALS_PER_HOUR - reveals.count { it.at > now - Free.HOUR }).coerceAtLeast(0)

/** all used up: when the next one may be revealed */
fun AccountUi.nextRevealAt(now: Long = System.currentTimeMillis()): Long? =
    if (revealsLeft(now) > 0) null else reveals.filter { it.at > now - Free.HOUR }.minOfOrNull { it.at }?.plus(Free.HOUR)

/** different cards in the phone's collection (the limit counts these, not copies) */
val AccountUi.localCardCount: Int get() = localItems.map { it.cardId }.toSet().size

/** "In collection: 2 (2 × DE Holo NM)" for a card */
fun AccountUi.localLines(cardId: String): List<CollectionLine> =
    localItems.filter { it.cardId == cardId }.map { CollectionLine(it.language, it.finish, it.condition, it.quantity, null) }

/** A feature the free version shows but doesn't include. */
enum class LockedFeature(val title: String, val text: String) {
    SEARCH("Search needs a subscription", "Find any card by name, number or set, in any language, with its prices."),
    MODES("This mode needs a subscription", "YouTube Mode for recording your openings, and Webshop Mode for your shop."),
    TRADING("Carts and deals need a subscription", "Carts for buying, selling and trading at shows, with conditions, asking prices and your deal history."),
    MISSING("Missing cards need a subscription", "Keep a list of the cards you're looking for – the shop tells you when one comes in."),
    PHOTO("Scanning photos needs a subscription", "Recognise cards on photos from your gallery."),
    ALERT("Price alerts need a subscription", "The phone vibrates when a scanned card is worth at least your alert price."),
    SHARE("Sharing needs a subscription", "Send friends a link to your collection – they see your cards and what they're worth."),
    COLLECTION_FULL("The free collection is full", "The free version keeps ${Free.MAX_CARDS} different cards on this phone. With a subscription your collection has no limit, is saved in your account, and these cards move into it."),
    VALUES("Collection values need a subscription", "See what each card and your whole collection is worth."),
    PSA("PSA prices are an add-on", "What PSA 10, 9 and 8 copies of a card sold for on eBay – an add-on for Collector and Creator, monthly or yearly.");

    /** the way forward: the plans, or (an add-on) Settings › Price data & add-ons */
    val isAddon: Boolean get() = this == PSA
}

/** A locked feature's message: what it does, and the way to the plans. */
@Composable
fun LockedDialog(f: LockedFeature, onPlans: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(f.title) }, text = { Text(f.text) },
        confirmButton = { Button(onPlans) { Text(if (f.isAddon) "See the add-on" else "See the plans") } },
        dismissButton = { TextButton(onClose) { Text("Not now") } })
}

/** A page the free version shows locked. */
@Composable
fun LockedPage(f: LockedFeature, icon: String, onPlans: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Ink).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 40.sp, color = Gold)
            Text(f.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp))
            Text(f.text, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Button(onPlans, modifier = Modifier.padding(top = 16.dp)) { Text("See the plans") }
        }
    }
}

/** In the free version, instead of a card's prices: reveal them (10 in any 60 minutes). */
@Composable
fun RevealPrice(a: AccountUi, cardId: String, vm: ScannerViewModel) {
    val left = a.revealsLeft()
    Column(Modifier.padding(top = 4.dp)) {
        if (left > 0) {
            Text("👁  Reveal the price", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Gold, RoundedCornerShape(24.dp)).clickable { vm.account.reveal(cardId) }
                    .padding(horizontal = 18.dp, vertical = 10.dp))
            Text("$left of ${Free.REVEALS_PER_HOUR} left this hour in the free version", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        } else {
            Text("You've seen ${Free.REVEALS_PER_HOUR} prices this hour.", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            a.nextRevealAt()?.let { t ->
                Text("The next one at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))} – or every price at once with a subscription.",
                    color = Muted, fontSize = 12.sp)
            }
            Text("See the plans", color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { vm.showPlans() }.padding(vertical = 6.dp))
        }
    }
}

/** Settings › Account in the free version, above the sign-in or the plans: what Free includes and what a plan adds. */
@Composable
fun FreeNote(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Free version", color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Line("✓", "Scanner: scan as many cards as you like, ${Free.REVEALS_PER_HOUR} prices per hour")
        Line("✓", "A collection of ${Free.MAX_CARDS} cards on this phone")
        Line("🔒", "Search, carts and deals, YouTube and Webshop Mode, missing cards, photo scans, price alerts and collection values come with a plan; PSA prices as an add-on")
        Text("Sign in and choose a plan for everything; your cards move into your account's collection.", color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Line(mark: String, text: String) = Row {
    Text(mark, color = Color.White, fontSize = 13.sp, modifier = Modifier.widthIn(min = 22.dp))
    Text(text, color = Color.White, fontSize = 13.sp)
}
