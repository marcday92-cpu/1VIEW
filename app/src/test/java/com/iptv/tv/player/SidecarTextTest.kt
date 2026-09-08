package com.iptv.tv.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidecarTextTest {
    @Test
    fun subripAndVttCountAsSidecar() {
        assertTrue(SidecarText.isSidecarMime("application/x-subrip"))
        assertTrue(SidecarText.isSidecarMime("text/vtt"))
        assertTrue(SidecarText.isSidecarMime("application/ttml+xml"))
        assertFalse(SidecarText.isSidecarMime("application/cea-608"))
        assertFalse(SidecarText.isSidecarMime(null))
    }

    @Test
    fun fileNameLabelMatchesSidecar() {
        assertTrue(SidecarText.isSidecarFormat("text/plain", "bound_vod_12.srt", "bound_vod_12.srt"))
        assertFalse(SidecarText.isSidecarFormat("text/plain", "English", "bound_vod_12.srt"))
        assertTrue(SidecarText.isSidecarFormat(null, "english.srt", null))
    }
}
