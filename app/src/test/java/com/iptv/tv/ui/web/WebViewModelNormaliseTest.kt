package com.iptv.tv.ui.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebViewModelNormaliseTest {
    @Test
    fun bareDomainGetsHttps() {
        assertEquals("https://bbc.co.uk", WebViewModel.normalise(" bbc.co.uk "))
        assertEquals("https://bbc.co.uk/iplayer", WebViewModel.normalise("bbc.co.uk/iplayer"))
    }

    @Test
    fun existingSchemeIsKept() {
        assertEquals("http://example.com", WebViewModel.normalise("http://example.com"))
        assertEquals("https://Example.com", WebViewModel.normalise("https://Example.com"))
    }

    @Test
    fun rejectsBlankOtherSchemesAndSpaces() {
        assertNull(WebViewModel.normalise(""))
        assertNull(WebViewModel.normalise("   "))
        assertNull(WebViewModel.normalise("ftp://files.example.com"))
        assertNull(WebViewModel.normalise("https://"))
        assertNull(WebViewModel.normalise("not a url"))
    }
}
