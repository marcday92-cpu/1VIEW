package com.iptv.tv.ui.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * Browser site data lives under the app data dir (`app_webview_1view`), not [Context.cacheDir],
 * so Home / Settings "Clear cache" never logs the user out of a site. Only [clearSiteData] does.
 *
 * [install] runs lazily from the first [BrowserWebView], so an app start that never opens the
 * Web tab never loads the Chromium provider.
 */
object BrowserPersistence {
    const val DATA_DIR_SUFFIX = "1view"

    @Volatile
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching { WebView.setDataDirectorySuffix(DATA_DIR_SUFFIX) }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        installed = true
    }

    fun flush() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun clearSiteData(context: Context) {
        install(context)
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.flush()
        WebStorage.getInstance().deleteAllData()
        // Cookies and WebStorage do not cover the HTTP cache or service-worker storage.
        runCatching { WebView(context).apply { clearCache(true); destroy() } }
    }
}
