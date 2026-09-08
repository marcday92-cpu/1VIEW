package com.iptv.tv.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sofa search: ignore punctuation, tolerate typos, rank closest titles first.
 */
object TitleMatch {

    /** Lower-case letters and digits of any script; everything else becomes a space. */
    fun normalize(value: String): String =
        value.lowercase(Locale.UK)
            .replace("&", " and ")
            .replace(NON_WORD, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")

    /** LIKE-safe stems so a typo still pulls candidates from Room. */
    fun stems(query: String): List<String> {
        val n = normalize(query)
        if (n.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        out += n
        val compact = n.replace(" ", "")
        if (compact.length >= 3) out += compact
        n.split(' ').filter { it.length >= 3 }.forEach { token ->
            out += token
            if (token.length >= 5) out += token.dropLast(1)
            if (token.length >= 6) out += token.take(4)
        }
        return out.map { likeSafe(it) }.filter { it.length >= 2 }.take(6)
    }

    fun likeSafe(value: String): String =
        value.replace("%", "").replace("_", "").replace("'", "")

    /**
     * Higher is closer. 0 means not a match.
     */
    fun score(query: String, title: String, extra: String? = null): Int {
        val q = normalize(query)
        if (q.length < 2) return 0
        return scoreNormalized(q, normalize(title), extra?.let(::normalize))
    }

    /** Same as [score] but for inputs already passed through [normalize]. */
    fun scoreNormalized(q: String, title: String, extra: String? = null): Int {
        if (q.isEmpty()) return 0
        val best = max(
            scoreNormalized(q, title),
            extra?.let { scoreNormalized(q, it) } ?: 0,
        )
        return best
    }

    private fun scoreNormalized(q: String, t: String): Int {
        if (t.isEmpty()) return 0
        // A single character only matches a title that starts with it: typing "b" must not
        // list every title containing a "b".
        if (q.length == 1) return if (t.startsWith(q)) 9_000 - t.length.coerceAtMost(300) else 0
        val qc = q.replace(" ", "")
        val tc = t.replace(" ", "")
        when {
            t == q || tc == qc -> return 10_000
            t.startsWith(q) -> return 9_000 - (t.length - q.length).coerceAtMost(300)
            tc.startsWith(qc) && qc.length >= 3 -> return 8_800 - (tc.length - qc.length).coerceAtMost(300)
        }
        val qTokens = q.split(' ')
        val tTokens = t.split(' ')
        if (tokensInOrder(qTokens, tTokens)) {
            return 8_200 - (tTokens.size - qTokens.size).coerceAtLeast(0) * 15
        }
        val containedAt = t.indexOf(q)
        if (containedAt >= 0) return 7_600 - containedAt.coerceAtMost(200)
        if (qc.length >= 3) {
            val compactAt = tc.indexOf(qc)
            if (compactAt >= 0) return 7_400 - compactAt.coerceAtMost(200)
        }

        var tokenScore = 0
        var unmatched = 0
        for (qt in qTokens) {
            val best = tTokens.maxOfOrNull { tt -> tokenSimilarity(qt, tt) } ?: 0
            if (best <= 0) unmatched++ else tokenScore += best
        }
        if (unmatched == 0 && tokenScore > 0) return 5_400 + tokenScore
        if (unmatched <= qTokens.size / 2 && tokenScore >= 70) {
            return 3_200 + tokenScore - unmatched * 250
        }

        if (qc.length >= 4 && abs(tc.length - qc.length) <= qc.length / 2 + 2) {
            val d = levenshtein(qc, tc.take(qc.length + 6))
            val allow = max(1, qc.length / 3)
            if (d <= allow) return 2_400 - d * 40
        }
        return 0
    }

    private fun tokensInOrder(query: List<String>, title: List<String>): Boolean {
        if (query.isEmpty()) return false
        var i = 0
        for (tt in title) {
            val qt = query[i]
            if (tt.startsWith(qt) || qt.startsWith(tt) && tt.length >= 3 || tokenSimilarity(qt, tt) >= 70) {
                i++
                if (i == query.size) return true
            }
        }
        return false
    }

    private fun tokenSimilarity(q: String, t: String): Int {
        if (t == q) return 100
        if (t.startsWith(q) || (q.startsWith(t) && t.length >= 3)) return 85
        if (q.length >= 3 && t.contains(q)) return 55
        val d = levenshtein(q, t)
        val allow = if (q.length <= 4) 1 else max(1, q.length / 3)
        if (d <= allow) return 75 - d * 12
        return 0
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val n = a.length
        val m = b.length
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[m]
    }
}
