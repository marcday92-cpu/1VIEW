package com.iptv.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelSourceTest {
    @Test
    fun normalizeNameStripsQualityTagsAndBrackets() {
        assertEquals("bbcone", ChannelSource.normalizeName("BBC One HD"))
        assertEquals("skysports", ChannelSource.normalizeName("Sky Sports [UK] (Backup)"))
        assertEquals("itv", ChannelSource.normalizeName("ITV FHD HEVC"))
    }

    @Test
    fun familyKeyGroupsQualityVariantsOnly() {
        val main = ChannelSource.familyKey("Sky Sports Main")
        assertEquals(main, ChannelSource.familyKey("Sky Sports Main UHD"))
        assertEquals(main, ChannelSource.familyKey("Sky Sports Main HD"))
        assertEquals(main, ChannelSource.familyKey("Sky Sports Main SD"))
        assertEquals(main, ChannelSource.familyKey("Sky Sports Main 4K HDR"))
        assertEquals(main, ChannelSource.familyKey("Sky Sports Main Dolby Vision"))
        assertEquals(main, ChannelSource.familyKey("Sky Sports Main UK"))
        assertEquals(main, ChannelSource.familyKey("Sky Sports Main +1"))
        assertEquals(ChannelSource.familyKey("BBC One"), ChannelSource.familyKey("BBC One FHD HEVC"))
        assertEquals(ChannelSource.familyKey("Discovery"), ChannelSource.familyKey("Discovery HQ"))
        assertEquals(ChannelSource.familyKey("ITV"), ChannelSource.familyKey("ITV +1"))
        assertEquals("bbcone", ChannelSource.familyKey("BBCOneHD"))
        assertEquals(
            ChannelSource.familyKey("Sky Sports Main Event"),
            ChannelSource.familyKey("SkySp Main Event FHD"),
        )
        assertEquals(
            ChannelSource.familyKey("Sky Sports F1"),
            ChannelSource.familyKey("SkySp F1 UHD"),
        )
        assertEquals("bbcone", ChannelSource.familyKey("UK: BBC One HD"))
        assertEquals("skysportf1", ChannelSource.familyKey("(IT) SKY SPORT F1 HD"))
    }

    @Test
    fun familyKeyDoesNotMergeDifferentChannels() {
        assertTrue(ChannelSource.familyKey("Sky Sports F1") != ChannelSource.familyKey("Sky Sports Main"))
        assertTrue(ChannelSource.familyKey("BBC One") != ChannelSource.familyKey("BBC Two"))
        assertTrue(ChannelSource.familyKey("Discovery") != ChannelSource.familyKey("Discovery Science"))
        assertTrue(ChannelSource.familyKey("Sky Sports Cricket") != ChannelSource.familyKey("Sky Sports Football"))
    }

    @Test
    fun lookupKeysAliasSkySportsMainToMainEvent() {
        val keys = ChannelSource.lookupKeys("Sky Sports Main HD")
        assertTrue("skysportsmainevent" in keys)
        assertTrue("skysportsmain" in keys)
        val f1 = ChannelSource.lookupKeys("Sky Sports F1")
        assertTrue("skysportsmainevent" !in f1)
    }

    @Test
    fun hyphenSlugForSkySpMainEvent() {
        assertEquals("sky-sports-main-event", ChannelSource.hyphenSlug("SkySp Main Event FHD"))
        assertEquals("sky-sports-f1", ChannelSource.hyphenSlug("SkySp F1 UHD"))
    }

    @Test
    fun usableLogoUrlRejectsDiscordAndData() {
        assertTrue(ChannelSource.isUsableLogoUrl("https://i.imgur.com/bbc.png"))
        assertTrue(!ChannelSource.isUsableLogoUrl("https://cdn.discordapp.com/attachments/1/flag.png"))
        assertTrue(!ChannelSource.isUsableLogoUrl("data:image/png;base64,abc"))
        assertTrue(!ChannelSource.isUsableLogoUrl(" "))
        assertTrue(!ChannelSource.isUsableLogoUrl("n/a"))
    }

    @Test
    fun genreCategoryIdSlugifies() {
        assertEquals("io_cat_news_weather", ChannelSource.genreCategoryId("News & Weather"))
    }
}
