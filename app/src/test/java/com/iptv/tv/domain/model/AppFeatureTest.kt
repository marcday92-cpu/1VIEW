package com.iptv.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppFeatureTest {
    @Test
    fun encodeAndParseRoundTrip() {
        val disabled = setOf(AppFeature.WEB, AppFeature.SERIES)
        val encoded = AppFeature.encodeDisabled(disabled)
        assertEquals("SERIES,WEB", encoded)
        assertEquals(disabled, AppFeature.parseDisabled(encoded))
    }

    @Test
    fun parseIgnoresUnknownAndBlankTokens() {
        assertEquals(setOf(AppFeature.MULTI), AppFeature.parseDisabled(" multi , ,BOGUS"))
        assertEquals(emptySet(), AppFeature.parseDisabled(null))
        assertEquals(emptySet(), AppFeature.parseDisabled(""))
    }

    @Test
    fun everythingOnByDefault() {
        assertEquals(AppFeature.entries.toSet(), AppFeature.effectiveEnabled(emptySet()))
    }

    @Test
    fun liveOffAlsoHidesCatchupGuideMultiAndRecordings() {
        val enabled = AppFeature.effectiveEnabled(setOf(AppFeature.LIVE))
        assertFalse(AppFeature.LIVE in enabled)
        assertFalse(AppFeature.CATCHUP in enabled)
        assertFalse(AppFeature.GUIDE in enabled)
        assertFalse(AppFeature.MULTI in enabled)
        assertFalse(AppFeature.RECORDINGS in enabled)
        assertTrue(AppFeature.MOVIES in enabled)
        assertTrue(AppFeature.WEB in enabled)
        assertTrue(AppFeature.SEARCH in enabled)
    }

    @Test
    fun guideOffAloneKeepsLive() {
        val enabled = AppFeature.effectiveEnabled(setOf(AppFeature.GUIDE))
        assertTrue(AppFeature.LIVE in enabled)
        assertFalse(AppFeature.GUIDE in enabled)
    }
}
