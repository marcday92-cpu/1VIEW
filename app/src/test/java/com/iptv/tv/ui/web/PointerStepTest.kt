package com.iptv.tv.ui.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointerStepTest {
    @Test
    fun centreDownMovesCursorWithoutPanning() {
        val step = pointerStep(
            x = 640f, y = 360f, width = 1280f, height = 720f,
            dx = 0f, dy = 1f, distance = 18f, hold = false,
        )
        assertEquals(640f, step.x)
        assertEquals(378f, step.y)
        assertEquals(0f, step.panX)
        assertEquals(0f, step.panY)
    }

    @Test
    fun bottomEdgeDownPansThePage() {
        val step = pointerStep(
            x = 640f, y = 720f, width = 1280f, height = 720f,
            dx = 0f, dy = 1f, distance = 18f, hold = false,
        )
        assertEquals(720f, step.y)
        assertTrue(step.panY >= WebPointerState.TAP_PAN_MIN)
        assertEquals(0f, step.panX)
    }

    @Test
    fun lowerHotZoneDownPansWhileCursorStillMoves() {
        val step = pointerStep(
            x = 640f, y = 600f, width = 1280f, height = 720f,
            dx = 0f, dy = 1f, distance = 18f, hold = false,
        )
        assertTrue(step.y > 600f)
        assertTrue(step.panY > 0f)
    }

    @Test
    fun topEdgeUpPansThePageUp() {
        val step = pointerStep(
            x = 640f, y = 0f, width = 1280f, height = 720f,
            dx = 0f, dy = -1f, distance = 18f, hold = false,
        )
        assertEquals(0f, step.y)
        assertTrue(step.panY <= -WebPointerState.TAP_PAN_MIN)
    }

    @Test
    fun rightEdgePansHorizontally() {
        val step = pointerStep(
            x = 1280f, y = 360f, width = 1280f, height = 720f,
            dx = 1f, dy = 0f, distance = 18f, hold = false,
        )
        assertEquals(1280f, step.x)
        assertTrue(step.panX >= WebPointerState.TAP_PAN_MIN)
    }

    @Test
    fun holdPanIsSmallerThanATapAndCapped() {
        val tap = pointerStep(
            x = 640f, y = 720f, width = 1280f, height = 720f,
            dx = 0f, dy = 1f, distance = 18f, hold = false,
        )
        val hold = pointerStep(
            x = 640f, y = 720f, width = 1280f, height = 720f,
            dx = 0f, dy = 1f, distance = 8f, hold = true,
        )
        assertTrue(hold.panY > 0f)
        assertTrue(hold.panY < tap.panY)
        assertTrue(hold.panY <= WebPointerState.HOLD_PAN_MAX)
    }

    @Test
    fun zeroSizeIsANoOp() {
        val step = pointerStep(
            x = 0f, y = 0f, width = 0f, height = 0f,
            dx = 0f, dy = 1f, distance = 18f, hold = false,
        )
        assertEquals(0f, step.panX)
        assertEquals(0f, step.panY)
    }
}
