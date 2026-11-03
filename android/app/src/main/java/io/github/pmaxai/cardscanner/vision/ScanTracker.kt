package io.github.pmaxai.cardscanner.vision

import android.os.SystemClock

/**
 * Keeps the shown card steady and decides when to "take the shot".
 *
 * A card is taken once the guide frame has been green (a sure match) for the same card
 * for [delayMs] (0 = on the first green frame). One frame that isn't green is forgiven
 * (a blurry frame while holding still). Cards that never get green (only yellow) are
 * still taken after several agreeing frames. A card the user picked (locked) is only
 * replaced by a clearly different, sure card.
 */
class ScanTracker(private val sureMargin: Float, private val likelyMargin: Float) {
    private data class Vote(val group: String?, val sure: Boolean, val t: Long)

    /** how long the frame must stay green before the card is taken */
    @Volatile var delayMs: Long = 300

    private val window = ArrayDeque<Vote>()
    var shown: String? = null
        private set
    var locked = false
        private set

    // the current green streak
    private var streakGroup: String? = null
    private var streakStart = 0L
    private var streakMiss = 0

    fun reset() {
        window.clear()
        shown = null
        locked = false
        streakGroup = null
    }

    /** The user chose this card: show it and keep it. */
    fun lock(group: String) {
        shown = group
        locked = true
        window.clear()
        streakGroup = null
    }

    /** How long the current card has been green, for a progress hint (null: no streak). */
    fun streakMillis(now: Long = SystemClock.elapsedRealtime()): Long? =
        streakGroup?.takeIf { it != shown }?.let { now - streakStart }

    /** Feed one frame; returns true if the shown card changed. */
    fun update(group: String?, margin: Float, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val sure = group != null && margin >= sureMargin
        val vote = if (group != null && margin >= likelyMargin) Vote(group, sure, now) else Vote(null, false, now)
        window.addLast(vote)
        while (window.size > 6) window.removeFirst()

        // green streak of one card
        when {
            sure && group == streakGroup -> streakMiss = 0
            sure -> { streakGroup = group; streakStart = now; streakMiss = 0 }
            else -> if (++streakMiss >= 2) streakGroup = null
        }
        val g = streakGroup
        if (g != null && g != shown) {
            val held = now - streakStart
            val ok = if (locked) held >= maxOf(delayMs, 700L) && window.takeLast(5).count { it.group == g && it.sure } >= 2
                     else held >= delayMs
            if (ok) return take(g)
        }

        // cards that only get yellow: several agreeing frames, and never faster than the delay
        if (locked) return false
        val counts = window.mapNotNull { it.group }.groupingBy { it }.eachCount()
        val (cand, n) = counts.maxByOrNull { it.value }?.toPair() ?: return false
        if (cand == shown) return false
        val lastN = window.takeLast(4).count { it.group == cand }
        val since = window.first { it.group == cand }.t
        val ok = (if (shown == null) n >= 3 else lastN == 4) && now - since >= delayMs
        return if (ok) take(cand) else false
    }

    private fun take(group: String): Boolean {
        shown = group
        locked = false
        streakGroup = null
        return true
    }
}
