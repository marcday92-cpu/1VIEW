package com.iptv.tv.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.FreeVodLoadState
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.ChannelSource
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.Episode
import com.iptv.tv.domain.model.FavouriteType
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.SeriesDetail
import com.iptv.tv.domain.model.SeriesItem
import com.iptv.tv.domain.model.VodItem
import com.iptv.tv.domain.model.VodSourceFilter
import com.iptv.tv.domain.model.inVodSourceFilter
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackOpenedFrom
import com.iptv.tv.player.PlaybackSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import com.iptv.tv.ui.launchSafely
import javax.inject.Inject

/** Contextual search inside Movies / Series. The section behind the sheet is untouched. */
data class CatalogSearchState<T>(
    val open: Boolean = false,
    val query: String = "",
    val results: List<T> = emptyList(),
    val searching: Boolean = false,
)

data class MoviesUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategory: String? = null,
    val items: List<VodItem> = emptyList(),
    val sourceFilter: VodSourceFilter = VodSourceFilter.ALL,
    val hasIptvLogin: Boolean = false,
    val emptyMessage: String? = null,
    val canRetry: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val profileRepository: ProfileRepository,
    private val playbackController: PlaybackController,
    private val preferences: AppPreferences,
    private val parentalLock: com.iptv.tv.data.parental.ParentalLock,
) : ViewModel() {
    private val selected = MutableStateFlow<String?>(null)

    /** True while a PIN is set and not entered this session. */
    private val adultLocked = combine(preferences.parentalPinHash, parentalLock.unlocked) { hash, unlocked ->
        !hash.isNullOrBlank() && !unlocked
    }

    val state: StateFlow<MoviesUiState> = profileRepository.observeActiveProfileId()
        .flatMapLatest { raw ->
            val profileId = raw ?: profileRepository.getActiveProfileId()
            combine(
                iptvRepository.observeVodCategories(),
                profileRepository.observeCategoryOverrides(profileId, FeedType.VOD),
                profileRepository.observeHiddenItemIds(profileId, FavouriteType.MOVIE),
                selected,
                preferences.moviesSourceFilter,
            ) { cats, overrides, hidden, sel, filter ->
                MoviesInputs(cats, overrides, hidden, sel, filter)
            }.combine(adultLocked) { inputs, locked ->
                val cats = inputs.cats
                val overrides = inputs.overrides
                val hidden = inputs.hidden
                val sel = inputs.sel
                val filter = inputs.filter
                val presented = ProfileRepository.presentCategories(
                    cats.filter { it.inVodSourceFilter(filter) }
                        .filter { !locked || !com.iptv.tv.domain.model.AdultContent.isAdultCategory(it.id, it.name) }
                        .orderedSources(),
                    overrides,
                )
                val id = sel?.takeIf { cid -> presented.any { it.id == cid } }
                    ?: presented.firstOrNull()?.id
                MoviesPick(presented, id, hidden, filter)
            }.flatMapLatest { pick ->
                val itemsFlow = if (pick.id == null) flowOf(emptyList()) else iptvRepository.observeVodByCategory(pick.id)
                combine(
                    itemsFlow,
                    iptvRepository.freeVodStatus,
                    iptvRepository.freeVodError,
                    iptvRepository.providerStatus,
                    iptvRepository.providerError,
                ) { items, load, error, providerLoad, providerError ->
                    val visible = items.asSequence()
                        .filter { it.streamId.toString() !in pick.hidden }
                        .distinctBy { it.streamId }
                        .toList()
                    val loggedIn = iptvRepository.hasCredentials()
                    val empty = pick.presented.isEmpty()
                    MoviesUiState(
                        categories = pick.presented,
                        selectedCategory = pick.id,
                        items = visible,
                        sourceFilter = pick.filter,
                        hasIptvLogin = loggedIn,
                        emptyMessage = vodEmptyMessage(
                            !empty,
                            pick.filter,
                            loggedIn,
                            movies = true,
                            loadingFree = load == FreeVodLoadState.LOADING,
                            freeError = error,
                            providerFailed = providerLoad == com.iptv.tv.data.repository.ProviderLoadState.ERROR,
                            providerError = providerError,
                        ),
                        canRetry = empty && (
                            load == FreeVodLoadState.ERROR ||
                                providerLoad == com.iptv.tv.data.repository.ProviderLoadState.ERROR
                            ),
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoviesUiState())

    private val searchOpen = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")

    val search: StateFlow<CatalogSearchState<VodItem>> = searchOpen
        .flatMapLatest { open ->
            if (!open) return@flatMapLatest flowOf(CatalogSearchState())
            combine(
                searchQuery.debounce(200),
                preferences.moviesSourceFilter,
                profileRepository.observeActiveProfileId()
                    .flatMapLatest { raw ->
                        profileRepository.observeHiddenItemIds(raw ?: profileRepository.getActiveProfileId(), FavouriteType.MOVIE)
                    },
            ) { query, filter, hidden -> Triple(query, filter, hidden) }
                .flatMapLatest { (query, filter, hidden) ->
                    flow {
                        if (query.isBlank()) {
                            emit(CatalogSearchState(open = true, query = query))
                            return@flow
                        }
                        emit(CatalogSearchState(open = true, query = query, searching = true))
                        val found = iptvRepository.searchVod(query)
                            .filter { it.inVodSourceFilter(filter) && it.streamId.toString() !in hidden }
                        emit(CatalogSearchState(open = true, query = query, results = found))
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogSearchState())

    fun openSearch() { searchOpen.value = true }
    fun closeSearch() { searchOpen.value = false }
    fun setSearchQuery(value: String) { searchQuery.value = value }

    fun playFromSearch(item: VodItem) {
        searchOpen.value = false
        play(item)
    }

    fun selectCategory(id: String) { selected.value = id }

    fun setSourceFilter(filter: VodSourceFilter) {
        selected.value = null
        launchSafely("MoviesViewModel.setSourceFilter") { preferences.setMoviesSourceFilter(filter) }
    }

    fun retryFree() {
        launchSafely("MoviesViewModel.retry") {
            if (iptvRepository.hasCredentials()) runCatching { iptvRepository.refreshAllFeeds() }
            iptvRepository.refreshArchiveVodFeed(force = true).getOrThrow()
        }
    }

    fun play(item: VodItem) {
        launchSafely("MoviesViewModel.play") {
            val resume = profileRepository.getResumePosition(
                profileRepository.getActiveProfileId(),
                "vod_${item.streamId}",
            ) ?: 0
            val playback = iptvRepository.resolveVodPlayback(item.streamId, item.containerExtension)
            playbackController.play(
                PlaybackSession(
                    type = ContentType.MOVIE,
                    streamId = item.streamId,
                    title = item.name,
                    url = playback.url,
                    posterUrl = item.posterUrl,
                    contentKey = "vod_${item.streamId}",
                    startPositionMs = resume,
                    year = item.year,
                    filename = item.name,
                    containerExtension = playback.containerExtension,
                    fallbackUrl = playback.fallbackUrl,
                    headers = playback.headers,
                    openedFrom = PlaybackOpenedFrom.MOVIES,
                    isFree = playback.isFree,
                ),
            )
        }
    }

    fun hide(item: VodItem) {
        launchSafely("MoviesViewModel.hide") {
            profileRepository.hideItem(
                profileRepository.getActiveProfileId(),
                FavouriteType.MOVIE,
                item.streamId.toString(),
            )
        }
    }

    fun toggleFavourite(item: VodItem) {
        launchSafely("MoviesViewModel.fav") {
            profileRepository.toggleFavourite(
                profileRepository.getActiveProfileId(),
                FavouriteType.MOVIE,
                item.streamId.toString(),
            )
        }
    }

    fun markWatched(item: VodItem) {
        launchSafely("MoviesViewModel.watched") {
            profileRepository.removeFromContinueWatching(
                profileRepository.getActiveProfileId(),
                "vod_${item.streamId}",
            )
        }
    }
}

data class SeriesUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategory: String? = null,
    val items: List<SeriesItem> = emptyList(),
    val detail: SeriesDetail? = null,
    val sourceFilter: VodSourceFilter = VodSourceFilter.ALL,
    val hasIptvLogin: Boolean = false,
    val emptyMessage: String? = null,
    val canRetry: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SeriesViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val profileRepository: ProfileRepository,
    private val playbackController: PlaybackController,
    private val preferences: AppPreferences,
    private val parentalLock: com.iptv.tv.data.parental.ParentalLock,
) : ViewModel() {
    private val selected = MutableStateFlow<String?>(null)
    private val detail = MutableStateFlow<SeriesDetail?>(null)
    private var openJob: kotlinx.coroutines.Job? = null

    private val adultLocked = combine(preferences.parentalPinHash, parentalLock.unlocked) { hash, unlocked ->
        !hash.isNullOrBlank() && !unlocked
    }

    val state: StateFlow<SeriesUiState> = profileRepository.observeActiveProfileId()
        .flatMapLatest { raw ->
            val profileId = raw ?: profileRepository.getActiveProfileId()
            combine(
                iptvRepository.observeSeriesCategories(),
                profileRepository.observeCategoryOverrides(profileId, FeedType.SERIES),
                selected,
                detail,
                preferences.seriesSourceFilter,
            ) { cats, overrides, sel, det, filter ->
                SeriesInputs(cats, overrides, sel, det, filter)
            }.combine(adultLocked) { inputs, locked ->
                val cats = inputs.cats
                val overrides = inputs.overrides
                val sel = inputs.sel
                val det = inputs.det
                val filter = inputs.filter
                val presented = ProfileRepository.presentCategories(
                    cats.filter { it.inVodSourceFilter(filter) }
                        .filter { !locked || !com.iptv.tv.domain.model.AdultContent.isAdultCategory(it.id, it.name) }
                        .orderedSources(),
                    overrides,
                )
                val id = sel?.takeIf { cid -> presented.any { it.id == cid } }
                    ?: presented.firstOrNull()?.id
                SeriesPick(presented, id, det, filter)
            }.flatMapLatest { pick ->
                val itemsFlow = if (pick.id == null) flowOf(emptyList()) else iptvRepository.observeSeriesByCategory(pick.id)
                combine(
                    itemsFlow,
                    iptvRepository.freeVodStatus,
                    iptvRepository.freeVodError,
                    iptvRepository.providerStatus,
                    iptvRepository.providerError,
                ) { items, load, error, providerLoad, providerError ->
                    val loggedIn = iptvRepository.hasCredentials()
                    val empty = pick.presented.isEmpty()
                    SeriesUiState(
                        categories = pick.presented,
                        selectedCategory = pick.id,
                        items = items.distinctBy { it.seriesId },
                        detail = pick.detail,
                        sourceFilter = pick.filter,
                        hasIptvLogin = loggedIn,
                        emptyMessage = vodEmptyMessage(
                            !empty,
                            pick.filter,
                            loggedIn,
                            movies = false,
                            loadingFree = load == FreeVodLoadState.LOADING,
                            freeError = error,
                            providerFailed = providerLoad == com.iptv.tv.data.repository.ProviderLoadState.ERROR,
                            providerError = providerError,
                        ),
                        canRetry = empty && (
                            load == FreeVodLoadState.ERROR ||
                                providerLoad == com.iptv.tv.data.repository.ProviderLoadState.ERROR
                            ),
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesUiState())

    private val searchOpen = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")

    val search: StateFlow<CatalogSearchState<SeriesItem>> = searchOpen
        .flatMapLatest { open ->
            if (!open) return@flatMapLatest flowOf(CatalogSearchState())
            combine(searchQuery.debounce(200), preferences.seriesSourceFilter) { query, filter -> query to filter }
                .flatMapLatest { (query, filter) ->
                    flow {
                        if (query.isBlank()) {
                            emit(CatalogSearchState(open = true, query = query))
                            return@flow
                        }
                        emit(CatalogSearchState(open = true, query = query, searching = true))
                        val found = iptvRepository.searchSeries(query).filter { it.inVodSourceFilter(filter) }
                        emit(CatalogSearchState(open = true, query = query, results = found))
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogSearchState())

    fun openSearch() { searchOpen.value = true }
    fun closeSearch() { searchOpen.value = false }
    fun setSearchQuery(value: String) { searchQuery.value = value }

    fun openFromSearch(item: SeriesItem) {
        searchOpen.value = false
        openSeries(item)
    }

    fun selectCategory(id: String) { selected.value = id }

    fun setSourceFilter(filter: VodSourceFilter) {
        selected.value = null
        launchSafely("SeriesViewModel.setSourceFilter") { preferences.setSeriesSourceFilter(filter) }
    }

    fun retryFree() {
        launchSafely("SeriesViewModel.retry") {
            if (iptvRepository.hasCredentials()) runCatching { iptvRepository.refreshAllFeeds() }
            iptvRepository.refreshArchiveVodFeed(force = true).getOrThrow()
        }
    }

    fun openSeries(item: SeriesItem) {
        // Newest pick wins: a slow detail fetch for an earlier series must not pop up later.
        openJob?.cancel()
        openJob = launchSafely("SeriesViewModel.openSeries") {
            val loaded = runCatching { iptvRepository.getSeriesDetail(item.seriesId) }.getOrNull() ?: return@launchSafely
            val live = loaded.seasons.values.flatten()
            if (item.source == ChannelSource.IPTV_ORG && live.size == 1) {
                play(live.first(), loaded.name, loaded.seriesId)
            } else {
                detail.value = loaded
            }
        }
    }

    fun dismissDetail() {
        openJob?.cancel()
        detail.value = null
    }

    fun play(episode: Episode, seriesName: String, seriesId: Int) {
        // Close the episode sheet first so it is not left on top of the player.
        detail.value = null
        launchSafely("SeriesViewModel.play") {
            val key = "ep_${episode.streamId}"
            val resume = profileRepository.getResumePosition(profileRepository.getActiveProfileId(), key) ?: 0
            val playback = iptvRepository.resolveEpisodePlayback(episode.streamId, episode.containerExtension)
            playbackController.play(
                PlaybackSession(
                    type = ContentType.EPISODE,
                    streamId = episode.streamId,
                    title = seriesName,
                    subtitle = "S${episode.seasonNum} E${episode.episodeNum} ${episode.title}",
                    url = playback.url,
                    contentKey = key,
                    startPositionMs = resume,
                    season = episode.seasonNum,
                    episode = episode.episodeNum,
                    filename = "${seriesName}.S${episode.seasonNum.toString().padStart(2, '0')}E${episode.episodeNum.toString().padStart(2, '0')}",
                    seriesId = seriesId,
                    containerExtension = playback.containerExtension,
                    fallbackUrl = playback.fallbackUrl,
                    headers = playback.headers,
                    openedFrom = PlaybackOpenedFrom.SERIES,
                    isFree = playback.isFree,
                ),
            )
        }
    }
}

private data class MoviesInputs(
    val cats: List<Category>,
    val overrides: List<com.iptv.tv.data.db.ProviderCategoryOverrideEntity>,
    val hidden: Set<String>,
    val sel: String?,
    val filter: VodSourceFilter,
)

private data class SeriesInputs(
    val cats: List<Category>,
    val overrides: List<com.iptv.tv.data.db.ProviderCategoryOverrideEntity>,
    val sel: String?,
    val det: SeriesDetail?,
    val filter: VodSourceFilter,
)

private data class MoviesPick(
    val presented: List<Category>,
    val id: String?,
    val hidden: Set<String>,
    val filter: VodSourceFilter,
)

private data class SeriesPick(
    val presented: List<Category>,
    val id: String?,
    val detail: SeriesDetail?,
    val filter: VodSourceFilter,
)

private fun List<Category>.orderedSources(): List<Category> =
    filter { !it.isFree } +
        filter { ChannelSource.isFastFreeCategory(it.id) } +
        filter { it.isFree && !ChannelSource.isFastFreeCategory(it.id) }
