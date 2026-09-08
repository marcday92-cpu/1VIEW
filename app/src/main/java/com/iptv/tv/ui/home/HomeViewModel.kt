package com.iptv.tv.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.ChannelCardStore
import com.iptv.tv.data.repository.EpgRepository
import com.iptv.tv.data.repository.HomeChannelSnap
import com.iptv.tv.data.repository.HomeResumeSnap
import com.iptv.tv.data.repository.HomeSeriesSnap
import com.iptv.tv.data.repository.HomeSnapshot
import com.iptv.tv.data.repository.HomeSnapshotStore
import com.iptv.tv.data.repository.HomeVodSnap
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.AdultContent
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.NowOnTvItem
import com.iptv.tv.domain.model.Profile
import com.iptv.tv.domain.model.ReminderItem
import com.iptv.tv.domain.model.ResumeItem
import com.iptv.tv.domain.model.SeriesItem
import com.iptv.tv.domain.model.VodItem
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackOpenedFrom
import com.iptv.tv.player.PlaybackSession
import com.iptv.tv.service.ReminderScheduler
import com.iptv.tv.ui.launchSafely
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val HOME_RAIL_CAP = 12

data class HomeUiState(
    val recentlyWatched: List<Channel> = emptyList(),
    val mostWatched: List<Channel> = emptyList(),
    val favourites: List<Channel> = emptyList(),
    val continueWatching: List<ResumeItem> = emptyList(),
    /** Live channels whose programme, watched recently, is still on air. */
    val continueLive: List<NowOnTvItem> = emptyList(),
    val upcomingReminders: List<ReminderItem> = emptyList(),
    val recentMovies: List<VodItem> = emptyList(),
    val recentSeries: List<SeriesItem> = emptyList(),
    val profileName: String = "",
    val busy: Boolean = false,
    val status: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val profileRepository: ProfileRepository,
    private val preferences: AppPreferences,
    private val playbackController: PlaybackController,
    private val epgRepository: EpgRepository,
    private val reminderScheduler: ReminderScheduler,
    private val snapshotStore: HomeSnapshotStore,
    private val channelCardStore: ChannelCardStore,
    private val updateChecker: com.iptv.tv.update.UpdateChecker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** A newer build found by the background check, for the Home banner. */
    val update: StateFlow<com.iptv.tv.update.UpdateChecker.Result.Available?> = updateChecker.available
    val updateDismissed: StateFlow<Boolean> = updateChecker.dismissed
    val updateProgress: StateFlow<Int?> = updateChecker.progress
    val updateError: StateFlow<String?> = updateChecker.installError

    fun installUpdate() {
        launchSafely("HomeViewModel.installUpdate") { updateChecker.install() }
    }

    fun dismissUpdate() {
        launchSafely("HomeViewModel.dismissUpdate") { updateChecker.dismiss() }
    }

    private val maintenance = MutableStateFlow(false to "")
    private val initialSnapshot = snapshotStore.load()

    val state: StateFlow<HomeUiState> = preferences.activeProfileId
        .flatMapLatest { profileId ->
            val pid = profileId ?: profileRepository.getActiveProfileId()
            val snap = snapshotStore.load()
            val snapForProfile = snap?.takeIf { it.profileId == pid }
            combine(
                profileRepository.observeFavouriteChannelIds(pid),
                profileRepository.observeContinueWatching(pid),
                profileRepository.observeMostWatchedLiveIds(pid),
                profileRepository.observeRecentChannels(pid),
                profileRepository.observeRecentLiveWatches(pid),
            ) { favIds, resume, mostIds, recentIds, liveWatches ->
                HomeLookup(favIds, resume, mostIds, recentIds, liveWatches)
            }.combine(profileRepository.observeProfiles()) { lookup, profiles ->
                lookup.copy(profiles = profiles)
            }.combine(reminderScheduler.observeReminders(pid)) { lookup, reminders ->
                lookup.copy(reminders = reminders)
            }.combine(iptvRepository.observeChannelCount()) { lookup, _ ->
                lookup
            }.transformLatest { lookup ->
                val railRecent = lookup.recentIds.take(HOME_RAIL_CAP)
                val railMost = lookup.mostIds.take(HOME_RAIL_CAP)
                val railFavs = lookup.favIds.take(HOME_RAIL_CAP)
                val liveIds = lookup.liveWatches.map { it.first }
                val needed = (railFavs + railMost + railRecent + liveIds).distinct()
                val history = profileRepository.getLiveRailPlaceholders(pid, needed)
                val snapById = snapForProfile?.channelIndex().orEmpty()
                val profileName = lookup.profiles.find { it.id == pid }?.name.orEmpty()
                val reminders = upcomingReminders(lookup)
                val fast = HomeUiState(
                    // Carried over from the last EPG lookup so the live card does not blink out
                    // (and drop the remote) every time the history or catalogue re-emits.
                    continueLive = lastContinueLive,
                    recentlyWatched = resolveRail(railRecent, emptyMap(), history, snapById).withoutAdult(),
                    mostWatched = resolveRail(railMost, emptyMap(), history, snapById).withoutAdult(),
                    favourites = resolveRail(railFavs, emptyMap(), history, snapById),
                    continueWatching = lookup.resume,
                    upcomingReminders = reminders,
                    recentMovies = snapForProfile?.recentMovies?.map { it.toVod() }.orEmpty(),
                    recentSeries = snapForProfile?.recentSeries?.map { it.toSeries() }.orEmpty(),
                    profileName = profileName,
                )
                emit(fast)
                snapshotStore.saveAsync(fast.toSnapshot(pid))
                prefetch(fast)

                val byId = iptvRepository.getChannelsByIds(needed).associateBy { it.streamId }
                val rails = fast.copy(
                    recentlyWatched = resolveRail(railRecent, byId, history, snapById).withoutAdult(),
                    mostWatched = resolveRail(railMost, byId, history, snapById).withoutAdult(),
                    favourites = resolveRail(railFavs, byId, history, snapById),
                )
                if (rails != fast) {
                    emit(rails)
                    prefetch(rails)
                }

                // "Still on": the programme had started when the channel was last watched and
                // has not ended, so the viewer can pick it back up. A new programme means no card.
                val nowMs = System.currentTimeMillis()
                val onNow = epgRepository.getCurrentForStreams(liveIds).associateBy { it.channelStreamId }
                val continueLive = lookup.liveWatches.mapNotNull { (id, watchedAt) ->
                    val programme = onNow[id] ?: return@mapNotNull null
                    if (programme.startTimeMs > watchedAt || programme.endTimeMs <= nowMs) return@mapNotNull null
                    val channel = byId[id] ?: history[id] ?: return@mapNotNull null
                    if (AdultContent.isAdultChannel(channel)) return@mapNotNull null
                    NowOnTvItem(channel, programme)
                }.take(CONTINUE_LIVE_CAP)
                lastContinueLive = continueLive
                val features = com.iptv.tv.domain.model.AppFeature.effectiveEnabled(preferences.disabledFeatures.first())
                val full = rails.copy(
                    continueLive = continueLive,
                    recentMovies = if (com.iptv.tv.domain.model.AppFeature.MOVIES in features) {
                        iptvRepository.recentlyAddedMovies(HOME_RAIL_CAP)
                    } else emptyList(),
                    recentSeries = if (com.iptv.tv.domain.model.AppFeature.SERIES in features) {
                        iptvRepository.recentlyAddedSeries(HOME_RAIL_CAP)
                    } else emptyList(),
                )
                emit(full)
                snapshotStore.saveAsync(full.toSnapshot(pid))
                prefetch(full)
            }
        }
        .combine(maintenance) { home, (busy, status) -> home.copy(busy = busy, status = status) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            initialSnapshot.toUi(),
        )

    fun refresh() {
        launchSafely("HomeViewModel.refresh", onError = { error ->
            maintenance.value = false to (error.message ?: "Refresh failed")
        }) {
            maintenance.value = true to "Refreshing channels…"
            iptvRepository.refreshAllFeeds()
            if (iptvRepository.hasCredentials()) {
                epgRepository.refreshEpg().getOrThrow()
            }
            maintenance.value = false to "Refresh complete"
        }
    }

    fun clearCache() {
        launchSafely("HomeViewModel.clearCache", onError = { error ->
            maintenance.value = false to (error.message ?: "Could not clear cache")
        }) {
            maintenance.value = true to "Clearing disposable cache…"
            iptvRepository.clearCatalogCache()
            epgRepository.clearCache()
            com.iptv.tv.data.storage.CacheCleaner.clearDisposableCaches(context)
            iptvRepository.refreshAllFeeds()
            if (iptvRepository.hasCredentials()) {
                epgRepository.refreshEpg().getOrThrow()
            }
            maintenance.value = false to "Cache cleared; favourites, history and profiles kept"
        }
    }

    fun playContinue(item: ResumeItem) {
        launchSafely("HomeViewModel.playContinue") {
            val streamId = item.streamId
                ?: item.contentId.substringAfter('_', "").toIntOrNull()
                ?: return@launchSafely
            val resumeAt = item.positionMs
            when {
                item.contentType == ContentType.EPISODE || item.contentId.startsWith("ep_") -> {
                    val history = profileRepository.getHistory(
                        profileRepository.getActiveProfileId(),
                        item.contentId,
                    )
                    val playback = iptvRepository.resolveEpisodePlayback(
                        streamId,
                        item.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4",
                    )
                    playbackController.play(
                        PlaybackSession(
                            type = ContentType.EPISODE,
                            streamId = streamId,
                            title = item.title.substringBefore(" · ").ifBlank { item.title },
                            subtitle = item.title.substringAfter(" · ", "").ifBlank { null },
                            url = playback.url,
                            posterUrl = item.posterUrl,
                            contentKey = item.contentId,
                            startPositionMs = resumeAt,
                            season = item.seasonNum ?: history?.seasonNum,
                            episode = item.episodeNum ?: history?.episodeNum,
                            filename = item.filename,
                            seriesId = item.seriesId,
                            containerExtension = playback.containerExtension,
                            fallbackUrl = playback.fallbackUrl,
                            headers = playback.headers,
                            openedFrom = PlaybackOpenedFrom.HOME,
                        ),
                    )
                }
                else -> {
                    val vod = iptvRepository.getVodItem(streamId)
                    val playback = iptvRepository.resolveVodPlayback(
                        streamId,
                        vod?.containerExtension ?: item.containerExtension ?: "mp4",
                    )
                    playbackController.play(
                        PlaybackSession(
                            type = ContentType.MOVIE,
                            streamId = streamId,
                            title = vod?.name ?: item.title,
                            url = playback.url,
                            posterUrl = vod?.posterUrl ?: item.posterUrl,
                            contentKey = "vod_$streamId",
                            startPositionMs = resumeAt,
                            year = vod?.year ?: item.year,
                            filename = vod?.name ?: item.filename,
                            containerExtension = playback.containerExtension,
                            fallbackUrl = playback.fallbackUrl,
                            headers = playback.headers,
                            openedFrom = PlaybackOpenedFrom.HOME,
                        ),
                    )
                }
            }
        }
    }

    fun removeFromContinue(item: ResumeItem) {
        launchSafely("HomeViewModel.removeFromContinue") {
            profileRepository.removeFromContinueWatching(
                profileRepository.getActiveProfileId(),
                item.contentId,
            )
        }
    }

    fun playReminder(item: ReminderItem) {
        launchSafely("HomeViewModel.playReminder") {
            val ch = iptvRepository.getChannel(item.channelStreamId) ?: return@launchSafely
            playbackController.playLive(
                ch,
                iptvRepository.livePlaybackUrl(ch),
                listOf(ch),
                browse = false,
                openedFrom = PlaybackOpenedFrom.HOME,
                liveRailId = ch.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" },
            )
        }
    }

    fun playMovie(item: VodItem) {
        launchSafely("HomeViewModel.playMovie") {
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
                    openedFrom = PlaybackOpenedFrom.HOME,
                    isFree = playback.isFree,
                ),
            )
        }
    }

    fun playSeries(item: SeriesItem) {
        launchSafely("HomeViewModel.playSeries") {
            val detail = iptvRepository.getSeriesDetail(item.seriesId)
            val episode = detail.seasons.toSortedMap().values.firstOrNull()?.firstOrNull() ?: return@launchSafely
            val key = "ep_${episode.streamId}"
            val resume = profileRepository.getResumePosition(profileRepository.getActiveProfileId(), key) ?: 0
            val playback = iptvRepository.resolveEpisodePlayback(episode.streamId, episode.containerExtension)
            playbackController.play(
                PlaybackSession(
                    type = ContentType.EPISODE,
                    streamId = episode.streamId,
                    title = detail.name,
                    subtitle = "S${episode.seasonNum} E${episode.episodeNum} ${episode.title}",
                    url = playback.url,
                    posterUrl = detail.posterUrl,
                    contentKey = key,
                    startPositionMs = resume,
                    season = episode.seasonNum,
                    episode = episode.episodeNum,
                    seriesId = detail.seriesId,
                    filename = "${detail.name}.S${episode.seasonNum}E${episode.episodeNum}",
                    containerExtension = playback.containerExtension,
                    fallbackUrl = playback.fallbackUrl,
                    headers = playback.headers,
                    openedFrom = PlaybackOpenedFrom.HOME,
                    isFree = playback.isFree,
                ),
            )
        }
    }

    private data class HomeLookup(
        val favIds: List<Int>,
        val resume: List<ResumeItem>,
        val mostIds: List<Int>,
        val recentIds: List<Int>,
        val liveWatches: List<Pair<Int, Long>> = emptyList(),
        val profiles: List<Profile> = emptyList(),
        val reminders: List<com.iptv.tv.data.db.ReminderEntity> = emptyList(),
    )

    private fun upcomingReminders(lookup: HomeLookup): List<ReminderItem> {
        if (!com.iptv.tv.domain.model.IptvCapabilities.showReminderUi(iptvRepository.hasCredentials())) {
            return emptyList()
        }
        val nowMs = System.currentTimeMillis()
        return lookup.reminders
            .filter { it.programmeStartMs > nowMs }
            .map {
                ReminderItem(
                    id = it.id,
                    profileId = it.profileId,
                    channelStreamId = it.channelStreamId,
                    channelName = it.channelName,
                    programmeTitle = it.programmeTitle,
                    programmeStartMs = it.programmeStartMs,
                    offsetMinutes = it.offsetMinutes,
                )
            }
    }

    private fun resolveRail(
        ids: List<Int>,
        byId: Map<Int, Channel>,
        history: Map<Int, Channel>,
        snapshot: Map<Int, Channel>,
    ): List<Channel> {
        if (ids.isEmpty()) return emptyList()
        return ids.distinct().map { id ->
            byId[id] ?: snapshot[id] ?: history[id] ?: Channel(
                streamId = id,
                name = "Channel $id",
                logoUrl = null,
                categoryId = "",
                epgChannelId = null,
            )
        }
    }

    private fun prefetch(state: HomeUiState) {
        viewModelScope.launch(Dispatchers.IO) {
            val channels = (
                state.recentlyWatched.take(PREFETCH_PER_RAIL) +
                    state.mostWatched.take(PREFETCH_PER_RAIL) +
                    state.favourites.take(PREFETCH_PER_RAIL)
                ).distinctBy { it.streamId }
            for (channel in channels) {
                runCatching {
                    channelCardStore.ensureCard(
                        channel.logoUrl,
                        channel.name,
                        channel.epgChannelId,
                        channel.streamId,
                    )
                }
            }
            val posters = (
                state.continueWatching.mapNotNull { it.posterUrl } +
                    state.recentMovies.mapNotNull { it.posterUrl } +
                    state.recentSeries.mapNotNull { it.posterUrl }
                ).distinct().take(PREFETCH_PER_RAIL * 3)
            val loader = context.imageLoader
            for (url in posters) {
                loader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey(url)
                        .diskCacheKey(url)
                        .build(),
                )
            }
        }
    }

    private var lastContinueLive: List<NowOnTvItem> = emptyList()

    private companion object {
        const val PREFETCH_PER_RAIL = HOME_RAIL_CAP
        const val CONTINUE_LIVE_CAP = 5
    }
}

private fun HomeSnapshot?.toUi(): HomeUiState {
    val snap = this ?: return HomeUiState()
    return HomeUiState(
        recentlyWatched = snap.recentlyWatched.map { it.toChannel() }.withoutAdult(),
        mostWatched = snap.mostWatched.map { it.toChannel() }.withoutAdult(),
        favourites = snap.favourites.map { it.toChannel() },
        continueWatching = snap.continueWatching.map { it.toResume() },
        recentMovies = snap.recentMovies.map { it.toVod() },
        recentSeries = snap.recentSeries.map { it.toSeries() },
        profileName = snap.profileName,
    )
}

private fun HomeUiState.toSnapshot(profileId: Long) = HomeSnapshot(
    profileId = profileId,
    profileName = profileName,
    recentlyWatched = recentlyWatched.take(HOME_RAIL_CAP).map { HomeChannelSnap.from(it) },
    mostWatched = mostWatched.take(HOME_RAIL_CAP).map { HomeChannelSnap.from(it) },
    favourites = favourites.take(HOME_RAIL_CAP).map { HomeChannelSnap.from(it) },
    continueWatching = continueWatching.take(HOME_RAIL_CAP).map { HomeResumeSnap.from(it) },
    recentMovies = recentMovies.take(HOME_RAIL_CAP).map { HomeVodSnap.from(it) },
    recentSeries = recentSeries.take(HOME_RAIL_CAP).map { HomeSeriesSnap.from(it) },
)

/** Watch-derived rails (Recently / Most Watched, Now on TV) never surface adult (XXX) channels. */
private fun List<Channel>.withoutAdult(): List<Channel> = filterNot(AdultContent::isAdultChannel)
