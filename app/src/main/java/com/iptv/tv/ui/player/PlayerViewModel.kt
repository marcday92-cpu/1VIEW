package com.iptv.tv.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.IptvCapabilities
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.Episode
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.domain.model.SubtitleCandidate
import com.iptv.tv.domain.model.SubtitleQuery
import com.iptv.tv.player.AspectMode
import com.iptv.tv.player.MediaTrack
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackSession
import com.iptv.tv.player.SeriesEpisodeOrder
import com.iptv.tv.player.TvPlayerManager
import com.iptv.tv.service.ActiveRecording
import com.iptv.tv.service.RecordingManager
import com.iptv.tv.service.ReminderScheduler
import com.iptv.tv.subtitle.SubtitleAutoSyncEngine
import com.iptv.tv.subtitle.SubtitleCatalog
import com.iptv.tv.subtitle.SubtitleCue
import com.iptv.tv.subtitle.SubtitleException
import com.iptv.tv.subtitle.SubtitleLiveAligner
import com.iptv.tv.subtitle.SubtitleMatch
import com.iptv.tv.subtitle.SubtitleSidecarStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import com.iptv.tv.ui.launchSafely
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class NextEpisodePrompt(
    val seriesName: String,
    val seriesId: Int,
    val episodeTitle: String,
    val seasonNum: Int,
    val episodeNum: Int,
    val secondsLeft: Int,
    val episode: Episode,
)

data class OnlineSubtitlesState(
    val query: String = "",
    val searching: Boolean = false,
    val downloadingId: String? = null,
    val results: List<SubtitleCandidate> = emptyList(),
    val status: String = "",
)
data class SubtitleAppearance(
    val textSizeSp: Float = 24f,
    val bottomPaddingFraction: Float = 0.08f,
    val backgroundBox: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val playerManager: TvPlayerManager,
    private val playbackController: PlaybackController,
    private val iptvRepository: IptvRepository,
    private val profileRepository: ProfileRepository,
    private val reminderScheduler: ReminderScheduler,
    private val recordingManager: RecordingManager,
    private val subtitleCatalog: SubtitleCatalog,
    private val autoSyncEngine: SubtitleAutoSyncEngine,
    private val sidecarStore: SubtitleSidecarStore,
    private val preferences: AppPreferences,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val session = playbackController.session
    val lastChannel = playbackController.lastChannel
    val hasIptvLogin: Boolean get() = iptvRepository.hasCredentials()
    val recording: StateFlow<ActiveRecording?> = recordingManager.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _now = MutableStateFlow<Programme?>(null)
    val now: StateFlow<Programme?> = _now.asStateFlow()
    private val _next = MutableStateFlow<Programme?>(null)
    val next: StateFlow<Programme?> = _next.asStateFlow()
    private val _warning = MutableStateFlow<String?>(null)
    val connectionWarning: StateFlow<String?> = _warning.asStateFlow()
    private val _candidates = MutableStateFlow<List<SubtitleCandidate>>(emptyList())
    val candidates: StateFlow<List<SubtitleCandidate>> = _candidates.asStateFlow()
    private val _onlineSubtitles = MutableStateFlow(OnlineSubtitlesState())
    val onlineSubtitles: StateFlow<OnlineSubtitlesState> = _onlineSubtitles.asStateFlow()
    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()
    private val _recent = MutableStateFlow<List<Channel>>(emptyList())
    val recent: StateFlow<List<Channel>> = _recent.asStateFlow()
    private val _subtitleUrl = MutableStateFlow("")
    val subtitleUrl: StateFlow<String> = _subtitleUrl.asStateFlow()
    private val _captionLines = MutableStateFlow<List<String>>(emptyList())
    val captionLines: StateFlow<List<String>> = _captionLines.asStateFlow()
    private val _nextEpisode = MutableStateFlow<NextEpisodePrompt?>(null)
    val nextEpisode: StateFlow<NextEpisodePrompt?> = _nextEpisode.asStateFlow()

    val subtitleAppearance: StateFlow<SubtitleAppearance> = profileRepository.observeActiveProfile()
        .map { profile ->
            SubtitleAppearance(
                textSizeSp = ((profile?.subtitleFontSize ?: 1f) * 24f).coerceIn(14f, 44f),
                bottomPaddingFraction = (1f - (profile?.subtitlePosition ?: 0.9f)).coerceIn(0f, 0.4f),
                backgroundBox = profile?.subtitleBackground ?: false,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleAppearance())

    private var loadedSubtitle: File? = null
    /** Stable id for saving/restoring delay (SubDL candidate id, otherwise file name). */
    private var loadedSubtitleId: String? = null
    private var parsedSidecarCues: List<SubtitleCue> = emptyList()
    private val showSidecarOverlay = AtomicBoolean(false)
    private val liveAligner = SubtitleLiveAligner()
    private var alignJob: Job? = null
    private var autoCaptionsJob: Job? = null
    private var warningJob: Job? = null
    private var nextEpisodeJob: Job? = null
    private var autoCaptionsFor: String? = null
    private var nextEpisodeFor: String? = null
    private val userSetDelay = AtomicBoolean(false)
    private val userDisabledCaptions = AtomicBoolean(false)
    private val userPickedCaptions = AtomicBoolean(false)

    private val _showTimingAfterLoad = MutableStateFlow(false)
    val showTimingAfterLoad: StateFlow<Boolean> = _showTimingAfterLoad.asStateFlow()

    fun consumeShowTimingAfterLoad() {
        _showTimingAfterLoad.value = false
    }

    init {
        launchSafely("PlayerViewModel.observeSubtitleLanguage") {
            profileRepository.observeActiveProfile().collect { profile ->
                runCatching { playerManager.setPreferredTextLanguage(profile?.subtitleLanguage ?: "en") }
                runCatching {
                    val audio = profileRepository.getPreferredAudioLanguage()
                    playerManager.setPreferredAudioLanguage(audio)
                }
            }
        }
        launchSafely("PlayerViewModel.observeSessionEpg") {
            // collectLatest: a slow short-EPG reply for channel A is dropped once the
            // user has zapped to channel B, so A's programme never overwrites B's.
            playbackController.session.collectLatest { current ->
                _now.value = null
                _next.value = null
                if (current?.contentKey != autoCaptionsFor) {
                    autoCaptionsJob?.cancel()
                    alignJob?.cancel()
                    nextEpisodeJob?.cancel()
                    _nextEpisode.value = null
                    if (current?.contentKey != nextEpisodeFor) nextEpisodeFor = null
                    loadedSubtitle = null
                    loadedSubtitleId = null
                    parsedSidecarCues = emptyList()
                    showSidecarOverlay.set(false)
                    _captionLines.value = emptyList()
                    userDisabledCaptions.set(false)
                    userPickedCaptions.set(false)
                    autoCaptionsFor = current?.contentKey
                    if (current != null && current.type != ContentType.LIVE) {
                        autoCaptionsJob = launchSafely("PlayerViewModel.autoEnglishCaptions") {
                            applyAutoEnglishCaptions(current)
                        }
                    }
                    if (current != null) {
                        launchSafely("PlayerViewModel.restoreAudioTrack") {
                            restoreAudioTrack(current.contentKey)
                        }
                    }
                }
                if (current?.type == ContentType.LIVE) {
                    val epg = runCatching { iptvRepository.getShortEpg(current.streamId, 4) }
                        .getOrDefault(emptyList())
                    _now.value = epg.getOrNull(0)
                    _next.value = epg.getOrNull(1)
                }
            }
        }
        launchSafely("PlayerViewModel.observeRecent") {
            profileRepository.observeActiveProfileId()
                .flatMapLatest { raw ->
                    val pid = raw ?: profileRepository.getActiveProfileId()
                    profileRepository.observeRecentChannels(pid)
                }
                .collect { ids ->
                    runCatching {
                        val byId = iptvRepository.getChannelsByIds(ids).associateBy { it.streamId }
                        _recent.value = ids.mapNotNull { byId[it] }
                    }
                }
        }
        launchSafely("PlayerViewModel.captionOverlay") {
            while (true) {
                updateCaptionLines()
                delay(80)
            }
        }
        launchSafely("PlayerViewModel.resumeTicker") {
            while (true) {
                delay(1_000)
                playerManager.tickPosition()
                if (playerManager.positionMs % 15_000L >= 1_000L) continue
                runCatching {
                    val s = playbackController.session.value
                    if (s != null && s.type != ContentType.LIVE) {
                        profileRepository.saveResume(
                            profileRepository.getActiveProfileId(),
                            s.contentKey,
                            s.type,
                            playerManager.positionMs,
                            playerManager.durationMs,
                            title = listOfNotNull(s.title, s.subtitle).joinToString(" · "),
                            posterUrl = s.posterUrl,
                            streamId = s.streamId,
                            seasonNum = s.season,
                            episodeNum = s.episode,
                            year = s.year,
                            seriesId = s.seriesId,
                            filename = s.filename,
                            containerExtension = s.containerExtension,
                        )
                    }
                }
            }
        }
        launchSafely("PlayerViewModel.nextEpisode") {
            playerManager.state.collect { st ->
                val s = playbackController.session.value
                if (s?.type != ContentType.EPISODE) return@collect
                val remaining = st.durationMs - st.positionMs
                val nearEnd = st.durationMs > 60_000L && remaining in 1..15_000
                if (!st.playbackEnded && !nearEnd) return@collect
                if (nextEpisodeFor == s.contentKey || _nextEpisode.value != null) return@collect
                if (!preferences.autoplayNextEpisode.first()) return@collect
                nextEpisodeFor = s.contentKey
                startNextEpisodeCountdown(s)
            }
        }
    }

    fun zap(delta: Int) {
        val current = playbackController.session.value ?: return
        val playlist = current.playlist
        if (playlist.isEmpty()) return
        val index = playlist.indexOfFirst { it.streamId == current.streamId }
        val nextCh = playlist[(index + delta).mod(playlist.size)]
        if (recordingManager.wouldConflict(nextCh.streamId)) {
            showWarning(recordingManager.conflictMessage())
            return
        }
        playbackController.zap(delta)
        launchSafely("PlayerViewModel.zap") {
            playbackController.session.value?.channel?.let {
                preferences.setLastChannelStreamId(it.streamId)
            }
        }
    }

    fun zapLast() {
        val previous = playbackController.lastChannel.value ?: return
        if (recordingManager.wouldConflict(previous.streamId)) {
            showWarning(recordingManager.conflictMessage())
            return
        }
        playbackController.zapToLast()
        launchSafely("PlayerViewModel.zapLast") {
            playbackController.session.value?.channel?.let {
                preferences.setLastChannelStreamId(it.streamId)
            }
        }
    }

    fun nudgeAudioDelay(deltaMs: Long) {
        val next = playerManager.state.value.audioDelayMs + deltaMs
        playerManager.setAudioDelayMs(next)
    }

    fun resetAudioDelay() {
        playerManager.setAudioDelayMs(0)
    }

    fun selectAudio(track: MediaTrack) {
        playerManager.selectAudioTrack(track)
        val key = playbackController.session.value?.contentKey ?: return
        launchSafely("PlayerViewModel.saveAudioTrack") {
            preferences.setAudioTrackPref(key, track.id)
        }
    }

    fun remind(offset: Int) {
        val s = playbackController.session.value ?: return
        // The current programme has already started; a reminder only makes sense for the next one.
        val prog = _next.value ?: _now.value?.takeIf { it.startTimeMs > System.currentTimeMillis() } ?: run {
            showWarning("Nothing to remind you about yet: no upcoming programme in the guide.")
            return
        }
        if (!IptvCapabilities.canRemind(s.channel?.source, iptvRepository.hasCredentials())) return
        launchSafely("PlayerViewModel.remind", onError = { showWarning("Could not set the reminder.") }) {
            reminderScheduler.scheduleReminder(
                profileRepository.getActiveProfileId(),
                s.streamId,
                s.title,
                prog.title,
                prog.startTimeMs,
                offset,
            )
        }
    }

    fun recordNow() {
        val s = playbackController.session.value ?: return
        val ch = s.channel ?: return
        if (!IptvCapabilities.canRecord(ch, iptvRepository.hasCredentials())) return
        launchSafely("PlayerViewModel.recordNow", onError = { showWarning("Could not start the recording.") }) {
            val prog = _now.value
            val startBuf = if (prog?.isLiveNow == true) 0 else preferences.recordingStartBufferMinutes.first()
            val endBuf = preferences.recordingEndBufferMinutes.first()
            val result = recordingManager.startOrSchedule(ch, prog, startBuf, endBuf, true)
            result.exceptionOrNull()?.message?.let(::showWarning)
        }
    }

    fun openOnlineSubtitles() {
        val s = playbackController.session.value ?: return
        val suggested = defaultSubtitleQuery(s)
        _onlineSubtitles.value = OnlineSubtitlesState(
            query = suggested,
            status = if (suggested.isBlank()) "Type a title, then search." else "Searching…",
        )
        if (suggested.isNotBlank()) searchOnlineSubtitles()
    }

    fun setOnlineSubtitleQuery(value: String) {
        _onlineSubtitles.value = _onlineSubtitles.value.copy(query = value)
    }

    fun findSubtitles() = searchOnlineSubtitles()

    fun searchOnlineSubtitles() {
        val s = playbackController.session.value ?: return
        val typed = _onlineSubtitles.value.query.trim()
        if (typed.isBlank()) {
            _onlineSubtitles.value = _onlineSubtitles.value.copy(
                searching = false,
                results = emptyList(),
                status = "Type a title, then search.",
            )
            return
        }
        launchSafely("PlayerViewModel.findSubtitles", onError = { error ->
            _onlineSubtitles.value = _onlineSubtitles.value.copy(
                searching = false,
                results = emptyList(),
                status = error.message ?: "Could not search for subtitles.",
            )
            _syncStatus.value = _onlineSubtitles.value.status
        }) {
            _onlineSubtitles.value = _onlineSubtitles.value.copy(searching = true, status = "Searching SubDL…")
            val language = runCatching { profileRepository.getActiveSubtitleLanguage() }.getOrDefault("en")
            val query = buildSubtitleQuery(s, typed, language)
            val results = try {
                subtitleCatalog.search(query)
            } catch (error: SubtitleException) {
                _onlineSubtitles.value = _onlineSubtitles.value.copy(
                    searching = false,
                    results = emptyList(),
                    status = error.message ?: "Could not search for subtitles.",
                )
                _syncStatus.value = _onlineSubtitles.value.status
                return@launchSafely
            }
            _candidates.value = results
            _onlineSubtitles.value = _onlineSubtitles.value.copy(
                searching = false,
                results = results,
                status = when {
                    results.isEmpty() -> "No English subtitles found. Edit the search and try again."
                    else -> "${results.size} result${if (results.size == 1) "" else "s"} · closest match first"
                },
            )
            _syncStatus.value = _onlineSubtitles.value.status
        }
    }

    fun downloadSubtitle(candidate: SubtitleCandidate) {
        val s = playbackController.session.value ?: return
        launchSafely("PlayerViewModel.downloadSubtitle", onError = { error ->
            _onlineSubtitles.value = _onlineSubtitles.value.copy(
                downloadingId = null,
                status = error.message ?: "Could not download that subtitle.",
            )
            _syncStatus.value = _onlineSubtitles.value.status
        }) {
            _onlineSubtitles.value = _onlineSubtitles.value.copy(
                downloadingId = candidate.id,
                status = "Downloading ${candidate.fileName}…",
            )
            val dir = File(context.filesDir, "subtitles")
            val file = try {
                subtitleCatalog.download(candidate, dir, episodeOf(s))
            } catch (error: SubtitleException) {
                _onlineSubtitles.value = _onlineSubtitles.value.copy(
                    downloadingId = null,
                    status = error.message ?: "Could not download that subtitle.",
                )
                _syncStatus.value = _onlineSubtitles.value.status
                return@launchSafely
            }
            if (playbackController.session.value?.contentKey != s.contentKey) return@launchSafely
            userDisabledCaptions.set(false)
            userPickedCaptions.set(true)
            sidecarStore.remember(s.contentKey, file, candidate.id, candidate.language)
            bindSidecarFile(file, candidate.id)
            withContext(Dispatchers.Main) {
                playerManager.playWithSubtitle(s.url, file)
            }
            val restored = autoSyncEngine.getSavedOffset(s.contentKey, candidate.id)
                ?: autoSyncEngine.getSavedOffset(s.contentKey, file.name)
            if (restored != null && restored != 0L) {
                playerManager.setSubtitleDelay(restored)
                val status = "Loaded ${candidate.fileName} · restored ${restored} ms delay"
                _onlineSubtitles.value = _onlineSubtitles.value.copy(
                    downloadingId = null,
                    status = status,
                )
                _syncStatus.value = status
            } else {
                val status = "Loaded ${candidate.fileName}. Lining up with the soundtrack…"
                _onlineSubtitles.value = _onlineSubtitles.value.copy(
                    downloadingId = null,
                    status = status,
                )
                _syncStatus.value = status
                startLiveAlign(openTimingIfFailed = true)
                return@launchSafely
            }
            _showTimingAfterLoad.value = true
        }
    }

    fun setSubtitleUrl(value: String) { _subtitleUrl.value = value }

    fun loadSubtitleUrl() {
        val s = playbackController.session.value ?: return
        val url = _subtitleUrl.value.trim().ifBlank { return }
        launchSafely("PlayerViewModel.loadSubtitleUrl", onError = { _syncStatus.value = "Could not load subtitle URL" }) {
            val dest = withContext(Dispatchers.IO) {
                require(url.startsWith("https://") || url.startsWith("http://")) {
                    "Subtitle URL must start with https:// or http://"
                }
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "Subtitle download failed (${response.code})" }
                    val body = response.body ?: error("Subtitle response was empty")
                    val length = body.contentLength()
                    require(length < 0 || length <= MAX_SUBTITLE_BYTES) { "Subtitle file is too large" }
                    val ext = url.substringBefore('?').substringAfterLast('.', "srt")
                        .lowercase().takeIf { it in SUPPORTED_SUBTITLE_EXTENSIONS } ?: "srt"
                    val output = File(
                        File(context.filesDir, "subtitles").apply { mkdirs() },
                        "url_${s.contentKey.hashCode().toUInt()}.$ext",
                    )
                    body.byteStream().use { input ->
                        output.outputStream().use { sink ->
                            val copied = input.copyTo(sink, bufferSize = DEFAULT_BUFFER_SIZE)
                            require(copied <= MAX_SUBTITLE_BYTES) { "Subtitle file is too large" }
                        }
                    }
                    output
                }
            }
            if (playbackController.session.value?.contentKey != s.contentKey) return@launchSafely
            userDisabledCaptions.set(false)
            userPickedCaptions.set(true)
            sidecarStore.remember(s.contentKey, dest, dest.name)
            bindSidecarFile(dest, dest.name)
            withContext(Dispatchers.Main) {
                playerManager.playWithSubtitle(s.url, dest)
            }
            _syncStatus.value = "Loaded subtitle from URL. Lining up with the soundtrack…"
            startLiveAlign(openTimingIfFailed = true)
        }
    }

    fun autoSync() {
        if (loadedSubtitle == null) {
            _syncStatus.value = "Load a subtitle file first."
            return
        }
        userSetDelay.set(false)
        _syncStatus.value = "Lining up with the soundtrack…"
        startLiveAlign(openTimingIfFailed = true)
    }

    fun nudgeDelay(delta: Long) {
        alignJob?.cancel()
        userSetDelay.set(true)
        if (!playerManager.hasSidecarSubtitle()) {
            _syncStatus.value = "Timing can only be shifted on loaded subtitle files, " +
                "not captions built into the stream."
            return
        }
        val next = playerManager.getSubtitleDelay() + delta
        launchSafely("PlayerViewModel.nudgeDelay") {
            val changed = playerManager.setSubtitleDelay(next)
            if (!changed) _syncStatus.value = "Could not adjust this subtitle file."
            else {
                _syncStatus.value = "Delay ${next} ms"
                persistDelay(next)
            }
        }
    }

    fun resetDelay() {
        alignJob?.cancel()
        userSetDelay.set(true)
        if (!playerManager.hasSidecarSubtitle()) return
        launchSafely("PlayerViewModel.resetDelay") {
            if (playerManager.setSubtitleDelay(0)) {
                _syncStatus.value = "Delay 0 ms"
                persistDelay(0)
            }
        }
    }

    fun disableSubtitles() {
        autoCaptionsJob?.cancel()
        userDisabledCaptions.set(true)
        showSidecarOverlay.set(false)
        _captionLines.value = emptyList()
        playerManager.selectTextTrack(null)
        _syncStatus.value = "Subtitles off"
    }

    fun selectTextTrack(track: MediaTrack) {
        autoCaptionsJob?.cancel()
        userDisabledCaptions.set(false)
        userPickedCaptions.set(true)
        showSidecarOverlay.set(isSidecarTrack(track))
        playerManager.selectTextTrack(track)
        _syncStatus.value = "Subtitles: ${track.label}"
    }

    fun nudgeSubtitleSize(delta: Float) {
        launchSafely("PlayerViewModel.nudgeSubtitleSize") {
            profileRepository.setSubtitleTextSize(subtitleAppearance.value.textSizeSp + delta)
        }
    }

    fun toggleSubtitleBackground() {
        launchSafely("PlayerViewModel.toggleSubtitleBackground") {
            profileRepository.setSubtitleBackground(!subtitleAppearance.value.backgroundBox)
        }
    }

    fun setAspect(mode: AspectMode) = playerManager.setAspect(mode)

    fun retry() = playerManager.retry()

    fun switchEngine() = playerManager.switchEngine()

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun seekBy(deltaMs: Long) {
        val s = playbackController.session.value ?: return
        if (s.type == ContentType.LIVE) return
        playerManager.seekBy(deltaMs)
    }

    fun seekTo(positionMs: Long) {
        val s = playbackController.session.value ?: return
        if (s.type == ContentType.LIVE) return
        playerManager.seekTo(positionMs)
    }

    fun playRecent(channel: Channel) {
        if (recordingManager.wouldConflict(channel.streamId)) {
            showWarning(recordingManager.conflictMessage())
            return
        }
        val url = runCatching { iptvRepository.livePlaybackUrl(channel) }.getOrElse {
            showWarning("That channel has no stream to play.")
            return
        }
        playbackController.playLive(channel, url, playbackController.session.value?.playlist.orEmpty())
    }

    fun cancelNextEpisode() {
        nextEpisodeJob?.cancel()
        nextEpisodeJob = null
        _nextEpisode.value = null
    }

    fun playNextEpisodeManual() {
        val current = playbackController.session.value ?: return
        if (current.type != ContentType.EPISODE) return
        launchSafely("PlayerViewModel.playNextManual") {
            persistResume()
            val seriesId = current.seriesId ?: return@launchSafely
            val detail = runCatching { iptvRepository.getSeriesDetail(seriesId) }.getOrNull()
                ?: return@launchSafely
            val next = SeriesEpisodeOrder.nextAfter(
                detail.seasons,
                current.streamId,
                current.season,
                current.episode,
            ) ?: return@launchSafely
            playEpisodeSession(
                NextEpisodePrompt(
                    seriesName = detail.name,
                    seriesId = detail.seriesId,
                    episodeTitle = next.title,
                    seasonNum = next.seasonNum,
                    episodeNum = next.episodeNum,
                    secondsLeft = 0,
                    episode = next,
                ),
                current,
            )
        }
    }

    fun playNextEpisodeNow() {
        val prompt = _nextEpisode.value ?: return
        val current = playbackController.session.value
        nextEpisodeJob?.cancel()
        nextEpisodeJob = null
        _nextEpisode.value = null
        launchSafely("PlayerViewModel.playNextEpisode") {
            playEpisodeSession(prompt, current)
        }
    }

    fun close() {
        cancelNextEpisode()
        persistResume()
        playbackController.close()
    }

    private fun startNextEpisodeCountdown(session: PlaybackSession) {
        nextEpisodeJob?.cancel()
        nextEpisodeJob = launchSafely("PlayerViewModel.nextEpisodeCountdown") {
            val seriesId = session.seriesId ?: return@launchSafely
            val detail = runCatching { iptvRepository.getSeriesDetail(seriesId) }.getOrNull()
                ?: return@launchSafely
            val next = SeriesEpisodeOrder.nextAfter(
                detail.seasons,
                session.streamId,
                session.season,
                session.episode,
            ) ?: return@launchSafely
            var seconds = NEXT_EPISODE_COUNTDOWN_SEC
            _nextEpisode.value = NextEpisodePrompt(
                seriesName = detail.name,
                seriesId = detail.seriesId,
                episodeTitle = next.title,
                seasonNum = next.seasonNum,
                episodeNum = next.episodeNum,
                secondsLeft = seconds,
                episode = next,
            )
            while (seconds > 0) {
                delay(1_000)
                seconds--
                val shown = _nextEpisode.value ?: return@launchSafely
                _nextEpisode.value = shown.copy(secondsLeft = seconds)
            }
            val prompt = _nextEpisode.value ?: return@launchSafely
            _nextEpisode.value = null
            persistResume()
            playEpisodeSession(prompt, playbackController.session.value)
        }
    }

    private suspend fun playEpisodeSession(prompt: NextEpisodePrompt, current: PlaybackSession?) {
        val ep = prompt.episode
        val paddedSeason = ep.seasonNum.toString().padStart(2, '0')
        val paddedEpisode = ep.episodeNum.toString().padStart(2, '0')
        val playback = iptvRepository.resolveEpisodePlayback(ep.streamId, ep.containerExtension)
        playbackController.play(
            PlaybackSession(
                type = ContentType.EPISODE,
                streamId = ep.streamId,
                title = prompt.seriesName,
                subtitle = "S${ep.seasonNum} E${ep.episodeNum} ${ep.title}",
                url = playback.url,
                posterUrl = current?.posterUrl,
                contentKey = "ep_${ep.streamId}",
                startPositionMs = 0,
                season = ep.seasonNum,
                episode = ep.episodeNum,
                seriesId = prompt.seriesId,
                filename = "${prompt.seriesName}.S${paddedSeason}E$paddedEpisode",
                containerExtension = playback.containerExtension,
                fallbackUrl = playback.fallbackUrl,
                headers = playback.headers,
                openedFrom = current?.openedFrom,
            ),
        )
    }

    private fun persistResume() {
        val s = playbackController.session.value ?: return
        if (s.type == ContentType.LIVE) return
        // Read now, on the calling thread: by the time the coroutine runs the player may be stopped.
        val position = playerManager.positionMs
        val duration = playerManager.durationMs
        if (duration <= 0L) return
        launchSafely("PlayerViewModel.persistResume") {
            profileRepository.saveResume(
                profileRepository.getActiveProfileId(),
                s.contentKey,
                s.type,
                position,
                duration,
                title = listOfNotNull(s.title, s.subtitle).joinToString(" · "),
                posterUrl = s.posterUrl,
                streamId = s.streamId,
                seasonNum = s.season,
                episodeNum = s.episode,
                year = s.year,
                seriesId = s.seriesId,
                filename = s.filename,
                containerExtension = s.containerExtension,
            )
        }
    }

    private suspend fun applyAutoEnglishCaptions(session: PlaybackSession) {
        val mode = runCatching { preferences.subtitleAutoSyncMode.first() }.getOrDefault("on")
        if (mode == "off") return
        playerManager.enableSubtitles()
        val preferOnline = profileRepository.getPreferExternalSubs()
        if (!preferOnline) {
            var tracks = emptyList<MediaTrack>()
            var waited = 0
            while (waited < 40) {
                if (userDisabledCaptions.get() || userPickedCaptions.get()) return
                if (playbackController.session.value?.contentKey != session.contentKey) return
                val state = playerManager.state.value
                val ready = !state.isBuffering && (state.isPlaying || state.durationMs > 0)
                if (ready) {
                    tracks = state.textTracks.filter { !isSidecarTrack(it) }
                    if (tracks.isNotEmpty()) {
                        delay(500)
                        tracks = playerManager.state.value.textTracks.filter { !isSidecarTrack(it) }
                        break
                    }
                }
                delay(200)
                waited++
            }
            if (userDisabledCaptions.get() || userPickedCaptions.get()) return
            if (playbackController.session.value?.contentKey != session.contentKey) return
            tracks = playerManager.state.value.textTracks.filter { !isSidecarTrack(it) }
            val embedded = pickEnglishEmbedded(tracks)
            if (embedded != null) {
                playerManager.selectTextTrack(embedded)
                return
            }
        } else {
            var waited = 0
            while (waited < 15) {
                if (userDisabledCaptions.get() || userPickedCaptions.get()) return
                if (playbackController.session.value?.contentKey != session.contentKey) return
                val state = playerManager.state.value
                if (!state.isBuffering && (state.isPlaying || state.durationMs > 0)) break
                delay(200)
                waited++
            }
        }
        if (userDisabledCaptions.get() || userPickedCaptions.get()) return
        if (playbackController.session.value?.contentKey != session.contentKey) return
        val cached = sidecarStore.boundFile(session.contentKey)
        if (cached != null) {
            attachSidecar(session, cached, sidecarStore.binding(session.contentKey)?.subtitleId ?: cached.name)
            return
        }
        if (sidecarStore.alreadySearchedEmpty(session.contentKey)) return
        autoFetchBestEnglish(session)
    }

    private fun isSidecarTrack(track: MediaTrack): Boolean {
        val lang = track.language?.lowercase().orEmpty()
        val label = track.label.lowercase()
        return playerManager.hasSidecarSubtitle() &&
            (lang.startsWith("en") || label.contains("english") || label.endsWith(".srt") || label.endsWith(".vtt"))
    }

    private fun pickEnglishEmbedded(tracks: List<MediaTrack>): MediaTrack? {
        val english = tracks.filter { isEnglishTrack(it) }
        return english.firstOrNull { !it.forced && !it.hearingImpaired }
            ?: english.firstOrNull { !it.forced }
            ?: english.firstOrNull()
    }

    private fun isEnglishTrack(track: MediaTrack): Boolean {
        val lang = track.language?.lowercase()?.replace('_', '-')?.trim().orEmpty()
        if (lang.startsWith("en") || lang == "eng") return true
        val label = track.label.lowercase()
        return label.contains("english") || label == "en" ||
            label.startsWith("en ") || label.startsWith("en-") || label.startsWith("eng")
    }

    private suspend fun autoFetchBestEnglish(session: PlaybackSession) {
        if (userDisabledCaptions.get() || userPickedCaptions.get()) return
        sidecarStore.sharedFetch(session.contentKey) {
            if (userDisabledCaptions.get() || userPickedCaptions.get()) return@sharedFetch null
            val typed = defaultSubtitleQuery(session).trim()
            if (typed.isBlank()) return@sharedFetch null
            val language = runCatching { profileRepository.getActiveSubtitleLanguage() }.getOrDefault("en")
            val query = buildSubtitleQuery(session, typed, language)
            val results = try {
                subtitleCatalog.search(query)
            } catch (_: SubtitleException) {
                return@sharedFetch null
            }
            val best = results.maxByOrNull { it.matchScore }
            if (best == null) {
                sidecarStore.rememberEmpty(session.contentKey)
                return@sharedFetch null
            }
            val dir = File(context.filesDir, "subtitles")
            val file = try {
                subtitleCatalog.download(best, dir, episodeOf(session))
            } catch (_: SubtitleException) {
                return@sharedFetch null
            }
            // The user may have picked their own file while this download ran: theirs wins.
            if (userDisabledCaptions.get() || userPickedCaptions.get()) return@sharedFetch null
            sidecarStore.remember(session.contentKey, file, best.id, best.language)
            file
        }?.let { file ->
            if (userDisabledCaptions.get() || userPickedCaptions.get()) return
            val latest = playbackController.session.value ?: return
            if (latest.contentKey != session.contentKey) return
            val subtitleId = sidecarStore.binding(latest.contentKey)?.subtitleId ?: file.name
            attachSidecar(latest, file, subtitleId)
        }
    }

    private suspend fun attachSidecar(session: PlaybackSession, file: File, subtitleId: String) {
        val usable = withContext(Dispatchers.IO) { file.exists() && file.length() >= 40 }
        if (!usable) return
        bindSidecarFile(file, subtitleId)
        withContext(Dispatchers.Main) {
            playerManager.playWithSubtitle(session.url, file)
        }
        val restored = autoSyncEngine.getSavedOffset(session.contentKey, subtitleId)
            ?: autoSyncEngine.getSavedOffset(session.contentKey, file.name)
        if (restored != null && restored != 0L) {
            playerManager.setSubtitleDelay(restored)
        } else {
            startLiveAlign(openTimingIfFailed = false)
        }
    }

    private suspend fun bindSidecarFile(file: File, subtitleId: String) {
        loadedSubtitle = file
        loadedSubtitleId = subtitleId
        parsedSidecarCues = withContext(Dispatchers.IO) { autoSyncEngine.parseSrt(file) }
        showSidecarOverlay.set(true)
        userDisabledCaptions.set(false)
        updateCaptionLines()
    }

    private fun updateCaptionLines() {
        // VLC burns the sidecar SPU into the video itself (with its own delay), so the
        // Compose overlay is Media3-only; otherwise every line would appear twice.
        if (!showSidecarOverlay.get() ||
            userDisabledCaptions.get() ||
            playerManager.state.value.engine == com.iptv.tv.player.PlayerEngine.VLC ||
            !playerManager.state.value.subtitlesEnabled ||
            parsedSidecarCues.isEmpty()
        ) {
            if (_captionLines.value.isNotEmpty()) _captionLines.value = emptyList()
            return
        }
        val adjusted = playerManager.positionMs - playerManager.getSubtitleDelay()
        val lines = parsedSidecarCues
            .filter { adjusted >= it.startMs && adjusted < it.endMs }
            .map { cue -> cue.text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
        if (lines != _captionLines.value) _captionLines.value = lines
    }

    private fun startLiveAlign(openTimingIfFailed: Boolean) {
        val file = loadedSubtitle ?: return
        val session = playbackController.session.value ?: return
        if (session.type == ContentType.LIVE) return
        alignJob?.cancel()
        userSetDelay.set(false)
        alignJob = launchSafely("PlayerViewModel.liveAlign", onError = {
            _syncStatus.value = "Could not auto-sync. Minus if the lines are late."
            if (openTimingIfFailed) _showTimingAfterLoad.value = true
        }) {
            val mode = runCatching { preferences.subtitleAutoSyncMode.first() }.getOrDefault("on")
            if (mode == "off") {
                _syncStatus.value = "Auto Sync is off. Minus if the lines are late."
                if (openTimingIfFailed) _showTimingAfterLoad.value = true
                return@launchSafely
            }
            var waited = 0
            while (waited < 30) {
                val state = playerManager.state.value
                if (state.isPlaying && !state.isBuffering) break
                delay(100)
                waited++
            }
            val cues = parsedSidecarCues.ifEmpty {
                withContext(Dispatchers.IO) { autoSyncEngine.parseSrt(file) }
            }
            val offset = liveAligner.estimateOffset(
                cues = cues,
                appliedDelayMs = playerManager.getSubtitleDelay(),
                positionMs = { playerManager.positionMs },
                isPlaying = {
                    val state = playerManager.state.value
                    state.isPlaying && !state.isBuffering
                },
                rms = { playerManager.audioLevel },
            )
            if (userSetDelay.get()) return@launchSafely
            if (offset == null) {
                _syncStatus.value = "Could not auto-sync. Minus if the lines are late."
                if (openTimingIfFailed) _showTimingAfterLoad.value = true
                return@launchSafely
            }
            if (offset == 0L) {
                _syncStatus.value = "Subtitles look in time"
                return@launchSafely
            }
            playerManager.setSubtitleDelay(offset)
            persistDelay(offset)
            val seconds = "%.2f".format(kotlin.math.abs(offset) / 1000.0)
            val direction = if (offset < 0) "pulled forward" else "pushed back"
            _syncStatus.value = "Auto-synced · $direction ${seconds}s"
            _onlineSubtitles.value = _onlineSubtitles.value.copy(status = _syncStatus.value)
        }
    }

    private fun subtitleOffsetId(): String =
        loadedSubtitleId ?: loadedSubtitle?.name.orEmpty()

    private fun persistDelay(offsetMs: Long) {
        val s = playbackController.session.value ?: return
        val id = subtitleOffsetId().ifBlank { return }
        launchSafely("PlayerViewModel.saveDelay") {
            autoSyncEngine.saveOffset(s.contentKey, id, offsetMs)
        }
    }

    private suspend fun restoreAudioTrack(contentKey: String) {
        val wanted = preferences.audioTrackPrefs.first()[contentKey] ?: return
        repeat(25) {
            if (playbackController.session.value?.contentKey != contentKey) return
            val track = playerManager.state.value.audioTracks.find { it.id == wanted }
            if (track != null) {
                playerManager.selectAudioTrack(track)
                return
            }
            delay(200)
        }
    }

    private fun showWarning(message: String) {
        warningJob?.cancel()
        _warning.value = message
        warningJob = launchSafely("PlayerViewModel.warningTimeout") {
            delay(6_000)
            if (_warning.value == message) _warning.value = null
        }
    }

    private fun defaultSubtitleQuery(session: PlaybackSession): String {
        val liveTitle = if (session.type == ContentType.LIVE) _now.value?.title else null
        val title = liveTitle?.takeIf { it.isNotBlank() }
            ?: session.title.takeIf { it.isNotBlank() }
            ?: session.filename.orEmpty()
        return SubtitleMatch.suggestedQuery(title, session.year, session.season, session.episode)
    }

    private suspend fun buildSubtitleQuery(
        session: PlaybackSession,
        typed: String,
        language: String,
    ): SubtitleQuery {
        val defaultQuery = defaultSubtitleQuery(session)
        val custom = typed.isNotBlank() && !typed.equals(defaultQuery, ignoreCase = true)
        var year = session.year ?: yearFrom(session.title)
        var season = session.season
        var episode = session.episode
        var imdb = session.imdbId
        var tmdb = session.tmdbId
        var title = typed.ifBlank { session.title }
        when (session.type) {
            ContentType.MOVIE -> {
                val meta = runCatching { iptvRepository.getVodMetadata(session.streamId) }.getOrNull()
                year = year ?: meta?.year
                imdb = imdb ?: meta?.imdbId
                tmdb = tmdb ?: meta?.tmdbId
                if (!custom) title = meta?.name?.takeIf { it.isNotBlank() } ?: title
            }
            ContentType.EPISODE -> {
                session.seriesId?.let { seriesId ->
                    val meta = runCatching { iptvRepository.getSeriesMetadata(seriesId) }.getOrNull()
                    year = year ?: meta?.year
                    imdb = imdb ?: meta?.imdbId
                    tmdb = tmdb ?: meta?.tmdbId
                    if (!custom) title = meta?.name?.takeIf { it.isNotBlank() } ?: title
                }
                if (season == null || episode == null) {
                    parseSeasonEpisode(session.subtitle ?: typed)?.let { (parsedSeason, parsedEpisode) ->
                        season = season ?: parsedSeason
                        episode = episode ?: parsedEpisode
                    }
                }
            }
            ContentType.LIVE -> {
                if (!custom) title = _now.value?.title?.takeIf { it.isNotBlank() } ?: session.title
            }
        }
        return SubtitleQuery(
            title = title.trim(),
            year = year,
            season = season,
            episode = episode,
            filename = session.filename,
            type = if (session.type == ContentType.LIVE) ContentType.MOVIE else session.type,
            imdbId = if (custom) null else imdb,
            tmdbId = if (custom) null else tmdb,
            language = language.ifBlank { "en" },
        )
    }

    private fun episodeOf(session: PlaybackSession): Pair<Int, Int>? {
        if (session.type != ContentType.EPISODE) return null
        val season = session.season
        val episode = session.episode
        if (season != null && episode != null) return season to episode
        return parseSeasonEpisode(session.subtitle ?: session.filename.orEmpty())
    }

    private fun yearFrom(title: String): String? =
        Regex("""\(((?:19|20)\d{2})\)""").find(title)?.groupValues?.getOrNull(1)

    private fun parseSeasonEpisode(text: String): Pair<Int, Int>? {
        val match = Regex("""S(\d{1,2})\s*E(\d{1,3})""", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("""(\d{1,2})x(\d{1,3})""", RegexOption.IGNORE_CASE).find(text)
            ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    companion object {
        private const val MAX_SUBTITLE_BYTES = 8L * 1024L * 1024L
        private const val NEXT_EPISODE_COUNTDOWN_SEC = 5
        private val SUPPORTED_SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ttml", "xml")
    }
}
