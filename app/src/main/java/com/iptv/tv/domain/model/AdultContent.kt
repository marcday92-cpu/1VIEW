package com.iptv.tv.domain.model

object AdultContent {
    fun isAdultCategory(id: String?, name: String? = null): Boolean {
        if (id == CategoryLayout.adultLiveId || id == CategoryLayout.adultVodId) return true
        val n = name.orEmpty().lowercase()
        if (n.isBlank()) return false
        return n.contains("adult") || n.contains("xxx") || n.contains("18+") || n.contains("porn")
    }

    fun isAdultChannel(channel: Channel): Boolean =
        isAdultCategory(channel.categoryId, channel.name)

    fun isAdultVod(item: VodItem): Boolean =
        isAdultCategory(item.categoryId, item.name)

    fun isAdultSeries(item: SeriesItem): Boolean =
        isAdultCategory(item.categoryId, item.name)
}
