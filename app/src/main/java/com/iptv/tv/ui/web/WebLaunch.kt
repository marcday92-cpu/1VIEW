package com.iptv.tv.ui.web

/** Hands a URL from Multi (or elsewhere) to the Browser tab without clearing cookies. */
object WebLaunch {
    @Volatile
    var pendingUrl: String? = null
}
