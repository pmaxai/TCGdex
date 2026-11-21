package io.github.pmaxai.cardscanner.data

/**
 * Where a price comes from. Cardmarket (EUR) and TCGplayer (USD) are in every plan; the person
 * picks the one the app shows first (Settings › Prices): the headline of a card, the lists, the
 * price alert and the market value in Add to cart. A card without a price from it shows the next
 * one in [FALLBACK].
 */
enum class PriceSource(val short: String) {
    CM_TREND("trend"), CM_AVG30("30-day average"), CM_AVG("average sell price"), CM_LOW("lowest offer"),
    TCG_MARKET("market price"), TCG_MID("mid price"), TCG_LOW("low price");

    /** TCGplayer gives USD (shown in EUR at the data pack's rate as well) */
    val isUsd: Boolean get() = this == TCG_MARKET || this == TCG_MID || this == TCG_LOW

    val provider: String get() = if (isUsd) "TCGplayer" else "Cardmarket"

    /** "Cardmarket trend" */
    val label: String get() = "$provider $short"

    /** "Trend", in the price table under its provider */
    val title: String get() = short.replaceFirstChar { it.uppercase() }

    companion object {
        /** the setting's key */
        const val KEY = "price_source"

        /** when the chosen source has no price for a card: the next ones, in this order */
        val FALLBACK = listOf(CM_TREND, CM_AVG30, CM_AVG, TCG_MARKET, TCG_MID, CM_LOW, TCG_LOW)

        /** Cardmarket first, then TCGplayer, as the price table shows them */
        val PROVIDERS: List<Pair<String, List<PriceSource>>> = listOf(
            "Cardmarket" to listOf(CM_TREND, CM_AVG30, CM_AVG, CM_LOW),
            "TCGplayer" to listOf(TCG_MARKET, TCG_MID, TCG_LOW),
        )

        /** the person's choice (Settings › Prices); the view model sets it when the settings load or change */
        @Volatile var current: PriceSource = CM_TREND

        fun of(name: String?): PriceSource = values().firstOrNull { it.name == name } ?: CM_TREND
    }
}

/** One price shown: from which source, as it gives it, and in EUR (TCGplayer at the pack's rate; null without one). */
data class PriceShown(val source: PriceSource, val native: Double, val eur: Double?)

/** TCGplayer's prices of one printing (USD). */
data class TcgPrice(val market: Double? = null, val mid: Double? = null, val low: Double? = null) {
    val best: Double? get() = market ?: mid ?: low
}
