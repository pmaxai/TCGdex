package io.github.pmaxai.cardscanner.shop

import io.github.pmaxai.cardscanner.data.Printing

/** The shop's words: card languages, conditions and finishes (Shop API v1 enums). */
object ShopTerms {
    /** card_language, with the name a collector knows it by */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "DE" to "Deutsch", "EN" to "English", "FR" to "Français", "IT" to "Italiano", "ES" to "Español",
        "PT" to "Português", "NL" to "Nederlands", "PL" to "Polski", "RU" to "Russkij", "JA" to "日本語",
        "KO" to "한국어", "ZH-HANT" to "中文 繁體", "ZH-HANS" to "中文 简体", "ID" to "Indonesia", "TH" to "ไทย",
    )

    /** condition, best first; [share] of the Near Mint price as in Trading Mode */
    data class Grade(val code: String, val label: String, val share: Double)
    val CONDITIONS: List<Grade> = listOf(
        Grade("M", "Mint", 1.0), Grade("NM", "Near Mint", 1.0), Grade("EX", "Excellent", 0.85), Grade("GD", "Good", 0.75),
        Grade("LP", "Light Played", 0.65), Grade("PL", "Played", 0.50), Grade("PO", "Poor", 0.30),
    )

    fun share(condition: String): Double = CONDITIONS.firstOrNull { it.code == condition }?.share ?: 1.0

    /** The shop's card_language for the language the app read off the card. */
    fun languageOf(appLang: String?): String = when (appLang?.lowercase()) {
        "de" -> "DE"; "en" -> "EN"; "fr" -> "FR"; "it" -> "IT"; "es", "es-mx" -> "ES"
        "pt", "pt-br", "pt-pt" -> "PT"; "nl" -> "NL"; "pl" -> "PL"; "ru" -> "RU"
        "ja" -> "JA"; "ko" -> "KO"; "zh-tw", "zh-hant" -> "ZH-HANT"; "zh-cn", "zh-hans" -> "ZH-HANS"
        "id" -> "ID"; "th" -> "TH"
        else -> "EN"
    }

    fun languageName(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

    /** The shop's finish for a printing the app knows. */
    fun finishOf(kind: Printing.Kind?): String = when (kind) {
        Printing.Kind.HOLO -> "holo"
        Printing.Kind.REVERSE -> "reverse_holo"
        Printing.Kind.FIRST_EDITION -> "first_edition"
        Printing.Kind.NORMAL, null -> "normal"
    }

    fun finishLabel(f: String): String = when (f) {
        "normal" -> "Normal"; "holo" -> "Holo"; "reverse_holo" -> "Reverse Holo"; "first_edition" -> "1st Edition"
        "shadowless" -> "Shadowless"; "stamped" -> "Stamped"; "pokeball" -> "Poké Ball"; "masterball" -> "Master Ball"
        else -> f
    }

    fun statusLabel(s: String): String = when (s) {
        "active" -> "for sale"; "draft" -> "draft (not in the shop)"; "archived" -> "archived"; else -> s
    }
}
