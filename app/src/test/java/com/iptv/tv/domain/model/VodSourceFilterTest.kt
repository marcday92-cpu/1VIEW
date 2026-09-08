package com.iptv.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VodSourceFilterTest {
    @Test
    fun freeFilterKeepsArchiveMoviesOnly() {
        val free = VodItem(1, "PD Film", null, "ao_vod_comedy", null, "1955", null, source = ChannelSource.ARCHIVE)
        val fast = VodItem(3, "Movie channel", null, ChannelSource.FAST_VOD_MOVIES, null, null, "Live", source = ChannelSource.IPTV_ORG)
        val paid = VodItem(2, "IPTV Film", null, "89", null, "2024", null, source = ChannelSource.XC)
        assertTrue(free.inVodSourceFilter(VodSourceFilter.FREE))
        assertTrue(fast.inVodSourceFilter(VodSourceFilter.FREE))
        assertTrue(fast.isFree)
        assertTrue(ChannelSource.isFastFreeCategory(ChannelSource.FAST_VOD_MOVIES))
        assertTrue(!paid.inVodSourceFilter(VodSourceFilter.FREE))
        assertTrue(paid.inVodSourceFilter(VodSourceFilter.IPTV))
        assertTrue(free.inVodSourceFilter(VodSourceFilter.ALL) && paid.inVodSourceFilter(VodSourceFilter.ALL))
    }

    @Test
    fun archiveIdsStayInReservedRange() {
        val movie = ChannelSource.archiveMovieId("night_of_the_living_dead")
        val series = ChannelSource.archiveSeriesId("ilovelucy")
        val episode = ChannelSource.archiveEpisodeId("lucy_s01e01")
        assertTrue(movie >= ChannelSource.ARCHIVE_MOVIE_ID_BASE)
        assertTrue(movie < ChannelSource.ARCHIVE_SERIES_ID_BASE)
        assertTrue(series >= ChannelSource.ARCHIVE_SERIES_ID_BASE)
        assertTrue(series < ChannelSource.ARCHIVE_EPISODE_ID_BASE)
        assertTrue(episode >= ChannelSource.ARCHIVE_EPISODE_ID_BASE)
        assertTrue(episode < Int.MAX_VALUE)
        assertEquals(movie, ChannelSource.archiveMovieId("night_of_the_living_dead"))
    }
}
