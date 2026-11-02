package io.github.pmaxai.cardscanner.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * One candidate artwork: its group, the best reference image (entry) and which reading of
 * the photo matched it. Which exact version (set, language) it is gets decided afterwards.
 */
data class Hit(val group: String, val score: Float, val entry: Int, val reading: Int)

/**
 * Nearest neighbours over every reference fingerprint (evaluate.search): the score of a
 * reference is its best cosine over all readings of the photo, and each card counts once
 * per artwork group (same picture in every language and reprint).
 *
 * Two passes for speed: a rough score from the first [coarseDim] dimensions (PCA orders
 * them by importance) for every reference, then exact scores for the best [shortlist].
 */
class Matcher(private val index: FloatArray, private val n: Int, private val dim: Int,
              private val groupOf: Array<String>,
              private val coarseDim: Int = 128, private val shortlist: Int = 300) {

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private fun parallel(count: Int, body: (from: Int, to: Int) -> Unit) {
        val chunk = (count + threads - 1) / threads
        runBlocking(Dispatchers.Default) {
            (0 until threads).map { t ->
                async { body(t * chunk, minOf(count, (t + 1) * chunk)) }
            }.awaitAll()
        }
    }

    /** Max over readings of the dot product with entry i over dims [0, d); writes which reading won. */
    private fun score(i: Int, readings: List<FloatArray>, d: Int, which: IntArray?): Float {
        val off = i * dim
        var bs = Float.NEGATIVE_INFINITY
        var bw = 0
        for (r in readings.indices) {
            val q = readings[r]
            var s = 0f
            for (k in 0 until d) s += q[k] * index[off + k]
            if (s > bs) { bs = s; bw = r }
        }
        which?.set(i, bw)
        return bs
    }

    fun search(readings: List<FloatArray>, k: Int = 5, pool: Int = 40): List<Hit> {
        val best = FloatArray(n) { Float.NEGATIVE_INFINITY }
        val which = IntArray(n)
        val cd = minOf(coarseDim, dim)
        val candidates: IntArray
        if (n <= shortlist || cd == dim) {
            parallel(n) { from, to -> for (i in from until to) best[i] = score(i, readings, dim, which) }
            candidates = IntArray(n) { it }
        } else {
            val rough = FloatArray(n)
            parallel(n) { from, to -> for (i in from until to) rough[i] = score(i, readings, cd, null) }
            candidates = topIndices(rough, shortlist)
            parallel(candidates.size) { from, to ->
                for (j in from until to) { val i = candidates[j]; best[i] = score(i, readings, dim, which) }
            }
        }
        // best `pool + 1` entries, then distinct cards in score order
        val top = topIndices(best, minOf(pool + 1, candidates.size), candidates)
        val seen = HashSet<String>()
        val out = ArrayList<Hit>()
        for (i in top) {
            val g = groupOf[i]
            if (!seen.add(g)) continue
            out.add(Hit(g, best[i], i, which[i]))
            if (out.size > k) break
        }
        return out
    }

    companion object {
        /** Indices of the m largest values (optionally only among [among]), largest first. */
        fun topIndices(v: FloatArray, m: Int, among: IntArray? = null): IntArray {
            val cnt = among?.size ?: v.size
            val size = minOf(m, cnt)
            if (size <= 0) return IntArray(0)
            // min-heap of the best `size` seen so far
            val heap = IntArray(size)
            var h = 0
            fun less(a: Int, b: Int) = v[a] < v[b] || (v[a] == v[b] && a > b)
            fun siftDown(start: Int) {
                var i = start
                while (true) {
                    val l = 2 * i + 1
                    if (l >= h) return
                    var c = l
                    if (l + 1 < h && less(heap[l + 1], heap[l])) c = l + 1
                    if (!less(heap[c], heap[i])) return
                    val t = heap[c]; heap[c] = heap[i]; heap[i] = t
                    i = c
                }
            }
            for (j in 0 until cnt) {
                val idx = among?.get(j) ?: j
                if (h < size) {
                    heap[h] = idx
                    var i = h++
                    while (i > 0) {
                        val p = (i - 1) / 2
                        if (!less(heap[i], heap[p])) break
                        val t = heap[p]; heap[p] = heap[i]; heap[i] = t
                        i = p
                    }
                } else if (less(heap[0], idx)) {
                    heap[0] = idx
                    siftDown(0)
                }
            }
            val out = heap.copyOf(h)
            // largest first; ties: lower index first (like a stable sort)
            return out.sortedWith { a, b -> if (v[a] != v[b]) v[b].compareTo(v[a]) else a.compareTo(b) }.toIntArray()
        }
    }
}
