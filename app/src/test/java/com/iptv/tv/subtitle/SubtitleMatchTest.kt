package com.iptv.tv.subtitle

import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.SubtitleQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleMatchTest {
    @Test
    fun exactEpisodeReleaseRanksAboveWrongEpisode() {
        val query = SubtitleQuery(
            title = "The Last of Us",
            season = 2,
            episode = 4,
            filename = "The.Last.of.Us.S02E04.WEB-DL",
            type = ContentType.EPISODE,
        )
        val exact = SubtitleMatch.score(query, "The.Last.of.Us.S02E04.WEB-DL", "english.srt", "en", false, 2, 4)
        val wrong = SubtitleMatch.score(query, "The.Last.of.Us.S02E03", "english.srt", "en", false, 2, 3)
        assertTrue(exact > wrong)
    }

    @Test
    fun suggestedEpisodeQueryIncludesSeasonAndEpisode() {
        assertEquals("Show S02E04", SubtitleMatch.suggestedQuery("Show", null, 2, 4))
    }
}
