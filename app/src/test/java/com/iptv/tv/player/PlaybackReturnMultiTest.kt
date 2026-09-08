package com.iptv.tv.player

import com.iptv.tv.domain.model.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackReturnMultiTest {
    @Test
    fun liveOpenedFromMultiReturnsToTheGrid() {
        assertEquals("multi", PlaybackReturn.route(ContentType.LIVE, PlaybackOpenedFrom.MULTI))
    }

    @Test
    fun liveOpenedElsewhereReturnsToLive() {
        assertEquals("live", PlaybackReturn.route(ContentType.LIVE, PlaybackOpenedFrom.HOME))
        assertEquals("live", PlaybackReturn.route(ContentType.LIVE, null))
    }
}
