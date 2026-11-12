package io.github.pmaxai.cardscanner.recording

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One input of the templates list: a field on the submit screen (R4). */
data class TemplateInput(
    val id: String,
    /** text, number, toggle, choice or cards; any other type is not shown (newer templates may add types) */
    val type: String,
    val label: String,
    val help: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    /** Boolean, Number or String, as in the file */
    val default: Any? = null,
    val options: List<String> = emptyList(),
) {
    val known: Boolean get() = type in TemplatesIndex.KNOWN_TYPES
}

/** One video template: what the pipeline makes from a recording. [id] is opaque. */
data class VideoTemplate(val id: String, val name: String, val description: String, val default: Boolean, val inputs: List<String>)

/** `templates/index.json`, written by the pipeline (schema pokepov.templates.v1); fields the app doesn't know are ignored. */
data class TemplatesIndex(val templates: List<VideoTemplate>, val inputs: Map<String, TemplateInput>) {
    /** The fields for the ticked templates: each input once, in the templates' order and their own; unknown types left out. */
    fun fieldsFor(ticked: Collection<String>): List<TemplateInput> =
        templates.filter { it.id in ticked }.flatMap { it.inputs }.distinct().mapNotNull { inputs[it] }.filter { it.known }

    companion object {
        const val SCHEMA = "pokepov.templates.v1"
        val KNOWN_TYPES = setOf("text", "number", "toggle", "choice", "cards")

        /** Without any list (fresh install, offline): the one built-in template, no inputs (R2). */
        val FALLBACK = TemplatesIndex(listOf(VideoTemplate("longform-smooth", "Long Form · Smooth", "", true, emptyList())), emptyMap())

        /** Reads the file; another schema or no template at all is an error. */
        fun parse(text: String): TemplatesIndex {
            val o = JSONObject(text)
            val schema = str(o, "schema")
            require(schema == SCHEMA) { "unknown schema '${schema ?: ""}'" }
            val defs = o.optJSONObject("inputs")
            val inputs = LinkedHashMap<String, TemplateInput>()
            defs?.keys()?.forEach { id ->
                val d = defs.optJSONObject(id) ?: return@forEach
                inputs[id] = TemplateInput(id, str(d, "type") ?: "", str(d, "label") ?: id, str(d, "help"),
                    num(d, "min"), num(d, "max"), d.opt("default")?.takeIf { it != JSONObject.NULL }, strings(d.optJSONArray("options")))
            }
            val list = o.optJSONArray("templates") ?: JSONArray()
            val templates = (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val id = str(t, "id") ?: return@mapNotNull null
                VideoTemplate(id, str(t, "name") ?: id, str(t, "description") ?: "", t.optBoolean("default"), strings(t.optJSONArray("inputs")))
            }.distinctBy { it.id }
            require(templates.isNotEmpty()) { "no templates in the list" }
            return TemplatesIndex(templates, inputs)
        }

        private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
        private fun num(o: JSONObject, k: String): Double? = if (o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }
        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toString() }
    }
}

/** Where the list on the screen came from. */
enum class TemplatesSource { BUCKET, SAVED, BUILT_IN }

data class LoadedTemplates(
    val index: TemplatesIndex,
    val source: TemplatesSource,
    /** why the bucket's list couldn't be used (null: it was) */
    val error: String? = null,
    /** when the saved copy was saved (ms) */
    val savedAt: Long? = null,
)

/**
 * The templates list (R2): `<prefix>templates/index.json` from the bucket, saved on the phone;
 * offline or on any error the last saved copy; without one the built-in default only.
 */
object Templates {
    fun key(cfg: S3Config): String = cfg.prefix + "templates/index.json"

    /** [fetch]: reads the bucket's file (null: no bucket set up). */
    fun load(fetch: (() -> ByteArray)?, cache: File): LoadedTemplates {
        val error = if (fetch == null) "no bucket set up" else try {
            val text = String(fetch(), Charsets.UTF_8)
            val index = TemplatesIndex.parse(text)
            runCatching {
                cache.parentFile?.mkdirs()
                val tmp = File(cache.path + ".tmp"); tmp.writeText(text, Charsets.UTF_8); tmp.renameTo(cache)
            }
            return LoadedTemplates(index, TemplatesSource.BUCKET)
        } catch (e: Exception) {
            reason(e)
        }
        val saved = runCatching { TemplatesIndex.parse(cache.readText(Charsets.UTF_8)) }.getOrNull()
        return if (saved != null) LoadedTemplates(saved, TemplatesSource.SAVED, error, cache.lastModified())
        else LoadedTemplates(TemplatesIndex.FALLBACK, TemplatesSource.BUILT_IN, error)
    }

    private fun reason(e: Exception): String = when (e) {
        is S3Exception -> when (e.code) {
            "NoSuchKey" -> "no template list in the bucket"
            "AccessDenied" -> "the key may not read templates/index.json"
            else -> e.friendly
        }
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
        is java.util.concurrent.TimeoutException, is javax.net.ssl.SSLException -> "offline"
        is IllegalArgumentException -> e.message ?: "not a template list"
        is org.json.JSONException -> "not a template list"
        else -> e.message ?: e.javaClass.simpleName
    }
}
