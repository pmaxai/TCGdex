package io.github.pmaxai.cardscanner.recording

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A card of the recording's pack, for a `cards` input: the event log's entry number and what the list shows. */
data class PackCard(val entry: Int, val name: String, val set: String, val number: String, val value: Double?, val currency: String?, val hit: Boolean)

/**
 * `submission.json` (R5): which templates to make from a recording and the inputs the user
 * filled in. Written into the recording's folder on Submit; uploaded before `_complete.json`.
 */
object Submission {
    const val SCHEMA = "tcgdex.submission.v1"
    const val FILE = "submission.json"

    /** [templates]: the ticked ids in the picker's order; [inputs]: only the filled-in ones (see [SubmissionForm.inputs]). */
    fun build(templates: List<String>, inputs: JSONObject, appVersion: String?, now: Instant = Instant.now()): JSONObject {
        require(templates.isNotEmpty()) { "no template" }
        val o = JSONObject().put("schema", SCHEMA).put("submitted_at", now.truncatedTo(ChronoUnit.SECONDS).toString())
        if (!appVersion.isNullOrBlank()) o.put("app_version", appVersion)
        o.put("templates", JSONArray().apply { templates.forEach { put(JSONObject().put("id", it)) } })
        if (inputs.length() > 0) o.put("inputs", inputs)
        return o
    }

    fun write(dir: File, json: JSONObject) {
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        check(tmp.renameTo(File(dir, FILE))) { "could not write $FILE" }
    }

    /** The pack at Stop, from events.json's `cards`. */
    fun packOf(events: File): List<PackCard> = runCatching {
        val a = JSONObject(events.readText(Charsets.UTF_8)).optJSONArray("cards") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val c = a.optJSONObject(i) ?: return@mapNotNull null
            if (!c.has("entry")) return@mapNotNull null
            val card = c.optJSONObject("card") ?: JSONObject()
            val p = c.optJSONObject("price") ?: JSONObject()
            fun s(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k)
            PackCard(c.getInt("entry"), s(card, "name"), s(card, "set"), s(card, "number_text"),
                if (p.isNull("value")) null else p.optDouble("value").takeIf { !it.isNaN() }, if (p.isNull("currency")) null else p.optString("currency"),
                c.optBoolean("hit"))
        }
    }.getOrDefault(emptyList())

    /** Whether the recording has the microphone's sound (events.json, else header.json). */
    fun hasMicSound(events: File, header: File): Boolean = listOf(events, header).firstNotNullOfOrNull { f ->
        runCatching { JSONObject(f.readText(Charsets.UTF_8)).optJSONObject("video")?.optString("audio") }.getOrNull()
    } == "mic"
}

/**
 * The inputs form (R4) without its screen: what a field holds, whether it's acceptable, and
 * the `inputs` object for submission.json. A field holds: text and number fields their text,
 * a toggle a Boolean, a choice its option, a cards field the chosen entry numbers.
 */
object SubmissionForm {
    /** what a field starts with */
    fun initial(f: TemplateInput): Any? = when (f.type) {
        "toggle" -> f.default as? Boolean ?: false
        "choice" -> f.default?.toString()?.takeIf { it in f.options }
        "cards" -> emptyList<Int>()
        else -> ""
    }

    /** "Between 2 and 6", "Not a number" … for a field that can't be submitted like this; null if fine. */
    fun problem(f: TemplateInput, raw: Any?): String? {
        if (f.type != "number") return null
        val t = (raw as? String)?.trim().orEmpty()
        if (t.isEmpty()) return null
        val v = number(t) ?: return "Not a number"
        if (f.min != null && v < f.min || f.max != null && v > f.max) return range(f)
        return null
    }

    /** "Between 2 and 6", "At least 0", "At most 90" (null: no limits) */
    fun range(f: TemplateInput): String? = when {
        f.min != null && f.max != null -> "Between ${fmt(f.min)} and ${fmt(f.max)}"
        f.min != null -> "At least ${fmt(f.min)}"
        f.max != null -> "At most ${fmt(f.max)}"
        else -> null
    }

    /**
     * The inputs the user filled in, typed for the pipeline: text trimmed (at most `max`
     * characters), numbers as JSON numbers, toggles and choices when changed from their
     * default, cards as arrays of entry numbers. Empty fields are left out.
     */
    fun inputs(fields: List<TemplateInput>, raw: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for (f in fields) {
            val v = raw[f.id]
            when (f.type) {
                "text" -> (v as? String)?.trim()?.let { t -> f.max?.toInt()?.let { t.take(it).trim() } ?: t }
                    ?.takeIf { it.isNotEmpty() }?.let { o.put(f.id, it) }
                "number" -> (v as? String)?.trim()?.takeIf { it.isNotEmpty() && problem(f, it) == null }?.let { number(it) }
                    ?.let { n -> if (n == Math.floor(n) && Math.abs(n) < 1e15) o.put(f.id, n.toLong()) else o.put(f.id, n) }
                "toggle" -> (v as? Boolean)?.takeIf { it != (f.default as? Boolean ?: false) }?.let { o.put(f.id, it) }
                "choice" -> (v as? String)?.takeIf { it.isNotEmpty() && it != f.default?.toString() }?.let { o.put(f.id, it) }
                "cards" -> (v as? List<*>)?.filterIsInstance<Int>()?.distinct()?.takeIf { it.isNotEmpty() }
                    ?.let { o.put(f.id, JSONArray(it)) }
            }
        }
        return o
    }

    /** A card ticked in one cards field leaves the others (it can't be in both "Always" and "Never"). */
    fun tickCard(fields: List<TemplateInput>, raw: Map<String, Any?>, fieldId: String, entry: Int, on: Boolean): Map<String, Any?> {
        val out = raw.toMutableMap()
        for (f in fields.filter { it.type == "cards" }) {
            val now = (raw[f.id] as? List<*>)?.filterIsInstance<Int>() ?: emptyList()
            out[f.id] = when {
                f.id == fieldId -> if (on) (now - entry) + entry else now - entry
                on -> now - entry
                else -> now
            }
        }
        return out
    }

    /** "40", "12.5", "12,50" → a number; anything else null */
    fun number(t: String): Double? = t.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.ROOT, "%s", d)
}
