package com.iptv.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY isDefault DESC, name ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("UPDATE profiles SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE profiles SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE profiles SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels_cache ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT COUNT(*) FROM channels_cache")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM channels_cache WHERE categoryId = :categoryId ORDER BY sortOrder ASC, name ASC")
    fun observeByCategory(categoryId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels_cache WHERE categoryId = :categoryId ORDER BY sortOrder ASC, name ASC")
    suspend fun getByCategory(categoryId: String): List<ChannelEntity>

    @Query("SELECT DISTINCT categoryId FROM channels_cache WHERE epgChannelId IN (:epgIds)")
    suspend fun distinctCategoryIdsForEpg(epgIds: List<String>): List<String>

    @Query("SELECT * FROM channels_cache WHERE tvArchive > 0 AND source = :source ORDER BY name ASC")
    suspend fun getWithTvArchive(source: String): List<ChannelEntity>

    @Query("SELECT * FROM channels_cache WHERE streamId = :streamId")
    suspend fun getById(streamId: Int): ChannelEntity?

    @Query("SELECT * FROM channels_cache WHERE streamId IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<ChannelEntity>

    @Query("SELECT * FROM channels_cache")
    suspend fun getAll(): List<ChannelEntity>

    @Query("SELECT * FROM channels_cache WHERE name LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int = 120): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("SELECT * FROM channels_cache WHERE source = :source")
    suspend fun getBySource(source: String): List<ChannelEntity>

    @Query("SELECT * FROM channels_cache WHERE externalId = :externalId LIMIT 1")
    suspend fun getByExternalId(externalId: String): ChannelEntity?

    @Query("DELETE FROM channels_cache WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM channels_cache")
    suspend fun clearAll()

    @Query("UPDATE channels_cache SET logoUrl = :url WHERE streamId = :streamId")
    suspend fun updateLogoUrl(streamId: Int, url: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories_cache WHERE feedType = :feedType ORDER BY sortOrder ASC, name ASC")
    fun observeByFeedType(feedType: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories_cache WHERE feedType = :feedType ORDER BY sortOrder ASC, name ASC")
    suspend fun getByFeedType(feedType: String): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories_cache WHERE feedType = :feedType")
    suspend fun clearByFeedType(feedType: String)

    @Query("SELECT * FROM categories_cache WHERE feedType = :feedType AND source = :source ORDER BY sortOrder ASC, name ASC")
    suspend fun getByFeedAndSource(feedType: String, source: String): List<CategoryEntity>

    @Query("DELETE FROM categories_cache WHERE feedType = :feedType AND source = :source")
    suspend fun deleteByFeedAndSource(feedType: String, source: String)

    @Query("DELETE FROM categories_cache")
    suspend fun clearAll()
}

@Dao
interface CategoryOverrideDao {
    @Query("SELECT * FROM provider_category_overrides WHERE profileId = :profileId AND feedType = :feedType")
    fun observeByProfile(profileId: Long, feedType: String): Flow<List<ProviderCategoryOverrideEntity>>

    @Query("SELECT * FROM provider_category_overrides WHERE profileId = :profileId AND feedType = :feedType")
    suspend fun getByProfile(profileId: Long, feedType: String): List<ProviderCategoryOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: ProviderCategoryOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(overrides: List<ProviderCategoryOverrideEntity>)

    @Query("SELECT COUNT(*) FROM provider_category_overrides WHERE profileId = :profileId")
    suspend fun countForProfile(profileId: Long): Int
}

@Dao
interface CustomGroupDao {
    @Query("SELECT * FROM custom_groups WHERE profileId = :profileId ORDER BY pinned DESC, sortOrder ASC, name ASC")
    fun observeByProfile(profileId: Long): Flow<List<CustomGroupEntity>>

    @Query("SELECT * FROM custom_groups WHERE id = :id")
    suspend fun getById(id: Long): CustomGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: CustomGroupEntity): Long

    @Update
    suspend fun update(group: CustomGroupEntity)

    @Query("DELETE FROM custom_groups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT streamId FROM custom_group_members WHERE groupId = :groupId")
    suspend fun getMemberStreamIds(groupId: Long): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: CustomGroupMemberEntity)

    @Query("DELETE FROM custom_group_members WHERE groupId = :groupId AND streamId = :streamId")
    suspend fun removeMember(groupId: Long, streamId: Int)

    @Query("DELETE FROM custom_group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: Long)
}

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourites WHERE profileId = :profileId ORDER BY sortOrder ASC")
    fun observeByProfile(profileId: Long): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourites WHERE profileId = :profileId AND itemType = :type")
    suspend fun getByType(profileId: Long, type: String): List<FavouriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE profileId = :profileId AND itemType = :type AND itemId = :itemId)")
    suspend fun isFavourite(profileId: Long, type: String, itemId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favourite: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE profileId = :profileId AND itemType = :type AND itemId = :itemId")
    suspend fun remove(profileId: Long, type: String, itemId: String)
}

@Dao
interface HiddenChannelDao {
    @Query("SELECT streamId FROM hidden_channels WHERE profileId = :profileId")
    fun observeHiddenIds(profileId: Long): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HiddenChannelEntity)

    @Query("DELETE FROM hidden_channels WHERE profileId = :profileId AND streamId = :streamId")
    suspend fun unhide(profileId: Long, streamId: Int)
}

@Dao
interface HiddenItemDao {
    @Query("SELECT itemId FROM hidden_items WHERE profileId = :profileId AND itemType = :type")
    fun observeHiddenIds(profileId: Long, type: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HiddenItemEntity)

    @Query("DELETE FROM hidden_items WHERE profileId = :profileId AND itemType = :type AND itemId = :itemId")
    suspend fun unhide(profileId: Long, type: String, itemId: String)
}

@Dao
interface ChannelEpgOverrideDao {
    @Query("SELECT * FROM channel_epg_overrides")
    suspend fun getAll(): List<ChannelEpgOverrideEntity>

    @Query("SELECT * FROM channel_epg_overrides WHERE streamId = :streamId")
    suspend fun get(streamId: Int): ChannelEpgOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChannelEpgOverrideEntity)

    @Query("DELETE FROM channel_epg_overrides WHERE streamId = :streamId")
    suspend fun delete(streamId: Int)
}

@Dao
interface ViewHistoryDao {
    @Query("SELECT * FROM view_history WHERE profileId = :profileId ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(profileId: Long, limit: Int = 20): Flow<List<ViewHistoryEntity>>

    @Query("SELECT * FROM view_history WHERE profileId = :profileId AND contentType = :type ORDER BY watchMs DESC, updatedAt DESC")
    fun observeByType(profileId: Long, type: String): Flow<List<ViewHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ViewHistoryEntity)

    @Query("DELETE FROM view_history WHERE profileId = :profileId AND contentId = :contentId")
    suspend fun delete(profileId: Long, contentId: String)

    /** Delete-then-insert in one transaction so two writers cannot leave duplicate rows. */
    @Transaction
    suspend fun replace(entry: ViewHistoryEntity) {
        delete(entry.profileId, entry.contentId)
        insert(entry)
    }

    @Query("DELETE FROM view_history WHERE contentId = :contentId")
    suspend fun deleteForAllProfiles(contentId: String)

    @Query("SELECT * FROM view_history WHERE profileId = :profileId AND contentId = :contentId LIMIT 1")
    suspend fun get(profileId: Long, contentId: String): ViewHistoryEntity?

    @Query(
        "SELECT * FROM view_history WHERE profileId = :profileId AND contentType = :type AND streamId IN (:streamIds)",
    )
    suspend fun getLiveByStreamIds(
        profileId: Long,
        type: String,
        streamIds: List<Int>,
    ): List<ViewHistoryEntity>
}

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId ORDER BY updatedAt DESC")
    fun observeAll(profileId: Long): Flow<List<ResumePositionEntity>>

    @Query("SELECT * FROM resume_positions WHERE profileId = :profileId AND contentId = :contentId")
    suspend fun get(profileId: Long, contentId: String): ResumePositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: ResumePositionEntity)

    @Query("DELETE FROM resume_positions WHERE profileId = :profileId AND contentId = :contentId")
    suspend fun delete(profileId: Long, contentId: String)

    @Query("DELETE FROM resume_positions WHERE contentId = :contentId")
    suspend fun deleteForAllProfiles(contentId: String)
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_programmes WHERE channelEpgId = :channelId AND endTimeMs > :fromMs AND startTimeMs < :toMs ORDER BY startTimeMs ASC")
    suspend fun getForChannel(channelId: String, fromMs: Long, toMs: Long): List<EpgProgrammeEntity>

    @Query("SELECT * FROM epg_programmes WHERE endTimeMs > :fromMs AND startTimeMs < :toMs ORDER BY startTimeMs ASC")
    suspend fun getInRange(fromMs: Long, toMs: Long): List<EpgProgrammeEntity>

    @Query("SELECT * FROM epg_programmes WHERE channelEpgId IN (:channelIds) AND endTimeMs > :fromMs AND startTimeMs < :toMs ORDER BY startTimeMs ASC")
    suspend fun getForChannels(channelIds: List<String>, fromMs: Long, toMs: Long): List<EpgProgrammeEntity>

    @Query("SELECT DISTINCT channelEpgId FROM epg_programmes WHERE endTimeMs > :fromMs AND startTimeMs < :toMs")
    suspend fun distinctChannelIdsInRange(fromMs: Long, toMs: Long): List<String>

    @Query("SELECT * FROM epg_programmes WHERE title LIKE '%' || :query || '%' AND endTimeMs > :nowMs LIMIT :limit")
    suspend fun searchProgrammes(query: String, nowMs: Long, limit: Int = 80): List<EpgProgrammeEntity>

    @Query("SELECT * FROM epg_programmes WHERE channelEpgId = :channelId AND startTimeMs <= :nowMs AND endTimeMs > :nowMs LIMIT 1")
    suspend fun getCurrentProgramme(channelId: String, nowMs: Long): EpgProgrammeEntity?

    @Query("SELECT * FROM epg_programmes WHERE channelStreamId IN (:streamIds) AND startTimeMs <= :nowMs AND endTimeMs > :nowMs")
    suspend fun getCurrentForStreams(streamIds: List<Int>, nowMs: Long): List<EpgProgrammeEntity>

    @Query("SELECT * FROM epg_programmes WHERE channelEpgId = :channelId AND startTimeMs >= :nowMs ORDER BY startTimeMs ASC LIMIT 1")
    suspend fun getNextProgramme(channelId: String, nowMs: Long): EpgProgrammeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programmes: List<EpgProgrammeEntity>)

    @Query("DELETE FROM epg_programmes WHERE endTimeMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("DELETE FROM epg_programmes")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(programmes: List<EpgProgrammeEntity>, retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        clearAll()
        insertAll(programmes.filter { it.endTimeMs >= cutoff })
    }
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE profileId = :profileId ORDER BY programmeStartMs ASC")
    fun observeByProfile(profileId: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startTimeMs DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE status = 'COMPLETED' ORDER BY startTimeMs DESC")
    fun observeCompleted(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE status = :status")
    suspend fun getByStatus(status: String): List<RecordingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE recordings SET positionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE title LIKE '%' || :query || '%' OR channelName LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int = 20): List<RecordingEntity>

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE recordings SET protectedFromAutoDelete = :keep WHERE id = :id")
    suspend fun setProtected(id: Long, keep: Boolean)

    @Query("UPDATE recordings SET filePath = :path WHERE id = :id")
    suspend fun updatePath(id: Long, path: String)
}

@Dao
interface ScheduledRecordingDao {
    @Query("SELECT * FROM scheduled_recordings WHERE status = 'SCHEDULED' ORDER BY startTimeMs ASC")
    suspend fun getScheduled(): List<ScheduledRecordingEntity>

    @Query("SELECT * FROM scheduled_recordings WHERE status = 'SCHEDULED' ORDER BY startTimeMs ASC")
    fun observeScheduled(): Flow<List<ScheduledRecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: ScheduledRecordingEntity): Long

    @Query("SELECT * FROM scheduled_recordings WHERE id = :id")
    suspend fun getById(id: Long): ScheduledRecordingEntity?

    @Query("SELECT * FROM scheduled_recordings WHERE status = :status")
    suspend fun getByStatus(status: String): List<ScheduledRecordingEntity>

    @Query("UPDATE scheduled_recordings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM scheduled_recordings WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SubtitleSyncDao {
    @Query("SELECT * FROM subtitle_sync_offsets WHERE contentKey = :contentKey AND subtitleId = :subtitleId")
    suspend fun get(contentKey: String, subtitleId: String): SubtitleSyncOffsetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(offset: SubtitleSyncOffsetEntity)
}

@Dao
interface SubtitleSidecarDao {
    @Query("SELECT * FROM subtitle_sidecars WHERE contentKey = :contentKey LIMIT 1")
    suspend fun get(contentKey: String): SubtitleSidecarEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sidecar: SubtitleSidecarEntity)

    @Query("DELETE FROM subtitle_sidecars WHERE contentKey = :contentKey")
    suspend fun delete(contentKey: String)
}

@Dao
interface VodDao {
    @Query("SELECT * FROM vod_cache WHERE streamId = :streamId LIMIT 1")
    suspend fun getById(streamId: Int): VodEntity?

    @Query("SELECT * FROM vod_cache WHERE categoryId = :categoryId ORDER BY sortOrder ASC, name ASC")
    fun observeByCategory(categoryId: String): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod_cache WHERE categoryId = :categoryId ORDER BY sortOrder ASC, name ASC LIMIT :limit")
    suspend fun getByCategory(categoryId: String, limit: Int = 40): List<VodEntity>

    @Query("SELECT * FROM vod_cache WHERE source = :source ORDER BY sortOrder ASC, name ASC LIMIT :limit")
    suspend fun getBySource(source: String, limit: Int = 40): List<VodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VodEntity>)

    @Query("UPDATE vod_cache SET streamUrl = :streamUrl, fallbackUrl = :fallbackUrl, containerExtension = :ext WHERE streamId = :streamId")
    suspend fun updatePlayback(streamId: Int, streamUrl: String?, fallbackUrl: String?, ext: String)

    @Query("DELETE FROM vod_cache WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM vod_cache")
    suspend fun clearAll()

    @Query("SELECT * FROM vod_cache WHERE name LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int = 80): List<VodEntity>
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series_cache WHERE seriesId = :seriesId LIMIT 1")
    suspend fun getById(seriesId: Int): SeriesEntity?

    @Query("SELECT * FROM series_cache WHERE categoryId = :categoryId ORDER BY sortOrder ASC, name ASC")
    fun observeByCategory(categoryId: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series_cache WHERE categoryId = :categoryId ORDER BY sortOrder ASC, name ASC LIMIT :limit")
    suspend fun getByCategory(categoryId: String, limit: Int = 40): List<SeriesEntity>

    @Query("SELECT * FROM series_cache WHERE source = :source ORDER BY sortOrder ASC, name ASC LIMIT :limit")
    suspend fun getBySource(source: String, limit: Int = 40): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SeriesEntity>)

    @Query("DELETE FROM series_cache WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM series_cache")
    suspend fun clearAll()

    @Query("SELECT * FROM series_cache WHERE name LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int = 80): List<SeriesEntity>
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episode_cache WHERE streamId = :streamId LIMIT 1")
    suspend fun getById(streamId: Int): EpisodeEntity?

    @Query("SELECT * FROM episode_cache WHERE seriesId = :seriesId ORDER BY seasonNum ASC, episodeNum ASC, sortOrder ASC")
    suspend fun getBySeriesId(seriesId: Int): List<EpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<EpisodeEntity>)

    @Query("UPDATE episode_cache SET streamUrl = :streamUrl, fallbackUrl = :fallbackUrl, containerExtension = :ext WHERE streamId = :streamId")
    suspend fun updatePlayback(streamId: Int, streamUrl: String?, fallbackUrl: String?, ext: String)

    @Query("DELETE FROM episode_cache WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM episode_cache")
    suspend fun clearAll()
}
