package com.iptv.tv.domain.model

/** Live ranking by total time watched. Never-watched keep source order. */
object WatchRanking {
    fun channels(channels: List<Channel>, watchMs: Map<Int, Long>): List<Channel> {
        val sourceIndex = channels.mapIndexed { index, channel -> channel.streamId to index }.toMap()
        return channels.sortedWith(
            compareByDescending<Channel> { watchMs[it.streamId] ?: 0L }
                .thenBy { sourceIndex[it.streamId] ?: Int.MAX_VALUE },
        )
    }

    fun categories(
        categories: List<Category>,
        channels: List<Channel>,
        watchMs: Map<Int, Long>,
        adultLastId: String? = CategoryLayout.adultLiveId,
    ): List<Category> {
        val byCategory = channels.groupBy { it.categoryId }
        val sourceIndex = categories.mapIndexed { index, category -> category.id to index }.toMap()
        // Adult (XXX) lists never take part in usage ranking: they stay last, in source order,
        // however much they are watched.
        fun isAdult(category: Category) =
            category.id == adultLastId || AdultContent.isAdultCategory(category.id, category.name)
        return categories.sortedWith(
            compareBy<Category> { isAdult(it) }
                .thenByDescending { category ->
                    if (isAdult(category)) 0L
                    else byCategory[category.id].orEmpty().sumOf { watchMs[it.streamId] ?: 0L }
                }
                .thenBy { sourceIndex[it.id] ?: Int.MAX_VALUE },
        )
    }

    /** IPTV rails first (most watched), then free countries (pinned, then A–Z). */
    fun liveRails(
        categories: List<Category>,
        channels: List<Channel>,
        watchMs: Map<Int, Long>,
        filter: LiveSourceFilter,
        adultLastId: String? = CategoryLayout.adultLiveId,
    ): List<Category> {
        val iptv = categories.filter { !it.isFree }
        val free = categories.filter { it.isFree }
        val rankedIptv = categories(iptv, channels, watchMs, adultLastId)
        val rankedFree = free.sortedWith(
            compareBy<Category> { ChannelSource.freeCountryPinIndex(it.id) }
                .thenBy { it.name.lowercase() },
        )
        return when (filter) {
            LiveSourceFilter.ALL -> rankedIptv + rankedFree
            LiveSourceFilter.IPTV -> rankedIptv
            LiveSourceFilter.FREE -> rankedFree
        }
    }
}

fun Channel.inSourceFilter(filter: LiveSourceFilter): Boolean = when (filter) {
    LiveSourceFilter.ALL -> true
    LiveSourceFilter.IPTV -> !isFree
    LiveSourceFilter.FREE -> isFree
}

fun Category.inVodSourceFilter(filter: VodSourceFilter): Boolean = when (filter) {
    VodSourceFilter.ALL -> true
    VodSourceFilter.IPTV -> !isFree
    VodSourceFilter.FREE -> isFree
}

fun VodItem.inVodSourceFilter(filter: VodSourceFilter): Boolean = when (filter) {
    VodSourceFilter.ALL -> true
    VodSourceFilter.IPTV -> !isFree
    VodSourceFilter.FREE -> isFree
}

fun SeriesItem.inVodSourceFilter(filter: VodSourceFilter): Boolean = when (filter) {
    VodSourceFilter.ALL -> true
    VodSourceFilter.IPTV -> !isFree
    VodSourceFilter.FREE -> isFree
}
