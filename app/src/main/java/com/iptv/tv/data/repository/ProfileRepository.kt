package com.iptv.tv.data.repository

import com.iptv.tv.data.db.CustomGroupDao
import com.iptv.tv.data.db.CustomGroupEntity
import com.iptv.tv.data.db.CustomGroupMemberEntity
import com.iptv.tv.data.db.FavouriteDao
import com.iptv.tv.data.db.FavouriteEntity
import com.iptv.tv.data.db.HiddenChannelDao
import com.iptv.tv.data.db.HiddenChannelEntity
import com.iptv.tv.data.db.HiddenItemDao
import com.iptv.tv.data.db.HiddenItemEntity
import com.iptv.tv.data.db.ProfileDao
import com.iptv.tv.data.db.ProfileEntity
import com.iptv.tv.data.db.ProviderCategoryOverrideEntity
import com.iptv.tv.data.db.CategoryOverrideDao
import com.iptv.tv.data.db.ResumeDao
import com.iptv.tv.data.db.ResumePositionEntity
import com.iptv.tv.data.db.RecordingDao
import com.iptv.tv.data.db.ViewHistoryDao
import com.iptv.tv.data.db.ViewHistoryEntity
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.CategoryLayout
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.CustomGroup
import com.iptv.tv.domain.model.FavouriteType
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.LiveSourceFilter
import com.iptv.tv.domain.model.Profile
import com.iptv.tv.domain.model.ResumeItem
import com.iptv.tv.domain.model.WatchRanking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val favouriteDao: FavouriteDao,
    private val hiddenChannelDao: HiddenChannelDao,
    private val hiddenItemDao: HiddenItemDao,
    private val categoryOverrideDao: CategoryOverrideDao,
    private val customGroupDao: CustomGroupDao,
    private val viewHistoryDao: ViewHistoryDao,
    private val resumeDao: ResumeDao,
    private val recordingDao: RecordingDao,
    private val preferences: AppPreferences,
) {
    fun observeProfiles(): Flow<List<Profile>> =
        profileDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeActiveProfileId(): Flow<Long?> = preferences.activeProfileId

    fun observeActiveProfile(): Flow<Profile?> =
        combine(profileDao.observeAll(), preferences.activeProfileId) { list, id ->
            val entity = id?.let { pid -> list.find { it.id == pid } }
                ?: list.find { it.isDefault }
                ?: list.firstOrNull()
            entity?.toDomain()
        }

    fun observeAccent(): Flow<Long> =
        combine(observeActiveProfile(), preferences.accentColor) { profile, global ->
            profile?.accentColor ?: profile?.avatarColor ?: global
        }

    suspend fun getActiveProfileId(): Long {
        val active = preferences.activeProfileId.first()
        if (active != null) return active
        val default = profileDao.getDefault()?.id ?: ensureDefaultProfile()
        preferences.setActiveProfileId(default)
        return default
    }

    suspend fun ensureDefaultProfile(): Long {
        val existing = profileDao.getDefault()
        if (existing != null) return existing.id
        val id = profileDao.insert(
            ProfileEntity(
                name = "Default",
                avatarColor = 0xFFC9A227,
                isDefault = true,
                accentColor = 0xFFC9A227,
                subtitleBackground = false,
            ),
        )
        seedCategoryLayoutIfNeeded(id)
        return id
    }

    suspend fun createProfile(name: String, avatarColor: Long): Long {
        val default = profileDao.getDefault()
        val id = profileDao.insert(
            ProfileEntity(
                name = name.trim().ifBlank { "Profile" },
                avatarColor = avatarColor,
                accentColor = avatarColor,
                subtitleLanguage = default?.subtitleLanguage ?: "en",
                subtitleFontSize = default?.subtitleFontSize ?: 1.0f,
                subtitlePosition = default?.subtitlePosition ?: 0.9f,
                subtitleBackground = default?.subtitleBackground ?: false,
            ),
        )
        val defaultId = default?.id
        if (defaultId != null && defaultId != id) {
            copyCategoryOverrides(fromProfileId = defaultId, toProfileId = id)
        }
        seedCategoryLayoutIfNeeded(id)
        return id
    }

    suspend fun renameProfile(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        profileDao.rename(id, trimmed.take(40))
    }

    suspend fun setAccentColor(color: Long) {
        val existing = profileDao.getById(getActiveProfileId()) ?: return
        profileDao.update(existing.copy(accentColor = color, avatarColor = color))
        preferences.setAccentColor(color)
    }

    suspend fun setSubtitleLanguage(language: String) {
        updateActiveProfile { it.copy(subtitleLanguage = language) }
        preferences.setSubtitleLanguage(language)
    }

    suspend fun setSubtitleTextSize(sizeSp: Float) {
        val coerced = sizeSp.coerceIn(14f, 44f)
        updateActiveProfile { it.copy(subtitleFontSize = coerced / 24f) }
        preferences.setSubtitleTextSize(coerced)
    }

    suspend fun setSubtitleBackground(enabled: Boolean) {
        updateActiveProfile { it.copy(subtitleBackground = enabled) }
        preferences.setSubtitleBackgroundBox(enabled)
    }

    suspend fun getActiveSubtitleLanguage(): String =
        profileDao.getById(getActiveProfileId())?.subtitleLanguage ?: "en"

    /**
     * Copy current device-level caption/accent prefs onto profiles that never had a per-profile value.
     * After this, Settings and the player read the active profile.
     */
    suspend fun hydrateProfilesFromDevicePrefs() {
        val globalAccent = preferences.accentColor.first()
        val lang = preferences.subtitleLanguage.first()
        val size = preferences.subtitleTextSize.first()
        val pad = preferences.subtitleBottomPadding.first()
        val box = preferences.subtitleBackgroundBox.first()
        profileDao.observeAll().first().forEach { entity ->
            if (entity.accentColor != null) return@forEach
            profileDao.update(
                entity.copy(
                    accentColor = globalAccent,
                    subtitleLanguage = lang,
                    subtitleFontSize = (size / 24f).coerceIn(0.5f, 2f),
                    subtitlePosition = (1f - pad).coerceIn(0.6f, 1f),
                    subtitleBackground = box,
                ),
            )
        }
    }

    suspend fun setActiveProfile(id: Long) {
        preferences.setActiveProfileId(id)
    }

    suspend fun deleteProfile(id: Long) {
        profileDao.delete(id)
    }

    private suspend fun updateActiveProfile(transform: (ProfileEntity) -> ProfileEntity) {
        val existing = profileDao.getById(getActiveProfileId()) ?: return
        profileDao.update(transform(existing))
    }

    fun observeFavouriteChannelIds(profileId: Long): Flow<List<Int>> =
        favouriteDao.observeByProfile(profileId).map { favs ->
            favs.filter { it.itemType == FavouriteType.CHANNEL.name }
                .mapNotNull { it.itemId.toIntOrNull() }
        }

    suspend fun toggleFavourite(profileId: Long, type: FavouriteType, itemId: String) {
        if (favouriteDao.isFavourite(profileId, type.name, itemId)) {
            favouriteDao.remove(profileId, type.name, itemId)
        } else {
            favouriteDao.insert(
                FavouriteEntity(profileId = profileId, itemType = type.name, itemId = itemId),
            )
        }
    }

    suspend fun isFavourite(profileId: Long, type: FavouriteType, itemId: String): Boolean =
        favouriteDao.isFavourite(profileId, type.name, itemId)

    fun observeHiddenChannelIds(profileId: Long): Flow<Set<Int>> =
        hiddenChannelDao.observeHiddenIds(profileId).map { it.toSet() }

    suspend fun hideChannel(profileId: Long, streamId: Int) {
        hiddenChannelDao.insert(HiddenChannelEntity(profileId = profileId, streamId = streamId))
    }

    fun observeHiddenItemIds(profileId: Long, type: FavouriteType): Flow<Set<String>> =
        hiddenItemDao.observeHiddenIds(profileId, type.name).map { it.toSet() }

    suspend fun hideItem(profileId: Long, type: FavouriteType, itemId: String) {
        hiddenItemDao.insert(HiddenItemEntity(profileId = profileId, itemType = type.name, itemId = itemId))
    }

    suspend fun unhideItem(profileId: Long, type: FavouriteType, itemId: String) {
        hiddenItemDao.unhide(profileId, type.name, itemId)
    }

    suspend fun getPreferExternalSubs(): Boolean =
        profileDao.getById(getActiveProfileId())?.preferExternalSubs == true

    suspend fun setPreferExternalSubs(enabled: Boolean) {
        updateActiveProfile { it.copy(preferExternalSubs = enabled, preferEmbeddedSubs = !enabled) }
    }

    suspend fun getPreferredAudioLanguage(): String =
        profileDao.getById(getActiveProfileId())?.preferredAudioLanguage ?: "en"

    suspend fun setPreferredAudioLanguage(language: String) {
        updateActiveProfile { it.copy(preferredAudioLanguage = language) }
    }

    suspend fun getGroupMembers(groupId: Long): List<Int> = customGroupDao.getMemberStreamIds(groupId)

    fun observeCategoryOverrides(profileId: Long, feedType: FeedType): Flow<List<ProviderCategoryOverrideEntity>> =
        categoryOverrideDao.observeByProfile(profileId, feedType.name)

    suspend fun applyCategoryOverrides(
        profileId: Long,
        feedType: FeedType,
        categories: List<Category>,
    ): List<Category> {
        val overrides = categoryOverrideDao.getByProfile(profileId, feedType.name)
        return presentCategories(categories, overrides)
    }

    /**
     * Per-profile hide/rename/pin/order. If this profile has a manual rail order, keep it.
     * Otherwise rank IPTV lists by this profile's watch time.
     */
    suspend fun presentLiveRails(
        profileId: Long,
        categories: List<Category>,
        channels: List<Channel>,
        watchMs: Map<Int, Long>,
        filter: LiveSourceFilter,
    ): List<Category> {
        val overrides = categoryOverrideDao.getByProfile(profileId, FeedType.LIVE.name)
        val presented = presentCategories(categories, overrides)
        val rankByWatch = overrides.none { it.sortOrder >= 0 }
        return WatchRanking.liveRails(
            presented,
            channels,
            if (rankByWatch) watchMs else emptyMap(),
            filter,
        )
    }

    /**
     * First-time sofa defaults for this profile only. Never rewrites a profile that already
     * has override rows — reordering on profile A must not touch profile B.
     */
    suspend fun seedCategoryLayoutIfNeeded(profileId: Long) {
        if (categoryOverrideDao.countForProfile(profileId) > 0) return
        applySofaCategoryLayout(profileId)
    }

    private suspend fun copyCategoryOverrides(fromProfileId: Long, toProfileId: Long) {
        if (fromProfileId == toProfileId) return
        if (categoryOverrideDao.countForProfile(toProfileId) > 0) return
        FeedType.entries.forEach { feed ->
            val rows = categoryOverrideDao.getByProfile(fromProfileId, feed.name)
            if (rows.isEmpty()) return@forEach
            categoryOverrideDao.upsertAll(
                rows.map { row -> row.copy(id = 0, profileId = toProfileId) },
            )
        }
    }

    suspend fun applySofaCategoryLayout(profileId: Long) {
        resetToSourceOrder(profileId, FeedType.LIVE, CategoryLayout.liveHidden)
        resetToSourceOrder(profileId, FeedType.VOD, emptySet())
        resetToSourceOrder(profileId, FeedType.SERIES, emptySet())
    }

    /** Keep hide/rename; drop sofa sort so Live can rank by most watched. */
    private suspend fun resetToSourceOrder(
        profileId: Long,
        feedType: FeedType,
        hiddenIds: Set<String>,
    ) {
        val existing = categoryOverrideDao.getByProfile(profileId, feedType.name)
            .associateBy { it.providerCategoryId }
        val ids = (existing.keys + hiddenIds).distinct()
        val rows = ids.map { categoryId ->
            val previous = existing[categoryId]
            ProviderCategoryOverrideEntity(
                id = previous?.id ?: 0,
                profileId = profileId,
                feedType = feedType.name,
                providerCategoryId = categoryId,
                displayName = previous?.displayName,
                sortOrder = -1,
                hidden = categoryId in hiddenIds || previous?.hidden == true,
                pinned = previous?.pinned ?: false,
                mergedIntoId = previous?.mergedIntoId,
            )
        }
        if (rows.isNotEmpty()) categoryOverrideDao.upsertAll(rows)
    }

    suspend fun setCategoryOverride(
        profileId: Long,
        feedType: FeedType,
        categoryId: String,
        displayName: String? = null,
        sortOrder: Int? = null,
        hidden: Boolean? = null,
        pinned: Boolean? = null,
        mergedIntoId: String? = null,
        clearMergedInto: Boolean = false,
    ) {
        val existing = categoryOverrideDao.getByProfile(profileId, feedType.name)
            .find { it.providerCategoryId == categoryId }
        categoryOverrideDao.upsert(
            ProviderCategoryOverrideEntity(
                id = existing?.id ?: 0,
                profileId = profileId,
                feedType = feedType.name,
                providerCategoryId = categoryId,
                displayName = displayName ?: existing?.displayName,
                sortOrder = sortOrder ?: existing?.sortOrder ?: -1,
                hidden = hidden ?: existing?.hidden ?: false,
                pinned = pinned ?: existing?.pinned ?: false,
                mergedIntoId = when {
                    clearMergedInto -> null
                    mergedIntoId != null -> mergedIntoId
                    else -> existing?.mergedIntoId
                },
            ),
        )
    }

    suspend fun combineCategory(
        profileId: Long,
        feedType: FeedType,
        sourceCategoryId: String,
        targetCategoryId: String,
    ) {
        if (sourceCategoryId == targetCategoryId) return
        setCategoryOverride(
            profileId,
            feedType,
            sourceCategoryId,
            hidden = true,
            mergedIntoId = targetCategoryId,
        )
    }

    suspend fun uncombineCategory(profileId: Long, feedType: FeedType, sourceCategoryId: String) {
        setCategoryOverride(profileId, feedType, sourceCategoryId, hidden = false, clearMergedInto = true)
    }

    suspend fun mergedInto(profileId: Long, feedType: FeedType, targetCategoryId: String): List<String> {
        return categoryOverrideDao.getByProfile(profileId, feedType.name)
            .filter { it.mergedIntoId == targetCategoryId }
            .map { it.providerCategoryId }
    }

    fun observeCustomGroups(profileId: Long): Flow<List<CustomGroup>> =
        customGroupDao.observeByProfile(profileId).map { list ->
            list.map { CustomGroup(it.id, it.profileId, it.name, it.sortOrder, it.pinned) }
        }

    suspend fun createCustomGroup(profileId: Long, name: String): Long {
        return customGroupDao.insert(
            CustomGroupEntity(profileId = profileId, name = name, sortOrder = 0, pinned = false),
        )
    }

    suspend fun renameCustomGroup(groupId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val existing = customGroupDao.getById(groupId) ?: return
        customGroupDao.update(existing.copy(name = trimmed))
    }

    suspend fun pinCustomGroup(groupId: Long, pinned: Boolean) {
        val existing = customGroupDao.getById(groupId) ?: return
        customGroupDao.update(existing.copy(pinned = pinned))
    }

    suspend fun deleteCustomGroup(groupId: Long) {
        customGroupDao.clearMembers(groupId)
        customGroupDao.delete(groupId)
    }

    suspend fun addChannelToGroup(groupId: Long, streamId: Int) {
        customGroupDao.insertMember(CustomGroupMemberEntity(groupId = groupId, streamId = streamId))
    }

    suspend fun removeChannelFromGroup(groupId: Long, streamId: Int) {
        customGroupDao.removeMember(groupId, streamId)
    }

    fun observeRecentChannels(profileId: Long, limit: Int = 40): Flow<List<Int>> =
        viewHistoryDao.observeRecent(profileId, 40).map { history ->
            history.filter { it.contentType == ContentType.LIVE.name }
                .mapNotNull { it.streamId }
                .distinct()
                .take(limit)
        }

    /** Live channels most recently watched, newest first, with when each was last watched. */
    fun observeRecentLiveWatches(profileId: Long, limit: Int = 10): Flow<List<Pair<Int, Long>>> =
        viewHistoryDao.observeRecent(profileId, 40).map { history ->
            history.filter { it.contentType == ContentType.LIVE.name }
                .mapNotNull { entry -> entry.streamId?.let { it to entry.updatedAt } }
                .distinctBy { it.first }
                .take(limit)
        }

    fun observeLiveWatchMs(profileId: Long): Flow<Map<Int, Long>> =
        viewHistoryDao.observeByType(profileId, ContentType.LIVE.name).map { history ->
            history.mapNotNull { entry ->
                val id = entry.streamId ?: return@mapNotNull null
                id to entry.watchMs
            }.toMap()
        }

    fun observeMostWatchedLiveIds(profileId: Long, limit: Int = 40): Flow<List<Int>> =
        viewHistoryDao.observeByType(profileId, ContentType.LIVE.name).map { history ->
            history
                .filter { it.watchMs > 0 }
                .sortedWith(
                    compareByDescending<ViewHistoryEntity> { it.watchMs }
                        .thenByDescending { it.updatedAt },
                )
                .mapNotNull { it.streamId }
                .distinct()
                .take(limit)
        }

    suspend fun getLiveRailPlaceholders(profileId: Long, streamIds: List<Int>): Map<Int, Channel> {
        val distinct = streamIds.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return distinct.chunked(400)
            .flatMap { chunk ->
                viewHistoryDao.getLiveByStreamIds(profileId, ContentType.LIVE.name, chunk)
            }
            .mapNotNull { entity ->
                val id = entity.streamId ?: return@mapNotNull null
                val title = entity.title.takeIf { it.isNotBlank() && !it.startsWith("live_") }
                    ?: "Channel $id"
                id to Channel(
                    streamId = id,
                    name = title,
                    logoUrl = entity.posterUrl,
                    categoryId = "",
                    epgChannelId = null,
                )
            }
            .toMap()
    }

    suspend fun addLiveWatchTime(streamId: Int, title: String, deltaMs: Long, posterUrl: String? = null) {
        if (deltaMs <= 0) return
        val profileId = getActiveProfileId()
        val contentId = "live_$streamId"
        val existing = viewHistoryDao.get(profileId, contentId)
        val now = System.currentTimeMillis()
        viewHistoryDao.replace(
            ViewHistoryEntity(
                profileId = profileId,
                contentId = contentId,
                contentType = ContentType.LIVE.name,
                title = title,
                posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: existing?.posterUrl,
                streamId = streamId,
                watchCount = existing?.watchCount ?: 1,
                watchMs = (existing?.watchMs ?: 0L) + deltaMs,
                updatedAt = now,
            ),
        )
    }

    suspend fun recordChannelView(
        profileId: Long,
        streamId: Int,
        title: String,
        posterUrl: String? = null,
    ) {
        val contentId = "live_$streamId"
        val existing = viewHistoryDao.get(profileId, contentId)
        val now = System.currentTimeMillis()
        viewHistoryDao.replace(
            ViewHistoryEntity(
                profileId = profileId,
                contentId = contentId,
                contentType = ContentType.LIVE.name,
                title = title,
                posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: existing?.posterUrl,
                streamId = streamId,
                watchCount = existing?.watchCount ?: 1,
                watchMs = existing?.watchMs ?: 0L,
                updatedAt = now,
            ),
        )
    }

    suspend fun saveResume(
        profileId: Long,
        contentId: String,
        contentType: ContentType,
        positionMs: Long,
        durationMs: Long,
        title: String? = null,
        posterUrl: String? = null,
        streamId: Int? = null,
        seasonNum: Int? = null,
        episodeNum: Int? = null,
        year: String? = null,
        seriesId: Int? = null,
        filename: String? = null,
        containerExtension: String? = null,
    ) {
        resumeDao.upsert(
            ResumePositionEntity(
                profileId = profileId,
                contentId = contentId,
                contentType = contentType.name,
                positionMs = positionMs,
                durationMs = durationMs,
            ),
        )
        val label = title?.trim()?.takeIf { it.isNotBlank() }
        if (label != null && contentType != ContentType.LIVE) {
            viewHistoryDao.replace(
                ViewHistoryEntity(
                    profileId = profileId,
                    contentId = contentId,
                    contentType = contentType.name,
                    title = label,
                    posterUrl = posterUrl,
                    streamId = streamId,
                    seasonNum = seasonNum,
                    episodeNum = episodeNum,
                    year = year,
                    seriesId = seriesId,
                    filename = filename,
                    containerExtension = containerExtension,
                ),
            )
        }
    }

    suspend fun getHistory(profileId: Long, contentId: String): ViewHistoryEntity? =
        viewHistoryDao.get(profileId, contentId)

    suspend fun getResumePosition(profileId: Long, contentId: String): Long? =
        resumeDao.get(profileId, contentId)?.positionMs

    suspend fun removeFromContinueWatching(profileId: Long, contentId: String) {
        resumeDao.delete(profileId, contentId)
    }

    suspend fun removeFromAllContinueWatching(contentId: String) {
        resumeDao.deleteForAllProfiles(contentId)
        viewHistoryDao.deleteForAllProfiles(contentId)
    }

    fun observeContinueWatching(profileId: Long): Flow<List<ResumeItem>> =
        combine(
            resumeDao.observeAll(profileId),
            viewHistoryDao.observeRecent(profileId, 500),
            recordingDao.observeAll(),
        ) { positions, history, recordings ->
            val details = history.associateBy { it.contentId }
            val recordingKeys = recordings.map { "rec_${it.id}" }.toSet()
            positions
                .filter {
                    it.positionMs > 0 &&
                        it.durationMs > 0 &&
                        it.positionMs < it.durationMs * 0.95 &&
                        it.contentType != ContentType.LIVE.name &&
                        (!it.contentId.startsWith("rec_") || it.contentId in recordingKeys)
                }
                .map { pos ->
                    val detail = details[pos.contentId]
                    val streamId = pos.contentId.substringAfter('_', "").toIntOrNull()
                    ResumeItem(
                        contentId = pos.contentId,
                        contentType = runCatching { ContentType.valueOf(pos.contentType) }.getOrDefault(ContentType.MOVIE),
                        title = detail?.title?.takeIf { it.isNotBlank() } ?: pos.contentId,
                        posterUrl = detail?.posterUrl,
                        positionMs = pos.positionMs,
                        durationMs = pos.durationMs,
                        streamId = detail?.streamId ?: streamId,
                        seasonNum = detail?.seasonNum,
                        episodeNum = detail?.episodeNum,
                        year = detail?.year,
                        seriesId = detail?.seriesId,
                        filename = detail?.filename,
                        containerExtension = detail?.containerExtension,
                    )
                }
        }

    companion object {
        fun presentCategories(
            categories: List<Category>,
            overrides: List<ProviderCategoryOverrideEntity>,
        ): List<Category> {
            val map = overrides.associateBy { it.providerCategoryId }
            return categories
                .filter { cat ->
                    val ov = map[cat.id]
                    ov?.hidden != true && ov?.mergedIntoId.isNullOrBlank()
                }
                .map { cat ->
                    val name = map[cat.id]?.displayName
                    if (!name.isNullOrBlank()) cat.copy(name = name) else cat
                }
                .sortedWith(
                    compareBy<Category> { map[it.id]?.pinned != true }
                        .thenBy { ovSort ->
                            val overrideSort = map[ovSort.id]?.sortOrder
                            if (overrideSort != null && overrideSort >= 0) overrideSort else ovSort.sortOrder
                        }
                        .thenBy { it.name },
                )
        }
    }
}
