package com.iptv.tv.domain.model

enum class FeedType { LIVE, VOD, SERIES }

enum class FavouriteType { CHANNEL, MOVIE, SERIES }

enum class ContentType { LIVE, MOVIE, EPISODE }

data class ServerCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
)

data class UserInfo(
    val auth: Int,
    val username: String,
    val maxConnections: Int,
    val status: String,
)

data class Category(
    val id: String,
    val name: String,
    val feedType: FeedType,
    val sortOrder: Int = 0,
    val source: String = ChannelSource.XC,
) {
    val isFree: Boolean get() = ChannelSource.isFreeSource(source)
}

data class Channel(
    val streamId: Int,
    val name: String,
    val logoUrl: String?,
    val categoryId: String,
    val epgChannelId: String?,
    val tvArchive: Int = 0,
    val tvArchiveDuration: Int = 0,
    val source: String = ChannelSource.XC,
    val externalId: String? = null,
    val streamUrl: String? = null,
    val fallbackUrl: String? = null,
    val referrer: String? = null,
    val userAgent: String? = null,
) {
    val isFree: Boolean get() = source == ChannelSource.IPTV_ORG
}

data class Programme(
    val id: String,
    val channelId: String,
    val channelStreamId: Int?,
    val title: String,
    val description: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val hasArchive: Boolean = false,
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
    val isLiveNow: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now in startTimeMs..endTimeMs
        }
    val progressFraction: Float
        get() {
            val now = System.currentTimeMillis()
            if (now < startTimeMs || now > endTimeMs) return 0f
            return (now - startTimeMs).toFloat() / durationMs.coerceAtLeast(1)
        }
}

data class VodItem(
    val streamId: Int,
    val name: String,
    val posterUrl: String?,
    val categoryId: String,
    val rating: String?,
    val year: String?,
    val duration: String?,
    val containerExtension: String = "mp4",
    val source: String = ChannelSource.XC,
    val externalId: String? = null,
    val streamUrl: String? = null,
    val fallbackUrl: String? = null,
) {
    val isFree: Boolean get() = ChannelSource.isFreeSource(source)
}

data class SeriesItem(
    val seriesId: Int,
    val name: String,
    val posterUrl: String?,
    val categoryId: String,
    val rating: String?,
    val plot: String? = null,
    val source: String = ChannelSource.XC,
    val externalId: String? = null,
) {
    val isFree: Boolean get() = ChannelSource.isFreeSource(source)
}

data class Episode(
    val id: String,
    val episodeNum: Int,
    val seasonNum: Int,
    val title: String,
    val streamId: Int,
    val containerExtension: String,
    val info: EpisodeInfo?,
    val source: String = ChannelSource.XC,
    val externalId: String? = null,
    val streamUrl: String? = null,
    val fallbackUrl: String? = null,
) {
    val isFree: Boolean get() = ChannelSource.isFreeSource(source)
}

data class EpisodeInfo(
    val plot: String?,
    val durationSecs: Int?,
    val rating: String?,
    val releaseDate: String?,
)

data class SeriesDetail(
    val seriesId: Int,
    val name: String,
    val posterUrl: String?,
    val plot: String?,
    val seasons: Map<Int, List<Episode>>,
)

data class Profile(
    val id: Long,
    val name: String,
    val avatarColor: Long,
    val isDefault: Boolean = false,
    val accentColor: Long? = null,
    val subtitleLanguage: String = "en",
    val subtitleFontSize: Float = 1.0f,
    val subtitlePosition: Float = 0.9f,
    val subtitleBackground: Boolean = false,
    val preferExternalSubs: Boolean = false,
    val preferredAudioLanguage: String = "en",
) {
    /** Default → D, Marc → M, Mary Jane → MJ. */
    val initials: String
        get() {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when {
                parts.isEmpty() -> "?"
                parts.size == 1 -> parts[0].firstLetter()
                else -> parts[0].firstLetter() + parts[1].firstLetter()
            }
        }

    private fun String.firstLetter(): String {
        if (isEmpty()) return "?"
        val cp = codePointAt(0)
        return String(Character.toChars(Character.toUpperCase(cp)))
    }
}

data class ResumeItem(
    val contentId: String,
    val contentType: ContentType,
    val title: String,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val seasonNum: Int? = null,
    val episodeNum: Int? = null,
    val streamId: Int? = null,
    val year: String? = null,
    val seriesId: Int? = null,
    val filename: String? = null,
    val containerExtension: String? = null,
) {
    val progressFraction: Float
        get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
}

data class CustomGroup(
    val id: Long,
    val profileId: Long,
    val name: String,
    val sortOrder: Int,
    val pinned: Boolean = false,
)

data class SearchResult(
    val type: SearchResultType,
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val streamId: Int? = null,
    val programmeStartMs: Long? = null,
    /** Release year for films, kept separate from the display subtitle. */
    val year: String? = null,
    /** Belongs to an adult category, so the parental lock can hide it. */
    val adult: Boolean = false,
)

enum class SearchResultType {
    CHANNEL, PROGRAMME, MOVIE, SERIES, RECORDING
    ;

    val label: String
        get() = when (this) {
            CHANNEL -> "Channel"
            PROGRAMME -> "Guide"
            MOVIE -> "Film"
            SERIES -> "Series"
            RECORDING -> "Recording"
        }
}

data class TitleMetadata(
    val name: String? = null,
    val year: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
)

data class SubtitleQuery(
    val title: String,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val filename: String? = null,
    val type: ContentType,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val language: String = "en",
)

data class SubtitleCandidate(
    val id: String,
    val fileName: String,
    val language: String,
    val matchScore: Int,
    val hearingImpaired: Boolean = false,
    val provider: String,
    val downloadUrl: String? = null,
    val releaseName: String? = null,
)

data class RecordingItem(
    val id: Long,
    val title: String,
    val channelName: String,
    val filePath: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val status: RecordingStatus,
    val positionMs: Long = 0,
    val protectedFromAutoDelete: Boolean = false,
)

enum class RecordingStatus { SCHEDULED, RECORDING, COMPLETED, FAILED }

data class NowOnTvItem(
    val channel: Channel,
    val programme: Programme,
)

data class ReminderItem(
    val id: Long,
    val profileId: Long,
    val channelStreamId: Int,
    val channelName: String,
    val programmeTitle: String,
    val programmeStartMs: Long,
    val offsetMinutes: Int,
)
