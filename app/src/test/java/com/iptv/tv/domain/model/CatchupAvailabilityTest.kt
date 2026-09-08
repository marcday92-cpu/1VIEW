package com.iptv.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatchupAvailabilityTest {
    private val channel = Channel(
        streamId = 1,
        name = "Test",
        logoUrl = null,
        categoryId = "1",
        epgChannelId = "t",
        tvArchive = 1,
        tvArchiveDuration = 7,
        source = ChannelSource.XC,
    )

    private val free = channel.copy(streamId = 2, source = ChannelSource.IPTV_ORG)

    @Test
    fun pastProgrammeWithinWindowIsCatchup() {
        val now = 1_000_000L
        val prog = programme(start = now - 3_600_000, end = now - 1_000)
        assertTrue(CatchupAvailability.watchCatchup(channel, prog, hasIptvLogin = true, nowMs = now))
    }

    @Test
    fun liveProgrammeIsNotCatchup() {
        val now = 1_000_000L
        val prog = programme(start = now - 1_000, end = now + 3_600_000)
        assertFalse(CatchupAvailability.watchCatchup(channel, prog, hasIptvLogin = true, nowMs = now))
        assertFalse(
            CatchupAvailability.restartProgramme(
                channel,
                prog,
                timeshiftWhileLive = false,
                hasIptvLogin = true,
                nowMs = now,
            ),
        )
    }

    @Test
    fun restartWhileLiveNeedsProvenTimeshift() {
        val now = 1_000_000L
        val prog = programme(start = now - 1_000, end = now + 3_600_000)
        assertFalse(
            CatchupAvailability.restartProgramme(
                channel,
                prog,
                timeshiftWhileLive = false,
                hasIptvLogin = true,
                nowMs = now,
            ),
        )
        assertTrue(
            CatchupAvailability.restartProgramme(
                channel,
                prog,
                timeshiftWhileLive = true,
                hasIptvLogin = true,
                nowMs = now,
            ),
        )
    }

    @Test
    fun programmeOutsideArchiveWindowIsNotCatchup() {
        val now = 8 * 24L * 60 * 60 * 1000
        val prog = programme(start = 0L, end = 3_600_000L)
        assertFalse(CatchupAvailability.watchCatchup(channel, prog, hasIptvLogin = true, nowMs = now))
    }

    @Test
    fun freeChannelsAndLoggedOutNeverShowCatchup() {
        val now = 1_000_000L
        val prog = programme(start = now - 3_600_000, end = now - 1_000)
        assertFalse(CatchupAvailability.watchCatchup(free, prog, hasIptvLogin = true, nowMs = now))
        assertFalse(CatchupAvailability.watchCatchup(channel, prog, hasIptvLogin = false, nowMs = now))
    }

    @Test
    fun archiveWindowUsesDurationNotAFabricatedWeek() {
        val threeDays = channel.copy(tvArchive = 1, tvArchiveDuration = 3)
        assertEquals(3, CatchupAvailability.archiveWindowDays(threeDays))
        val now = 4 * 24L * 60 * 60 * 1000
        val inside = programme(start = now - 2 * 24L * 60 * 60 * 1000, end = now - 2 * 24L * 60 * 60 * 1000 + 3_600_000)
        val outside = programme(start = 0L, end = 3_600_000L)
        assertTrue(CatchupAvailability.watchCatchup(threeDays, inside, hasIptvLogin = true, nowMs = now))
        assertFalse(CatchupAvailability.watchCatchup(threeDays, outside, hasIptvLogin = true, nowMs = now))
    }

    @Test
    fun catchupProgrammesAreFinishedOnlyAndPreferHasArchive() {
        val now = 8 * 24L * 60 * 60 * 1000
        val finished = programme(start = now - 3_600_000, end = now - 1_000).copy(id = "a", hasArchive = true)
        val live = programme(start = now - 1_000, end = now + 3_600_000).copy(id = "b", hasArchive = true)
        val unmarked = programme(start = now - 7_200_000, end = now - 3_600_000).copy(id = "c", hasArchive = false)
        val kept = CatchupAvailability.catchupProgrammes(
            channel,
            listOf(finished, live, unmarked),
            hasIptvLogin = true,
            nowMs = now,
        )
        assertEquals(listOf("a"), kept.map { it.id })
    }

    private fun programme(start: Long, end: Long) = Programme(
        id = "p",
        channelId = "t",
        channelStreamId = 1,
        title = "Show",
        description = null,
        startTimeMs = start,
        endTimeMs = end,
    )
}
