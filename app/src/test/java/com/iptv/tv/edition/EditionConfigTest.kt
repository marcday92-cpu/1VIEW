package com.iptv.tv.edition

import com.iptv.tv.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs once per flavour and pins the edition object to the flavour being built. */
class EditionConfigTest {
    @Test
    fun editionMatchesBuildFlavour() {
        assertEquals(BuildConfig.EDITION, EditionConfig.id)
        when (EditionConfig.id) {
            // Browser is the published identity: versionName stays 1.1.11 with no suffix.
            "browser" -> assertTrue(!BuildConfig.VERSION_NAME.contains("-deetv"))
            "deetv" -> assertTrue(BuildConfig.VERSION_NAME.endsWith("-deetv"))
            else -> error("Unknown edition ${EditionConfig.id}")
        }
    }

    @Test
    fun featuredSiteFollowsTheEdition() {
        when (EditionConfig.id) {
            "deetv" -> {
                val site = assertNotNull(EditionConfig.featuredSite)
                assertEquals("DeeTV", site.label)
                assertTrue(site.url.startsWith("https://deetv."))
            }
            "browser" -> assertNull(EditionConfig.featuredSite)
            else -> error("Unknown edition ${EditionConfig.id}")
        }
    }
}
