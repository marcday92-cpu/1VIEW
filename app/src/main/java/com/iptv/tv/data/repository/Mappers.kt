package com.iptv.tv.data.repository

import com.iptv.tv.data.api.CategoryDto
import com.iptv.tv.data.api.EpgListingDto
import com.iptv.tv.data.api.EpisodeDto
import com.iptv.tv.data.api.LiveStreamDto
import com.iptv.tv.data.api.SeriesDto
import com.iptv.tv.data.api.VodStreamDto
import com.iptv.tv.data.db.CategoryEntity
import com.iptv.tv.data.db.ChannelEntity
import com.iptv.tv.data.db.EpgProgrammeEntity
import com.iptv.tv.data.db.EpisodeEntity
import com.iptv.tv.data.db.ProfileEntity
import com.iptv.tv.data.db.SeriesEntity
import com.iptv.tv.data.db.VodEntity
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ChannelSource
import com.iptv.tv.domain.model.Episode
import com.iptv.tv.domain.model.EpisodeInfo
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.domain.model.Profile
import com.iptv.tv.domain.model.SeriesDetail
import com.iptv.tv.domain.model.SeriesItem
import com.iptv.tv.domain.model.VodItem

fun LiveStreamDto.toChannel(): Channel? {
    val id = streamIdInt() ?: return null
    val channelName = name?.takeIf { it.isNotBlank() } ?: return null
    return Channel(
        streamId = id,
        name = channelName,
        logoUrl = streamIcon?.takeIf { it.isNotBlank() },
        categoryId = categoryIdStr(),
        epgChannelId = epgChannelId?.takeIf { it.isNotBlank() },
        tvArchive = tvArchiveInt(),
        tvArchiveDuration = tvArchiveDurationInt(),
    )
}

fun ChannelEntity.toDomain() = Channel(
    streamId = streamId,
    name = name,
    logoUrl = logoUrl,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
    tvArchive = tvArchive,
    tvArchiveDuration = tvArchiveDuration,
    source = source,
    externalId = externalId,
    streamUrl = streamUrl,
    fallbackUrl = fallbackUrl,
    referrer = referrer,
    userAgent = userAgent,
)

fun Channel.toEntity(sortOrder: Int = 0) = ChannelEntity(
    streamId = streamId,
    name = name,
    logoUrl = logoUrl,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
    tvArchive = tvArchive,
    tvArchiveDuration = tvArchiveDuration,
    sortOrder = sortOrder,
    source = source,
    externalId = externalId,
    streamUrl = streamUrl,
    fallbackUrl = fallbackUrl,
    referrer = referrer,
    userAgent = userAgent,
)

fun CategoryDto.toCategory(feedType: FeedType): Category? {
    val catId = id().takeIf { it.isNotBlank() } ?: return null
    val catName = categoryName?.takeIf { it.isNotBlank() } ?: return null
    return Category(id = catId, name = catName, feedType = feedType)
}

fun CategoryEntity.toDomain() = Category(
    id = id,
    name = name,
    feedType = FeedType.valueOf(feedType),
    sortOrder = sortOrder,
    source = source,
)

fun Category.toEntity() = CategoryEntity(
    id = id,
    name = name,
    feedType = feedType.name,
    sortOrder = sortOrder,
    source = source,
)

fun EpgListingDto.toProgramme(channelId: String, streamId: Int?): Programme? {
    val progTitle = decodedTitle()
    return Programme(
        id = id ?: "${channelId}_${startMs()}",
        channelId = channelId,
        channelStreamId = streamId,
        title = progTitle,
        description = decodedDescription(),
        startTimeMs = startMs(),
        endTimeMs = endMs(),
        hasArchive = hasArchiveInt() > 0,
    )
}

fun EpgProgrammeEntity.toDomain() = Programme(
    id = id,
    channelId = channelEpgId,
    channelStreamId = channelStreamId,
    title = title,
    description = description,
    startTimeMs = startTimeMs,
    endTimeMs = endTimeMs,
)

fun Programme.toEntity() = EpgProgrammeEntity(
    id = id,
    channelEpgId = channelId,
    channelStreamId = channelStreamId,
    title = title,
    description = description,
    startTimeMs = startTimeMs,
    endTimeMs = endTimeMs,
)

fun VodStreamDto.toVodItem(): VodItem? {
    val id = streamIdInt() ?: return null
    val vodName = name?.takeIf { it.isNotBlank() } ?: return null
    return VodItem(
        streamId = id,
        name = vodName,
        posterUrl = streamIcon?.takeIf { it.isNotBlank() },
        categoryId = categoryIdStr(),
        rating = rating,
        year = year,
        duration = duration,
        containerExtension = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4",
    )
}

fun VodEntity.toDomain() = VodItem(
    streamId = streamId,
    name = name,
    posterUrl = posterUrl,
    categoryId = categoryId,
    rating = rating,
    year = year,
    duration = duration,
    containerExtension = containerExtension,
    source = source,
    externalId = externalId,
    streamUrl = streamUrl,
    fallbackUrl = fallbackUrl,
)

fun VodItem.toEntity(sortOrder: Int = 0) = VodEntity(
    streamId = streamId,
    name = name,
    posterUrl = posterUrl,
    categoryId = categoryId,
    rating = rating,
    year = year,
    duration = duration,
    containerExtension = containerExtension,
    sortOrder = sortOrder,
    source = source,
    externalId = externalId,
    streamUrl = streamUrl,
    fallbackUrl = fallbackUrl,
)

fun SeriesDto.toSeriesItem(): SeriesItem? {
    val id = seriesIdInt() ?: return null
    val seriesName = name?.takeIf { it.isNotBlank() } ?: return null
    return SeriesItem(
        seriesId = id,
        name = seriesName,
        posterUrl = cover?.takeIf { it.isNotBlank() },
        categoryId = categoryIdStr(),
        rating = rating,
        source = ChannelSource.XC,
    )
}

fun SeriesEntity.toDomain() = SeriesItem(
    seriesId = seriesId,
    name = name,
    posterUrl = posterUrl,
    categoryId = categoryId,
    rating = rating,
    plot = plot,
    source = source,
    externalId = externalId,
)

fun SeriesItem.toEntity(sortOrder: Int = 0) = SeriesEntity(
    seriesId = seriesId,
    name = name,
    posterUrl = posterUrl,
    categoryId = categoryId,
    rating = rating,
    plot = plot,
    sortOrder = sortOrder,
    source = source,
    externalId = externalId,
)

fun EpisodeDto.toEpisode(): Episode? {
    val epId = id ?: return null
    val epStreamId = epId.toIntOrNull() ?: return null
    return Episode(
        id = epId,
        episodeNum = episodeNumInt(),
        seasonNum = seasonInt(),
        title = title ?: "Episode ${episodeNumInt()}",
        streamId = epStreamId,
        containerExtension = containerExtension ?: "mp4",
        info = info?.let {
            EpisodeInfo(
                plot = it.plot,
                durationSecs = it.durationSecsInt(),
                rating = it.rating,
                releaseDate = it.releaseDate,
            )
        },
        source = ChannelSource.XC,
    )
}

fun EpisodeEntity.toDomain() = Episode(
    id = streamId.toString(),
    episodeNum = episodeNum,
    seasonNum = seasonNum,
    title = title,
    streamId = streamId,
    containerExtension = containerExtension,
    info = EpisodeInfo(plot = plot, durationSecs = durationSecs, rating = null, releaseDate = null),
    source = source,
    externalId = externalId,
    streamUrl = streamUrl,
    fallbackUrl = fallbackUrl,
)

fun Episode.toEntity(seriesId: Int, sortOrder: Int = 0) = EpisodeEntity(
    streamId = streamId,
    seriesId = seriesId,
    episodeNum = episodeNum,
    seasonNum = seasonNum,
    title = title,
    containerExtension = containerExtension,
    plot = info?.plot,
    durationSecs = info?.durationSecs,
    source = source,
    externalId = externalId,
    streamUrl = streamUrl,
    fallbackUrl = fallbackUrl,
    sortOrder = sortOrder,
)

fun ProfileEntity.toDomain() = Profile(
    id = id,
    name = name,
    avatarColor = avatarColor,
    isDefault = isDefault,
    accentColor = accentColor,
    subtitleLanguage = subtitleLanguage,
    subtitleFontSize = subtitleFontSize,
    subtitlePosition = subtitlePosition,
    subtitleBackground = subtitleBackground,
    preferExternalSubs = preferExternalSubs,
    preferredAudioLanguage = preferredAudioLanguage,
)
