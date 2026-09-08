package com.iptv.tv.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XtreamTimeTest {
    @Test
    fun xmlTvOffsetIsHonouredAndNotTheDeviceZone() {
        val utc = XtreamTime.parseXmlTv("20260908140000 +0000")
        val bst = XtreamTime.parseXmlTv("20260908140000 +0100")
        // Same wall clock, +0100 is one hour earlier as an instant.
        assertEquals(3_600_000L, utc - bst)
        // Compact form without a space (some panels omit it).
        assertEquals(utc, XtreamTime.parseXmlTv("20260908140000+0000"))
    }

    @Test
    fun timezoneLessXmlTvIsUtcNeverDeviceLocal() {
        val utc = XtreamTime.parseXmlTv("20260908140000 +0000")
        val naive = XtreamTime.parseXmlTv("20260908140000", "UTC")
        assertEquals(utc, naive)
    }

    @Test
    fun timezoneLessXmlTvCanUsePanelZone() {
        val london = XtreamTime.parseXmlTv("20260908140000", "Europe/London")
        val utc = XtreamTime.parseXmlTv("20260908140000 +0000")
        // 14:00 BST is 13:00 UTC in September.
        assertEquals(3_600_000L, utc - london)
    }

    @Test
    fun unixSecondsBecomeMillis() {
        assertEquals(1_725_541_200_000L, XtreamTime.unixMs(kotlinx.serialization.json.JsonPrimitive("1725541200")))
        assertEquals(1_725_541_200_000L, XtreamTime.unixMs(kotlinx.serialization.json.JsonPrimitive(1725541200)))
    }

    @Test
    fun timeshiftStartIsPanelWallClock() {
        val start = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, java.util.Calendar.SEPTEMBER, 8, 12, 0, 0)
        }.timeInMillis
        assertEquals("2026-09-08:12-00-00", XtreamTime.timeshiftStart(start, "UTC"))
        assertEquals("2026-09-08:13-00-00", XtreamTime.timeshiftStart(start, "Europe/London"))
        assertEquals("2026-09-08:14-00-00", XtreamTime.timeshiftStart(start, "Europe/Paris"))
    }

    @Test
    fun listingPrefersUnixTimestampOverNaiveString() {
        val dto = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<EpgListingDto>(
                """{"start":"2026-09-08 14:00:00","start_timestamp":"1725796800","has_archive":1}""",
            )
        assertEquals(1_725_796_800_000L, dto.startMs())
        assertEquals(1, dto.hasArchiveInt())
        assertTrue(dto.startMs() > 0L)
    }
}
