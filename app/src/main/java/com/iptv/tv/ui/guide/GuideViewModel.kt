package com.iptv.tv.ui.guide

import androidx.lifecycle.ViewModel
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.EpgRepository
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.CatchupAvailability
import com.iptv.tv.domain.model.IptvCapabilities
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.LiveSourceFilter
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.domain.model.WatchRanking
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackOpenedFrom
import com.iptv.tv.service.RecordingManager
import com.iptv.tv.service.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.iptv.tv.ui.launchSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

data class GuideUiState(
    val dayStartMs: Long = startOfDay(System.currentTimeMillis()),
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val selectedCategoryName: String? = null,
    val channels: List<Channel> = emptyList(),
    val programmes: Map<String, List<Programme>> = emptyMap(),
    val favouritesOnly: Boolean = false,
    val selected: Programme? = null,
    val selectedChannel: Channel? = null,
    val loading: Boolean = false,
    val jumpToNowToken: Int = 0,
    val emptyMessage: String? = null,
    val hasIptvLogin: Boolean = false,
    val timeshiftWhileLive: Boolean = false,
)

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val profileRepository: ProfileRepository,
    private val preferences: AppPreferences,
    private val reminderScheduler: ReminderScheduler,
    private val recordingManager: RecordingManager,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = _state.asStateFlow()
    private var reloadJob: kotlinx.coroutines.Job? = null

    init {
        launchSafely("GuideViewModel.timeshiftPref") {
            preferences.timeshiftWhileLive.collect { enabled ->
                _state.value = _state.value.copy(timeshiftWhileLive = enabled)
            }
        }
        reload()
    }

    fun jumpToNow() {
        _state.value = _state.value.copy(
            dayStartMs = startOfDay(System.currentTimeMillis()),
            jumpToNowToken = _state.value.jumpToNowToken + 1,
        )
        reload()
    }

    fun shiftDay(delta: Int) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = _state.value.dayStartMs
        cal.add(Calendar.DAY_OF_YEAR, delta)
        _state.value = _state.value.copy(dayStartMs = cal.timeInMillis)
        reload()
    }

    fun toggleFavouritesOnly() {
        _state.value = _state.value.copy(favouritesOnly = !_state.value.favouritesOnly)
        reload()
    }

    fun selectCategory(id: String) {
        _state.value = _state.value.copy(selectedCategoryId = id, selected = null, selectedChannel = null)
        reload()
    }

    fun clearCategory() {
        _state.value = _state.value.copy(
            selectedCategoryId = null,
            selectedCategoryName = null,
            channels = emptyList(),
            programmes = emptyMap(),
            selected = null,
            selectedChannel = null,
        )
        reload()
    }

    fun select(programme: Programme, channel: Channel) {
        _state.value = _state.value.copy(selected = programme, selectedChannel = channel)
    }

    fun dismissDetail() {
        _state.value = _state.value.copy(selected = null, selectedChannel = null)
    }

    fun watch(channel: Channel) {
        launchSafely("GuideViewModel.watch") {
            val url = iptvRepository.livePlaybackUrl(channel)
            playbackController.playLive(
                channel,
                url,
                _state.value.channels,
                openedFrom = PlaybackOpenedFrom.GUIDE,
                liveRailId = channel.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" },
            )
            preferences.setLastChannelStreamId(channel.streamId)
        }
    }

    fun remind(offsetMinutes: Int) {
        val prog = _state.value.selected ?: return
        val ch = _state.value.selectedChannel ?: return
        if (!IptvCapabilities.canRemind(ch, iptvRepository.hasCredentials())) return
        launchSafely("GuideViewModel.remind") {
            reminderScheduler.scheduleReminder(
                profileId = profileRepository.getActiveProfileId(),
                channelStreamId = ch.streamId,
                channelName = ch.name,
                programmeTitle = prog.title,
                programmeStartMs = prog.startTimeMs,
                offsetMinutes = offsetMinutes,
            )
            dismissDetail()
        }
    }

    fun record(startBuffer: Int, endBuffer: Int) {
        val prog = _state.value.selected ?: return
        val ch = _state.value.selectedChannel ?: return
        if (!IptvCapabilities.canRecord(ch, iptvRepository.hasCredentials())) return
        launchSafely("GuideViewModel.record") {
            val start = if (startBuffer >= 0) startBuffer else preferences.recordingStartBufferMinutes.first()
            val end = if (endBuffer >= 0) endBuffer else preferences.recordingEndBufferMinutes.first()
            recordingManager.startOrSchedule(ch, prog, start, end, prog.isLiveNow)
            dismissDetail()
        }
    }

    fun watchFromStart() {
        val prog = _state.value.selected ?: return
        val ch = _state.value.selectedChannel ?: return
        val hasLogin = iptvRepository.hasCredentials()
        val catchup = CatchupAvailability.watchCatchup(ch, prog, hasLogin)
        val restart = CatchupAvailability.restartProgramme(
            ch,
            prog,
            _state.value.timeshiftWhileLive,
            hasLogin,
        )
        if (!catchup && !restart) return
        launchSafely("GuideViewModel.watchFromStart") {
            val durationMin = ((prog.endTimeMs - prog.startTimeMs) / 60_000L).toInt().coerceAtLeast(1)
            val url = runCatching { iptvRepository.timeshiftUrl(ch, prog.startTimeMs, durationMin) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return@launchSafely
            playbackController.play(
                com.iptv.tv.player.PlaybackSession(
                    type = com.iptv.tv.domain.model.ContentType.MOVIE,
                    streamId = ch.streamId,
                    title = ch.name,
                    subtitle = prog.title,
                    url = url,
                    posterUrl = ch.logoUrl,
                    contentKey = "catchup_${ch.streamId}_${prog.startTimeMs}",
                    channel = ch,
                    headers = com.iptv.tv.player.PlaybackController.playbackHeaders(ch, url),
                    openedFrom = PlaybackOpenedFrom.GUIDE,
                ),
            )
            dismissDetail()
        }
    }

    fun refreshEpg() {
        launchSafely("GuideViewModel.refreshEpg", onError = { clearLoading() }) {
            _state.value = _state.value.copy(loading = true)
            epgRepository.refreshEpg()
            reload()
        }
    }

    private fun clearLoading() {
        _state.value = _state.value.copy(loading = false)
    }

    private fun reload() {
        // Newest request wins: day / category changes cancel a slower load still in flight.
        reloadJob?.cancel()
        reloadJob = launchSafely("GuideViewModel.reload", onError = { clearLoading() }) {
            _state.value = _state.value.copy(loading = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val start = _state.value.dayStartMs
                    val end = start + 24 * 60 * 60 * 1000L
                    val epgIds = epgRepository.epgChannelIdsOnDay(start, end)
                    val profileId = profileRepository.getActiveProfileId()
                    val favIds = profileRepository.observeFavouriteChannelIds(profileId).first().toSet()
                    val hidden = profileRepository.observeHiddenChannelIds(profileId).first()
                    val allCats = profileRepository.applyCategoryOverrides(
                        profileId,
                        FeedType.LIVE,
                        iptvRepository.getLiveCategories(),
                    )
                    val catIdsWithGuide = iptvRepository.liveCategoryIdsWithEpg(epgIds)
                    val categories = WatchRanking.liveRails(
                        categories = allCats.filter { it.id in catIdsWithGuide },
                        channels = emptyList(),
                        watchMs = emptyMap(),
                        filter = LiveSourceFilter.ALL,
                    )
                    val selectedId = _state.value.selectedCategoryId
                        ?.takeIf { id -> categories.any { it.id == id } }
                    if (selectedId == null) {
                        return@withContext GuideLoad(
                            categories = categories,
                            selectedCategoryId = null,
                            selectedCategoryName = null,
                            channels = emptyList(),
                            programmes = emptyMap(),
                            emptyMessage = when {
                                epgIds.isEmpty() ->
                                    if (end <= System.currentTimeMillis()) {
                                        "No catch-up data for this day. Try a more recent day, or refresh the EPG."
                                    } else {
                                        "No guide data yet. Connect IPTV and refresh the EPG."
                                    }
                                categories.isEmpty() ->
                                    "No lists have guide data for this day."
                                else -> null
                            },
                        )
                    }
                    val inCat = iptvRepository.getChannelsByCategory(selectedId)
                        .filter { it.streamId !in hidden }
                        .filter { ch ->
                            val epgId = ch.epgChannelId?.takeIf { it.isNotBlank() } ?: return@filter false
                            epgId in epgIds
                        }
                    var channels = if (_state.value.favouritesOnly) {
                        inCat.filter { it.streamId in favIds }
                    } else {
                        inCat.sortedBy { if (it.streamId in favIds) 0 else 1 }
                    }.distinctBy { it.streamId }
                    val programmes = epgRepository
                        .getProgrammesForDay(channels.mapNotNull { it.epgChannelId }, start, end)
                        .groupBy { it.channelId }
                    channels = channels.filter { ch ->
                        programmes[ch.epgChannelId].orEmpty().isNotEmpty()
                    }
                    GuideLoad(
                        categories = categories,
                        selectedCategoryId = selectedId,
                        selectedCategoryName = categories.find { it.id == selectedId }?.name,
                        channels = channels,
                        programmes = programmes,
                        emptyMessage = if (channels.isEmpty()) {
                            "No channels in this list have guide data."
                        } else {
                            null
                        },
                    )
                }
            }.onSuccess { load ->
                _state.value = _state.value.copy(
                    categories = load.categories,
                    selectedCategoryId = load.selectedCategoryId,
                    selectedCategoryName = load.selectedCategoryName,
                    channels = load.channels,
                    programmes = load.programmes,
                    loading = false,
                    emptyMessage = load.emptyMessage,
                    hasIptvLogin = iptvRepository.hasCredentials(),
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }
}

fun startOfDay(ms: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private data class GuideLoad(
    val categories: List<Category>,
    val selectedCategoryId: String?,
    val selectedCategoryName: String?,
    val channels: List<Channel>,
    val programmes: Map<String, List<Programme>>,
    val emptyMessage: String?,
)
