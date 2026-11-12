package io.github.pmaxai.cardscanner.recording

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Where a recording stands. */
enum class RecStatus(val label: String) {
    RECORDING("recording"), QUEUED("queued"), UPLOADING("uploading"), UPLOADED("uploaded"), FAILED("failed"), LOCAL("on this phone")
}

/** One recording: a folder on the phone with its files and meta.json (status, counts, sizes). */
class RecordingDir(val dir: File) {
    val id: String get() = dir.name
    val video: File get() = File(dir, "video.mp4")
    val camera: File get() = File(dir, "camera.mp4")
    val eventsJsonl: File get() = File(dir, "events.jsonl")
    val events: File get() = File(dir, "events.json")
    val packCsv: File get() = File(dir, "pack.csv")
    val header: File get() = File(dir, "header.json")
    /** which video templates to make, and the user's inputs (written on Submit) */
    val submission: File get() = File(dir, Submission.FILE)
    val uploadState: File get() = File(dir, "upload.json")
    private val metaFile: File get() = File(dir, "meta.json")

    @Synchronized fun meta(): JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }
    @Synchronized fun updateMeta(f: (JSONObject) -> Unit) {
        val m = meta(); f(m)
        val tmp = File(dir, "meta.json.tmp"); tmp.writeText(m.toString()); tmp.renameTo(metaFile)
    }

    val status: RecStatus get() = runCatching { RecStatus.valueOf(meta().optString("status", "LOCAL")) }.getOrDefault(RecStatus.LOCAL)
    val bytes: Long get() = listOf(video, camera, events, packCsv).filter { it.exists() }.sumOf { it.length() }

    /**
     * The files to upload, in order: events.json, pack.csv, submission.json (if submitted with
     * templates), video.mp4, camera.mp4 (if recorded). The uploader puts `_complete.json` last.
     */
    fun uploadFiles(): List<UploadFile> = listOfNotNull(
        UploadFile("events.json", events, "application/json"),
        UploadFile("pack.csv", packCsv, "text/csv; charset=utf-8"),
        submission.takeIf { it.exists() }?.let { UploadFile(Submission.FILE, it, "application/json") },
        UploadFile("video.mp4", video, "video/mp4"),
        camera.takeIf { it.exists() && it.length() > 0 }?.let { UploadFile("camera.mp4", it, "video/mp4") },
    )

    /** "Recording 30:34 · 38 cards · 3 hits · 1.8 GB · queued" */
    fun summary(): String {
        val m = meta()
        val d = m.optLong("duration_ms") / 1000
        val gb = bytes / 1e9
        return "Recording %d:%02d · %d cards · %d hits · %s · %s".format(Locale.ROOT, d / 60, d % 60, m.optInt("cards"), m.optInt("hits"),
            if (gb >= 1) "%.1f GB".format(Locale.ROOT, gb) else "%d MB".format(Locale.ROOT, bytes / 1_000_000), statusText())
    }

    fun statusText(): String {
        val m = meta()
        return when (status) {
            RecStatus.UPLOADING -> "uploading ${m.optInt("progress")} %"
            RecStatus.FAILED -> "failed: ${m.optString("error")}"
            RecStatus.QUEUED -> if (m.optBoolean("waiting_wifi")) "waiting for Wi-Fi" else "queued"
            RecStatus.LOCAL -> "not submitted yet"
            else -> status.label
        }
    }
}

class RecordingStore(ctx: Context) {
    val root: File = (ctx.getExternalFilesDir("recordings") ?: File(ctx.filesDir, "recordings")).apply { mkdirs() }

    fun list(): List<RecordingDir> =
        (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).sortedByDescending { it.name }.map(::RecordingDir)

    fun get(id: String): RecordingDir? = File(root, id).takeIf { it.isDirectory }?.let(::RecordingDir)

    /** A new folder: id = UTC start time + 4 random hex characters ("20261109T140322Z-7f3a"). */
    fun create(): RecordingDir {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val id = stamp + "-" + "%04x".format(Locale.ROOT, (Math.random() * 0x10000).toInt())
        return RecordingDir(File(root, id).apply { mkdirs() })
    }

    fun freeBytes(): Long = runCatching { android.os.StatFs(root.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    companion object {
        /**
         * events.json from what a recording left behind after a crash: the header written at Start,
         * the events written until then; the pack rebuilt from the events.
         */
        fun recoverEvents(header: JSONObject, jsonl: File, id: String, durationMs: Long): Pair<String, List<JSONObject>> {
            val events = JSONArray()
            val pack = LinkedHashMap<Int, JSONObject>()
            if (jsonl.exists()) jsonl.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.forEach { l ->
                val e = runCatching { JSONObject(l) }.getOrNull() ?: return@forEach
                events.put(e)
                when (e.optString("type")) {
                    "recording_started" -> e.optJSONArray("pack_at_start")?.let { a ->
                        for (i in 0 until a.length()) a.getJSONObject(i).let { c -> pack[c.getInt("entry")] = JSONObject(c.toString()).put("first_t_ms", JSONObject.NULL) }
                    }
                    "card_shown", "card_added_again" -> if (!e.isNull("entry") && (e.optBoolean("in_pack", true))) {
                        pack[e.getInt("entry")] = JSONObject().put("entry", e.getInt("entry")).put("first_t_ms", e.getLong("t_ms"))
                            .put("before_recording", false).put("hit", e.optBoolean("hit")).put("card", e.getJSONObject("card"))
                            .put("price", e.getJSONObject("price"))
                    }
                    "card_updated" -> if (!e.isNull("entry")) pack[e.getInt("entry")]?.let { c ->
                        c.put("hit", e.optBoolean("hit")).put("card", e.getJSONObject("card")).put("price", e.getJSONObject("price"))
                    }
                    "card_removed" -> pack.remove(e.optInt("entry"))
                    "pack_reset" -> pack.clear()
                }
            }
            val lastT = (0 until events.length()).maxOfOrNull { events.getJSONObject(it).optLong("t_ms") } ?: 0
            events.put(JSONObject().put("seq", events.length() + 1).put("t_ms", maxOf(lastT, durationMs))
                .put("at", JSONObject.NULL).put("type", "recording_stopped").put("reason", "recovered"))
            val cards = pack.values.toList()
            val out = JSONObject().put("schema", Recording.SCHEMA).put("recording_id", id).put("status", "recovered")
            header.keys().forEach { k -> out.put(k, header.get(k)) }
            out.optJSONObject("video")?.put("duration_ms", durationMs)
            val eur = cards.sumOf { c -> c.getJSONObject("price").let { p -> if (p.optString("currency") == "EUR") p.optDouble("value", 0.0) else 0.0 } }
            val usd = cards.sumOf { c -> c.getJSONObject("price").let { p -> if (p.optString("currency") == "USD") p.optDouble("value", 0.0) else 0.0 } }
            out.put("events", events).put("cards", JSONArray(cards))
                .put("summary", JSONObject().put("cards", cards.size).put("hits", cards.count { it.optBoolean("hit") })
                    .put("pack_value_eur", EventLog.round2(eur)).put("pack_value_usd", EventLog.round2(usd)))
            return out.toString(2) to cards
        }

        /** A simple pack CSV from the log's cards (after a crash the app's own pack is gone). */
        fun csvOf(cards: List<JSONObject>): String {
            fun cell(v: Any?): String { val t = v?.toString() ?: ""; return if (t.any { it == ',' || it == '"' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t }
            val sb = StringBuilder("﻿No,Name,English name,Set,Number,Language,Card ID,Printing,Value,Currency,Hit\r\n")
            cards.forEachIndexed { i, c ->
                val card = c.getJSONObject("card"); val p = c.getJSONObject("price")
                listOf(i + 1, card.optString("name"), card.opt("name_en").takeIf { it != JSONObject.NULL }, card.optString("set"),
                    card.optString("number_text"), card.optString("lang"), card.optString("id"), card.opt("printing").takeIf { it != JSONObject.NULL },
                    p.opt("value").takeIf { it != JSONObject.NULL }, p.opt("currency").takeIf { it != JSONObject.NULL },
                    if (c.optBoolean("hit")) "yes" else "").joinTo(sb, ",") { cell(it) }
                sb.append("\r\n")
            }
            return sb.toString()
        }
    }
}
