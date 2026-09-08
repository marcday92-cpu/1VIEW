package com.iptv.tv.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvLogosCatalogTest {
    @Test
    fun filenameKeysMatchSkySportsMainEvent() {
        val keys = TvLogosCatalog.keysFromFilename("sky-sports-main-event-uk.png")
        assertTrue("skysportsmainevent" in keys)
        val hz = TvLogosCatalog.keysFromFilename("sky-sports-main-event-hz-uk.png")
        assertTrue("skysportsmainevent" in hz)
    }

    @Test
    fun horizontalFileScoresHigherThanIcon() {
        assertTrue(
            TvLogosCatalog.fileScore("sky-sports-main-event-hz-uk.png") >
                TvLogosCatalog.fileScore("sky-sports-main-event-uk.png"),
        )
        assertTrue(
            TvLogosCatalog.fileScore("sky-sports-main-event-uk.png") >
                TvLogosCatalog.fileScore("sky-sports-main-event-icon-uk.png"),
        )
    }

    @Test
    fun constructedUkUrlForSkySp() {
        val urls = TvLogosCatalog.constructedUrls("SkySp Main Event FHD")
        assertTrue(urls.any { it.endsWith("sky-sports-main-event-uk.png") })
        assertEquals(
            "https://cdn.jsdelivr.net/gh/tv-logo/tv-logos@main/countries/united-kingdom/sky-sports-main-event-uk.png",
            urls.first { it.endsWith("sky-sports-main-event-uk.png") },
        )
    }
}
