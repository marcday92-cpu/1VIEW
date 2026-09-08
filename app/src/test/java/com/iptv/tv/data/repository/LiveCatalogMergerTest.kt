package com.iptv.tv.data.repository

import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ChannelSource
import com.iptv.tv.domain.model.FeedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveCatalogMergerTest {
    @Test
    fun sharesProviderLogoAcrossQualityVariants() {
        val merged = LiveCatalogMerger.merge(
            xcCategories = listOf(cat("uk")),
            xcChannels = listOf(
                ch(1, "Sky Sports Main", logo = "https://provider/main.png"),
                ch(2, "Sky Sports Main UHD"),
                ch(3, "Sky Sports Main HD"),
                ch(4, "Sky Sports F1"),
            ),
            orgChannels = emptyList(),
            orgCategories = emptyList(),
        )
        val byName = merged.channels.associate { it.name to it.logoUrl }
        assertEquals("https://provider/main.png", byName["Sky Sports Main"])
        assertEquals("https://provider/main.png", byName["Sky Sports Main UHD"])
        assertEquals("https://provider/main.png", byName["Sky Sports Main HD"])
        assertNull(byName["Sky Sports F1"])
    }

    @Test
    fun fillsBlankFromIptvOrgThenSharesFamily() {
        val index = ChannelLogoIndex(
            byId = mapOf("bbcone.uk" to "https://i.imgur.com/bbc.png"),
            byName = mapOf("bbcone" to "https://i.imgur.com/bbc.png"),
        )
        val merged = LiveCatalogMerger.merge(
            xcCategories = listOf(cat("uk")),
            xcChannels = listOf(
                ch(10, "BBC One HD", epg = "BBCOne.uk"),
                ch(11, "BBC One UHD"),
                ch(12, "BBC Two HD"),
            ),
            orgChannels = emptyList(),
            orgCategories = emptyList(),
            logoIndex = index,
        )
        val byName = merged.channels.associate { it.name to it.logoUrl }
        assertEquals("https://i.imgur.com/bbc.png", byName["BBC One HD"])
        assertEquals("https://i.imgur.com/bbc.png", byName["BBC One UHD"])
        assertNull(byName["BBC Two HD"])
    }

    @Test
    fun doesNotMergeDistinctBrandSuffixes() {
        val merged = LiveCatalogMerger.merge(
            xcCategories = listOf(cat("us")),
            xcChannels = listOf(
                ch(20, "Discovery", logo = "https://provider/disc.png"),
                ch(21, "Discovery Science"),
                ch(22, "Discovery HQ"),
            ),
            orgChannels = emptyList(),
            orgCategories = emptyList(),
        )
        val byName = merged.channels.associate { it.name to it.logoUrl }
        assertEquals("https://provider/disc.png", byName["Discovery"])
        assertEquals("https://provider/disc.png", byName["Discovery HQ"])
        assertNull(byName["Discovery Science"])
    }

    @Test
    fun prefersProviderLogoOverOrgWhenSharing() {
        val index = ChannelLogoIndex(byName = mapOf("itv" to "https://org/itv.png"))
        val merged = LiveCatalogMerger.merge(
            xcCategories = listOf(cat("uk")),
            xcChannels = listOf(
                ch(30, "ITV", logo = "https://provider/itv.png"),
                ch(31, "ITV +1"),
            ),
            orgChannels = emptyList(),
            orgCategories = emptyList(),
            logoIndex = index,
        )
        assertTrue(merged.channels.all { it.logoUrl == "https://provider/itv.png" })
    }

    @Test
    fun fillsSkySpMainEventFromIptvOrgName() {
        val index = ChannelLogoIndex(
            byName = mapOf("skysportsmainevent" to "https://org/main-event.png"),
        )
        val merged = LiveCatalogMerger.merge(
            xcCategories = listOf(cat("uk")),
            xcChannels = listOf(
                ch(40, "SkySp Main Event FHD"),
                ch(41, "SkySp Main Event UHD"),
                ch(42, "SkySp F1 HD"),
            ),
            orgChannels = emptyList(),
            orgCategories = emptyList(),
            logoIndex = index,
        )
        val byName = merged.channels.associate { it.name to it.logoUrl }
        assertEquals("https://org/main-event.png", byName["SkySp Main Event FHD"])
        assertEquals("https://org/main-event.png", byName["SkySp Main Event UHD"])
        assertNull(byName["SkySp F1 HD"])
    }

    @Test
    fun skySportsMainAliasUsesMainEventArtwork() {
        val index = ChannelLogoIndex(
            byName = mapOf("skysportsmainevent" to "https://org/main-event.png"),
        )
        val merged = LiveCatalogMerger.merge(
            xcCategories = listOf(cat("uk")),
            xcChannels = listOf(ch(50, "Sky Sports Main HD")),
            orgChannels = emptyList(),
            orgCategories = emptyList(),
            logoIndex = index,
        )
        assertEquals("https://org/main-event.png", merged.channels.single().logoUrl)
    }

    @Test
    fun replacesUnusableDiscordLogoFromIndex() {
        val index = ChannelLogoIndex(
            byName = mapOf("skysportsf1" to "https://org/f1.png"),
        )
        val merged = LiveCatalogMerger.merge(
            xcCategories = listOf(cat("uk")),
            xcChannels = listOf(
                ch(
                    60,
                    "SkySp F1 HD",
                    logo = "https://cdn.discordapp.com/attachments/1/flag.png",
                ),
            ),
            orgChannels = emptyList(),
            orgCategories = emptyList(),
            logoIndex = index,
        )
        assertEquals("https://org/f1.png", merged.channels.single().logoUrl)
    }

    @Test
    fun epgIdMatchesIptvOrgEvenWhenNameIsSkySp() {
        val index = ChannelLogoIndex(
            byId = mapOf("skysportsmainevent.uk" to "https://org/main-event.png"),
        )
        val merged = LiveCatalogMerger.merge(
            xcCategories = listOf(cat("uk")),
            xcChannels = listOf(ch(70, "SkySp Main Event FHD", epg = "skysportsmainevent.uk")),
            orgChannels = emptyList(),
            orgCategories = emptyList(),
            logoIndex = index,
        )
        assertEquals("https://org/main-event.png", merged.channels.single().logoUrl)
    }

    private fun cat(id: String) = Category(id, id, FeedType.LIVE, source = ChannelSource.XC)

    private fun ch(
        id: Int,
        name: String,
        logo: String? = null,
        epg: String? = null,
    ) = Channel(
        streamId = id,
        name = name,
        logoUrl = logo,
        categoryId = "uk",
        epgChannelId = epg,
        source = ChannelSource.XC,
    )
}
