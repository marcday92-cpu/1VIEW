package com.iptv.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeekScrubTest {
    @Test
    fun tapStepIsAtLeastTenSeconds() {
        assertEquals(10_000L, SeekScrub.stepMs(90_000, held = false))
        assertTrue(SeekScrub.stepMs(3_600_000, held = false) >= 10_000L)
    }

    @Test
    fun holdStepIsLargerThanTap() {
        val duration = 3_600_000L
        assertTrue(SeekScrub.stepMs(duration, held = true) > SeekScrub.stepMs(duration, held = false))
    }

    @Test
    fun dragFractionMapsToPosition() {
        assertEquals(0L, SeekScrub.positionFromFraction(0f, 100_000))
        assertEquals(50_000L, SeekScrub.positionFromFraction(0.5f, 100_000))
        assertEquals(100_000L, SeekScrub.positionFromFraction(1f, 100_000))
        assertEquals(0L, SeekScrub.positionFromFraction(0.5f, 0))
    }

    @Test
    fun applyStepClampsToDuration() {
        assertEquals(0L, SeekScrub.applyStep(5_000, 100_000, -10_000))
        assertEquals(100_000L, SeekScrub.applyStep(95_000, 100_000, 10_000))
        assertEquals(40_000L, SeekScrub.applyStep(50_000, 100_000, -10_000))
    }
}
