package io.github.pmaxai.cardscanner.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
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
    /** what the script check saw (for the details view): "kana 120 · kanji 40 · hangul 3" */
    val script: String = "",
    /** the card's name as printed: the largest line of the name bar, without "HP 160" */
    val title: String = "",
)

/**
 * Reads a straightened card with on-device text recognition (ML Kit, offline) to find
 * its number (037/167) and its language. Western languages come from the words every card
 * prints ("Weakness" / "Schwäche" / "Faiblesse"..., HP / KP / PV / PS) and from the name.
 * Without those words the printed script decides: the Japanese and the Korean reader look
 * at the card, and the one that sees more of its own script wins (characters weighted by
 * the reader's confidence): Hangul is Korean, kana Japanese, Chinese characters without kana
 * Chinese - traditional or simplified by the characters only one of the two uses.
 */
class VersionResolver : AutoCloseable {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** Load the recognition models now (the first use takes a few seconds), off the main thread. */
    fun warmUp() {
        val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val img = InputImage.fromBitmap(blank, 0)
        for ((name, r) in listOf("latin" to latin, "japanese" to japanese, "korean" to korean, "chinese" to chinese))
            runCatching { Tasks.await(r.process(img), 30, TimeUnit.SECONDS) }.onFailure { Log.w(TAG, "$name warm-up", it) }
    }

    private fun recognise(r: com.google.mlkit.vision.text.TextRecognizer, img: InputImage, what: String): Text? =
        runCatching { Tasks.await(r.process(img), 20, TimeUnit.SECONDS) }
            .onFailure { Log.w(TAG, "$what text recognition failed", it) }.getOrNull()

    /** [detectAsian]: check the printed script when the western words are missing (false: western languages only) */
    fun read(card: Bitmap, detectAsian: Boolean = true): CardText {
        val img = InputImage.fromBitmap(card, 0)
        var text = recognise(latin, img, "latin")
        var asian: String? = null
        var asianChars = 0
        var scriptInfo = ""
        // a western card prints "Weakness", "Schwäche" …; an Asian card doesn't (the Latin reader turns its
        // script into made-up words, so their number says nothing)
        val western = text?.let { westernScore(it.text) } ?: 0.0
        if (detectAsian && western < WESTERN_SURE) {
            val s = asianScript(img)
            scriptInfo = s.info
            if (s.lang != null && s.text != null) {
                text = s.text
                asian = s.lang
                asianChars = s.chars
            }
        }
        if (text == null) return CardText(null, null, null, 0.0, emptyMap(), "", "", scriptInfo)
        val result = analyse(text, card.height, asian, asianChars).copy(script = scriptInfo)
        // the set number is tiny: read the bottom strip again, enlarged
        val strip = readNumberStrip(card)
        return if (strip != null) result.copy(number = strip.first, total = strip.second) else result
    }

    /** The printed script: [lang] ja, ko, zh-tw or zh-cn (null: none of them), the text read in it, and what was seen. */
    private class Script(val lang: String?, val text: Text?, val chars: Int, val info: String)

    private fun asianScript(img: InputImage): Script {
        val jt = recognise(japanese, img, "japanese")
        val kt = recognise(korean, img, "korean")
        val kana = weighted(jt, ::isKana)
        val han = weighted(jt, ::isHan)
        val hangul = weighted(kt, ::isHangul)
        val seen = "kana %.0f · kanji %.0f · hangul %.0f".format(java.util.Locale.ROOT, kana, han, hangul)
        return when {
            // each reader turns the other script into a few of its own characters; the real one sees far more
            hangul >= MIN_SCRIPT && hangul > kana + han -> Script("ko", kt, hangul.toInt(), "$seen → Korean")
            kana >= 3 && kana + han >= MIN_SCRIPT && kana >= 0.15 * han -> Script("ja", jt, (kana + han).toInt(), "$seen → Japanese")
            han >= MIN_SCRIPT && han > hangul -> {
                // Chinese: the Chinese reader knows both character sets; which one is printed
                val ct = recognise(chinese, img, "chinese")
                val read = ct?.text ?: jt?.text.orEmpty()
                val trad = read.count { it in TRADITIONAL }
                val simp = read.count { it in SIMPLIFIED }
                val lang = if (simp > trad) "zh-cn" else "zh-tw"
                Script(lang, ct ?: jt, han.toInt(), "$seen · traditional $trad · simplified $simp → " +
                    if (lang == "zh-cn") "Chinese (simplified)" else "Chinese (traditional)")
            }
            else -> Script(null, null, 0, seen)
        }
    }

    /** characters of one script, each counted with its line's confidence */
    private fun weighted(t: Text?, script: (Char) -> Boolean): Double =
        t?.textBlocks?.flatMap { it.lines }?.sumOf { l ->
            val c = runCatching { l.confidence.toDouble() }.getOrDefault(0.0).takeIf { it > 0 } ?: 0.6
            l.text.count(script) * c
        } ?: 0.0

    private fun readNumberStrip(card: Bitmap): Pair<String, String>? {
        val y0 = (card.height * 0.86).toInt()
        val crop = Bitmap.createBitmap(card, 0, y0, card.width, card.height - y0)
        val big = Bitmap.createScaledBitmap(crop, (crop.width * 2.5).toInt(), (crop.height * 2.5).toInt(), true)
        val t = recognise(latin, InputImage.fromBitmap(big, 0), "number strip") ?: return null
        for (line in t.textBlocks.flatMap { it.lines }) {
            val m = NUMBER.find(line.text.replace(" ", "")) ?: continue
            return cleanNumber(m.groupValues[1], m.groupValues[2]) to m.groupValues[2]
        }
        return null
    }

    private fun analyse(t: Text, height: Int, asian: String?, asianChars: Int): CardText {
        val lines = t.textBlocks.flatMap { it.lines }
        val all = lines.joinToString("\n") { it.text }
        // card number: the lowest "123/456" on the card
        var number: String? = null
        var total: String? = null
        var bestY = -1
        for (l in lines) {
            val y = l.boundingBox?.centerY() ?: continue
            val m = NUMBER.find(l.text.replace(" ", "")) ?: continue
            if (y > bestY) { bestY = y; number = cleanNumber(m.groupValues[1], m.groupValues[2]); total = m.groupValues[2] }
        }
        if (number == null) PROMO.find(all.replace(" ", ""))?.let { number = it.value }
        // name bar: the upper 16% of the card; the name is its largest line
        val bar = lines.filter { (it.boundingBox?.centerY() ?: height) < height * 0.16 }
        val nameText = bar.joinToString(" ") { it.text }
        val title = bar.maxByOrNull { it.boundingBox?.height() ?: 0 }?.text.orEmpty()
            .replace(Regex("""(?i)\bHP\s*\d+"""), "").replace(Regex("""\s\d{2,3}\s*$"""), "").trim()

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
        if (asian != null) scores[asian] = 3.0 + asianChars / 10.0
        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull()?.takeIf { it.value > 0 }
        val margin = if (top == null) 0.0 else top.value - (ranked.getOrNull(1)?.value ?: 0.0)
        return CardText(number, total, top?.key, margin, scores, nameText, all, title = title)
    }

    override fun close() {
        latin.close()
        runCatching { japanese.close() }
        runCatching { korean.close() }
        runCatching { chinese.close() }
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

        fun kanaCount(s: String): Int = s.count(::isKana)

        fun isKana(c: Char): Boolean = Character.UnicodeScript.of(c.code).let { it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA }
        fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        fun isHangul(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HANGUL

        /** below this many (confidence-weighted) characters, a script isn't really on the card */
        private const val MIN_SCRIPT = 8.0
        /** the western words that make the script check unnecessary (HP and "Illus." are printed on Asian cards too) */
        private const val WESTERN_SURE = 2.0
        private val GENERIC = setOf("hp", "kp", "pv", "ps", "illus")

        /** How much of the words every western card prints the Latin reader found. */
        fun westernScore(text: String): Double {
            val norm = " " + fold(text) + " "
            var s = 0.0
            for ((_, words) in KEYWORDS) for (w in words) {
                if (fold(w) in GENERIC) continue
                if (norm.contains(" ${fold(w)} ") || (w.length >= 6 && norm.contains(fold(w)))) s += WEIGHT[w] ?: 1.0
            }
            return s
        }

        /**
         * Characters only traditional (Taiwan, Hong Kong) or only simplified (mainland) Chinese
         * uses, as they appear on Pokémon cards (宝可梦 / 寶可夢, 进化 / 進化, 伤害 / 傷害 …).
         */
        val TRADITIONAL: Set<Char> = "寶夢進對擊傷點張從時個會這為與場戰鬥選擇數後備棄換隻復狀態氣類屬擁計錢幣給將階變隨機傳說讓種號圖鑑體輕價現開還準卻頭麼們來過當連續於應該發樣邊雙歡戲壓術絕滿獎勵輪"
            .toSet()
        val SIMPLIFIED: Set<Char> = "宝梦进对击伤点张从时个会这为与场战选择数备弃换复状态气类属拥计钱币给将阶变随机传说让种号图鉴体轻价现开还却头们来过当连续应该发样边双欢戏压术绝满奖励轮"
            .toSet() - TRADITIONAL

        /** '037' -> '37', 'TG05' -> 'TG5' */
        fun normNumber(s: String?): String {
            val m = Regex("""^([A-Za-z]*)0*(\d+)([a-zA-Z]?)$""").find(s?.trim() ?: return "") ?: return s.trim().uppercase()
            return m.groupValues[1].uppercase() + m.groupValues[2] + m.groupValues[3].lowercase()
        }

        /**
         * The set code is printed right before the number ("TWM 034/167") and runs into it
         * once spaces are dropped. With a plain-digit total, the number is plain digits too
         * (letters stay for subsets like TG05/TG30).
         */
        fun cleanNumber(number: String, total: String): String =
            if (total.all { it.isDigit() } && number.any { it.isLetter() }) {
                Regex("""\d+[a-z]?$""").find(number)?.value ?: number
            } else number

        /** digit pairs text recognition mixes up on small print */
        private val CONFUSABLE = setOf("68", "69", "89", "08", "38", "56", "17", "09", "06", "35", "13", "47", "58")

        /** true if two normalised numbers differ in at most one easily confused digit */
        fun numberClose(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.isEmpty() || a.length != b.length) return false
            var diff = 0
            for (i in a.indices) if (a[i] != b[i]) {
                val pair = "${minOf(a[i], b[i])}${maxOf(a[i], b[i])}"
                if (pair !in CONFUSABLE) return false
                diff++
            }
            return diff <= 1
        }

        /**
         * Of the prints with the read number, the one whose name is on the card ([printedName], the name
         * bar in its own script); with several, the longest name found wins ("メガメガニウムex" over
         * "メガニウム"); a name the Pokémon has on other prints ([known]) counts too. Without a readable
         * name: a single print of that number and set size ([totalKnown]).
         */
        fun pickPrint(cands: List<io.github.pmaxai.cardscanner.data.CardInfo>, printedName: String?, known: Set<String>,
                      totalKnown: Boolean): io.github.pmaxai.cardscanner.data.CardInfo? {
            if (cands.isEmpty()) return null
            fun norm(s: String?) = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
            val printed = norm(printedName)
            val knownN = known.map(::norm).toSet()
            fun score(c: io.github.pmaxai.cardscanner.data.CardInfo): Int {
                val n = norm(c.name)
                if (n.isEmpty()) return 0
                return (if (printed.contains(n)) 10 + n.length else 0) + (if (n in knownN) 5 else 0)
            }
            val ranked = cands.map { it to score(it) }.sortedByDescending { it.second }
            val (best, top) = ranked.first()
            return when {
                top > 0 && ranked.getOrNull(1)?.second != top -> best
                top == 0 && cands.size == 1 && totalKnown && printed.isEmpty() -> best
                else -> null
            }
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
