package com.iptv.tv.util

/**
 * In-memory ranked search over a list that is already loaded (Live channels). Names are
 * normalised once when the index is built, so typing only pays for the comparison.
 *
 * Ranking reuses [TitleMatch] so every search in the app orders results the same way.
 * [programmes] are secondary texts (what is on now / next): a hit on one ranks just below
 * the same quality of hit on the name, and the matching title is reported in [Match].
 */
class SearchIndex<T>(
    items: List<T>,
    private val name: (T) -> String,
    private val extra: (T) -> String? = { null },
    private val programmes: (T) -> List<String> = { emptyList() },
) {
    /**
     * A result and, when the query matched a programme rather than the name, that title.
     * [onAir] marks the reserved block of strong programme hits that leads the list.
     */
    data class Match<T>(val item: T, val programme: String?, val onAir: Boolean = false)

    private class Entry<T>(
        val item: T,
        val normalized: String,
        val normalizedExtra: String?,
        val programmes: List<String>,
        val normalizedProgrammes: List<String>,
    )

    private val entries: List<Entry<T>> = items.map { item ->
        val titles = programmes(item)
        Entry(
            item,
            TitleMatch.normalize(name(item)),
            extra(item)?.let(TitleMatch::normalize),
            titles,
            titles.map(TitleMatch::normalize),
        )
    }

    val size: Int get() = entries.size

    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<T> =
        searchDetailed(query, limit).map { it.item }

    /**
     * Strong programme hits (the title starts with the query, or the query's words appear in
     * order) lead the list in a small reserved block, so "us open" shows the channels carrying
     * it even when dozens of channel names start with "US". Everything else follows by score.
     */
    fun searchDetailed(query: String, limit: Int = DEFAULT_LIMIT): List<Match<T>> {
        val q = TitleMatch.normalize(query)
        if (q.length < MIN_QUERY) return emptyList()
        class Scored(val entry: Entry<T>, val score: Int, val programme: String?, val programmeScore: Int)
        val scored = ArrayList<Scored>()
        for (entry in entries) {
            val nameScore = TitleMatch.scoreNormalized(q, entry.normalized, entry.normalizedExtra)
            var bestProgramme: String? = null
            var bestProgrammeScore = 0
            entry.normalizedProgrammes.forEachIndexed { i, title ->
                val s = TitleMatch.scoreNormalized(q, title)
                if (s > bestProgrammeScore) {
                    bestProgrammeScore = s
                    bestProgramme = entry.programmes[i]
                }
            }
            val penalised = (bestProgrammeScore - PROGRAMME_PENALTY).coerceAtLeast(0)
            val score = maxOf(nameScore, penalised)
            if (score <= 0) continue
            val programme = bestProgramme.takeIf { penalised > nameScore }
            scored += Scored(entry, score, programme, bestProgrammeScore)
        }
        val byScore = compareByDescending<Scored> { it.score }.thenBy { it.entry.normalized }
        val onAir = scored.asSequence()
            .filter { it.programme != null && it.programmeScore >= STRONG_MATCH && q.length >= ON_AIR_MIN_QUERY }
            .sortedWith(compareByDescending<Scored> { it.programmeScore }.thenBy { it.entry.normalized })
            .take(ON_AIR_BLOCK)
            .toList()
        val rest = scored.asSequence()
            .filter { candidate -> onAir.none { it === candidate } }
            .sortedWith(byScore)
            .take((limit - onAir.size).coerceAtLeast(0))
        return (onAir.asSequence().map { Match(it.entry.item, it.programme, onAir = true) } +
            rest.map { Match(it.entry.item, it.programme) }).toList()
    }

    companion object {
        const val DEFAULT_LIMIT = 80
        const val MIN_QUERY = 1
        /** A programme hit ranks just below the same hit on a channel name. */
        private const val PROGRAMME_PENALTY = 150
        /** [TitleMatch] scores from a prefix or ordered-words match upward. */
        private const val STRONG_MATCH = 8_000
        private const val ON_AIR_BLOCK = 8
        private const val ON_AIR_MIN_QUERY = 2
    }
}
