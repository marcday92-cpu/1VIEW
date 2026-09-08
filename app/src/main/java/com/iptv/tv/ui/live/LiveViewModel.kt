package com.iptv.tv.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.data.credentials.CredentialsStore
import com.iptv.tv.data.parental.ParentalLock
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.EpgRepository
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.AdultContent
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.CustomGroup
import com.iptv.tv.domain.model.FavouriteType
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.IptvCapabilities
import com.iptv.tv.domain.model.LiveSourceFilter
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.domain.model.WatchRanking
import com.iptv.tv.domain.model.inSourceFilter
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackOpenedFrom
import com.iptv.tv.service.RecordingManager
import com.iptv.tv.service.ReminderScheduler
import com.iptv.tv.ui.launchSafely
import com.iptv.tv.util.SearchIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class LiveRailItem(
    val id: String,
    val label: String,
    val kind: Kind,
    val free: Boolean = false,
) {
    enum class Kind { FAVOURITES, MOST_WATCHED, RECENT, GROUP, CATEGORY }
}

data class LiveUiState(
    val rails: List<LiveRailItem> = emptyList(),
    val selectedRailId: String = "",
    val channels: List<Channel> = emptyList(),
    val selected: Channel? = null,
    val now: Programme? = null,
    val next: Programme? = null,
    val isFavourite: Boolean = false,
    val sourceFilter: LiveSourceFilter = LiveSourceFilter.ALL,
    val hasIptvLogin: Boolean = false,
    val emptyMessage: String? = null,
    val groups: List<CustomGroup> = emptyList(),
    /** The rail being shown is a custom group, so "Remove from group" makes sense. */
    val selectedGroupId: Long? = null,
    val favouriteIds: Set<Int> = emptySet(),
)

/** One row in the Live search results: the channel plus the list it lives in. */
data class LiveSearchHit(
    val channel: Channel,
    val categoryName: String,
    /** The now/next programme title that matched, when the channel name did not. */
    val programme: String? = null,
    /** Part of the leading "on now & next" block. */
    val onAir: Boolean = false,
)

data class LiveSearchState(
    val open: Boolean = false,
    val query: String = "",
    val results: List<LiveSearchHit> = emptyList(),
    /** True while the channel index is still being built for the first search. */
    val indexing: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val profileRepository: ProfileRepository,
    private val playbackController: PlaybackController,
    private val preferences: AppPreferences,
    private val credentialsStore: CredentialsStore,
    private val reminderScheduler: ReminderScheduler,
    private val recordingManager: RecordingManager,
    private val parentalLock: ParentalLock,
) : ViewModel() {

    private val selectedRail = MutableStateFlow("")
    private val selectedStream = MutableStateFlow<Int?>(null)
    private val _revealNonce = MutableStateFlow(0)
    val revealNonce: StateFlow<Int> = _revealNonce.asStateFlow()

    private val searchOpen = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")

    /**
     * Everything that depends on the catalogue, profile and chosen rail. Deliberately does
     * not include the focused channel: moving the D-pad down a list of a thousand channels
     * must never re-run the rail queries.
     */
    private val listState: StateFlow<LiveUiState> = profileRepository.observeActiveProfileId()
        .flatMapLatest { rawId ->
            val profileId = rawId ?: profileRepository.getActiveProfileId()
            combine(
                iptvRepository.observeLiveCategories(),
                iptvRepository.observeChannelCount(),
                profileRepository.observeFavouriteChannelIds(profileId),
                profileRepository.observeRecentChannels(profileId),
                profileRepository.observeHiddenChannelIds(profileId),
            ) { categories, _, favIds, recentIds, hidden ->
                LiveIds(categories, favIds, recentIds, hidden)
            }.combine(profileRepository.observeMostWatchedLiveIds(profileId)) { ids, mostIds ->
                ids.copy(mostIds = mostIds)
            }.combine(profileRepository.observeLiveWatchMs(profileId)) { ids, watchMs ->
                ids.copy(watchMs = watchMs)
            }.combine(profileRepository.observeCustomGroups(profileId)) { ids, groups ->
                ids.copy(groups = groups)
            }.combine(selectedRail) { ids, railId ->
                ids.copy(railId = railId)
            }.combine(preferences.liveSourceFilter) { ids, filter ->
                ids.copy(filter = filter)
            }.combine(preferences.parentalPinHash) { ids, hash ->
                ids.copy(pinSet = !hash.isNullOrBlank())
            }.combine(parentalLock.unlocked) { ids, unlocked ->
                ids.copy(hideAdult = ids.pinSet && !unlocked)
            }.flatMapLatest { sel ->
                flow { emit(buildState(profileId, sel)) }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    /** The focused channel, its favourite flag and its now/next programmes. Cheap. */
    private val selection = combine(listState, selectedStream) { list, id ->
        list.channels.firstOrNull { it.streamId == id }
    }.distinctUntilChanged()
        .flatMapLatest { channel ->
            if (channel == null) return@flatMapLatest flowOf(Selection())
            flow {
                emit(Selection(channel))
                val epgId = channel.epgChannelId?.takeIf { it.isNotBlank() } ?: return@flow
                emit(
                    Selection(
                        channel,
                        now = epgRepository.getCurrentProgramme(epgId),
                        next = epgRepository.getNextProgramme(epgId),
                    ),
                )
            }
        }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<LiveUiState> = combine(listState, selection) { list, sel ->
        list.copy(
            selected = sel.channel,
            now = sel.now,
            next = sel.next,
            isFavourite = sel.channel?.streamId?.let { it in list.favouriteIds } == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    /**
     * Contextual search over every channel the user can currently see (all rails, current
     * source filter, hidden and adult rules applied). The index is only built while the
     * search sheet is open, then thrown away.
     */
    val search: StateFlow<LiveSearchState> = searchOpen
        .flatMapLatest { open ->
            if (!open) return@flatMapLatest flowOf(LiveSearchState())
            val index = searchIndex()
            combine(index, searchQuery.debounce(120)) { built, query ->
                LiveSearchState(
                    open = true,
                    query = query,
                    indexing = built == null,
                    results = built?.searchDetailed(query, SEARCH_LIMIT)
                        ?.map { it.item.copy(programme = it.programme, onAir = it.onAir) }
                        .orEmpty(),
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveSearchState())

    private fun searchIndex(): kotlinx.coroutines.flow.Flow<SearchIndex<LiveSearchHit>?> = flow {
        emit(null)
        builtSearchIndex().collect { emit(it) }
    }

    private fun builtSearchIndex() = profileRepository.observeActiveProfileId()
        .flatMapLatest { rawId ->
            val profileId = rawId ?: profileRepository.getActiveProfileId()
            combine(
                iptvRepository.observeLiveChannels(),
                iptvRepository.observeLiveCategories(),
                profileRepository.observeHiddenChannelIds(profileId),
                preferences.liveSourceFilter,
                parentalLock.unlocked.combine(preferences.parentalPinHash) { unlocked, hash ->
                    !hash.isNullOrBlank() && !unlocked
                },
            ) { channels, categories, hidden, filter, hideAdult ->
                val catNames = categories.associate { it.id to it.name }
                val visible = channels.asSequence()
                    .filter { it.streamId !in hidden && it.inSourceFilter(filter) }
                    .filter { !hideAdult || !AdultContent.isAdultChannel(it) }
                    .map { LiveSearchHit(it, catNames[it.categoryId].orEmpty()) }
                    .toList()
                // What is on now and next, so a programme name finds the channel showing it.
                val onAir = epgRepository.nowAndNextTitles()
                SearchIndex(
                    visible,
                    name = { it.channel.name },
                    extra = { it.categoryName.ifBlank { null } },
                    programmes = { hit -> hit.channel.epgChannelId?.let { onAir[it] }.orEmpty() },
                )
            }
        }

    fun openSearch() {
        searchOpen.value = true
    }

    fun closeSearch() {
        searchOpen.value = false
    }

    fun setSearchQuery(value: String) {
        searchQuery.value = value
    }

    /** A search result behaves exactly like OK on that channel in its own list. */
    fun playSearchHit(hit: LiveSearchHit) {
        searchOpen.value = false
        val rail = hit.channel.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" }
        if (rail != null) selectedRail.value = rail
        selectedStream.value = hit.channel.streamId
        _revealNonce.value++
        play(hit.channel)
    }

    fun selectRail(id: String, clearChannel: Boolean = true) {
        selectedRail.value = id
        if (clearChannel) selectedStream.value = null
    }

    fun setSourceFilter(filter: LiveSourceFilter) {
        selectedRail.value = ""
        selectedStream.value = null
        launchSafely("LiveViewModel.setSourceFilter") { preferences.setLiveSourceFilter(filter) }
    }

    val session = playbackController.session
    val liveBrowse = playbackController.liveBrowse
    val playerState = playbackController.playerManager.state
    val playerManager = playbackController.playerManager

    fun selectChannel(id: Int, requestFocus: Boolean = false) {
        selectedStream.value = id
        if (requestFocus) _revealNonce.value++
    }

    fun play(channel: Channel, browse: Boolean? = null) {
        selectedStream.value = channel.streamId
        val current = playbackController.session.value
        val alreadyBrowsing = playbackController.liveBrowse.value &&
            current?.type == ContentType.LIVE
        if (alreadyBrowsing && current?.streamId == channel.streamId) {
            playbackController.setLiveBrowse(false)
            return
        }
        val railId = selectedRail.value.ifBlank { null }
            ?: channel.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" }
        launchSafely("LiveViewModel.play") {
            if (parentalLock.adultBlocked() && AdultContent.isAdultChannel(channel)) return@launchSafely
            val url = iptvRepository.livePlaybackUrl(channel)
            val playlist = listState.value.channels.ifEmpty { listOf(channel) }
            playbackController.playLive(
                channel,
                url,
                playlist,
                browse = browse ?: true,
                openedFrom = PlaybackOpenedFrom.LIVE,
                liveRailId = railId,
            )
        }
    }

    fun enterFullscreen() {
        playbackController.setLiveBrowse(false)
    }

    fun stopPreview() {
        playbackController.close()
    }


    fun toggleFavourite(channel: Channel) {
        launchSafely("LiveViewModel.toggleFavourite") {
            profileRepository.toggleFavourite(
                profileRepository.getActiveProfileId(),
                FavouriteType.CHANNEL,
                channel.streamId.toString(),
            )
        }
    }

    fun hide(channel: Channel) {
        launchSafely("LiveViewModel.hide") {
            profileRepository.hideChannel(profileRepository.getActiveProfileId(), channel.streamId)
        }
    }

    fun remind(channel: Channel, offsetMinutes: Int) {
        if (!IptvCapabilities.canRemind(channel, credentialsStore.hasCredentials())) return
        launchSafely("LiveViewModel.remind") {
            val epgId = channel.epgChannelId ?: return@launchSafely
            val next = epgRepository.getNextProgramme(epgId) ?: epgRepository.getCurrentProgramme(epgId) ?: return@launchSafely
            reminderScheduler.scheduleReminder(
                profileRepository.getActiveProfileId(),
                channel.streamId,
                channel.name,
                next.title,
                next.startTimeMs,
                offsetMinutes,
            )
        }
    }

    fun record(channel: Channel) {
        if (!IptvCapabilities.canRecord(channel, credentialsStore.hasCredentials())) return
        launchSafely("LiveViewModel.record") {
            val now = channel.epgChannelId?.let { epgRepository.getCurrentProgramme(it) }
            recordingManager.startOrSchedule(channel, now, 0, preferences.recordingEndBufferMinutes.first(), true)
        }
    }

    fun playFallback(channel: Channel) {
        launchSafely("LiveViewModel.playFallback") {
            val url = channel.fallbackUrl?.takeIf { it.isNotBlank() } ?: return@launchSafely
            val playlist = listState.value.channels.ifEmpty { listOf(channel) }
            playbackController.playLive(
                channel.copy(streamUrl = url),
                url,
                playlist,
                browse = playbackController.liveBrowse.value,
                openedFrom = playbackController.session.value?.openedFrom ?: PlaybackOpenedFrom.LIVE,
                liveRailId = selectedRail.value.ifBlank { null },
            )
        }
    }

    fun setEpgOverride(channel: Channel, epgId: String) {
        launchSafely("LiveViewModel.setEpgOverride") {
            iptvRepository.setEpgChannelId(channel.streamId, epgId)
        }
    }

    fun setEngineOverride(channel: Channel, engine: String) {
        launchSafely("LiveViewModel.setEngineOverride") {
            preferences.setStreamEngineOverride(channel.streamId, engine)
        }
    }

    fun addToGroup(groupId: Long, streamId: Int) {
        launchSafely("LiveViewModel.addToGroup") { profileRepository.addChannelToGroup(groupId, streamId) }
    }

    fun removeFromGroup(groupId: Long, streamId: Int) {
        launchSafely("LiveViewModel.removeFromGroup") { profileRepository.removeChannelFromGroup(groupId, streamId) }
    }

    fun playById(streamId: Int) {
        launchSafely("LiveViewModel.playById") {
            val ch = iptvRepository.getChannel(streamId) ?: return@launchSafely
            playFromHome(streamId, ch.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" } ?: "recent")
        }
    }

    fun playFromHome(streamId: Int, homeRailId: String) {
        launchSafely("LiveViewModel.playFromHome") {
            val ch = iptvRepository.getChannel(streamId) ?: return@launchSafely
            val rail = when (homeRailId) {
                "favourites", "recent", "most_watched" -> homeRailId
                else -> ch.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" } ?: homeRailId
            }
            selectedRail.value = rail
            selectedStream.value = ch.streamId
            val url = iptvRepository.livePlaybackUrl(ch)
            val playlist = playlistForRail(rail, ch)
            playbackController.playLive(
                ch,
                url,
                playlist,
                browse = false,
                openedFrom = PlaybackOpenedFrom.HOME,
                liveRailId = rail,
            )
        }
    }

    private suspend fun playlistForRail(railId: String, channel: Channel): List<Channel> {
        val profileId = profileRepository.getActiveProfileId()
        val hidden = profileRepository.observeHiddenChannelIds(profileId).first()
        val filter = preferences.liveSourceFilter.first()
        val list = when (railId) {
            "favourites" -> resolveLiveChannels(
                profileRepository.observeFavouriteChannelIds(profileId).first(),
                profileId,
                hidden,
                filter,
            )
            "recent" -> resolveLiveChannels(
                profileRepository.observeRecentChannels(profileId).first(),
                profileId,
                hidden,
                filter,
            ).filterNot(AdultContent::isAdultChannel)
            "most_watched" -> resolveLiveChannels(
                profileRepository.observeMostWatchedLiveIds(profileId).first(),
                profileId,
                hidden,
                filter,
            ).filterNot(AdultContent::isAdultChannel)
            else -> {
                val catId = railId.removePrefix("cat_").ifBlank { channel.categoryId }
                if (catId.isBlank()) listOf(channel)
                else iptvRepository.getChannelsByCategory(catId).ifEmpty { listOf(channel) }
            }
        }
        return list.ifEmpty { listOf(channel) }
    }

    private suspend fun buildState(profileId: Long, sel: LiveIds): LiveUiState {
        val loggedIn = credentialsStore.hasCredentials()
        val filter = sel.filter
        val watched = iptvRepository.getChannelsByIds(
            (sel.mostIds + sel.watchMs.filterValues { it > 0L }.keys).distinct(),
        )
        val presentedCats = profileRepository.presentLiveRails(
            profileId,
            sel.categories.filter { cat ->
                when (filter) {
                    LiveSourceFilter.ALL -> true
                    LiveSourceFilter.IPTV -> !cat.isFree
                    LiveSourceFilter.FREE -> cat.isFree
                }
            },
            watched,
            sel.watchMs,
            filter,
        )
        // Watch-derived rails never surface adult (XXX) channels, locked or not.
        val mostWatched = resolveLiveChannels(sel.mostIds, profileId, sel.hidden, filter)
            .filterNot(AdultContent::isAdultChannel)
        val rails = buildList {
            add(LiveRailItem("favourites", "Favourites", LiveRailItem.Kind.FAVOURITES))
            if (mostWatched.isNotEmpty()) {
                add(LiveRailItem("most_watched", "Most Watched", LiveRailItem.Kind.MOST_WATCHED))
            }
            add(LiveRailItem("recent", "Recently Watched", LiveRailItem.Kind.RECENT))
            if (filter != LiveSourceFilter.FREE) {
                sel.groups.forEach { add(LiveRailItem("group_${it.id}", it.name, LiveRailItem.Kind.GROUP)) }
            }
            presentedCats.distinctBy { it.id }
                .filter { !sel.hideAdult || !AdultContent.isAdultCategory(it.id, it.name) }
                .forEach {
                    add(
                        LiveRailItem(
                            id = "cat_${it.id}",
                            label = it.name,
                            kind = LiveRailItem.Kind.CATEGORY,
                            free = it.isFree,
                        ),
                    )
                }
        }.distinctBy { it.id }
        val rail = rails.find { it.id == sel.railId }
            ?: rails.firstOrNull { it.kind == LiveRailItem.Kind.CATEGORY }
            ?: rails.first()
        val list = when (rail.kind) {
            LiveRailItem.Kind.FAVOURITES ->
                resolveLiveChannels(sel.favIds, profileId, sel.hidden, filter)
            LiveRailItem.Kind.MOST_WATCHED -> mostWatched
            LiveRailItem.Kind.RECENT ->
                resolveLiveChannels(sel.recentIds, profileId, sel.hidden, filter)
                    .filterNot(AdultContent::isAdultChannel)
            LiveRailItem.Kind.CATEGORY -> {
                val catId = rail.id.removePrefix("cat_")
                val extra = profileRepository.mergedInto(profileId, FeedType.LIVE, catId)
                val merged = (listOf(catId) + extra).flatMap { id ->
                    iptvRepository.getChannelsByCategory(id)
                }
                WatchRanking.channels(
                    merged.filter { it.streamId !in sel.hidden && it.inSourceFilter(filter) },
                    sel.watchMs,
                )
            }
            LiveRailItem.Kind.GROUP -> {
                val gid = rail.id.removePrefix("group_").toLongOrNull() ?: 0L
                WatchRanking.channels(
                    resolveLiveChannels(
                        profileRepository.getGroupMembers(gid),
                        profileId,
                        sel.hidden,
                        filter,
                    ),
                    sel.watchMs,
                )
            }
        }.distinctBy { it.streamId }
            .filter { !sel.hideAdult || !AdultContent.isAdultChannel(it) }
        val emptyMessage = when {
            list.isNotEmpty() -> null
            rail.kind == LiveRailItem.Kind.FAVOURITES -> "No favourites yet. Hold OK on a channel and choose Favourite."
            rail.kind == LiveRailItem.Kind.RECENT -> "Channels you watch will appear here."
            rail.kind == LiveRailItem.Kind.GROUP -> "This group is empty. Hold OK on a channel and choose Add to group."
            filter == LiveSourceFilter.IPTV && !loggedIn ->
                "Sign in under Settings → Connection & Data to bring your IPTV lists back."
            filter == LiveSourceFilter.IPTV ->
                "Loading your IPTV lists…"
            filter == LiveSourceFilter.FREE ->
                "Loading free TV…"
            else -> "No channels in this list yet."
        }
        return LiveUiState(
            rails = rails,
            selectedRailId = rail.id,
            channels = list,
            sourceFilter = filter,
            hasIptvLogin = loggedIn,
            emptyMessage = emptyMessage,
            groups = sel.groups,
            selectedGroupId = if (rail.kind == LiveRailItem.Kind.GROUP) rail.id.removePrefix("group_").toLongOrNull() else null,
            favouriteIds = sel.favIds.toSet(),
        )
    }

    private suspend fun resolveLiveChannels(
        ids: List<Int>,
        profileId: Long,
        hidden: Set<Int>,
        filter: LiveSourceFilter,
    ): List<Channel> {
        if (ids.isEmpty()) return emptyList()
        val byId = iptvRepository.getChannelsByIds(ids).associateBy { it.streamId }
        return ids.distinct().mapNotNull { id ->
            val channel = byId[id] ?: unresolvedChannel(profileId, id)
            channel.takeIf { it.streamId !in hidden && it.inSourceFilter(filter) }
        }
    }

    private suspend fun unresolvedChannel(profileId: Long, streamId: Int): Channel {
        val history = profileRepository.getHistory(profileId, "live_$streamId")
        val title = history?.title?.takeIf { it.isNotBlank() && !it.startsWith("live_") }
        return Channel(
            streamId = streamId,
            name = title ?: "Channel $streamId",
            logoUrl = history?.posterUrl,
            categoryId = "",
            epgChannelId = null,
        )
    }

    private data class Selection(
        val channel: Channel? = null,
        val now: Programme? = null,
        val next: Programme? = null,
    )

    private data class LiveIds(
        val categories: List<Category>,
        val favIds: List<Int>,
        val recentIds: List<Int>,
        val hidden: Set<Int>,
        val mostIds: List<Int> = emptyList(),
        val watchMs: Map<Int, Long> = emptyMap(),
        val groups: List<CustomGroup> = emptyList(),
        val railId: String = "",
        val filter: LiveSourceFilter = LiveSourceFilter.ALL,
        val pinSet: Boolean = false,
        val hideAdult: Boolean = false,
    )

    private companion object {
        const val SEARCH_LIMIT = 80
    }
}
