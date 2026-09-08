package com.iptv.tv.subtitle

import com.iptv.tv.domain.model.SubtitleQuery

internal object SubtitleMatch {

    fun score(
        query: SubtitleQuery,
        releaseName: String,
        fileName: String,
        language: String,
        hearingImpaired: Boolean,
        season: Int?,
        episode: Int?,
    ): Int {
        val haystack = normalize("$releaseName $fileName")
        val titleTokens = tokens(query.title)
        var score = 35
        if (titleTokens.isNotEmpty()) {
            val hits = titleTokens.count { it in haystack }
            score += ((hits.toFloat() / titleTokens.size) * 30).toInt()
        }
        query.year?.let { year ->
            if (haystack.contains(year)) score += 12
        }
        query.season?.let { seasonNumber ->
            if (season == seasonNumber || haystack.contains(seasonToken(seasonNumber))) score += 10
        }
        query.episode?.let { episodeNumber ->
            if (episode == episodeNumber || haystack.contains(episodeToken(episodeNumber))) score += 12
        }
        query.filename?.let { filename ->
            val fileTokens = tokens(filename)
            if (fileTokens.isNotEmpty()) {
                val hits = fileTokens.count { it.length > 2 && it in haystack }
                score += ((hits.toFloat() / fileTokens.size) * 20).toInt()
            }
        }
        if (language.equals(query.language, ignoreCase = true) ||
            language.equals("en", ignoreCase = true) && query.language.equals("en", ignoreCase = true)
        ) {
            score += 8
        }
        if (hearingImpaired) score -= 8
        return score.coerceIn(0, 100)
    }

    fun suggestedQuery(title: String, year: String?, season: Int?, episode: Int?): String {
        val cleaned = title.trim()
        val withYear = if (!year.isNullOrBlank() && !cleaned.contains(year)) "$cleaned $year" else cleaned
        if (season != null && episode != null) {
            return "$withYear S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        }
        return withYear
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""[._\-+\[\]()]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokens(value: String): List<String> =
        normalize(value).split(' ').filter { it.length > 1 && it !in STOP }

    private fun seasonToken(season: Int) = "s${season.toString().padStart(2, '0')}"

    private fun episodeToken(episode: Int) = "e${episode.toString().padStart(2, '0')}"

    private val STOP = setOf("the", "and", "of", "a", "an")

    /** True when [name] carries the given season/episode as S01E05, 1x05, or "Episode 5" style markers. */
    fun mentionsEpisode(name: String, season: Int, episode: Int): Boolean {
        val n = name.lowercase()
        val se = "s%02de%02d".format(season, episode)
        val x = "${season}x%02d".format(episode)
        if (n.contains(se) || n.contains(x)) return true
        return Regex("""(?:^|[^0-9])e%02d(?:[^0-9]|$)""".format(episode)).containsMatchIn(n) &&
            Regex("""(?:^|[^0-9])s%02d(?:[^0-9]|$)""".format(season)).containsMatchIn(n)
    }
}
