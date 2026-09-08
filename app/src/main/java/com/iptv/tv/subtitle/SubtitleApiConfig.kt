package com.iptv.tv.subtitle

import com.iptv.tv.BuildConfig
import com.iptv.tv.data.credentials.CredentialsStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleApiConfig @Inject constructor(
    private val credentialsStore: CredentialsStore,
) {
    fun subdlApiKey(): String {
        val override = credentialsStore.getSubdlApiKey()?.trim().orEmpty()
        if (override.isNotBlank()) return override
        return BuildConfig.SUBDL_API_KEY.trim()
    }
}
