package io.github.pmaxai.cardscanner.vision

/**
 * Keeps the shown card steady. Single frames often disagree; a card is shown once it wins
 * repeatedly, and a different card replaces it only after winning several frames in a row.
 * A card the user picked (locked) is replaced only by a clearly different, sure card.
 */
class ScanTracker(private val sureMargin: Float, private val likelyMargin: Float) {
    private data class Vote(val group: String?, val sure: Boolean)

    private val window = ArrayDeque<Vote>()
    var shown: String? = null
        private set
    var locked = false
        private set

    fun reset() {
        window.clear()
        shown = null
        locked = false
    }

    /** The user chose this card: show it and keep it. */
    fun lock(group: String) {
        shown = group
        locked = true
        window.clear()
    }

    /** Feed one frame; returns true if the shown card changed. */
    fun update(group: String?, margin: Float): Boolean {
        val vote = if (group != null && margin >= likelyMargin) Vote(group, margin >= sureMargin) else Vote(null, false)
        // a very clear frame counts double (a card held still in good light)
        val verySure = group != null && margin >= sureMargin * VERY_SURE
        window.addLast(vote)
        while (window.size > 6) window.removeFirst()

        val counts = window.mapNotNull { it.group }.groupingBy { it }.eachCount()
        val (cand, n) = counts.maxByOrNull { it.value }?.toPair() ?: return false
        if (cand == shown) return false
        val anySure = window.any { it.group == cand && it.sure }
        val last = { k: Int -> window.takeLast(k) }
        val lastN = { k: Int -> last(k).count { it.group == cand } }
        val lastSure = { k: Int -> last(k).count { it.group == cand && it.sure } }
        val ok = when {
            // nothing shown yet: one very clear frame, two with one sure, or three likely ones
            shown == null -> (verySure && group == cand) || (n >= 2 && anySure) || n >= 3
            // a card the user picked: only a clearly different, sure card replaces it
            locked -> lastN(5) >= 4 && lastSure(5) >= 2
            // another card is shown: two sure frames in a row, or it wins 3 of the last 4
            // (with one sure), or 4 likely frames in a row
            else -> lastSure(2) == 2 || (lastN(4) >= 3 && anySure) || lastN(4) == 4
        }
        if (!ok) return false
        shown = cand
        locked = false
        return true
    }

    companion object {
        /** margin multiple of 'sure' at which a single frame is enough */
        const val VERY_SURE = 1.6f
    }
}
