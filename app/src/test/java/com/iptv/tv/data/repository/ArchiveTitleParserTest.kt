package com.iptv.tv.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchiveTitleParserTest {
    @Test
    fun parsesSxxExxWithEpisodeTitle() {
        val parsed = ArchiveTitleParser.parse("I Love Lucy S01E05 The Diet")
        assertNotNull(parsed)
        assertEquals("I Love Lucy", parsed.showName)
        assertEquals(1, parsed.season)
        assertEquals(5, parsed.episode)
        assertEquals("The Diet", parsed.episodeTitle)
    }

    @Test
    fun parsesSeasonEpisodeWords() {
        val parsed = ArchiveTitleParser.parse("The Twilight Zone Season 1 Episode 3 - Mr Denton on Doomsday")
        assertNotNull(parsed)
        assertEquals("The Twilight Zone", parsed.showName)
        assertEquals(1, parsed.season)
        assertEquals(3, parsed.episode)
        assertTrue(parsed.episodeTitle.contains("Denton"))
    }

    @Test
    fun parsesNxnn() {
        val parsed = ArchiveTitleParser.parse("Gunsmoke 2x14 The Round Up")
        assertNotNull(parsed)
        assertEquals("Gunsmoke", parsed.showName)
        assertEquals(2, parsed.season)
        assertEquals(14, parsed.episode)
    }

    @Test
    fun groupsByCreatorWhenTitleHasNoShowName() {
        val parsed = ArchiveTitleParser.parse("The Diet", creator = "I Love Lucy")
        assertNotNull(parsed)
        assertEquals("I Love Lucy", parsed.showName)
        assertEquals("The Diet", parsed.episodeTitle)
    }

    @Test
    fun parsesEpNumberAfterShowName() {
        val parsed = ArchiveTitleParser.parse("Beverly Hillbillies Ep01 The Clampetts Strike Oil")
        assertNotNull(parsed)
        assertEquals("Beverly Hillbillies", parsed.showName)
        assertEquals(1, parsed.season)
        assertEquals(1, parsed.episode)
        assertTrue(parsed.episodeTitle.contains("Clampetts"))
    }

    @Test
    fun showKeyIsStableAcrossCasing() {
        assertEquals(
            ArchiveTitleParser.showKey("The Lucy Show"),
            ArchiveTitleParser.showKey("the lucy show"),
        )
    }
}
