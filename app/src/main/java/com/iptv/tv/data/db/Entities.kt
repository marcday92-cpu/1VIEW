package com.iptv.tv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatarColor: Long,
    val isDefault: Boolean = false,
    val subtitleLanguage: String = "en",
    val subtitleFontSize: Float = 1.0f,
    val subtitleTextColor: Long = 0xFFFFFFFF,
    val subtitleOutline: Boolean = true,
    val subtitleBackground: Boolean = true,
    val subtitlePosition: Float = 0.9f,
    val preferEmbeddedSubs: Boolean = true,
    val preferExternalSubs: Boolean = false,
    val preferSdh: Boolean = false,
    val autoLoadSubs: Boolean = true,
    val preferredAudioLanguage: String = "en",
    val accentColor: Long? = null,
)

@Entity(
    tableName = "channels_cache",
    indices = [
        Index("categoryId"),
        Index("streamId", unique = true),
        Index("source"),
        Index("externalId"),
    ],
)
data class ChannelEntity(
    @PrimaryKey val streamId: Int,
    val name: String,
    val logoUrl: String?,
    val categoryId: String,
    val epgChannelId: String?,
    val tvArchive: Int = 0,
    val tvArchiveDuration: Int = 0,
    val sortOrder: Int = 0,
    val source: String = "xc",
    val externalId: String? = null,
    val streamUrl: String? = null,
    val fallbackUrl: String? = null,
    val referrer: String? = null,
    val userAgent: String? = null,
)

@Entity(
    tableName = "categories_cache",
    primaryKeys = ["id", "feedType"],
)
data class CategoryEntity(
    val id: String,
    val name: String,
    val feedType: String,
    val sortOrder: Int = 0,
    val source: String = "xc",
)

@Entity(
    tableName = "provider_category_overrides",
    indices = [Index("profileId", "feedType")],
)
data class ProviderCategoryOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val feedType: String,
    val providerCategoryId: String,
    val displayName: String?,
    val sortOrder: Int,
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val collapsed: Boolean = false,
    val mergedIntoId: String? = null,
)

@Entity(
    tableName = "custom_groups",
    indices = [Index("profileId")],
)
data class CustomGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val sortOrder: Int,
    val pinned: Boolean = false,
)

@Entity(
    tableName = "custom_group_members",
    indices = [Index("groupId"), Index("streamId")],
)
data class CustomGroupMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val streamId: Int,
)

@Entity(
    tableName = "favourites",
    indices = [Index("profileId", "itemType", "itemId", unique = true)],
)
data class FavouriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val itemType: String,
    val itemId: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "hidden_channels",
    indices = [Index("profileId", "streamId", unique = true)],
)
data class HiddenChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val streamId: Int,
)

@Entity(
    tableName = "hidden_items",
    indices = [Index("profileId", "itemType", "itemId", unique = true)],
)
data class HiddenItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val itemType: String,
    val itemId: String,
)

@Entity(tableName = "channel_epg_overrides")
data class ChannelEpgOverrideEntity(
    @PrimaryKey val streamId: Int,
    val epgChannelId: String,
)

@Entity(
    tableName = "view_history",
    indices = [Index("profileId"), Index("contentId")],
)
data class ViewHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val contentId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String?,
    val streamId: Int?,
    val seasonNum: Int? = null,
    val episodeNum: Int? = null,
    val year: String? = null,
    val seriesId: Int? = null,
    val filename: String? = null,
    val containerExtension: String? = null,
    val watchCount: Int = 1,
    val watchMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "resume_positions",
    indices = [Index("profileId", "contentId", unique = true)],
)
data class ResumePositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val contentId: String,
    val contentType: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "epg_programmes",
    indices = [
        Index("channelEpgId"),
        Index("startTimeMs"),
        Index("endTimeMs"),
        Index("channelEpgId", "startTimeMs"),
    ],
)
data class EpgProgrammeEntity(
    @PrimaryKey val id: String,
    val channelEpgId: String,
    val channelStreamId: Int?,
    val title: String,
    val description: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

@Entity(
    tableName = "reminders",
    indices = [Index("profileId"), Index("alarmId", unique = true)],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val channelStreamId: Int,
    val channelName: String,
    val programmeTitle: String,
    val programmeStartMs: Long,
    val offsetMinutes: Int,
    val alarmId: Int,
)

@Entity(
    tableName = "recordings",
    indices = [Index("status"), Index("startTimeMs")],
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val channelName: String,
    val channelStreamId: Int,
    val filePath: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val status: String,
    val positionMs: Long = 0,
    val protectedFromAutoDelete: Boolean = false,
)

@Entity(
    tableName = "scheduled_recordings",
    indices = [Index("startTimeMs"), Index("alarmId", unique = true)],
)
data class ScheduledRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val channelName: String,
    val channelStreamId: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startBufferMinutes: Int = 2,
    val endBufferMinutes: Int = 5,
    val storagePath: String,
    val alarmId: Int,
    val status: String = "SCHEDULED",
)

@Entity(
    tableName = "subtitle_sync_offsets",
    indices = [Index("contentKey", "subtitleId", unique = true)],
)
data class SubtitleSyncOffsetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentKey: String,
    val subtitleId: String,
    val offsetMs: Long,
    val driftPoints: String? = null,
)

@Entity(tableName = "subtitle_sidecars")
data class SubtitleSidecarEntity(
    @PrimaryKey val contentKey: String,
    val filePath: String?,
    val subtitleId: String,
    val language: String = "en",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "vod_cache",
    indices = [Index("categoryId"), Index("streamId", unique = true), Index("source")],
)
data class VodEntity(
    @PrimaryKey val streamId: Int,
    val name: String,
    val posterUrl: String?,
    val categoryId: String,
    val rating: String?,
    val year: String?,
    val duration: String?,
    val containerExtension: String = "mp4",
    val sortOrder: Int = 0,
    val source: String = "xc",
    val externalId: String? = null,
    val streamUrl: String? = null,
    val fallbackUrl: String? = null,
)

@Entity(
    tableName = "series_cache",
    indices = [Index("categoryId"), Index("seriesId", unique = true), Index("source")],
)
data class SeriesEntity(
    @PrimaryKey val seriesId: Int,
    val name: String,
    val posterUrl: String?,
    val categoryId: String,
    val rating: String?,
    val plot: String? = null,
    val sortOrder: Int = 0,
    val source: String = "xc",
    val externalId: String? = null,
)

@Entity(
    tableName = "episode_cache",
    indices = [Index("seriesId"), Index("source")],
)
data class EpisodeEntity(
    @PrimaryKey val streamId: Int,
    val seriesId: Int,
    val episodeNum: Int,
    val seasonNum: Int,
    val title: String,
    val containerExtension: String = "mp4",
    val plot: String? = null,
    val durationSecs: Int? = null,
    val source: String = "xc",
    val externalId: String? = null,
    val streamUrl: String? = null,
    val fallbackUrl: String? = null,
    val sortOrder: Int = 0,
)
