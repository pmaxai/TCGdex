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
        window.addLast(vote)
        while (window.size > 6) window.removeFirst()

        val counts = window.mapNotNull { it.group }.groupingBy { it }.eachCount()
        val (cand, n) = counts.maxByOrNull { it.value }?.toPair() ?: return false
        val anySure = window.any { it.group == cand && it.sure }
        if (cand == shown) return false
        val lastN = { k: Int -> window.takeLast(k).count { it.group == cand } }
        val ok = when {
            shown == null -> (n >= 2 && anySure) || n >= 3
            locked -> lastN(5) >= 4 && window.takeLast(5).count { it.group == cand && it.sure } >= 2
            else -> lastN(4) >= 3 && anySure
        }
        if (!ok) return false
        shown = cand
        locked = false
        return true
    }
}
