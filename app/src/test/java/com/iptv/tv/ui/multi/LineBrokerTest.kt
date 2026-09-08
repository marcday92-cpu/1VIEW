package com.iptv.tv.ui.multi

import com.iptv.tv.data.credentials.ExtraLine
import com.iptv.tv.data.repository.IptvRepository.ConnectionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineBrokerTest {
    private val bob = ExtraLine("1", "Bob", "bob", "pw")
    private val sue = ExtraLine("2", "Sue", "sue", "pw")
    private val tom = ExtraLine("3", "Tom", "tom", "pw")

    @Test
    fun picksTheFirstIdleLineNobodyElseHolds() {
        val chosen = LineBroker.choose(
            candidates = listOf(bob, sue, tom),
            free = { it != bob },          // Bob is watching
            taken = setOf(sue.id),          // Sue's line already carries another screen
            busyUntil = emptyMap(),
            now = 1_000L,
        )
        assertEquals(tom, chosen)
    }

    @Test
    fun leavesARecentlyReclaimedLineAloneUntilTheGracePeriodEnds() {
        val busy = mapOf(bob.id to 5_000L)
        assertNull(LineBroker.choose(listOf(bob), { true }, emptySet(), busy, now = 4_999L))
        assertEquals(bob, LineBroker.choose(listOf(bob), { true }, emptySet(), busy, now = 5_000L))
    }

    @Test
    fun nothingFreeMeansNoLease() {
        assertNull(LineBroker.choose(listOf(bob, sue), { false }, emptySet(), emptyMap(), now = 0L))
    }

    @Test
    fun oneLookAtACutLineIsFreeBusyTakenOrUnreachable() {
        // Nobody on it: just a dropped connection, reconnect.
        assertEquals(LineBroker.Look.FREE, LineBroker.verdict(ConnectionSnapshot(active = 0, max = 1)))
        // Panel unreachable: neither reconnect blind nor blame the owner.
        assertEquals(LineBroker.Look.UNREACHABLE, LineBroker.verdict(null))
        // Someone streaming: may still be our own dropped stream, so only conclusive twice in a row.
        assertEquals(LineBroker.Look.BUSY, LineBroker.verdict(ConnectionSnapshot(active = 1, max = 1)))
        // Login rejected or line expired: hand it back for good.
        assertEquals(LineBroker.Look.TAKEN, LineBroker.verdict(ConnectionSnapshot(active = 0, max = 1, valid = false)))
        assertEquals(LineBroker.Look.TAKEN, LineBroker.verdict(ConnectionSnapshot(active = 0, max = 1, status = "Expired")))
    }
}
