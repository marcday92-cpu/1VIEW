package com.iptv.tv.ui.web

import android.content.Context
import android.webkit.WebSettings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.edition.BrowserEdition
import com.iptv.tv.ui.launchSafely
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class WebUiState(
    val url: String = WebViewModel.START,
    val title: String = "",
    val desktopUa: Boolean = true,
)

data class RecentSite(val url: String, val title: String)

@HiltViewModel
class WebViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    val edition: BrowserEdition,
) : ViewModel() {

    private val _state = MutableStateFlow(WebUiState())
    val state: StateFlow<WebUiState> = _state.asStateFlow()

    val recentSites: StateFlow<List<RecentSite>> = preferences.recentSites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val defaultUserAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(DESKTOP_UA)
    }

    fun open(raw: String) {
        val url = normalise(raw) ?: return
        _state.value = _state.value.copy(url = url)
    }

    fun openFeatured() {
        edition.featuredSite?.let { open(it.url) }
    }

    fun goHome() {
        _state.value = _state.value.copy(url = START, title = "")
    }

    /** A navigation began: the address updates now, the title only once the page has one. */
    fun onPageStarted(url: String) {
        if (url != _state.value.url) _state.value = _state.value.copy(url = url, title = "")
    }

    fun onNavigated(url: String, title: String) {
        _state.value = _state.value.copy(url = url, title = title)
        if (url.startsWith("http")) {
            launchSafely("WebViewModel.rememberSite") { preferences.rememberRecentSite(url, title) }
        }
    }

    /** Chromium's renderer died (out of memory on a 1 GB stick): drop back to the start page. */
    fun onRendererGone() {
        _state.value = _state.value.copy(url = START, title = "")
        _rendererGeneration.value++
    }

    private val _rendererGeneration = MutableStateFlow(0)
    /** Keys the WebView host so a dead renderer is replaced by a fresh WebView. */
    val rendererGeneration: StateFlow<Int> = _rendererGeneration.asStateFlow()

    fun forgetRecent(site: RecentSite) {
        launchSafely("WebViewModel.forgetRecent") { preferences.forgetRecentSite(site.url) }
    }

    fun toggleDesktopUa() {
        _state.value = _state.value.copy(desktopUa = !_state.value.desktopUa)
    }

    fun userAgent(): String = if (_state.value.desktopUa) DESKTOP_UA else defaultUserAgent

    companion object {
        const val START = "about:1view-start"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Accepts "bbc.co.uk", "http://…" or "https://…"; anything else is rejected. */
        fun normalise(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            val withScheme = when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.contains("://") -> return null
                else -> "https://$trimmed"
            }
            val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?')
            if (host.isBlank() || host.any { it.isWhitespace() }) return null
            return withScheme
        }

        fun clearBrowserData(context: Context) {
            BrowserPersistence.clearSiteData(context.applicationContext)
        }
    }
}
