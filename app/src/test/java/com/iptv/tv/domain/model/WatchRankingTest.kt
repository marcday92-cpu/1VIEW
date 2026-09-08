package com.iptv.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchRankingTest {
    private fun channel(id: Int, category: String) = Channel(id, "Channel $id", null, category, null)

    @Test
    fun channelsRankByTimeAndKeepSourceOrderForTies() {
        val source = listOf(channel(1, "a"), channel(2, "a"), channel(3, "a"))
        val ranked = WatchRanking.channels(source, mapOf(2 to 20_000L, 3 to 20_000L))
        assertEquals(listOf(2, 3, 1), ranked.map { it.streamId })
    }

    @Test
    fun categoriesRankByTotalTimeButKeepAdultLast() {
        val categories = listOf(
            Category("a", "A", FeedType.LIVE),
            Category("b", "B", FeedType.LIVE),
            Category(CategoryLayout.adultLiveId, "Adult", FeedType.LIVE),
        )
        val channels = listOf(channel(1, "a"), channel(2, "b"), channel(3, CategoryLayout.adultLiveId))
        val ranked = WatchRanking.categories(categories, channels, mapOf(2 to 50L, 3 to 1_000L))
        assertEquals(listOf("b", "a", CategoryLayout.adultLiveId), ranked.map { it.id })
    }

    @Test
    fun adultNamedCategoriesIgnoreWatchTimeAndStayLast() {
        val categories = listOf(
            Category("a", "A", FeedType.LIVE),
            Category("x1", "XXX Movies", FeedType.LIVE),
            Category("b", "B", FeedType.LIVE),
            Category("x2", "Adult 18+", FeedType.LIVE),
        )
        val channels = listOf(channel(1, "a"), channel(2, "b"), channel(3, "x1"), channel(4, "x2"))
        val ranked = WatchRanking.categories(categories, channels, mapOf(2 to 50L, 3 to 9_000L, 4 to 5_000L))
        assertEquals(listOf("b", "a", "x1", "x2"), ranked.map { it.id })
    }
}
