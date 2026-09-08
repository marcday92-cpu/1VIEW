package com.iptv.tv.domain.model

/**
 * Recording and reminders are IPTV-account features.
 * FREE sources (iptv-org, Archive.org) stay watch-only for now.
 * Flip the FREE branches here later without touching every menu.
 */
object IptvCapabilities {
    fun canRecord(source: String?, hasIptvLogin: Boolean): Boolean {
        if (!hasIptvLogin) return false
        val src = source ?: ChannelSource.XC
        return !ChannelSource.isFreeSource(src)
    }

    fun canRemind(source: String?, hasIptvLogin: Boolean): Boolean =
        canRecord(source, hasIptvLogin)

    fun canCatchup(source: String?, hasIptvLogin: Boolean): Boolean =
        canRecord(source, hasIptvLogin)

    fun canRecord(channel: Channel?, hasIptvLogin: Boolean): Boolean =
        canRecord(channel?.source, hasIptvLogin)

    fun canRemind(channel: Channel?, hasIptvLogin: Boolean): Boolean =
        canRemind(channel?.source, hasIptvLogin)

    fun canCatchup(channel: Channel?, hasIptvLogin: Boolean): Boolean =
        canCatchup(channel?.source, hasIptvLogin)

    fun canRecord(item: VodItem, hasIptvLogin: Boolean): Boolean =
        canRecord(item.source, hasIptvLogin)

    fun canRemind(item: VodItem, hasIptvLogin: Boolean): Boolean =
        canRemind(item.source, hasIptvLogin)

    fun canRecord(item: SeriesItem, hasIptvLogin: Boolean): Boolean =
        canRecord(item.source, hasIptvLogin)

    fun canRemind(item: SeriesItem, hasIptvLogin: Boolean): Boolean =
        canRemind(item.source, hasIptvLogin)

    fun canRecord(item: Episode, hasIptvLogin: Boolean): Boolean =
        canRecord(item.source, hasIptvLogin)

    fun showRecordingUi(hasIptvLogin: Boolean): Boolean = hasIptvLogin

    fun showReminderUi(hasIptvLogin: Boolean): Boolean = hasIptvLogin

    fun showCatchupUi(hasIptvLogin: Boolean): Boolean = hasIptvLogin
}
