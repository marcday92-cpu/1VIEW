package com.iptv.tv.edition

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * What differs between the two APKs. Everything else in the app is shared.
 *
 * The DeeTV edition ships a one-press shortcut to the DeeTV site in the Web tab and Multi
 * picker. The Browser edition is a plain browser with an address bar and recent sites.
 * Each flavour source set provides exactly one [EditionConfig] object.
 */
interface BrowserEdition {
    /** Short id, matches BuildConfig.EDITION. */
    val id: String

    /** Label shown in Settings → About. */
    val displayName: String

    /**
     * A featured site that gets its own button on the Web start page, browser bar and
     * Multi picker, or null when this edition has no featured site.
     */
    val featuredSite: FeaturedSite?

    /** Copy shown on the Web tab start page under the title. */
    val startPageHint: String
}

data class FeaturedSite(
    val label: String,
    val url: String,
)

@Module
@InstallIn(SingletonComponent::class)
object EditionModule {
    @Provides
    @Singleton
    fun provideEdition(): BrowserEdition = EditionConfig
}
