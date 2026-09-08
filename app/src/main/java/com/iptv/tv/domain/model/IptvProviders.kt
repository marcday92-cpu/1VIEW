package com.iptv.tv.domain.model

object IptvProviders {
    const val EDTV_NAME = "EDTV"
    const val EDTV_URL = "https://blowyourraffles.win"

    fun displayName(serverUrl: String?): String {
        val normalized = serverUrl?.trimEnd('/')?.lowercase().orEmpty()
        return if (normalized == EDTV_URL.lowercase()) EDTV_NAME else "Custom IPTV"
    }

    fun isEdtv(serverUrl: String?): Boolean =
        serverUrl?.trimEnd('/')?.equals(EDTV_URL, ignoreCase = true) == true
}
