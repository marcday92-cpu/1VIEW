package com.iptv.tv.ui.catchup

import androidx.lifecycle.ViewModel
import com.iptv.tv.data.parental.ParentalLock
import com.iptv.tv.data.repository.EpgRepository
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.AdultContent
import com.iptv.tv.domain.model.CatchupAvailability
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackOpenedFrom
import com.iptv.tv.ui.launchSafely
import com.iptv.tv.util.formatDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

data class CatchupDayGroup(
    val label: String,
    val programmes: List<Programme>,
)

data class CatchupUiState(
    val channels: List<Channel> = emptyList(),
    val selected: Channel? = null,
    val days: List<CatchupDayGroup> = emptyList(),
    /** Stream id the [days] list belongs to; stale rows stay on screen while the next channel loads. */
    val listingsStreamId: Int? = null,
    val loading: Boolean = true,
    val loadingProgrammes: Boolean = false,
    val hasIptvLogin: Boolean = false,
    val emptyMessage: String? = null,
    val programmesEmptyMessage: String? = null,
    val archiveHint: String? = null,
)

@HiltViewModel
class CatchupViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val profileRepository: ProfileRepository,
    private val playbackController: PlaybackController,
    private val parentalLock: ParentalLock,
) : ViewModel() {

    private val _state = MutableStateFlow(CatchupUiState())
    val state: StateFlow<CatchupUiState> = _state.asStateFlow()
    private val listingsCache = mutableMapOf<Int, List<Programme>>()
    private var programmesJob: Job? = null

    init {
        launchSafely("CatchupViewModel.channels") {
            iptvRepository.observeChannelCount().collect { reloadChannels() }
        }
    }

    fun selectChannel(streamId: Int) {
        val channel = _state.value.channels.find { it.streamId == streamId } ?: return
        if (_state.value.selected?.streamId == streamId) return
        _state.value = _state.value.copy(
            selected = channel,
            archiveHint = archiveHint(channel),
            programmesEmptyMessage = null,
        )
        val cached = listingsCache[streamId]
        if (cached != null) {
            programmesJob?.cancel()
            applyProgrammes(channel, cached)
            return
        }
        loadProgrammes(channel)
    }

    fun play(programme: Programme) {
        val channel = _state.value.selected ?: return
        if (_state.value.listingsStreamId != channel.streamId) return
        if (!CatchupAvailability.watchCatchup(channel, programme, iptvRepository.hasCredentials())) return
        launchSafely("CatchupViewModel.play") {
            if (parentalLock.adultBlocked() && AdultContent.isAdultChannel(channel)) return@launchSafely
            val durationMin = ((programme.endTimeMs - programme.startTimeMs) / 60_000L).toInt().coerceAtLeast(1)
            val url = runCatching { iptvRepository.timeshiftUrl(channel, programme.startTimeMs, durationMin) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return@launchSafely
            playbackController.play(
                com.iptv.tv.player.PlaybackSession(
                    type = ContentType.MOVIE,
                    streamId = channel.streamId,
                    title = channel.name,
                    subtitle = programme.title,
                    url = url,
                    posterUrl = channel.logoUrl,
                    contentKey = "catchup_${channel.streamId}_${programme.startTimeMs}",
                    channel = channel,
                    headers = PlaybackController.playbackHeaders(channel, url),
                    openedFrom = PlaybackOpenedFrom.CATCHUP,
                ),
            )
        }
    }

    private suspend fun reloadChannels() {
        val loggedIn = iptvRepository.hasCredentials()
        if (!loggedIn) {
            listingsCache.clear()
            _state.value = CatchupUiState(
                loading = false,
                hasIptvLogin = false,
                emptyMessage = "Sign in to IPTV to watch catch-up. Free channels do not keep an archive.",
            )
            return
        }
        // Do not flip loading=true after the first fill: that tears down both lists and the
        // window then gives the remote to the profile icon.
        if (_state.value.channels.isEmpty()) {
            _state.value = _state.value.copy(loading = true, hasIptvLogin = true)
        } else {
            _state.value = _state.value.copy(hasIptvLogin = true)
        }
        val channels = withContext(Dispatchers.IO) {
            val profileId = profileRepository.getActiveProfileId()
            val hidden = profileRepository.observeHiddenChannelIds(profileId).first()
            val hideAdult = parentalLock.adultBlocked()
            iptvRepository.getArchiveChannels()
                .filter { it.streamId !in hidden }
                .filter { !hideAdult || !AdultContent.isAdultChannel(it) }
        }
        val previousId = _state.value.selected?.streamId
        val listingsId = _state.value.listingsStreamId
        val selected = _state.value.selected?.let { prev -> channels.find { it.streamId == prev.streamId } }
            ?: channels.firstOrNull()
        _state.value = _state.value.copy(
            channels = channels,
            selected = selected,
            loading = false,
            archiveHint = selected?.let { archiveHint(it) },
            emptyMessage = if (channels.isEmpty()) {
                "This IPTV account has no catch-up channels. Only live streams with a TV archive appear here."
            } else {
                null
            },
            days = if (selected == null) emptyList() else _state.value.days,
            listingsStreamId = if (selected == null) null else listingsId,
        )
        if (selected == null) return
        // A channel-count tick must not cancel an in-flight listing or yank the right-hand list.
        if (listingsId == selected.streamId && _state.value.days.isNotEmpty()) return
        if (previousId == selected.streamId && programmesJob?.isActive == true) return
        val cached = listingsCache[selected.streamId]
        if (cached != null) {
            applyProgrammes(selected, cached)
        } else {
            loadProgrammes(selected, debounce = previousId != null)
        }
    }

    private fun loadProgrammes(channel: Channel, debounce: Boolean = true) {
        programmesJob?.cancel()
        programmesJob = launchSafely("CatchupViewModel.programmes", onError = {
            _state.value = _state.value.copy(
                loadingProgrammes = false,
                programmesEmptyMessage = "Could not load catch-up for this channel.",
            )
        }) {
            if (debounce) delay(150)
            if (_state.value.selected?.streamId != channel.streamId) return@launchSafely
            val cached = listingsCache[channel.streamId]
            if (cached != null) {
                applyProgrammes(channel, cached)
                return@launchSafely
            }
            _state.value = _state.value.copy(loadingProgrammes = true, programmesEmptyMessage = null)
            val listings = withContext(Dispatchers.IO) {
                val remote = iptvRepository.getCatchupTable(channel)
                val source = if (remote.isNotEmpty()) remote else localArchive(channel)
                CatchupAvailability.catchupProgrammes(channel, source, hasIptvLogin = true)
            }
            listingsCache[channel.streamId] = listings
            applyProgrammes(channel, listings)
        }
    }

    private suspend fun localArchive(channel: Channel): List<Programme> {
        val epgId = channel.epgChannelId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val now = System.currentTimeMillis()
        val window = CatchupAvailability.archiveWindowMs(channel)
        val from = if (window > 0) now - window else 0L
        return epgRepository.getProgrammesForChannel(epgId, from, now)
    }

    private fun applyProgrammes(channel: Channel, listings: List<Programme>) {
        if (_state.value.selected?.streamId != channel.streamId) return
        val grouped = groupByDay(listings)
        _state.value = _state.value.copy(
            days = grouped,
            listingsStreamId = channel.streamId,
            loadingProgrammes = false,
            programmesEmptyMessage = if (listings.isEmpty()) {
                "No finished programmes in this channel's archive window."
            } else {
                null
            },
        )
    }

    private fun archiveHint(channel: Channel): String {
        val days = CatchupAvailability.archiveWindowDays(channel)
        return if (days > 0) "Last $days day${if (days == 1) "" else "s"}" else "Archive"
    }

    private fun groupByDay(programmes: List<Programme>): List<CatchupDayGroup> {
        if (programmes.isEmpty()) return emptyList()
        val today = startOfLocalDay(System.currentTimeMillis())
        val yesterday = today - DAY_MS
        return programmes
            .sortedByDescending { it.startTimeMs }
            .groupBy { startOfLocalDay(it.startTimeMs) }
            .toSortedMap(compareByDescending { it })
            .map { (day, rows) ->
                val label = when (day) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> formatDay(day)
                }
                CatchupDayGroup(label, rows)
            }
    }

    private fun startOfLocalDay(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
