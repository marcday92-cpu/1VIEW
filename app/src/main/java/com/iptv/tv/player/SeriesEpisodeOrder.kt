package com.iptv.tv.player

import com.iptv.tv.domain.model.Episode

/** Xtream season keys and episode lists are not guaranteed in order. */
object SeriesEpisodeOrder {
    fun sortedSeasons(seasons: Map<Int, List<Episode>>): Map<Int, List<Episode>> =
        seasons.entries
            .sortedBy { it.key }
            .associate { (season, episodes) ->
                season to episodes.sortedBy { it.episodeNum }
            }

    fun flattened(seasons: Map<Int, List<Episode>>): List<Episode> =
        sortedSeasons(seasons).values.flatten()

    fun nextAfter(
        seasons: Map<Int, List<Episode>>,
        streamId: Int,
        seasonNum: Int? = null,
        episodeNum: Int? = null,
    ): Episode? {
        val list = flattened(seasons)
        val byId = list.indexOfFirst { it.streamId == streamId }
        val index = when {
            byId >= 0 -> byId
            seasonNum != null && episodeNum != null ->
                list.indexOfFirst { it.seasonNum == seasonNum && it.episodeNum == episodeNum }
            else -> -1
        }
        if (index < 0) return null
        return list.getOrNull(index + 1)
    }
}
