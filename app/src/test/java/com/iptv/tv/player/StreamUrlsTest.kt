package com.iptv.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamUrlsTest {
    @Test
    fun rewritesOnlyTheFinalExtension() {
        assertEquals(
            "http://iptv.tsnet.io/live/u/p/42.m3u8",
            StreamUrls.hlsVariant("http://iptv.tsnet.io/live/u/p/42.ts"),
        )
    }

    @Test
    fun keepsTheQueryString() {
        assertEquals("http://h/1.m3u8?token=a.ts", StreamUrls.hlsVariant("http://h/1.ts?token=a.ts"))
    }

    @Test
    fun nonTsUrlsHaveNoVariant() {
        assertNull(StreamUrls.hlsVariant("http://h/stream.m3u8"))
        assertNull(StreamUrls.hlsVariant("http://h/movie.mp4"))
    }
}
