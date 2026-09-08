package com.iptv.tv.player

import com.iptv.tv.domain.model.Episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesEpisodeOrderTest {
    @Test
    fun nextEpisodeIsFollowingInSameSeason() {
        val seasons = mapOf(
            1 to listOf(ep(10, 1, 1), ep(11, 1, 2), ep(12, 1, 3)),
        )
        val next = SeriesEpisodeOrder.nextAfter(seasons, streamId = 11, seasonNum = 1, episodeNum = 2)
        assertEquals(12, next?.streamId)
        assertEquals(3, next?.episodeNum)
    }

    @Test
    fun nextEpisodeCrossesSeasonToFirstOfFollowing() {
        val seasons = mapOf(
            2 to listOf(ep(21, 2, 1)),
            1 to listOf(ep(12, 1, 2), ep(11, 1, 1)),
        )
        val next = SeriesEpisodeOrder.nextAfter(seasons, streamId = 12, seasonNum = 1, episodeNum = 2)
        assertEquals(21, next?.streamId)
        assertEquals(2, next?.seasonNum)
        assertEquals(1, next?.episodeNum)
    }

    @Test
    fun lastEpisodeHasNoNext() {
        val seasons = mapOf(
            1 to listOf(ep(11, 1, 1)),
            2 to listOf(ep(21, 2, 1), ep(22, 2, 2)),
        )
        assertNull(SeriesEpisodeOrder.nextAfter(seasons, streamId = 22, seasonNum = 2, episodeNum = 2))
    }

    @Test
    fun unorderedSeasonListsAreSortedByEpisodeNumber() {
        val seasons = mapOf(
            1 to listOf(ep(13, 1, 3), ep(11, 1, 1), ep(12, 1, 2)),
        )
        val flat = SeriesEpisodeOrder.flattened(seasons).map { it.episodeNum }
        assertEquals(listOf(1, 2, 3), flat)
        assertEquals(12, SeriesEpisodeOrder.nextAfter(seasons, streamId = 11)?.streamId)
    }

    @Test
    fun unknownEpisodeIsNotInvented() {
        val seasons = mapOf(1 to listOf(ep(11, 1, 1)))
        assertNull(SeriesEpisodeOrder.nextAfter(seasons, streamId = 99, seasonNum = 9, episodeNum = 9))
    }
}

private fun ep(streamId: Int, season: Int, episode: Int) = Episode(
    id = streamId.toString(),
    episodeNum = episode,
    seasonNum = season,
    title = "E$episode",
    streamId = streamId,
    containerExtension = "mp4",
    info = null,
)
