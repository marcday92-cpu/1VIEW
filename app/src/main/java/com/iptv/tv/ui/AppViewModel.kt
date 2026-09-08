package com.iptv.tv.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.iptv.tv.data.credentials.CredentialsStore
import com.iptv.tv.data.parental.ParentalLock
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.AdultContent
import com.iptv.tv.domain.model.AppFeature
import com.iptv.tv.domain.model.Profile
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackOpenedFrom
import com.iptv.tv.service.ArchiveOrgRefreshWorker
import com.iptv.tv.service.EpgRefreshWorker
import com.iptv.tv.service.IptvOrgRefreshWorker
import com.iptv.tv.service.RecordingManager
import com.iptv.tv.ui.theme.DefaultAccentArgb
import com.iptv.tv.ui.theme.LegacyBlueAccentArgb
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppUiState(
    val checking: Boolean = true,
    val profileChosen: Boolean = false,
    val providerConnected: Boolean = false,
    val accent: Long = DefaultAccentArgb,
    val startTab: String = "home",
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val iptvRepository: IptvRepository,
    private val profileRepository: ProfileRepository,
    private val preferences: AppPreferences,
    private val playbackController: PlaybackController,
    private val recordingManager: RecordingManager,
    private val parentalLock: ParentalLock,
    private val epgRepository: com.iptv.tv.data.repository.EpgRepository,
    private val sidecarStore: com.iptv.tv.subtitle.SubtitleSidecarStore,
    private val updateChecker: com.iptv.tv.update.UpdateChecker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val workManager by lazy { WorkManager.getInstance(context) }

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    val profiles: StateFlow<List<Profile>> = profileRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfile: StateFlow<Profile?> = profileRepository.observeActiveProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Sections switched on in Settings → Manage features (Live off also hides Catch-up, Guide, Multi, Recordings). */
    val enabledFeatures: StateFlow<Set<AppFeature>> = preferences.disabledFeatures
        .map { AppFeature.effectiveEnabled(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppFeature.entries.toSet())

    init {
        launchSafely("AppViewModel.bootstrap", onError = { _state.value = _state.value.copy(checking = false) }) {
            // The encrypted credential store opens the Keystore; do that off the main thread once.
            withContext(Dispatchers.IO) { credentialsStore.warmUp() }
            val storedAccent = preferences.accentColor.first()
            if (storedAccent == LegacyBlueAccentArgb) {
                preferences.setAccentColor(DefaultAccentArgb)
            }
            val defaultId = profileRepository.ensureDefaultProfile()
            if (preferences.activeProfileId.first() == null) {
                profileRepository.setActiveProfile(defaultId)
            }
            profileRepository.hydrateProfilesFromDevicePrefs()
            profileRepository.observeProfiles().first().forEach { profile ->
                profileRepository.seedCategoryLayoutIfNeeded(profile.id)
            }
            val askProfile = preferences.showProfilePicker.first()
            val features = enabledFeatures.value
            val startTab = preferences.startTab.first().takeIf { it == "live" && AppFeature.LIVE in features } ?: "home"
            // copy(), not a fresh state: a reminder deep link may already have chosen the profile.
            _state.value = _state.value.copy(
                checking = false,
                profileChosen = _state.value.profileChosen || !askProfile,
                providerConnected = credentialsStore.hasCredentials(),
                accent = profileRepository.observeAccent().first(),
                startTab = startTab,
            )
            recordingManager.reconcileInterrupted()
            // Update check: its own coroutine with a short budget, so start-up never waits for
            // GitHub and a failed automatic check stays silent.
            launchSafely("AppViewModel.updateCheck") { updateChecker.check() }
            if (AppFeature.RECORDINGS in features) recordingManager.keepAwakeForRecordings()
            refreshFeeds()
            launchSafely("AppViewModel.pruneSubtitles") {
                sidecarStore.prune(java.io.File(context.filesDir, "subtitles"))
            }
            if (AppFeature.LIVE in features && preferences.launchToLastChannel.first()) {
                val lastId = preferences.lastChannelStreamId.first()
                if (lastId != null && playbackController.session.value == null) handleWatchNow(lastId)
            }
        }
        launchSafely("AppViewModel.observeAccent") {
            profileRepository.observeAccent().collect { color ->
                _state.value = _state.value.copy(accent = color)
            }
        }
    }

    fun onProfileSelected(id: Long) {
        launchSafely("AppViewModel.onProfileSelected") {
            profileRepository.setActiveProfile(id)
            parentalLock.lock()
            _state.value = _state.value.copy(profileChosen = true)
        }
    }

    fun createProfile(name: String) {
        launchSafely("AppViewModel.createProfile") {
            val id = profileRepository.createProfile(name, DefaultAccentArgb)
            onProfileSelected(id)
        }
    }

    fun renameProfile(id: Long, name: String) {
        launchSafely("AppViewModel.renameProfile") {
            profileRepository.renameProfile(id, name)
        }
    }

    fun showProfilePicker() {
        _state.value = _state.value.copy(profileChosen = false)
    }

    fun handleWatchNow(streamId: Int) {
        _state.value = _state.value.copy(
            providerConnected = credentialsStore.hasCredentials(),
            profileChosen = true,
        )
        launchSafely("AppViewModel.handleWatchNow") {
            val channel = iptvRepository.getChannel(streamId) ?: return@launchSafely
            if (parentalLock.adultBlocked() && AdultContent.isAdultChannel(channel)) return@launchSafely
            playbackController.playLive(
                channel,
                iptvRepository.livePlaybackUrl(channel),
                emptyList(),
                openedFrom = PlaybackOpenedFrom.LIVE,
                liveRailId = channel.categoryId.takeIf { it.isNotBlank() }?.let { "cat_$it" },
            )
        }
    }

    fun logout() {
        credentialsStore.clearCredentials()
        _state.value = _state.value.copy(providerConnected = false)
        launchSafely("AppViewModel.logout") {
            iptvRepository.clearProviderCatalog()
        }
    }

    /** Settings signed in: pull the account's lists here so leaving Settings cannot cancel it. */
    fun onLoggedIn() {
        if (_state.value.providerConnected) return
        _state.value = _state.value.copy(providerConnected = true)
        launchSafely("AppViewModel.refreshAfterLogin") {
            profileRepository.seedCategoryLayoutIfNeeded(profileRepository.getActiveProfileId())
            iptvRepository.refreshAllFeeds()
            if (AppFeature.LIVE in enabledFeatures.value) {
                epgRepository.refreshEpg()
                EpgRefreshWorker.enqueue(workManager)
            }
        }
    }

    private fun refreshFeeds() {
        val features = enabledFeatures.value
        val vodWanted = AppFeature.MOVIES in features || AppFeature.SERIES in features
        if (vodWanted) {
            launchSafely("AppViewModel.refreshArchive") {
                iptvRepository.refreshArchiveVodFeed()
            }
        }
        launchSafely("AppViewModel.refreshFeeds") {
            try {
                iptvRepository.refreshAllFeeds()
            } finally {
                if (AppFeature.LIVE in features) {
                    if (credentialsStore.hasCredentials()) EpgRefreshWorker.enqueue(workManager)
                    IptvOrgRefreshWorker.enqueue(workManager)
                }
                if (vodWanted) ArchiveOrgRefreshWorker.enqueue(workManager)
            }
        }
    }
}
