package io.github.pmaxai.cardscanner

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.data.CardInfo
import io.github.pmaxai.cardscanner.data.Graded
import io.github.pmaxai.cardscanner.data.Prices
import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.recording.EventLog
import io.github.pmaxai.cardscanner.recording.EventLog.Reason
import io.github.pmaxai.cardscanner.ui.Confidence
import io.github.pmaxai.cardscanner.ui.PackCsv
import io.github.pmaxai.cardscanner.ui.SessionItem
import io.github.pmaxai.cardscanner.ui.Settings
import io.github.pmaxai.cardscanner.ui.Shown
import io.github.pmaxai.cardscanner.ui.isHit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

/** The recording's event log: its format (schema v1, also on a German phone), and a scripted pack's events. */
@RunWith(AndroidJUnit4::class)
class EventLogTest {
    private lateinit var dir: File
    private var nowNanos = 0L
    private val direct = Executor { it.run() }
    private var serial = 0
    private val defaultLocale = Locale.getDefault()

    @Before fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "eventlog-${System.nanoTime()}").apply { mkdirs() }
        Locale.setDefault(Locale.GERMANY)      // no decimal commas may get into the log
    }

    @After fun tearDown() { Locale.setDefault(defaultLocale); dir.deleteRecursively() }

    private fun info(id: String, lang: String, name: String, number: String, set: String, eur: Double?, kinds: List<Printing>,
                     rarity: String? = "Uncommon", graded: Map<Printing.Kind?, Graded> = emptyMap()) = CardInfo(
        id = id, lang = lang, name = name, number = number, setName = set, setTotal = 191, series = "Scarlet & Violet", rarity = rarity,
        imageUrl = null, prices = Prices("2026-11-08T22:50:00Z", eur, null, null, null, null, null, null, null), printings = kinds,
        graded = graded, releaseDate = "2024-11-08")

    private val magnetonDe = info("sv08-059", "de", "Magneton", "059", "Stürmische Funken", 0.42, listOf(
        Printing(Printing.Kind.NORMAL, 0.12, 0.10, 0.02, 0.20), Printing(Printing.Kind.REVERSE, 0.42, 0.39, 0.10, 0.55)))
    private val magnetonEn = magnetonDe.copy(lang = "en", setName = "Surging Sparks")
    private val pikachu = info("sv08-238", "en", "Pikachu ex", "238", "Surging Sparks", 7.8, listOf(
        Printing(Printing.Kind.HOLO, 7.8, 8.1, 6.5, 9.99)), rarity = "Special Illustration Rare",
        graded = mapOf(Printing.Kind.HOLO to Graded(120.0, 14, 45.5, 9, null, null, null)))
    private val bulk = info("sv08-012", "en", "Caterpie", "012", "Surging Sparks", 0.15, listOf(Printing(Printing.Kind.NORMAL, 0.15, null, null, null)))

    private fun shown(c: CardInfo, printing: Printing.Kind? = c.printings.first().kind, english: CardInfo? = null, s: Int = ++serial) =
        Shown(c.id, c, english, c, printing, false, null, Confidence.SURE, emptyList(), emptyList(), null, false, false, null, s)

    private fun item(s: Shown) = SessionItem(s.serial, 1_762_697_000_000L, s)

    private fun log(name: String = "events.jsonl") = EventLog("20261109T140322Z-7f3a", File(dir, name), threshold = 5.0,
        nanos = { nowNanos }, wallMs = { 1_794_233_002_418L }, writer = direct)

    private fun header() = JSONObject()
        .put("app", JSONObject().put("version", "1.2.31").put("data_pack", "2026-11-08").put("prices_date", "2026-11-08T22:50:00Z"))
        .put("device", JSONObject().put("model", "Redmi Note 12 Pro+ 5G").put("android_sdk", 34))
        .put("settings", JSONObject().put("hit_threshold_eur", 5.0).put("title", "Surging Sparks booster box").put("show_psa", true))
        .put("ui", JSONObject().put("count_up_ms", 900).put("hit_burst_ms", 1600))

    private fun at(ms: Long) { nowNanos = 1_000_000_000L + ms * 1_000_000 }

    @Test
    fun theFormatIsSchemaV1() {
        val log = log()
        at(0)
        val mag = shown(magnetonDe, Printing.Kind.REVERSE, english = magnetonEn)
        log.start(nowNanos, emptyList(), null)
        at(41_230)
        log.observe(Reason.SHOWN, "camera", mag, listOf(item(mag)), listOf(doubleArrayOf(0.31, 0.18), doubleArrayOf(0.69, 0.18),
            doubleArrayOf(0.69, 0.82), doubleArrayOf(0.31, 0.82)))
        at(1_834_500)
        log.stop("user")
        val text = log.finish(header(), listOf(item(mag)), "complete")
        val o = JSONObject(text)

        assertEquals("tcgdex.recording.v1", o.getString("schema"))
        assertEquals("20261109T140322Z-7f3a", o.getString("recording_id"))
        assertEquals("complete", o.getString("status"))
        assertEquals("1.2.31", o.getJSONObject("app").getString("version"))
        val ev = o.getJSONArray("events")
        assertEquals(3, ev.length())
        val started = ev.getJSONObject(0)
        assertEquals(listOf("seq", "t_ms", "at", "type", "pack_at_start"), started.keys().asSequence().toList())
        assertEquals(1, started.getInt("seq")); assertEquals(0, started.getLong("t_ms"))
        assertEquals("2026-11-09T14:03:22.418Z", started.getString("at"))

        val shownEv = ev.getJSONObject(1)
        assertEquals("card_shown", shownEv.getString("type"))
        assertEquals(41_230, shownEv.getLong("t_ms"))
        assertEquals("2026-11-09T14:04:03.648Z", shownEv.getString("at"))
        assertEquals(1, shownEv.getInt("entry")); assertTrue(shownEv.getBoolean("in_pack"))
        assertEquals("camera", shownEv.getString("source")); assertEquals("sure", shownEv.getString("confidence"))
        assertFalse(shownEv.getBoolean("hit"))
        assertEquals(0.69, shownEv.getJSONArray("card_quad").getJSONArray(1).getDouble(0), 1e-9)
        val card = shownEv.getJSONObject("card")
        assertEquals(JSONObject("""{"id":"sv08-059","lang":"de","name":"Magneton","name_en":"Magneton","set":"Stürmische Funken",
            "set_en":"Surging Sparks","number":"059","number_text":"059/191","release_year":2024,"rarity":"Uncommon",
            "printing":"reverse_holo","printing_by_user":false,"version_by_user":false}""").toString(), card.toString())
        val price = shownEv.getJSONObject("price")
        assertEquals(JSONObject("""{"value":0.42,"currency":"EUR","basis":"cardmarket","cardmarket":{"trend":0.42,"avg30":0.39,"low":0.1},
            "tcgplayer_usd":0.55,"from_english":false,"prices_updated":"2026-11-08","psa":null,"gem_rate_pct":null,"warning":null,
            "all_printings":[{"printing":"normal","value":0.12},{"printing":"reverse_holo","value":0.42}]}""").toString(), price.toString())

        assertEquals(JSONObject("""{"seq":3,"t_ms":1834500,"at":"2026-11-09T14:33:56.918Z","type":"recording_stopped","reason":"user"}""").toString(),
            ev.getJSONObject(2).toString())
        val cards = o.getJSONArray("cards")
        assertEquals(1, cards.length())
        assertEquals(41_230, cards.getJSONObject(0).getLong("first_t_ms"))
        assertEquals(JSONObject("""{"cards":1,"hits":0,"pack_value_eur":0.42,"pack_value_usd":0}""").toString(), o.getJSONObject("summary").toString())

        // a German phone writes no decimal commas, and every key is there (null, never left out)
        assertFalse(Regex("\\d,\\d").containsMatchIn(text.replace(Regex("\"[^\"]*\""), "\"\"")))
        assertTrue(text.contains("\"psa\": null"))
    }

    @Test
    fun aScriptedPackGivesTheExpectedEvents() = scripted(packBefore = 0)

    @Test
    fun aRecordingContinuingAPackListsItsCards() = scripted(packBefore = 3)

    private fun scripted(packBefore: Int) {
        val log = log()
        at(0)
        val before = List(packBefore) { item(shown(bulk)) }
        var session = before
        var current: Shown? = before.lastOrNull()?.shown
        log.start(nowNanos, before, current)
        fun step(ms: Long, reason: Reason, source: String? = null, f: () -> Unit) {
            at(ms); f(); log.observe(reason, source, current, session, null)
        }
        // a new card from the camera
        val mag = shown(magnetonDe, Printing.Kind.NORMAL)
        step(1_000, Reason.SHOWN, "camera") { current = mag; session = session + item(mag) }
        // the printed text says it's the English one (same serial, another version)
        val magEn = mag.copy(version = magnetonEn, priceCard = magnetonEn)
        step(1_400, Reason.TEXT_READ) { current = magEn; session = session.map { if (it.serial == magEn.serial) it.copy(shown = magEn) else it } }
        // the user taps Reverse holo
        val magRev = magEn.copy(printing = Printing.Kind.REVERSE, printingByUser = true)
        step(2_000, Reason.PRINTING) { current = magRev; session = session.map { if (it.serial == magRev.serial) it.copy(shown = magRev) else it } }
        // a hit
        val pika = shown(pikachu)
        step(5_000, Reason.SHOWN, "camera") { current = pika; session = session + item(pika) }
        // +1: the same Pikachu again
        val pika2 = pika.copy(serial = ++serial)
        step(6_000, Reason.ADD_AGAIN) { current = pika2; session = session + item(pika2) }
        // the same card twice in a row isn't counted: on screen, not in the pack
        val again = shown(pikachu)
        step(7_000, Reason.SHOWN, "camera") { current = again }
        // swiped away: not in the pack anyway -> the panel is empty
        step(8_000, Reason.SWIPE) { current = null }
        // a search look-up (not in the pack)
        val looked = shown(bulk)
        step(9_000, Reason.SHOWN, "search") { current = looked }
        // Next card
        step(9_500, Reason.NEXT) { current = null }
        // Remove last card: the +1 copy
        step(10_000, Reason.REMOVE_LAST) { session = session.dropLast(1) }
        // swipe the shown Magneton? show it first via a new scan, then swipe it out of the pack
        val bulk2 = shown(bulk)
        step(11_000, Reason.SHOWN, "double_tap") { current = bulk2; session = session + item(bulk2) }
        step(12_000, Reason.SWIPE) { current = null; session = session.filter { it.serial != bulk2.serial } }
        val finalPack = session
        log.stop("user")
        val o = JSONObject(log.finish(header(), finalPack, "complete"))
        val ev = o.getJSONArray("events").objects()
        val types = ev.map { it.getString("type") }
        assertEquals(listOf("recording_started", "card_shown", "card_updated", "card_updated", "card_shown", "card_added_again",
            "card_shown", "card_cleared", "card_shown", "card_cleared", "card_removed", "card_shown", "card_removed", "recording_stopped"), types)
        val k = packBefore
        assertEquals(k, ev[0].getJSONArray("pack_at_start").length())
        if (k > 0) assertTrue(ev[0].getJSONArray("pack_at_start").getJSONObject(0).getBoolean("before_recording"))
        assertEquals(k + 1, ev[1].getInt("entry"))
        assertEquals(listOf("text_read", "printing"), listOf(ev[2].getString("reason"), ev[3].getString("reason")))
        assertEquals("the correction keeps the entry", k + 1, ev[2].getInt("entry"))
        assertEquals("reverse_holo", ev[3].getJSONObject("card").getString("printing"))
        assertTrue("Pikachu ex is a hit", ev[4].getBoolean("hit"))
        assertEquals(120.0, ev[4].getJSONObject("price").getJSONObject("psa").getJSONObject("10").getDouble("usd"), 1e-9)
        assertEquals(k + 3, ev[5].getInt("entry")); assertEquals(k + 2, ev[5].getInt("copy_of"))
        assertFalse("twice in a row: on screen only", ev[6].getBoolean("in_pack")); assertTrue(ev[6].isNull("entry"))
        assertEquals("search", ev[8].getString("source"))
        assertEquals("remove_last", ev[10].getString("reason")); assertEquals(k + 3, ev[10].getInt("entry"))
        assertEquals("double_tap", ev[11].getString("source"))
        assertEquals("swipe", ev[12].getString("reason")); assertEquals(k + 4, ev[12].getInt("entry"))
        // seq counts up by one, time never goes back
        ev.forEachIndexed { i, e -> assertEquals(i + 1, e.getInt("seq")) }
        assertEquals(ev.map { it.getLong("t_ms") }.sorted(), ev.map { it.getLong("t_ms") })

        // the final pack: entries kept, and the same cards as Save pack's CSV
        val cards = o.getJSONArray("cards").objects()
        assertEquals((1..k).toList() + listOf(k + 1, k + 2), cards.map { it.getInt("entry") })
        assertEquals(finalPack.size, cards.size)
        val csvRows = PackCsv.build(finalPack, Settings(threshold = 5.0), Locale.US).removePrefix("﻿").split("\r\n")
            .drop(1).takeWhile { it.isNotBlank() }
        assertEquals(cards.size, csvRows.size)
        cards.zip(csvRows).forEach { (c, row) ->
            val cols = row.split(",")
            assertEquals(cols[7], c.getJSONObject("card").getString("id"))
            assertEquals(cols[2], c.getJSONObject("card").getString("name"))
            assertEquals(cols[23] == "yes", c.getBoolean("hit"))
        }
        assertEquals(cards.count { it.getBoolean("hit") }, o.getJSONObject("summary").getInt("hits"))
        assertEquals(cards.size, o.getJSONObject("summary").getInt("cards"))

        // a new pack during the recording: entries go on, never reused
        val log2 = log("second.jsonl")
        at(0)
        val b = shown(bulk)
        log2.start(nowNanos, listOf(item(b)), b)
        at(100); log2.observe(Reason.RESET, null, b, emptyList(), null)
        val n = shown(pikachu)
        at(200); log2.observe(Reason.SHOWN, "camera", n, listOf(item(n)), null)
        val ev2 = JSONObject(log2.finish(header(), listOf(item(n)), "complete")).getJSONArray("events").objects()
        assertEquals(listOf("recording_started", "pack_reset", "card_shown"), ev2.map { it.getString("type") })
        assertEquals(2, ev2[2].getInt("entry"))
    }

    @Test
    fun oneHitRule() {
        assertTrue(isHit(5.0, 5.0)); assertTrue(isHit(7.8, 5.0))
        assertFalse(isHit(4.99, 5.0)); assertFalse(isHit(null, 5.0)); assertFalse("alert off", isHit(100.0, 0.0))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
