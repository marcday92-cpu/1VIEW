package com.iptv.tv.data.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelCardLayoutTest {
    @Test
    fun wideWordmarkIsContainedNotCovered() {
        assertFalse(ChannelCardLayout.shouldCover(800, 100, opaqueRatio = 1f))
        val dest = ChannelCardLayout.destRect(800, 100, opaqueRatio = 1f)
        assertTrue(dest.width <= ChannelCardLayout.WIDTH)
        assertTrue(dest.height < ChannelCardLayout.HEIGHT)
        assertTrue(dest.left >= 0)
        assertTrue(dest.top > 0)
    }

    @Test
    fun landscapePhotoCoversTheCard() {
        assertTrue(ChannelCardLayout.shouldCover(1920, 1080, opaqueRatio = 1f))
        val dest = ChannelCardLayout.destRect(1920, 1080, opaqueRatio = 1f)
        assertTrue(dest.width >= ChannelCardLayout.WIDTH)
        assertTrue(dest.height >= ChannelCardLayout.HEIGHT)
    }

    @Test
    fun transparentLogoIsContained() {
        assertFalse(ChannelCardLayout.shouldCover(400, 400, opaqueRatio = 0.4f))
        val dest = ChannelCardLayout.destRect(400, 400, opaqueRatio = 0.4f)
        assertTrue(dest.width < ChannelCardLayout.WIDTH)
        assertTrue(dest.height < ChannelCardLayout.HEIGHT)
        assertTrue(kotlin.math.abs(dest.left - (ChannelCardLayout.WIDTH - dest.right)) <= 2)
    }

    @Test
    fun squareOpaqueLogoIsContained() {
        assertFalse(ChannelCardLayout.shouldCover(512, 512, opaqueRatio = 1f))
    }
}
