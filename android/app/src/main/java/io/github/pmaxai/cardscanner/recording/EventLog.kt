package io.github.pmaxai.cardscanner.recording

import io.github.pmaxai.cardscanner.data.Printing
import io.github.pmaxai.cardscanner.ui.Confidence
import io.github.pmaxai.cardscanner.ui.SessionItem
import io.github.pmaxai.cardscanner.ui.Shown
import io.github.pmaxai.cardscanner.ui.isHit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The recording's event log: every change of the pack or of the shown card, with its time in
 * the video. The view model reports each change ([observe], with what caused it); the log
 * works out what happened by comparing the pack and the shown card with the last report, so
 * no change can slip through. Lines go to `events.jsonl` on a background thread (flushed
 * every event); [finish] turns them into `events.json` (schema tcgdex.recording.v1) with the
 * final pack and totals.
 */
class EventLog(
    val recordingId: String,
    private val jsonl: File,
    private val threshold: Double,
    private val nanos: () -> Long = System::nanoTime,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val writer: Executor = Executors.newSingleThreadExecutor(),
) {
    /** What caused a change: decides the event's type and reason. */
    enum class Reason(val wire: String?) {
        SHOWN(null), TEXT_READ("text_read"), PRINTING("printing"), VERSION("version"), OTHER_CARD("other_card"),
        SEARCH_REPLACE("search_replace"), ADD_AGAIN(null), SWIPE("swipe"), REMOVE_LAST("remove_last"), NEXT(null), RESET(null),
    }

    private class Entry(val entry: Int, val beforeRecording: Boolean, var firstT: Long?)

    private val lock = Any()
    private var seq = 0
    private var lastT = 0L
    private var nextEntry = 1
    /** the video's time zero: the first frame's timestamp (same clock as [nanos]) */
    @Volatile var zeroNanos: Long? = null
        private set
    private val entries = HashMap<Int, Entry>()          // pack serial -> entry
    private var lastSession: List<Pair<Int, String>> = emptyList()
    private var lastShownSerial: Int? = null
    private var lastShownKey: String? = null
    private var hits = 0

    /** The first frame is in the video: t = 0. The cards already in the pack are entries 1…k. */
    fun start(firstFrameNanos: Long, packAtStart: List<SessionItem>, shown: Shown?) = synchronized(lock) {
        zeroNanos = firstFrameNanos
        val pack = JSONArray()
        packAtStart.forEach { item ->
            val e = Entry(nextEntry++, beforeRecording = true, firstT = null)
            entries[item.serial] = e
            pack.put(JSONObject().put("entry", e.entry).put("before_recording", true).put("hit", hit(item.shown))
                .put("card", cardJson(item.shown)).put("price", priceJson(item.shown)))
        }
        lastSession = packAtStart.map { it.serial to key(it.shown) }
        lastShownSerial = shown?.serial
        lastShownKey = shown?.let(::key)
        emit("recording_started", t = 0) { it.put("pack_at_start", pack) }
    }

    /** A change of the pack or the shown card, right after it happened. [quad]: the card's corners in camera.mp4 (0–1). */
    fun observe(reason: Reason, source: String?, shown: Shown?, session: List<SessionItem>, quad: List<DoubleArray>?) = synchronized(lock) {
        if (zeroNanos == null) return@synchronized
        val t = now()
        val inPack = session.map { it.serial }.toSet()

        if (reason == Reason.RESET) {
            if (lastSession.isNotEmpty()) emit("pack_reset", t) {}
            entries.clear()
            lastSession = emptyList()
            remember(shown, session)
            return@synchronized
        }

        val old = lastSession
        val new = session.map { it.serial to key(it.shown) }
        when {
            new.size == old.size -> new.forEachIndexed { i, (serial, k) ->
                val (oldSerial, oldKey) = old[i]
                if (serial != oldSerial || k != oldKey) {
                    val e = entries.remove(oldSerial) ?: Entry(nextEntry++, false, t)
                    entries[serial] = e
                    updated(t, e.entry, reason, session[i].shown, quad)
                }
            }
            new.size < old.size -> old.filter { (s, _) -> s !in inPack }.forEach { (s, _) ->
                val e = entries.remove(s) ?: return@forEach
                emit("card_removed", t) { it.put("entry", e.entry).put("reason", if (reason == Reason.REMOVE_LAST) "remove_last" else "swipe") }
            }
            else -> session.filter { it.serial !in entries }.forEach { item ->
                val e = Entry(nextEntry++, false, t)
                entries[item.serial] = e
                if (reason == Reason.ADD_AGAIN) {
                    val copyOf = lastShownSerial?.let { entries[it]?.entry }
                    emit("card_added_again", t) {
                        it.put("entry", e.entry).put("copy_of", copyOf ?: JSONObject.NULL).put("hit", hit(item.shown))
                            .put("card", cardJson(item.shown)).put("price", priceJson(item.shown))
                    }
                } else shownEvent(t, e.entry, true, source, item.shown, quad)
            }
        }

        // the shown card outside the pack: on screen, so the pipeline must know it too
        if (shown != null && shown.serial !in inPack) {
            if (shown.serial != lastShownSerial) shownEvent(t, null, false, source, shown, quad)
            else if (key(shown) != lastShownKey) updated(t, null, reason, shown, quad)
        } else if (shown == null && lastShownSerial != null) {
            // the panel is empty (Next card); a swiped pack card was logged as removed above
            val wasInPack = old.any { it.first == lastShownSerial }
            if (!(reason == Reason.SWIPE && wasInPack)) emit("card_cleared", t) {}
        }
        remember(shown, session)
    }

    /** The last frame: the recording ends. */
    fun stop(reason: String) = synchronized(lock) { if (zeroNanos != null) emit("recording_stopped", now()) { it.put("reason", reason) } }

    private fun remember(shown: Shown?, session: List<SessionItem>) {
        lastSession = session.map { it.serial to key(it.shown) }
        lastShownSerial = shown?.serial
        lastShownKey = shown?.let(::key)
    }

    private fun shownEvent(t: Long, entry: Int?, inPack: Boolean, source: String?, s: Shown, quad: List<DoubleArray>?) {
        if (hit(s)) hits++
        emit("card_shown", t) {
            it.put("entry", entry ?: JSONObject.NULL).put("in_pack", inPack).put("source", source ?: "camera")
                .put("confidence", s.confidence.name.lowercase()).put("hit", hit(s)).put("card_quad", quadJson(quad))
                .put("card", cardJson(s)).put("price", priceJson(s))
        }
    }

    private fun updated(t: Long, entry: Int?, reason: Reason, s: Shown, quad: List<DoubleArray>?) = emit("card_updated", t) {
        it.put("entry", entry ?: JSONObject.NULL).put("reason", reason.wire ?: "text_read").put("hit", hit(s))
            .put("card_quad", quadJson(quad)).put("card", cardJson(s)).put("price", priceJson(s))
    }

    private fun now(): Long {
        val z = zeroNanos ?: return 0
        val t = ((nanos() - z) / 1_000_000).coerceAtLeast(lastT)
        lastT = t
        return t
    }

    private fun emit(type: String, t: Long, fill: (JSONObject) -> Unit) {
        val o = JSONObject().put("seq", ++seq).put("t_ms", t).put("at", Instant.ofEpochMilli(wallAt(t)).toString()).put("type", type)
        fill(o)
        val line = o.toString() + "\n"
        writer.execute {
            runCatching { FileOutputStream(jsonl, true).use { out -> out.write(line.toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync() } }
        }
    }

    /** wall-clock time of video time [t] */
    private var wallZero: Long? = null
    private fun wallAt(t: Long): Long {
        val z = wallZero ?: (wallMs() - ((zeroNanos?.let { nanos() - it } ?: 0L) / 1_000_000)).also { wallZero = it }
        return z + t
    }

    private fun hit(s: Shown) = isHit(s.value, threshold)

    /**
     * events.json: the header, every event written so far, the final pack and the totals.
     * [finalPack] is the pack at Stop (the same cards as Save pack's CSV).
     */
    fun finish(header: JSONObject, finalPack: List<SessionItem>, status: String): String = synchronized(lock) {
        // wait for the writer, then read the lines back
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
        val events = JSONArray()
        if (jsonl.exists()) jsonl.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.forEach { l -> runCatching { events.put(JSONObject(l)) } }
        val cards = JSONArray()
        var eur = 0.0
        var usd = 0.0
        var packHits = 0
        finalPack.forEach { item ->
            val e = entries[item.serial]
            val s = item.shown
            val p = s.selected
            if (p?.eur != null) eur += p.eur else if (p?.usd != null) usd += p.usd
            if (hit(s)) packHits++
            cards.put(JSONObject().put("entry", e?.entry ?: JSONObject.NULL).put("first_t_ms", e?.firstT ?: JSONObject.NULL)
                .put("before_recording", e?.beforeRecording ?: false).put("hit", hit(s)).put("card", cardJson(s)).put("price", priceJson(s)))
        }
        val out = JSONObject().put("schema", Recording.SCHEMA).put("recording_id", recordingId).put("status", status)
        header.keys().forEach { k -> out.put(k, header.get(k)) }
        out.put("events", events).put("cards", cards)
            .put("summary", JSONObject().put("cards", finalPack.size).put("hits", packHits)
                .put("pack_value_eur", round2(eur)).put("pack_value_usd", round2(usd)))
        out.toString(2)
    }

    companion object {
        fun round2(v: Double): Double = Math.round(v * 100) / 100.0
        private fun n(v: Double?): Any = v?.let(::round2) ?: JSONObject.NULL
        private fun s(v: String?): Any = v ?: JSONObject.NULL

        /** what makes a card's entry different for the log: version, printing, price */
        fun key(s: Shown): String = "${s.version.id}/${s.version.lang}/${s.printing}/${s.value}/${s.group}"

        fun printingName(k: Printing.Kind?): String? = when (k) {
            Printing.Kind.NORMAL -> "normal"; Printing.Kind.HOLO -> "holo"; Printing.Kind.REVERSE -> "reverse_holo"
            Printing.Kind.FIRST_EDITION -> "first_edition"; null -> null
        }

        fun cardJson(s: Shown): JSONObject {
            val v = s.version
            val en = s.english ?: v.takeIf { it.lang == "en" }
            return JSONObject().put("id", v.id).put("lang", v.lang).put("name", v.name).put("name_en", s(en?.name))
                .put("set", v.setName).put("set_en", s(en?.setName)).put("number", v.number).put("number_text", v.numberText)
                .put("release_year", (v.year ?: s.priceCard.year)?.toIntOrNull() ?: JSONObject.NULL)
                .put("rarity", s(s.priceCard.rarity ?: v.rarity)).put("printing", s(printingName(s.printing)))
                .put("printing_by_user", s.printingByUser).put("version_by_user", s.versionByUser)
        }

        fun priceJson(s: Shown): JSONObject {
            val p = s.selected
            val basis = when {
                p?.eur != null -> "cardmarket"
                p?.usdEur != null -> "tcgplayer_eur"
                p?.usd != null -> "tcgplayer_usd"
                else -> null
            }
            val g = s.graded
            val psa = g?.let {
                JSONObject().put("10", JSONObject().put("usd", n(it.psa10)).put("sales", it.psa10n ?: JSONObject.NULL))
                    .put("9", JSONObject().put("usd", n(it.psa9)).put("sales", it.psa9n ?: JSONObject.NULL))
                    .put("8", JSONObject().put("usd", n(it.psa8)).put("sales", it.psa8n ?: JSONObject.NULL))
            }
            val gem = s.population?.let { pop -> (pop.gemRate ?: pop.psa10?.let { it.toDouble() / pop.psaTotal })?.times(100) }
            return JSONObject().put("value", n(s.value)).put("currency", if (basis == "tcgplayer_usd") "USD" else if (basis == null) JSONObject.NULL else "EUR")
                .put("basis", s(basis))
                .put("cardmarket", if (p?.eur == null && p?.eur30 == null && p?.eurLow == null) JSONObject.NULL
                    else JSONObject().put("trend", n(p?.eur)).put("avg30", n(p?.eur30)).put("low", n(p?.eurLow)))
                .put("tcgplayer_usd", n(p?.usd)).put("from_english", s.pricedFromEnglish)
                .put("prices_updated", s(s.priceCard.prices?.updated?.take(10)))
                .put("psa", psa ?: JSONObject.NULL).put("gem_rate_pct", gem?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
                .put("warning", s(s.priceCard.priceCheck?.message))
                .put("all_printings", JSONArray().apply {
                    s.priceCard.printings.forEach { pr -> put(JSONObject().put("printing", printingName(pr.kind)).put("value", n(pr.value))) }
                })
        }

        fun quadJson(q: List<DoubleArray>?): Any =
            q?.let { JSONArray().apply { it.forEach { c -> put(JSONArray().put(Math.round(c[0] * 1000) / 1000.0).put(Math.round(c[1] * 1000) / 1000.0)) } } }
                ?: JSONObject.NULL
    }
}
