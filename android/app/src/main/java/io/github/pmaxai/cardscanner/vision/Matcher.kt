package io.github.pmaxai.cardscanner.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/** One candidate card: best reference image (entry) and which reading of the photo matched it. */
data class Hit(val cardId: String, val score: Float, val entry: Int, val reading: Int)

/**
 * Brute-force nearest neighbours over every reference fingerprint (evaluate.search):
 * the score of a reference is its best cosine over all readings of the photo, and
 * each card counts once (its best reference image, e.g. English or German).
 */
class Matcher(private val index: FloatArray, private val n: Int, private val dim: Int,
              private val cardOf: Array<String>) {

    fun search(readings: List<FloatArray>, k: Int = 5, pool: Int = 40): List<Hit> {
        val best = FloatArray(n)
        val which = IntArray(n)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val chunk = (n + threads - 1) / threads
        runBlocking(Dispatchers.Default) {
            (0 until threads).map { t ->
                async {
                    val from = t * chunk
                    val to = minOf(n, from + chunk)
                    for (i in from until to) {
                        val off = i * dim
                        var bs = Float.NEGATIVE_INFINITY
                        var bw = 0
                        for ((r, q) in readings.withIndex()) {
                            var s = 0f
                            for (d in 0 until dim) s += q[d] * index[off + d]
                            if (s > bs) { bs = s; bw = r }
                        }
                        best[i] = bs
                        which[i] = bw
                    }
                }
            }.awaitAll()
        }
        // top `pool + 1` entries, then distinct cards in score order
        val take = minOf(pool + 1, n)
        val top = IntArray(take) { -1 }
        val topS = FloatArray(take) { Float.NEGATIVE_INFINITY }
        for (i in 0 until n) {
            val s = best[i]
            if (s <= topS[take - 1]) continue
            var j = take - 1
            while (j > 0 && topS[j - 1] < s) { topS[j] = topS[j - 1]; top[j] = top[j - 1]; j-- }
            topS[j] = s; top[j] = i
        }
        val seen = HashSet<String>()
        val out = ArrayList<Hit>()
        for (j in 0 until take) {
            val i = top[j]
            if (i < 0) break
            val cid = cardOf[i]
            if (!seen.add(cid)) continue
            out.add(Hit(cid, topS[j], i, which[i]))
            if (out.size > k) break
        }
        return out
    }
}
