package com.iptv.tv.ui.catalog

import com.iptv.tv.domain.model.VodSourceFilter

internal fun vodEmptyMessage(
    hasItems: Boolean,
    filter: VodSourceFilter,
    loggedIn: Boolean,
    movies: Boolean,
    loadingFree: Boolean = false,
    freeError: String? = null,
    providerFailed: Boolean = false,
    providerError: String? = null,
): String? {
    if (hasItems) return null
    val kind = if (movies) "films" else "series"
    return when {
        filter == VodSourceFilter.IPTV && !loggedIn ->
            "Sign in under Settings → Connection & Data to bring your IPTV lists back."
        filter == VodSourceFilter.IPTV && providerFailed ->
            providerError?.takeIf { it.isNotBlank() } ?: "Could not load your IPTV $kind."
        filter == VodSourceFilter.IPTV -> "Loading your IPTV $kind…"
        loggedIn && providerFailed && !loadingFree && freeError.isNullOrBlank() && filter == VodSourceFilter.ALL ->
            providerError?.takeIf { it.isNotBlank() } ?: "Could not load your IPTV $kind."
        filter == VodSourceFilter.FREE && loadingFree -> "Loading free $kind…"
        filter == VodSourceFilter.FREE && !freeError.isNullOrBlank() -> freeError
        filter == VodSourceFilter.FREE ->
            "No free $kind from live channels or public archives."
        !loggedIn && loadingFree -> "Loading free $kind…"
        !loggedIn && !freeError.isNullOrBlank() -> freeError
        else -> "No $kind in this list yet."
    }
}
