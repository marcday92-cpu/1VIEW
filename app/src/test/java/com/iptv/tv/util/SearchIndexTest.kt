package com.iptv.tv.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchIndexTest {
    private data class Item(val name: String, val group: String? = null)

    private val index = SearchIndex(
        listOf(
            Item("BBC One HD", "UK"),
            Item("BBC Two", "UK"),
            Item("Sky Sports Main Event", "Sports"),
            Item("Sky Sports F1", "Sports"),
            Item("ITV1", "UK"),
            Item("Первый канал", "Russia"),
            Item("Boomerang", "Kids"),
        ),
        name = { it.name },
        extra = { it.group },
    )

    @Test
    fun prefixMatchesComeFirst() {
        val hits = index.search("bbc").map { it.name }
        assertEquals(setOf("BBC One HD", "BBC Two"), hits.take(2).toSet())
        assertEquals("BBC Two", index.search("bbc two").first().name)
    }

    @Test
    fun ignoresPunctuationAndCase() {
        assertEquals("Sky Sports F1", index.search("sky-sports f1").first().name)
        assertEquals("Sky Sports Main Event", index.search("SKY SPORTS MAIN").first().name)
    }

    @Test
    fun singleCharacterOnlyMatchesTitleStart() {
        val hits = index.search("b").map { it.name }
        assertTrue("BBC One HD" in hits && "Boomerang" in hits)
        assertTrue("ITV1" !in hits)
    }

    @Test
    fun matchesTheExtraField() {
        val hits = index.search("sports").map { it.name }
        assertTrue("Sky Sports Main Event" in hits && "Sky Sports F1" in hits)
    }

    @Test
    fun nonLatinQueriesWork() {
        assertEquals("Первый канал", index.search("первый").first().name)
    }

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun limitIsRespected() {
        assertEquals(1, index.search("sky", limit = 1).size)
    }

    @Test
    fun programmeTitlesFindTheChannelButNameHitsRankFirst() {
        val index = SearchIndex(
            listOf(
                Item("Sky Sports Main Event", "Sports"),
                Item("US Open TV", "Sports"),
                Item("BBC One HD", "UK"),
            ),
            name = { it.name },
            extra = { it.group },
            programmes = { if (it.name == "Sky Sports Main Event") listOf("US Open 2026", "Cricket") else emptyList() },
        )
        val hits = index.searchDetailed("us open")
        // A strong programme hit leads in the on-air block; the channel-name hit follows.
        assertEquals(listOf("Sky Sports Main Event", "US Open TV"), hits.map { it.item.name })
        assertEquals("US Open 2026", hits[0].programme)
        assertTrue(hits[0].onAir)
        assertEquals(null, hits[1].programme)
        assertTrue(!hits[1].onAir)
        assertEquals("Sky Sports Main Event", index.search("cricket").single().name)
    }

    @Test
    fun onAirBlockSurvivesManyChannelNameMatches() {
        val usChannels = (1..100).map { Item("US Channel $it", "US") }
        val index = SearchIndex(
            usChannels + Item("Sky Sports Main Event", "Sports"),
            name = { it.name },
            programmes = { if (it.name.startsWith("Sky")) listOf("US Open 2026") else emptyList() },
        )
        val hits = index.searchDetailed("us", limit = 80)
        assertEquals("Sky Sports Main Event", hits.first().item.name)
        assertEquals(80, hits.size)
    }
}
