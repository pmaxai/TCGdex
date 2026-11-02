package io.github.pmaxai.cardscanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Half
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The data pack built by the pipeline (`python -m tcgscan export`):
 * fingerprint model, index of every reference image, catalog with prices.
 * It ships inside the APK (assets/pack) and is copied to app storage once per version.
 */
class Pack(
    val dir: File,
    val version: String,
    val meta: Map<String, String>,
    val db: SQLiteDatabase,
    /** n x dim fingerprints, row-major */
    val index: FloatArray,
    val n: Int,
    val dim: Int,
    val entryCard: Array<String>,
    val entryLang: Array<String>,
    /** artwork group of each entry: same picture across languages and reprints */
    val entryGroup: Array<String>,
) {
    val modelPath: String get() = File(dir, "model.onnx").absolutePath

    fun metaInt(key: String): Int = meta[key]?.toInt() ?: error("pack meta '$key' missing")
    fun metaFloat(key: String, default: Float): Float = meta[key]?.toFloatOrNull() ?: default

    companion object {
        private val FILES = listOf("pack.json", "model.onnx", "index.f16", "catalog.sqlite")

        fun hasBundledPack(ctx: Context): Boolean =
            runCatching { ctx.assets.open("pack/pack.json").close() }.isSuccess

        /** Slow (copies ~50 MB the first time, converts the index): call off the main thread. */
        fun load(ctx: Context): Pack {
            val dir = File(ctx.filesDir, "pack")
            val bundled = JSONObject(ctx.assets.open("pack/pack.json").bufferedReader().readText())
            val version = bundled.getString("version")
            val installed = File(dir, "pack.json").takeIf { it.exists() }
                ?.let { runCatching { JSONObject(it.readText()).getString("version") }.getOrNull() }
            if (installed != version) {
                dir.deleteRecursively()
                dir.mkdirs()
                for (name in FILES.reversed()) {          // pack.json last: marks a complete copy
                    ctx.assets.open("pack/$name").use { input ->
                        File(dir, "$name.tmp").outputStream().use { input.copyTo(it, 1 shl 20) }
                    }
                    File(dir, "$name.tmp").renameTo(File(dir, name))
                }
            }

            val db = SQLiteDatabase.openDatabase(
                File(dir, "catalog.sqlite").absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            val meta = HashMap<String, String>()
            db.rawQuery("SELECT key, value FROM meta", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
            val dim = meta.getValue("dim").toInt()
            val n = meta.getValue("entries").toInt()

            val cards = arrayOfNulls<String>(n)
            val langs = arrayOfNulls<String>(n)
            val groups = arrayOfNulls<String>(n)
            val hasGroups = db.rawQuery("PRAGMA table_info(entries)", null).use { c ->
                var found = false
                while (c.moveToNext()) if (c.getString(1) == "grp") found = true
                found
            }
            val sql = if (hasGroups) "SELECT idx, card_id, lang, grp FROM entries" else "SELECT idx, card_id, lang, card_id FROM entries"
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val i = c.getInt(0)
                    cards[i] = c.getString(1)
                    langs[i] = c.getString(2)
                    groups[i] = c.getString(3)
                }
            }

            val bytes = File(dir, "index.f16").readBytes()
            require(bytes.size == n * dim * 2) { "index.f16 has ${bytes.size} bytes, expected ${n * dim * 2}" }
            val halves = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val index = FloatArray(n * dim)
            for (i in index.indices) index[i] = Half.toFloat(halves.get(i))

            return Pack(dir, version, meta, db, index, n, dim, cards.requireNoNulls(), langs.requireNoNulls(),
                groups.requireNoNulls())
        }
    }
}
