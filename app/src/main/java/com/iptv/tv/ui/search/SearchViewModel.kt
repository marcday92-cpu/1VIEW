package com.iptv.tv.ui.search

import androidx.lifecycle.ViewModel
import com.iptv.tv.data.db.RecordingDao
import com.iptv.tv.data.parental.ParentalLock
import com.iptv.tv.domain.model.AdultContent
import com.iptv.tv.data.repository.EpgRepository
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.SearchResult
import com.iptv.tv.domain.model.SearchResultType
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackOpenedFrom
import com.iptv.tv.player.PlaybackSession
import com.iptv.tv.domain.model.SeriesDetail
import com.iptv.tv.domain.model.Episode
import com.iptv.tv.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.iptv.tv.ui.launchSafely
import com.iptv.tv.util.TitleMatch
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val status: String = "",
    val seriesDetail: SeriesDetail? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val playbackController: PlaybackController,
    private val profileRepository: ProfileRepository,
    private val recordingDao: RecordingDao,
    private val parentalLock: ParentalLock,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun updateQuery(value: String) {
        _state.value = _state.value.copy(query = value, status = "")
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _state.value = _state.value.copy(results = emptyList(), loading = false)
            return
        }
        searchJob = launchSafely("SearchViewModel.debounce") {
            delay(300)
            search(value.trim())
        }
    }

    private suspend fun search(q: String) {
            _state.value = _state.value.copy(loading = true, status = "")
            val hideAdult = parentalLock.adultBlocked()
            val ranked = withContext(Dispatchers.Default) {
                val remote = runCatching { iptvRepository.search(q) }.getOrDefault(emptyList())
                    .filter { !hideAdult || !it.adult }
                val epg = runCatching { epgRepository.searchProgrammes(q) }.getOrDefault(emptyList()).map {
                    SearchResult(
                        type = SearchResultType.PROGRAMME,
                        id = it.id,
                        title = it.title,
                        subtitle = it.channelId,
                        imageUrl = null,
                        streamId = it.channelStreamId,
                        programmeStartMs = it.startTimeMs,
                    )
                }
                val recordings = if (com.iptv.tv.domain.model.IptvCapabilities.showRecordingUi(iptvRepository.hasCredentials())) {
                    TitleMatch.stems(q).flatMap { stem ->
                        runCatching { recordingDao.search(stem) }.getOrDefault(emptyList())
                    }.distinctBy { it.id }.map {
                        SearchResult(
                            type = SearchResultType.RECORDING,
                            id = it.id.toString(),
                            title = it.title,
                            subtitle = it.channelName,
                            imageUrl = null,
                        )
                    }
                } else {
                    emptyList()
                }
                (remote + epg + recordings)
                    .distinctBy { "${it.type}_${it.id}" }
                    .map { result ->
                        result to TitleMatch.score(q, result.title, result.subtitle)
                    }
                    .filter { it.second > 0 }
                    .sortedWith(
                        compareByDescending<Pair<SearchResult, Int>> { it.second }
                            .thenBy { it.first.title.lowercase() },
                    )
                    .take(40)
                    .map { it.first }
            }
            if (_state.value.query.trim() != q) return
            _state.value = _state.value.copy(
                results = ranked,
                loading = false,
                status = if (ranked.isEmpty()) "No results for “$q”" else "",
            )
    }

    fun open(result: SearchResult) {
        launchSafely("SearchViewModel.open") {
            when (result.type) {
                SearchResultType.CHANNEL -> {
                    val streamId = result.streamId ?: return@launchSafely
                    val ch = iptvRepository.getChannel(streamId) ?: return@launchSafely
                    if (parentalLock.adultBlocked() && AdultContent.isAdultChannel(ch)) return@launchSafely
                    val playlist = iptvRepository.getChannelsByCategory(ch.categoryId).ifEmpty { listOf(ch) }
                    playbackController.playLive(
                        ch,
                        iptvRepository.livePlaybackUrl(ch),
                        playlist,
                        openedFrom = PlaybackOpenedFrom.SEARCH,
                        liveRailId = ch.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" },
                    )
                }
                SearchResultType.PROGRAMME -> {
                    if ((result.programmeStartMs ?: 0) > System.currentTimeMillis()) {
                        _state.value = _state.value.copy(
                            status = "${result.title} is not live yet. Open it in Guide to set a reminder.",
                        )
                        return@launchSafely
                    }
                    val streamId = result.streamId ?: return@launchSafely
                    val ch = iptvRepository.getChannel(streamId) ?: return@launchSafely
                    if (parentalLock.adultBlocked() && AdultContent.isAdultChannel(ch)) return@launchSafely
                    val playlist = iptvRepository.getChannelsByCategory(ch.categoryId).ifEmpty { listOf(ch) }
                    playbackController.playLive(
                        ch,
                        iptvRepository.livePlaybackUrl(ch),
                        playlist,
                        openedFrom = PlaybackOpenedFrom.SEARCH,
                        liveRailId = ch.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" },
                    )
                }
                SearchResultType.MOVIE -> {
                    val streamId = result.streamId ?: return@launchSafely
                    val item = iptvRepository.getVodItem(streamId)
                    if (item != null && parentalLock.adultBlocked() && AdultContent.isAdultVod(item)) return@launchSafely
                    val resume = profileRepository.getResumePosition(profileRepository.getActiveProfileId(), "vod_$streamId") ?: 0
                    val playback = iptvRepository.resolveVodPlayback(streamId, item?.containerExtension ?: "mp4")
                    playbackController.play(
                        PlaybackSession(
                            type = ContentType.MOVIE,
                            streamId = streamId,
                            title = result.title,
                            url = playback.url,
                            posterUrl = result.imageUrl,
                            contentKey = "vod_$streamId",
                            startPositionMs = resume,
                            year = item?.year ?: result.year,
                            filename = result.title,
                            containerExtension = playback.containerExtension,
                            fallbackUrl = playback.fallbackUrl,
                            headers = playback.headers,
                            openedFrom = PlaybackOpenedFrom.SEARCH,
                            isFree = playback.isFree,
                        ),
                    )
                }
                SearchResultType.SERIES -> {
                    val id = result.id.toIntOrNull() ?: return@launchSafely
                    if (parentalLock.adultBlocked() && result.adult) return@launchSafely
                    val detail = iptvRepository.getSeriesDetail(id)
                    val live = detail.seasons.values.flatten()
                    if (live.size == 1 && live.first().isFree && live.first().streamUrl != null) {
                        playEpisode(live.first(), detail)
                    } else {
                        _state.value = _state.value.copy(seriesDetail = detail)
                    }
                }
                SearchResultType.RECORDING -> {
                    val rec = recordingDao.getById(result.id.toLongOrNull() ?: return@launchSafely) ?: return@launchSafely
                    playbackController.play(
                        PlaybackSession(
                            type = ContentType.MOVIE,
                            streamId = rec.channelStreamId,
                            title = rec.title,
                            url = rec.filePath,
                            contentKey = "rec_${rec.id}",
                            startPositionMs = rec.positionMs,
                            openedFrom = PlaybackOpenedFrom.SEARCH,
                        ),
                    )
                }
            }
        }
    }

    fun dismissSeries() {
        _state.value = _state.value.copy(seriesDetail = null)
    }

    fun playEpisode(episode: Episode, detail: SeriesDetail) {
        // Close the episode sheet first so it is not left sitting on top of the player.
        _state.value = _state.value.copy(seriesDetail = null)
        launchSafely("SearchViewModel.playEpisode") {
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
                    openedFrom = PlaybackOpenedFrom.SEARCH,
                    isFree = playback.isFree,
                ),
            )
        }
    }
}
