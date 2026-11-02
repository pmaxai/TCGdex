package io.github.pmaxai.cardscanner.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** What the card's printed text says about which version it is. */
data class CardText(
    /** card number as printed, e.g. "037" and total "167" */
    val number: String?,
    val total: String?,
    /** most likely language and how sure (score gap to the runner-up, 0..) */
    val lang: String?,
    val langMargin: Double,
    val langScores: Map<String, Double>,
    /** the name bar text, for matching names */
    val nameText: String,
    val text: String,
)

/**
 * Reads a straightened card with on-device text recognition (ML Kit, offline) to find
 * its number (037/167) and its language. The language comes from the words every card
 * prints ("Weakness" / "Schwäche" / "Faiblesse"..., HP / KP / PV / PS) and from the name;
 * Japanese is recognised by its script.
 */
class VersionResolver : AutoCloseable {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    /** Load both recognition models now (the first use takes a few seconds), off the main thread. */
    fun warmUp() {
        val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val img = InputImage.fromBitmap(blank, 0)
        runCatching { Tasks.await(latin.process(img), 30, TimeUnit.SECONDS) }.onFailure { Log.w(TAG, "latin warm-up", it) }
        runCatching { Tasks.await(japanese.process(img), 30, TimeUnit.SECONDS) }.onFailure { Log.w(TAG, "japanese warm-up", it) }
    }

    private fun recognise(r: com.google.mlkit.vision.text.TextRecognizer, img: InputImage, what: String): Text? =
        runCatching { Tasks.await(r.process(img), 20, TimeUnit.SECONDS) }
            .onFailure { Log.w(TAG, "$what text recognition failed", it) }.getOrNull()

    fun read(card: Bitmap, tryJapanese: Boolean): CardText {
        val img = InputImage.fromBitmap(card, 0)
        var text = recognise(latin, img, "latin")
        var japaneseChars = 0
        if (tryJapanese && (text == null || cjkCount(text.text) > 0 || latinWords(text.text) < 6)) {
            val jt = recognise(japanese, img, "japanese")
            if (jt != null) {
                japaneseChars = cjkCount(jt.text)
                if (japaneseChars >= 4) text = jt
            }
        }
        if (text == null) return CardText(null, null, null, 0.0, emptyMap(), "", "")
        return analyse(text, card.height, japaneseChars)
    }

    private fun analyse(t: Text, height: Int, japaneseChars: Int): CardText {
        val lines = t.textBlocks.flatMap { it.lines }
        val all = lines.joinToString("\n") { it.text }
        // card number: the lowest "123/456" on the card
        var number: String? = null
        var total: String? = null
        var bestY = -1
        for (l in lines) {
            val y = l.boundingBox?.centerY() ?: continue
            val m = NUMBER.find(l.text.replace(" ", "")) ?: continue
            if (y > bestY) { bestY = y; number = m.groupValues[1]; total = m.groupValues[2] }
        }
        if (number == null) PROMO.find(all.replace(" ", ""))?.let { number = it.value }
        // name bar: the upper 16% of the card
        val nameText = lines.filter { (it.boundingBox?.centerY() ?: height) < height * 0.16 }.joinToString(" ") { it.text }

        val scores = HashMap<String, Double>()
        val norm = " " + fold(all) + " "
        for ((lang, words) in KEYWORDS) {
            var s = 0.0
            for (w in words) if (norm.contains(" ${fold(w)} ") || (w.length >= 6 && norm.contains(fold(w)))) s += WEIGHT[w] ?: 1.0
            scores[lang] = s
        }
        // the HP label glued to the number: "HP80", "80 KP", "PV 120"
        for (m in HP.findAll(norm)) {
            val label = m.groupValues[1].ifEmpty { m.groupValues[2] }
            HP_LANGS[label]?.forEach { (lang, w) -> scores[lang] = (scores[lang] ?: 0.0) + w }
        }
        if (japaneseChars >= 4) scores["ja"] = 3.0 + japaneseChars / 10.0
        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull()?.takeIf { it.value > 0 }
        val margin = if (top == null) 0.0 else top.value - (ranked.getOrNull(1)?.value ?: 0.0)
        return CardText(number, total, top?.key, margin, scores, nameText, all)
    }

    override fun close() {
        latin.close()
        runCatching { japanese.close() }
    }

    companion object {
        private const val TAG = "CardScanner"
        private val NUMBER = Regex("""([A-Z]{0,4}\d{1,3}[a-z]?)/([A-Z]{0,4}\d{1,3})""")
        private val PROMO = Regex("""\b(SWSH|SM|XY|BW|SVP|SV|DP|HGSS)\d{2,3}\b""")
        private val HP = Regex("""(?:\b(hp|kp|pv|ps) ?\d{2,3}\b)|(?:\b\d{2,3} ?(hp|kp|pv|ps)\b)""")
        private val HP_LANGS = mapOf(
            "hp" to listOf("en" to 1.5), "kp" to listOf("de" to 1.5), "pv" to listOf("fr" to 1.5),
            "ps" to listOf("es" to 0.5, "it" to 0.5, "pt" to 0.5),
        )

        /** words printed on every card, per language (accents are ignored when comparing) */
        val KEYWORDS: Map<String, List<String>> = mapOf(
            "en" to listOf("weakness", "resistance", "retreat", "basic", "stage 1", "stage 2", "evolves from", "hp",
                "illus", "ability", "trainer", "supporter", "item", "pokemon tool", "stadium"),
            "de" to listOf("schwäche", "resistenz", "rückzug", "basis", "phase 1", "phase 2", "entwickelt sich aus", "kp",
                "fähigkeit", "unterstützer", "pokémon-ausrüstung", "stadion", "trainer"),
            "fr" to listOf("faiblesse", "résistance", "retraite", "de base", "niveau 1", "niveau 2", "évolue de", "pv",
                "talent", "dresseur", "supporter", "objet", "outil pokémon", "stade"),
            "es" to listOf("debilidad", "resistencia", "retirada", "básico", "fase 1", "fase 2", "evoluciona de", "ps",
                "habilidad", "entrenador", "partidario", "objeto", "herramienta pokémon", "estadio"),
            "it" to listOf("debolezza", "resistenza", "ritirata", "base", "fase 1", "fase 2", "si evolve da", "ps",
                "abilità", "allenatore", "aiuto", "strumento", "oggetto pokémon", "stadio"),
            "pt" to listOf("fraqueza", "resistência", "recuo", "básico", "estágio 1", "estágio 2", "evolui de", "ps",
                "habilidade", "treinador", "apoiador", "item", "ferramenta pokémon", "estádio"),
        )

        /** words shared by several languages count less */
        private val WEIGHT: Map<String, Double> = KEYWORDS.values.flatten().groupingBy { fold(it) }.eachCount()
            .let { counts -> KEYWORDS.values.flatten().associateWith { 1.0 / (counts[fold(it)] ?: 1) } }

        fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9/ ]+"), " ").replace(Regex(" +"), " ")

        fun cjkCount(s: String): Int = s.count { Character.UnicodeScript.of(it.code).let { sc ->
            sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HAN } }

        private fun latinWords(s: String): Int = Regex("[A-Za-z]{3,}").findAll(s).count()

        /** '037' -> '37', 'TG05' -> 'TG5' */
        fun normNumber(s: String?): String {
            val m = Regex("""^([A-Za-z]*)0*(\d+)([a-zA-Z]?)$""").find(s?.trim() ?: return "") ?: return s.trim().uppercase()
            return m.groupValues[1].uppercase() + m.groupValues[2] + m.groupValues[3].lowercase()
        }

        /** similarity 0..1 of two names (OCR noise tolerant) */
        fun nameSimilarity(a: String, b: String): Double {
            val x = fold(a).replace(" ", "")
            val y = fold(b).replace(" ", "")
            if (x.isEmpty() || y.isEmpty()) return 0.0
            if (x.contains(y) || y.contains(x)) return 1.0
            val d = Array(x.length + 1) { IntArray(y.length + 1) }
            for (i in 0..x.length) d[i][0] = i
            for (j in 0..y.length) d[0][j] = j
            for (i in 1..x.length) for (j in 1..y.length) {
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            }
            return 1.0 - d[x.length][y.length].toDouble() / maxOf(x.length, y.length)
        }
    }
}
