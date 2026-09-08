package com.iptv.tv.data.repository

import com.iptv.tv.data.api.XtreamApi
import com.iptv.tv.data.api.XtreamUrlBuilder
import com.iptv.tv.data.credentials.CredentialsStore
import com.iptv.tv.data.db.CategoryDao
import com.iptv.tv.data.db.ChannelDao
import com.iptv.tv.data.db.ChannelEpgOverrideDao
import com.iptv.tv.data.db.EpisodeDao
import com.iptv.tv.data.db.SeriesDao
import com.iptv.tv.data.db.VodDao
import com.iptv.tv.data.db.AppDatabase
import com.iptv.tv.data.preferences.AppPreferences
import androidx.room.withTransaction
import com.iptv.tv.domain.model.CatchupAvailability
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ChannelSource
import com.iptv.tv.domain.model.Episode
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.domain.model.SearchResult
import com.iptv.tv.domain.model.SearchResultType
import com.iptv.tv.domain.model.SeriesDetail
import com.iptv.tv.domain.model.SeriesItem
import com.iptv.tv.domain.model.ServerCredentials
import com.iptv.tv.domain.model.TitleMetadata
import com.iptv.tv.domain.model.UserInfo
import com.iptv.tv.domain.model.VodItem
import com.iptv.tv.player.SeriesEpisodeOrder
import com.iptv.tv.player.VlcEngine
import com.iptv.tv.util.TitleMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedPlayback(
    val url: String,
    val fallbackUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val containerExtension: String = "mp4",
    val isFree: Boolean = false,
)

@Singleton
class IptvRepository @Inject constructor(
    private val api: XtreamApi,
    private val credentialsStore: CredentialsStore,
    private val channelDao: ChannelDao,
    private val epgOverrideDao: ChannelEpgOverrideDao,
    private val categoryDao: CategoryDao,
    private val vodDao: VodDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val database: AppDatabase,
    private val iptvOrgCatalog: IptvOrgCatalog,
    private val archiveOrgCatalog: ArchiveOrgCatalog,
    private val tvLogosCatalog: TvLogosCatalog,
    private val channelLogoResolver: ChannelLogoResolver,
    private val preferences: AppPreferences,
) {
    private val catalogMutex = Mutex()
    private val archiveMutex = Mutex()
    /** Single-flight guards: the launcher and the periodic workers may ask at the same moment. */
    private val archiveRefreshMutex = Mutex()
    private val orgRefreshMutex = Mutex()
    private val _freeVodStatus = MutableStateFlow(FreeVodLoadState.LOADING)
    private val _freeVodError = MutableStateFlow<String?>(null)
    private val _providerStatus = MutableStateFlow(ProviderLoadState.IDLE)
    private val _providerError = MutableStateFlow<String?>(null)

    val freeVodStatus: StateFlow<FreeVodLoadState> = _freeVodStatus.asStateFlow()
    val freeVodError: StateFlow<String?> = _freeVodError.asStateFlow()
    /** Last IPTV-account catalogue refresh, so Movies/Series can say "failed, retry" instead of "loading" forever. */
    val providerStatus: StateFlow<ProviderLoadState> = _providerStatus.asStateFlow()
    val providerError: StateFlow<String?> = _providerError.asStateFlow()

    fun getCredentials(): ServerCredentials? = credentialsStore.getCredentials()

    fun hasCredentials(): Boolean = credentialsStore.hasCredentials()

    suspend fun authenticate(creds: ServerCredentials): Result<UserInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val response = try {
                api.authenticate(XtreamUrlBuilder.authUrl(creds))
            } catch (error: kotlinx.serialization.SerializationException) {
                throw IllegalStateException("The server sent an unexpected reply. Check the server address.", error)
            } catch (error: java.io.IOException) {
                throw IllegalStateException("Could not reach the server. Check the address and your connection.", error)
            }
            val userInfo = response.userInfo ?: throw IllegalStateException("No user info returned")
            if (userInfo.authInt() != 1) throw IllegalStateException("Wrong username or password.")
            credentialsStore.saveCredentials(creds)
            credentialsStore.saveMaxConnections(userInfo.maxConnectionsInt())
            persistServerTimeZone(response.serverInfo)
            UserInfo(
                auth = userInfo.authInt(),
                username = userInfo.username ?: creds.username,
                maxConnections = userInfo.maxConnectionsInt(),
                status = userInfo.status ?: "Active",
            )
        }
    }

    /** Streams the provider's panel counts as open on the main line right now, and the line's limit. */
    suspend fun connectionSnapshot(): ConnectionSnapshot? {
        val creds = credentialsStore.getCredentials() ?: return null
        return lineStatus(creds)
    }

    /** The panel's view of one login: reachable, valid, streams in use, streams allowed. */
    suspend fun lineStatus(creds: ServerCredentials): ConnectionSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.authenticate(XtreamUrlBuilder.authUrl(creds))
            persistServerTimeZone(response.serverInfo)
            val info = response.userInfo ?: return@runCatching null
            ConnectionSnapshot(
                active = info.activeConsInt(),
                max = info.maxConnectionsInt(),
                valid = info.authInt() == 1,
                status = info.status ?: "Active",
            )
        }.getOrNull()
    }

    /** A live stream URL for any login on the main server (Multi's borrowed lines). */
    fun livePlaybackUrl(channel: Channel, creds: ServerCredentials): String {
        // Provider channels are always built from the borrowed login; a cached URL would carry
        // the main login and put the screen back on the main line.
        if (channel.source == ChannelSource.XC) return XtreamUrlBuilder.liveStreamUrl(creds, channel.streamId)
        return livePlaybackUrl(channel)
    }

    data class ConnectionSnapshot(
        val active: Int?,
        val max: Int,
        val valid: Boolean = true,
        val status: String = "Active",
    ) {
        /** Nobody is streaming on this line right now, and the line can stream. */
        val free: Boolean get() = valid && status.equals("Active", ignoreCase = true) && (active ?: 0) == 0
    }

    suspend fun refreshLiveFeed(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val creds = requireCreds()
            val categories = api.getLiveCategories(XtreamUrlBuilder.liveCategoriesUrl(creds))
                .mapNotNull { it.toCategory(FeedType.LIVE) }
                .distinctBy { it.id }
                .mapIndexed { index, cat -> cat.copy(sortOrder = index, source = ChannelSource.XC) }
            val channels = api.getLiveStreams(XtreamUrlBuilder.liveStreamsUrl(creds))
                .mapNotNull { it.toChannel() }
                .map { it.copy(source = ChannelSource.XC) }
                .distinctBy { it.streamId }
            runCatching { tvLogosCatalog.download(force = false) }
            catalogMutex.withLock {
                // The user may have signed out while the download ran; never resurrect their lists.
                if (!hasCredentials()) return@withContext
                writeLiveCatalog(categories, channels, currentOrgSnapshot())
            }
        }
    }

    suspend fun refreshIptvOrgFeed(force: Boolean = false): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { orgRefreshMutex.withLock { refreshIptvOrgFeedLocked(force) } }
    }

    private suspend fun refreshIptvOrgFeedLocked(force: Boolean) {
        run {
            val last = preferences.iptvOrgFetchedAt.first()
            val cacheOk = iptvOrgCatalog.hasCache()
            val indexReady = iptvOrgCatalog.hasLogoIndex()
            val fresh = !force && cacheOk && last > 0L &&
                System.currentTimeMillis() - last < IPTV_ORG_TTL_MS &&
                channelDao.getBySource(ChannelSource.IPTV_ORG).isNotEmpty() &&
                indexReady
            runCatching { tvLogosCatalog.download(force = force) }
            if (fresh) {
                writeFastIfNeeded()
                return@run
            }
            if (!cacheOk || force || last == 0L || System.currentTimeMillis() - last >= IPTV_ORG_TTL_MS) {
                iptvOrgCatalog.download(force = force || !cacheOk)
                preferences.setIptvOrgFetchedAt(System.currentTimeMillis())
            }
            val snapshot = iptvOrgCatalog.parse()
            catalogMutex.withLock {
                val xcCategories = categoryDao.getByFeedAndSource(FeedType.LIVE.name, ChannelSource.XC)
                    .map { it.toDomain() }
                val xcChannels = channelDao.getBySource(ChannelSource.XC).map { it.toDomain() }
                writeLiveCatalog(xcCategories, xcChannels, snapshot)
            }
            writeFastCatalog(iptvOrgCatalog.fastVod())
        }
    }

    private suspend fun currentOrgSnapshot(): IptvOrgSnapshot {
        // Never parse iptv-org JSON here. A cache miss must stay cheap so XC refresh
        // cannot stall the UI; refreshIptvOrgFeed() is the only parse path.
        return IptvOrgSnapshot(
            categories = categoryDao.getByFeedAndSource(FeedType.LIVE.name, ChannelSource.IPTV_ORG)
                .map { it.toDomain() },
            channels = channelDao.getBySource(ChannelSource.IPTV_ORG).map { it.toDomain() },
        )
    }

    private suspend fun writeLiveCatalog(
        xcCategories: List<Category>,
        xcChannels: List<Channel>,
        org: IptvOrgSnapshot,
    ) {
        val logoIndex = iptvOrgCatalog.logoIndex()
            .plus(tvLogosCatalog.logoIndex())
            .plus(ChannelLogoIndex.fromChannels(org.channels))
        channelLogoResolver.update(logoIndex)
        val merged = LiveCatalogMerger.merge(
            xcCategories = xcCategories,
            xcChannels = xcChannels,
            orgChannels = org.channels,
            orgCategories = org.categories,
            logoIndex = logoIndex,
        )
        val categories = merged.categories.distinctBy { it.id }
        val channels = merged.channels.distinctBy { it.streamId }
        database.withTransaction {
            categoryDao.clearByFeedType(FeedType.LIVE.name)
            channelDao.clearAll()
            if (categories.isNotEmpty()) {
                categoryDao.insertAll(categories.map { it.toEntity() })
            }
            channels.mapIndexed { index, channel -> channel.toEntity(index) }
                .chunked(300)
                .forEach { batch -> channelDao.insertAll(batch) }
        }
    }

    suspend fun clearProviderCatalog() = withContext(Dispatchers.IO) {
        _providerStatus.value = ProviderLoadState.IDLE
        _providerError.value = null
        catalogMutex.withLock {
            database.withTransaction {
            channelDao.deleteBySource(ChannelSource.XC)
            categoryDao.deleteByFeedAndSource(FeedType.LIVE.name, ChannelSource.XC)
            categoryDao.deleteByFeedAndSource(FeedType.VOD.name, ChannelSource.XC)
            categoryDao.deleteByFeedAndSource(FeedType.SERIES.name, ChannelSource.XC)
            vodDao.deleteBySource(ChannelSource.XC)
            seriesDao.deleteBySource(ChannelSource.XC)
            }
        }
    }

    suspend fun refreshVodFeed(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
        val creds = requireCreds()
        val categories = api.getVodCategories(XtreamUrlBuilder.vodCategoriesUrl(creds))
            .mapNotNull { it.toCategory(FeedType.VOD) }
            .mapIndexed { index, cat -> cat.copy(sortOrder = index, source = ChannelSource.XC) }
        val items = api.getVodStreams(XtreamUrlBuilder.vodStreamsUrl(creds))
            .mapNotNull { it.toVodItem() }

        catalogMutex.withLock {
            if (!hasCredentials()) return@withContext
            database.withTransaction {
                categoryDao.deleteByFeedAndSource(FeedType.VOD.name, ChannelSource.XC)
                vodDao.deleteBySource(ChannelSource.XC)
                if (categories.isNotEmpty()) categoryDao.insertAll(categories.map { it.toEntity() })
                vodDao.insertAll(items.mapIndexed { index, item -> item.toEntity(index) })
            }
        }
        }
    }

    suspend fun refreshSeriesFeed(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
        val creds = requireCreds()
        val categories = api.getSeriesCategories(XtreamUrlBuilder.seriesCategoriesUrl(creds))
            .mapNotNull { it.toCategory(FeedType.SERIES) }
            .mapIndexed { index, cat -> cat.copy(sortOrder = index, source = ChannelSource.XC) }
        val items = api.getSeries(XtreamUrlBuilder.seriesUrl(creds))
            .mapNotNull { it.toSeriesItem() }

        catalogMutex.withLock {
            if (!hasCredentials()) return@withContext
            database.withTransaction {
                categoryDao.deleteByFeedAndSource(FeedType.SERIES.name, ChannelSource.XC)
                seriesDao.deleteBySource(ChannelSource.XC)
                if (categories.isNotEmpty()) categoryDao.insertAll(categories.map { it.toEntity() })
                seriesDao.insertAll(items.mapIndexed { index, it -> it.toEntity(index) })
            }
        }
        }
    }

    suspend fun ensureArchiveCatalog() = withContext(Dispatchers.IO) {
        archiveMutex.withLock { ensureArchiveCatalogLocked() }
    }

    suspend fun refreshArchiveVodFeed(force: Boolean = false): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { archiveRefreshMutex.withLock { refreshArchiveVodFeedLocked(force) } }
    }

    private suspend fun refreshArchiveVodFeedLocked(force: Boolean) {
        run {
            val roomOk = archiveMutex.withLock { ensureArchiveCatalogLocked() }
            val last = preferences.archiveFetchedAt.first()
            val cacheOk = archiveOrgCatalog.hasCache()
            val now = System.currentTimeMillis()
            val ttlOk = last > 0L && now - last < ARCHIVE_TTL_MS
            if (!force && cacheOk && roomOk && ttlOk) {
                markFreeVodReady()
                return@run
            }
            if (!roomOk) {
                _freeVodStatus.value = FreeVodLoadState.LOADING
                _freeVodError.value = null
            }
            try {
                archiveOrgCatalog.download(force = force || !ttlOk || !cacheOk)
                val snapshot = archiveOrgCatalog.parse()
                archiveMutex.withLock {
                    writeArchiveCatalog(snapshot)
                    preferences.setArchiveFetchedAt(now)
                    markFreeVodReady()
                }
            } catch (error: Throwable) {
                val kept = archiveMutex.withLock { roomOk || ensureArchiveCatalogLocked() }
                if (kept) {
                    markFreeVodReady()
                } else {
                    _freeVodStatus.value = FreeVodLoadState.ERROR
                    _freeVodError.value = error.message?.takeIf { it.isNotBlank() }
                        ?: "Could not load free films. Check the network and retry."
                }
                throw error
            }
        }
    }

    private suspend fun ensureArchiveCatalogLocked(): Boolean {
        writeFastIfNeeded()
        val already = freeVodRowsPresent()
        if (already) {
            markFreeVodReady()
            return true
        }
        _freeVodStatus.value = FreeVodLoadState.LOADING
        val snapshot = archiveOrgCatalog.ensureLocalSnapshot()
        if (snapshot != null && (snapshot.movies.isNotEmpty() || snapshot.series.isNotEmpty())) {
            writeArchiveCatalog(snapshot)
            writeFastIfNeeded()
            markFreeVodReady()
            return true
        }
        return freeVodRowsPresent()
    }

    private suspend fun writeFastIfNeeded() {
        if (vodDao.getBySource(ChannelSource.IPTV_ORG, 1).isNotEmpty() ||
            seriesDao.getBySource(ChannelSource.IPTV_ORG, 1).isNotEmpty()
        ) {
            return
        }
        if (!iptvOrgCatalog.hasCache()) return
        writeFastCatalog(iptvOrgCatalog.fastVod())
    }

    private suspend fun writeFastCatalog(snapshot: FreeFastSnapshot) {
        if (snapshot.isEmpty) return
        database.withTransaction {
            categoryDao.deleteByFeedAndSource(FeedType.VOD.name, ChannelSource.IPTV_ORG)
            categoryDao.deleteByFeedAndSource(FeedType.SERIES.name, ChannelSource.IPTV_ORG)
            vodDao.deleteBySource(ChannelSource.IPTV_ORG)
            seriesDao.deleteBySource(ChannelSource.IPTV_ORG)
            episodeDao.deleteBySource(ChannelSource.IPTV_ORG)
            val categories = snapshot.movieCategories + snapshot.seriesCategories
            if (categories.isNotEmpty()) {
                categoryDao.insertAll(categories.map { it.toEntity() })
            }
            if (snapshot.movies.isNotEmpty()) {
                vodDao.insertAll(snapshot.movies.mapIndexed { index, item -> item.toEntity(index) })
            }
            if (snapshot.series.isNotEmpty()) {
                seriesDao.insertAll(snapshot.series.mapIndexed { index, item -> item.toEntity(index) })
            }
            if (snapshot.episodes.isNotEmpty()) {
                episodeDao.insertAll(
                    snapshot.episodes.mapIndexed { index, (seriesId, episode) ->
                        episode.toEntity(seriesId, index)
                    },
                )
            }
        }
    }

    private suspend fun freeVodRowsPresent(): Boolean =
        vodDao.getBySource(ChannelSource.ARCHIVE, 1).isNotEmpty() ||
            seriesDao.getBySource(ChannelSource.ARCHIVE, 1).isNotEmpty() ||
            vodDao.getBySource(ChannelSource.IPTV_ORG, 1).isNotEmpty() ||
            seriesDao.getBySource(ChannelSource.IPTV_ORG, 1).isNotEmpty()

    private fun markFreeVodReady() {
        _freeVodStatus.value = FreeVodLoadState.READY
        _freeVodError.value = null
    }

    private suspend fun writeArchiveCatalog(snapshot: ArchiveVodSnapshot) {
        database.withTransaction {
            categoryDao.deleteByFeedAndSource(FeedType.VOD.name, ChannelSource.ARCHIVE)
            categoryDao.deleteByFeedAndSource(FeedType.SERIES.name, ChannelSource.ARCHIVE)
            vodDao.deleteBySource(ChannelSource.ARCHIVE)
            seriesDao.deleteBySource(ChannelSource.ARCHIVE)
            episodeDao.deleteBySource(ChannelSource.ARCHIVE)
            val categories = snapshot.movieCategories + snapshot.seriesCategories
            if (categories.isNotEmpty()) {
                categoryDao.insertAll(categories.map { it.toEntity() })
            }
            if (snapshot.movies.isNotEmpty()) {
                vodDao.insertAll(snapshot.movies.mapIndexed { index, item -> item.toEntity(index) })
            }
            if (snapshot.series.isNotEmpty()) {
                seriesDao.insertAll(snapshot.series.mapIndexed { index, item -> item.toEntity(index) })
            }
            if (snapshot.episodes.isNotEmpty()) {
                episodeDao.insertAll(
                    snapshot.episodes.mapIndexed { index, (seriesId, episode) ->
                        episode.toEntity(seriesId, index)
                    },
                )
            }
        }
    }

    fun observeLiveCategories(): Flow<List<Category>> =
        categoryDao.observeByFeedType(FeedType.LIVE.name)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    fun observeLiveChannels(): Flow<List<Channel>> =
        channelDao.observeAll()
            .map { list ->
                val ov = epgOverrides.get()
                list.distinctBy { it.streamId }.map { applyEpgOverride(it.toDomain(), ov) }
            }
            .flowOn(Dispatchers.Default)

    fun observeChannelCount(): Flow<Int> = channelDao.observeCount()

    fun observeChannelsByCategory(categoryId: String): Flow<List<Channel>> =
        channelDao.observeByCategory(categoryId)
            .map { list ->
                val ov = epgOverrides.get()
                list.map { applyEpgOverride(it.toDomain(), ov) }
            }
            .flowOn(Dispatchers.Default)

    suspend fun getChannel(streamId: Int): Channel? =
        channelDao.getById(streamId)?.let { applyEpgOverride(it.toDomain()) }

    suspend fun getChannelsByIds(ids: List<Int>): List<Channel> = withContext(Dispatchers.IO) {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return@withContext emptyList()
        distinct.chunked(400)
            .flatMap { chunk -> channelDao.getByIds(chunk) }
            .distinctBy { it.streamId }
            .toDomainWithEpg()
    }

    suspend fun getChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        if (categoryId.isBlank()) return@withContext emptyList()
        channelDao.getByCategory(categoryId).distinctBy { it.streamId }.toDomainWithEpg()
    }

    /** IPTV live streams the panel marked `tv_archive` — not iptv-org FREE. */
    suspend fun getArchiveChannels(): List<Channel> = withContext(Dispatchers.IO) {
        if (!hasCredentials()) return@withContext emptyList()
        channelDao.getWithTvArchive(ChannelSource.XC)
            .distinctBy { it.streamId }
            .toDomainWithEpg()
            .filter { CatchupAvailability.isArchiveChannel(it, hasIptvLogin = true) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Xtream catch-up listings for one archive channel (`get_simple_data_table`).
     * Empty when the panel has no dedicated table — callers then use local EPG.
     */
    suspend fun getCatchupTable(channel: Channel): List<Programme> {
        val creds = credentialsStore.getCredentials() ?: return emptyList()
        return runCatching {
            api.getShortEpg(XtreamUrlBuilder.simpleDataTableUrl(creds, channel.streamId))
                .epgListings
                ?.mapNotNull {
                    it.toProgramme(
                        channel.epgChannelId?.takeIf { id -> id.isNotBlank() } ?: channel.streamId.toString(),
                        channel.streamId,
                    )
                }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun getLiveCategories(): List<Category> = withContext(Dispatchers.IO) {
        categoryDao.getByFeedType(FeedType.LIVE.name).map { it.toDomain() }
    }

    suspend fun liveCategoryIdsWithEpg(epgIds: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        if (epgIds.isEmpty()) return@withContext emptySet()
        epgIds.distinct().chunked(400).flatMap { batch ->
            channelDao.distinctCategoryIdsForEpg(batch)
        }.toSet()
    }

    suspend fun getShortEpg(streamId: Int, limit: Int = 4): List<Programme> {
        val creds = credentialsStore.getCredentials() ?: return emptyList()
        val channel = channelDao.getById(streamId)
        return runCatching {
            api.getShortEpg(XtreamUrlBuilder.shortEpgUrl(creds, streamId, limit))
                .epgListings
                ?.mapNotNull { it.toProgramme(channel?.epgChannelId ?: streamId.toString(), streamId) }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun getSeriesDetail(seriesId: Int): SeriesDetail {
        val cached = seriesDao.getById(seriesId)?.toDomain()
        if (cached?.isFree == true) {
            val episodes = episodeDao.getBySeriesId(seriesId).map { it.toDomain() }
            return SeriesDetail(
                seriesId = seriesId,
                name = cached.name,
                posterUrl = cached.posterUrl,
                plot = cached.plot,
                seasons = SeriesEpisodeOrder.sortedSeasons(episodes.groupBy { it.seasonNum }),
            )
        }
        val creds = requireCreds()
        val response = api.getSeriesInfo(XtreamUrlBuilder.seriesInfoUrl(creds, seriesId))
        val seasons = SeriesEpisodeOrder.sortedSeasons(
            response.episodes?.mapKeys { (key, _) -> key.toIntOrNull() ?: 1 }
                ?.mapValues { (_, episodes) -> episodes.mapNotNull { it.toEpisode() } }
                ?: emptyMap(),
        )
        return SeriesDetail(
            seriesId = seriesId,
            name = response.info?.name ?: cached?.name ?: "Series",
            posterUrl = response.info?.cover ?: cached?.posterUrl,
            plot = response.info?.plot ?: cached?.plot,
            seasons = seasons,
        )
    }

    suspend fun getVodItem(streamId: Int): VodItem? = vodDao.getById(streamId)?.toDomain()

    suspend fun getVodMetadata(streamId: Int): TitleMetadata {
        val cached = vodDao.getById(streamId)?.toDomain()
        if (cached?.isFree == true) {
            return TitleMetadata(name = cached.name, year = cached.year)
        }
        val remote = runCatching {
            api.getVodInfo(XtreamUrlBuilder.vodInfoUrl(requireCreds(), streamId))
        }.getOrNull()
        val info = remote?.info
        val movie = remote?.movieData
        return TitleMetadata(
            name = info?.name?.takeIf { it.isNotBlank() } ?: movie?.name ?: cached?.name,
            year = yearOf(info?.year) ?: yearOf(movie?.year) ?: yearOf(info?.releaseDate) ?: cached?.year,
            imdbId = normalizeImdb(info?.imdbId.asLooseId()),
            tmdbId = info?.tmdbId.asLooseId(),
        )
    }

    suspend fun getSeriesMetadata(seriesId: Int): TitleMetadata {
        val cached = seriesDao.getById(seriesId)?.toDomain()
        if (cached?.isFree == true) {
            return TitleMetadata(name = cached.name)
        }
        val remote = runCatching {
            api.getSeriesInfo(XtreamUrlBuilder.seriesInfoUrl(requireCreds(), seriesId))
        }.getOrNull()?.info
        return TitleMetadata(
            name = remote?.name?.takeIf { it.isNotBlank() } ?: cached?.name,
            year = yearOf(remote?.year),
            imdbId = normalizeImdb(remote?.imdbId.asLooseId()),
            tmdbId = remote?.tmdbId.asLooseId(),
        )
    }

    fun observeVodCategories(): Flow<List<Category>> =
        categoryDao.observeByFeedType(FeedType.VOD.name)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    fun observeVodByCategory(categoryId: String): Flow<List<VodItem>> =
        vodDao.observeByCategory(categoryId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    fun observeSeriesCategories(): Flow<List<Category>> =
        categoryDao.observeByFeedType(FeedType.SERIES.name)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    fun observeSeriesByCategory(categoryId: String): Flow<List<SeriesItem>> =
        seriesDao.observeByCategory(categoryId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    fun livePlaybackUrl(channel: Channel): String {
        channel.streamUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val creds = credentialsStore.getCredentials()
        if (creds != null && channel.source != ChannelSource.IPTV_ORG) {
            return XtreamUrlBuilder.liveStreamUrl(creds, channel.streamId)
        }
        channel.fallbackUrl?.takeIf { it.isNotBlank() }?.let { return it }
        throw IllegalStateException("No stream for ${channel.name}")
    }

    fun buildLiveUrl(streamId: Int, ext: String = "ts"): String {
        val creds = credentialsStore.getCredentials()
        if (creds != null) return XtreamUrlBuilder.liveStreamUrl(creds, streamId, ext)
        throw IllegalStateException("Not logged in")
    }

    fun buildVodUrl(streamId: Int, ext: String = "mp4"): String {
        val creds = requireCreds()
        return XtreamUrlBuilder.vodStreamUrl(creds, streamId, ext)
    }

    fun buildEpisodeUrl(streamId: Int, ext: String): String {
        val creds = requireCreds()
        return XtreamUrlBuilder.episodeStreamUrl(creds, streamId, ext)
    }

    suspend fun resolveVodPlayback(streamId: Int, ext: String = "mp4"): ResolvedPlayback =
        withContext(Dispatchers.IO) {
            val item = vodDao.getById(streamId)?.toDomain()
            if (item?.source == ChannelSource.IPTV_ORG) {
                freeLivePlayback(item.externalId, item.streamUrl, item.fallbackUrl, item.containerExtension)
            } else if (item?.isFree == true) {
                archivePlayback(item.externalId, item.streamUrl, item.fallbackUrl, item.containerExtension) { pick ->
                    vodDao.updatePlayback(streamId, pick.url, pick.fallbackUrl, pick.containerExtension)
                }
            } else {
                ResolvedPlayback(
                    url = buildVodUrl(streamId, item?.containerExtension ?: ext),
                    containerExtension = item?.containerExtension ?: ext,
                )
            }
        }

    suspend fun resolveEpisodePlayback(streamId: Int, ext: String = "mp4"): ResolvedPlayback =
        withContext(Dispatchers.IO) {
            val episode = episodeDao.getById(streamId)?.toDomain()
            if (episode?.source == ChannelSource.IPTV_ORG) {
                freeLivePlayback(episode.externalId, episode.streamUrl, episode.fallbackUrl, episode.containerExtension)
            } else if (episode?.isFree == true) {
                archivePlayback(episode.externalId, episode.streamUrl, episode.fallbackUrl, episode.containerExtension) { pick ->
                    episodeDao.updatePlayback(streamId, pick.url, pick.fallbackUrl, pick.containerExtension)
                }
            } else {
                ResolvedPlayback(
                    url = buildEpisodeUrl(streamId, episode?.containerExtension ?: ext),
                    containerExtension = episode?.containerExtension ?: ext,
                )
            }
        }

    private suspend fun freeLivePlayback(
        externalId: String?,
        cachedUrl: String?,
        cachedFallback: String?,
        cachedExt: String?,
    ): ResolvedPlayback {
        val url = cachedUrl?.takeIf { it.isNotBlank() }
            ?: error("No stream for this free channel")
        val live = externalId?.let { channelDao.getByExternalId(it)?.toDomain() }
        val headers = linkedMapOf<String, String>()
        headers["User-Agent"] = live?.userAgent?.takeIf { it.isNotBlank() } ?: VlcEngine.DEFAULT_UA
        live?.referrer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        return ResolvedPlayback(
            url = url,
            fallbackUrl = cachedFallback ?: live?.fallbackUrl,
            headers = headers,
            containerExtension = cachedExt ?: "m3u8",
            isFree = true,
        )
    }

    private suspend fun archivePlayback(
        identifier: String?,
        cachedUrl: String?,
        cachedFallback: String?,
        cachedExt: String?,
        persist: suspend (ArchivePlaybackPick) -> Unit,
    ): ResolvedPlayback {
        val id = identifier?.takeIf { it.isNotBlank() } ?: error("Missing Archive identifier")
        val pick = if (!cachedUrl.isNullOrBlank()) {
            ArchivePlaybackPick(cachedUrl, cachedFallback, cachedExt ?: "mp4")
        } else {
            archiveOrgCatalog.resolvePlayback(id) ?: error("No playable file on Archive.org")
        }
        if (cachedUrl.isNullOrBlank()) persist(pick)
        return ResolvedPlayback(
            url = pick.url,
            fallbackUrl = pick.fallbackUrl,
            headers = archiveHeaders(id, pick.url),
            containerExtension = pick.containerExtension,
            isFree = true,
        )
    }

    /**
     * Movies matching [query], closest first. Uses LIKE stems so the database does the
     * narrowing, then [TitleMatch] ranks the survivors; large catalogues never leave SQLite.
     */
    suspend fun searchVod(query: String, limit: Int = 60): List<VodItem> = withContext(Dispatchers.IO) {
        val stems = TitleMatch.stems(query)
        if (stems.isEmpty()) return@withContext emptyList()
        rankByTitle(
            stems.flatMap { vodDao.search(it) }.distinctBy { it.streamId }.map { it.toDomain() },
            query,
            limit,
        ) { it.name }
    }

    suspend fun searchSeries(query: String, limit: Int = 60): List<SeriesItem> = withContext(Dispatchers.IO) {
        val stems = TitleMatch.stems(query)
        if (stems.isEmpty()) return@withContext emptyList()
        rankByTitle(
            stems.flatMap { seriesDao.search(it) }.distinctBy { it.seriesId }.map { it.toDomain() },
            query,
            limit,
        ) { it.name }
    }

    private fun <T> rankByTitle(items: List<T>, query: String, limit: Int, title: (T) -> String): List<T> =
        items.asSequence()
            .map { it to TitleMatch.score(query, title(it)) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { title(it.first).lowercase() })
            .take(limit)
            .map { it.first }
            .toList()

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val stems = TitleMatch.stems(query)
        if (stems.isEmpty()) return@withContext emptyList()
        val results = mutableListOf<SearchResult>()
        stems.flatMap { channelDao.search(it) }.distinctBy { it.streamId }.forEach { entity ->
            val ch = entity.toDomain()
            results += SearchResult(
                type = SearchResultType.CHANNEL,
                id = ch.streamId.toString(),
                title = ch.name,
                subtitle = if (ch.isFree) "Free" else null,
                imageUrl = ch.logoUrl,
                streamId = ch.streamId,
                adult = com.iptv.tv.domain.model.AdultContent.isAdultChannel(ch),
            )
        }
        stems.flatMap { vodDao.search(it) }.distinctBy { it.streamId }.forEach { entity ->
            val item = entity.toDomain()
            results += SearchResult(
                type = SearchResultType.MOVIE,
                id = item.streamId.toString(),
                title = item.name,
                subtitle = listOfNotNull(item.year, if (item.isFree) "Free" else null)
                    .joinToString(" · ")
                    .ifBlank { null },
                imageUrl = item.posterUrl,
                streamId = item.streamId,
                year = item.year,
                adult = com.iptv.tv.domain.model.AdultContent.isAdultVod(item),
            )
        }
        stems.flatMap { seriesDao.search(it) }.distinctBy { it.seriesId }.forEach { entity ->
            val item = entity.toDomain()
            results += SearchResult(
                type = SearchResultType.SERIES,
                id = item.seriesId.toString(),
                title = item.name,
                subtitle = listOfNotNull(item.rating, if (item.isFree) "Free" else null)
                    .joinToString(" · ")
                    .ifBlank { null },
                imageUrl = item.posterUrl,
                adult = com.iptv.tv.domain.model.AdultContent.isAdultSeries(item),
            )
        }
        results.distinctBy { "${it.type}_${it.id}" }
    }

    fun getMaxConnections(): Int = credentialsStore.getMaxConnections()

    fun timeshiftUrl(channel: Channel, startMs: Long, durationMinutes: Int): String {
        val creds = requireCreds()
        return XtreamUrlBuilder.timeshiftUrl(
            creds,
            channel.streamId,
            startMs,
            durationMinutes,
            credentialsStore.getServerTimeZoneId(),
        )
    }

    suspend fun setEpgChannelId(streamId: Int, epgChannelId: String?) {
        val trimmed = epgChannelId?.trim().orEmpty()
        if (trimmed.isEmpty()) epgOverrideDao.delete(streamId)
        else epgOverrideDao.upsert(com.iptv.tv.data.db.ChannelEpgOverrideEntity(streamId, trimmed))
        loadEpgOverrides()
    }

    suspend fun recentlyAddedMovies(limit: Int = 16): List<VodItem> = withContext(Dispatchers.IO) {
        val xc = pickRecentCategory(FeedType.VOD, ChannelSource.XC)?.let { cat ->
            vodDao.getByCategory(cat.id, limit).map { it.toDomain() }
        }.orEmpty()
        val fast = vodDao.getBySource(ChannelSource.IPTV_ORG, limit).map { it.toDomain() }
        val archive = vodDao.getBySource(ChannelSource.ARCHIVE, limit).map { it.toDomain() }
        val free = (fast + archive.filter { item -> fast.none { it.streamId == item.streamId } })
        if (!hasCredentials()) return@withContext free.take(limit)
        (xc + free.filter { item -> xc.none { it.streamId == item.streamId } }).take(limit)
    }

    suspend fun recentlyAddedSeries(limit: Int = 16): List<SeriesItem> = withContext(Dispatchers.IO) {
        val xc = pickRecentCategory(FeedType.SERIES, ChannelSource.XC)?.let { cat ->
            seriesDao.getByCategory(cat.id, limit).map { it.toDomain() }
        }.orEmpty()
        val fast = seriesDao.getBySource(ChannelSource.IPTV_ORG, limit).map { it.toDomain() }
        val archive = seriesDao.getBySource(ChannelSource.ARCHIVE, limit).map { it.toDomain() }
        val free = (fast + archive.filter { item -> fast.none { it.seriesId == item.seriesId } })
        if (!hasCredentials()) return@withContext free.take(limit)
        (xc + free.filter { item -> xc.none { it.seriesId == item.seriesId } }).take(limit)
    }

    /** Drops server lists and posters only. History, favourites and login stay. */
    suspend fun clearCatalogCache() = withContext(Dispatchers.IO) {
        database.withTransaction {
            channelDao.clearAll()
            categoryDao.clearAll()
            vodDao.clearAll()
            seriesDao.clearAll()
            episodeDao.clearAll()
        }
    }

    /**
     * Pulls every feed the user still has switched on. Sections turned off in
     * Settings → Manage features are skipped so the stick does no work for them.
     */
    suspend fun refreshAllFeeds() = withContext(Dispatchers.IO) {
        val enabled = com.iptv.tv.domain.model.AppFeature.effectiveEnabled(preferences.disabledFeatures.first())
        val wantMovies = com.iptv.tv.domain.model.AppFeature.MOVIES in enabled
        val wantSeries = com.iptv.tv.domain.model.AppFeature.SERIES in enabled
        val wantLive = com.iptv.tv.domain.model.AppFeature.LIVE in enabled
        if (wantMovies || wantSeries) ensureArchiveCatalog()
        if (hasCredentials()) {
            runCatching { connectionSnapshot() }
            _providerStatus.value = ProviderLoadState.LOADING
            _providerError.value = null
            try {
                if (wantLive) refreshLiveFeed().getOrThrow()
                if (wantMovies) refreshVodFeed().getOrThrow()
                if (wantSeries) refreshSeriesFeed().getOrThrow()
                _providerStatus.value = ProviderLoadState.READY
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _providerStatus.value = ProviderLoadState.ERROR
                _providerError.value = providerErrorMessage(error)
                throw error
            }
        }
        if (wantLive) refreshIptvOrgFeed()
    }

    private fun providerErrorMessage(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "Could not find the IPTV server. Check the internet connection."
        is java.net.SocketTimeoutException -> "The IPTV server took too long to answer."
        is java.io.IOException -> "Could not reach the IPTV server."
        is kotlinx.serialization.SerializationException -> "The IPTV server sent an unexpected reply."
        is retrofit2.HttpException -> "The IPTV server refused the request (HTTP ${error.code()})."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Could not refresh the IPTV catalogue."
    }

    private fun persistServerTimeZone(info: com.iptv.tv.data.api.ServerInfoDto?) {
        info?.timezone?.trim()?.takeIf { it.isNotBlank() }?.let { credentialsStore.saveServerTimeZoneId(it) }
    }

    private fun requireCreds(): ServerCredentials =
        credentialsStore.getCredentials() ?: throw IllegalStateException("Not logged in")

    private val epgOverrides = java.util.concurrent.atomic.AtomicReference<Map<Int, String>>(emptyMap())

    private suspend fun loadEpgOverrides(): Map<Int, String> {
        val map = epgOverrideDao.getAll().associate { it.streamId to it.epgChannelId }
        epgOverrides.set(map)
        return map
    }

    private suspend fun List<com.iptv.tv.data.db.ChannelEntity>.toDomainWithEpg(): List<Channel> {
        val ov = loadEpgOverrides()
        return map { entity -> applyEpgOverride(entity.toDomain(), ov) }
    }

    private fun applyEpgOverride(
        channel: Channel,
        ov: Map<Int, String> = epgOverrides.get(),
    ): Channel {
        val id = ov[channel.streamId]?.takeIf { it.isNotBlank() } ?: return channel
        return channel.copy(epgChannelId = id)
    }

    private suspend fun pickRecentCategory(
        feed: FeedType,
        source: String? = null,
    ): com.iptv.tv.data.db.CategoryEntity? {
        val cats = if (source != null) {
            categoryDao.getByFeedAndSource(feed.name, source)
        } else {
            categoryDao.getByFeedType(feed.name)
        }
        return cats.firstOrNull { nameLooksRecent(it.name) } ?: cats.minByOrNull { it.sortOrder }
    }

    companion object {
        private const val IPTV_ORG_TTL_MS = 24L * 60 * 60 * 1000
        private const val ARCHIVE_TTL_MS = 24L * 60 * 60 * 1000

        fun archiveHeaders(identifier: String, url: String): Map<String, String> {
            val headers = linkedMapOf<String, String>()
            headers["User-Agent"] = VlcEngine.DEFAULT_UA
            headers["Accept"] = "*/*"
            headers["Referer"] = "https://archive.org/details/$identifier"
            headers["Origin"] = "https://archive.org"
            if (url.isNotBlank()) {
                val uri = android.net.Uri.parse(url)
                val host = uri.host
                if (!host.isNullOrBlank() && !host.contains("archive.org")) {
                    headers["Origin"] = "${uri.scheme}://$host"
                }
            }
            return headers
        }

        private fun nameLooksRecent(name: String): Boolean {
            val n = name.lowercase()
            return n.contains("recent") || n.contains("latest") || n.contains("new add") ||
                n.contains("just added") || n.startsWith("new ")
        }
        private fun JsonElement?.asLooseId(): String? {
            val primitive = this?.jsonPrimitive ?: return null
            primitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it != "0" }?.let { return it }
            return primitive.intOrNull?.takeIf { it > 0 }?.toString()
        }

        private fun yearOf(raw: String?): String? {
            val text = raw?.trim().orEmpty()
            return Regex("""((?:19|20)\d{2})""").find(text)?.groupValues?.getOrNull(1)
        }

        private fun normalizeImdb(raw: String?): String? {
            val value = raw?.trim()?.removePrefix("tt")?.removePrefix("TT") ?: return null
            if (value.isBlank() || value == "0") return null
            return if (value.all { it.isDigit() }) "tt$value" else raw.trim()
        }
    }
}

enum class FreeVodLoadState { LOADING, READY, ERROR }

enum class ProviderLoadState { IDLE, LOADING, READY, ERROR }
