package com.iptv.tv.ui.catalog

import com.iptv.tv.domain.model.VodSourceFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogEmptyMessageTest {
    @Test
    fun freeFilterStopsSayingLoadingOnceReady() {
        assertEquals(
            "Loading free films…",
            vodEmptyMessage(false, VodSourceFilter.FREE, loggedIn = false, movies = true, loadingFree = true),
        )
        assertEquals(
            "Could not load free films.",
            vodEmptyMessage(
                false,
                VodSourceFilter.FREE,
                loggedIn = false,
                movies = true,
                loadingFree = false,
                freeError = "Could not load free films.",
            ),
        )
        assertEquals(
            "No free series from live channels or public archives.",
            vodEmptyMessage(false, VodSourceFilter.FREE, loggedIn = true, movies = false),
        )
    }

    @Test
    fun providerFailureReplacesEndlessLoading() {
        assertEquals(
            "Loading your IPTV films…",
            vodEmptyMessage(false, VodSourceFilter.IPTV, loggedIn = true, movies = true),
        )
        assertEquals(
            "Could not reach the IPTV server.",
            vodEmptyMessage(
                false,
                VodSourceFilter.IPTV,
                loggedIn = true,
                movies = true,
                providerFailed = true,
                providerError = "Could not reach the IPTV server.",
            ),
        )
        assertEquals(
            "Could not load your IPTV series.",
            vodEmptyMessage(false, VodSourceFilter.IPTV, loggedIn = true, movies = false, providerFailed = true),
        )
    }

    @Test
    fun itemsSuppressEmptyMessage() {
        assertNull(vodEmptyMessage(true, VodSourceFilter.FREE, loggedIn = false, movies = true, loadingFree = true))
    }
}
