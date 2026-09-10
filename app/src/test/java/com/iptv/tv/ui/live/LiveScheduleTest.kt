package com.iptv.tv.ui.live

import com.iptv.tv.domain.model.Programme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveScheduleTest {
    private val nowMs = 1_000_000L

    @Test
    fun splitsCurrentFromUpcoming() {
        val now = prog("now", nowMs - 1_000, nowMs + 1_000)
        val next = prog("next", nowMs + 1_000, nowMs + 2_000)
        val later = prog("later", nowMs + 2_000, nowMs + 3_000)
        val (current, upcoming) = LiveSchedule.splitNowAndUpcoming(listOf(later, now, next), nowMs)
        assertEquals("now", current?.title)
        assertEquals(listOf("next", "later"), upcoming.map { it.title })
    }

    @Test
    fun dropsAlreadyFinishedAndCapsUpcoming() {
        val finished = prog("old", nowMs - 3_000, nowMs - 1_000)
        val current = prog("now", nowMs - 500, nowMs + 500)
        val rest = (1..60).map { i ->
            prog("p$i", nowMs + i * 1_000L, nowMs + (i + 1) * 1_000L)
        }
        val (now, upcoming) = LiveSchedule.splitNowAndUpcoming(listOf(finished, current) + rest, nowMs)
        assertEquals("now", now?.title)
        assertEquals(LiveSchedule.UPCOMING_LIMIT, upcoming.size)
        assertEquals("p1", upcoming.first().title)
        assertEquals("p50", upcoming.last().title)
    }

    @Test
    fun unionsShortEpgOntoThinLocalWithoutDroppingNow() {
        val local = listOf(
            prog("now", nowMs - 1_000, nowMs + 1_000),
            prog("next", nowMs + 1_000, nowMs + 2_000),
        )
        val short = (0..8).map { i ->
            prog("s$i", nowMs + i * 1_000L, nowMs + (i + 1) * 1_000L)
        }
        val merged = LiveSchedule.merge(local, short, nowMs)
        assertEquals("now", merged.first().title)
        assertTrue(merged.any { it.title == "next" })
        assertEquals(10, merged.size)
        assertEquals("s8", merged.last().title)
    }

    @Test
    fun keepsLocalWhenItHasAsManyOrMore() {
        val local = (0..7).map { i ->
            prog("l$i", nowMs + i * 1_000L, nowMs + (i + 1) * 1_000L)
        }
        val short = listOf(prog("s", nowMs, nowMs + 1_000))
        val merged = LiveSchedule.merge(local, short, nowMs)
        assertEquals(8, merged.size)
        assertTrue(merged.all { it.title.startsWith("l") })
    }

    @Test
    fun noCurrentLeavesEverythingUpcoming() {
        val later = prog("later", nowMs + 5_000, nowMs + 6_000)
        val (current, upcoming) = LiveSchedule.splitNowAndUpcoming(listOf(later), nowMs)
        assertNull(current)
        assertEquals(listOf("later"), upcoming.map { it.title })
    }

    @Test
    fun listingsPutsNowFirstThenUpcoming() {
        val now = prog("now", 0, 1)
        val next = prog("next", 1, 2)
        assertEquals(listOf("now", "next"), LiveSchedule.listings(now, listOf(next)).map { it.title })
        assertEquals(listOf("next"), LiveSchedule.listings(null, listOf(next)).map { it.title })
    }

    @Test
    fun lazyIndexCountsNowThenDayLabelsNotAHeader() {
        val now = prog("now", 0, 1)
        val progs = listOf(
            prog("a", 1, 2),
            prog("b", 3, 4),
            prog("c", 5, 6),
        )
        assertEquals(0, LiveSchedule.lazyIndexOf(now, progs, "now_0", "today") { "today" })
        assertEquals(1, LiveSchedule.lazyIndexOf(now, progs, "a_1", "today") { "today" })
        assertEquals(0, LiveSchedule.lazyIndexOf(null, progs, "a_1", "today") { "today" })
        assertEquals(2, LiveSchedule.lazyIndexOf(null, progs, "c_5", "today") { "today" })
        val splitDay = LiveSchedule.lazyIndexOf(null, progs, "b_3", "today") { ms ->
            if (ms >= 3) "tomorrow" else "today"
        }
        assertEquals(2, splitDay)
        assertNull(LiveSchedule.lazyIndexOf(now, progs, "missing", "today") { "today" })
    }

    @Test
    fun needsShortEpgWhenLocalUpcomingIsThin() {
        assertTrue(LiveSchedule.needsShortEpg(8))
        assertTrue(!LiveSchedule.needsShortEpg(20))
    }

    private fun prog(title: String, start: Long, end: Long) = Programme(
        id = "${title}_$start",
        channelId = "ch",
        channelStreamId = 1,
        title = title,
        description = "$title desc",
        startTimeMs = start,
        endTimeMs = end,
    )
}
