package com.iptv.tv.data.api

import com.iptv.tv.domain.model.ServerCredentials
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XtreamUrlBuilderTest {
    private val credentials = ServerCredentials("https://example.com", "user name", "p/a&ss")

    @Test
    fun apiParametersAreEncoded() {
        val url = XtreamUrlBuilder.liveStreamsUrl(credentials, "Sports & News")
        assertTrue(url.contains("username=user%20name"))
        assertTrue(url.contains("category_id=Sports%20%26%20News"))
        assertFalse(url.contains("p/a&ss"))
    }

    @Test
    fun pathCredentialsCannotBreakStreamPath() {
        val url = XtreamUrlBuilder.liveStreamUrl(credentials, 42)
        assertTrue(url.contains("/user%20name/p%2Fa&ss/42.ts"))
    }

    @Test
    fun timeshiftUrlUsesUtcStartAndDuration() {
        val start = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, java.util.Calendar.SEPTEMBER, 8, 15, 30, 0)
        }.timeInMillis
        val url = XtreamUrlBuilder.timeshiftUrl(credentials, 42, start, 90)
        assertTrue(url.contains("/timeshift/"))
        assertTrue(url.contains("/90/"))
        assertTrue(url.contains("2026-09-08:15-30-00"))
        assertTrue(url.contains("42.ts"))
    }

    @Test
    fun timeshiftUrlUsesServerTimeZoneWallClockNotUtc() {
        val start = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, java.util.Calendar.SEPTEMBER, 8, 15, 30, 0)
        }.timeInMillis
        val url = XtreamUrlBuilder.timeshiftUrl(credentials, 42, start, 90, "Europe/London")
        // 15:30 UTC in September is 16:30 BST. Sending 15:30 would play the previous hour on EDTV.
        assertTrue(url.contains("2026-09-08:16-30-00"))
        assertFalse(url.contains("2026-09-08:15-30-00"))
    }

    @Test
    fun simpleDataTableUrlUsesCatchupAction() {
        val url = XtreamUrlBuilder.simpleDataTableUrl(credentials, 42)
        assertTrue(url.contains("action=get_simple_data_table"))
        assertTrue(url.contains("stream_id=42"))
    }
}
