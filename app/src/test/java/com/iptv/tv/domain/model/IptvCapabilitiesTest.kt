package com.iptv.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvCapabilitiesTest {
    private val xc = Channel(1, "Sky", null, "1", null, source = ChannelSource.XC)
    private val free = Channel(2, "BBC", null, "gb", null, source = ChannelSource.IPTV_ORG)
    private val archive = Channel(3, "Old film", null, "ao", null, source = ChannelSource.ARCHIVE)

    @Test
    fun iptvChannelWhenLoggedInCanRecordAndRemind() {
        assertTrue(IptvCapabilities.canRecord(xc, hasIptvLogin = true))
        assertTrue(IptvCapabilities.canRemind(xc, hasIptvLogin = true))
        assertTrue(IptvCapabilities.canCatchup(xc, hasIptvLogin = true))
    }

    @Test
    fun loggedOutNeverExposesRecordOrRemind() {
        assertFalse(IptvCapabilities.canRecord(xc, hasIptvLogin = false))
        assertFalse(IptvCapabilities.canRemind(free, hasIptvLogin = false))
        assertFalse(IptvCapabilities.canCatchup(xc, hasIptvLogin = false))
        assertFalse(IptvCapabilities.showRecordingUi(false))
    }

    @Test
    fun freeAndArchiveStayWatchOnlyEvenWhenLoggedIn() {
        assertFalse(IptvCapabilities.canRecord(free, hasIptvLogin = true))
        assertFalse(IptvCapabilities.canRemind(archive, hasIptvLogin = true))
        assertFalse(IptvCapabilities.canCatchup(free, hasIptvLogin = true))
        assertFalse(IptvCapabilities.showCatchupUi(false))
        assertTrue(IptvCapabilities.showCatchupUi(true))
        assertFalse(IptvCapabilities.canRecord(VodItem(1, "Film", null, "ao", null, null, null, source = ChannelSource.ARCHIVE), true))
    }
}
